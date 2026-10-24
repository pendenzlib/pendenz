# Kotlin library versions: compile against what the frameworks pin

- Status: proposed
- Date: 2026-10-24

## Context

The core depends on kotlinx-coroutines and kotlinx-serialization (ADR
0003) and is built against the newest releases, 1.11.0 of both. Maven
and Gradle resolve a library's dependencies to the version the
application asks for, and frameworks ask firmly:

| Framework line | kotlinx-coroutines | kotlinx-serialization | Kotlin |
|---|---|---|---|
| Spring Boot 4.0.9 | 1.10.2 | 1.9.0 | 2.2.21 |
| Spring Boot 4.1.2 | 1.10.2 | 1.11.0 | 2.3.21 |

Spring Boot's dependency management, which Spring Initializr sets up for
every Gradle and Maven project, forces these versions over the ones a
library declares. `examples/spring` showed the result: built against
1.11, Pendenz fails on Boot 4.0 and 4.1 with `NoSuchMethodError` at the
first `enqueue` through the Java facade (`runBlocking` compiled against
1.11 calls a method 1.10 lacks) and when the worker stops
(`Job.cancel`). The README now tells Boot users to raise both versions;
every user who does not read that paragraph meets a crash in
production code paths, not at startup.

Two observations make a fix cheap:

- Nothing in the code needs 1.11. Compiled against coroutines 1.10.2 and
  serialization 1.9.0, every module compiles and every test passes,
  including the Spring 7 run and `examples/spring` on exactly Boot's
  versions without any override (tried on 2026-10-24, then reverted).
- Binary compatibility runs one way: code compiled against an older
  release runs on newer ones, both libraries promise that; the reverse
  is what broke.

The same holds for Kotlin itself, one level up. A Kotlin compiler reads
the metadata of libraries built by its own version and the next one,
not further. Pendenz is built with Kotlin 2.4; a Kotlin application on
Boot 4.0's Kotlin 2.2 can call it from Java-compiled code, but its own
Kotlin compiler refuses the classes. Java users are not affected. This
has not been tried yet; it is the documented forward-compatibility
rule of Kotlin.

## Decision

Libraries are compiled against the oldest versions the supported
frameworks pin, not the newest.

- **kotlinx-coroutines and kotlinx-serialization:** the version catalog
  holds the lowest version pinned by a supported Spring Boot line,
  today coroutines 1.10.2 and serialization 1.9.0. Applications without
  dependency management resolve newer ones, which is safe.
- **Kotlin:** library modules keep the Kotlin 2.4 compiler but set
  `apiVersion` and `languageVersion` to the oldest Kotlin a supported
  framework line ships, today 2.2. The metadata then is readable by a
  2.2 compiler, and the standard library is used only as far as 2.2
  has it.
- **The newest versions stay tested.** As `pendenz-spring` does for
  Spring 7 (ADR 0023), the core and the Java facade run their tests a
  second time with the newest coroutines and serialization in the
  runtime classpath.
- **Raising the floor** is a decision taken when the oldest supported
  framework line moves, recorded in the catalog's comment; Renovate is
  told not to raise these two entries on its own.
- The README paragraph telling Boot users to raise the versions is
  removed; `examples/spring` drops its override, so it proves the
  default.

## Rejected alternatives

- **Keep 1.11 and document the override.** What the README does today.
  Every Boot user is one forgotten paragraph from a `NoSuchMethodError`
  under load, and the starter, whose purpose is to remove glue, would
  need glue.
- **A version check in the starter at startup**, failing with a pointer
  to the override. Better than a crash under load, still a step every
  Boot user takes, and it guards only the starter, not
  `pendenz-spring` or plain use under Boot's dependency management.
  With the floor lowered it has nothing left to catch; it can come back
  if a future floor cannot follow a framework.
- **Shading coroutines and serialization into the core.** Hides the
  conflict, doubles the classes in every application, and breaks the
  coroutine types (`Job`, `CoroutineScope`) that are part of the public
  API.
- **Declaring the versions as strict in the published metadata.** Gradle
  would then fail resolution in every Boot application instead of
  running it; Maven ignores it.

## Consequences

- One catalog change, two compiler options in the convention plugin, a
  second test task for the core and the facade.
- New coroutine or serialization API is off limits until the floor
  rises; the compiler enforces it, since the catalog holds the floor.
- Kotlin features after 2.2 are off limits in library code; examples
  and tests may use 2.4.
- ADR 0003's "Kotlin 2.4" remains the compiler; the language level is
  this ADR's.

## Open questions

1. **Floor.** Proposal: coroutines 1.10.2, serialization 1.9.0, Kotlin
   language and API version 2.2, from Spring Boot 4.0.
2. **Kotlin level.** Proposal: apply it to every published module, and
   prove it with a test project compiled by Kotlin 2.2 against the
   built jars, since this part is not yet tried.
3. **Second test run.** Proposal: for `core` and `core-java` in every
   build, as with Spring 7; other modules rely on the core's run.
4. **Starter check.** Proposal: none, see rejected alternatives.
