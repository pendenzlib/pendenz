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

- `SqlDialect.createTable(table)` returns the DDL for a mapping.
- `SqlDialect.statements(table)` returns a `TaskSql`: one statement per
  store operation, with `?` placeholders in an order the `TaskSql`
  documentation fixes. The store binds in exactly that order.
- `PostgresDialect` is the one implementation and the store's default.
  `PostgresDdl.createTable` stays as the documented entry point for
  migrations and delegates to it.

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
