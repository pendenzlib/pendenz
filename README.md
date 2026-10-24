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

Declare a task type. Its payload is one JSON column by default; spread it
over typed columns instead and SQL can read, write and join the fields:

```kotlin
data class SendInvoice(val invoiceId: Long, val customer: String)

val sendInvoice = TaskType.columns<SendInvoice>(
    "send-invoice",
    columns = listOf(PayloadColumn("invoice_id", SqlType.BIGINT), PayloadColumn("customer", SqlType.TEXT)),
    write = { mapOf("invoice_id" to it.invoiceId, "customer" to it.customer) },
    read = { SendInvoice(it.getLong("invoice_id"), it.getString("customer")) },
)
```

Generate the table for your migration tool, with the payload columns of
the task types that live in it:

```kotlin
println(PostgresDdl.createTable(TaskTable(name = "billing_task"), sendInvoice))
```

The id column is `bigint` by default; `TaskTable(IdType.UUID, "billing_task")`
gives you UUIDs instead, and `enqueue` accepts an id of your own either way.

Register task types with their handlers and start a worker:

```kotlin

val pendenz = Pendenz.create {
    dataSource(dataSource)
    table(TaskTable(name = "billing_task"))
    register(sendInvoice) { context, payload ->
        invoiceService.send(payload.invoiceId)   // your code, your dependencies
    }
}

pendenz.startWorker(applicationScope)
```

Metrics and logs hook in through a listener with empty defaults; implement
what you need and register it with `listener(...)` on the builder:

```kotlin
object InvoiceMetrics : TaskListener<Long> {
    override fun onSucceeded(task: TaskContext<Long>, duration: Duration) = timer.record(duration)
    override fun onFailed(task: TaskContext<Long>, error: Throwable) = failures.increment()
}
```

With Micrometer, `pendenz-micrometer` does this for you: timers for the wait
before a claim and for every run by outcome, counters for enqueues and
approvals, and, if you ask for them, gauges for what waits in the table,
read in your scope so a scrape never queries the database:

```kotlin
val metrics = PendenzMetrics<Long>(meterRegistry, Tags.of("queue", "billing"))
// on the builder: listener(metrics)
metrics.watchBacklog(pendenz, applicationScope, every = Duration.ofSeconds(30))
```

Finished rows do not pile up unless you want them to. A task type says what
happens to its `SUCCEEDED` and `CANCELLED` rows, and workers apply the rule
during their maintenance sweep:

```kotlin
val sendInvoice = TaskType.json<SendInvoice>("send-invoice", retention = Retention.delete(after = Duration.ofDays(30)))
val archiveOrder = TaskType.json<ArchiveOrder>("archive-order", retention = Retention.archive(Duration.ofDays(7), "order_task_archive"))
```

Callers that retry pass an idempotency key. A repeat with the same key for
the same task type inserts nothing and returns the existing task's id:

```kotlin
pendenz.enqueue(sendInvoice, SendInvoice(invoiceId), idempotencyKey = "invoice-$invoiceId")
```

Enqueue inside your own transaction, so the task appears exactly when the
business change commits:

```kotlin
connection.autoCommit = false
invoiceRepository.markSent(connection, invoiceId)
pendenz.enqueue(connection, sendInvoice, SendInvoice(invoiceId))
connection.commit()
```

Test your own code without a database. The in-memory store keeps tasks in
a map, and `runPending()` runs whatever is due right there in the test:

```kotlin
val pendenz = Pendenz.create {
    inMemory()
    register(sendInvoice) { _, payload -> sent += payload.invoiceId }
}

pendenz.enqueue(sendInvoice, SendInvoice(42))
pendenz.runPending()
assertThat(sent).containsExactly(42L)
```

Operate it with SQL. A failed task comes back with one statement, a new
one is a plain insert, see [`docs/sql-recipes.md`](docs/sql-recipes.md):

