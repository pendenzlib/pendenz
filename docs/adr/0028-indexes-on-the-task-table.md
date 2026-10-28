# Indexes on the task table, and queries that can use them

- Status: accepted
- Date: 2026-10-25

## Context

The generated `CREATE TABLE` comes with four indexes: the claim index
`(status, type, lane, priority, run_at)` of ADR 0015, a partial index on
`resource` over running rows (ADR 0017), a partial index on
`correlation_id` (ADR 0019) and the unique index on `(type,
idempotency_key)` (ADR 0009). The review of 2026-10-25 read every
statement the library issues against them and found that the regular
ones do not get the index they were meant to get:

- **Claiming a group** filters the lane with `lane IS NOT DISTINCT FROM
  ?`. PostgreSQL cannot use that as a B-tree condition, so only `status`
  and `type` of the claim index narrow the scan; lane and priority are
  filtered row by row, and the order by `run_at` costs a sort of every
  due pending task of the type, on every group visit of every poll,
  under the advisory lock that serializes claims (ADR 0017).
- **Promoting due tasks**, `status IN ('SCHEDULED', 'RETRY_PENDING') AND
  run_at <= ?`, runs on every poll. `run_at` is the fifth column of the
  claim index, so every scheduled and retrying row is read, due or not.
- **Cycle statements** (`countLane`, `cancelLane`, `releaseLane`,
  `approveLane`, `finishCycles`, the progress view and the generated
  cycle cancel) filter by lane alone. The lane is the third column of
  the claim index; no index starts with it, so each of them reads the
  whole table, and `finishCycles` does so every maintenance interval.
- **Retention** selects `type = ? AND status IN ('SUCCEEDED',
  'CANCELLED') AND COALESCE(finished_at, created_at) < ?`. The
  `COALESCE` is not indexable, so every finished row of the type is
  visited when nothing has expired yet, every minute, on every worker.
- **Listing tasks** (`Pendenz.tasks`, and the MCP tool `list_tasks` with
  `created_by = ?` on every call) orders by `created_at DESC, id DESC`
  with no index on either column: a full scan and a top-N sort.
- **Expired locks** (ADR 0026) select `status = 'RUNNING' AND
  locked_until < ?` and have no index of their own.

A second, smaller finding belongs here because the indexes are named
after the table: every derived name (`<table>_claim_idx`, the views and
functions of the operator DDL, and the notify channel `pendenz_<table>`)
can exceed PostgreSQL's limit of 63 bytes. PostgreSQL shortens index,
view and function names silently, so two of them can collide, while
`pg_notify` in the trigger fails with "channel name too long", which
makes every enqueue fail once the trigger is installed. `LISTEN` shortens
silently and listens on a channel nobody announces on.

Principle 2 says the schema belongs to the user, who adds whatever
indexes they like. That is no reason to hand out a schema whose own
queries scan the table. The generated DDL is the starting point most
users copy into their migration; the indexes in it have to be the ones
the library's queries need, and those needs have to be written down for
users who write their tables themselves.

## Decision

### Queries take an indexable shape

- **The group claim comes in two statements**: `lane = ?` for a lane and
  `lane IS NULL` for single tasks. Both are B-tree conditions, so status,
  type, lane and priority are all equality conditions and the index
  yields the rows in `run_at` order. `TaskSql.claimFromGroup` is
  replaced by `claimFromLane` and `claimWithoutLane`.
- **The claim index ends in `id`**, `(status, type, lane, priority,
  run_at, id)`, so the tie-breaker of `ORDER BY run_at, id` needs no
  sort either.
- **Retention compares the columns, not an expression**:
  `finished_at < ? OR (finished_at IS NULL AND created_at < ?)`. The
  fallback to `created_at` of ADR 0011 stays; it only covers rows that
  SQL recipes finished without a finish time, which are few.

### The indexes the generated DDL creates

| Name suffix | Columns | Serves |
|---|---|---|
| `_claim_idx` | `(status, type, lane, priority, run_at, id)` | `dueGroups`, both group claims, the backlog |
| `_due_idx` | `(run_at) WHERE status IN ('SCHEDULED', 'RETRY_PENDING')` | `promoteDue`, every poll |
| `_running_idx` | `(resource) WHERE status = 'RUNNING'` | resource limits (ADR 0017) and expired locks (ADR 0026) |
| `_lane_idx` | `(lane, status) WHERE lane IS NOT NULL` | every cycle statement, the progress view |
| `_retention_idx` | `(type, finished_at) WHERE status IN ('SUCCEEDED', 'CANCELLED')` | retention (ADR 0011) |
| `_creator_idx` | `(created_by, created_at DESC, id DESC) WHERE created_by IS NOT NULL` | `tasks(TaskQuery)` by creator, the MCP listing |
| `_correlation_idx` | `(correlation_id) WHERE correlation_id IS NOT NULL` | unchanged |
| `_idempotency_idx` | unique `(type, idempotency_key)` | unchanged, ADR 0009 |

