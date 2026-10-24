# Development

The working rules, including the test conventions, are in
[`CLAUDE.md`](../CLAUDE.md); they apply to humans and AI tools alike.

## Build

Run Gradle on a JDK 17 or 21. detekt 1.23 does not run on JDK 25: the
Kotlin compiler inside it cannot parse that version, whatever the vendor.
The build provisions a JDK 17 toolchain for compilation automatically, so
the provisioned Temurin under `~/.gradle/jdks` will do.

```bash
./gradlew build
```

This runs ktlint, detekt, the tests on a real PostgreSQL (Testcontainers
when Docker is available, an embedded PostgreSQL otherwise), the API
compatibility check and the coverage check, which fails below 85 % line
coverage. `./gradlew dokkaGenerate` writes the API reference to
`*/build/dokka/html`; CI uploads it as an artifact. Public API changes
show up as a diff in `*/api/*.api`; regenerate with `./gradlew apiDump` in
a run of its own, since Gradle 9 refuses `apiDump` and `apiCheck` in the
same invocation.

## Tests

The tests run on the JDK 17 toolchain. `-Ppendenz.testJdk=21` or `=25`
runs them on another JDK, which Gradle finds among the installed ones or
downloads; CI does that for each entry of its matrix, so the library is
tested on 17, 21 and 25 while Gradle stays on 17.

Tests create one fresh table per test and wait on states, never on time.
The end-to-end tests run workers against the real database, two of them
in a second JVM that the test kills mid-task, to show the heartbeat
protecting a living worker and the lock timeout relieving a dead one.

## Library versions

The libraries are compiled against the oldest kotlinx-coroutines,
kotlinx-serialization and Kotlin language level that a supported Spring
Boot line pins, so they run under its dependency management unchanged
([ADR 0024](adr/0024-kotlin-library-versions.md)). The build proves both
ends: `core` and `core-java` run their tests a second time with the
newest kotlinx releases, and `compatibility/` compiles a small consumer
with that oldest Kotlin compiler against the built jars. `pendenz-spring`
runs its tests on Spring Framework 6.2 and 7.

## Decisions

Architecture decisions are recorded in [`adr/`](adr). A fundamental
change is proposed as an ADR before it is implemented.
