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
- **Publishing:** Maven Central under `io.github.pendenzlib`, Apache
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

## Noted later (2026-10-24)

- The CI matrix is meant for the library: its tests run on 17, 21 and
  25. Gradle, the compiler and detekt run on 17 in every entry, and the
  matrix JDK is the tests' launcher, set with `-Ppendenz.testJdk`. Until
  then the matrix only changed the JDK Gradle ran on, while the tests ran
  on the JDK 17 toolchain, and detekt 1.23 fails on JDK 25.

## Noted later (2026-10-29)

- The namespace moved from `io.github.d4niella.pendenz`, tied to the
  author's personal GitHub account, to `io.github.pendenzlib`, the GitHub
  organisation that now holds the repository. Group ID and root package are
  the same string. The project's coordinates no longer depend on a personal
  account, and the repository can gain maintainers without moving again.
  Nothing had been published, so the move broke no user.
- The organisation's name has no hyphen on purpose: Maven allows one in a
  group ID, a Java package does not, and the two would drift apart.
