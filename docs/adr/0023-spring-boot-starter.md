# Spring Boot starter: transactions, handlers as beans, a worker in the lifecycle

- Status: proposed
- Date: 2026-10-24

## Context

Most JVM services that would use Pendenz run on Spring Boot. Today they
can, but they write the same glue every time: a `ConnectionProvider`
that joins Spring's transaction, a bean that builds the queue, a
lifecycle that starts the worker after the context is ready and stops it
before the data source closes. The planning names a
`pendenz-spring-boot-starter`; ADR 0003 keeps Spring out of the core and
routes transaction participation through the connection abstraction for
exactly this module.

What the module must get right:

- **Transactions.** The point of enqueueing in the caller's transaction
  (principle 4) is that `enqueue` inside a `@Transactional` method
  commits or rolls back with the business change. Spring binds its
  transaction to the calling thread. The core's
  `DataSourceConnectionProvider` switches to `Dispatchers.IO`, so it
  would run on another thread and miss the transaction.
- **Workers have no Spring transaction.** Claim, heartbeat and outcome
  run on the worker's coroutines, outside any `@Transactional` method,
  and need transactions of their own.
- **Handlers are instances** (CLAUDE.md, no DI container in the core).
  In Spring they are beans with dependencies of their own; the starter
  must collect them without reflection or annotation scanning in the
  core.
- **Shutdown order.** Spring closes beans in reverse dependency order and
  runs `SmartLifecycle` phases first. The worker has to stop, with its
  `shutdownTimeout` (ADR 0005), before the data source goes away.
- **The schema is the user's** (principle 2) and there are no migrations
  of our own (principle 4). The starter creates no tables.
- **Leaving costs nothing** (principle 6). Without the starter, the same
  queue must keep working with the core's builder.

Spring Boot 4 is the current major line, with Spring Framework 7,
Jakarta EE 11 and Jackson 3, the line ADR 0012 chose for the Jackson
module. As of October 2026 the newest releases are Boot 4.1.2 and
4.0.9.

## Decision

A new module `spring-boot`, artifact `pendenz-spring-boot-starter`,
holding the auto-configuration itself; Spring's split into a starter and
an `-autoconfigure` artifact pays off for libraries with many optional
integrations, and this one has two. The core does not know it.

### Transactions

`SpringConnectionProvider` runs every block through a
`TransactionTemplate` with propagation `REQUIRED` over the application's
`PlatformTransactionManager`, and takes the connection from
`DataSourceUtils.getConnection`:

- Called inside a `@Transactional` method, it joins that transaction on
  the calling thread: the task is written, or not, with the business
  change.
- Called from a worker, there is no transaction on that thread, so the
  template starts one and commits it, as the core's provider does.
- It switches threads only when no transaction is active on the calling
  one. With an active transaction it must stay, because Spring binds the
  transaction to the thread; without one it moves the blocking JDBC work
  to `Dispatchers.IO`, as the core's provider does, so the worker's
  coroutines do not block their dispatcher.

This works with `DataSourceTransactionManager`, `JdbcTransactionManager`
and `JpaTransactionManager` (which exposes its connection to
`DataSourceUtils`), and with JTA.

A Kotlin `suspend` function cannot be `@Transactional` with an imperative
transaction manager, so Kotlin callers that want the caller's
transaction call the blocking `PendenzJava` facade inside it, or pass the
connection explicitly as today.

### Beans

- `Pendenz<Long>` and `PendenzJava<Long>`, built from the application's
  `DataSource` and transaction manager. The Java facade is there because
  half of Spring's users write Java.
- The table comes from `pendenz.table` (default `pendenz_task`); a
  `TaskTable` bean, for other column names or `UUID` ids, replaces it.
  With a `TaskTable<UUID>` bean the queue beans are `Pendenz<UUID>` and
  `PendenzJava<UUID>`.
- Every bean backs off with `@ConditionalOnMissingBean`, so an
  application that builds its own queue keeps it.

### Handlers

Handlers are registered by beans of a small type the starter provides:

```kotlin
@Bean
fun sendInvoice(mail: MailService) = TaskRegistration.of(Billing.SEND_INVOICE) { _, invoice -> mail.send(invoice) }
```

```java
@Bean
TaskRegistration<SendInvoice> sendInvoice(MailService mail) {
    return TaskRegistration.of(SEND_INVOICE, (ctx, invoice) -> mail.send(invoice));
}
```

`TaskRegistration` has overloads for a Kotlin `TaskHandler`, a Java
handler and a `ResultHandler` with its `TaskResult` (ADR 0020). The
starter collects every such bean and registers it on the builder. For
anything else on the builder (listeners, permissions, a clock, cycles,
resource limits) a `PendenzCustomizer` bean receives the builder before
it is built, the pattern Spring Boot uses for its own builders.

