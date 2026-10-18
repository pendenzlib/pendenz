# Tech-Stack und JVM-Baseline

- Status: akzeptiert
- Datum: 2026-10-18

## Kontext

Pendenz wird als Bibliothek in fremde Anwendungen eingebunden. Jede
Abhängigkeit des Kerns und die gewählte JVM-Baseline bestimmen, wer die
Bibliothek überhaupt nutzen kann.

## Entscheidung

- **Kotlin 2.4** mit K2 und Explicit API Mode in Bibliotheksmodulen.
- **Bytecode für Java 17.** CI-Matrix 17, 21, 25. Spring Boot 4, Spring
  Framework 7 und JUnit 6 haben dieselbe Baseline. Anheben ist später eine
  Hauptversionsänderung, Senken praktisch unmöglich.
- **Gradle 9** mit Kotlin DSL, Version Catalog und Convention-Plugins in
  `build-logic`. Toolchain 17 wird bei Bedarf automatisch bereitgestellt.
- **Kern-Abhängigkeiten:** kotlinx-coroutines, kotlinx-serialization-json,
  SLF4J-API, `javax.sql.DataSource`. Kein Pool, kein Treiber, kein Framework,
  kein DI-Container.
- **Java-Fassade** als eigenes Modul `core-java` mit blockierenden Methoden
  und `CompletableFuture`. Tests der Fassade werden in Java geschrieben.
- **Tests:** JUnit 6, AssertJ, MockK sparsam, Testcontainers mit PostgreSQL,
  Awaitility.
- **Qualität:** ktlint, detekt, Kover, Binary Compatibility Validator ab dem
  ersten Commit, Dokka.
- **Veröffentlichung:** Maven Central unter `io.github.d4niella.pendenz`,
  Apache 2.0, Semantic Versioning, Conventional Commits, Renovate.

## Verworfene Alternativen

- **Baseline 21 oder 25:** schließt Teams auf 17 aus, bringt einer Queue
  nichts. Virtuelle Threads können Nutzer über einen eigenen Executor
  einbringen.
- **Spring im Kern:** Versionskonflikte und Ausschluss anderer Stacks.
  Transaktionsteilnahme läuft über eine Connection-Abstraktion, ein
  Spring-Boot-Starter kommt als Modul.
- **Kotest Assertions:** gut aus Kotlin, aber unbrauchbar für die
  Java-Tests der Fassade. Eine Zusicherungsbibliothek im ganzen Repo.
- **ORM oder jOOQ:** stehen nutzerdefinierten Schemata im Weg.

## Konsequenzen

- Wer unter Java 17 ist, kann Pendenz nicht nutzen.
- Jede neue Kern-Abhängigkeit braucht ein eigenes ADR.
