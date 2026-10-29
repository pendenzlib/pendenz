# Guide

From a task type to a running worker, and what to do with the rows
afterwards. The concepts behind each step are in
[`concepts.md`](concepts.md); Java and Spring have pages of their own,
[`java.md`](java.md) and [`spring.md`](spring.md).

## Declare a task type

A task type has a name and a payload. The payload is one JSON column by
default; spread it over typed columns instead and SQL can read, write and
join the fields:

```kotlin
data class SendInvoice(val invoiceId: Long, val customer: String)

val sendInvoice = TaskType.columns<SendInvoice>(
    "send-invoice",
    columns = listOf(PayloadColumn("invoice_id", SqlType.BIGINT), PayloadColumn("customer", SqlType.TEXT)),
    write = { mapOf("invoice_id" to it.invoiceId, "customer" to it.customer) },
    read = { SendInvoice(it.getLong("invoice_id"), it.getString("customer")) },
)
```

With kotlinx-serialization, `TaskType.json<SendInvoice>("send-invoice")`
keeps the payload in one `jsonb` column. Without it, a `PayloadCodec`
does the conversion, such as Jackson's in `pendenz-serialization-jackson`.

## Create the table

The table is yours (principle 2): Pendenz generates the DDL, your
migration tool runs it. Pass the task types that live in the table, so
their payload columns are included:

```kotlin
println(PostgresDdl.createTable(TaskTable(name = "billing_task"), sendInvoice))
```

The id column is `bigint` by default; `TaskTable(IdType.UUID, "billing_task")`
gives you UUIDs instead, and `enqueue` accepts an id of your own either way.
`PostgresOperatorDdl` generates views and functions for operators, see
[`concepts.md`](concepts.md#operating-with-sql).

### Indexes the queries expect

`createTable` ends with the indexes the library's own queries rely on;
`PostgresDdl.indexes(table)` prints them alone, for a table you write
yourself or one you add Pendenz to. Names are the table name plus the
suffix, shortened with a hash when they would exceed 63 bytes
([ADR 0028](adr/0028-indexes-on-the-task-table.md)).

| Suffix | Columns | Used by |
|---|---|---|
| `_claim_idx` | `(status, task_type, lane, priority, run_at, id)` | every poll: due groups, claims |
| `_due_idx` | `(run_at) WHERE status IN ('SCHEDULED', 'RETRY_PENDING')` | every poll: promoting due tasks |
| `_running_idx` | `(resource) WHERE status = 'RUNNING'` | resource limits, expired locks |
| `_held_idx` | `(resource) WHERE status = 'CANCELLED' AND locked_by IS NOT NULL` | resource limits, the sweep of locks left by dead workers |
| `_lane_idx` | `(lane, status) WHERE lane IS NOT NULL` | cycles |
| `_retention_idx` | `(task_type, finished_at) WHERE status IN ('SUCCEEDED', 'CANCELLED')` | retention |
| `_creator_idx` | `(created_by, created_at DESC, id DESC) WHERE created_by IS NOT NULL` | `tasks(TaskQuery)` by creator, the MCP listing |
| `_correlation_idx` | `(correlation_id) WHERE correlation_id IS NOT NULL` | follow-ups by correlation |
| `_idempotency_idx` | unique `(task_type, idempotency_key)` | idempotency keys |

Two are left to you: with `FifoClaim`, add `(status, priority DESC,
run_at, id)`; to list tasks without a creator filter on a large table,
add `(created_at DESC, id DESC)`. `locked_until` has no index on
purpose, so the heartbeat stays a cheap update.

## Register handlers and start a worker

```kotlin
val pendenz = Pendenz.create(TaskTable(name = "billing_task")) {
    dataSource(dataSource)
    register(sendInvoice) { context, payload ->
        invoiceService.send(payload.invoiceId)
    }
}

val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
pendenz.startWorker(applicationScope)
```

Handlers are instances: build them with whatever wiring the application
already has. The worker runs as a coroutine in the scope you pass, one
that lives as long as the application; the `SupervisorJob` keeps a
failure there from cancelling the application's other coroutines. Cancel
the scope, or the job `startWorker` returns, to stop the worker; running
handlers get `shutdownTimeout` to finish, see [`concepts.md`](concepts.md#stopping-a-worker).

## Enqueue in your transaction

Enqueue inside your own transaction, so the task appears exactly when the
business change commits, and disappears with it on rollback:

```kotlin
connection.autoCommit = false
invoiceRepository.markSent(connection, invoiceId)
pendenz.enqueue(connection, sendInvoice, SendInvoice(invoiceId, customer))
connection.commit()
```

Without a connection, `enqueue` uses a transaction of its own. With
Spring, an enqueue inside a `@Transactional` method joins that
transaction, see [`spring.md`](spring.md).

## Idempotency

Callers that retry pass an idempotency key. A repeat with the same key for
the same task type inserts nothing and returns the existing task's id:

```kotlin
pendenz.enqueue(sendInvoice, SendInvoice(invoiceId, customer), idempotencyKey = "invoice-$invoiceId")
```

## Retention

A task type says what happens to its finished rows, those in `SUCCEEDED`
and `CANCELLED`, and workers apply the rule during their maintenance sweep:

```kotlin
val sendInvoice = TaskType.json<SendInvoice>("send-invoice", retention = Retention.delete(after = Duration.ofDays(30)))
val archiveOrder = TaskType.json<ArchiveOrder>("archive-order", retention = Retention.archive(Duration.ofDays(7), "order_task_archive"))
```

The sweep runs beside the claim loop every `maintenanceInterval` (one
minute by default), the first time one interval after the worker starts.
An interval of zero turns it off on that worker, for applications that
leave retention to one worker or call `pendenz.runMaintenance()` on a
schedule of their own.

## Test without a database

The in-memory store keeps tasks in a map, and `runPending()` runs whatever
is due right there in the test:

```kotlin
val pendenz = Pendenz.create {
    inMemory()
    register(sendInvoice) { _, payload -> sent += payload.invoiceId }
}

pendenz.enqueue(sendInvoice, SendInvoice(42, "Globex"))
pendenz.runPending()
assertThat(sent).containsExactly(42L)
```

## Operate with SQL

A failed task comes back with one statement, a new one is a plain insert;
[`sql-recipes.md`](sql-recipes.md) has the rest:

```sql
UPDATE billing_task SET status = 'PENDING', finished_at = NULL, version = version + 1
WHERE id = 42 AND status = 'FAILED';

INSERT INTO billing_task (task_type, status, run_at, invoice_id, customer)
VALUES ('send-invoice', 'PENDING', now(), 4711, 'Globex');
```

## Observe

Metrics and logs hook in through a listener; with Micrometer,
`pendenz-micrometer` does it for you. See [`metrics.md`](metrics.md).
