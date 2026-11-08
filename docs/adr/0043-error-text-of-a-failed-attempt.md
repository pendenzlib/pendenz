# The error text of a failed attempt

- Status: accepted
- Date: 2026-11-08

## Context

When an attempt fails, the queue writes a text into `last_error`: on a
retry and on `FAILED`. The text is always `Throwable.toString()`, the
class name and the message, in three places:

- the handler threw, timed out or its payload could not be decoded
  (ADR 0026),
- the stored payload has a version the type cannot upgrade (ADR 0010),
- the lock of the task expired (ADR 0026), with the queue's own
  `LockExpiredException`.

The application cannot change that text, and there are reasons to:

- **Secrets and personal data.** An exception message can carry what
  the code was working on: a JDBC driver names the values that broke a
  constraint, an HTTP client quotes the URL with its query string, a
  parser quotes the input. `last_error` is read by more people than the
  logs are: operators with SQL, the generated views `_failures` and
  `_stuck`, code through `TaskRecord` and `tasks()`, and, when the MCP
  module is built with `ErrorText.EVERYTHING`, AI agents through
  `get_task` (ADR 0021). The MCP default already hides exception texts
  from the agent; the other readers see the column as it is.
- **The useful part of the chain.** Frameworks and clients wrap
  exceptions; `toString()` of the outermost one often says
  `ExecutionException: ...` and leaves out the cause that explains the
  failure. Some applications want the stack trace in the row, others a
  fixed length.

The reasons a handler or an operator gives on purpose, for a rejection
(ADR 0020), a deferral or a hand-over (ADR 0035) or a quarantine
(ADR 0031), are text the application already chose. They are not
affected.

## Decision

A new functional interface in the core, configured once per queue:

```kotlin
public fun interface ErrorDescription {
    public fun describe(context: TaskContext<*>, error: Throwable): String

    public companion object {
        /** The class name and the message, `Throwable.toString()`. */
        @JvmField
        public val DEFAULT: ErrorDescription = ErrorDescription { _, error -> error.toString() }
    }
}
```

- **Configured on the queue builder**, `Pendenz.Builder.errorDescription(...)`,
  at most once, like `CallerDefaults`; the Java facade passes it
  through. Redacting secrets is a rule for the whole application, not
  for one type. The context carries the type name, so a description that
  differs by type is still one function.
- **Every exception that becomes `last_error` goes through it**: all
  three places above, the queue's own `LockExpiredException` included,
  so a description that, say, adds the root cause works the same for
  every failure. The context of an expired lock has no deadline
  (ADR 0042).
- **The text is stored as returned.** The core does not cut it; a limit
  is the description's business. PostgreSQL stores long `text` values
  out of line.
- **A description that throws** is logged at ERROR, and the attempt is
  recorded with the exception's class name only. Falling back to
  `toString()` would write the message the description was meant to
  keep out.
- **The listener is unchanged.** `onRetryScheduled` and `onFailed`
  receive the `Throwable` as before; a listener that logs or counts
  failures decides on its own what it writes.
- **The debug log line of a failed attempt** uses the description, so
  the log and the row say the same.
- **Spring starter:** one `ErrorDescription` bean is used, several fail
  startup, like `Permissions` and `CallerDefaults`.

## Rejected alternatives

- **One description per task type.** Redaction would have to be repeated
  on every type, and a type that forgets it leaks. The context names the
  type for the cases that need a difference.
- **A length limit in the core.** Any number would be arbitrary for
  someone; the description can cut, and the default keeps today's text.
- **Ready-made descriptions in the core** (root cause, stack trace,
  cut to a length). Each is a line or two on top of the interface, and
  the guide shows them; the core keeps one default.
- **Redacting in the listener or with a trigger.** The listener runs
  after the row is written, and a trigger sees only the text, without
  the exception and its chain.

## Consequences

- No change in behaviour without configuration; no schema change.
- New public API: `ErrorDescription`, `Pendenz.Builder.errorDescription`,
  `PendenzJava.Builder.errorDescription`. The name differs from the MCP
  module's `ErrorText`, which chooses what an agent sees of the column
  and stays as it is; the two work one after the other.
- The guide shows a description that names the root cause and cuts the
  text, and one that keeps only the class name for exceptions of a
  library known to quote input.

## Decisions taken with the acceptance (2026-11-08)

1. `ErrorDescription` in the core, named apart from the MCP module's
   `ErrorText`, configured once per queue on the builder, at most once;
   the Java facade passes it through.
2. Every exception that ends an attempt goes through it, the queue's
   own `LockExpiredException` included; reasons given on purpose do not.
3. The text is stored as returned, without a limit in the core.
4. A description that throws is logged, and the attempt keeps the
   exception's class name only.
5. The listener receives the exception unchanged.
6. The Spring starter uses one `ErrorDescription` bean; several fail
   startup.
