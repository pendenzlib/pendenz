---
name: adr-implement
description: Implement an ADR of this repository that the maintainer has accepted or told to accept. Covers the acceptance commit, the order of the modules, tests with a counter-check, the API files, changelog and documentation, and the record of what was decided without asking. Use when asked to accept an ADR and implement it, or to implement an accepted ADR under docs/adr/.
---

# Implementing an ADR

For an ADR the maintainer has accepted, or has said to accept ("accept
ADR nnnn with your proposals and implement it"). Without that word an
ADR stays `proposed` and nothing is implemented (CLAUDE.md, "Before
every change").

Work happens on the ADR's topic branch, in small commits, each with a
green build. Nothing is pushed.

## 1. Before the first line of code

1. Read the ADR as a whole, the "Principles" section of the README and
   every ADR it names or changes.
2. Read the code it changes and the callers of that code.
3. Collect what the ADR leaves open: choices it does not make, wording
   that the code contradicts, anything that needs a public signature
   it does not name. Questions that change the scope or the shape of
   the public API are asked now, in one batch. Everything else is
   decided while implementing and recorded (section 5).

## 2. Accept

One commit that only changes documents:

- the status in the ADR becomes `accepted`, and the index in
  `docs/adr/README.md` follows;
- the section "Open decisions" becomes "Decisions taken with the
  acceptance (date)", each with what was chosen. When the maintainer
  took the proposals, the text says so;
- an ADR this one amends gets its note.

## 3. Implement, in this order

Each step is one commit, or several when it carries more than one
intention, and `./gradlew build` is green after each.

1. **Core.** The behaviour and its tests. Public signatures only where
   the ADR needs them; when in doubt, `internal`.
2. **Java facade** (`core-java`), with a test written in Java when
   anything new is called from Java.
3. **Adapters and modules** the change reaches: `micrometer`, `spring`,
   `spring-boot` (properties with their configuration metadata),
   `mcp`, `postgres-notify`, the examples.
4. **Documentation.**

A step that does not apply is left out, not committed empty.

## 4. Tests

The conventions are in CLAUDE.md under "Tests". In addition:

- Every rule of the ADR's decision has a test, and every test the ADR
  lists exists or the ADR says why not.
- A bound is tested from both sides.
- **Counter-check.** Before the commit, the new tests are run once
  against the old behaviour: with the new mechanism switched off or
  the change stashed. A new test that still passes does not test the
  change; it is fixed or dropped. The report says how many failed.
- A test that reads a listener event or a metric waits for it; the
  event can arrive after the status the test waited for.
- Behaviour that depends on what the database answers is tested on
  PostgreSQL. A store of the test that throws stands in only for
  failures PostgreSQL cannot be made to produce on demand.

## 5. Record what the ADR did not say

The ADR gets a section "Noted during implementation" with

- every deviation from its text, and the reason;
- every decision taken without asking: names, defaults, limits, where
  a class was split and why;
- what was found on the way and not changed.

The same list goes into the report to the maintainer, so the review has
a checklist.

## 6. Public API

- `./gradlew apiDump` and `./gradlew build` run as separate
  invocations.
- Every change in `*/api/*.api` is intended and named in the commit
  message.
- A change that breaks a store, a dialect or a caller of an application
  is named in `CHANGELOG.md` under "Changed"; additions go under
  "Added", each with its ADR number.

## 7. Documentation

Checked for every ADR, changed where the behaviour shows:

- `docs/guide.md`: options, their table, what an operator sees;
- `docs/concepts.md`: the model, the replaceable parts, the stores;
- `docs/java.md`, `docs/spring.md`, `docs/metrics.md`,
  `docs/agents.md`, `docs/sql-recipes.md`;
- the index `docs/README.md`, and the README where it lists features or
  the roadmap;
- other ADRs that describe what has changed: a dated note, not a
  rewrite. A table of meters or options in an older ADR that the
  change extends gets the new row.

## 8. Report

At the end, to the maintainer:

- the commits, in order, each with what it does;
- the state of the build, with the output when it is not green;
- the result of the counter-check;
- the list from section 5;
- what was not done or not verified, said plainly.

The review of the implementation is a separate step
(`implementation-review`), asked for by the maintainer.
