# A builder for task types

- Status: accepted
- Date: 2026-11-08

## Context

`TaskType` has grown one constructor parameter per aspect of a kind of
work: payload mapping, retry policy, payload version, upgrades,
retention, resource, claim batch size, approval, preview and, with
ADR 0039, the execution timeout. Kotlin callers name the parameters
they set and leave the rest at their defaults. Java callers have
positional overloads from `@JvmOverloads`, which only cut parameters
from the end. A Java type that needs approval passes seven values it
does not care about first; one with its own execution timeout passes
ten:

```java
new TaskType<>("greet-quickly", codec, NoRetry.INSTANCE, 1, Map.of(),
        Retention.keep(), TaskResource.none(), 1, Approval.NEVER, null,
        Duration.ofMillis(100));
```

The positions carry no names, two of them are plain `int`s next to
each other (version and claim batch size), and every further aspect
lengthens the line for everyone who needs it. CLAUDE.md asks that what
is called from Java gets `@JvmOverloads`, `@JvmStatic` or a facade, and
that Java usability is proven by tests. `WorkerOptions` met the same
problem with a builder (`WorkerOptions.builder()`, `toBuilder()`).

## Decision

`TaskType` gets a builder in the core, next to the constructors.

```kotlin
TaskType.builder("send-invoice", codec)          // JSON payload through a PayloadCodec
TaskType.builder("send-invoice", mapping)        // any PayloadMapping, e.g. ColumnsPayload
    .retryPolicy(NoRetry)
    .version(2)
    .upgrade(1, upgrade)                         // one step; upgrades(map) sets all at once
    .retention(Retention.delete(Duration.ofDays(30)))
    .resource(TaskResource.fixed("mail-server", 4))
    .claimBatchSize(10)
    .approval(rule)
    .preview(payload -> "...")
    .executionTimeout(Duration.ofSeconds(30))
    .build()
```

- **Name and payload are required** and given to `builder`, everything
  else has the defaults of the constructor.
- **One setter per constructor parameter**, plus `upgrade(from, step)`
  for adding one upgrade at a time. A setter given the same aspect
  twice keeps the last value.
- **Validation stays in the constructor.** `build()` calls it, so the
  builder and the constructors refuse the same values with the same
  messages.
- **`toBuilder()`** on a type starts from its values, for a variant
  under another name: `toBuilder().name("send-reminder").build()`.
  `name(...)` exists on the builder for that case.
- **In the core, not in `core-java`.** It is a plain builder without
  coroutines; Kotlin code may use it too, and the Spring starter's
  Java users reach it without the facade.
- **The constructors stay.** They are the Kotlin way and existing code
  uses them. New aspects are added to both.

## Rejected alternatives

- **A Kotlin DSL** (`TaskType.json<P>("name") { retryPolicy = ... }`).
  Named arguments already give Kotlin that; Java cannot use a DSL.
- **The builder in `core-java`.** The type lives in the core and the
  starter's Java users build types without the facade.
- **More `@JvmOverloads` combinations by hand.** One overload per
  combination grows without bound and still has no names.
- **Removing the long constructors.** Breaks every Kotlin caller that
  passes more than name and payload, for no gain to them.

## Consequences

- Public API: `TaskType.builder(name, codec)`, `TaskType.builder(name,
  mapping)`, `TaskType.Builder` and `TaskType.toBuilder()`.
- A new aspect of a task type needs a constructor parameter and a
  builder setter; a test from Java proves the setter.
- `docs/java.md` shows the builder; the Java tests that use the full
  constructor stay as they are, as proof that it still works.

## Decisions taken with the acceptance (2026-11-08)

1. The builder lives in the core, as `TaskType.Builder`, not in
   `core-java`.
2. `builder(name, codec)` and `builder(name, mapping)`; name and payload
   are required, everything else has the constructor's defaults.
3. `upgrade(from, step)` adds one step, `upgrades(map)` replaces all.
4. `toBuilder()` with `name(...)` builds a variant under another name.
5. Validation stays in the constructor, which `build()` calls.
6. The constructors stay; a new aspect is added to both.
