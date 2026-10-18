# Tech stack and JVM baseline

- Status: accepted
- Date: 2026-10-18

## Context

Pendenz is embedded as a library into other people's applications. Every
dependency of the core and the chosen JVM baseline decide who can use the
library at all.

## Decision

- **Kotlin 2.4** with K2 and explicit API mode in library modules.
- **Bytecode for Java 17.** CI matrix 17, 21, 25. Spring Boot 4, Spring
  Framework 7 and JUnit 6 share that baseline. Raising it later is a major
  version change; lowering it is practically impossible.
- **Gradle 9** with Kotlin DSL, version catalog and convention plugins in
  `build-logic`. A JDK 17 toolchain is provisioned automatically when needed.
- **Core dependencies:** kotlinx-coroutines, kotlinx-serialization-json, the
  SLF4J API, `javax.sql.DataSource`. No pool, no driver, no framework, no DI
  container.
- **Java facade** as a separate module `core-java` with blocking methods and
  `CompletableFuture`. Its tests are written in Java.
- **Tests:** JUnit 6, AssertJ, MockK sparingly, Testcontainers with
  PostgreSQL, Awaitility.
- **Quality:** ktlint, detekt, Kover, Binary Compatibility Validator from the
  first commit, Dokka.
- **Publishing:** Maven Central under `io.github.d4niella.pendenz`, Apache
  2.0, semantic versioning, Conventional Commits, Renovate.

## Rejected alternatives

- **Baseline 21 or 25:** excludes teams on 17 and gives a queue nothing it
  needs. Users can bring virtual threads through their own executor.
- **Spring in the core:** version conflicts and exclusion of other stacks.
  Transaction participation goes through a connection abstraction; a Spring
  Boot starter comes as a module.
- **Kotest assertions:** fine from Kotlin, unusable for the Java tests of
  the facade. One assertion library for the whole repository.
- **ORM or jOOQ:** get in the way of user-defined schemas.

## Consequences

- Anyone below Java 17 cannot use Pendenz.
- Every new core dependency needs an ADR of its own.
