# Record architecture decisions

- Status: accepted
- Date: 2026-10-18

## Context

Pendenz deliberately makes some unusual choices: no schema of its own, no
framework in the core, no AI features despite AI being a use case. Without a
written rationale such choices are later taken for accidents and reverted.

## Decision

Every fundamental decision is recorded as an ADR in MADR format under
`docs/adr` before it is implemented.

## Consequences

- More writing before code.
- Decisions are traceable for reviewers, contributors and AI tools, and need
  not be guessed from the code.
