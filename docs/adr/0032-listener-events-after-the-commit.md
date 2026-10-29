# Listener events after the commit, for every operation

- Status: accepted
- Date: 2026-10-29

## Context

`TaskListener` is the hook for metrics, logs and audits (ADR 0022 builds
the Micrometer module on it). Its KDoc says that the events of a run come
from the worker after the outcome is written, and that `onEnqueued`, the
approval events and the operator's moves come from the caller. The review
of 2026-10-25 found that this holds for the worker only:

- **The caller's events fire before the commit.** The worker reports
  after `store.transaction` has returned. `Pendenz` calls `onEnqueued`,
  `onAwaitingApproval`, `onApproved` and `onDeclined` inside the block
  it passes to `store.transaction`, also when the queue runs the
  transaction itself. A cycle of 500 payloads whose 400th insert fails
  rolls back, but 399 `onEnqueued` have fired, and
  `pendenz.tasks.enqueued` counts 399 tasks that do not exist. A
  connection lost at the commit of an approval leaves an `onApproved`
  for a task that still waits.
- **A repeat under an idempotency key reports a task it did not store.**
  ADR 0009 lets `onEnqueued` fire for a repeat, "the hook counts calls,
  not rows". `onAwaitingApproval` fires on the same path, decided by the
  status of the task that was built, not of the row that exists. An
  agent that enqueues `refund` with key `order-7` a second time, after
  the first one has succeeded, makes a listener that mails approvers ask
  for approval of a finished task.
- **Operator moves fire nothing.** `restart`, `complete`, `quarantine`
  and `cancel` carry an actor (ADR 0018) and a permission check, and
  report to no listener, though the KDoc says they do. An audit through
  the listener sees approvals and declines, but not a cancel, a restart
  or a task completed by hand.
- **Cycles report by one path and not by the other.** A cycle started
  from a payload list fires `onEnqueued` and `onAwaitingApproval` per
  task; the same cycle started from a query fires nothing, since the
  store only returns a count. `approveCycle` approves thousands of tasks
  and fires no `onApproved`. `pauseCycle`, `resumeCycle` and
  `cancelCycle` fire nothing either. The same operation produces
  different metrics depending on how it was called.

Two kinds of transaction are involved. In the first, the queue opens and
commits it: `DataSourceConnectionProvider`, the in-memory store, or the
Spring provider outside a Spring transaction. In the second, the caller
owns it: `enqueue(connection, ...)` and `startCycle(connection, ...)`,
or the Spring provider joining a transaction on the calling thread
(ADR 0023). Only in the first does the queue see the commit.

## Decision

### Events of an operation are delivered after its commit

`Pendenz` collects the events of an operation while its transaction
runs, and hands them to the listeners once the transaction has
committed, in the order they occurred. When the transaction rolls back
or the block throws, the events are dropped. The worker keeps reporting
as today.

The store learns to defer an action to the commit:

- `TaskStore` gains `afterCommit(action)`, called by `Pendenz` right
  after `transaction` has returned. The default runs the action at once,
  which is right for every store whose `transaction` commits before it
  returns, including `InMemoryTaskStore`.
- `JdbcTaskStore` delegates to `ConnectionProvider.afterCommit(action)`,
  a new method with the same default. `DataSourceConnectionProvider`
  keeps it.
- `SpringConnectionProvider` overrides it: inside an active Spring
  transaction it registers a `TransactionSynchronization` whose
  `afterCommit` runs the action; outside one it runs the action at once.
  The joined transaction runs on the calling thread without a suspension
  point between `inTransaction` and `afterCommit`, so the
  synchronization is registered on the transaction that holds the rows.

With an explicit `Connection`, the caller commits, and the queue cannot
see when. These overloads deliver their events when the operation
returns, before the caller's commit, as their KDoc says today. An
application that needs events after its commit lets the queue join its
transaction through a provider, as the Spring provider does, instead of
passing the connection.

Delivery is at most once. A process that dies between the commit and the
delivery loses the events of that operation. The listener stays a hook
for metrics and logs. The table records the status, `created_by` and
`approved_by`; who restarted or cancelled a task it does not record
(ADR 0018 leaves that open), so an audit that must not lose an entry
needs that decision, not this hook.

### An event means a row changed

- `onEnqueued` and `onAwaitingApproval` fire when a row was inserted.
  A repeat under an idempotency key fires neither. `TaskStore.insert`
  says which happened: it returns the id together with whether the row
  is new. ADR 0009 gets a note; its sentence on counting calls goes.
- `onApproved`, `onDeclined` and the new events below fire only when
  the store reports that the row changed, as `approve` and `decline` do
  today.

### Operator moves get an event

`restart`, `complete`, `quarantine` and `cancel` fire
`onOperatorMove(move: OperatorMove<ID>)`. `OperatorMove` is a class with
the task's id and type, the action (`Action.RESTART`, `COMPLETE`,
`QUARANTINE`, `CANCEL`), the status the task left, the status it
reached, the actor and the reason. A class rather than six parameters,
so that a property added later breaks no listener.

The status the task left comes from the store: `TaskStore.move` returns
it, or null when nothing changed, instead of a Boolean. The store reads
it from the row it updates, in the same statement, so a concurrent
change between `find` and the update cannot make the event lie.

`approve` and `decline` keep their own events and fire no
`onOperatorMove`. The generated SQL functions (`_restart`, `_cancel`,
...) run outside the JVM and fire nothing. ADR 0018 leaves them to
database grants; what they do is seen in the table, not in the
listener.

