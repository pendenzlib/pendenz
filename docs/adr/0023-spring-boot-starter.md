# Spring integration: a transaction-joining provider and a Boot starter

- Status: accepted
- Date: 2026-10-24

## Context

Most JVM services that would use Pendenz run on Spring. Today they can,
but they write the same glue every time: a `ConnectionProvider` that
joins Spring's transaction, a bean that builds the queue, a lifecycle
that starts the worker after the context is ready and stops it before
the data source closes. The planning names a
`pendenz-spring-boot-starter`; ADR 0003 keeps Spring out of the core and
routes transaction participation through the connection abstraction for
exactly this module.

What the integration must get right:

- **Transactions.** The point of enqueueing in the caller's transaction
  (principle 4) is that `enqueue` inside a `@Transactional` method
  commits or rolls back with the business change. Spring binds its
  transaction to the calling thread. The core's
  `DataSourceConnectionProvider` switches to `Dispatchers.IO`, so it
  would run on another thread and miss the transaction.
- **Joining can fail silently.** `DataSourceUtils` joins only when the
  transaction manager manages exactly the `DataSource` it is handed. A
  `JpaTransactionManager` over a different `DataSource`, a
  `TransactionAwareDataSourceProxy` on one side only, or a second data
  source with its own manager all make it open a connection of its own
  in auto-commit mode: the task is stored whatever the business change
  does, and nothing reports it.
- **A failed enqueue must not decide the caller's transaction.** Run
  through a `TransactionTemplate` with `REQUIRED`, any exception from
  `enqueue`, a refused permission for instance, marks the outer
  transaction rollback-only. A caller that catches it and carries on
  gets `UnexpectedRollbackException` at commit.
- **Worker transactions are not business transactions.** Claim,
  heartbeat and outcome run on the worker's coroutines, outside any
  `@Transactional` method, several times per task and every few seconds
  per running task. Routed through the application's transaction
  manager they would take on its settings: an `EntityManager` per
  transaction with JPA, `SERIALIZABLE` where the application set it as
  the default (which `SKIP LOCKED` answers with serialization failures),
  and the application's timeouts.
- **Joining depends on the thread.** It works only as long as the core
  calls the provider on the thread of the calling coroutine. Nothing in
  the core states that today; a `withContext` added later would break
  the atomicity as silently as a misconfigured data source.
- **Handlers are instances** (CLAUDE.md, no DI container in the core).
  In Spring they are beans with dependencies of their own; the starter
  must collect them without reflection or annotation scanning.
- **Tests of the application.** Every `@SpringBootTest` context would
  start a worker, and Spring caches test contexts, so several workers
  keep polling the same tables between tests. The starter would make
  its users' tests flaky.
- **Shutdown.** The worker's `shutdownTimeout` (ADR 0005) defaults to
  30 seconds, exactly Spring Boot's `timeout-per-shutdown-phase`. Spring
  can give up on the phase before the worker has released the tasks it
  cancelled.
- **The schema is the user's** (principle 2) and there are no migrations
  of our own (principle 4). The starter creates no tables.
- **Leaving costs nothing** (principle 6). Without the starter, the same
  queue must keep working with the core's builder.

Spring Boot 4 is the current major line, with Spring Framework 7,
Jakarta EE 11 and Jackson 3, the line ADR 0012 chose for the Jackson
module; the newest releases are Boot 4.1.2 and 4.0.9. Boot 3.5 and
Spring Framework 6.2 are out of open-source support since mid-2026 but
run a large share of production services and will for a while. The
provider needs only `spring-jdbc` and `spring-tx`, whose APIs used here
are the same in Spring 6 and 7; only the auto-configuration is tied to
a Boot major.

## Decision

Two modules. The core does not know either.

- `spring`, artifact `pendenz-spring`: the `SpringConnectionProvider`
  and its configuration check. Depends on `spring-jdbc` and `spring-tx`,
  compiled against Spring Framework 6.2 and tested against 6.2 and 7.0.
  Usable with Boot 3, Boot 4 and Spring without Boot.