```sql
UPDATE billing_task SET status = 'PENDING', version = version + 1
WHERE id = 42 AND status = 'FAILED';

INSERT INTO billing_task (task_type, status, run_at, invoice_id, customer)
VALUES ('send-invoice', 'PENDING', now(), 4711, 'Globex');
```

## How it works

A task is a row. The queue's own columns, status, run time, attempts, lock,
version, are the same for every row; the payload columns belong to the
task type. Its `status` column holds one of ten states; the library
enforces the legal transitions and so do the SQL recipes.

| Status | Who acts next | Meaning |
|---|---|---|
| `PENDING` | Pendenz | Due, waiting to be claimed |
| `SCHEDULED` | Pendenz | Run time in the future |
| `RUNNING` | Pendenz | Claimed, heartbeat running |
| `RETRY_PENDING` | Pendenz | Failed, attempts left, backoff running |
| `SUCCEEDED` | nobody | Done |
| `CANCELLED` | nobody | Will never run |
| `FAILED` | a human | Attempts exhausted |
| `REJECTED` | a human | Result refused by validation |
| `QUARANTINED` | a human | Set aside with a reason |
| `AWAITING_APPROVAL` | a human | Task type requires approval |

Workers poll the table, promote due tasks, claim with `FOR UPDATE SKIP
LOCKED` and run the registered handler. Polling is the default and the
safety net; with the optional trigger from `PostgresDdl.notifyTrigger` and
`PostgresNotify` from `pendenz-postgres-notify` as the worker's `wakeUp`,
a task runs the moment its transaction commits, however long the poll
interval is. Several workers on several JVMs can
share one table. Every write bumps a `version` column; a worker only records
an outcome if the version it claimed is still current, so an operator's SQL
always wins over a running worker.

A cycle runs a task type over a whole dataset. Its tasks come from a
query the application writes, inserted with `INSERT ... SELECT` so the
rows never leave the database, and share the lane `cycle:` plus the
cycle's id:

```kotlin
val cycle = pendenz.startCycle(
    sendInvoice,
    CycleTasks.query("SELECT i.id, c.name FROM invoice i JOIN customer c ON c.id = i.customer_id WHERE i.sent_at IS NULL"),
    description = "October invoices",
)
pendenz.pauseCycle(cycle)    // waiting tasks step aside, running ones finish
pendenz.resumeCycle(cycle)
pendenz.cancelCycle(cycle)
```

The cycle lives in a table of its own, generated by
`PostgresDdl.createCycleTable`, and a generated view shows its progress
per task status. Enable cycles with `cycles()` on the builder.

A handler that has a next step enqueues it on its context. Nothing is
written until the task's outcome is recorded; then the follow-ups go into
the same transaction, with the task as their parent and the chain's
correlation id, so a crash can neither lose them nor double them:

```kotlin
register(sendInvoice) { ctx, invoice ->
    mail.send(invoice)
    ctx.enqueue(remind, Reminder(invoice.id), runAt = Instant.now().plus(Duration.ofDays(14)))
}
```

A handler may produce a result. It is registered with how to store and
how to validate it; the worker validates before it writes, and a refused
result lands in `REJECTED` for a person, with the result and the reason
in the row. Handlers attach provenance, the code or model version that
produced the result, and a task type may compute a preview of what a
task would do, stored at enqueue for the person who approves it:

```kotlin
val scoreTicket = TaskType.json<Ticket>("score-ticket", preview = { "Score ticket ${it.id}" })
register(scoreTicket, TaskResult.json<Ticket, Score> { _, s -> if (s.value in 0..100) Verdict.accept() else Verdict.reject("out of range") }) { ctx, ticket ->
    ctx.provenance("model", "claude-sonnet-5-5")
    model.score(ticket)
}
```

Some tasks need a second pair of eyes. An approval rule on the task type
holds them as `AWAITING_APPROVAL` until someone who is not their creator
approves or declines. A cycle's tasks are approved in one call; follow-ups
of other types they enqueue wait for their own approval. Who may enqueue,
approve, restart or cancel what is a `Permissions` implementation over the
application's own permission system, checked before anything is written:

