# Recurring tasks: one row ahead per schedule

- Status: accepted
- Date: 2026-10-29

## Context

The first question anyone comparing JVM task libraries asks after "can it
retry" is "can it run something every night". Quartz, db-scheduler and
JobRunr all can. Pendenz cannot, and the README lists recurring tasks as
the next thing to build when there is a need.

Today an application gets close with chaining (ADR 0019): the handler of
`nightly-report` enqueues the next `nightly-report` with a run time
tomorrow, in the outcome's transaction. That works until it does not:

- **The first occurrence comes from nowhere.** Someone has to enqueue it
  once, and every instance that starts must not enqueue it again.
- **The chain breaks on anything but success.** A follow-up is written
  only when the handler returns normally. An occurrence that ends
  `FAILED` or `REJECTED`, that an operator cancels or quarantines, or
  whose outcome is dropped because the row changed, ends the series
  without a trace. Nobody notices until the report is missing.
- **Several instances race.** Two instances that start at the same
  time and both seed the first occurrence create two series, and from
  then on every night runs twice.
- **The schedule is arithmetic in every handler.** "Tomorrow at three in
  Berlin" across a daylight-saving change is easy to get wrong, and each
  application would have to solve it on its own.

What a schedule needs from the queue is small: at any moment, the next
occurrence exists as a row, exactly once, whatever happened to the last
one. Everything else, claiming, retry, timeouts, approval, retention,
listeners, metrics, SQL visibility, is what a row already gets.

Principle 4 rules out a scheduler process or a leader; principle 2 leaves
every table to the application; the core may not take a cron library as
a dependency.

## Decision

### A schedule is declared in code, next to the registration

```kotlin
val nightlyReport = TaskType.json<ReportRequest>("nightly-report")

Pendenz.create(table) {
    dataSource(dataSource)
    register(nightlyReport, ReportHandler(reports))
    schedule(
        name = "nightly-report",
        type = nightlyReport,
        payload = ReportRequest(scope = "all"),
        schedule = Schedule.cron("0 3 * * *", ZoneId.of("Europe/Berlin")),
    )
}
```

- A schedule has a **name**, unique within the queue, a **registered task
  type**, a fixed **payload** and a **`Schedule`** that computes fire
  times. One type may have several schedules, for example one per
  tenant with different payloads.
- The builder refuses a schedule whose type is not registered, a name
  used twice, and a name outside `[A-Za-z0-9._-]`, at most 100
  characters, so that the derived values below fit their columns.
- Schedules are code. They change with a deploy, like handlers do.

### `Schedule` is an interface with three built-ins

```kotlin
public fun interface Schedule {
    /** The first fire time strictly after [after], or null when the series has ended. */
    public fun next(after: Instant): Instant?
}
```

- `next` must be a pure function of its argument: every instance must
  compute the same fire times, because the fire time is part of the
  occurrence's identity (below). An implementation that consults the
  clock or a random number breaks deduplication.
- **`Schedule.every(interval, anchor = Instant.EPOCH)`**: fire times
  `anchor + n * interval`. Anchored, not "interval after start", so that
  instances started at different moments agree. Every fifteen minutes
  fires at :00, :15, :30, :45 UTC.
- **`Schedule.daily(time, zone)`**: once a day at a local time.
- **`Schedule.cron(expression, zone)`**: the five standard fields,
  minute, hour, day of month, month, day of week, with `*`, lists,
  ranges, steps and the English names of months and days. When both day
  fields are restricted, either matching is enough, as in Vixie cron.
  No seconds field and no Quartz extensions (`?`, `L`, `W`, `#`). The
  parser is written in the core; it is small, and a dependency is not
  allowed (CLAUDE.md, ADR 0003).
- **Daylight saving**: a local time that does not exist (02:30 on the
  spring change) fires at the first instant after the gap; a local time
  that exists twice (02:30 on the autumn change) fires once, at the
  earlier instant. That is what `ZonedDateTime.of` resolves to, and
  what most people expect from "every night at 02:30".
- Returning null ends a series, which allows an end date in a schedule
  of the application's own. The built-ins never end.
- Anything the built-ins do not cover is an implementation of the
  interface: cron-utils, Spring's `CronExpression`, business calendars.
  Principle 1.

### An occurrence is an ordinary task row

The queue keeps **exactly one open occurrence ahead** per schedule:

- An occurrence is inserted with `run_at` set to its fire time, so it is
  `SCHEDULED` and is promoted, claimed, retried and timed out like any
  other task. The type's approval rule, resource and retention apply
  unchanged.
