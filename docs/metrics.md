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

An event means a row changed, and it arrives after the commit; a
rolled-back enqueue is not reported, and neither is a repeat under an
idempotency key. Inside a Spring transaction the events wait for its
commit. Only the overloads that take a `Connection` report on return,
before the caller commits, since the queue cannot see that commit.
Operator moves arrive as `onOperatorMove`, cycle operations as one event
per operation with the number of tasks
([ADR 0032](adr/0032-listener-events-after-the-commit.md)).

## Micrometer

With Micrometer, `pendenz-micrometer` does this for you: timers for the wait
before a claim and for every run by outcome, counters for enqueues and
approvals, operator moves and cycle operations, and, if you ask for them, gauges for what waits in the table,
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
