# Cycles: tasks for a whole dataset

- Status: proposed
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
tasks whose `lane` carries the cycle's id.

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
  retention included, works on them. Retention of the cycle row itself
  follows the task type's rule.
- The query the application supplies runs with the application's
  privileges and inside its transaction; Pendenz validates nothing
  about it beyond the column count. A wrong query fails at the
  database, in the application's transaction.
- The MCP module (v0.4) gets a natural tool: start a cycle of a
  registered type from a registered query.

## Open questions for the discussion

1. **Does the application's query select payload columns by position
   or by name?** Position, as proposed, is one `INSERT ... SELECT` and
   no parsing. Name would let the query return extra columns and would
   need Pendenz to wrap it as a subquery and list the names.
2. **Run time per task from the query?** A cycle could spread its tasks
   over time, say one per second, to go easy on a downstream system.
   Proposal: not in the first version; `priority` and a lane are enough
   to keep a cycle from crowding others, and a per-resource limit (the
   next ADR) is the right tool for the downstream.
3. **Is `FINISHED` derived by maintenance, as proposed, or by the last
   worker that finishes a task of the cycle?** Maintenance is simpler
   and a minute late; the worker is immediate and has to count.
4. **Should pausing quarantine the tasks, as proposed, or leave them
   `PENDING` and have the claim skip paused cycles?** Skipping keeps the
   task rows untouched but puts a join on the cycle table into every
   claim. Quarantining is explicit in the task rows and costs nothing at
   claim time.
