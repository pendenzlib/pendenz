# Creation time and deadline in the task context

- Status: accepted
- Date: 2026-11-08

## Context

`TaskContext` is what code that does not know the payload type can read
about a task: the handler, the interceptors (ADR 0036), the listener
(ADR 0032) and, since ADR 0038, the retry policy. It carries the
attempt, creator, lane, priority, resource, chain, the run time the task
was claimed for (`dueAt`) and the claim time (`claimedAt`). Two values
are missing, and ADR 0038 and ADR 0039 name both as later changes:

- **When the task was enqueued.** A retry policy that gives up once a
  task is a day old, whatever its attempt count, needs it. So does a
  handler that skips work that has become pointless, and a listener
  that measures the whole time from enqueue to outcome rather than
  only the wait of the last attempt. The row has it in `created_at`;
  the claim does not read it.
- **When the execution timeout ends.** A handler that calls an HTTP
  client with a timeout of its own cannot align it with the run's
  timeout: the handler knows neither the worker's `executionTimeout`
  nor, since ADR 0039, the type's. A client timeout longer than the
  run's means the coroutine is cancelled while the call is still
  waiting.

## Decision

`TaskContext` gains two properties.

- **`createdAt: Instant`**, the row's `created_at`: when the task was
  inserted, by `enqueue`, a cycle, a handler's follow-up or a schedule.
  It is the same in every place a context is built, the retry policy's
  `FailedAttempt` included, because it comes from the row.
- **`deadline: Instant?`**, when the execution timeout of the current
  run ends, on the queue's clock: the time the runner started the run
  plus the type's or the worker's timeout. It is set in the context the
  handler, the interceptors and the outcome events of that run see. It
  is `null` where no run is going on: in `onClaimed` and `onReleased`,
  for an expired lock, and in the context of a `FailedAttempt`, which
  is built from the row (ADR 0038).

What changes underneath:

- **The claim reads `created_at`.** `ClaimedTask` gains `createdAt`,
  and the claim statements and the statement for expired locks return
  the creation column. `TaskSql` documents it; a custom dialect has to
  return the column, and a custom store has to fill the property.
- **The runner computes the deadline once** from the same start time and
  timeout it passes to `withTimeout`, so the two agree up to the time
  between reading the clock and starting the timer.
- **Constructor.** `TaskContext` takes `createdAt` after `claimedAt`,
  and `deadline` last with a default of `null`; a test that builds a
  context by hand passes the creation time.

## Rejected alternatives

- **The remaining time as a `Duration`.** It is stale the moment it is
  read; an instant can be compared with the clock whenever needed.
- **The deadline as a coroutine context element.** Readable only from
  Kotlin inside the handler's coroutine; Java handlers run on another
  thread (ADR 0036) and could not see it.
- **A deadline in every context, computed from `claimedAt`.** Wrong for
  an expired lock or a retry policy, where no run is going on, and
  slightly off for a run that started after its claim.
- **The whole `TaskRecord` in the context.** Reading the row again per
  run, for columns nobody asked for; see ADR 0038.

## Consequences

- Breaking for code that constructs `TaskContext` or `ClaimedTask`, and
  for custom dialects and stores, which return or fill the creation
  time. Named in the changelog.
- A retry policy can give up by age:
  `if (failure.failedAt > failure.task.createdAt + 1 day) null else ...`.
- The guide shows both: the age check in the retry policy section and
  the deadline next to the execution timeout.

## Decisions taken with the acceptance (2026-11-08)

1. `createdAt` is a required constructor parameter of `TaskContext`,
   after `claimedAt`; `deadline` comes last with a default of `null`.
2. `ClaimedTask` gains `createdAt`, and the claim statements return the
   creation column; custom dialects and stores follow.
3. `deadline` is the run's start plus the type's or the worker's
   timeout, on the queue's clock, and `null` where no run is going on.
4. No remaining time as a `Duration`, no coroutine context element.

