# SQL dialect behind the JDBC store

- Status: accepted
- Date: 2026-10-19

## Context

`JdbcTaskStore` was written for PostgreSQL and says so in its SQL:
`FOR UPDATE SKIP LOCKED`, `?::jsonb`, `= ANY (?)`, `INSERT ... RETURNING`,
`UPDATE ... FROM`. The plan names PostgreSQL first and other databases
later, behind a dialect, so that the store need not change when the second
database arrives. Nothing else about the store is PostgreSQL-specific: it
binds parameters and reads rows.

## Decision

`SqlDialect` produces the SQL; `JdbcTaskStore` binds and reads.

- `SqlDialect.statements(table, payloadColumns, limitTable)` returns a
  `TaskSql`: one statement per store operation, with `?` placeholders in
  an order the `TaskSql` documentation fixes. The store binds in exactly
  that order. The dialect grew two more families with later ADRs,
  `cycleStatements` (ADR 0016) and `resourceLimitStatements`
  (ADR 0017), and the payload columns of ADR 0013 and the limit table
  of ADR 0017 became inputs of `statements` (text updated on review,
  2026-10-19).
- `PostgresDialect` is the one implementation and the store's default.
  `PostgresDdl` produces the DDL for migrations on its own; the dialect
  covers only what the store runs.

The dialect is an interface rather than a sealed class so that a second
database can be added outside the core while it is being proven.

## Rejected alternatives

- **No abstraction until a second database exists.** Cheaper today, but
  the SQL text would keep growing inside the store with every feature,
  and the split would become a rewrite.
- **A dialect of fragments** (`skipLocked`, `jsonCast`, `arrayMatch`)
  spliced into shared SQL. Works for PostgreSQL and the databases that
  look like it, and breaks on the first one whose claim statement has a
  different shape.
- **jOOQ or another SQL builder.** A dependency in the core for a handful
  of statements, and a second schema model next to the mapping.

## Consequences

- One interface and one object more; `JdbcTaskStore` shrinks to binding.
- The binding order is a contract between the store and every dialect.
  A dialect that cannot take an array for the type filter needs a change
  to the store's binding, which is accepted: the abstraction is proven by
  one implementation and will be adjusted by the second.
- The in-memory store is unaffected.

Amended on review (2026-10-25): the dialect had a `createTable(table)`
that nothing called. It knew no payload columns, so the DDL it returned
was wrong for every task type with columns of its own, and a second
dialect would have had to implement it for nothing. It is removed. DDL
belongs to the migrations the application owns (principle 2), and
`PostgresDdl` serves them directly.
