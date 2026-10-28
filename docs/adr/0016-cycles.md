# Cycles: tasks for a whole dataset

- Status: accepted
- Date: 2026-10-19

## Context

The second use of a task queue, after "do this one thing later", is "do
this for every row": send the monthly invoices, re-score every open
ticket, migrate every customer's documents. The planning calls such a run
a cycle and asks for four things: create the tasks from a SQL query
rather than one by one, see the progress of the run, pause it, cancel
it. The claim strategy (ADR 0015) already keeps a cycle from starving
single tasks; payload columns (ADR 0013) already let a query produce
payloads. What is missing is the cycle as a thing of its own.

Operators know cycles from the outside: "how far is the invoice run",
"stop it, the template is wrong", "continue after the fix". Those are
questions about the run, not about one task, and they are asked in SQL
and in UIs built on the schema.

## Decision

A cycle is a row in a table the application owns, and its tasks are the
tasks whose `lane` is `cycle:` followed by the cycle's id.

- **A cycle table**, mapped like the task table: `CycleTable(name,
  columns)` with the concepts id, task type, status, description,
  created at, created by, finished at. `PostgresDdl.createCycleTable`
  generates it; the application adds columns of its own as with tasks.
  The id type follows the task table's `IdType`.
- **Cycle status** is its own small model, not a reuse of the task
  states: `ACTIVE`, `PAUSED`, `CANCELLED`, `FINISHED`. Final are
  `CANCELLED` and `FINISHED`.
- **Starting a cycle** inserts the cycle row and the tasks in one
  transaction, the tasks with `INSERT ... SELECT` from a query the
  application supplies. The query yields the payload columns of the task
  type in their declared order; Pendenz wraps it:

  ```kotlin
  val cycle = pendenz.startCycle(sendInvoice, description = "October invoices", createdBy = "billing-run") {
      "SELECT i.id, c.name FROM invoice i JOIN customer c ON c.id = i.customer_id WHERE i.sent_at IS NULL"
  }
  ```

  becomes

  ```sql
  INSERT INTO billing_task (task_type, status, run_at, lane, priority, created_by, invoice_id, customer)
  SELECT 'send-invoice', 'PENDING', now(), '<cycle id>', 0, 'billing-run', q.*
  FROM (SELECT i.id, c.name FROM invoice i ... ) AS q;
  ```

  The query is the application's SQL, run inside the application's
  transaction when one is given, exactly like `enqueue`. Pendenz adds
  nothing but the fixed columns. A cycle may also be started with an
  explicit list of payloads for small runs; that is a loop over
  `enqueue` with the lane set, inside one transaction.
- **Progress** is a view the DDL generator emits: one row per cycle with
  counts per task status, the oldest pending and the newest finished
  task. No counters on the cycle row; counters drift, counts do not.
- **Pausing** moves the cycle to `PAUSED` and its `PENDING`, `SCHEDULED`
  and `RETRY_PENDING` tasks to `QUARANTINED` with the reason "cycle
  paused". Running tasks finish. Resuming moves the cycle back to
  `ACTIVE` and those tasks back to `PENDING`. The task state model
  (ADR 0002) already has the state for "set aside, a human decides", so
  pausing needs no new task state, and SQL that lists quarantined tasks
  shows paused cycles for what they are.
- **Cancelling** moves the cycle to `CANCELLED` and every non-final task
  to `CANCELLED`, with `finished_at` set. Running tasks are cancelled at
  their next heartbeat, as today.
- **Finishing** is derived, not declared: a maintenance sweep marks a
  cycle `FINISHED` when none of its tasks is active or waiting for a
  human any more. A cycle with a `FAILED` task is not finished; a human
  decides.
- All four operations are SQL recipes as well as API calls, each with a
  version check on the cycle row.

## Rejected alternatives

- **No cycle table; a cycle is just a lane.** Progress and pausing would
  have nothing to hang on: no description, no creator, no state, no
  place for the application's own columns. The lane stays the link; the
  row is the thing operators and UIs talk about.
- **Counters on the cycle row**, updated by workers on every finish.
  Contention on one row for every task of a large run, and a counter
  that is wrong after any SQL intervention. A view counts what is there.
- **A `PAUSED` task state.** Would add an eleventh state to a contract
  that is stable from 1.0, for a situation `QUARANTINED` already names.
- **Tasks created by the application and handed to Pendenz as a batch.**
  Works for small runs and is the loop form offered above. For a million
  rows the data should not leave the database.
- **Chunked cycles that create tasks lazily.** Saves nothing in the
  database and loses the simple truth that every task of a run exists
  as a row from the start.

## Consequences

- One more mapped table, one generated view, four operations on the
  API and as SQL recipes, one cycle status model with its own tests.
- A cycle's tasks are ordinary rows; everything that works on tasks,
  retention included, works on them. The cycle row itself is deleted by
  the same sweep once it is final, older than the type's period and has
  no task left in its lane; it is never archived, an archive of tasks
  carries the lane and so the cycle's id (corrected on review,
  2026-10-19: this was promised and not built).
- The query the application supplies runs with the application's
  privileges and inside its transaction; Pendenz validates nothing
  about it beyond the column count. A wrong query fails at the
  database, in the application's transaction.
- The MCP module (v0.4) gets a natural tool: start a cycle of a
  registered type from a registered query.

## Decisions taken with the acceptance (2026-10-19)

1. The query selects the payload columns by position, in the order the
   task type declares them. One `INSERT ... SELECT`, no parsing.
2. No run time per task from the query in the first version. A
   per-resource limit is the tool for a sensitive downstream.
3. `FINISHED` is derived by the maintenance sweep, so a cycle is
   finished up to one maintenance interval after its last task.
4. Pausing quarantines the waiting tasks with the reason `cycle paused`;
   resuming releases exactly the tasks that carry that reason, so a task
   an operator quarantined for a reason of their own stays put.

Known limitation (noted on review, 2026-10-19): a task of a paused cycle
that was running when its worker shut down is released as `PENDING` by
the shutdown (ADR 0005) or the lock timeout, not quarantined, and runs
again despite the pause. Pausing promises that waiting tasks wait and
running tasks finish; a task interrupted mid-run is neither. The same
holds for a running task that fails after the pause: its retry is
scheduled as `RETRY_PENDING` and runs when due, because the retry
policy does not consult the cycle. Pausing also writes the reason
`cycle paused` into `last_error`, so a `RETRY_PENDING` task of the cycle
loses the text of its last failure; the worker log keeps it.

Amended with ADR 0031 (2026-10-28): resuming moves the tasks the pause
set aside back to `PENDING` when they are due and to `SCHEDULED` when
their run time lies ahead, a task paused during its backoff therefore
with its retry time; the transition table allows it. Pause, resume,
cancel and finishing read their source states from
`CycleStatusTransitions`. Cancelling leaves the lock of a running task
in place until its handler has returned.

Revised on review (2026-10-19): the lane of a cycle's tasks is
`cycle:<id>`, not the bare id. The bare id could collide with a tenant
lane such as "42", and the guard against that, a join on the task type
as well as the lane, left out the follow-ups of other types that ADR
0019 says belong to the cycle. With the prefix the join is on the lane
alone, follow-ups count in the progress view and keep the cycle from
finishing, and `GROUP BY lane` reads as what it is.
