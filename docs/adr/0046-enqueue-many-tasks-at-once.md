# Enqueue many tasks at once

- Status: proposed
- Date: 2026-11-09

## Context

An import of 2,000 orders that wants one task per order calls `enqueue`
2,000 times. Each call is a transaction of its own; a commit waits for
the write-ahead log to reach the disk. Measured against PostgreSQL 17 in
a local container, with the default task table:

| 2,000 tasks | Time |
|---|---|
| `enqueue`, one transaction each | about 12 s |
| `enqueue(connection, ...)` in a loop, one transaction | about 440 ms |
| one JDBC batch of the same `INSERT`, one transaction | about 85 ms |

The loop in one transaction is open to a caller today, but only through
`enqueue(connection, ...)` with a connection the caller manages. The
batch is not open to anyone: the JDBC store prepares and executes one
statement per task, and the round trip per statement grows with the
distance to the database. A handler's follow-ups (ADR 0019) take the
same path; that ADR left a batch insert "for when someone measures it".

Cycles (ADR 0016) create many tasks in one statement, but only from rows
that are already in a table. A list in the application's memory, such
as a parsed file or the response of another service, has no such way.

## Decision

`enqueueAll` enqueues a list of tasks in one transaction, and the JDBC
store inserts them as one batch.

- **`EnqueueRequest<P>(type, payload, idempotencyKey, runAt, lane,
  priority, resource, correlationId)`** describes one task: the per-task
  parts of `enqueue`, all optional but type and payload. The key comes
  third, because an import usually sets the key and nothing else, and a
  Java caller passes the parameters by position.
- **`Pendenz.enqueueAll(requests, createdBy)`** in a transaction of its
  own and **`enqueueAll(connection, requests, createdBy)`** in the
  caller's, both returning the ids in the order of the requests. The
  creator is one per call, because one caller enqueues the list; it
  falls back to `CallerDefaults.actor` as for `enqueue`. A request
  without a correlation id gets `CallerDefaults.correlationId`.
  Requests of different types may be mixed.
- **The result is that of `enqueue` called for every request in order,
  inside one transaction.** A taken idempotency key returns the existing
  task's id and stores nothing, or replaces a waiting task when the type
  says so (ADR 0044); two requests with the same type and key in one
  list behave as two such calls do. Ids are generated in the order of
  the list, so workers claim tasks of equal priority and run time in
  that order.
- **All or nothing.** Every request is checked before the first row is
  written: the type is registered, the lane is not a cycle's, key and
  creator are not reserved for schedules, and the creator may enqueue
  each type in the list (ADR 0018). A failure throws and writes nothing;
  in the caller's transaction no partial list is left behind for the
  caller to roll back. A database error, such as a violated constraint
  of the application, fails the whole list.
- **Listener**: one `onEnqueued` per new row and one `onAwaitingApproval`
  per task sent to approval, as for single calls, after the commit or on
  return from the call in the caller's transaction (ADR 0032).
- An empty list returns an empty list and opens no transaction.

Underneath:

- **`TaskStore.insertAll(tx, tasks)`** returns one `InsertedTask` per
  task, in order. The default calls `insert` for each, so the in-memory
  store and every existing custom store keep working.
- **The JDBC store** binds the tasks to its insert statement and runs
  them as one JDBC batch, with generated keys. A row whose key was taken
  returns nothing. To tell which request a returned row belongs to,
  the insert statements of `TaskSql` return the type and the idempotency
  key beside the id; the store walks the requests and the returned rows
  in order and matches a row to a request with the same type and key,
  or to a request without a key. Requests left without a row take the
  path a single insert takes for a taken key: replace the waiting task,
  or find the existing id. A list that mixes tasks with and without an
  id of their own runs as consecutive batches per statement, so the
  order holds.
- **Follow-ups** (ADR 0019) are inserted with `insertAll`, in the
  outcome's transaction as before.

## Rejected alternatives

- **Leave it to the caller.** A loop over `enqueue(connection, ...)`
  gives one transaction, but no batch, and it asks every caller to
  manage a connection for a performance concern of the library.
- **One multi-row `INSERT ... SELECT FROM unnest(...)`.** One statement
  instead of a batch, but every payload column would need an array type
  of its own, and `jsonb` columns arrays of JSON text cast per element.
  The JDBC batch reuses the insert statement as it is.
- **An id per request.** `EnqueueRequest` would need the table's id type
  as a second type parameter, which Kotlin cannot infer from type and
  payload, so every request would have to name it. A caller that brings
  its own ids calls `enqueue` with `id`, in its own transaction loop.
- **`createdBy` per request.** One call has one caller; a list from
  several actors is several calls. The permission check stays one per
  type and actor.
- **A count or a flag per id telling new from existing.** `enqueue`
  returns the id alone (ADR 0009); the list keeps that.
- **Splitting a long list into several transactions.** That would break
  all or nothing. A caller that wants chunks calls `enqueueAll` per
  chunk; a dataset already in a table is a cycle.
- **Return the matching columns only from a separate batch statement.**
  A second pair of insert statements in `TaskSql` would keep the
  existing ones unchanged for custom dialects, at the price of two
  statements that differ only in their `RETURNING`. Returning two more
  columns from the existing ones costs nothing for a single insert.

## Consequences

- New public API: `EnqueueRequest`, `Pendenz.enqueueAll` in both
  forms, `TaskStore.insertAll` with its default,
  `PendenzJava.enqueueAll` in both forms.
- The contract of `TaskSql.insert` and `insertWithId` changes: they
  return type and idempotency key beside the id. A custom dialect that
  returns the id alone works for single inserts and fails `enqueueAll`
  with an exception that names the missing column. Named in the
  changelog.
- No schema change.
- The guide shows `enqueueAll` next to enqueue in the caller's
  transaction.
