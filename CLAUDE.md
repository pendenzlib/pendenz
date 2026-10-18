# Arbeitsregeln für dieses Repository

Pendenz ist eine Kotlin-Bibliothek für die JVM. Die Prinzipien stehen in der
README, Entscheidungen in `docs/adr/`. Beide sind verbindlich; wer davon
abweichen will, ändert zuerst das Dokument.

## Sprache

- Code, Kommentare, API-Doku, README und Commit-Nachrichten auf Englisch.
- Planung, ADRs und diese Datei auf Deutsch.

## Vor jeder Änderung

1. README-Abschnitt "Principles" lesen. Jede Änderung muss zu allen neun
   Prinzipien passen, besonders: Flexibilität mit Defaults, Schema gehört
   dem Nutzer, kein Framework im Kern, Ausstieg kostet nichts.
2. Betroffene ADRs lesen. Eine Grundsatzentscheidung ohne ADR wird nicht
   umgesetzt, sondern erst als ADR vorgeschlagen.

## Code

- Explicit API Mode: Jede öffentliche Signatur ist eine bewusste Entscheidung.
  Im Zweifel `internal`.
- Der Kern (`core`) hängt nur von Coroutines, SLF4J-API und
  `javax.sql.DataSource` ab. Keine weitere Abhängigkeit ohne ADR.
- Konstruktorinjektion, kein DI-Container. Handler werden als Instanzen
  registriert.
- Alles, was aus Java aufgerufen wird, bekommt `@JvmStatic`, `@JvmOverloads`
  oder eine Fassade in `core-java`. Java-Tauglichkeit wird mit Tests in Java
  bewiesen, nicht behauptet.
- Keine Reflection, keine Annotationsverarbeitung im Kern.

## Tests

- Jede Verhaltensänderung kommt mit Test. Tests gegen echtes PostgreSQL per
  Testcontainers, nie H2.
- Fakes vor Mocks. Der Speicher hat eine In-Memory-Implementierung, die in
  Tests bevorzugt wird.
- Testnamen in Backticks beschreiben das Verhalten, nicht die Methode.

## Qualität

- `./gradlew build` muss grün sein: ktlint, detekt, Tests, `apiCheck`.
- Änderungen an `*/api/*.api` sind gewollt und werden im Commit erklärt.
- Warnungen sind Fehler (`allWarningsAsErrors`). Keine Unterdrückung ohne
  Begründung im Code.

## Commits

- Conventional Commits: `feat:`, `fix:`, `docs:`, `refactor:`, `test:`,
  `build:`, `chore:`. Scope ist das Modul, etwa `feat(core): ...`.
- Kleine, nachvollziehbare Commits. Ein Commit, eine Absicht.
- Co-Authored-By-Zeile für KI-Beteiligung bleibt stehen.
- KI-Werkzeuge committen nur lokal und nur auf Wunsch. Sie pushen nie, legen
  keine Remotes an und erstellen keine Pull Requests. Veröffentlichen ist
  allein Sache des Menschen.

## Was nicht in den Kern gehört

- Keine KI-Funktionen, kein Modell-Client.
- Kein Dashboard, kein Serverprozess, keine Workflow-Engine.
- Kein Spring, kein Ktor, kein anderes Framework. Adapter sind eigene Module.
