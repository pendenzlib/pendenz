# An outcome that cannot be written is a failed attempt

- Status: proposed
- Date: 2026-11-17

## Context

When a handler has ended, the worker writes the outcome in one
transaction: the task's row, and with it whatever belongs to the
outcome. For `SUCCEEDED` that is the follow-ups the handler enqueued
(ADR 0019) and the cycles it started (ADR 0047); for a final outcome of
an occurrence, the schedule's next one (ADR 0033); for a resource the
handler reported unavailable, the block (ADR 0035).

A write that fails is repeated, with pauses from 100 ms upwards, for
half the worker's lock timeout, so that a short interruption of the
database does not lose work that was done. After that the worker logs
the exception and lets the task go. The row stays `RUNNING`. Its lock
runs out, another poll finds it and records a failed attempt with a
`LockExpiredException` (ADR 0026), and the retry policy decides.

That path was built for a database that is unreachable. It is also the
path of a write the database refuses every time while it is fine:

- a follow-up that violates a constraint of the application, such as a
  foreign key or a `CHECK` on a payload column;
- a cycle query that is wrong: a missing table, a column of the wrong
  type (ADR 0047, decision 7);
- a result or an error text the task table refuses, for example by a
  length limit the application put on the column.

For these, four things are off. The numbers are from the measurements
below.

- **The stored error names the wrong cause.** `last_error` reads `lock
  expired: worker billing-1 stopped confirming its lock`, although the
  worker is alive. The exception of the write is in that worker's log
  only.
- **It takes long.** With the default options a task whose follow-up
  violates a constraint was `FAILED` after 85 s, per attempt.
- **The task keeps its place in the worker meanwhile.** While the write
  is repeated, the task holds one of the worker's `parallelism` permits.
  Twenty such tasks delayed twenty tasks of another type from 0.4 s to
  6.5 s, with a lock timeout of 4 s; the delay grows with the lock
  timeout.
- **The listener reports a crash.** `onRetryScheduled` and `onFailed`
  carry a `LockExpiredException`; a metric or an alert on expired locks
  counts a worker that died.

What does not change with any text: the handler has run, and what it
did outside the database has happened.

### Measured before the decision

Measured on 2026-11-17 against PostgreSQL 17 with today's code. The
task table has a `CHECK` that refuses the type of the follow-up the
handler enqueues, so the write of the `SUCCEEDED` outcome fails every
time. Poll interval 50 ms.

- **Default options** (heartbeat 5 s, lock timeout 60 s), no retry: the
  task was `FAILED` after 85 s with one handler run, `last_error` and
  the listener's `onFailed` named a `LockExpiredException`.
- **Lock timeout 2 s**, heartbeat 200 ms: `FAILED` after 2.7 s without a
  retry. With three attempts and 10 ms between them: `FAILED` after
  8.1 s, three handler runs, the listener heard two `onRetryScheduled`
  and one `onFailed`, each with a `LockExpiredException`.
- **Permits.** One worker, parallelism 4, lock timeout 4 s, twenty tasks
  of the refused kind and twenty tasks of another type with an empty
  handler, enqueued together. The twenty other tasks were done after
  6.5 s, the twenty refused ones `FAILED` after 11.7 s. With a handler
  that throws instead, all forty were done after 0.35 s.