`TaskListener` and `Permissions` beans are picked up without a
customizer, since an application typically has one of each.

### Worker

A `SmartLifecycle` bean starts one worker when the context has started
and stops it when the context closes, in an early phase so the worker
has drained before the data source and the transaction manager close. It
owns a `CoroutineScope` on `Dispatchers.Default`, not Spring's executor.

`pendenz.worker.enabled` (default `true`) lets a deployment run nodes
that only enqueue. `pendenz.worker.*` maps onto `WorkerOptions`:
`parallelism`, `poll-interval`, `lock-timeout`, `execution-timeout`,
`shutdown-timeout` and the rest, as Spring `Duration`s.

Spring Boot's graceful shutdown (`spring.lifecycle.timeout-per-shutdown-phase`,
30 seconds by default) waits for the phase; `shutdown-timeout` stays the
worker's own limit and should be below it, which the starter checks at
startup and warns about.

### Optional integrations

- With `pendenz-micrometer` on the classpath and a `MeterRegistry` bean,
  a `PendenzMetrics` listener is registered; `pendenz.metrics.backlog-interval`
  turns on `watchBacklog` in the worker's scope (ADR 0022).
- With `pendenz-postgres-notify` on the classpath, `pendenz.worker.notify=true`
  wakes workers through `LISTEN`/`NOTIFY` (ADR 0014).

### Schema

The starter creates no tables. The README shows how to put the generated
DDL into a Flyway or Liquibase migration, as the examples already do.

### Tests

`ApplicationContextRunner` for the conditions and properties; against
PostgreSQL: a task enqueued in a `@Transactional` method that rolls back
leaves no row, one that commits is run by the worker; a handler bean
with an injected dependency runs; context close drains a running
handler. One test in Java with a `@SpringBootTest` application proves
the registration from Java. An `examples/spring` service follows as its
own step.

## Rejected alternatives

- **An annotation such as `@TaskHandler` on bean methods.** Familiar from
  `@Scheduled` and `@KafkaListener`, but it needs reflection to find and
  call the methods and loses the type link between task type and
  payload. Registration beans are explicit, typed and visible in the
  bean graph; an annotation could be added later on top of them.
- **Handlers as beans implementing `TaskHandler` directly.** The starter
  would not know which task type a handler serves without a second
  interface or a naming convention.
- **A provider that checks `TransactionSynchronizationManager` and falls
  back to the core's provider.** Works for plain JDBC, but bypasses
  the transaction manager's own settings (timeouts, isolation, JTA) for
  the worker's transactions.
- **Separate starter and `-autoconfigure` artifacts.** Spring's own
  convention, worth it once there are many optional integrations; with
  two, one artifact is easier to depend on. It can be split later
  without changing package names.
- **Spring Boot 3.** Jakarta EE 10 and Jackson 2; supporting both majors
  doubles the test matrix for a line on its way out. Boot 3 users write
  the provider by hand, as today.
- **Migrations from the starter**, such as a Flyway location with the
  task table. Breaks principle 2 and 4: the table's name, columns and
  indexes are the application's.

## Consequences

- A new published module with Spring Framework and Spring Boot as
  dependencies; the core is untouched.
- `TaskRegistration` and `PendenzCustomizer` are public API of the
  starter.
- The worker's thread use is unchanged; an enqueue inside a transaction
  runs on the caller's thread, which a `@Transactional` caller needs.
- An `examples/spring` service becomes possible, and the README gets a
  Spring section.

## Open questions

1. **Spring Boot version.** Proposal: compile against 4.0, the oldest
   line of the current major, as ADR 0022 did for Micrometer; Boot 3 is
   not supported.
2. **Handler registration.** Proposal: `TaskRegistration` beans plus a
   `PendenzCustomizer`; no annotation for now.
3. **Transaction routing.** Proposal: everything through a
   `TransactionTemplate` with `REQUIRED` over the application's
   transaction manager; on the calling thread when a transaction is
   active there, on `Dispatchers.IO` otherwise.
4. **Beans for both APIs.** Proposal: `Pendenz` and `PendenzJava`, with
   the id type taken from an optional `TaskTable` bean.
5. **One artifact.** Proposal: `pendenz-spring-boot-starter` with the
   auto-configuration inside, no separate `-autoconfigure` artifact.
6. **Optional integrations.** Proposal: Micrometer and LISTEN/NOTIFY are
   configured when their modules are on the classpath; nothing else.
