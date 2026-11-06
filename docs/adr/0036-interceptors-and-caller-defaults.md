# Interceptors around the handler and defaults from the caller

- Status: accepted
- Date: 2026-11-05

## Context

Two places carry context across the boundary between the code that
enqueues a task and the code that runs it, and in both the queue offers
no place for the application's own logic.

### Around the handler

`TaskRunner.execute` calls the registered handler inside
`withTimeout(executionTimeout)`, in the coroutine context of the worker.
Nothing runs between the claim and the handler except the payload
decoding. `TaskListener` observes `onClaimed` and the outcome events,
but it is synchronous, cannot hold state across the run and cannot
change the coroutine context the handler runs in. That leaves out work
that has to enclose the handler:

- **Logging context.** Putting the task id, type and correlation id
  into the SLF4J MDC for every line the handler logs. In a coroutine
  that needs a `ThreadContextElement` such as `MDCContext` from
  `kotlinx-coroutines-slf4j`, added with `withContext` around the call.
- **Tracing.** Starting a span when the handler starts and ending it
  when the handler ends, continuing the trace the caller stored in
  `correlationId`. ADR 0022 rejected the Micrometer Observation API for
  the metrics module because "it wants a scope around the handler, which
  the listener does not give", and named an around-hook in the core as
  the subject of a later ADR.
- **Security or tenant context.** Establishing the identity in
  `createdBy` for the duration of the run, for example in Spring
  Security's `SecurityContextHolder`, so that service methods the
  handler calls check permissions against it.
- **Translating exceptions.** Mapping a client library's exception to
  one of the decisions of ADR 0035, for every handler that uses the
  client, in one place.

Today each of these is possible only by wrapping every handler by hand
before registering it. That has to be repeated per registration, and
the Spring starter registers handler beans through `TaskRegistration`,
where a wrapper has to be applied to each registration bean.

Java handlers add a constraint. `JavaTaskHandler.asTaskHandler` runs
the blocking method with `runInterruptible` on a dispatcher of its own,
the IO pool by default. A thread-local value set in the worker's
coroutine is not visible on that thread unless it travels as a
`ThreadContextElement`, and Java code cannot write one.

### At enqueue

`enqueue`, `startCycle` and the operator moves (`restart`, `complete`,
`quarantine`, `cancel`, `pauseCycle`, `resumeCycle`, `cancelCycle`)
take the caller's identity as a parameter: `createdBy` or `actor`. The
identity goes into the row and into the permission check of ADR 0018.
`enqueue` and `startCycle` also take a `correlationId` (ADR 0019).

In a web application both values are usually known from the request:
the authenticated user and the trace id. Every call site has to read
them and pass them on. A call site that forgets the identity enqueues as
the anonymous caller, which `Permissions` either refuses or, with
`ALLOW_ALL`, accepts without a trace of who it was. ADR 0021 fixed one
actor per MCP server and named "a resolver per request" as possible
later work.

## Decision

Two hooks on the builder: a `TaskInterceptor` that encloses every
handler run, and `CallerDefaults` that supply identity and correlation
when a call leaves them out.

### TaskInterceptor

```kotlin
public fun interface TaskInterceptor<ID : Any> {
    /** Runs around one handler run; [proceed] runs the next interceptor or the handler. */
    public suspend fun intercept(context: TaskContext<ID>, proceed: suspend () -> Unit)
}
```

Registered with `Builder.interceptor(interceptor)`, any number of them.
The first registered is the outermost.

- **Where it runs.** Inside `withTimeout(executionTimeout)`, around the
  payload decoding and the handler, so the timeout covers the
  interceptors and a decoding error passes through them. Outside the
  outcome transaction: when `intercept` returns, the outcome is not yet
  written, and the listener events of ADR 0032 come later. The
  heartbeat runs beside it as before.
- **What it may do.** Change the coroutine context of `proceed` with
  `withContext`, which carries thread-local values to the thread a
  blocking handler runs on; run code before and after; catch what
  `proceed` throws and throw something else, a decision of ADR 0035
  included.
- **What it may not do.** It does not see the payload or the result:
  `TaskContext` is what every interceptor can read without knowing the
  type. It cannot replace the result or skip the handler. `proceed`
  must be called exactly once; a second call, or a return without a
  call, is an `IllegalStateException`, which counts as a failed attempt
  like any exception of the handler.
- **Exceptions.** What an interceptor throws is handled as if the
  handler had thrown it: a decision of ADR 0035 is recognised, anything
  else goes to the retry policy (ADR 0026). Cancellation passes through
  unchanged.
- **In-memory mode.** `runPending()` uses the same path, so tests see
  the interceptors (principle 9).
- **Not for maintenance.** Maintenance, lock expiry and schedule
  occurrences run no handler and pass through no interceptor.

### Interceptors for Java

Java code cannot implement a `suspend` function, and a thread-local
value set in an interceptor must be visible on the thread where the
blocking handler runs. `core-java` therefore offers a blocking variant
that runs on the handler's thread:

```kotlin
public fun interface BlockingTaskInterceptor<ID : Any> {
    @Throws(Exception::class)
    public fun intercept(context: TaskContext<ID>, proceed: Proceed)
}

public fun interface Proceed {
    @Throws(Exception::class)
    public fun proceed()
}
```

`PendenzJava.Builder.interceptor(BlockingTaskInterceptor)` registers
one. They are applied inside `blocking(dispatcher)` around every
`JavaTaskHandler` and `JavaResultHandler` registered through the
facade, so they run on the same thread as the handler, in registration
order. They enclose only handlers registered through the facade; a
`TaskInterceptor` encloses every handler.

### CallerDefaults

```kotlin
public interface CallerDefaults {
    /** The identity of the current caller, or null when there is none. */
    public fun actor(): String? = null

    /** The correlation id of the current call, or null when there is none. */
    public fun correlationId(): String? = null
}
```

