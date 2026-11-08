# Guide

From a task type to a running worker, what happens when a handler
fails, and what to do with the rows afterwards. The concepts behind each step are in
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
The table and its DDL, `TaskTable`, `IdType`, `PostgresDdl` and
`PostgresOperatorDdl`, are in the package `io.github.pendenzlib.schema`;
the other types on this page are in `io.github.pendenzlib`.
`PostgresOperatorDdl` generates views and functions for operators, see
[`concepts.md`](concepts.md#operating-with-sql). The optional tables for
cycles, resource limits, resource blocks and resource rates come from
`PostgresDdl.createCycleTable`, `createResourceLimitTable`,
`createResourceBlockTable`, `createResourceRateTable` and
`createResourceStartTable`.

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

## When a handler fails

Whatever a handler throws is a failed attempt, and the task type's
`RetryPolicy` decides whether the task runs again and when. The default
is `ExponentialBackoff()`: five attempts in all, the retries after about
1, 2, 4 and 8 seconds. After the last attempt the task is `FAILED` and
waits for a person.

```kotlin
val sendInvoice = TaskType.json<SendInvoice>(
    "send-invoice",
    retryPolicy = ExponentialBackoff(maxAttempts = 8, initialDelay = Duration.ofSeconds(30), maxDelay = Duration.ofHours(2)),
)
val importFile = TaskType.json<ImportFile>("import-file", retryPolicy = NoRetry)
```

| `ExponentialBackoff` parameter | Default | Meaning |
|---|---|---|
| `maxAttempts` | 5 | attempts in all, the first one included |
| `initialDelay` | 1 second | delay after the first failure |
| `multiplier` | 2.0 | growth of the delay per failure |
| `maxDelay` | 1 hour | upper bound for one delay |
| `jitter` | 0.2 | share of the delay that is randomised in both directions; 0 in tests |

`NoRetry` makes the first failure final. A policy of your own is one
function. It gets a `FailedAttempt`: the attempt that failed, counted
from 1, the time, what was thrown, and the task as `TaskContext` with
its resource, lane, priority and creator. It returns the next run time
or `null` to give up:

```kotlin
val charge = TaskType.json<Charge>("charge", retryPolicy = RetryPolicy { failure ->
    if (failure.cause is CardDeclinedException || failure.attempt >= 3) null
    else failure.failedAt.plus(Duration.ofMinutes(5))
})

// One type for every partner; the slow partner gets more time between attempts
val callPartner = TaskType.json<PartnerCall>("call-partner", retryPolicy = RetryPolicy { failure ->
    val delay = if (failure.task.resource == "partner-api:slow") Duration.ofMinutes(30) else Duration.ofMinutes(1)
    if (failure.attempt >= 5) null else failure.failedAt.plus(delay)
})
```

The context also carries `createdAt`, the time the task was enqueued, so
a policy can give up by age instead of by attempts:

```kotlin
val syncLedger = TaskType.json<SyncLedger>("sync-ledger", retryPolicy = RetryPolicy { failure ->
    val age = Duration.between(failure.task.createdAt, failure.failedAt)
    if (age > Duration.ofDays(1)) null else failure.failedAt.plus(Duration.ofHours(1))
})
```

A policy that throws gives up: the exception is logged and the task
becomes `FAILED` with the handler's error. The context in a
`FailedAttempt` cannot enqueue follow-ups.

- A handler that runs longer than its execution timeout is cancelled,
  and the timeout counts as a failed attempt. The timeout is the task
  type's own `executionTimeout` when it sets one, shorter or longer,
  and the worker's otherwise:

  ```kotlin
  val sendMail = TaskType.json<Mail>("send-mail", executionTimeout = Duration.ofSeconds(30))
  val monthlyReport = TaskType.json<Report>("monthly-report", executionTimeout = Duration.ofHours(1))
  ```

  The handler reads when its run's timeout ends from
  `context.deadline`, for example to give an HTTP client no more time
  than the run has left
  ([ADR 0042](adr/0042-creation-time-and-deadline-in-the-task-context.md)).
- A worker that dies with a task leaves a lock that expires; the next
  worker asks the policy with a `LockExpiredException` as the cause.
- A payload version the handler cannot read fails the task at once,
  without asking the policy ([Payload versions](#payload-versions)).
- `RetryLaterException`, `ResourceUnavailableException` and
  `HandOverException` are decisions, not failures; the policy is not
  asked and the attempt does not count, see
  [`concepts.md`](concepts.md#decisions-a-handler-reports).

### What `last_error` says

A failed attempt stores `Throwable.toString()` of what was thrown in
`last_error`, on a retry and on `FAILED`. That column is read by
operators with SQL, by the views, by code through `tasks()`, and
sometimes by agents, and exception messages can quote what the code
was working on: values a constraint refused, a URL with its query
string, parser input. One `ErrorDescription` per queue decides the text
instead
([ADR 0043](adr/0043-error-text-of-a-failed-attempt.md)):

```kotlin
val iban = Regex("[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}")

val pendenz = Pendenz.create {
    dataSource(dataSource)
    errorDescription { _, error ->
        // Name the innermost cause next to the outer exception, without IBANs, at most 2000 characters
        val root = generateSequence(error) { it.cause }.last()
        "${error.javaClass.name} caused by $root".replace(iban, "<iban>").take(2000)
    }
}
```

It receives the task's context, so a rule can differ by `context.type`,
and it applies to every exception that ends an attempt: what the handler
threw, its timeout, an unreadable payload and an expired lock. Reasons
given on purpose, for a rejection, a deferral, a hand-over or by an
operator, are stored as given. A description that throws is logged, and
the attempt keeps only the exception's class name. Listeners still
receive the exception itself.

A failed task comes back with `restart`, from code or SQL. Its attempts
are kept, so after five failures under the default policy the restarted
task gets one more attempt, not five; set `attempts` to 0 with SQL for
a fresh start ([`sql-recipes.md`](sql-recipes.md#restart-a-failed-task)).

## Worker options

`startWorker`, `runWorker`, `runPending` and `runMaintenance` take
`WorkerOptions`; every option has a default:

```kotlin
pendenz.startWorker(applicationScope, WorkerOptions(parallelism = 8, executionTimeout = Duration.ofMinutes(2)))
```

| Option | Default | Meaning |
|---|---|---|
| `parallelism` | 4 | tasks one worker runs at the same time |
| `pollInterval` | 500 ms | wait after a poll that found nothing |
| `heartbeatInterval` | 5 s | how often a running task renews its lock |
| `lockTimeout` | 60 s | how long others wait for a missing heartbeat before they take the task over; at least twice `heartbeatInterval` |
| `executionTimeout` | 10 min | how long one handler run may take, for task types without a timeout of their own |
| `shutdownTimeout` | 30 s | how long a stopping worker lets running handlers finish before it cancels them and releases their tasks |
| `maintenanceInterval` | 1 min | how often the sweep runs: schedules, finished cycles, retention; zero turns it off on this worker |
| `retentionBatchSize` | 1000 | rows one retention statement removes at most |
| `wakeUp` | `WakeUp.POLLING` | how the worker waits; `PostgresNotify` wakes it on commit ([ADR 0014](adr/0014-wait-instead-of-poll.md)) |
| `claimStrategy` | `FairClaim()` | which due tasks a poll takes; `FifoClaim` for arrival order ([ADR 0015](adr/0015-claim-strategy.md)) |

Values that cannot work, such as a `lockTimeout` shorter than two
heartbeats, are refused when the options are built. From Java,
`WorkerOptions.builder()` sets them one at a time; with Spring Boot they
are the properties `pendenz.worker.*`.

## Around every handler

An interceptor encloses every handler run: a logging context, a span, an
identity for the duration of the run, or an exception of a client
library turned into a decision such as `RetryLaterException`. With
`kotlinx-coroutines-slf4j`, the task's id and correlation go into the
MDC for every line the handler logs:

```kotlin
val pendenz = Pendenz.create(TaskTable(name = "billing_task")) {
    dataSource(dataSource)
    interceptor { context, proceed ->
        val mdc = mapOf("task.id" to context.id.toString(), "task.type" to context.type) +
            listOfNotNull(context.correlationId?.let { "correlation.id" to it })
        withContext(MDCContext(MDC.getCopyOfContextMap().orEmpty() + mdc)) { proceed() }
    }
    register(sendInvoice) { _, payload -> invoiceService.send(payload.invoiceId) }
}
```

The first interceptor registered is the outermost. They run inside the
execution timeout and before the outcome is written; what one throws
counts as if the handler had thrown it. An interceptor sees the task's
context, not its payload, and calls `proceed` exactly once: a run it
skips, or runs twice, is a failed attempt. `runPending()` goes through
the same interceptors. See ADR 0036.

## Who calls

`enqueue`, `startCycle` and the operator actions take the caller's
identity as `createdBy` or `actor`, and `enqueue` and `startCycle` a
`correlationId`. In a web application both are usually known from the
request. `CallerDefaults` supplies them where a call leaves them out:

```kotlin
val pendenz = Pendenz.create(TaskTable(name = "billing_task")) {
    dataSource(dataSource)
    callerDefaults(object : CallerDefaults {
        override fun actor() = currentUser.get()?.email
        override fun correlationId() = MDC.get("traceId")
    })
    register(sendInvoice) { _, payload -> invoiceService.send(payload.invoiceId) }
}
```

- A value passed in the call wins; null from the defaults leaves the
  field empty.
- The filled actor is the one `Permissions` checks and the one stored in
  `created_by`.
- The defaults are read on the caller's thread before anything else, so
  thread-local state of the request is visible. What they throw reaches
  the caller, and nothing is written.
- `approve`, `approveCycle` and `decline` always take their actor
  explicitly, because the four-eyes rule compares it with the creator.
- Follow-ups keep their parent's creator and correlation, and schedule
  occurrences carry the schedule's mark; neither asks the defaults.

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

The key stays taken as long as the row exists, also after the task
succeeded ([ADR 0009](adr/0009-idempotency-key.md)).

The first enqueue wins by default. For work where the latest call
describes the work better, a task type replaces the waiting task
instead: `OnDuplicateKey.REPLACE`. While the task with that key is
`PENDING` or `SCHEDULED`, a repeat replaces its payload, run time, lane
and priority, and with them the resource and the preview; the status
follows from the new values, so an approval rule asks again. A task
that is running, waiting for approval, failed or finished stays as it
is ([ADR 0044](adr/0044-repeat-enqueue-replaces-a-waiting-task.md)).

```kotlin
// Every change to a product enqueues a reindex; one task per product waits, with the latest fields,
// and runs 30 seconds after the last change
val reindex = TaskType.json<Reindex>("reindex-product", onDuplicateKey = OnDuplicateKey.REPLACE)

pendenz.enqueue(
    reindex,
    Reindex(product.id, changedFields),
    runAt = Instant.now().plusSeconds(30),
    idempotencyKey = "product-${product.id}",
)
```

A replacement keeps the task's id, creation time, creator and
correlation id, and fires no second `onEnqueued`. Schedule occurrences
are never replaced.

## Retention

A task type says what happens to its finished rows, those in `SUCCEEDED`
and `CANCELLED`, and workers apply the rule during their maintenance sweep:

```kotlin
val sendInvoice = TaskType.json<SendInvoice>("send-invoice", retention = Retention.delete(after = Duration.ofDays(30)))
val archiveOrder = TaskType.json<ArchiveOrder>("archive-order", retention = Retention.archive(Duration.ofDays(7), "order_task_archive"))
```

`Retention.byStatus` gives each of the two statuses a rule of its own,
for example to drop successes soon and keep cancellations, with who
cancelled, for a year
([ADR 0045](adr/0045-retention-per-final-status.md)):

```kotlin
val sendInvoice = TaskType.json<SendInvoice>(
    "send-invoice",
    retention = Retention.byStatus(
        succeeded = Retention.delete(Duration.ofDays(1)),
        cancelled = Retention.archive(Duration.ofDays(365), "billing_task_archive"),
    ),
)
```

Rows in `FAILED`, `REJECTED` and `QUARANTINED` wait for a person and
are never removed; cancelling one hands it to the rule for `CANCELLED`
([ADR 0011](adr/0011-retention.md)).

The sweep runs beside the claim loop every `maintenanceInterval` (one
minute by default), the first time one interval after the worker starts.
An interval of zero turns it off on that worker, for applications that
leave retention to one worker or call `pendenz.runMaintenance()` on a
schedule of their own. The same sweep checks the schedules of recurring
tasks (ADR 0033); with schedules, keep it on in at least one worker, or a
series an operator broke is repaired only when a worker starts.

## Payload versions

A payload class changes over time, while rows written by the old class
still wait in the table. The task type carries a `version`, and every
row stores the version it was written with. For each step from an old
version to the next, an upgrade rewrites the stored values before the
handler reads them:

```kotlin
@Serializable
data class SendInvoice(val invoiceId: Long, val customer: String, val currency: String)

val sendInvoice = TaskType.json<SendInvoice>(
    "send-invoice",
    version = 2,
    upgrades = mapOf(
        1 to PayloadUpgrade.json { v1 -> JsonObject(v1 + ("currency" to JsonPrimitive("EUR"))) },
    ),
)
```

- The map is keyed by the version an upgrade starts from; `1 to ...`
  turns version 1 into version 2. Every step up to `version` needs one.
- An upgrade works on the stored values, not on the old class, which
  may no longer exist. `PayloadUpgrade.json` hands over the parsed JSON
  document; a `PayloadUpgrade` of your own gets the map of column values
  of a type with payload columns.
- A row with a version newer than the handler, or older without a path
  up, fails at once with `UnsupportedPayloadVersionException`; no retry
  can fix it. Deploy the handler that reads a new version before
  anything enqueues it, and restart such tasks once it is out.
- [`sql-recipes.md`](sql-recipes.md#which-payload-versions-are-still-in-the-table)
  shows which versions are still in the table; Java uses
  `JacksonPayloadUpgrade`, see [`java.md`](java.md).
  ([ADR 0010](adr/0010-payload-versioning.md))

## Read and steer from code

What the SQL recipes do, the queue also does from code, with permission
checks and listener events. `find` reads one task, `tasks` lists them
newest first:

```kotlin
val task: TaskRecord<Long>? = pendenz.find(42)

val query = TaskQuery(types = setOf("send-invoice"), statuses = setOf(TaskStatus.FAILED), limit = 100)
val page = pendenz.tasks(query)
val nextPage = pendenz.tasks(query.after(page.last()))
```

`TaskQuery` filters by `createdBy`, `types`, `statuses`,
`correlationId`, `lane` and `parentId`, all optional; `limit` is 50 by
default and at most 1000. Pages continue with `after`, and neither skip
nor repeat a task while new ones arrive. A `TaskRecord` has every mapped
column: status, attempts, `lastError`, run and creation times, lock,
creator, approval, lane, resource, correlation and result. Its `payload`
is the map of stored column values, not the decoded class.

The operator actions move a task the way the status model allows and
return whether it moved; a task in another status is left alone:

| Call | From | To |
|---|---|---|
| `restart(id)` | `FAILED`, `REJECTED`, `QUARANTINED` | `PENDING`, to run now; attempts kept |
| `complete(id)` | `FAILED`, `REJECTED`, `QUARANTINED` | `SUCCEEDED`, without running |
| `quarantine(id, reason)` | `SCHEDULED`, `PENDING`, `RETRY_PENDING`, `FAILED`, `REJECTED` | `QUARANTINED` |
| `cancel(id)` | every status that is not final | `CANCELLED`; a running task at its next heartbeat |
| `approve(id, actor)` | `AWAITING_APPROVAL` | `PENDING` or `SCHEDULED` |
| `decline(id, actor, reason)` | `AWAITING_APPROVAL` | `CANCELLED` |

The first four take an optional `actor`, filled from `CallerDefaults`
when left out ([Who calls](#who-calls)); `Permissions` decide whether
that actor may, and a refusal throws `NotPermittedException` before
anything is written. Every move is reported to listeners as
`onOperatorMove` ([ADR 0031](adr/0031-status-transitions-of-operator-actions.md)).

Resource limits, rates and blocks have their calls too, beside the SQL:
`setResourceLimit(name, maxRunning)`, `removeResourceLimit(name)` and
`resourceLimits()`; `setResourceRate(name, maxStarts, per)`,
`removeResourceRate(name)` and `resourceRates()`;
`blockResource(name, until, reason)`, `unblockResource(name)` and
`resourceBlocks()`. `backlog()` counts what
waits per type and status, `schedules()` says where every schedule
stands.

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