The resource index is renamed `_running_idx` because it now serves two
queries: it holds every running row, with or without a resource, and
running rows are few, bounded by the workers' parallelism. Expired locks
are found by reading those rows. **`locked_until` itself gets no
index**: a heartbeat changes only that column, every few seconds for
every running task, and as long as no index contains it PostgreSQL can
do the update as a HOT update, without touching any index. An index on
it would turn every heartbeat into index writes to save a scan of a
handful of rows.

The partial indexes stay small where a feature is not used: without
lanes, creators or retained rows they hold nothing.

### Where the indexes come from

- `PostgresDdl.indexes(table)` returns the `CREATE INDEX` statements on
  their own; `createTable` is the table plus that. Users who write their
  own `CREATE TABLE`, or add Pendenz to an existing table, take the
  indexes from there.
- `docs/` gets a section "Indexes the queries expect" with the table
  above, so a migration written by hand can be checked against it.

### Names derived from the table fit into 63 bytes

All names derived from the table name, indexes, views, functions and
the notify channel, go through one function. When the name and its
suffix exceed 63 bytes, the table part is cut and a short hash of the
full table name is appended, so the result is stable, unique per table
and the same for the trigger and `PostgresNotify`. Names that fit stay
exactly as today.

### Tests

Against PostgreSQL, each regular statement is checked with `EXPLAIN` in
a session with `enable_seqscan = off`: the plan uses the intended index
with the filter columns as index conditions, and the group claim has no
sort node. The check reads the plan, not the SQL text. A small table
would otherwise get a sequential scan from the planner, which says
nothing about large ones. A test with a table name of sixty characters
covers the derived names, the trigger and `LISTEN`.

## Rejected alternatives

- **Leave the indexes to the user** and only document what the queries
  need. Principle 2 allows it, but the generated DDL is what most users
  run, and a default that scans the table on every poll is not a good
  default (principle 1).
- **One `COALESCE` expression index for retention.** It would serve
  today's expression, but ties the index to the fallback, which exists
  for a rare case; comparing the columns needs a plain index.
- **An index on `locked_until`.** Costs index writes on every heartbeat,
  see above.
- **An index for `FifoClaim`**, `(status, priority DESC, run_at, id)`.
  `FifoClaim` is the strategy for teams with an ordering requirement,
  not the default; its index is named in the docs instead of being
  generated for everyone.
- **An index on `created_at` for listings without a creator.** Only
  operators list without a filter, and rarely; documented, not
  generated.
- **Refusing long table names** instead of shortening. Simpler, but a
  table name of 56 characters is legal and would lose the notify module
  for no reason the user could see in their own name.

## Consequences

- Eight indexes instead of four. Each enqueue writes to the claim,
  idempotency and, where set, creator, lane and correlation indexes;
  transitions write to the indexes that contain `status`, which they did
  before. Heartbeats stay HOT updates.
- Public API: `TaskSql.claimFromGroup` becomes `claimFromLane` and
  `claimWithoutLane`; `PostgresDdl.indexes` is new; the resource index
  changes its name. Nothing is published, so there is no migration; the
  index names change only for tables that already exist in development
  databases.
- ADR 0011 (retention query), 0015 (claim index), 0017 (resource index)
  and 0026 (expired locks) get a note pointing here.
- Not decided here, though found in the same review: `FairClaim` visits
  every due group per poll even when it claims nothing from them, and
  the resource condition is evaluated per candidate row; with the
  indexes above each visit becomes cheap, but their number stays. Both
  belong to the claim strategy and get a decision of their own.
  Retention running inside the claim loop is likewise separate.

## Decisions taken with the acceptance (2026-10-25)

1. The creator index is generated.
2. Long derived names are shortened with a hash, not refused.
3. `PostgresDdl.indexes(table)` is public API.
4. Plan tests with `EXPLAIN` and `enable_seqscan = off` check the
   regular statements.
5. The `FifoClaim` index and an index for unfiltered listings are named
   in the docs, not generated.

Noted during implementation:

- Both group claims order by `lane, run_at, id`. The lane is fixed in
  either statement, but the planner does not treat `lane IS NULL` as a
  constant and sorted the single tasks instead of reading the claim
  index in order; the plan test showed it.
- `PostgresNotify.start` with a channel name refuses one longer than 63
  bytes; with a table it uses the shortened channel of the trigger.
- The retention fallback to `created_at` gained a contract test on both
  stores.

Amended with ADR 0031 (2026-10-28): a ninth index, `_held_idx` on
`(resource) WHERE status = 'CANCELLED' AND locked_by IS NOT NULL`,
serves the running count for cancelled tasks whose handler has not
returned and the sweep of locks dead workers left on them. Its predicate
names `locked_by`, not `locked_until`: the heartbeat writes only
`locked_until`, which carries no index, and so stays a HOT update.
