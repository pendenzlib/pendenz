# An outcome that cannot be written is a failed attempt

- Status: proposed
- Date: 2026-11-17

## Context

When a handler has ended, the worker writes the outcome in one
transaction: the task's row, and with it whatever belongs to the
outcome. For `SUCCEEDED` that is the follow-ups the handler enqueued
(ADR 0019) and the cycles it started (ADR 0047); for a final outcome of
an occurrence, the schedule's next one (ADR 0033); for a resource the
handler reported unavailable, the block (ADR 0035).

A write that fails is repeated, with pauses from 100 ms upwards, for
half the worker's lock timeout, so that a short interruption of the
database does not lose work that was done. After that the worker logs
the exception and lets the task go. The row stays `RUNNING`. Its lock
runs out, another poll finds it and records a failed attempt with a
`LockExpiredException` (ADR 0026), and the retry policy decides.

That path was built for a database that is unreachable. It is also the
path of a write that fails every time while the database is fine:

- a follow-up that violates a constraint of the application, such as a
  foreign key or a `CHECK` on a payload column;
- a cycle query that is wrong: a missing table, a column of the wrong
  type (ADR 0047, decision 7);
- a result or an error text the task table refuses, for example by a
  length limit the application put on the column.

For these, three things are off:

- **The stored error names the wrong cause.** `last_error` reads `lock
  expired: worker billing-1 stopped confirming its lock`, although the
  worker is alive. The exception of the write is in that worker's log
  only. An operator who reads the table, a view or `tasks()` looks for
  a crashed process.
- **It takes long.** ADR 0047 measured it with a lock timeout of 2 s:
  four writes within 1 s, then 2 s more until the lock expired. The
  heartbeat keeps the lock fresh while the worker writes, so with the
  defaults an attempt takes 30 s of writes and then up to 60 s until
  the lock runs out.
- **The listener reports a crash.** `onRetryScheduled` and `onFailed`
  carry a `LockExpiredException`; a metric or an alert on expired locks
  counts a worker that died.

What does not change with any text: the handler has run, and what it
did outside the database has happened. Whether it runs again is the
retry policy's decision, as it is today after the lock expired.

## Decision

When the write of an outcome still fails after the worker's retries,
the worker records the attempt as failed itself, with the exception of
the write as the cause.

- **A second write, of the row alone.** After the retries have run out,
  the worker opens a new transaction and calls `finish` for the task
  with a failure outcome, checked against the version it claimed like
  every outcome. Nothing else is written in it: no follow-ups, no
  cycles, no resource block, no next occurrence. Whatever made the
  first write fail is therefore not repeated, unless it is the row
  itself.
- **The cause is an `OutcomeNotRecordedException`**, a public class in
  the core next to `LockExpiredException`. `intended` is the status the
  outcome would have set, `cause` is the exception of the last write.
  When the handler itself had thrown, its exception is attached as a
  suppressed exception, so that it is not lost. The message reads
  `outcome SUCCEEDED could not be recorded: <cause>`.
- **The retry policy is asked**, with the task's attempt and this
  exception, as for an expired lock (ADR 0026). `RETRY_PENDING` with
  the policy's run time, or `FAILED`. A policy can tell the case by the
  type and give up at once:

  ```kotlin
  RetryPolicy { failure ->
      if (failure.cause is OutcomeNotRecordedException) null else backoff.nextAttemptAt(failure)
  }
  ```

- **The text goes through `ErrorDescription`** (ADR 0043), like every
  exception that becomes `last_error`. That matters here: the message
  of a constraint violation quotes the values of the row.
- **The listener hears `onRetryScheduled` or `onFailed`** with the
  exception, once this second write has committed. The event of the
  intended outcome, such as `onSucceeded`, is not delivered, and
  neither are `onEnqueued` or `onCycleStarted`: none of it was stored.
- **A final outcome of an occurrence** gets its schedule's next one
  from the maintenance sweep, as after an operator's SQL (ADR 0033),
  not from this write.
- **When the second write fails too**, nothing changes from today: the
  exception is logged at ERROR, the row stays `RUNNING`, and the lock
  expires. That is the case of a database that is unreachable, and of
  a row the table refuses whatever the text.
- **When the row moved on meanwhile**, because an operator cancelled or
  restarted the task, `finish` applies nothing, and the outcome is
  dropped with `onOutcomeDropped` as today.

The rule holds for both stores, since it lives in the task runner and
not in a store (ADR 0006).

### What stays as it is

