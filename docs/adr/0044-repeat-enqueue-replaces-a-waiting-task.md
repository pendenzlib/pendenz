# A repeat enqueue may replace a waiting task

- Status: proposed
- Date: 2026-11-08

## Context

ADR 0009 makes the idempotency key unique per type for the lifetime of
the row. A repeat enqueue with a taken key inserts nothing and returns
the existing task's id; its payload, run time and options are dropped:
the first enqueue wins.

That fits a repeated request: "send invoice 42" sent twice is one
invoice. It does not fit work where the latest call describes the work
better than the first one:

- **Coalescing.** Every change to product 42 enqueues "reindex product
  42". While a reindex is still waiting, further changes should not add
  tasks, but the waiting one should carry the newest payload, say the
  fields that changed.
- **Debouncing.** "Recalculate the quote in 30 seconds" on every edit:
  each edit should push the waiting task's run time back, so it runs
  once, 30 seconds after the last edit.
- **Correcting a planned task.** A reminder scheduled for Friday is
  enqueued again with a new date and text; the planned task should
  follow.

Today the application has to find the waiting task itself and change
the row with SQL, outside the API and without the checks enqueue makes.

## Decision

A task type says what a repeat enqueue does, with a new aspect
`onDuplicateKey: OnDuplicateKey`:

- **`KEEP`**, the default, is ADR 0009 unchanged: the first enqueue
  wins.
- **`REPLACE`**: when the existing task is still `PENDING` or
  `SCHEDULED`, the repeat replaces what describes the work: the payload
  columns and the payload version, the run time, lane and priority, and
  what the type derives from the payload, the resource and the preview.
  The status follows from the new values as on a first enqueue:
  `PENDING`, `SCHEDULED`, or `AWAITING_APPROVAL` when the type's approval
  rule requires it for the new payload. Approver and approval time are
  cleared, because nobody approved the new payload. Id, creation time,
  creator, correlation id, parent and attempts stay: it is still the
  task the first enqueue created. The row's version is raised, as on
  every change.
- **In every other status the repeat is ignored**, as with `KEEP`. A
  running task is not changed under its handler; a task awaiting
  approval is not changed while someone may be looking at its preview;
  a final task keeps its key, as ADR 0009 decided.
- **The return value is the id**, in every case. ADR 0009 rejected a
  flag that tells a first enqueue from a repeat; a replacement does not
  change that reason.

Where it applies:

- `enqueue`, in its own and in the caller's transaction, and follow-ups
  a handler enqueues (ADR 0019): the policy belongs to the type, so
  every way of enqueuing a task of that type gets it, the MCP module's
  tools included.
- **Not schedule occurrences** (ADR 0033). Their key is the schedule's
  fire time, and two instances that insert the same occurrence must
  not replace each other's row.
- The permission check is the enqueue check (ADR 0018); replacing is
  enqueuing.

Underneath:

- `NewTask` gains `replaceWaiting: Boolean`, false by default, and
  `InsertedTask` gains `replaced: Boolean`, false by default. A store
  that finds the key taken and `replaceWaiting` set replaces the row
  when it waits, and reports `replaced`.
- `TaskSql` gains `replaceWaiting`, an `UPDATE` that matches type, key
  and the two waiting statuses and returns the id. The JDBC store runs
  it only after the insert found the key taken, so a first enqueue
  costs what it costs today. The parameter is nullable with a default
  of `null`; a dialect without it fails a replacing enqueue with an
  exception that says so.
- **Listener:** a replacement inserts no row and fires no `onEnqueued`
  (ADR 0032 counts rows). When it sends the task to approval,
  `onAwaitingApproval` fires, as it would for a new task.

## Rejected alternatives

- **The policy per call** on `enqueue`. A key's meaning belongs to the
  type, like its uniqueness (ADR 0009); per call, any caller, an agent
  through MCP among them, could replace the payload of a type whose
  first enqueue must win. `enqueue` also has ten parameters already.
- **`ON CONFLICT DO UPDATE`** in the insert. One statement instead of
  two, but every dialect would need a second insert statement, and the
  condition on the status would be part of the insert. The update after
  a failed insert keeps the insert as it is and runs only for repeats.
- **Replacing a task in `RETRY_PENDING`.** It has failed before, and its
  `last_error` and attempts belong to the old payload. An operator's
  restart (ADR 0031) is the way back for it.
- **Freeing the key once the task runs, so a repeat creates a new
  task.** This is the debounce some applications want, but it gives up
  the guarantee of ADR 0009 that a key stands for one task. A caller
  that wants a new task per round puts the round into the key.
- **More values**, such as replacing only the run time. A caller that
  wants to keep the payload passes it again.

## Consequences

- No change for existing types; no schema change.
- New public API: `OnDuplicateKey`, `TaskType.onDuplicateKey` with its
  constructor parameter and builder method (ADR 0041),
  `NewTask.replaceWaiting`, `InsertedTask.replaced`,
  `TaskSql.replaceWaiting`.
- A custom store must honour `replaceWaiting`; one that ignores it keeps
  the first enqueue, which is the behaviour of `KEEP`. Named in the
  changelog.
- The guide shows coalescing and debouncing next to the idempotency
  key.
