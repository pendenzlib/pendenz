---
name: adr-review
description: Review a proposed ADR of this repository before it is accepted. Checks the draft against the code, sorts its numbers into measured, read and derived, asks a fixed set of failure questions, and weighs every fix for the problems it brings. Use when asked to check, review or find problems in an ADR under docs/adr/, or whether a draft is ready for a decision.
---

# Reviewing an ADR

For an ADR with the status `proposed`. The review ends with findings and
proposed fixes. It does not accept the ADR and does not implement it;
both are the maintainer's decisions (CLAUDE.md, "Before every change").

A review changes nothing by itself. Findings are reported first; the ADR
is edited only after the maintainer has said which fixes to take.

## 1. Read

1. The ADR as a whole, including rejected alternatives, consequences,
   tests and open decisions.
2. The "Principles" section of the README, and every ADR the draft
   names or changes.
3. The code the draft describes: the classes and statements it names,
   and the callers of what it changes. A draft is checked against the
   code, not against its own text.

## 2. Sort every statement of fact

Each number and each claim about behaviour is one of three kinds:

- **measured**: the ADR says when, against what, and how;
- **read from the code**: the place can be named;
- **derived**: computed or concluded from one of the two.

A derived number that the decision rests on is measured (section 5) or
marked as derived in the ADR. A claim about the code that the code does
not bear out is a finding.

## 3. Ask the fixed questions

For every mechanism the draft adds or changes:

- **A failure that is not the row's.** What happens when the database
  refuses every write (read-only after a failover, a missing
  privilege, a full disk), and when it does not answer at all? Is the
  cost bounded: transactions, log entries, memory?
- **A failure in the middle.** What happens when the mechanism itself
  fails halfway, with and without an answer of the database? Does
  anything end without a trace?
- **Several workers.** What do two workers do that meet the same row
  at the same time: wait, skip, deadlock, each repeat the work?
- **The other entry points.** `runPending`, which builds a worker per
  call and keeps no state between calls; the in-memory store; a store
  or `ConnectionProvider` of an application, which may wrap exceptions
  and join a transaction it does not own.
- **Where the database checks.** At the statement or at the commit?
  With `SKIP LOCKED` or waiting? Under a statement timeout?
- **The neighbours.** Other ADRs that act in the same transaction or
  on the same rows: does the draft say what happens when both apply
  at once?
- **The normal case.** What does the path without a fault cost that it
  does not cost today: statements, round trips, locks, rows read?
- **Limits.** Every "at most", "up to" and "once per": what happens
  one past the limit, and is that written down?
- **The log.** How often is an entry written when the fault lasts, and
  at which level? Does a state that is normal for an application's
  schema get logged as an error?
- **Public API.** What breaks for a store, a dialect or a caller of an
  application, and does the draft name it?

## 4. Weigh every fix

For each finding, propose a fix and ask two questions before reporting
it:

- Which new problems does the fix bring?
- Which other fix of this review does it disturb?

A fix that brings more than it removes is reported in its smaller form,
or the finding is reported as a price the draft accepts, with the
reason. A fix that would change behaviour an accepted ADR decided is
named as such.

## 5. Measure

Measurements run against a real PostgreSQL through the test helper
(`PostgresDatabase`, ADR 0004), in a throwaway test that is deleted
afterwards and never committed.

- The statements are written as the draft describes them, on a table
  with the generated indexes and analysed.
- One connection for a sequence of transactions, unless the cost of
  opening connections is what is measured.
- Medians of several runs. Single runs far from the median are named,
  also when their cause is not found.
- A plan is read with `EXPLAIN (ANALYZE, BUFFERS)` where the claim is
  about the plan.
- The ADR gets a section that says when and against what was measured,
  the numbers, and what was not measured.

## 6. Reconcile the document

After the findings, and again after every edit:

- every open decision names the proposal the text follows, and every
  choice in the text that someone could make differently is an open
  decision;
- every consequence follows from the decision text, and every bound
  and limit of the decision appears among the consequences;
- the tests cover each rule of the decision, each bound from both
  sides, and each case the consequences describe; their wording names
  a state that can be observed, not a log entry;
- rejected alternatives carry a reason that can be checked;
- numbers agree wherever they are repeated.

Prose follows the language rules of CLAUDE.md: English, factual, no
judgement of other projects.

## 7. Report

Findings in the order of their weight:

1. what must change before the ADR can be accepted;
2. decisions the draft has not put to the maintainer;
3. gaps in the text;
4. what stays as a price, and why.

Each finding says what is wrong, where it shows in the code or the
draft, and the proposed fix. The report says of each statement whether
it was measured, read or reasoned, and it corrects earlier statements
of the same review that a measurement has overturned.

## 8. When to stop

A round that finds only corrections of wording means the draft is ready
for a decision. What remains open then is named once: what can only be
learned from the implementation, and what was not measured. Further
rounds on paper are not proposed.
