# Metrics and logs

## Listener

Metrics and logs hook in through a `TaskListener` with empty defaults;
implement what you need and register it with `listener(...)` on the
builder. Exceptions thrown by a listener are logged and swallowed.

```kotlin
object InvoiceMetrics : TaskListener<Long> {
    override fun onSucceeded(task: TaskContext<Long>, duration: Duration) = timer.record(duration)
    override fun onFailed(task: TaskContext<Long>, error: Throwable) = failures.increment()
}
```

`onEnqueued` fires when the row is inserted, before the caller's
transaction commits; a rolled-back enqueue is still reported.

## Micrometer

With Micrometer, `pendenz-micrometer` does this for you: timers for the wait
before a claim and for every run by outcome, counters for enqueues and
approvals, and, if you ask for them, gauges for what waits in the table,
read in your scope so a scrape never queries the database:

```kotlin
val metrics = PendenzMetrics<Long>(meterRegistry, Tags.of("queue", "billing"))
// on the builder: listener(metrics)
metrics.watchBacklog(pendenz, applicationScope, every = Duration.ofSeconds(30))
```

The meters, their tags and why some tags are left out are listed in
[ADR 0022](adr/0022-micrometer-module.md). With the Spring Boot starter
and a `MeterRegistry`, the listener is registered for you;
`pendenz.metrics.backlog-interval` turns on the gauges, see
[`spring.md`](spring.md).