- **`created_by` is `schedule:<name>`**. That answers "who enqueued
  this" in SQL, in `TaskRecord` and for approval rules, and the existing
  `_creator_idx` (ADR 0028) finds a schedule's occurrences without a new
  column or index.
- **`idempotency_key` is `schedule:<name>@<fire time>`**, the fire time
  as an ISO instant in UTC. The unique index of ADR 0009 makes a second
  insert for the same fire time a no-op on every instance.
- An occurrence is **open** while it is `SCHEDULED`, `PENDING`,
  `RUNNING`, `RETRY_PENDING` or `AWAITING_APPROVAL`. While one is open,
  no further occurrence is inserted. Occurrences of one schedule
  therefore never overlap.
- `FAILED`, `REJECTED`, `QUARANTINED`, `SUCCEEDED` and `CANCELLED` are
  not open. A failed nightly report waits for a human, and tomorrow's
  report runs anyway.
- `enqueue` refuses a caller-given `createdBy` or `idempotencyKey` that
  starts with `schedule:`, as it refuses a lane that starts with
  `cycle:` (ADR 0016).

### The next occurrence is written at two places

1. **With the outcome.** When the worker records a final outcome of an
   occurrence, `SUCCEEDED`, `FAILED` or `REJECTED`, it inserts the next
   occurrence, at `schedule.next(now)`, in the same transaction, as it
   inserts follow-ups (ADR 0019). A retry is not final and inserts
   nothing; the occurrence is still open.
2. **By a check.** For every schedule, the check looks at the newest
   occurrence (`created_by = 'schedule:<name>'`, newest `created_at`
   first). When there is none, or it is not open, it inserts the
   occurrence at `schedule.next(now)`. The check runs when a worker
   starts, in every maintenance sweep (`maintenanceInterval`, one
   minute by default, ADR 0029) and at the start of `runPending`.

The first path keeps a series going without delay; the second starts it,
and repairs everything the first cannot see: an operator's cancel, a
quarantine, a dropped outcome, an expired lock that ends in `FAILED`
(ADR 0026), an occurrence deleted by hand.

Both paths take a transaction-level advisory lock per schedule before
they look, so that two instances cannot both find "nothing open" and
insert. The idempotency key stays as the second guard and as the means
for skipping (below). The in-memory store needs no lock.

### What follows from one row ahead

- **Missed fire times run once.** After a downtime, the occurrence that
  was already scheduled is overdue; it is promoted and runs once, late.
  The next one is computed from the time it finishes, so the fire times
  in between are skipped, not caught up. `TaskContext.dueAt` tells the
  handler which fire time it is running for.
- **Cancelling an occurrence skips it.** An operator cancels tonight's
  `SCHEDULED` row; the check finds it not open and computes
  `next(now)`, which is tonight's fire time again, whose key is taken,
  so nothing is inserted. Once tonight's fire time has passed, the check
  inserts tomorrow's. The series continues; one night is skipped. This
  holds as long as retention has not removed the cancelled row before
  its fire time.
- **A run longer than the interval delays the next one.** An occurrence
  every five minutes that runs for twelve finishes, and the next fire
  time after that moment is inserted. There is no queue of overdue
  occurrences.
- **An unapproved occurrence holds its series.** A type that requires
  approval leaves each occurrence in `AWAITING_APPROVAL` until someone
  decides; no further approval requests pile up meanwhile.
- **A changed schedule takes effect after the open occurrence.** A
  deploy that moves the report from 03:00 to 04:00 leaves tonight's
  03:00 row in place; the occurrence after it follows the new schedule.
  The same holds for a changed payload. An application that wants the
  change at once cancels the open row.
- **A removed schedule leaves its open occurrence.** It runs once more
  if its type is still registered, then the series ends. The queue does
  not cancel rows of schedules it does not know: during a rolling
  deploy, the old instance still knows a schedule the new one does not.

### Tests

- `Schedule` and the cron parser are unit-tested with fixed instants,
  including both daylight-saving changes in `Europe/Berlin` and a
  day-of-month and day-of-week combination.
- The behaviour above is tested through the public API with the
  in-memory store, a controlled `Clock` and `runPending`: the first
  occurrence appears, runs at its fire time and leaves the next; a
  failed occurrence leaves the next; a cancelled one is skipped; a
  downtime runs once.
- End-to-end on PostgreSQL: two `Pendenz` instances on the same table
  check concurrently and produce one occurrence per fire time.

## Rejected alternatives

