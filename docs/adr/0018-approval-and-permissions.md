# Approval and permissions

- Status: accepted
- Date: 2026-10-19

## Context

Principle 7 says outside callers only use registered task types: a UI,
another system or an AI agent picks a type and supplies a payload, and
the handler decides what happens. v0.3 adds the two controls that make
that safe enough for agents: some tasks must be approved by a person
before they run, and not everyone may enqueue, approve or restart
everything.

The status model (ADR 0002) already reserved `AWAITING_APPROVAL` for
this, with transitions to `PENDING`, `SCHEDULED`, `CANCELLED` and
`QUARANTINED`. The creator of a task is recorded since v0.1. The planning
fixes one rule: the person who approves is not the person who created.

Two things hide under "control", and they are different:

- **Approval** is a property of the task: this one needs a second pair
  of eyes before it runs. It is decided when the task is enqueued, by a
  rule of the task type, and it is visible in the row.
- **Permission** is a property of the actor: this caller may or may not
  do this to tasks of that type. It is decided when the call is made,
  by the application's permission system, and it leaves no trace in the
  row except, when granted, the actor's name.

A third thing is deliberately not in scope: what the database allows.
SQL recipes and generated functions are governed by database grants; a
permission model inside the library cannot and should not police a
`psql` session.

## Decision

### Approval

- A task type carries an `Approval` rule, `Approval.NEVER` by default.
  `Approval.ALWAYS` holds every task; `Approval.rule { createdBy,
  payload -> ... }` decides per task, for example "refunds above a
  thousand" or "everything an agent created". The rule sees the creator
  and the payload; for tasks a cycle creates from a query it sees no
  payload and must decide from the creator alone.
- When the rule says so, `enqueue` stores the task as `AWAITING_APPROVAL`
  instead of `PENDING` or `SCHEDULED`. Workers never claim it; the
  `_needs_human` view lists it.
- Two columns, `approvedBy` and `approvedAt`, nullable. Approving sets
  both and moves the task to `PENDING`, or to `SCHEDULED` when its run
  time is still ahead. Declining moves it to `CANCELLED` with the reason
  in the error column and the finish time set.
- **The approver is never the creator.** `approve` compares the actor
  with `createdBy` and refuses with `NotPermittedException` when they
  match. This is a rule of the library, not a permission the
  application may lift. A task without a creator can be approved by
  anyone the permissions allow.
- A cycle whose type requires approval is approved as a whole:
  `approveCycle` moves every awaiting task of the cycle's type in its
  lane to `PENDING` in one statement, with the same creator check against
  the cycle's creator. Individual tasks of it may still be approved one
  by one. Follow-ups of other types that handlers enqueued into the lane
  (ADR 0019) are not covered: whoever approves the cycle approves what
  it was started for, not what grew out of it. Declining a cycle is
  cancelling it.
- Starting a cycle needs `START_CYCLE` on the type and nothing else;
  the tasks it creates are not separate enqueues.
- `approve`, `decline` and `approveCycle` exist as API calls, as SQL
  recipes and as generated functions `_approve(id, version, approver)`
  and `_decline(id, version, actor, reason)`; the function enforces the
  creator rule in SQL, so the rule holds for UIs that talk SQL.
- The listener gains `onAwaitingApproval`, `onApproved` and `onDeclined`.

### Permissions

- An interface the application implements:

  ```kotlin
  public fun interface Permissions {
      /** Whether [actor] may do [action] to tasks of [taskType]; null is an anonymous caller. */
      public fun allows(actor: String?, action: Action, taskType: String): Boolean
  }

  public enum class Action {
      ENQUEUE, APPROVE, DECLINE, RESTART, COMPLETE, QUARANTINE, CANCEL,
      START_CYCLE, APPROVE_CYCLE, PAUSE_CYCLE, RESUME_CYCLE, CANCEL_CYCLE,
  }
  ```

