# Status transitions of operator actions

- Status: accepted
- Date: 2026-10-28

## Context

ADR 0002 makes `TaskStatusTransitions` the single source of truth for
task states, and ADR 0018 derives the source states of the operator
calls and the generated SQL functions from it, "so the API and the
functions cannot drift". The review of 2026-10-25 found that the table
governs which state a task may leave, but not what the operation writes
on the way, and that some operations bypass the table altogether:

- **The library makes a move the table forbids.** Pausing a cycle
  quarantines its `SCHEDULED` and `RETRY_PENDING` tasks; resuming it
  writes `SCHEDULED` when their run time is still ahead. The table
  allows `QUARANTINED` only to `PENDING`, `SUCCEEDED` and `CANCELLED`,
  and ADR 0016 says "back to `PENDING`". Code, table and ADR disagree,
  and no test notices, because no test resumes a task whose run time
  lies ahead, and no test holds the stores' moves against the table.
- **`PENDING` does not always mean due.** `restart` moves a task to
  `PENDING` and leaves `run_at` as it was. A reminder planned for in
  three weeks, quarantined and restarted, sits in the table as `PENDING`
  and is claimed in three weeks. The notify trigger fires for it anyway,
  and `backlog` counts it as pending without an `oldestDue`.
  `approve` and resume decide between `PENDING` and `SCHEDULED` by the
  run time; `restart` does not.
- **The side effects are written twice, and differ.** `Pendenz.quarantine`
  clears `finished_at`, because `QUARANTINED` is not final; the
  generated `_quarantine` keeps it. A `FAILED` task quarantined in SQL
  still says when it finished, the same task quarantined through the API
  does not. The cycle's cancel, in Kotlin and in the generated function,
  spells the final states out as `NOT IN ('SUCCEEDED', 'CANCELLED')`
  instead of deriving them.
- **Cancelling a running task frees its resource slot at once.** The
  cancel writes `CANCELLED` and clears the lock; the limit counts
  `RUNNING` rows (ADR 0017). The handler notices the cancel at its next
  heartbeat, and a blocking handler, which cannot be interrupted, runs
  to its end while the worker waits for it (ADR 0005). Meanwhile the
  next poll claims another task for the same resource. A mail server
  limited to two connections sees four.
- **`CycleStatusTransitions` is used by nothing but its test.** It calls
  itself the single source of truth; pause, resume, cancel and the
  finishing sweep each spell their source states out by hand.

None of this needs a new state. It needs the transitions to say what
they write, and the code to have one place that writes it.

## Decision

### A transition is a status and its side effects

Every move an operator, a cycle operation or an approval makes is
described once in the core, as the target status together with what
the move writes beside it. The Kotlin store statements and the
generated SQL functions are both built from that description. The
rules for the columns:

- **`finished_at`** is set to now when the target is final, and cleared
  when it is not. Quarantining a `FAILED` task clears it, in SQL as in
  Kotlin.
- **The lock** (`locked_by`, `locked_until`) is cleared, except when a
  running task is cancelled; see below.
- **`last_error`** takes the reason when the operation has one
  (quarantine, decline, pause); otherwise it is kept.
- **`run_at`** decides between `PENDING` and `SCHEDULED`, see the next
  section.

The source states of every operation come from `TaskStatusTransitions`,
including the cycle's cancel, which takes `cancellable` instead of
listing the final states.

### Due decides between `PENDING` and `SCHEDULED`

`PENDING` means due, as `TaskStatus` documents it. Two kinds of
operation move a task back into the active group, and they mean
different things:

- **Releasing** puts a task back where it was: resuming a cycle and
  approving a task. The task keeps its `run_at` and becomes `PENDING`
  when that is due, `SCHEDULED` when it lies ahead. A task in backoff
  that the pause set aside comes back as `SCHEDULED` with its retry
  time; its `attempts` still say it is a retry.
- **Restarting** runs the task now: `run_at` becomes the earlier of its
  value and now, and the task becomes `PENDING`. An operator who
  restarts a task wants it to run; one who wants it later postpones it
  (the recipe in `docs/sql-recipes.md`).

`TaskStatusTransitions` gains `QUARANTINED -> SCHEDULED`, and ADR 0002
and ADR 0016 get a note. The SQL recipes for restart and resume follow
the same rules.

### A cancelled task holds its slot until its handler has returned

Cancelling a `RUNNING` task writes `CANCELLED` and `finished_at` as
before, but leaves the lock in place. The worker running it:

- finds the row changed at its next heartbeat and cancels the handler,
  as today;
- keeps renewing `locked_until` on the cancelled row while the handler
  is still running, by worker id instead of by version;