Registered with `Builder.callerDefaults(defaults)`, at most one.

- **Where it applies.** `actor()` fills `createdBy` of `enqueue` and
  `startCycle` and `actor` of the operator moves and cycle moves, when
  the call passes null. `correlationId()` fills `correlationId` of
  `enqueue` and `startCycle` when the call passes null.
  `approve`, `approveCycle` and `decline` require an actor today and
  keep requiring it.
- **Explicit values win.** A value passed in the call is never
  replaced. The MCP module, which passes its server's actor, is
  unaffected.
- **Before the permission check.** The filled actor is the one
  `Permissions.allows` sees and the one stored in `created_by`. That is
  the purpose: the check sees who called.
- **On the caller's thread.** The defaults are read when the call
  starts, before the queue opens a transaction or switches dispatcher,
  so thread-local state such as `SecurityContextHolder` or the MDC is
  visible. `PendenzJava.enqueueAsync` and the other asynchronous calls
  read them on the calling thread before handing the write to their
  own scope.
- **Not inside the worker.** Follow-ups take their parent's
  `createdBy` and `correlationId` (ADR 0019); schedule occurrences
  carry the schedule mark (ADR 0033). Neither asks `CallerDefaults`.
- **Exceptions.** An exception from `CallerDefaults` propagates to the
  caller and the operation does not happen. A missing identity does not
  silently become the anonymous caller.

### Spring starter

Beans of type `TaskInterceptor`, `BlockingTaskInterceptor` and
`CallerDefaults` are picked up like `TaskListener` beans. Interceptors
are ordered by `@Order` or `Ordered`, lowest first and outermost.
`TaskRegistration.blocking` applies the blocking interceptors to the
handlers it registers. More than one `CallerDefaults` bean is a startup
error that names the beans. The starter ships no `CallerDefaults` of its
own; reading Spring Security or Micrometer Tracing would add a
dependency, and the guide shows the few lines an application writes.

### Tests

- Worker with the in-memory store: interceptors run in registration
  order around the handler; an interceptor's `withContext` element is
  visible in the handler; an exception the interceptor maps to
  `RetryLaterException` defers the task; an interceptor that does not
  call `proceed`, or calls it twice, is a failed attempt; the timeout
  covers a slow interceptor.
- `CallerDefaults`: a null `createdBy` is filled and reaches the
  permission check; an explicit one is kept; the follow-up of a handler
  keeps its parent's creator, not the default; an exception from the
  defaults leaves no row.
- Java: a `BlockingTaskInterceptor` sets an MDC value that the blocking
  handler reads on its thread; `CallerDefaults` implemented in Java
  fills the creator of `enqueue` and `enqueueAsync`.
- Spring: interceptor beans run in `@Order` order; two
  `CallerDefaults` beans fail the start.

## Rejected alternatives

- **An around-method on `TaskListener`.** The listener reports after
  the commit (ADR 0032) and its exceptions are swallowed so that a
  faulty listener cannot break the worker. An interceptor runs before
  the outcome and its exceptions count; one interface cannot keep both
  rules.
- **Separate `before` and `after` hooks.** They cannot change the
  coroutine context of the handler and cannot keep a span or a scope
  open across the run without state of their own keyed by task id.
- **A `CoroutineContext` factory per task**, `(TaskContext) ->
  CoroutineContext`, on the worker options. Covers the MDC and
  thread-local identity, but not spans that have to end, nor exception
  translation. The interceptor covers it with one `withContext`.
- **An interceptor on enqueue that may change the whole new task.**
  Payload, type, lane and run time are the caller's business decisions.
  A hook that rewrites them hides behaviour from the call site; the
  defaults are limited to the two values that come from the call's
  environment rather than from the call.
- **Defaults for lane, priority and resource.** These depend on the task
  type and its payload, not on who calls. A per-type default belongs on
  the `TaskType`, if anywhere.
- **The identity as a coroutine context element only.** Works for Kotlin
  callers inside a coroutine, not for the blocking overloads and not for
  Java, which call from plain threads.
- **A thread-local of the queue's own.** The application already has
  one, its security framework's; a second one would have to be kept in
  step with it.

## Consequences

- Public API: `TaskInterceptor`, `CallerDefaults`, two builder methods
  in the core; `BlockingTaskInterceptor`, `Proceed` and a builder method
  in `core-java`; bean discovery in the starter.
- A call cannot enqueue anonymously while `CallerDefaults` returns an
  actor: null means "not given", not "nobody". An application that
  needs both returns null from `actor()` in that situation.
- A slow interceptor uses the execution timeout of the handler it
  encloses.
- The span of a tracing interceptor ends before the outcome is
  written; the time of that write stays with the listener's timers
  (ADR 0022).
- A tracing or MDC module can be built on the interceptor without a
  change to the core. Such modules are not part of this decision.
- The guide gains a section on interceptors with the MDC example, and
  one on caller defaults with a Spring Security example; the Java page
  gains the blocking variant.

## Decisions taken with the acceptance (2026-11-06)

1. Interceptors run inside the execution timeout.
2. An interceptor that does not call `proceed`, or calls it twice, ends
   the attempt as a failure; it cannot skip a task.
3. The interceptor sees `TaskContext` only, neither payload nor result.
4. `core-java` has a blocking variant, applied only to handlers
   registered through the facade.
5. `CallerDefaults` fills the actor of the operator moves and cycle
   moves too, not only of `enqueue` and `startCycle`.
6. `approve`, `approveCycle` and `decline` keep their required actor;
   the defaults do not apply to them.
7. The defaults supply the correlation id alongside the actor.
8. The Spring starter ships no `CallerDefaults` of its own.
