# Pendenz

A task queue for the JVM whose tasks live in tables **you** own.

> *Pendenz* (Swiss German): an open item, something still to be done.

Pendenz is a Kotlin library, usable from Java, for background work in systems
that already have a relational database and their own user interface. Tasks
are ordinary rows in your schema. You can see them, restart them and complete
them with plain SQL, join them to your business tables, and build your own
screens on top. There is no server, no dashboard you did not ask for, and no
binary blob between you and your data.

**Status: early. The core queue works end to end against PostgreSQL, nothing is released yet.**

## Why

Existing JVM schedulers hide their work: Quartz needs eleven tables of
serialized blobs, JobRunr ships its own dashboard and stores jobs as JSON,
db-scheduler uses one fixed table with a binary payload. Teams that must show
progress and failures in their own UI, restart tasks with SQL, or process a
whole dataset as one batch end up writing the queue themselves. Pendenz is
that queue, written once.

## Principles

1. **Flexible, with good defaults.** Every behaviour is replaceable: claim
   strategy, retry policy, serialization, clock, hooks. Every one has a sane
   default, so the common case needs no decisions.
2. **The schema is yours.** Pendenz knows concepts (status, attempts, run
   time), not table or column names. You map them onto your table and add
   whatever columns, foreign keys and indexes you like.
3. **SQL is a supported interface.** Status transitions are documented and
   enforced. DDL, views and SQL functions are generated from your mapping.
4. **Integration, not takeover.** No process of its own, no framework in the
   core, no migrations of its own. Enqueue inside your transaction.
5. **Small core, modules on demand.**
6. **Leaving costs nothing.** Tasks are rows, handlers are functions. Remove
   the library and everything stays readable.
7. **Outside callers only use registered task types.** A UI, another system
   or an AI agent picks a task type the developer registered and supplies a
   payload that is validated. What happens next is decided by your handler.
8. **The schema is a versioned public API.** Status names, minimum columns
   and generated views are stable from 1.0 on.
9. **Testable without infrastructure.** A synchronous in-memory mode runs
   handlers directly.

## A first look

Generate the table for your migration tool:

```kotlin
println(PostgresDdl.createTable(TaskTable(name = "billing_task")))
```

Register task types with their handlers and start a worker:

```kotlin
@Serializable
data class SendInvoice(val invoiceId: Long)

val sendInvoice = TaskType.json<SendInvoice>("send-invoice")

val pendenz = Pendenz.create {
    dataSource(dataSource)
    table(TaskTable(name = "billing_task"))
    register(sendInvoice) { context, payload ->
        invoiceService.send(payload.invoiceId)   // your code, your dependencies
    }
}

pendenz.startWorker(applicationScope)
```

Enqueue inside your own transaction, so the task appears exactly when the
business change commits:

```kotlin
connection.autoCommit = false
invoiceRepository.markSent(connection, invoiceId)
pendenz.enqueue(connection, sendInvoice, SendInvoice(invoiceId))
connection.commit()
```

Operate it with SQL. A failed task comes back with one statement, see
[`docs/sql-recipes.md`](docs/sql-recipes.md):

```sql
UPDATE billing_task SET status = 'PENDING', version = version + 1
WHERE id = 42 AND status = 'FAILED';
```

## When not to use Pendenz

- You need replay of arbitrary program flow with branching: look at
  [DBOS](https://dbos.dev) or [Temporal](https://temporal.io).
- You start on a green field and are happy with a vendor dashboard: JobRunr
  or db-scheduler will get you going faster.

## Roadmap

| Version | Scope |
|---|---|
| 0.1 | Queue: schema mapping, status model, enqueue in your transaction, claim with `SKIP LOCKED`, retry with backoff, deferred tasks, idempotency keys, execution timeouts, retention, in-memory test mode, Java facade |
| 0.2 | Datasets: cycles from a SQL query, fair claiming, batch claim, per-resource concurrency, generated views and SQL functions |
| 0.3 | Control: human approval, permissions, chaining, result validation, provenance metadata, preview |
| 0.4 | Agents: MCP server exposing registered task types as tools |
| 0.5 | Examples: a demo service and an example agent |

## Development

Requires a JDK 17 or newer to run Gradle. The build provisions a JDK 17
toolchain for compilation automatically. Note: detekt 1.x cannot parse
four-part JDK version strings such as the JetBrains Runtime's `25.0.4.1`;
run Gradle on a standard JDK (for example the provisioned Temurin under
`~/.gradle/jdks`) if you hit that.

```bash
./gradlew build
```

This runs ktlint, detekt, the tests on a real PostgreSQL (Testcontainers
when Docker is available, an embedded PostgreSQL otherwise), the API
compatibility check and the coverage report. Public API changes show up as a diff in `*/api/*.api`;
regenerate with `./gradlew apiDump`.

Architecture decisions are recorded in [`docs/adr`](docs/adr).

## License

[Apache License 2.0](LICENSE)
