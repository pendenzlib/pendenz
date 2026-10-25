# Failures the handler cannot report: errors and expired locks

- Status: accepted
- Date: 2026-10-25

## Context

The retry policy is the one place that decides how often a task is
tried and when it gives up (ADR 0002: `FAILED` means "attempts
exhausted"). It is asked when the handler throws an `Exception`. There
are two other ways an attempt can end badly, and in both the policy is
never asked.

**The handler throws an `Error`.** `TaskRunner` catches
`CancellationException` and `Exception`. A `NotImplementedError` from a
forgotten `TODO()`, a `StackOverflowError` from runaway recursion, an
`ExceptionInInitializerError` or `NoClassDefFoundError` from a broken
dependency, an `AssertionError` from a library: all of them pass every
catch. No outcome is recorded. The heartbeat ends with the scope, the
coroutine machinery logs the error, and the row stays `RUNNING`. The
next worker releases it after the lock timeout, claims it again and
counts the attempt, and the same `Error` follows. The task never
reaches `FAILED`, `onFailed` never fires, and `runPending` throws
although its contract says a failing handler does not make it throw.

**The worker vanishes.** The process is killed by the OOM killer, by
`kill -9`, by a container eviction, or by native code in the handler.
Nobody records anything, and that cannot change. After the lock
timeout, `releaseStale` sets the row back to `PENDING` in one blind
`UPDATE`, without looking at `attempts`. A task that kills its worker
every time, for instance a payload that makes the handler allocate
more memory than the heap has, takes down a worker every lock timeout,
forever. There is no backoff between the crashes, and the task never
shows up where operators look for failures.

The two cases differ in one respect. The first can be recorded when it
happens; the second can only be noticed later, by someone else. They
belong together because the second is the backstop for the first: an
`OutOfMemoryError` may leave too little memory to record anything, and
then the expired lock is all that is left.

## Decision

### Every throwable from the handler is a failed attempt

The worker records whatever the handler throws as a failure and asks
the retry policy, exactly as for an `Exception`. That includes `Error`
and its subclass `VirtualMachineError` (`OutOfMemoryError`,
`StackOverflowError`). The only exception is the worker's own
cancellation, which stays as ADR 0005 describes: release on shutdown,
drop when the heartbeat found the row taken away. A
`CancellationException` that is not ours is already counted as a
failure; that does not change.

Letting an `Error` escape does not help anyone. The coroutine machinery
only logs it. Whether the JVM should die after an `OutOfMemoryError` is
for the operator to decide with `-XX:+ExitOnOutOfMemoryError`, not for a
library. If recording fails because the JVM is too broken, the expired
lock catches the task (below).

`runSafely` logs every throwable from recording an outcome as well, not
only exceptions, so a worker loop never loses a handler coroutine
without a log line from Pendenz.

### An expired lock is a failed attempt

When a worker finds a `RUNNING` row whose lock has expired, it no longer
sets the row back to `PENDING`. It treats the expiry as the failure of
the attempt that held the lock:

1. In the claim transaction, before claiming, the worker selects expired
   rows of the task types **it has registered**, with
   `FOR UPDATE SKIP LOCKED` and a bounded batch size, so two workers
   never handle the same row and one poll never does unbounded work.
2. For each row it asks the type's retry policy, with the row's attempt
   number and a `LockExpiredException` as the cause. The exception names
   the worker that held the lock and the last time it confirmed the lock.
3. It records `RETRY_PENDING` with the policy's run time, or `FAILED`,
   through the existing `finish` with the row's version. `last_error`
   reads like `lock expired: worker host-1234 stopped confirming its lock at
   2026-10-25T10:15:00Z`.
4. The listener sees `onRetryScheduled` or `onFailed` with the
   `LockExpiredException` as the cause, like any other failure.

Which locks count as expired is defined by ADR 0027 (the deadline in the
row), not by the polling worker's own options.

Rows of task types the worker has not registered stay untouched. A
worker cannot ask a retry policy it does not have, and nobody could run
such a task anyway. If no worker for the type is alive, the row stays
`RUNNING` with an expired lock and shows up in the `_stuck` view, which
is exactly what an operator needs to see.

Releasing a task on shutdown (ADR 0005) is unchanged. It is a graceful
hand-over, not a failure, and goes straight back to `PENDING`.

### Store seam

`TaskStore.releaseStale(tx, staleBefore): Int` is replaced by an
operation that returns the expired rows instead of resetting them:

```kotlin
public fun expiredLocks(tx: StoreTransaction, types: Collection<String>, now: Instant, limit: Int): List<ExpiredLock<ID>>
```

`ExpiredLock` carries what `ClaimedTask` carries plus `lockedBy` and the
expired deadline, so the worker can build the `TaskContext` for the
listener. Recording uses `finish`, which already checks status
`RUNNING` and the version. Both stores implement it; the contract tests
cover both.

## Rejected alternatives

- **A fixed cap in SQL**, `CASE WHEN attempts >= n THEN 'FAILED'` in the
  release statement. One statement and no round trip per row, but a
  second limit beside the retry policy that users would have to keep
  in step with it. The policy is code and cannot run in SQL. And it
  gives no backoff between crashes.
- **Catching `Exception` only and documenting it.** `TODO()` and
  `StackOverflowError` are ordinary bugs in ordinary handlers. A
  contract that makes them loop forever is a trap.
- **Rethrowing `VirtualMachineError` so the process dies.** Some
  frameworks do this. Whether a process survives an
  `OutOfMemoryError` is a deployment decision, and the JVM already has
  a flag for it.
- **A separate crash counter column.** It would let crashes and
  failures be limited separately, at the price of one more column in
  the user's schema for one rule. The retry policy can tell the two
  apart through the cause if a user wants that.
- **Resetting to `PENDING` as now, but only below a limit.** Same
  problem as the fixed cap: which limit, and no backoff.

## Consequences

- A task that kills its worker reaches `FAILED` after the retry policy
  gives up. With the default `ExponentialBackoff` that is five attempts,
  with growing pauses in between instead of a crash every lock timeout.
- A task whose worker died runs again after the policy's delay, not at
  the next poll. With the defaults that is about one second after the
  expiry is noticed.
- `LockExpiredException` is new public API. A retry policy can
  distinguish a crash from a handler failure with `cause is
  LockExpiredException`.
- ADR 0005, point 4: a handler that ignores cancellation past the lock
  timeout loses the row "exactly as if the process had died". With
  this ADR that now means: its attempt counts as failed. ADR 0005 gets a
  note when this one is accepted.
- `TaskStore.releaseStale` is replaced (API change before 1.0). The
  listener event `onStaleLocksReleased` and the metric
  `pendenz.tasks.stale.released` change with it, see open question 4.
- Tests: a handler throwing `NotImplementedError` ends `FAILED` after
  `maxAttempts`; a `StackOverflowError` is recorded; an expired lock
  leads to `RETRY_PENDING` and finally to `FAILED`; a row of an
  unregistered type stays `RUNNING`; `runPending` does not throw for an
  `Error`. `WorkerProcessEndToEndTest` (second JVM killed with
  `kill -9`) expects the task to pass through `RETRY_PENDING`.

## Decisions taken with the acceptance (2026-10-25)

1. Every throwable from the handler is a failed attempt, including
   `VirtualMachineError`. Process death after an `OutOfMemoryError` is
   left to the JVM flag.
2. Expired locks go through the retry policy, not through a fixed cap.
3. The cause is `LockExpiredException` in `io.github.d4niella.pendenz`,
   with `lockedBy` and `lockedUntil`.
4. `onStaleLocksReleased` is removed. An expired lock fires
   `onRetryScheduled` or `onFailed` with the `LockExpiredException`; the
   Micrometer module drops `pendenz.tasks.stale.released` and counts
   expired locks through the `exception` tag.
5. Rows of task types the worker has not registered are left alone.

Noted during implementation:

- The worker records at most 100 expired locks per poll; the rest follow
  at the next poll. The handling lives in `LockExpiry`, beside
  `Maintenance`.
- The `TaskContext` of an expired lock carries the `now` of the poll
  that found it as `claimedAt`, because the time of the lost claim is
  not stored. The execution timer of the Micrometer module therefore
  records such an attempt with a duration of about zero.
- A `finish` that loses the version check, because an operator changed
  the row in the same moment, leaves the row to the operator and fires
  no event.
