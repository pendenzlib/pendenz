# Schema check of the optional tables

- Status: accepted
- Date: 2026-11-07

## Context

The Spring Boot starter checks at startup that the task table exists
with every column its mapping names (ADR 0023, decision 6). It reads
the table from the `TaskTable` bean and fails with the table and the
missing columns, instead of failing at the first poll.

The core has three optional tables beside the task table, each turned
on with a call on the builder:

| Table | Builder call | ADR |
|---|---|---|
| `pendenz_cycle` | `enableCycles()` | 0016 |
| `pendenz_resource_limit` | `enableResourceLimits()` | 0017 |
| `pendenz_resource_block` | `enableResourceBlocks()` | 0035 |

In the starter, an application turns them on in a `PendenzCustomizer`
bean, since no property or bean does it. The schema check does not know
about any of them:

- A missing cycle table fails the first `startCycle`.
- A missing limit table or block table fails every poll, since the
  worker reads the limits and the active blocks in the poll's
  transaction. The worker logs "could not poll" at every poll interval
  and claims nothing, for every task type.

The starter cannot see which tables a customizer turned on. The builder
keeps them in private fields, and the built `Pendenz` hands them to the
`JdbcTaskStore` without exposing them. A property such as
`pendenz.resource-blocks.enabled` would let the starter know about the
tables it turned on itself, but not about those a customizer turned on.

## Decision

### The tables of a queue, readable

`Pendenz` gains a read-only property `tables` of a new class
`QueueTables<ID>` in the `schema` package:

```kotlin
public class QueueTables<ID : Any>(
    public val task: TaskTable<ID>,
    public val cycles: CycleTable<ID>?,
    public val resourceLimits: ResourceLimitTable?,
    public val resourceBlocks: ResourceBlockTable?,
)
```

- The builder fills it with the tables it hands to the `JdbcTaskStore`.
  An optional table that is not turned on is `null`.
- A queue on `inMemory()` or on a `store(...)` of the application has no
  tables to check; `Pendenz.tables` is then `null`.
- `ResourceLimitColumns` and `ResourceBlockColumns` gain `all`, as
  `TaskColumns` and `CycleColumns` have it, so that a check lists every
  mapped column without knowing the classes.

### The starter checks every table of the queue

`SchemaCheck` reads the tables from the `Pendenz` bean instead of the
`TaskTable` bean and applies the existing check to each one:

- A missing table fails startup with its name and the DDL call that
  creates it, for example
  `the resource block table pendenz_resource_block does not exist;
  create it with a migration (PostgresDdl.createResourceBlockTable), or
  set pendenz.schema-check=false`.
- Missing columns fail startup with the table and the columns, as today.
- `pendenz.schema-check=false` turns off the check of every table.

The check stays read-only on `information_schema` (principle 2). No
property turns a table on: the customizer is one line, and a property
would also need the table's name and columns, which the mapping classes
already hold.

### Documentation

`docs/spring.md` shows the customizer that turns on resource blocks,
and the section on startup checks names the optional tables.

### Tests

In `PendenzAutoConfigurationTest`, against PostgreSQL: a customizer
that turns on resource blocks without the table fails startup naming
the table; with the table created, startup succeeds; a table without
one mapped column fails naming that column; `schema-check=false` starts
without the table. Unit tests of the core for `Pendenz.tables` with
and without the optional tables, and `null` for `inMemory()`.

## Rejected alternatives

- **Properties `pendenz.cycles.enabled`, `pendenz.resource-limits.enabled`
  and `pendenz.resource-blocks.enabled`.** They turn tables on, but the
  check would still not see a table turned on by a customizer, and the
  properties would need table and column names to match what a
  `ResourceBlockTable` already maps.
- **Only a note in the documentation.** The failure would still come at
  the first claim, as an error logged on every poll.
- **The check in the core, as `Pendenz.checkSchema()` through the store
  and the dialect.** Applications without Spring would gain it too, and
  the store could check the payload columns as well. It changes the
  store and dialect seams for a check that no caller outside the starter
  has asked for. It remains possible later, with `QueueTables` as its
  input.
- **Getters on `Pendenz.Builder`.** The starter would have to read the
  builder after every customizer ran, and the builder is the wrong
  place to ask what a queue uses.

## Consequences

- Public API of the core: `QueueTables`, `Pendenz.tables`, and `all` on
  two column classes.
- An application that turned on an optional table but created it under
  another name than its mapping says, or not at all, now fails at
  startup instead of at the first claim. That is the purpose; it can set
  `pendenz.schema-check=false`.
- The `TaskTable` bean stays: `LISTEN`/`NOTIFY` reads it, and an
  application that builds its own `Pendenz` bean still declares it. The
  schema check no longer needs it.

## Decisions taken with the acceptance (2026-11-07)

1. `Pendenz.tables` is a new public, read-only property.
2. It is `null` for `inMemory()` and for an application's own store.
3. No property turns an optional table on; a `PendenzCustomizer` does.
4. `ResourceLimitColumns` and `ResourceBlockColumns` gain `all`.