### Cycle operations report once per operation, with counts

A cycle operation reports one event, not one per task:

- `onCycleStarted(cycle: CycleEvent<ID>)` after `startCycle`, with the
  number of tasks inserted and how many of them wait for approval. The
  payload list and the query report alike; the per-task `onEnqueued`
  and `onAwaitingApproval` of the payload list go.
- `onCycleApproved(cycle: CycleEvent<ID>)` after `approveCycle`, with
  the actor and the number of tasks approved, when that is more than 0.
- `onCycleMoved(cycle: CycleEvent<ID>)` after `pauseCycle`,
  `resumeCycle` and `cancelCycle`, with the status the cycle left and
  reached, the actor and the number of tasks moved with it.

`CycleEvent` carries the cycle's id and type and the fields above. A
cycle of 100 000 tasks then costs one event, not 100 000, and the query
path needs no list of ids from the database.

The finishing sweep, which moves a cycle to `SUCCEEDED` or `FAILED` in
the maintenance run, is not part of this decision.

### The Micrometer module follows

- `pendenz.tasks.enqueued` counts rows: single enqueues, plus the task
  count of `onCycleStarted`. `pendenz.tasks.approval{outcome=requested}`
  and `{outcome=approved}` take the cycle counts as well.
- A new counter `pendenz.tasks.operator` with `type` and `action` counts
  operator moves; cycle operations are counted by
  `pendenz.cycles.operations` with `type` and `operation`.

## Rejected alternatives

- **Keep firing inside the transaction and document it.** Metrics
  would keep counting rolled-back work, and every listener that sends a
  mail or writes an audit would need to know whether the operation
  committed, which it cannot.
- **Fire after the explicit-connection overloads' commit** by asking
  the caller to call back, or by returning the events. The overloads
  exist so that enqueuing is one line inside the caller's transaction;
  an extra call the caller can forget turns a missing event into a
  silent bug. A provider that joins the caller's transaction gives the
  same result without one.
- **A transactional outbox for events,** written in the same
  transaction and delivered from the table. Exactly once, but a second
  table in the user's schema (principle 8) and a delivery loop, for
  hooks meant for metrics and logs. An exact audit of operator actions
  belongs to the audit column ADR 0018 leaves open.
- **One `onEnqueued` per cycle task, on both paths.** The query path
  would have to return every id it inserted, and a large cycle would
  call each listener 100 000 times inside one operation.
- **A hook per operator action** (`onRestarted`, `onCancelled`, ...).
  Four methods where one with the action as a property does, and a
  fifth for every action added later.
- **Keep counting repeats in `onEnqueued`,** or report them through a
  hook of their own. Nothing in the library or its examples needs the
  count of repeats; an application that wants it compares the id it got
  with the one it passed, or counts its own calls. A hook can be added
  later without breaking anyone.

## Consequences

- `TaskListener` gains `onOperatorMove`, `onCycleStarted`,
  `onCycleApproved` and `onCycleMoved`, each with an empty default; no
  existing listener breaks. Its KDoc says when events are delivered.
- `OperatorMove` and `CycleEvent` are new public classes in the root
  package.
- `TaskStore` changes: `insert` reports whether the row is new, `move`
  returns the status left, and `afterCommit` is new. `ConnectionProvider`
  gains `afterCommit` with a default. Both are public interfaces, before
  1.0; custom stores and providers adapt.
- `onEnqueued` no longer fires for repeats or for cycle tasks, and
  `PendenzMetrics` counts cycles through the new events. ADR 0009 and
  ADR 0022 get a note; `docs/metrics.md` and `docs/spring.md` change.
- Tests: rollback after a failed insert in a payload cycle fires
  nothing; a repeat under an idempotency key fires nothing; every
  operator move fires one `onOperatorMove` with the right source and
  target; the query and the payload cycle report alike; inside a Spring
  transaction that rolls back, no event arrives, and after its commit
  they do.

## Decisions taken with the acceptance (2026-10-29)

1. Events are delivered after the commit where the queue or its
   provider sees it; the explicit-connection overloads fire at return.
2. A repeat under an idempotency key fires nothing.
3. Restart, complete, quarantine and cancel fire one `onOperatorMove`
   with an `OperatorMove`; approve and decline keep their events.
4. Cycles report once per operation with counts; the payload list stops
   firing per-task events.
5. Micrometer counts rows, cycle tasks included, and gains counters for
   operator moves and cycle operations.

Noted during implementation:

- `TaskStore.insert` returns an `InsertedTask` with the id and `isNew`.
  `TaskStore.move` returns the status left, and
  `CycleStore.updateCycleStatus` the cycle status left, so the cycle
  events name their source as reliably as the operator moves. Both read
  it in the statement that changes the row: the PostgreSQL statements
  lock the row in a sub-select and return its status. `TaskSql` and
  `CycleSql` name the returned column `MOVED_FROM`.
- One class, `CycleEvent`, serves the three cycle events. Its `action`
  tells them apart, `from` is null for a start, and an approval reports
  `ACTIVE -> ACTIVE`.
- Follow-ups a handler enqueues follow the same rule: one whose
  idempotency key was taken fires nothing.
- The Spring provider defers only inside an actual transaction with
  synchronization active; otherwise its own transaction has committed
  when `afterCommit` is called, and the action runs at once.
- Both new counters tag the operation `action`, not `operation` as
  proposed above, since the event classes call it that. Cycle actions
  drop the `_cycle` suffix: `start`, `approve`, `pause`, `resume`,
  `cancel`.