- **A schedule table that operators edit with SQL.** Fits principle 3,
  and is what makes changing a schedule without a deploy possible. But
  it needs a table of the library's own, a payload stored outside a
  task row, a permission model for who may create schedules, and a
  definition of what a row in it means when no handler is registered.
  The code form can be extended by one later; the reverse is harder.
  See the open questions.
- **A leader that schedules for everyone.** Needs an election, a lease
  and a failover; the idempotency key and the advisory lock make every
  instance equal instead. Principle 4.
- **Materialising occurrences ahead**, say every fire time of the next
  day. Many rows for frequent schedules, and a changed schedule leaves
  rows that have to be found and removed.
- **Catching up every missed fire time.** A queue that was down for a
  day would run 96 quarter-hourly occurrences at once on return. The
  handler can look at `dueAt` when a missed run matters; see the open
  questions for an option.
- **Ending a series when an occurrence fails.** Turns one bad night into
  silence; the failed row already waits for a human.
- **A cron library in the core**, cron-utils or Spring's
  `CronExpression`. A dependency, or a framework; the interface lets an
  application bring either.
- **The Quartz cron dialect**, with seconds and `L`, `W`, `#`. Larger,
  less known outside Quartz, and the interface covers it for those who
  need it.
- **A new `schedule` column.** Clearer in SQL than a prefix on
  `created_by`, but a change to every mapping and one more index, for
  information `created_by` already carries.

## Consequences

- `Pendenz.Builder.schedule(...)` and `Schedule` with `every`, `daily`
  and `cron` are new public API; `PendenzJava` gains the same, and
  Java tests prove it.
- `TaskStore` gains two operations: the newest task by creator, and a
  lock per schedule (a no-op in the in-memory store). `SqlDialect` gains
  the lock statement. Custom stores and dialects have to implement them
  before 1.0.
- `enqueue` refuses the `schedule:` prefix in `createdBy` and
  `idempotencyKey`; an application that uses it today breaks.
- `Maintenance` gains a part, isolated like the others: a failing
  schedule check is logged and the sweep goes on.
- Listeners hear of an occurrence through `onEnqueued`, after the commit
  (ADR 0032). No new event.
- A worker with `maintenanceInterval` zero still checks at start and
  writes the next occurrence with the outcome; it does not repair a
  series broken by an operator. At least one instance with maintenance
  is needed for that.
- The README roadmap moves recurring tasks out of "next"; the SQL
  recipes gain "the next run of every schedule" and "skip tonight's
  run".
- Out of scope, and noted: the Spring starter registering schedules
  from beans, and a gauge of the next fire time per schedule in the
  Micrometer module.

## Decisions taken with the acceptance (2026-10-29)

1. Schedules are declared in code only; a schedule table is left for
   later.
2. Cron is in the core: five standard fields, no seconds, no Quartz
   extensions, with a parser of its own.
3. Missed fire times run once, late; the ones in between are skipped,
   and there is no option to catch them all up.
4. Occurrences never overlap: an open occurrence holds the series,
   including one in `AWAITING_APPROVAL`.
5. The check runs at worker start, with maintenance and in
   `runPending`, without an interval of its own.
6. A removed schedule's open occurrence stays and runs once more.
7. `created_by` and the idempotency key carry the schedule, and the
   `schedule:` prefix is reserved in both; there is no new column.

Noted during implementation:

- A local time that daylight saving skips fires later by the length of
  the gap, 02:30 becomes 03:30, which is what `ZonedDateTime.of` does;
  the decision above said "the first instant after the gap". For an
  hourly cron the shifted time coincides with the next hour's and fires
  once.
- The check looks at the newest task the schedule created **of the
  schedule's type**. Follow-ups inherit the creator (ADR 0019), so a
  follow-up of another type would otherwise hold the series; one of the
  same type still counts as an occurrence. An operator who restarts an
  older failed occurrence while a newer one is open runs the two side by
  side; the check only sees the newest.
- `TaskStore` gains one operation, `lockSchedule`, with a default that
  does nothing. The newest occurrence comes through the existing
  `search` with a `TaskQuery` by creator and type, served by
  `_creator_idx`. The in-memory store keeps the default and relies on the
  idempotency key.
- `TaskSql` gains `lockSchedule`: on PostgreSQL the two-key advisory lock
  with the class "PSCH" and `hashtext` of the table's OID and the
  schedule name.
- The builder encodes the payload once when the schedule is registered,
  so a payload the mapping refuses fails at start, not at the first fire
  time.
- `Builder.schedule` exists on `PendenzJava.Builder` as well.
