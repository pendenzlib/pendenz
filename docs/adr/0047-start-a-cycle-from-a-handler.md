# Start a cycle from a handler

- Status: proposed
- Date: 2026-11-12

## Context

A cycle (ADR 0016) starts when the application calls `startCycle`.
Some runs start from a task: a recurring task (ADR 0033) that fires
every Monday at 13:00 and starts the weekly invoice run, or an import
task that has loaded a file into a table and wants one task per row.

Today such a handler calls `Pendenz.startCycle` itself:

```kotlin
register(startInvoiceRun) { _, run ->
    pendenz.startCycle(sendInvoice, CycleTasks.query(UNSENT_INVOICES), description = run.description)
}
```

That has the failure ADR 0019 describes for follow-ups enqueued in a
transaction of their own, and a wiring problem:

- `startCycle` commits in a transaction of its own. When the worker
  dies after that commit and before the task's outcome is recorded, the
  lock expires, the handler runs again and starts a second cycle over
  the same rows.
- A handler that throws after the start leaves the cycle in place and
  is retried, which starts a second one as well.
- The handler needs a reference to the queue it is registered on. The
  queue exists only after the builder that takes the handler has
  returned, so the application needs a late reference (`lateinit`, a
  provider), and with a dependency injection container the handler and
  the queue depend on each other.

A handler cannot use `TaskContext.enqueue` for this: a follow-up may
only keep its parent's cycle lane (ADR 0018), there is no cycle row for
it to belong to, and the rows of a query would have to pass through the
application's memory.

## Decision

A handler starts a cycle on its context. The worker inserts the cycle
row and the cycle's tasks in the transaction that records the outcome,
as it inserts follow-ups.

```kotlin
register(startInvoiceRun) { ctx, run ->
    ctx.startCycle(sendInvoice, CycleTasks.query(UNSENT_INVOICES), description = run.description)
}
```

- **`TaskContext.startCycle(type, tasks, description, priority, id,
  resource)`** buffers a cycle start. Nothing is written while the
  handler runs. The parameters are those of `Pendenz.startCycle`
  without `createdBy` and `correlationId`, which are inherited. It
  returns nothing: the cycle's id is generated at the insert, after the
  handler has returned. A caller that needs the id beforehand passes
  `id`, as with `Pendenz.startCycle`.
- **Written with a `SUCCEEDED` outcome only**, after the follow-ups, in
  the order of the calls. A handler that throws, a result the validator
  refuses (ADR 0020), a deferred or handed-over run (ADR 0035) and a
  dropped outcome start nothing, as they enqueue no follow-ups. The
  retry runs the handler again and collects again.
- **A query runs when the outcome is recorded**, not when the handler
  calls `startCycle`. It sees what is committed at that moment,
  including what the handler itself committed while it ran.
- **Inherited from the task**: the creator and the correlation id, as
  for follow-ups (ADR 0019). The cycle row and the cycle's tasks carry
  both. A cycle started by an occurrence of a schedule therefore has
  `created_by = 'schedule:<name>'` (ADR 0033), which answers "which
  schedule started this run" in SQL. The prefix stays refused for a
  creator the caller passes; here the queue passes it. The priority is
  the task's unless given, as for follow-ups.
- **The cycle's tasks name the starting task as their parent.** "What
  spawned this task" (ADR 0019) then answers for a cycle's task as for
  a follow-up. The query form writes the parent with the other fixed
  columns of its `INSERT ... SELECT`.
- **The lane is the new cycle's.** A handler that itself runs inside a
  cycle starts a second, independent cycle: its tasks do not count in
  the progress of the first, and the first does not wait for them.
- **Approval applies, permissions are not checked**, as for follow-ups:
  the approval rule of the cycle's type decides with the inherited
  creator whether the tasks start as `AWAITING_APPROVAL`; the handler
  is the application's own code, so `START_CYCLE` is not asked for
  (principle 7).
- **Checked when the handler calls**, so a mistake fails the attempt
  with its own message: cycles are enabled, the type is registered, a
  query that gives a resource per row is not combined with a resource
  for the whole cycle, and the store can run the query form (the
  in-memory store cannot, ADR 0016). The list form works in both
  stores.
- **Listener**: `onCycleStarted` after the commit, and `onEnqueued` for
  the tasks of the list form, as for `Pendenz.startCycle` (ADR 0032).
- **Outside a running handler**, on the context of a `FailedAttempt`
  (ADR 0038) or in a listener, `startCycle` throws, as `enqueue` does.
- **Java**: `TaskContext.startCycle` gets `@JvmOverloads`; a test in
  Java starts a cycle from a blocking handler.

`Pendenz.startCycle` is unchanged. It remains the way to start a cycle
from outside a handler and inside the application's own transaction.

### What the outcome's transaction now contains

The transaction that records the outcome updates the task's row first
and then runs the application's query. Three things follow, each to be
shown by a test on PostgreSQL before this is accepted as built:

- **A long query holds the task's row.** The heartbeat keeps running
  while the outcome is written; its update waits for the row and finds
  the task finished once the transaction commits. The execution timeout
  (ADR 0039) covers the handler, not the query.
- **A lock that expires meanwhile is not taken over.** Another
  worker's expiry (ADR 0027) sees the row as locked by the open
  transaction and, after the commit, as finished.
