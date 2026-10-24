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

## Register handlers and start a worker

```kotlin
val pendenz = Pendenz.create(TaskTable(name = "billing_task")) {
    dataSource(dataSource)
    register(sendInvoice) { context, payload ->
        invoiceService.send(payload.invoiceId)   // your code, your dependencies
    }
}

pendenz.startWorker(applicationScope)
```

Handlers are instances: build them with whatever wiring the application
already has. Cancel the worker's job to stop it; running handlers get
`shutdownTimeout` to finish, see [`concepts.md`](concepts.md#stopping-a-worker).

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

Finished rows do not pile up unless you want them to. A task type says what
happens to its `SUCCEEDED` and `CANCELLED` rows, and workers apply the rule
during their maintenance sweep:

```kotlin
val sendInvoice = TaskType.json<SendInvoice>("send-invoice", retention = Retention.delete(after = Duration.ofDays(30)))
val archiveOrder = TaskType.json<ArchiveOrder>("archive-order", retention = Retention.archive(Duration.ofDays(7), "order_task_archive"))
```

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
UPDATE billing_task SET status = 'PENDING', version = version + 1
WHERE id = 42 AND status = 'FAILED';

INSERT INTO billing_task (task_type, status, run_at, invoice_id, customer)
VALUES ('send-invoice', 'PENDING', now(), 4711, 'Globex');
```

## Observe

Metrics and logs hook in through a listener; with Micrometer,
`pendenz-micrometer` does it for you. See [`metrics.md`](metrics.md).