- The retries of the first write, for half the lock timeout. They are
  what covers a failover or an exhausted pool, and the worker cannot
  tell from one exception whether the next write will succeed.
- The heartbeat beside the write. It keeps the lock while the worker
  retries and while it writes the second time.
- An expired lock as the path for a worker that died.

### Tests

- End-to-end on PostgreSQL, with a short lock timeout:
  - a handler enqueues a follow-up that violates a foreign key: the task
    is `FAILED` or `RETRY_PENDING` by its policy, `last_error` names the
    violation, no follow-up exists, and the lock had not expired;
  - a cycle query that fails: the existing test of ADR 0047 expects the
    query's error instead of `lock expired`;
  - a policy that gives up on `OutcomeNotRecordedException`: one
    attempt, `FAILED`;
  - a policy that retries: the handler runs again;
  - an operator cancels the task while the worker retries the write:
    the task stays `CANCELLED`, `onOutcomeDropped` fires;
  - a trigger that refuses every update of the row: the task ends by an
    expired lock, as today;
  - the handler threw and the write of that failure fails: the
    handler's exception is a suppressed exception of the cause the
    listener receives.
- Through `runPending` with the in-memory store behind a store of the
  test that fails `insertAll`: the same outcome without a database.
- An `ErrorDescription` receives the `OutcomeNotRecordedException`.
- In Java: a retry policy reads `getIntended()` and `getCause()`.

## Rejected alternatives

- **Leave it and document it.** The stored text would still point to a
  dead worker, and an operator would still need the log of the right
  instance to find the cause.
- **`FAILED` at once, without the retry policy.** Shorter for a write
  that can never succeed. But the worker cannot know that: a follow-up
  that refers to a row another transaction has not committed yet
  succeeds on the next attempt. An expired lock asks the policy
  (ADR 0026), and two rules for the two ways an attempt ends without
  its outcome would be one more thing to explain. The policy can give
  up by the exception's type.
- **Decide by the SQL state whether to retry the write.** Classes 22,
  23 and 42 do not go away by waiting, so the worker could skip the
  retries for them and record the failure within milliseconds. That
  needs a method on `SqlDialect` or the store that classifies an
  exception, for every database and driver, and a wrong classification
  turns a short interruption into a failed attempt. Can be added later
  behind the same exception without changing what is stored.
- **A shorter retry window, or an option for it.** The window is half
  the lock timeout so that the lock cannot expire while the worker
  writes, and an application that wants a shorter one shortens the lock
  timeout. One more option for a case that is a bug in the application
  does not pay for itself.
- **Write the outcome without the part that failed**, for example with
  a savepoint per follow-up. A task would be `SUCCEEDED` with some of
  its follow-ups missing. ADR 0019 promises that the outcome and its
  follow-ups are stored together or not at all.
- **Store the write's error in the row and leave it `RUNNING`.** The
  text would be right, but the task would still wait for its lock to
  expire, and the expiry would overwrite the text.
- **A listener event of its own**, `onOutcomeNotRecorded`. The attempt
  is a failed attempt and ends in the same two statuses; listeners that
  count failures would have to implement one more method to stay
  complete. The exception's type carries the difference.

## Consequences

- With the defaults, a write that fails every time shows in the table
  after about 30 s instead of 30 s plus up to 60 s, with the write's
  exception in `last_error`.
- An attempt whose handler succeeded can be recorded as failed. The
  handler's result is not stored and its follow-ups do not exist; what
  it did elsewhere has happened. This is what happens today after the
  lock expired; the row now says why.
- Under the default policy the handler runs again, up to its maximum of
  attempts, as it does today.
- `LockExpiredException` in `last_error`, in a listener or in a metric
  means again what its name says: a worker stopped confirming its lock.
- Public API: one exception class in the core, visible in
  `core/api/*.api`. No change to `TaskStore`, `SqlDialect`,
  `TaskListener` or the schema.
- `runPending` behaves the same and, like today, spends half the lock
  timeout of its options on the retries; tests pass a short one.
- Documentation: the guide's list under "When a handler fails" and its
  section on `last_error`, the KDoc of `RetryPolicy` and `TaskListener`,
  a note in ADR 0019 and ADR 0047, which describe today's path.

## Decisions to take

1. The second write contains the task's row alone.
2. The retry policy is asked; it is not `FAILED` at once.
3. The exception is `OutcomeNotRecordedException` with `intended` as a
   `TaskStatus`; a handler's own exception is attached as suppressed.
4. The retries of the first write stay at half the lock timeout; no
   classification by SQL state and no new option.
5. No new listener event.