- **A query that fails aborts the transaction, and the outcome with
  it.** The worker writes again for half the lock timeout, as for any
  failed write of an outcome; a query that is wrong fails every time.
  The task then stays `RUNNING` until its lock expires and takes the
  path of ADR 0027: a failed attempt, a retry by the type's policy, in
  the end `FAILED` with the error of an expired lock. The query's own
  error is in the worker's log. A follow-up that violates a constraint
  of the application takes the same path today.

### Tests

- Through the public API with the in-memory store and `runPending`: a
  handler that starts a cycle with the list form leaves the cycle and
  its tasks; a handler that throws after `startCycle` leaves neither; a
  retry that succeeds leaves one cycle; creator, correlation id, parent
  and priority are inherited; the query form is refused at the call.
- End-to-end on PostgreSQL: the query form from a handler; an occurrence
  of a schedule starts a cycle whose row names the schedule; an outcome
  dropped because an operator cancelled the task starts nothing; a
  query that fails ends in `FAILED` after the lock expired and starts
  nothing; a query that runs longer than the lock timeout starts one
  cycle and the task ends `SUCCEEDED`.
- In Java: a blocking handler starts a cycle.

## Rejected alternatives

- **Leave it to the handler**, with `Pendenz.startCycle` and a cycle id
  derived from the task as a guard. The second attempt then fails at
  the primary key instead of starting a second cycle, so the handler
  has to tell that failure from others and treat it as success, and it
  still needs the late reference to the queue.
- **A schedule that starts a cycle**, `scheduleCycle(name, type, tasks,
  schedule)`. A schedule keeps one occurrence ahead as a task row that
  operators see, cancel to skip a run, and approve (ADR 0033). A cycle's
  tasks must not exist before the fire time, because the query is to run
  then. Such a schedule would either keep a trigger row after all, and
  be this decision with a fixed handler, or need a second mechanism
  with state of its own. It would also cover only the scheduled case,
  and every variation, a query chosen by date, a run skipped while the
  last one is active, would need an option where a handler has code.
- **The deferred hook of ADR 0019**: a function the handler registers
  and the worker calls inside the outcome's transaction, with the
  connection. It would cover this case and business writes as well. It
  is a larger decision: it hands the outcome's transaction to
  application code, and the in-memory store has no connection to give.
  This decision does not stand in its way.
- **Return the cycle's id.** An identity column does not know its value
  before the insert; inserting the cycle row early, in a transaction of
  its own, would bring back the cycle without an outcome.
- **Run the query when the handler calls**, and keep the rows for the
  insert. The rows would pass through the application's memory, which
  ADR 0016 avoids for large runs.
- **Run the cycle start in a second transaction right after the
  outcome's.** The task's row would be free during a long query, but a
  crash between the two would lose the cycle, which is the failure this
  decision removes.
- **The cycle's tasks in the lane of the starting task's cycle.** A
  lane is one cycle's (ADR 0016); pausing or cancelling the first cycle
  would then reach tasks of a run with its own row and status.

## Consequences

- New public API: `TaskContext.startCycle`. No new type; `CycleTasks`
  and `CycleResource` exist.
- `CycleStore.insertTasksFromQuery` writes the parent from its fixed
  task, and the cycle statement of the dialect gains the parent column.
  A custom store or dialect that does not write it leaves the parent of
  such tasks empty; named in the changelog.
- No schema change: creator, correlation id and parent are existing
  columns of the task table, creator an existing column of the cycle
  table. The cycle table has no correlation column; the correlation is
  on the cycle's tasks, as today.
- A cycle's tasks that inherit `schedule:<name>` as creator make the
  worker check that schedule after each of their final outcomes, as
  follow-ups of an occurrence do today (ADR 0033): one advisory lock
  and one read per finished task. For a cycle of many tasks that is
  measured before this is built; if it shows, the check is limited to
  tasks of the schedule's own type, which is what it looks for anyway.
- The guide and the concepts page show a recurring task that starts a
  cycle; the SQL recipes gain "the cycles a schedule started".
- Whether runs overlap stays the handler's decision: it reads the
  cycles of its type and does not call `startCycle` while one is
  active. No option for it here.

## Open questions

1. **`TaskContext.startCycle` buffers, and the worker writes with a
   `SUCCEEDED` outcome only**, in the outcome's transaction, after the
   follow-ups? Proposal: yes.
2. **No return value**; a caller that needs the id passes one?
   Proposal: yes.
3. **Creator and correlation id are inherited and cannot be set**, so a
   cycle started by a schedule carries `schedule:<name>`? Proposal:
   yes.
4. **The cycle's tasks name the starting task as their parent**, which
   changes what the query form's statement writes? Proposal: yes.
5. **The priority is the starting task's unless given**, as for
   follow-ups, not 0 as for `Pendenz.startCycle`? Proposal: yes.
6. **Permissions are not checked, approval applies**, as for
   follow-ups? Proposal: yes.
7. **A failing query surfaces as an expired lock**, as a failing
   follow-up does today, without a path of its own that records the
   query's error? Proposal: yes for this decision; a better error for
   a failed write of an outcome concerns follow-ups too and is a
   decision of its own.
8. **No scheduled-cycle API and no overlap option**; a recurring task
   whose handler calls `ctx.startCycle` is the documented way?
   Proposal: yes.
