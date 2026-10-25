# The read model in the root package

- Status: accepted
- Date: 2026-10-25

## Context

The package `io.github.d4niella.pendenz.store` holds the store SPI: the
`TaskStore` and `CycleStore` interfaces a store implements, the SQL
dialect, and the types they exchange with the worker, such as
`NewTask`, `ClaimedTask` and `ClaimTerms`. Two types in it were not part
of that seam but of the API every user calls: `TaskRecord`, which
`find` and `tasks` return, and `CycleRecord`, which `findCycle` returns.
A user who only enqueues and reads tasks had to import from the SPI
package, and the API dump listed the read model among the store's
types. The review of 2026-10-25 found it; nothing is published, so the
move costs no migration.

## Decision

`TaskRecord` and `CycleRecord` move to the root package
`io.github.d4niella.pendenz`, beside `TaskStatus`, `Backlog` and the other
types of the API. The store SPI keeps returning them; `store` stays the
package for those who implement a store or a dialect.

## Rejected alternatives

- **A package of its own for the read model**, such as `model`. One more
  import for every user, for two classes.
- **Leaving them where they are.** The package name would tell users
  they work with the SPI when they only read their tasks.

## Consequences

- Imports change for users of `find`, `tasks` and `findCycle`, in Kotlin
  and Java (API change before 1.0).
- The approval in the review's list of medium findings is this ADR's
  acceptance.
