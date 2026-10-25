# Graceful shutdown of a worker

- Status: accepted
- Date: 2026-10-19

## Context

A worker runs as a coroutine; cancelling its job stops it. Cancellation in
structured concurrency is immediate: every handler running under the worker
is cancelled with it. A handler in the middle of a bank transfer or a mail
send is cut off, and because the worker never records an outcome, the row
stays `RUNNING` until another worker releases it after `lockTimeout`
(sixty seconds by default). Every deployment therefore produces a minute of
dead time for whatever was in flight, plus a rerun of work that was perhaps
nearly done.

Applications stop workers in two ways: by cancelling the job returned from
`startWorker`, or by cancelling the scope that `runWorker` was called in.
Both must behave the same.

## Decision

Cancelling the worker's job remains the only stop signal. No second API.

On cancellation the worker:

1. stops claiming immediately;
2. lets handlers that are already running finish, for at most
   `WorkerOptions.shutdownTimeout` (thirty seconds by default), and records
   their outcomes as usual;
3. cancels the handlers still running after that, and releases their tasks
   back to `PENDING` right away, so another worker can claim them without
   waiting for `lockTimeout`;
4. returns only when every handler coroutine has ended. A handler that does
   not cooperate with cancellation, for example because it blocks in
   non-interruptible code, keeps the worker from returning; its task stays
   `RUNNING` until the handler ends, and what the handler then returns or
   throws is recorded as usual, because the work was in fact done. The
   heartbeat stops with the cancellation, so a handler that outlives
   `lockTimeout` loses the row to the next worker, exactly as if the
   process had died, and its late outcome is dropped. Since ADR 0026 that
   lost lock counts as a failed attempt and goes through the retry
   policy (amended 2026-10-25).

`attempts` keeps counting. The row says how often the task was claimed, and
a claim that was given back because of a restart is still a claim. The
retry policy sees that number, so a task that is repeatedly interrupted by
restarts moves towards `FAILED` a little faster than its handler failures
alone would justify. This is accepted; it is rare and the row tells the
story.

To make this work, handlers no longer run as children of the polling loop.
The loop reserves parallelism permits before it claims, so a worker never
holds more claimed tasks than it can run, and launches each handler into a
supervisor scope of its own that survives the loop's cancellation until the
shutdown has completed.

## Rejected alternatives

- **A `WorkerHandle` with `stop(timeout)`.** Explicit, but a second stop
  path beside job cancellation. Users who put the worker into their
  application scope would get the abrupt behaviour by accident.
- **Cancelling handlers immediately and releasing the task.** Simpler, but
  the handler's partial effect is still there and the task reruns at once,
  which is worse than letting it finish.
- **Waiting without a bound.** A hanging handler would keep the process
  from stopping. Process supervisors kill it, and then we are back to the
  lock timeout anyway.
- **Resetting `attempts` on release.** Hides restarts from the row. The
  attempt counter would no longer mean what its column says.

## Consequences

- One new option, `shutdownTimeout`. Zero disables waiting.
- One new store operation, `release`, which moves a `RUNNING` task back to
  `PENDING` with the usual version check.
- Tests can stop a worker with `cancelAndJoin` and read final state
  without waiting on time.

Amended on review (2026-10-25): the heartbeat ran on the dispatcher of
the worker's scope. A handler that blocked that dispatcher, such as a
worker started in `runBlocking` with a handler doing blocking I/O, kept
its own lock from being renewed until another worker took the task. The
heartbeat now runs on a few threads of the IO pool kept for heartbeats,
whatever dispatcher the worker runs on. Handlers that block should still
run on an IO dispatcher, as the Java facade's do.