- `spring-boot`, artifact `pendenz-spring-boot-starter`: the
  auto-configuration for Boot 4, depending on `pendenz-spring`. On
  Boot 3 it fails at startup with a message pointing to
  `pendenz-spring`, instead of a `ClassNotFoundException`.

### Transactions

`SpringConnectionProvider(dataSource, transactionManager)` decides per
call:

- **A Spring transaction is active on the calling thread and holds a
  connection of this data source:** the block runs on the calling
  thread with that connection, taken from `DataSourceUtils` and released
  through it. The provider neither commits nor rolls back nor marks the
  transaction rollback-only: an exception propagates, and the caller's
  handling of it decides whether the task is written with the business
  change.
- **That transaction is read-only:** the call fails with an
  `IllegalStateException` naming the cause, instead of an SQL error from
  the database.
- **A transaction is active, but holds no connection of this data
  source** (another manager, another data source): the call fails. The
  provider joins or refuses; it never writes beside the caller's
  transaction.
- **No transaction is active:** a transaction of its own, opened,
  committed and closed on `Dispatchers.IO` as the core's provider does,
  bypassing the transaction manager. This is the path of the worker and
  of every call outside a `@Transactional` method.

The constructor checks the pair once: the transaction manager must be a
`ResourceTransactionManager` whose data source, after unwrapping
`TransactionAwareDataSourceProxy` and `LazyConnectionDataSourceProxy`,
is the provider's, or a `JpaTransactionManager` whose `getDataSource()`
is. Anything else, JTA included, is refused with a message that says
what was found.

The core's `ConnectionProvider` documentation gains the guarantee the
provider relies on: for calls without an explicit connection, the queue
calls `inTransaction` on the thread of the calling coroutine, without
switching dispatchers first. A rollback test in `pendenz-spring` holds
the core to it.

A Kotlin `suspend` function cannot be `@Transactional` with an imperative
transaction manager, so Kotlin callers that want the caller's
transaction call the blocking `PendenzJava` facade inside it, or pass
the connection explicitly as today. The facade's `*Async` methods run on
another thread and never join; their documentation says so.

### Beans

- `pendenz.id-type` (`bigint`, default, or `uuid`) fixes the queue's id
  type. The starter registers `Pendenz<Long>` and `PendenzJava<Long>`, or
  the `UUID` pair, with explicit generic types, so injection points are
  matched by their type argument.
- The table comes from `pendenz.table` (default `pendenz_task`). A
  `TaskTable` bean replaces it, for other column names; its id type must
  match `pendenz.id-type`, otherwise startup fails.
- `PendenzJava` wraps the same `Pendenz` instance. `core-java` gains a
  public `PendenzJava.wrap(pendenz)` for that; the bean's `close` stops
  its own scope only, since the worker belongs to the lifecycle bean.
