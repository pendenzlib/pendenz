# Idempotency key

- Status: accepted
- Date: 2026-10-19

## Context

Callers retry. A web request that enqueues "send invoice 42" may be sent
twice, a message consumer may redeliver, an agent may call the same tool
again. Without a guard every retry becomes another task and the invoice
goes out twice. The planning settled the shape of the key: unique per
task type, so that the same business key, say `ticket-4711`, may be used
for different actions on the same ticket.

## Decision

- The mapping gets an `idempotencyKey` column, nullable text, with a
  unique index over `(type, idempotencyKey)`. The generated DDL includes
  the index; the feature depends on it.
- `enqueue` takes an optional `idempotencyKey`. Without one, every call
  inserts a row as before.
- With one, the insert is `ON CONFLICT DO NOTHING` and, when nothing was
  inserted, the id of the existing task is looked up and returned. The
  caller cannot tell a first enqueue from a repeat by the return value,
  and does not need to: either way the task exists under that id.
- The key stays unique for the lifetime of the row, including after the
  task succeeded. A second enqueue with the same key after success is a
  repeat, not new work. Once retention removes the row, the key is free
  again.
- Payload, run time and creator of a repeat are ignored. The first
  enqueue wins.

## Rejected alternatives

- **Catching the unique violation.** PostgreSQL aborts the whole
  transaction on a constraint error, which would break enqueuing inside
  the caller's transaction.
- **Key unique across all types.** Forces callers to prefix keys with the
  action, which the type column already records.
- **Freeing the key when the task reaches a final state.** Lets the same
  key create a second task after the first succeeded, which is exactly
  the duplicate the key is meant to prevent.
- **Returning a flag that says whether the task was created.** Useful at
  first sight, but it would push callers to branch on it; the point of
  the key is that they do not have to.

## Consequences

- One more column and one more index in every mapping.
- `TaskRecord` exposes the key so operators and tests can see it.
- A table created without the unique index fails on the first keyed
  enqueue with a PostgreSQL error naming the missing constraint.
- `TaskListener.onEnqueued` fires for a repeat as well, with the existing
  task's id, because the caller cannot tell the two apart either. A
  listener that counts rows must count the table, not this hook.