```kotlin
val refund = TaskType.json<Refund>("refund", approval = Approval.rule { createdBy, p -> p == null || p.amount > 1000 })

Pendenz.create {
    permissions { actor, action, type -> myAuth.may(actor, action.name.lowercase(), type) }
    register(refund) { _, r -> payments.refund(r) }
}
pendenz.approve(id, actor = "manager")
```

A task may name the resource it runs against, and a limit table says how
many tasks of a resource may run at once, across all workers. The claim
skips tasks whose resource is full; an operator changes the limit with
SQL, effective at the next poll:

```kotlin
val sendInvoice = TaskType.columns<SendInvoice>(..., resource = TaskResource.fixed("mail-server", maxRunning = 4))
val notify = TaskType.json<Notify>("notify", resource = TaskResource.fromPayload({ "customer:${it.customerId}" }, "customer:%", 2))
```

Enable it with `resourceLimits()` on the builder and
`PostgresDdl.createResourceLimitTable` in your migration.

Workers take due tasks fairly. A `lane`, say a tenant or a batch, groups
tasks within a type; each poll visits the lanes in turn, so a batch of ten
thousand and a single task enqueued a second later alternate. `priority`
is a strict tier above that: higher runs first. First come, first served
is still available as `FifoClaim` for teams that need arrival order.

A task type that is cheap to run can set `claimBatchSize`, so a worker
takes several of its tasks from one lane per visit instead of one; lanes
still take turns.

`PostgresOperatorDdl.views` generates three views for operators: failed
tasks grouped by error, running tasks whose heartbeat stopped, and
everything that waits for a human. `PostgresOperatorDdl.functions`
generates `_restart`, `_complete`, `_quarantine` and `_cancel` functions
that take the id and the version the caller saw, apply the legal
transition and return whether the row changed, so a UI or a script never
has to know the rules.

An AI agent uses the queue through MCP. The application names the task
types an agent may use; each becomes a tool that enqueues, beside
`preview_<type>`, `get_task` and `list_tasks`. Every call acts as one
actor, so the approval rule can hold what the agent creates and the
agent can never approve it, and the agent sees only its own tasks:

```kotlin
val mcp = PendenzMcp.create(pendenz, IdType.BIGINT, actor = "ticket-agent") {
    tool(escalateTicket, "Escalates a ticket to second level.", JsonSchema.of<Escalation>())
}
mcp.serveStdio()                         // an agent host starts this application
servletContext.addServlet("mcp", mcp.servlet("/mcp")).addMapping("/mcp")   // or Streamable HTTP
```

Stopping a worker means cancelling its coroutine. It stops claiming at once,
gives running handlers `shutdownTimeout` (thirty seconds by default) to
finish, then cancels the rest and hands their tasks back as `PENDING` so
another worker picks them up without waiting for the lock timeout. A
handler that ignores the cancellation, say one blocked in a socket read,
is waited for and its outcome recorded, since the work was done; its
heartbeat has stopped, though, so past the lock timeout the task belongs
to the next worker and the late outcome is dropped.

## Modules

| Module | Artifact | Contents |
|---|---|---|
| `core` | `pendenz-core` | Schema mapping, status model, JDBC store, worker, builder. Depends on coroutines, kotlinx-serialization and SLF4J only. |
| `core-java` | `pendenz-core-java` | Java facade: blocking methods, `CompletableFuture`, handlers as plain methods. Its tests are written in Java. |
| `serialization-jackson` | `pendenz-serialization-jackson` | `PayloadCodec` and payload upgrades over Jackson 3, for teams without kotlinx-serialization. |
| `postgres-notify` | `pendenz-postgres-notify` | Wakes workers through `LISTEN`/`NOTIFY` instead of waiting for the poll interval. |
| `mcp` | `pendenz-mcp` | An MCP server over the task types you expose, so AI agents enqueue and follow tasks as tools. On the Java MCP SDK; opens no port of its own. |
| `micrometer` | `pendenz-micrometer` | Micrometer meters from the listener events, and gauges for the backlog per type and status with the age of the oldest due task. |
| `examples/demo` | not published | A billing service in miniature: Flyway, HikariCP, a flaky handler, an SQL restart, retention into an archive table. |
| `examples/helpdesk` | not published | A ticket agent and the helpdesk it works for. The agent starts the helpdesk as an MCP server over stdio, reads open tickets with SQL and acts only through tools; escalations wait for a person, a reply that promises a refund is refused. |