- clears the lock once the handler has returned, and drops its outcome
  as today.

The running count of ADR 0017 counts the rows whose lock is alive:
`RUNNING`, or `CANCELLED` with `locked_until` in the future. A slot is
therefore occupied exactly as long as a handler may still be talking to
the resource. A worker that dies with a cancelled task leaves a lock
that expires by itself; the maintenance sweep clears expired locks on
cancelled rows. The partial index on running rows (ADR 0028) covers
these rows too.

`CANCELLED` keeps its meaning, "will never run": the task is not
claimed, restarted or retried again. A lock on a final row means only
that a handler of it has not returned yet. The SQL recipe for cancelling
stops clearing the lock; a recipe or a tool that keeps clearing it
frees the slot early, as today, and nothing worse.

### Cycle transitions are read from the table

Pause, resume, cancel and the finishing sweep take their source states
from `CycleStatusTransitions`, and the generated `<cycle table>_cancel`
does too. Whether a cycle's task blocks finishing is decided by
`TaskStatus.isFinal`, not by a list of names.

### Tests hold behaviour against the tables

- A contract test for both stores runs every operation (restart,
  complete, quarantine, cancel, approve, decline, pause, resume, cycle
  cancel, cycle approve) on a task in every status, and asserts that the
  status is either unchanged or a legal transition from the one before.
- The same test compares the row the API leaves with the row the
  generated SQL function leaves, for every operator function.
- `CycleStatusTransitionsTest` is parameterised over `CycleStatus`
  entries, derives final states from `isFinal` and covers `ACTIVE <->
  PAUSED` and the absence of transitions into the same state.
- Pause with `SCHEDULED` and `RETRY_PENDING` tasks, resume with a run
  time ahead, resume of an `ACTIVE` cycle, restart with a run time
  ahead, and a resource limit of two that holds while a cancelled
  blocking handler is still running.

## Rejected alternatives

- **Resume always to `PENDING`, leaving the promotion of a future
  `run_at` to the claim.** `PENDING` would stop meaning due, for every
  reader of the schema; the claim, the notify trigger and `backlog`
  would each have to treat a `PENDING` row with a future run time as
  something else.
- **Restart keeps the run time** and decides like a release. A restarted
  reminder for in three weeks would stay `SCHEDULED` for three weeks;
  the operator's "run it" would read as "put it back". Postponing exists
  for that.
- **Restoring the exact state before the pause**, `RETRY_PENDING`
  included. Needs a column for the previous state, or a new state, for a
  difference the `attempts` column already shows.
- **A cancel request column** that the heartbeat reads, with
  `CANCELLED` written only when the handler has returned. Exact as well,
  but adds a column to the minimum schema (principle 8) and leaves the
  row `RUNNING` after an operator has cancelled it, which is the wrong
  answer to "is it cancelled?".
- **Documenting the early free slot** instead of fixing it. ADR 0017
  promises an exact limit; cancelling is what an operator does when a
  resource is in trouble, the moment exactness matters most.
- **Making `CycleStatusTransitions` internal** and dropping its claim.
  UIs that offer pause and resume buttons need the same table the
  library uses.

## Consequences

- One description of operator moves in the core, used by
  `PostgresDialect`, `PostgresOperatorDdl` and `InMemoryTaskStore`.
  `TaskStore.move` gains the release-or-restart distinction and its
  KDoc says what it writes.
- `TaskStatusTransitions` gains `QUARANTINED -> SCHEDULED`. A public
  table changes, before 1.0.
- `_quarantine` clears `finished_at`; `_restart` sets `run_at`;
  `_cancel` and `<cycle table>_cancel` leave the lock of running tasks.
  Applications regenerate their functions with their next migration.
- The running count and the partial running index change; the heartbeat
  gains a second statement for cancelled rows, and the worker a release
  of the lock after a dropped outcome. ADR 0017 and ADR 0028 get a note.
- The recipes for restart, cancel and resume in `docs/sql-recipes.md`
  change.
- The known limitation of ADR 0016 stays: a running task of a paused
  cycle that is interrupted or fails is released or retried without
  consulting the cycle.

## Decisions taken with the acceptance (2026-10-28)

1. Restart runs now: `run_at` becomes the earlier of its value and now.
   Resume and approve keep the run time.
2. `QUARANTINED -> SCHEDULED` joins the table.
3. A cancelled running task keeps its lock until its handler has
   returned; the limit counts live locks.
4. One description per move builds the Kotlin statements and the SQL
   functions; a test compares the rows both leave.
5. `CycleStatusTransitions` stays public and drives the cycle
   operations and the generated cancel.
