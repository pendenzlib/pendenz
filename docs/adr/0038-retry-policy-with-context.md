# The retry policy sees the task

- Status: proposed
- Date: 2026-11-08

## Context

A `RetryPolicy` is asked after a failed attempt, in two places: by the
worker when the handler threw (`TaskRunner.failure`), and by the poll
when a lock expired because a worker died with the task
(`LockExpiry`, ADR 0026). It gets three values:

```kotlin
fun nextAttemptAt(attempt: Int, failedAt: Instant, cause: Throwable): Instant?
```

The policy belongs to the task type, so it knows the type. It knows
nothing else about the task that failed. The row and the
`TaskContext` the handler saw carry more, and some of it is a reason
to retry differently:

- **Resource.** A task against `partner-api:acme` may wait longer than
  one against the internal mail server; with one type for all
  partners, only the resource tells them apart.
- **Lane and priority.** A task in a cycle's lane can wait for the
  night; a single task a person waits for should be retried soon or
  given up quickly (ADR 0015).
- **Creator.** A task enqueued by an agent (ADR 0021) may get fewer
  attempts than one enqueued by the application.
- **Run time.** `dueAt` and `claimedAt` say how late the attempt was.

Today a policy that needs any of this has to look the row up itself,
by an id it is not given.

`TaskContext` is already the read model of a task for code that does
not know the payload type: the listener gets it for every event
(ADR 0032), the interceptor gets it around the handler (ADR 0036).

## Decision

The policy gets one parameter, `FailedAttempt`, which carries the
task's context next to the time and the cause.

```kotlin
public fun interface RetryPolicy {
    public fun nextAttemptAt(failure: FailedAttempt): Instant?
}

public class FailedAttempt(
    public val task: TaskContext<*>,
    public val failedAt: Instant,
    public val cause: Throwable,
) {
    /** The attempt that failed, starting at 1; the same as `task.attempt`. */
    public val attempt: Int get() = task.attempt
}
```

- **Replaces the three-argument method.** `RetryPolicy` stays a
  functional interface with one method. `NoRetry` and
  `ExponentialBackoff` implement the new one and behave as before. A
  policy written against 0.5 changes from `{ attempt, failedAt, cause
  -> ... }` to `{ f -> ... }` with `f.attempt`, `f.failedAt`,
  `f.cause`. The break is named in the changelog.
- **The constructor is public**, so a policy can be tested by calling
  it with a `FailedAttempt` built in the test.
- **The context is a copy without follow-ups.** The policy gets a
  `TaskContext` built from the claimed row, not the one the handler
  ran with. Its `enqueue` throws `IllegalStateException` as it does
  outside a handler; the provenance the handler attached is not part
  of it. A follow-up enqueued from a policy would be dropped with the
  failed outcome anyway (ADR 0019).
- **The same context in both places.** For an expired lock, `claimedAt`
  is the time the expiry was found, as in `ExpiredLock.task` today,
  and the cause stays `LockExpiredException`.
- **No payload.** The policy is called for an expired lock, where the
  poll does not decode the payload, and the context is what every
  policy can read without knowing the payload type, as for the
  interceptor (ADR 0036, decision 3).
- **A policy that throws gives up.** Today an exception from the policy
  propagates: in the worker the outcome is not written, the task stays
  `RUNNING` until its lock expires, and then the poll asks the same
  policy inside its transaction, where the exception fails the whole
  poll, for every type, at every poll interval. With more logic in
  policies this becomes likelier. From now on an exception from
  `nextAttemptAt` is logged with the task id and the task becomes
  `FAILED`, with the handler's cause as `last_error`, as if the policy
  had returned `null`. `Error`s are treated the same way, as for the
  handler (ADR 0026); cancellation propagates.

## Rejected alternatives

- **Keep the three-argument method and add an overload with a
  default.** A policy that wants the context would override the new
  method and still have to implement the old abstract one, and a
  lambda could only ever implement the old one.
- **A second interface, `ContextualRetryPolicy`.** Two ways to say the
  same thing on `TaskType`, and every place that asks a policy has to
  handle both (see ADR 0017, decision 3, for the same reasoning about
  limits).
- **Pass the payload.** It needs a generic policy (`RetryPolicy<P>`)
  and a decoded payload in the poll that handles expired locks. A
  policy that needs payload data can ask the handler to put it into
  the exception it throws.
- **Pass the `TaskRecord`.** It holds the full row with `last_error`
  and timestamps, but building it means reading the row again in both
  places. Nothing asked for those columns yet.
- **The first enqueue time (`created_at`) in the context**, for a
  policy that gives up after a day. The claim does not read that
  column today; adding it to `TaskContext` is a change of its own,
  for the listener and the interceptor as well.

## Consequences

- Breaking change in the public API: `RetryPolicy.nextAttemptAt` has a
  new signature; `FailedAttempt` is new. Users of `NoRetry` and
  `ExponentialBackoff` are not affected, only policies of their own
  and code that calls `nextAttemptAt` directly.
- A faulty policy costs the task its remaining attempts instead of
  stopping the poll of every task type.
- The guide's example of a policy of one's own changes, and gains one
  that uses the resource.