- Two implementations ship. `Permissions.ALLOW_ALL` is the default and
  today's behaviour. `Permissions.table { actor("billing-clerk")
  .may(ENQUEUE, "send-invoice").may(APPROVE, "refund") }` is a static
  table for small applications and for tests; anyone not listed may do
  nothing. Applications with a permission system of their own implement
  the interface over it, which is the point of the abstraction.
- The actor is a string, the same opaque identity `createdBy` already
  holds: a user id, a service name, an agent's name. The library never
  interprets it.
- Permissions are checked in the API before anything is written, and a
  refusal is `NotPermittedException` with the actor, the action and the
  type in its message. A call without an actor is anonymous; `ALLOW_ALL`
  lets it through, the static table does not.
- The operations that take an actor get one. `enqueue` and `startCycle`
  already have `createdBy`. New in this ADR, because a UI built on the
  library needs them and permissions need something to guard:
  `restart(id, actor)`, `complete(id, actor)`, `quarantine(id, actor,
  reason)` and `cancel(id, actor)` on `Pendenz`, each applying the same
  transition as the SQL recipe and returning whether the row changed.
  `pauseCycle`, `resumeCycle` and `cancelCycle` gain an actor parameter.

### What is not decided here

- Who did the last operator action is not recorded beyond `approvedBy`.
  An audit column, or the provenance metadata the planning lists for
  v0.3, is a decision of its own.
- Approval by several people, approval that expires, and approval that
  requires a specific role are left until someone needs them; the
  columns and the rule interface leave room.

## Rejected alternatives

- **An approval table beside the task table.** Duplicates the row and
  its state, and the status model already has the state. A task that
  waits is a task that waits.
- **A flag per type instead of a rule.** Cannot say "refunds above a
  thousand" or "only what the agent created", which are the two cases
  people actually have.
- **Self-approval as a permission the application may grant.** The
  planning rules it out, and the one case for it, a solo developer in a
  test, is served by two names.
- **A `Principal` type with roles.** Roles are the application's; a
  string identity is what every system can produce and what the row
  already stores.
- **Permissions enforced in the generated SQL functions**, through the
  session user. The database has grants for that, and a second model in
  the functions would drift from the application's.
- **Checking permissions in the handler.** Too late: the task is already
  in the table, visible and claimable.

## Consequences

- Two columns, one rule on the task type, one interface with two
  implementations, four new API operations and an actor on the cycle
  operations, two generated functions, three listener events.
- Agents in v0.4 get their control for free: register the MCP tools
  with an actor name, let the approval rule hold what the agent creates,
  and let a person approve from the `_needs_human` view.
- `createdBy` becomes worth filling in. Applications that left it null
  so far have anonymous tasks that anyone may approve.
- The transition table of ADR 0002 is unchanged.

## Decisions taken with the acceptance (2026-10-19)

1. The rule sees creator and payload; for query cycles the payload is
   null and the rule decides from the creator.
2. The creator may never approve. Hard rule, in the API and in the
   generated `_approve` function.
3. The actor is a string.
4. `restart`, `complete`, `quarantine` and `cancel` are API operations
   with an optional actor; the cycle operations take one too.
5. Anonymous calls are denied by the static table and allowed by
   `ALLOW_ALL`.
6. `approveCycle` approves every waiting task of a cycle in one
   statement; single tasks can still be approved on their own.

Noted during implementation: the `_needs_human` view of the operator
DDL lists `AWAITING_APPROVAL` tasks already, so approval needs no view
of its own. `decline` writes the reason into the error column, as
pausing a cycle does; who declined is not recorded, see "What is not
decided here".

Corrected on review (2026-10-19): `restart` and `quarantine`, on the API
and as generated functions, used to accept `AWAITING_APPROVAL`, which
let anyone with the restart permission bypass the approval, directly or
through a quarantine that forgot the approval was due. Both now refuse
it; the transition `AWAITING_APPROVAL -> QUARANTINED` is gone from ADR
0002, and `TaskStatusTransitions` names the source states of each
operator move (`restartable`, `completable`, `quarantinable`,
`cancellable`) so the API and the functions cannot drift. `approveCycle`
approves only an `ACTIVE` cycle; approving a single task of a paused
cycle still lets that task run, which is accepted as an operator's
explicit choice.
