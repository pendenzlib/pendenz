# Execution timeout per task type

- Status: accepted
- Date: 2026-11-08

## Context

`WorkerOptions.executionTimeout` bounds how long one handler run may
take, ten minutes by default. When it passes, the handler's coroutine
is cancelled and the timeout counts as a failed attempt for the retry
policy. The interceptors of ADR 0036 run inside the same timeout.

The value belongs to the worker, and a worker runs every registered
type. The types of one queue often differ by orders of magnitude:

- Sending a mail takes a second; a run that takes five minutes hangs
  on a dead connection, and should be cut off long before ten.
- Generating a monthly report or classifying a product with a language
  model (the `catalog` example) takes minutes; ten may be too short.

With one value per worker, an application either picks the longest
run time for all types, so a hanging mail task holds a permit and its
lock for the full ten minutes, or starts one worker per group of
types with its own options. The second works, since a worker only
claims the types registered on its queue, but it means one queue per
group, each with its own registrations, to express one number.

The task type is where the other properties of a kind of work live:
retry policy, retention, resource, claim batch size, approval
(ADR 0010, 0011, 0015, 0017, 0018).

## Decision

`TaskType` gains `executionTimeout: Duration?`, `null` by default.

- **`null` means the worker's value.** A type without its own timeout
  runs under `WorkerOptions.executionTimeout`, as today. Existing code
  is unaffected.
- **The type's value wins in both directions.** A type may have a
  shorter or a longer timeout than the worker; the worker's value is a
  default, not an upper bound.
- **Same rules as the worker's.** At least one millisecond, checked
  when the type is built. The timeout covers interceptors, payload
  decoding and the handler (ADR 0036); its expiry is a failed attempt
  for the type's retry policy. A blocking handler that ignores the
  cancel is still waited for and its result recorded, as before.
- **In-memory mode.** `runPending()` runs through the same runner and
  applies the same timeout.
- **Parameter position.** The parameter comes last in the constructors
  and in `TaskType.json` and `TaskType.columns`, so existing positional
  calls from Java and Kotlin keep compiling. `@JvmOverloads` adds the
  longer overloads.

## Rejected alternatives

- **A map from type name to timeout on `WorkerOptions`.** Keeps the
  type's run time away from the type, and a name typed wrong in the map
  is silently ignored. Different workers could also give the same type
  different timeouts, which nobody asked for.
- **A timeout per task, at enqueue, stored in a column.** A new column
  on a table the user owns (principle 2) for a value that depends on
  the kind of work, not on the single task. A handler that knows a
  task is big can split it or report a deferral (ADR 0035).
- **The worker's value as an upper bound.** Would force raising the
  worker's value for one long type, which is the problem this solves.
- **A property per type in the Spring starter**
  (`pendenz.types.<name>.execution-timeout`). The types are built in
  code, where the timeout can be set from any property the application
  likes. Can be added later if asked for.

## Consequences

- One nullable property on `TaskType`, one lookup in the runner.
- The guide's table of worker options names the type's timeout as the
  override; the section on failed handlers names both.
- `TaskContext` does not tell the handler its deadline. A handler that
  needs it for a client timeout reads it from its own configuration;
  adding the deadline to the context is a separate change.

## Decisions taken with the acceptance (2026-11-08)

1. `TaskType.executionTimeout` is nullable; `null` leaves the timeout to
   `WorkerOptions.executionTimeout`.
2. The type's value wins in both directions; the worker's value is a
   default, not an upper bound.
3. The parameter comes last in the constructors and in
   `TaskType.columns`, so existing calls keep compiling.
4. The Spring starter has no property per type.