- **A handler's own failure that cannot be stored.** A `CHECK
  (length(last_error) <= 200)` on the table and a handler that throws
  with a message of 300 characters, lock timeout 2 s: `FAILED` after
  2.7 s with `lock expired: ...` in `last_error`. The handler's
  exception reached neither the row nor the listener.
- **What the driver reports.** pgjdbc throws a `PSQLException` whose
  message has the error on the first line and the values on the
  following ones:

  | Case | SQL state | Message |
  |---|---|---|
  | foreign key | `23503` | `ERROR: insert or update on table "child" violates foreign key constraint "child_invoice_id_fkey"` / `Detail: Key (invoice_id)=(4711) is not present in table "invoice".` |
  | check | `23514` | `ERROR: new row for relation "child" violates check constraint "child_amount_check"` / `Detail: Failing row contains (2, null, a, -5).` |
  | not null | `23502` | `ERROR: null value in column "amount" of relation "child" violates not-null constraint` / `Detail: Failing row contains (3, null, a, null).` |
  | unique | `23505` | `ERROR: duplicate key value violates unique constraint "child_pkey"` / `Detail: Key (id)=(5) already exists.` |
  | value too long | `22001` | `ERROR: value too long for type character varying(5)` |
  | wrong type | `22P02` | `ERROR: invalid input syntax for type bigint: "abc"` |
  | missing table | `42P01` | `ERROR: relation "invoice_missing" does not exist` / `Position: 57` |

- **The in-memory store.** Behind a store of the test whose `insertAll`
  throws for a follow-up, `runPending` returned after 0.2 s; the task
  was `SUCCEEDED`, its follow-up did not exist, and the listener heard
  `onOutcomeDropped` and no `onSucceeded`. The in-memory store has no
  rollback: the first write finished the row before the insert threw,
  and the repeated write found the row changed.

Read from the code, not measured: the maintenance sweep gives a
schedule its next occurrence when the newest one is `FAILED`
(`Occurrences.ensure`), and `pendenz-micrometer` tags the execution
timer with the simple class name of the exception.

## Decision

When the write of an outcome fails, the worker decides by the kind of
failure how long it repeats the write, and when it gives up it records
the attempt itself instead of leaving the row to its lock.

### How long the write is repeated

- **A statement the database refuses** is repeated for
  `refusedWriteRetry`, 300 ms by default: two repeats, after pauses of
  100 ms and 200 ms. That is an exception with a
  `java.sql.SQLException` in its cause chain whose SQL state begins
  with `22` (data exception), `23` (integrity constraint violation) or
  `42` (syntax error or access rule violation). The classes are those
  of the SQL standard, read through `SQLException.getSQLState()`; no
  method of `SqlDialect` is involved. The default covers a row that
  another transaction commits a moment later.
- **Everything else** is repeated for half the lock timeout, as today:
  a connection that broke, a pool that is exhausted, a deadlock, a
  serialization failure, and any exception of a store that is not on
  JDBC.

`refusedWriteRetry` is an option of the worker and of the task type,
like the execution timeout (ADR 0039):

- `WorkerOptions.refusedWriteRetry: Duration`, 300 ms by default, at
  most half the lock timeout; a longer value is refused when the
  options are built. Zero writes once.
- `TaskType.refusedWriteRetry: Duration?`, `null` by default, which
  leaves it to the worker's value. A type's value above half the lock
  timeout of the worker that registered it is refused when that worker
  starts.

While the write is repeated, the handler's result and its follow-ups
are kept and the task holds its permit. A type whose handler is costly
to run again sets a long value: an operator who removes the cause
within it, by a migration or by correcting a row, lets the same write
succeed without another run of the handler. Half the lock timeout is
today's behaviour for that type. The price is the permit, as measured
above; it is paid by the types that ask for it.

### What is recorded after giving up

The worker opens a new transaction and writes the task's row alone,
checked against the version it claimed like every outcome. No
follow-ups, no cycles, no resource block. What it writes depends on how
the handler had ended:

- **The handler had thrown** and the write of that failure failed. The
  decision already taken stands: `RETRY_PENDING` with the same run time,
  or `FAILED`. The retry policy is not asked a second time. The text is
  the class name of the handler's exception and nothing else, the rule
  of ADR 0043 for a description that throws, because the text is the
  one part of that write the application's table can refuse. The
  listener hears `onRetryScheduled` or `onFailed` with the handler's
  exception, as for any failure.
- **Otherwise**, for an outcome of `SUCCEEDED`, `REJECTED`, a deferral
  or a hand-over, the attempt is a failed attempt with an
  `OutcomeNotRecordedException` as its cause. The retry policy is
  asked, as for an expired lock (ADR 0026), and the listener hears
  `onRetryScheduled` or `onFailed` with that exception. The event of
  the intended outcome is not delivered, and neither are `onEnqueued`
  or `onCycleStarted`: none of it was stored.

When this second write fails too, nothing changes from today: the
exception is logged at ERROR, the row stays `RUNNING`, and the lock
expires. That is the case of a database that is unreachable. When the
row moved on meanwhile, because an operator cancelled or restarted the
task, the write applies nothing and the outcome is dropped with
`onOutcomeDropped`, as today.

After a second write that recorded `FAILED` for an occurrence of a
schedule, the worker gives the schedule its next occurrence in a
transaction of its own. A failure there is logged; the maintenance
sweep does the same later (ADR 0033).

### The exception and its text

`OutcomeNotRecordedException` is a public class in the core next to
`LockExpiredException`. Its `cause` is the exception of the last write.
Its message names the status the outcome would have set and the first
line of the cause's message:

```
outcome SUCCEEDED could not be recorded: org.postgresql.util.PSQLException: ERROR: insert or update on table "billing_task" violates foreign key constraint "billing_task_invoice_fkey"
```

The first line carries the constraint or the relation, the following
lines the values of the row, as measured above. An `ErrorDescription`
receives the exception (ADR 0043) and reads the whole message from its
`cause` when the application wants it in `last_error`.

### The built-in retry policy gives up

`ExponentialBackoff` returns no next attempt for an
`OutcomeNotRecordedException`: the task is `FAILED`. When the second
write succeeds, the database was reachable and had refused the first
one, and running the handler again repeats what it did outside the
database for a write that is refused again. A policy of the application
decides for itself:

```kotlin
RetryPolicy { failure ->
    if (failure.cause is OutcomeNotRecordedException) failure.failedAt.plus(Duration.ofMinutes(5))
    else backoff.nextAttemptAt(failure)
}
```

An operator who has removed the cause brings the task back with
`restart`; the handler then runs again.

### What stays as it is

- The heartbeat beside the write. It keeps the lock while the worker
  repeats the write and while it writes the second time.
- An expired lock as the path for a worker that died, and for a
  database that stayed unreachable.
- `TaskListener`: no new event. A listener tells the case by the
  exception's type, and `pendenz-micrometer` shows it as
  `exception=OutcomeNotRecordedException` on the execution timer.
- The in-memory store. It finishes the row before a later part of the
  write throws and has no rollback (ADR 0006), so the path of this ADR is not
  reached there; the measurement above shows what happens instead.
  Whether the in-memory store should write an outcome all or nothing is
  a decision of its own.

### Tests

End-to-end on PostgreSQL, with a short lock timeout:

- a handler enqueues a follow-up that violates a foreign key: the task
  is `FAILED` under `ExponentialBackoff` after one handler run,
  `last_error` names the constraint and not the key's value, no
  follow-up exists, and the row's lock had not expired;
- a cycle query that fails: the existing test of ADR 0047 expects the
  query's error instead of `lock expired`;
- a policy of the test that retries: the handler runs again;
- twenty tasks whose write is refused beside twenty of another type:
  the others are not held up by more than the default `refusedWriteRetry`;
- a trigger that fails the write with a SQL state outside the three
  classes: the write is repeated for half the lock timeout, then the
  second write records the attempt;
- an operator cancels the task while the worker repeats the write: the
  task stays `CANCELLED`, `onOutcomeDropped` fires;
- a trigger that refuses every update of the row: the task ends by an
  expired lock, as today;
- the handler threw and its text is refused by a `CHECK`: the status
  the policy chose, the class name in `last_error`, the handler's
  exception at the listener;
- an occurrence of a schedule whose write is refused: `FAILED`, and the
  next occurrence exists without a maintenance sweep;
- an `ErrorDescription` receives the `OutcomeNotRecordedException` with
  the driver's exception as its cause.

- a task type with a long `refusedWriteRetry` whose follow-up is
  refused until the test drops the constraint: `SUCCEEDED` after one
  handler run, with its follow-up; a type without a value beside it on
  the same worker is `FAILED` meanwhile.

Unit tests: `ExponentialBackoff` with the exception as the cause; the
classification over the SQL states in the table above and over an
exception without a `SQLException`; `WorkerOptions` refuses a
`refusedWriteRetry` above half the lock timeout, and a worker refuses
to start with a type whose value is. In Java: a retry policy reads
`getCause()` of the exception, and both builders set the option.

## Rejected alternatives

- **Leave it and document it.** The stored text would still point to a
  dead worker, and the task would still hold its permit.
- **Repeat every failed write for half the lock timeout**, as the first
  draft of this ADR proposed. The text would be right after 30 s
  instead of 85 s, but a run of tasks with the same fault would hold
  the worker's permits as measured.
- **Keep the outcome in the worker and give the permit back.** After
  the short repeats the worker would free the permit and go on writing
  in the background, with the heartbeat holding the lock and the
  write's error stored in the row while it stays `RUNNING`. The
  handler's work would survive for a long window without holding up
  other tasks. It needs a bound on the outcomes a worker keeps, a rule
  for what happens at the bound, and a rule for shutdown. Not part of
  this decision; it can be added later on top of the second write
  decided here, which stays the end of the window.
- **Store the intended outcome in the database** and let an operator
  apply it once the cause is gone. It would survive a restart of the
  worker. It needs a column or a table for follow-ups and cycle starts
  that were not written, a format for them, and a transition of its
  own in the status model (ADR 0002).
- **One fixed number of repeats, without an option.** Simpler, but an
  application could not keep today's window for the types whose
  handlers are costly to run again.
- **Do not repeat a refused statement at all.** A follow-up that refers
  to a row another transaction commits a moment later fails once and
  succeeds on the next write.
- **Ask the dialect whether an exception is permanent.** A method on
  `SqlDialect` for every database and driver. The three classes are
  standard SQL states; a database that reports a refusal under another
  class keeps today's repeats, which is slow and not wrong.
- **Ask the retry policy and let `ExponentialBackoff` retry.**
  Consistent with any other failed attempt. But the default would run a
  handler five times for a write that is refused five times.
- **`FAILED` at once, without the retry policy.** The same default, but
  an application whose write can succeed later, because another system
  delivers the referenced row, could not choose to retry.
- **Ask the policy again when the handler had thrown.** One attempt
  would get two decisions that can contradict each other, and a policy
  that had given up could be overruled by its second answer.
- **Store the whole message of the write's exception.** The values of
  the refused row would reach `last_error`, which operators, views,
  `tasks()` and agents read, from an exception the application's code
  did not throw. An `ErrorDescription` can still choose that.
- **A property for the intended status on the exception.** A deferral
  has no status of its own; the message names what was intended, and a
  policy needs the type and the cause.
- **Write the outcome without the part that failed**, for example with
  a savepoint per follow-up. A task would be `SUCCEEDED` with some of
  its follow-ups missing. ADR 0019 promises that the outcome and its
  follow-ups are stored together or not at all.
- **A listener event of its own**, `onOutcomeNotRecorded`. The attempt
  ends in the same two statuses as any failed attempt; the exception's
  type carries the difference.

## Consequences

- With the default, a refused write shows in the table after about 0.3 s
  plus the second write, with the constraint or relation in
  `last_error`, and the task gives its permit back then.
- A task whose handler succeeded can end `FAILED` after one attempt.
  Its result is not stored and its follow-ups do not exist; what it did
  elsewhere has happened. Today the same task is retried by its policy
  after each expired lock; under `ExponentialBackoff` it now waits for
  a person.
- A refusal that would have gone away within half the lock timeout, such
  as a table a migration creates some seconds later, is now a failed
  attempt after `refusedWriteRetry`. Today the repeats would have
  outlasted it.
- With the default, the result and the follow-ups of a handler whose
  write is refused are discarded after 300 ms. Today half the lock
  timeout remains to remove the cause and have the same write succeed.
  A type keeps that window by setting `refusedWriteRetry`.
- Code that runs before the migration it needs fails its tasks one by
  one within the default, where today they queue up behind the repeats
  and succeed once the migration has run. A cycle then ends with its
  tasks `FAILED` after their handlers ran, and comes back by `restart`
  with another run of each handler.
- A resource block the handler reported is not written with the second
  write, and a deferral whose write is refused counts as an attempt.
- `last_error` can name tables and constraints of the application's
  schema that were so far in the worker's log only.
- A listener or an alert that looks for `LockExpiredException` no
  longer sees these tasks; `LockExpiredException` means again what its
  name says.
- When the handler had thrown and that failure could not be written
  for half the lock timeout, the row keeps the class name only, also
  when the database and not the text was the cause.
- The built-in policy and a policy of the application answer
  differently by default: `ExponentialBackoff` gives up, a policy that
  does not look at the cause retries.
- PostgreSQL and the in-memory mode differ for a write that fails: a
  failed attempt there, a `SUCCEEDED` task without its follow-ups here.
  The difference exists today; a test of the application that wants to
  see its policy handle the exception calls the policy directly.
- Public API: one exception class in the core, and `refusedWriteRetry`
  on `WorkerOptions`, `TaskType` and their builders, all visible in
  `core/api/*.api`; the Spring starter gains the property
  `pendenz.worker.refused-write-retry`. No change to `TaskStore`, `SqlDialect`,
  `TaskListener` or the schema. The changelog names the changed
  behaviour of `ExponentialBackoff` and of the stored text.
- Documentation: the guide's list under "When a handler fails" and its
  section on `last_error`, the KDoc of `RetryPolicy`,
  `ExponentialBackoff` and `TaskListener`, a note in ADR 0019, ADR 0026
  and ADR 0047, which describe today's path.

## Decisions to take

1. A refused statement, SQL state classes 22, 23 and 42, is repeated
   for `refusedWriteRetry`; everything else for half the lock timeout
   as today.
2. The second write contains the task's row alone; a schedule's next
   occurrence follows in a transaction of its own.
3. When the handler had thrown, its decision stands and the text is the
   exception's class name.
4. Otherwise the retry policy is asked with an
   `OutcomeNotRecordedException`, and `ExponentialBackoff` gives up.
5. The message carries the first line of the cause's message; the
   exception has no property for the intended status.
6. No new listener event.
7. The in-memory store keeps today's behaviour; whether it writes an
   outcome all or nothing is decided separately.
8. `refusedWriteRetry` is an option of the worker, 300 ms by default
   and at most half the lock timeout, and of the task type, `null` by
   default; the name is open.