Planned: framework adapters.

With Jackson the task type of the Java example reads
`new TaskType<>("send-invoice", JacksonCodec.of(SendInvoice.class))`.

From Java the same queue reads like this:

```java
try (PendenzJava<Long> pendenz = PendenzJava.builder()
        .dataSource(dataSource)
        .register(sendInvoice, (ctx, payload) -> invoiceService.send(payload.invoiceId()))
        .build()) {
    pendenz.enqueue(sendInvoice, new SendInvoice(42L));
    try (WorkerHandle worker = pendenz.startWorker()) {
        // ...
    }
}
```

## Documentation

- [`docs/sql-recipes.md`](docs/sql-recipes.md): restart, complete, cancel,
  quarantine and inspect tasks with SQL.
- [`docs/adr`](docs/adr): architecture decision records.
- [`CLAUDE.md`](CLAUDE.md): working rules for humans and AI tools
  contributing here.

## When not to use Pendenz

- You need replay of arbitrary program flow with branching: look at
  [DBOS](https://dbos.dev) or [Temporal](https://temporal.io).
- You start on a green field and are happy with a vendor dashboard: JobRunr
  or db-scheduler will get you going faster.

## Roadmap

| Version | Scope |
|---|---|
| 0.1 | Queue: schema mapping, status model, enqueue in your transaction, claim with `SKIP LOCKED`, retry with backoff, deferred tasks, idempotency keys, execution timeouts, retention, in-memory test mode, Java facade |
| 0.2 | Datasets: cycles from a SQL query, fair claiming, batch claim, per-resource concurrency, generated views and SQL functions. Complete. |
| 0.3 | Control: human approval, permissions, chaining, result validation, provenance metadata, preview. Complete. |
| 0.4 | Agents: MCP server exposing registered task types as tools. Complete. |
| 0.5 | Examples and evidence: a demo service, an example agent, Micrometer metrics |

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
compatibility check and the coverage check, which fails below 85 % line
coverage. `./gradlew dokkaGenerate` writes the API reference to
`*/build/dokka/html`; CI uploads it as an artifact. Public API changes show up as a diff in `*/api/*.api`;
regenerate with `./gradlew apiDump` in a run of its own, since Gradle 9
refuses `apiDump` and `apiCheck` in the same invocation.

Tests create one fresh table per test and wait on states, never on time;
see the test conventions in [`CLAUDE.md`](CLAUDE.md). The end-to-end
tests run workers against the real database, two of them in a second
JVM that the test kills mid-task, to show the heartbeat protecting a
living worker and the lock timeout relieving a dead one.

The demo runs against an embedded PostgreSQL unless `PENDENZ_JDBC_URL`,
`PENDENZ_DB_USER` and `PENDENZ_DB_PASSWORD` point it at yours:

```bash
./gradlew :examples:demo:run
```

The ticket agent needs a model with tool calling behind an
OpenAI-compatible API, a local [Ollama](https://ollama.com) by default
(`ollama pull qwen3:8b`); `AGENT_MODEL_URL`, `AGENT_MODEL` and
`AGENT_API_KEY` point it at another. Its tests replay prepared model
answers instead, so CI needs neither a model nor a key:

```bash
./gradlew :examples:helpdesk:run
```

Architecture decisions are recorded in [`docs/adr`](docs/adr).

## License

[Apache License 2.0](LICENSE)
