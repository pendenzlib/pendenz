# Schedules in the Spring starter and the Micrometer module

- Status: proposed
- Date: 2026-10-30

## Context

ADR 0033 added recurring tasks to the core: a schedule is declared on the
builder with a name, a registered task type, a fixed payload and a
`Schedule`, and the queue keeps one open occurrence per schedule as an
ordinary task row. It left two things out of scope: registering schedules
from Spring beans, and a gauge of the next fire time per schedule.

**Spring.** A Spring Boot application can declare a schedule today, through
a `PendenzCustomizer`:

```kotlin
@Bean
fun nightlyReport(): PendenzCustomizer<Long> =
    PendenzCustomizer { it.schedule("nightly-report", NIGHTLY_REPORT, ReportRequest("all"), Schedule.daily(LocalTime.of(3, 0), BERLIN)) }
```

That works, but:

- The customizer carries the queue's id type, although a schedule does not
  depend on it, and a customizer declared for the other id type fails
  startup (ADR 0023).
- The starter cannot see which schedules exist, so it cannot offer what
  Spring applications configure per environment: a schedule switched off
  on a staging system, or run at another time there.
- Nothing names a schedule in the bean graph; a customizer can do
  anything to the builder.

**Metrics.** ADR 0022 counts and times tasks by type. A schedule is not a
type: several schedules may enqueue one type with different payloads, one
per tenant for instance. What an operator wants to know about a schedule
is not covered by the existing meters:

- **Is the next run where it should be?** The series repairs itself
  (ADR 0033), but only on instances with maintenance. When every worker is
  down or none runs maintenance, the open occurrence stays overdue, and
  `pendenz.tasks.backlog.oldest` shows it only per type and only with
  `watchBacklog`.
- **When did it last succeed?** A failed occurrence does not end the
  series; the next one is inserted and the failed row waits for a person.
  A report that fails every night therefore looks healthy in "next run",
  and `pendenz.tasks.execution` with `outcome=failed` counts failures per
  type, not per schedule.

The core exposes nothing about schedules after `build()`. A module that
wants to report on them has to find the occurrences itself, with
knowledge of the `schedule:` prefix that is internal to the core.

## Decision

### The core reports the state of every schedule

```kotlin
public suspend fun Pendenz<ID>.schedules(): List<ScheduleState<ID>>
```

```kotlin
public class ScheduleState<ID : Any>(
    public val name: String,
    public val type: String,
    /** The newest occurrence of the schedule's type, or null when there is none. */
    public val latest: TaskRecord<ID>?,
    /** The newest occurrence that ended SUCCEEDED, or null when retention removed it or none did. */
    public val lastSuccess: TaskRecord<ID>?,
) {
    /** The run time of [latest] while it is open; null when no occurrence is open. */
    public val next: Instant?
}
```

- One entry per schedule registered on this queue, in registration order.
  Schedules that other instances know and this one does not are not
  listed.
- Both records come from the existing `search` with a `TaskQuery` by the
  schedule's creator and type, `_creator_idx` (ADR 0028), the second one
  also by status `SUCCEEDED`. All of them run in one read transaction.
  No new store operation, no new index.
- "Open" is the set ADR 0033 defines. `next` is null when the series is
  broken and the check has not yet repaired it, when the series has
  ended, or before the first check.
- `PendenzJava.schedules()` returns the same list, proven by a test in
  Java.

The method is useful beyond metrics: an operator endpoint, a health
check, an MCP tool. It reads; it never inserts an occurrence.

### Gauges per schedule in `pendenz-micrometer`

| Name | Kind | Tags | Value |
|---|---|---|---|
| `pendenz.schedules.next` | time gauge | `schedule`, `type` | time from now until `next`; negative when the occurrence is overdue; NaN when none is open |
| `pendenz.schedules.last.success` | time gauge | `schedule`, `type` | time since `lastSuccess.finishedAt`; NaN when there is none |

- They are refreshed by `watchBacklog` and `refreshBacklog`, in the same
  call as the backlog, so one switch turns on every query-backed gauge.
  A queue without schedules issues no extra query.
- Like `pendenz.tasks.backlog.oldest`, the gauges store the instants
  read at the last refresh and compute the distance with the queue's
  `Clock` at every scrape, so an overdue occurrence keeps growing between
  refreshes.
- The `schedule` tag is the schedule's name. Names are declared in code
  and do not grow with the data, unlike lanes or creators, which ADR 0022
  keeps out of tags. An application with many schedules per tenant can
  drop the tag with a `MeterFilter`.
- A schedule that this instance does not know has no gauge; after a
  deploy that removes one, its gauges disappear with the old instance.

Typical alerts: `pendenz_schedules_next_seconds < -600` (an occurrence is
ten minutes overdue), `pendenz_schedules_last_success_seconds > 90000`
(no success in 25 hours for a nightly job).

### `ScheduleRegistration` beans in the Spring starter

```kotlin
@Bean
fun nightlyReport(): ScheduleRegistration =
    ScheduleRegistration.of("nightly-report", NIGHTLY_REPORT, ReportRequest("all"), Schedule.daily(LocalTime.of(3, 0), BERLIN))
```

