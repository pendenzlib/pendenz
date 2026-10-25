# Spring

Two modules, both optional ([ADR 0023](adr/0023-spring-boot-starter.md)):

- `pendenz-spring-boot-starter`: auto-configuration for Spring Boot 4.
- `pendenz-spring`: the transaction-joining connection provider alone, for
  Spring Boot 3 and for Spring without Boot.

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
  clock, cycles, resource limits. `TaskListener` beans and one
  `Permissions` bean are picked up.
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
before the web server's graceful shutdown. `pendenz.worker.*` maps onto
the worker's options; `pendenz.worker.shutdown-timeout` defaults to 20
seconds and must stay below `spring.lifecycle.timeout-per-shutdown-phase`.
`pendenz.worker.enabled=false` runs a node that only enqueues. A
`ClaimStrategy` bean replaces the default `FairClaim`, as a `WakeUp` bean
replaces polling.

## Startup checks

Startup fails, with a message saying why, when the table lacks columns,
the transaction manager does not manage the data source, beans are
declared for another id type, or a handler named by its bean type has no
bean or more than one. `pendenz.schema-check=false` turns the table check off.

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

## Tests

In your tests, set `pendenz.worker.enabled=false` and call
`pendenz.runPending()` where a task should run. A test then decides when
work happens, and Spring's cached test contexts leave no worker behind.

## Metrics and LISTEN/NOTIFY

- With `pendenz-micrometer` on the classpath and a `MeterRegistry`, the
  metrics listener is registered; `pendenz.metrics.backlog-interval`
  turns on the backlog gauges. See [`metrics.md`](metrics.md).
- With `pendenz-postgres-notify` on the classpath,
  `pendenz.worker.notify=true` wakes the worker through `LISTEN`/`NOTIFY`.
  The trigger comes from `PostgresDdl.notifyTrigger` in your migration.
  The listener holds one connection of the data source's pool for as
  long as the application runs; a node with the worker switched off
  starts no listener.

## Without Boot

On Spring Boot 3, or Spring without Boot, `pendenz-spring` gives you the
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
