---
name: implementation-review
description: Review the implementation of an ADR or another finished change of this repository against its decision and against ten fixed criteria - completeness, correctness, concurrency, failure paths, performance, resources, security, maintainability, API and Java use, tests. Use when asked to review, check or look through an implementation, a branch or a set of commits. Complements the general code review for bugs; it adds what only this project's ADRs and conventions say.
---

# Reviewing an implementation

For a change that is implemented and committed: usually the commits of
one ADR on its topic branch. The review reads the diff, the code
around it and the ADR, reports findings, and fixes only what the
maintainer asks for.

A general review for bugs in the diff is a different tool. This review
asks what that one cannot know: whether the code does what the ADR
decided, and whether it holds where this library has failed before.

## 1. Read

1. The ADR, including "Noted during implementation".
2. The whole diff of the change, `git diff <base>...<branch>`, and
   each commit by itself: a commit in the middle must compile and
   pass the API check alone.
3. The code the diff calls and the code that calls the diff. A finding
   names a line that was read, not one that was assumed.

## 2. The criteria

Each criterion is answered, also when the answer is "nothing found".

**Completeness**
- Every rule in the ADR's decision has code and a test. Every test the
  ADR lists exists.
- Documentation, the ADR index, the changelog and the notes in older
  ADRs follow the change (the list in `adr-implement`, section 7).
- Every module the change reaches has it: Java facade, Micrometer,
  Spring properties with their metadata.

**Correctness**
- The behaviour matches the ADR's text sentence by sentence; a
  difference is a bug or a missing note under "Noted during
  implementation".
- Bounds: the value at the bound, one below, one above.
- Time comes from the injected `Clock`; where wall time is used, the
  code says why.

**Concurrency**
- Two workers on the same row at the same moment: who waits, who
  skips, can they deadlock?
- State a worker keeps between polls: who reads and writes it, from
  which coroutine?
- Cancellation: a transaction that has started runs to its end, and a
  worker that is stopping does not start the next one.
- A version check protects only against writes that raise the version;
  the heartbeat does not.

**Failure paths**
- For every call into the store: what happens when it throws with an
  answer of the database, without one, and wrapped by a store or
  `ConnectionProvider` of an application? Causes are searched through
  the chain, not only at the top.
- No path ends without a trace: an exception is thrown, logged, or
  reported to the listener.
- `runPending` returns or throws; it does not hide a failure behind a
  normal return.
- A `CancellationException` of the worker's own scope is rethrown;
  someone else's is a failure.

**Performance**
- Statements and round trips per poll on the path without a fault,
  compared with before.
- Rows read and locked per statement: a read that needs one row asks
  for one.
- A new or changed statement has a plan test, or uses an index a plan
  test already covers (ADR 0028).

**Resources**
- Connections held at once, and for how long; row locks held over a
  wait.
- Memory that grows with the data: every collection kept between polls
  has a bound or a time after which entries leave.
- Log volume while a fault lasts: entries per poll interval and per
  row, and at which level. Stack traces not more often than the fault
  is news.

**Security**
- Identifiers that reach SQL text go through `SqlIdentifiers`; values
  are bound.
- What an exception, a log entry or a listener event quotes from a
  row: the database's messages can carry column values, and the KDoc
  says so where they are passed on.
- Operations an actor can trigger are checked by `Permissions`; a new
  operation has an action of its own or names the one it uses.
- What an agent sees through `pendenz-mcp` does not tell it of tasks
  that are not its own.

**Maintainability**
- A class or function split for a detekt limit is split along a
  responsibility, and its name says which.
- No suppression without a reason in the code.
- Helpers of tests that exist twice or more in different modules: say
  so; moving them to the test fixtures is the maintainer's choice.
- KDoc states the contract, comments state the reason; neither
  repeats the code.

**API and Java use**
- Every change in `*/api/*.api` is intended and named in a commit
  message. Nothing public that could be `internal`.
- A new default parameter on a published function breaks its
  synthetic `$default` signature; an overload does not.
- What is called from Java has `@JvmStatic`, `@JvmOverloads` or a
  facade, and a test written in Java.

**Tests**
- The conventions of CLAUDE.md: names in three parts, groups by
  operation, Arrange/Act/Assert with one Act, a fresh table per test.
- Waiting for state with Awaitility, never a sleep for success. A test
  that reads a listener event or a meter after a status waits for the
  event too.
- A fixed delay only to show that something did not happen, with a
  comment.
- Were the new tests run against the old behaviour? If the
  implementation did not report a counter-check, run it for the tests
  that carry the change.

## 3. Verify before reporting

A finding is confirmed before it is reported: by a line in the code,
by a test written for it, or by running the case. What could not be
confirmed is reported as a suspicion and marked as such. A finding that
turns out to be wrong during the review is dropped, not softened.

## 4. Report

Findings in the order of their weight:

1. bugs: behaviour that differs from the ADR or loses, repeats or
   hides work;
2. gaps: a rule without a test, a document that was not followed up;
3. costs: statements, locks, memory or log volume that can be
   lowered;
4. what was checked and left as it is, with the reason.

Each finding names the file and line, what happens, and the proposed
fix. The report ends with the criteria that produced nothing.

## 5. Fixes

Fixes follow the maintainer's choice. Code and its tests in one commit
when their changes share files, documents in another. The ADR gets a
section "Noted in the review of the implementation (date)" with what
was found, what was fixed and what was left. `./gradlew build` is green
before each commit.
