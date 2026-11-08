# Spring

Two modules, both optional ([ADR 0023](adr/0023-spring-boot-starter.md)):

- `pendenz-spring-boot-starter`: auto-configuration for Spring Boot 4.
- `pendenz-spring`: the transaction-joining connection provider alone, for
  Spring Framework 6.2 or 7 without Boot.

Spring Boot 3 is not supported: it pins Kotlin and kotlinx versions below
the ones Pendenz is built against
([ADR 0024](adr/0024-kotlin-library-versions.md)).

## Spring Boot

The starter builds the queue from your data source and joins your
transaction manager, so an enqueue inside a `@Transactional` method
commits or rolls back with the business change. Handlers are beans;
their dependencies are injected as usual:

```kotlin
@Bean
fun sendInvoiceHandler(mail: MailService): TaskRegistration<Long> =
    TaskRegistration.of(sendInvoice) { _, payload -> mail.send(payload.invoiceId) }

@Service
class Billing(private val invoices: InvoiceRepository, private val pendenz: PendenzJava<Long>) {
    @Transactional
    fun bill(invoice: Invoice) {
        invoices.save(invoice)
        pendenz.enqueue(sendInvoice, SendInvoice(invoice.id, invoice.customer))
    }
}
```

```properties
pendenz.table=billing_task
pendenz.worker.parallelism=8
```

- The service uses the blocking `PendenzJava`: a Kotlin `suspend` function
  cannot be `@Transactional` with JDBC's transaction manager.
- From Java, `TaskRegistration.blocking(sendInvoice, (ctx, payload) -> ...)`
  registers a blocking handler.
- `PendenzCustomizer` beans receive the builder for anything else: a
  clock, cycles, resource limits, resource blocks, resource rates. No
  property turns an optional table on; a customizer does, with the
  table's mapping:

  ```kotlin
  @Bean
  fun resourceBlocks(): PendenzCustomizer<Long> =
      PendenzCustomizer { it.enableResourceBlocks(ResourceBlockTable("billing_resource_block")) }
  ```

  `TaskListener` beans and one
  `Permissions` bean are picked up. Listener events of an enqueue or an
  operator action inside a Spring transaction arrive after its commit,
  and not at all on rollback ([ADR 0032](adr/0032-listener-events-after-the-commit.md)).
