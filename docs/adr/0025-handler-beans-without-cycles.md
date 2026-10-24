# Handler beans that may depend on what enqueues

- Status: proposed
- Date: 2026-10-24

## Context

The starter (ADR 0023) builds the `Pendenz` bean from every
`TaskRegistration` bean. A registration is created by a bean method
whose parameters are the handler's dependencies:

```kotlin
@Bean
fun sendInvoice(mail: MailServer, invoices: Invoices): TaskRegistration<Long> =
    TaskRegistration.of(SEND_INVOICE) { _, task -> invoices.markSent(task.invoiceId) }
```

When `Invoices` is the service that enqueues, it injects `PendenzJava`,
and the beans form a circle: `Invoices` needs the queue, the queue needs
its registrations, the registration needs `Invoices`. `examples/spring`
ran into it with its first draft; Spring stops with its generic "Is
there an unresolvable circular reference?" after several nested exceptions,
none of which names Pendenz. A service that enqueues work and also does
that work when the task runs is a common shape, so this is not an edge
case.

The circle is in the core's construction order. `Pendenz.Builder.build()`
needs every task type before it returns: the JDBC store takes the
payload columns of all types (ADR 0013), `enqueue` checks that a type is
registered, the worker tells the wake-up which types it serves. The
handler instance itself is needed only when a task runs.

Spring already offers ways out that work today without any change:
`@Lazy` on the parameter injects a proxy resolved on first use, and an
`ObjectProvider<Invoices>` parameter resolves when asked. Both are
known to Spring users, but nobody reaches for them before the circle
has happened, and the message does not point to them.

## Decision

Two changes in the starter. The core is not touched.

### A message that names the circle and the ways out

While collecting registrations, the starter catches Spring's
`BeanCurrentlyInCreationException` and fails with its own message: which
registration bean could not be created, that the queue is built from its
handlers, and the three ways out below. The original exception stays the
cause.

### Handlers as beans, resolved when they first run

`TaskRegistration` gains an overload that names the handler's bean type
instead of taking an instance:

```kotlin
@Bean
fun sendInvoice(): TaskRegistration<Long> = TaskRegistration.of(SEND_INVOICE, SendInvoiceHandler::class.java)

@Component
class SendInvoiceHandler(private val invoices: Invoices) : TaskHandler<Long, SendInvoice> {
    override suspend fun handle(context: TaskContext<Long>, payload: SendInvoice) = invoices.markSent(payload.invoiceId)
}
```

```java
@Bean
TaskRegistration<Long> sendInvoice() {
    return TaskRegistration.blocking(SEND_INVOICE, SendInvoiceHandler.class);
}
```

- The registration carries the task type, so the queue is built with
  every type and its payload columns as today. It carries no
  dependencies, so building the queue creates no handler bean.
- The handler bean is looked up by type from the bean factory the first
  time a task of the type runs, then kept. Any bean of that type will
  do: a `@Component`, a `@Bean`, with whatever it injects.
- After all singletons are created, the starter checks that each named
  handler type has exactly one bean, and that its declared type
  arguments match the task type's payload and the queue's id type where
  Spring can resolve them. A mistake fails startup, not the first run.
- The lambda form stays: it is shorter where there is no circle, which
  is most of the time.

The README shows the three ways out in this order: a handler bean
named by type, an `ObjectProvider` parameter, `@Lazy`.

## Rejected alternatives

- **Registering handlers after the queue is built**, as a core feature
  (`Pendenz.register` after `build()`). Breaks the store's construction
  from the payload columns of all types, and opens a window in which
  `enqueue` accepts a type no worker can run yet. A registry that can
  change after start is a different core than the one ADRs 0006 and 0013
  describe, for a problem the starter can solve alone.
- **The starter injecting every registration lazily** on its own, for
  instance by building the queue from proxies. The task type, which the
  queue needs at once, sits inside the registration object; there is
  nothing to build the queue from without creating it.
- **Only documenting `@Lazy` and `ObjectProvider`.** What README and ADR
  0023 do now. Correct, but the failure arrives first and does not name
  its cause.
- **An annotation such as `@TaskHandler(type = "send-invoice")` on
  handler classes.** Rejected in ADR 0023 for its reflection and the
  lost type link; the named bean type keeps the link in the
  registration, where the compiler sees the task type.
- **Handler beans found by implementing `TaskHandler` alone.** Also
  rejected in ADR 0023: nothing maps a handler to its task type. The new
  overload is exactly that mapping.

## Consequences

- `TaskRegistration` gains two public overloads, `of(type, Class)` and
  `blocking(type, Class)`, plus the result variants.
- The first run of a task type pays one bean lookup.
- The starter's startup gains the check of named handler types.
- `examples/spring` can let its handler use the service again, through a
  handler bean, and shows the message in a test of the starter.

## Open questions

1. **Message.** Proposal: the starter recognises the circle and explains
   it, with the original exception as cause.
2. **Handler beans named by type.** Proposal: the new overloads, looked
   up on first run, checked after all singletons.
3. **Order in the README.** Proposal: handler bean by type first, then
   `ObjectProvider`, then `@Lazy`.
4. **Example.** Proposal: `examples/spring` keeps the separate
   repository, which is good layering anyway, and gains one handler
   written as a bean.
