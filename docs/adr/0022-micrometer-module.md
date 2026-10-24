# Micrometer module: metrics from the listener and the backlog

- Status: proposed
- Date: 2026-10-24

## Context

A queue in production is watched through a few numbers: how much work
waits, how long it waits, how long it runs, how often it fails. The
core has the hook for the events behind them since v0.1: `TaskListener`
with one method per event in a task's life, empty defaults, exceptions
swallowed. The README shows a listener that records a timer, and every
user writes those lines again.

Micrometer is the metrics facade of the JVM. Spring Boot, Quarkus and
Micronaut expose a `MeterRegistry`, and from there the numbers reach
Prometheus, OpenTelemetry, Datadog and the rest. A module that turns
listener events into meters on that registry is what the planning
calls `pendenz-metrics`.

Reading the listener against that goal shows three gaps:

- **Duration is only reported for success.** `onSucceeded` carries
  it; `onRetryScheduled`, `onRejected` and `onFailed` do not. A slow
  handler that times out is exactly the one to see.
- **The wait before a task runs is not visible.** `TaskContext` does
  not carry the due time, so the time from due to claimed, the lag
  every queue dashboard shows first, cannot be computed.
- **How much is waiting is not an event.** Backlog per type and status
  is a property of the table, not of anything the worker does. A
  listener can count what went in and out of one JVM, never what the
  table holds when several workers share it, or after a restart, or
  after an operator changed rows with SQL.

The first gap could be closed inside the module by remembering the
claim time per task id. It leaks: a release that finds the row changed
fires no event, and a database error while recording the outcome fires
none either, so the entry stays forever. The clock belongs in the
context the worker already builds.

Rules of this repository that constrain the module:

- The core depends on coroutines, kotlinx-serialization, SLF4J and
  `DataSource` only (CLAUDE.md). Micrometer goes into a module.
- No process of its own (principle 4). The module schedules nothing
  outside a scope the application hands it.
- Tags multiply time series. A tag whose values grow with the data,
  such as a cycle's lane `cycle:<id>` or a resource `tenant:42`, makes
  a metrics backend fall over.

## Decision

A new module `micrometer`, artifact `pendenz-micrometer`, depending on
`io.micrometer:micrometer-core`. The core does not know it.

### Two small changes in the core

- `TaskContext` gains `dueAt`, the run time the task was claimed for,
  and `claimedAt`, the worker's clock at the claim. `ClaimedTask` gains
  `runAt`, the run time as it stood before the claim, which the claim
  query returns with the other columns. Handlers may find both useful
  too.
- `Pendenz.backlog()` returns, for every task type with open rows, the
  count per open status (`PENDING`, `SCHEDULED`, `RUNNING`,
  `AWAITING_APPROVAL`) and the due time of the oldest `PENDING` task.
  One `GROUP BY` over the claim index on PostgreSQL; the in-memory store
  answers from its rows. Final statuses are not counted: they grow
  without bound, and their counts are the counters' job.

Both go into the store contract of ADR 0006. No listener signature
changes.

### What the application writes

```kotlin
val metrics = PendenzMetrics(registry, tags = Tags.of("queue", "billing"))

val pendenz = Pendenz.builder<Long>(dataSource) {
    table(TaskTable(name = "billing_task"))
    listener(metrics)
    // ...
}

metrics.watchBacklog(pendenz, applicationScope, every = Duration.ofSeconds(30))
```

- `PendenzMetrics<ID>` is a `TaskListener<ID>`; it never looks at the
  id. The common tags tell two queues in one application apart.
- `watchBacklog` is opt-in. It queries `backlog()` in the given scope
  at the given interval and keeps the last answer; the gauges read that
  answer, so a scrape never touches the database. Without it the module
  issues no query at all.
- From Java: `new PendenzMetrics<Long>(registry)` and
  `metrics.watchBacklog(pendenzJava, executor, interval)`, proven by a
  test in Java.

### Meters