- With several transaction managers, `pendenz.transaction-manager` names
  the bean to check against.
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
TaskRegistration<Long> sendInvoice(MailService mail) {
    return TaskRegistration.of(SEND_INVOICE, (ctx, invoice) -> mail.send(invoice));
}
```

`TaskRegistration<ID>` has overloads for a Kotlin `TaskHandler`, a Java
handler and a `ResultHandler` with its `TaskResult` (ADR 0020). The
starter collects every such bean by its resolved type; a registration
whose id type differs from the queue's fails startup with its bean name.
For anything else on the builder (a clock, cycles, resource limits) a
`PendenzCustomizer` bean receives the builder before it is built, the
pattern Spring Boot uses for its own builders.

- Every `TaskListener` bean is registered, each once.
- One `Permissions` bean is used; more than one fails startup with their
  names, since combining them has no obvious meaning.

### Worker

A `SmartLifecycle` bean starts one worker when the context starts, which
is after every singleton, a Flyway or Liquibase migration included, has
been initialised. It owns a `CoroutineScope` on `Dispatchers.Default`,
not Spring's executor.

- Its phase is `SmartLifecycle.DEFAULT_PHASE - 512`, stated as a public
  constant: it stops before the web server's graceful shutdown phase,
  while requests may still enqueue, and long before the data source is
  destroyed.
- `stop(Runnable)` cancels the worker and calls back from the worker's
  completion, so beans in the same phase stop in parallel.
- `pendenz.worker.shutdown-timeout` defaults to 20 seconds in the
  starter, below Spring Boot's 30. A value not below
  `spring.lifecycle.timeout-per-shutdown-phase` fails startup.
- `pendenz.worker.enabled` (default `true`) lets a deployment run nodes
  that only enqueue, and tests run without a worker.
  `pendenz.worker.*` maps onto `WorkerOptions`: `parallelism`,
  `poll-interval`, `lock-timeout`, `execution-timeout` and the rest, as
  Spring `Duration`s.

### Tests of the application

The README's Spring section shows the pattern: `pendenz.worker.enabled=false`
in the test properties, and `runPending()` in the test where a task
should run. A test then decides when work happens, and cached contexts
leave no worker behind.

### Schema check

The starter creates no tables. With `pendenz.schema-check` (default
`true`) it reads the table's columns from `information_schema` at
startup and fails, naming the table and the missing columns, instead of
at the first poll. Read-only, so principle 2 holds. The README shows
how to put the generated DDL into a Flyway or Liquibase migration, as
the examples already do.

### Optional integrations

- With `pendenz-micrometer` on the classpath and a `MeterRegistry` bean,
  a `PendenzMetrics` bean, unless the application defines one; as a
  `TaskListener` bean it is registered once like any other.
  `pendenz.metrics.backlog-interval` turns on `watchBacklog` in the
  worker's scope (ADR 0022).
- With `pendenz-postgres-notify` on the classpath,
  `pendenz.worker.notify=true` wakes workers through `LISTEN`/`NOTIFY`
  (ADR 0014). `NOTIFY` is delivered at commit, so a rolled-back enqueue
  wakes no one.

### Tests

In `pendenz-spring`, against PostgreSQL with `DataSourceTransactionManager`
and `JpaTransactionManager`: an enqueue in a transaction that rolls back
leaves no row; one that commits leaves one; a refused permission caught
by the caller leaves the business change committable; a read-only
transaction and a transaction over another data source are refused; a
mismatched pair fails at construction. The same suite runs against
Spring 6.2 and 7.0.

In the starter, `ApplicationContextRunner` for conditions, properties
and every startup failure named above; against PostgreSQL: a committed
enqueue is run by the worker; a handler bean with an injected dependency
runs; context close drains a running handler within the phase. One test
in Java with a `@SpringBootTest` application proves registration and
`PendenzJava` injection from Java. An `examples/spring` service follows
as its own step.

## Rejected alternatives

- **Every call through a `TransactionTemplate` with `REQUIRED`** over the
  application's transaction manager, the first draft of this ADR.
  Marks the caller's transaction rollback-only on any enqueue failure,
  wraps checked exceptions, and gives the worker's frequent short
  transactions the application's isolation, timeouts and JPA overhead.
- **Joining through `DataSourceUtils` without a check.** Simple, and
  silently non-atomic whenever the manager and the data source do not
  match.
- **JTA in the first version.** An XA connection is enlisted by the JTA
  manager, not bound as a resource Spring can check, so the provider
  could not tell joining from writing beside the transaction. It can
  follow with its own tests when someone needs it.
- **One artifact for Boot 4 only.** Leaves out Boot 3 and plain Spring,
  although the provider, the part that is hard to write by hand, does
  not depend on Boot.
- **Separate starter and `-autoconfigure` artifacts.** Spring's own
  convention, worth it once there are many optional integrations; with
  two, one artifact is easier to depend on. It can be split later
  without changing package names.
- **The id type taken from a `TaskTable` bean.** Spring cannot see the
  type argument of an object built at runtime, so `Pendenz<Long>` and
  `Pendenz<UUID>` injection points would not be told apart.
- **An annotation such as `@TaskHandler` on bean methods.** Familiar from
  `@Scheduled` and `@KafkaListener`, but it needs reflection to find and
  call the methods and loses the type link between task type and
  payload. Registration beans are explicit, typed and visible in the
  bean graph; an annotation could be added later on top of them.
- **Handlers as beans implementing `TaskHandler` directly.** The starter
  would not know which task type a handler serves without a second
  interface or a naming convention.
- **A test annotation** that turns the worker off and offers a helper.
  A property and a documented pattern do the same; an annotation can
  follow if the pattern proves tedious.
- **A warning instead of a failure for the shutdown timeout.** A
  warning in a startup log is read after the first lost shutdown.
- **Migrations from the starter**, such as a Flyway location with the
  task table. Breaks principle 2 and 4: the table's name, columns and
  indexes are the application's.

## Consequences

- Two new published modules with Spring as a dependency; the core's code
  is untouched, its `ConnectionProvider` documentation gains the thread
  guarantee.
- `core-java` gains `PendenzJava.wrap`, public API.
- `TaskRegistration`, `PendenzCustomizer` and the lifecycle phase
  constant are public API of the starter.
- The worker's transactions do not go through the application's
  transaction manager: transaction listeners, timeouts and isolation
  configured there do not apply to them, which is the intent.
- `TaskListener.onEnqueued` fires when the row is inserted, before the
  caller's commit, as it does today with an explicit connection; a
  rolled-back enqueue is still counted by `pendenz.tasks.enqueued`.
- An `examples/spring` service becomes possible, and the README gets a
  Spring section.

## Decisions taken with the acceptance (2026-10-24)

1. Two modules: `pendenz-spring` for Spring Framework 6.2 and 7, and
   `pendenz-spring-boot-starter` for Boot 4 only.
2. A running transaction is joined on the calling thread without touching
   its outcome; read-only and foreign transactions are refused; otherwise
   a JDBC transaction of its own. The pair is checked at construction, JTA
   is refused.
3. The id type comes from `pendenz.id-type`; `TaskTable` beans are checked
   against it.
4. Handlers are registered through `TaskRegistration` beans plus a
   `PendenzCustomizer`; no annotation.
5. The worker is switched off in the application's tests by property,
   with `runPending()` as the documented pattern.
6. The transaction pair, the id type, the shutdown timeout and the
   table's columns are checked at startup and fail it; the schema check
   can be turned off.
7. Micrometer and LISTEN/NOTIFY are configured when their modules are on
   the classpath; nothing else.

Noted during implementation:

- Kotlin cannot tell a suspending `TaskHandler` lambda from a blocking
  `JavaTaskHandler` lambda, so `TaskRegistration.of` takes Kotlin handlers
  and `TaskRegistration.blocking` the Java ones, instead of overloads of
  one name. Blocking handlers run on `Dispatchers.IO`, as with the Java
  builder. `core-java` makes the two adapters behind that,
  `asTaskHandler` and `asResultHandler`, public, and its builder gains
  `connectionProvider`, which Java users of `pendenz-spring` need.
- The id type is matched by the type argument Spring resolves from a
  bean's declared type. `TaskRegistration`, `PendenzCustomizer` and
  `TaskListener` beans declared for the other id type fail startup with
  their names; a bean whose type argument Spring cannot resolve is taken
  as matching.
- The effective `TaskTable` is a bean: the application's, or one the
  starter builds from `pendenz.table` and `pendenz.id-type`. The schema
  check and `LISTEN`/`NOTIFY` read it, so an application that builds its
  own `Pendenz` bean declares its table as a bean too.
- The schema check runs as a `SmartInitializingSingleton`, after every
  singleton, Flyway and Liquibase initializers included.
- `pendenz-spring` uses the transaction manager's own data source as the
  key it looks the bound connection up by, and the provider's data
  source, without a `TransactionAwareDataSourceProxy`, for transactions
  of its own.
- On PostgreSQL a failed statement aborts the whole transaction, so a
  database error during an enqueue still ends the caller's transaction;
  permissions and payloads are checked before the first statement.
- Spring Boot 4.0's dependency management pins kotlinx-coroutines 1.10
  and kotlinx-serialization 1.9, below the 1.11 the core is built with.
  The README tells Boot users to raise both; the starter's own tests
  resolve the module's versions, not Boot's.
- The Spring 7 run of `pendenz-spring`'s tests is a second test task over
  the same classes, with Spring and Hibernate raised in its runtime
  classpath.