```java
@Bean
ScheduleRegistration nightlyReport() {
    return ScheduleRegistration.of("nightly-report", NIGHTLY_REPORT, new ReportRequest("all"), Schedule.daily(LocalTime.of(3, 0), BERLIN));
}
```

- `ScheduleRegistration` has no id type argument. The starter collects
  every such bean and calls `Builder.schedule` for it, after the
  `TaskRegistration`s and before the `PendenzCustomizer`s, so a customizer
  still sees the whole builder.
- The core's checks apply unchanged: an unregistered type, a name used
  twice or an invalid name fails startup, and the message names the bean.
- A `PendenzCustomizer` that calls `schedule` keeps working.

### Per-schedule properties

```properties
pendenz.schedules.nightly-report.enabled=false
pendenz.schedules.cleanup.cron=*/5 * * * *
pendenz.schedules.cleanup.zone=UTC
```

- `enabled` (default `true`): `false` leaves the schedule off the builder.
  The queue then treats it as a removed schedule (ADR 0033): its open
  occurrence stays and runs once more if a worker has the type.
- `cron` with `zone` replaces the bean's `Schedule` by
  `Schedule.cron(cron, zone)`. `zone` is required with `cron`: a zone taken
  from the JVM would differ between instances and break the deduplication
  by fire time.
- A property that names no `ScheduleRegistration` bean fails startup, as
  does `cron` without `zone` or an expression the parser refuses. A typo
  in a property name must not leave a schedule running unnoticed.
- The configuration metadata gains the map, and the existing test holds it
  to the bound properties.

Schedules registered by a `PendenzCustomizer` are not affected by these
properties; the starter cannot see them.

### Tests

- Core: `schedules()` with the in-memory store and a controlled `Clock`:
  `next` before the first check, after it, after a failed occurrence and
  after an operator cancel; `lastSuccess` after a success and after
  retention removed it. Against PostgreSQL once, through the public API.
- Micrometer: the two gauges against `SimpleMeterRegistry` with a fixed
  clock, including the negative value of an overdue occurrence and NaN
  for a broken series; one test in Java.
- Starter: `ApplicationContextRunner` for collection, ordering after
  `TaskRegistration`s, every startup failure named above, `enabled=false`
  and the `cron` override; one `@SpringBootTest` in Java.

## Rejected alternatives

- **An annotation on bean methods**, like Spring's `@Scheduled`. Needs
  reflection to find and call the method, and the method would not be a
  registered task type with its retry, approval and listeners. Rejected
  for handlers in ADR 0023 for the same reasons.
- **Schedules declared entirely in properties**, type and payload
  included. The payload is typed and encoded by the task type's mapping;
  from YAML it would be an untyped map or a JSON string, and a mistake in
  it would show at the first fire time. Overriding the time of a schedule
  declared in code covers the per-environment need without that.
- **Only the `PendenzCustomizer`, documented.** Works without new API,
  but carries the id type and gives the starter no names to apply
  properties to.
- **A global `pendenz.schedules.enabled`.** It would collide with a
  schedule named `enabled` in the map binding. For tests the pattern of
  ADR 0023 suffices: a schedule's occurrence lies in the future, and
  `runPending()` runs only due tasks.
- **A Spring `CronExpression` adapter in `pendenz-spring`.** `Schedule`
  is a functional interface; the README shows the two lines that adapt
  Spring's cron, with seconds and `L`, `W`, `#`.
- **The next fire time computed from `Schedule.next(now)`** instead of
  read from the row. It would report a time even when the series is
  broken, the case the gauge is there to show.
- **The next fire time as an epoch timestamp.** Common in Prometheus
  exporters, but `pendenz.tasks.backlog.oldest` already reports a
  distance from now, and two conventions in one module are one too many.
- **A `watchSchedules` of its own.** A second interval to configure and a
  second job in the worker's scope, for gauges that answer the same kind
  of question as the backlog.
- **Gauges in the core's listener events**, without a query. The listener
  sees occurrences being enqueued and finished on this instance only, not
  the row an operator cancelled or a worker on another instance ran.

## Consequences

- The core gains `Pendenz.schedules()`, `PendenzJava.schedules()` and
  `ScheduleState`, public API. No store or dialect change.
- `pendenz-micrometer` gains two gauges and their name constants on
  `PendenzMetrics`; `watchBacklog` and `refreshBacklog` query
  `schedules()` when the queue has any.
- The starter gains `ScheduleRegistration`, public API, and the
  `pendenz.schedules.*` properties.
- The README's Spring and metrics sections and the configuration
  reference gain the new parts; ADR 0033's "out of scope" note points
  here.

## Open questions

1. **`Pendenz.schedules()` in the core**, reading with the existing
   `search`, as the source for the gauges and for anyone else? Proposal:
   yes.
2. **Two gauges, `next` and `last.success`**, or only `next`, which ADR
   0033 named? Proposal: both; `next` does not show a schedule that fails
   every run.
3. **Refreshed by `watchBacklog`**, without a switch of their own?
   Proposal: yes.
4. **The schedule's name as a tag**? Proposal: yes; the names are code.
5. **`ScheduleRegistration` beans without an id type**, applied after the
   `TaskRegistration`s and before the customizers? Proposal: yes.
6. **Per-schedule properties `enabled`, `cron` and `zone`**, with unknown
   names failing startup? Proposal: yes; `zone` required with `cron`.
