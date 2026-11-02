# Decisions a handler reports: deferral, resource blocks, hand-over

- Status: proposed
- Date: 2026-11-02

## Context

A handler ends in one of two ways. It returns, and the task succeeds or
its result is refused by the validator (ADR 0020). Or it throws, and the
type's `RetryPolicy` decides between another attempt and `FAILED`
(ADR 0026). The policy sees the attempt number, the time and the
throwable; every throw counts as an attempt, since the claim has already
incremented `attempts`.

Three situations a handler can recognise do not fit either way:

- **The work cannot be done yet, and that is not a failure.** A partner
  API answers `429 Too Many Requests` with `Retry-After: 3600`; an
  upstream export is not ready before 06:00. Today the handler throws,
  and a `RetryPolicy` that inspects the throwable can return the given
  time. The attempt still counts, so an hour of waiting uses up one of
  `maxAttempts`, and a quota that stays empty for a day ends the task in
  `FAILED`.
- **The resource is unavailable, not only this task.** When the quota
  of the partner API is used up, every task against that API fails in
  the same way. The queue does not know this: the next poll claims the
  next task of the same resource, it runs into the same `429`, and so on
  for every pending task until the quota resets. Each costs a request, a
  claim and an attempt. ADR 0017 limits how many tasks of a resource run
  at once, and a limit of zero pauses a resource (ADR 0029), but only an
  operator or the application can set that limit, and nothing lifts it
  at a given time.
- **The task needs a person.** A handler that finds the input
  implausible, for example an invoice whose total does not match its
  lines, can only throw. The task then goes through every retry before
  it lands in `FAILED`, although no retry can change the answer. The
  status for "set aside with a reason, a person acts next" exists,
  `QUARANTINED`, but only operators reach it (ADR 0031).

Two workarounds exist and both fall short. A custom `ClaimStrategy` can
skip groups while a flag is set, but the flag lives in one JVM, groups
are `(priority, type, lane)` rather than resources, and the strategy has
no clock. An application job can set `max_running = 0` and reset it
later, but that needs a scheduler of the application's own, and the
block is not written in the transaction that records the outcome, so a
crash between the two loses one of them.

ADR 0017 named rate limits (starts per time) as later work. They are
related but different: a rate limit throttles in advance; the cases
above react to what the remote side reported.

## Decision

A handler reports a decision by throwing one of three exceptions. The
worker recognises them before the retry policy and records the matching
outcome in the same transaction as any other outcome.

### Three exceptions

```kotlin
public sealed class HandlerDecisionException(message: String) : RuntimeException(message)

/** Run this task again at [at]; the attempt does not count. */
public class RetryLaterException(public val at: Instant, public val reason: String) : HandlerDecisionException(reason)

/** The task's resource, or [resource], takes no tasks until [until]; this task runs again then. */
public class ResourceUnavailableException @JvmOverloads constructor(
    public val until: Instant,
    public val reason: String,
    public val resource: String? = null,
) : HandlerDecisionException(reason)

/** A person must look at this task: it is quarantined with [reason]. */
public class HandOverException(public val reason: String) : HandlerDecisionException(reason)
```

An exception, not a return value, because the decision is often made
deep in client code: an HTTP client that maps `429` with `Retry-After`
to `ResourceUnavailableException` serves every handler that uses it,
and the handler's signature and result type (ADR 0020) stay as they
are. Java handlers throw them like any unchecked exception.

The worker looks for a decision in the throwable and its cause chain,
because clients and frameworks wrap exceptions; the first decision found
wins. Anything else stays a failed attempt for the retry policy, as
ADR 0026 decided.

### Deferral

`RetryLaterException` and `ResourceUnavailableException` move the task
from `RUNNING` to `RETRY_PENDING` with `run_at` at the given time and
`attempts` back to the value before the claim. The reason goes into the
error column, prefixed so that it reads as a deferral rather than a
failure. `RUNNING → RETRY_PENDING` is a legal transition already; the
status model does not change. A time that is not after now is a
programming error and counts as a failed attempt for the retry policy,
so that a handler cannot make a task loop at the poll rate.