| Name | Kind | Tags | From |
|---|---|---|---|
| `pendenz.tasks.enqueued` | counter | `type` | `onEnqueued`, repeats under an idempotency key included (ADR 0009) |
| `pendenz.tasks.wait` | timer | `type` | `onClaimed`: `claimedAt - dueAt` |
| `pendenz.tasks.execution` | timer | `type`, `outcome`, `exception` | every end of a run: `succeeded`, `retry`, `rejected`, `failed`, `released`, `dropped`; duration since `claimedAt` |
| `pendenz.tasks.approvals` | counter | `type`, `decision` | `requested`, `approved`, `declined` |
| `pendenz.tasks.stale.released` | counter | | `onStaleLocksReleased` |
| `pendenz.tasks.retention` | counter | `type` | `onRetentionApplied`, by the number of rows |
| `pendenz.tasks.backlog` | gauge | `type`, `status` | `backlog()`, only with `watchBacklog` |
| `pendenz.tasks.backlog.oldest` | time gauge | `type` | age of the oldest due `PENDING` task, only with `watchBacklog` |

- `exception` is the simple class name of the error for `retry` and
  `failed`, `none` otherwise, as Micrometer's own instrumentation does.
- Outcomes are counted by the execution timer; there are no separate
  success or failure counters to keep in step with it.
- No `lane`, `resource`, `createdBy`, `actor` or task id as a tag. All
  of them grow with the data.
- No histogram or percentile configuration. That is the application's
  decision, made with a `MeterFilter` on its registry.
- A type that had rows and has none any more reports 0, so a dashboard
  shows the drain instead of a gap.

### Tests

Against `SimpleMeterRegistry` with the in-memory store and `runPending`
for the events, a fixed clock for the durations; the backlog against
PostgreSQL with rows changed by SQL behind the worker's back; one test
in Java for the facade path.

## Rejected alternatives

- **Micrometer in the core.** Principle 5; a dependency every user would
  carry for a feature some use.
- **Start times kept in the module.** No core change, but it leaks for
  every claim whose end fires no event, see Context.
- **Durations added to the listener's outcome events.** Changes four
  public signatures every existing listener overrides. Two properties on
  the context change nothing that compiles today.
- **Backlog through a `MeterBinder` that queries on every scrape.**
  Several registries or a short scrape interval multiply the queries,
  and a slow database stalls the scrape. A refresh in the application's
  scope decouples the two.
- **Backlog left to SQL.** The views `_failures`, `_stuck` and
  `_needs_human` and a Prometheus SQL exporter can do it, and remain an
  option. But depth and lag are the two numbers every alert on a queue
  starts with; a metrics module without them is half a module.
- **An in-flight gauge from claim and outcome events.** Same leak as
  start times kept in the module. The backlog's `RUNNING` count comes
  from the table and is right across all workers.
- **The Observation API** for traces and metrics in one. It wants a
  scope around the handler, which the listener does not give, and
  context propagation across coroutines. Tracing gets an ADR of its own
  when someone needs it; an around-hook in the core would be its
  subject.
- **The artifact name `pendenz-metrics`** from the planning. The other
  modules are named after what they bind to (`serialization-jackson`,
  `postgres-notify`, `mcp`); an OpenTelemetry module next to this one
  would be just as much "metrics".

## Consequences

- One more module to publish; Micrometer types (`MeterRegistry`,
  `Tags`) are part of its public API on purpose, since the registry is
  what the application hands over.
- The core's public API grows by two context properties, one query and
  one result class; `ClaimedTask` gains a constructor parameter, an API
  break for stores of one's own, accepted before 1.0 as with `TaskSql`.
- The README's listener example stays as the way without Micrometer,
  with a pointer to the module.
- `dueAt` of a task in its second attempt is the retry's run time, so
  the wait timer measures the wait for this attempt, not since enqueue.

## Open questions

1. **Core changes at all?** Without them the module times only successes
   and has no wait timer and no backlog. Proposal: both changes as
   above.
2. **Backlog in this module or left to SQL?** Proposal: in the module,
   opt-in through `watchBacklog`.
3. **Artifact name** `pendenz-micrometer` or `pendenz-metrics` as
   planned. Proposal: `pendenz-micrometer`.
4. **Micrometer version to compile against.** The module declares it as
   `api`, so the application's newer version wins. Proposal: the oldest
   line still in Micrometer's open-source support when we release,
   rather than the newest, 1.17.
5. **`exception` tag** on the execution timer: useful to tell a timeout
   from a broken payload, at the price of one series per error class.
   Proposal: keep it.
