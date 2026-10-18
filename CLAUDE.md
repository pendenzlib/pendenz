# Working rules for this repository

Pendenz is a Kotlin library for the JVM. The principles are in the README,
decisions in `docs/adr/`. Both are binding: whoever wants to deviate changes
the document first.

Internal material such as planning notes and task lists lives in `intern/`.
That folder is excluded via `.gitignore` and is never committed, not even
with `git add -f`. Versioned files never link to anything in it.

## Language

- Everything in the repository is written in English: code, comments, API
  docs, README, ADRs, commit messages and this file.

## Before every change

1. Read the "Principles" section of the README. Every change must fit all
   nine principles, in particular: flexible with good defaults, the schema
   belongs to the user, no framework in the core, leaving costs nothing.
2. Read the ADRs that are affected. A fundamental decision without an ADR is
   not implemented; it is proposed as an ADR first.

## Code

- Explicit API mode: every public signature is a deliberate decision. When in
  doubt, `internal`.
- The core (`core`) depends only on coroutines, kotlinx-serialization, the
  SLF4J API and `javax.sql.DataSource`. No further dependency without an ADR.
- Constructor injection, no DI container. Handlers are registered as
  instances.
- Everything called from Java gets `@JvmStatic`, `@JvmOverloads` or a facade
  in `core-java`. Java usability is proven by tests written in Java, not
  claimed.
- No reflection, no annotation processing in the core.

## Tests

Principle: every change in behaviour comes with a test. Tests exercise
behaviour through the public API, not implementation. No test compares SQL
text, no test checks a getter.

### Naming

- A test name has three parts separated by ` - `: what is tested, the
  situation, the expected outcome.
  `enqueue - run time in the future - stores the task as SCHEDULED`.
- The first part names an operation or behaviour (`enqueue`, `claim`,
  `retry policy`). The second part is the condition. The third part is a
  concrete state, value or exception, not "works" or "is correct".
- Kotlin test names are written in backticks, lower case, no `test` prefix.
- Java test names use the same three parts in camelCase, separated by
  underscores: `transitions_pendingTask_mayBecomeRunning`.
- Names must fit the 120 character line limit; be terse rather than wrap.

### Grouping

- Guiding question: *if I change this behaviour, which tests do I have to
  read?* Tests are grouped by the contract of one operation or behaviour
  area, so that everything that changes together is read together.
- Groups are `@Nested inner class`es in PascalCase, named after an
  operation (`Enqueue`, `Claim`) or a behaviour area (`Retry`, `Scheduling`,
  `OperatorInterventions`). Never after a judgement: no `EdgeCases`, `Misc`,
  `Other`. An edge case is a situation and belongs in the second part of the
  name, right next to the happy path of the same operation.
- Rules that span the whole unit, such as an invariant over all status
  values, stay at the top level of the test class.
- A second nesting level is the exception, used only when one group grows
  too large to read, and then split by situation type (`HappyPath`,
  `Failures`).
- `@BeforeEach` sets up infrastructure only: table, worker, scope. Business
  preconditions belong in the Arrange block of the test that needs them.

### Arrange, Act, Assert

- Every test body has three blocks in this order, separated by blank lines,
  with exactly one Act.
- Blocks with more than one statement are labelled `// Arrange`, `// Act`,
  `// Assert`. Single-statement blocks may omit the label. End-to-end tests
  always carry the labels.
- Act is the trigger whose effect is asserted. Enqueuing and starting the
  worker are Act.
- Waiting with Awaitility is Assert: it states the expected state and gives
  it time to arrive.

### Layout

- Unit tests sit next to the class and are named `<Class>Test`.
- Tests that go through database and worker are named `<Topic>EndToEndTest`.
- Test helpers live under `testing/` in the test source tree, never in main
  code.
- Tests of the Java facade are written in Java under
  `core-java/src/test/java`. They are compile-time proof that the API is
  usable from Java; Kotlin tests cannot provide that.

### Database

- Tests run against a real PostgreSQL: Testcontainers when Docker is
  available, embedded PostgreSQL otherwise (ADR 0004). Never H2.
- Every test gets its own freshly created table via
  `PostgresDatabase.createTable()`. No shared tables, no cleanup between
  tests; tests are therefore independent and can run in parallel.
- Changing rows with SQL inside a test is welcome when that is what the test
  checks, for example a restart or a cancellation by an operator.

### Concurrency and time

- Wait for state, not for time: Awaitility `await atMost ... untilAsserted`.
  A `delay` or `Thread.sleep` to wait for success is a bug.
- A fixed `delay` is allowed only to show that something did *not* happen,
  and then carries a comment saying so.
- Coroutine tests write `runBlocking<Unit> { ... }`. JUnit silently ignores
  test methods that return a value, and `runBlocking` without the type
  argument returns the last expression.
- Time-dependent logic takes a `Clock` or short intervals through the
  options, never sleeps in the test.
- No uncontrolled randomness: jitter 0 in tests, or `Random(seed)`.

### Style

- One test checks one behaviour. Several assertions are fine when they show
  the same behaviour from different sides.
- AssertJ for all assertions. Inside loops use `describedAs`, so the failure
  message names the case that broke.
- Fakes over mocks. MockK only for third-party interfaces without a fake,
  never for our own classes.
- Test data has meaningful values (`Greeting("Ada")`), no `foo`, no `test1`.
- Parameterised tests for rules that hold for many values, for example
  across all status values.

### When a test flakes

- Do not rerun, do not comment out. Find the cause: almost always a wait on
  time where a wait on state belongs, or shared state.
- The upper bound for `atMost` is 10 seconds. Needing more points to a
  design problem in the test or in the code.

## Quality

- `./gradlew build` must be green: ktlint, detekt, tests, `apiCheck`.
- Changes to `*/api/*.api` are intentional and explained in the commit.
- Warnings are errors (`allWarningsAsErrors`). No suppression without a
  justification in the code.

## Commits

- Conventional Commits: `feat:`, `fix:`, `docs:`, `refactor:`, `test:`,
  `build:`, `chore:`. The scope is the module, e.g. `feat(core): ...`.
- Small, traceable commits. One commit, one intention.
- The Co-Authored-By line for AI involvement stays.
- AI tools commit locally and only on request. They never push, never add
  remotes and never open pull requests. Publishing is the human's decision
  alone.

## What does not belong in the core

- No AI features, no model client.
- No dashboard, no server process, no workflow engine.
- No Spring, no Ktor, no other framework. Adapters are separate modules.