The store gains `Outcome.Deferred(runAt, reason, provenance)`. Like
`Outcome.Retry` it is not final: follow-ups are dropped (ADR 0019) and a
recurring occurrence does not insert its successor (ADR 0033).

### Resource blocks

`ResourceUnavailableException` additionally blocks a resource: the one
it names, or else the task's own. A task without a resource whose
exception names none is deferred only.

- **A table of its own.** `ResourceBlockTable` with `name`,
  `blocked_until`, `reason` and `blocked_at`, generated by
  `PostgresDdl.createResourceBlockTable`, enabled with
  `enableResourceBlocks()` on the builder. `name` is an exact resource
  value or a `LIKE` pattern, as in the limit table. Unlike limits, any
  matching row with `blocked_until` in the future blocks; there is no
  longest match, since a block never permits anything.
- **Written with the outcome.** The block is an upsert in the
  transaction that records the deferral, only when that recording
  applied: `blocked_until = greatest(existing, new)`, so two handlers
  reporting different reset times leave the later one.
- **Read once per poll.** The poll reads the active blocks with the
  limit rows (ADR 0029): exact names join `ResourceFilter.full`,
  patterns go into a new `ResourceFilter.blockedPatterns` that no other
  pattern overrides. `dueGroups` and the claims leave the blocked
  resources out; their tasks stay `PENDING`. Blocks need no advisory
  lock: a task claimed in the instant another worker writes a block
  meets the same remote answer and is deferred in turn. Without
  `enableResourceLimits()` the poll reads only the blocks.
- **Operated with SQL and from code.** An operator lifts a block with
  `DELETE` or by setting `blocked_until` to the past, and sets one with
  an insert. The API offers `blockResource(name, until, reason)`,
  `unblockResource(name)` and `resourceBlocks()`. The maintenance sweep
  deletes rows whose time has passed.
- **No wake-up at expiry.** When a block ends, the next poll claims the
  resource's tasks; the poll interval bounds the delay, as for slots
  freed by another worker (ADR 0017).

### Hand-over

`HandOverException` moves the task from `RUNNING` to `QUARANTINED` with
the reason, without asking the retry policy. This adds the transition
`RUNNING → QUARANTINED` to `TaskStatusTransitions` and the generated SQL
functions. `quarantinable`, the set an operator may quarantine from,
leaves `RUNNING` out explicitly: an operator's way to stop a running
task stays `cancel`, with the slot handling of ADR 0031. A
quarantined occurrence lets its schedule insert the next one, like a
failed one (ADR 0033).

### Listener and store seam

- `TaskListener` gains `onDeferred(task, runAt, reason)`,
  `onHandedOver(task, reason)` and `onResourceBlocked(resource, until,
  reason)`, after the commit (ADR 0032).
- `TaskStore.finish` handles the two new outcomes; a
  `ResourceBlockStore` half joins the store like the limit and cycle
  halves; `SqlDialect` gains the block statements. These are API breaks
  for custom stores and dialects, accepted before 1.0.
- The in-memory store applies the same rules over its maps, so
  `runPending()` tests show deferrals, blocks and hand-overs.

### Tests

- Worker with the in-memory store and a controlled `Clock`: deferral
  keeps `attempts`, sets `run_at`, writes the reason; a past time is a
  failed attempt; a wrapped decision is found; a hand-over quarantines
  without consulting the retry policy; a deferral drops follow-ups.
- Blocks against PostgreSQL: a blocked resource's other tasks are not
  claimed until `blocked_until`, then run; a pattern block covers every
  matching value; a later report extends, an earlier one does not
  shorten; an operator's `DELETE` lifts it at the next poll; the sweep
  removes expired rows; a block is not written when the outcome was
  dropped.