- `TaskInterceptor` beans enclose every handler, in `@Order` order with
  the lowest outermost; `BlockingTaskInterceptor` beans enclose the
  handlers of `TaskRegistration.blocking` on their thread. One
  `CallerDefaults` bean supplies actor and correlation id where a call
  leaves them out; two fail the start. The starter ships none of its
  own; with Spring Security it is a few lines:

  ```kotlin
  @Bean
  fun callerDefaults(): CallerDefaults =
      object : CallerDefaults {
          override fun actor(): String? = SecurityContextHolder.getContext().authentication?.name
      }
  ```

  See [`guide.md`](guide.md#around-every-handler) and [ADR 0036](adr/0036-interceptors-and-caller-defaults.md).
- One `ErrorDescription` bean decides what a failed attempt stores in
  `last_error`; two fail the start. See
  [`guide.md`](guide.md#what-last_error-says) and [ADR 0043](adr/0043-error-text-of-a-failed-attempt.md).
- The starter ships metadata for the `pendenz.*` properties, so the IDE
  completes them with their descriptions and defaults.
- `pendenz.id-type=uuid` gives the queue `uuid` ids; the default is
  `bigint`. Beans you declare for the queue, such as a `TaskTable`, must
  use the same id type, or startup fails naming the property.
- With more than one transaction manager, `pendenz.transaction-manager`
  names the bean the queue joins.
- With JPA, flush before you enqueue when the task's handler or a
  database constraint must see the entity: `repository.saveAndFlush(...)`
  or `entityManager.flush()`. The enqueue writes through the same
  connection, but JPA writes its entities only when it flushes.

## The worker

The starter runs one worker in the application's lifecycle and stops it
before the web server's graceful shutdown, in the phase
`PendenzAutoConfiguration.WORKER_PHASE`. `pendenz.worker.*` maps onto
the worker's options; `pendenz.worker.shutdown-timeout` defaults to 20
seconds and must stay below `spring.lifecycle.timeout-per-shutdown-phase`.
`pendenz.worker.enabled=false` runs a node that only enqueues. A
`ClaimStrategy` bean replaces the default `FairClaim`, as a `WakeUp` bean
replaces polling.

## Startup checks

Startup fails, with a message saying why, when a table the queue uses is
missing or lacks columns, the transaction manager does not manage the
data source, beans are declared for another id type, or a handler named
by its bean type has no bean or more than one. The tables checked are
the task table and the cycle, resource limit, resource block, resource
rate and resource start tables a customizer turned on; the message
names the `PostgresDdl` function that creates a missing one
([ADR 0037](adr/0037-schema-check-of-the-optional-tables.md),
[ADR 0040](adr/0040-rate-limits-per-resource.md)).
`pendenz.schema-check=false` turns the table check off.

## Handlers that need what enqueues

The queue is built from its registrations, so a registration that needs
a bean which injects the queue, such as the service that enqueues, closes
a circle; startup stops and says so
([ADR 0025](adr/0025-handler-beans-without-cycles.md)). Three ways out, in
this order: name the handler's bean type, and the handler is looked up
when it first runs; take the dependency as an `ObjectProvider`; or mark it
`@Lazy`.

```kotlin
@Bean
fun sendInvoiceHandler(): TaskRegistration<Long> = TaskRegistration.of(sendInvoice, SendInvoiceHandler::class.java)

@Component
class SendInvoiceHandler(private val billing: Billing) : TaskHandler<Long, SendInvoice> {
    override suspend fun handle(context: TaskContext<Long>, payload: SendInvoice) = billing.send(payload.invoiceId)
}
```

From Java, `TaskRegistration.blocking(sendInvoice, SendInvoiceHandler.class)`
names a handler that implements `JavaTaskHandler`.

## Schedules

A recurring task ([ADR 0033](adr/0033-recurring-tasks.md)) is a `ScheduleRegistration` bean. It carries
no id type; the starter registers every such bean after the
`TaskRegistration`s and before the `PendenzCustomizer`s
([ADR 0034](adr/0034-schedules-in-spring-and-micrometer.md)):

```kotlin
@Bean
fun nightlyReport(): ScheduleRegistration =
    ScheduleRegistration.of("nightly-report", nightlyReport, ReportRequest("all"), Schedule.daily(LocalTime.of(3, 0), berlin))
```

Per environment, properties switch a schedule off or move it:

```properties
pendenz.schedules.nightly-report.enabled=false
pendenz.schedules.cleanup.cron=*/5 * * * *
pendenz.schedules.cleanup.zone=UTC
```

- `enabled=false` leaves the schedule off the queue. Its open occurrence
  stays and runs once more where a worker has the type, as for a
  schedule removed from the code.
- `cron` replaces the bean's schedule with `Schedule.cron`; `zone` is
  required with it, because a zone taken from the JVM could differ
  between instances, and the fire time is what keeps them from inserting
  an occurrence twice.
- Startup fails when a property names no `ScheduleRegistration` bean,
  when `cron` and `zone` do not come together, when the parser refuses
  the expression, or when the schedule's type is not registered; the
  message names the property or the bean. A name with characters other
  than lower-case letters, digits and `-` goes in brackets, as Spring
  binds map keys: `pendenz.schedules[reports.nightly].enabled`.
- Schedules that a `PendenzCustomizer` registers keep working, but the
  properties do not reach them.

`Schedule` is a one-method interface, so Spring's `CronExpression`, with
a seconds field and `L`, `W` and `#`, fits in two lines:

```kotlin
val lastDayOfMonth = CronExpression.parse("0 0 18 L * *")
val schedule = Schedule { after -> lastDayOfMonth.next(after.atZone(berlin))?.toInstant() }
```

## Tests

In your tests, set `pendenz.worker.enabled=false` and call
`pendenz.runPending()` where a task should run. A test then decides when
work happens, and Spring's cached test contexts leave no worker behind.

## Metrics and LISTEN/NOTIFY

- With `pendenz-micrometer` on the classpath and a `MeterRegistry`, the
  metrics listener is registered; `pendenz.metrics.backlog-interval`
  turns on the backlog gauges and the gauges per schedule. See [`metrics.md`](metrics.md).
- With `pendenz-postgres-notify` on the classpath,
  `pendenz.worker.notify=true` wakes the worker through `LISTEN`/`NOTIFY`.
  The trigger comes from `PostgresDdl.notifyTrigger` in your migration.
  The listener holds one connection of the data source's pool for as
  long as the application runs; a node with the worker switched off
  starts no listener.

## Without Boot

With Spring but without Boot, `pendenz-spring` gives you the
transaction-joining provider alone:

```kotlin
Pendenz.create(table) {
    connectionProvider(SpringConnectionProvider(dataSource, transactionManager))
    // ...
}
```

It joins a running transaction on the calling thread without deciding its
outcome, refuses to write in a read-only transaction but reads in one,
refuses transactions over another data source, and runs transactions of
its own otherwise. It checks at
construction that the transaction manager manages the data source; JTA is
not supported.

[`examples/spring`](../examples/spring) is a shop built this way, see
[`examples.md`](examples.md#spring-shop).