- The transition table test covers `RUNNING → QUARANTINED` and the
  unchanged `quarantinable`.
- Java: a blocking handler throws each exception; one test per case in
  `core-java`.

## Rejected alternatives

- **A return value per decision**, a sealed `HandlerOutcome` instead of
  `Unit` or the result. Changes every handler's signature and
  `ResultHandler`'s typed result, and code deep in a client could not
  report a decision without threading it back up.
- **Methods on `TaskContext`**, such as `ctx.deferUntil(at)` followed by
  a return. Two steps that can disagree: a handler that defers and then
  throws, or defers and returns a result. Can be added later as a
  shorthand that throws.
- **A `RetryPolicy` that inspects the throwable**, which works today.
  Counts the attempt and blocks nothing; the other tasks of the resource
  are still claimed one after another.
- **Blocks as a column of the limit table.** With `customer:%` limited
  to two, a block for `customer:acme` needs a row of its own, and by
  the longest-match rule that row would replace the pattern's limit.
  It would also mix a count with a time in one row and interfere with
  seeding default limits, which only inserts where no row exists.
- **A new status `DEFERRED` or `BLOCKED`.** The status model is part of
  the public schema (principle 8). `RETRY_PENDING` already means "waits
  for its time to try again", and the error column says why.
- **Releasing the task to `PENDING`**, as a shutdown does (ADR 0005).
  The shutdown keeps the attempt counted; a deferral should not, and
  without a run time the task would be claimed again at the next poll
  unless a block holds it.
- **Rate limits in the same decision.** A token bucket throttles before
  the remote side refuses and needs a history of starts. It remains
  later work under ADR 0017; a block covers the case where the remote
  side states when it will accept again.
- **Blocks that also hold running tasks.** Tasks of the resource that
  are already running continue; cancelling them would discard work that
  may still succeed.

## Consequences

- Public API: three exception classes, `Outcome.Deferred`,
  `ResourceBlockTable` and its columns, `enableResourceBlocks()`, three
  operations on `Pendenz` and the Java facade, three listener events,
  `ResourceFilter.blockedPatterns`. Store and dialect gain a block half.
- Schema: a new optional table and a new legal transition
  `RUNNING → QUARANTINED`; the generated functions and the transition
  documentation change with it.
- A deferral never ends a task. A handler that defers forever keeps its
  task alive forever; the error column and `onDeferred` show it, and an
  operator cancels it.
- While a resource is blocked, its tasks wait in `PENDING` and the age
  of the oldest due task (ADR 0022) grows. That is intended, and it is
  what an alert on the backlog should see.
- `pendenz-micrometer` can count deferrals and hand-overs from the new
  events; that is a change of its own.
- The guide, the concepts, the SQL recipes and the Java page gain the
  three decisions and the block table.

## Open questions

1. **A deferral does not count as an attempt?** Proposal: yes; waiting
   for a quota is not a failure, and counting it would end tasks in
   `FAILED` during a long block.
2. **`RETRY_PENDING` as the deferred status**, rather than a new
   `RUNNING → SCHEDULED` transition or a new status? Proposal: yes.
3. **Blocks in a table of their own, enabled separately from limits,
   any match blocks?** Proposal: yes.
4. **`ResourceUnavailableException` without `enableResourceBlocks()`**:
   defer the task and log a warning, or count it as a failed attempt
   so that the missing setup shows? Proposal: defer and warn once per
   resource; the task's own outcome is still right.
5. **The cause chain is searched** for a decision, not only the thrown
   exception? Proposal: yes.
6. **A time not after now is a failed attempt?** Proposal: yes; a
   `Retry-After: 0` becomes an ordinary retry through the policy.
7. **Hand-over in this decision**, with `RUNNING → QUARANTINED` and
   `quarantinable` unchanged? Proposal: yes; it uses the same mechanism
   and is a one-line transition.
8. **Expired blocks deleted by the maintenance sweep**, rather than
   kept as history? Proposal: yes; the listener event is the history.
