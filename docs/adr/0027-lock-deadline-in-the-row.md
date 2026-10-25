# The lock carries its own deadline

- Status: accepted
- Date: 2026-10-25

## Context

A running task holds a lock: `locked_by` names the worker, `locked_at`
is the last time that worker confirmed the lock with a heartbeat. How
long a lock may go unconfirmed before it counts as abandoned is
`WorkerOptions.lockTimeout`. That is an option of a worker, but every
worker applies it to **every** row of the table: the poll releases all
`RUNNING` rows whose `locked_at` is older than its own `now -
lockTimeout`, whoever holds them.

Workers on one table do not have to agree on their options, and
sometimes they should not. A worker for long reports may beat every
minute with a lock timeout of five minutes, next to a worker for
quick tasks with the defaults of five seconds and one minute. The quick
worker then releases the report worker's tasks after one minute,
although that worker is alive and well inside its own rules. The task
runs twice. The check `lockTimeout > heartbeatInterval` protects only
pairs of values within one worker. Nothing tells users that all workers
of a table must use the same lock timeout, and a rolling deployment
that changes the value breaks the rule for its duration even when
everyone tries to follow it.

The lock timeout belongs with the heartbeat interval. Only the worker
that holds the lock knows how often it will beat, so only that worker
can say how long its silence means death.

## Decision

The worker that holds a lock writes the lock's deadline into the row.
Whoever reads the row compares the deadline with the time, and nothing
else.

- The column `locked_at` is replaced by **`locked_until`**
  (`TaskTable.lockedUntil`, `timestamptz`). The claim sets it to `now +
  lockTimeout` of the claiming worker; every heartbeat moves it to `now
  + lockTimeout` again.
- A lock is **expired** when `locked_until < now`. That is the only
  definition, used by the worker (ADR 0026), the `_stuck` view and the
  SQL recipes.
- `lockTimeout` stays in `WorkerOptions` beside `heartbeatInterval`. Its
  meaning narrows: how long others wait for *this* worker. The existing
  check that it is longer than the heartbeat interval is now enough,
  because both values come from the same worker.
- The deadline is computed with the worker's `Clock`, as `locked_at` is
  today and as `created_at` is, so both stores behave alike (ADR 0006).
  Workers whose clocks differ by more than the lock timeout have the
  same problem now as before. Hosts are expected to run NTP.
- The `_stuck` view lists `RUNNING` rows whose lock expired more than
  `stuckAfter` ago, with `now() - locked_until AS expired_for`. Since
  ADR 0026, those are rows that no live worker for their type has picked
  up.

## Rejected alternatives

- **Keep `locked_at` and add `locked_until`.** `locked_at` would still
  answer "when did the worker last confirm its lock", but no rule needs
  that answer. "Is it alive?" is `locked_until > now()`, which is
  clearer. Two lock columns would be one more column in the user's
  schema for information nobody acts on.
- **Document that all workers of a table must share one lock timeout.**
  Cannot be checked, and rolling deployments break it by design.
- **Lock timeout per task type**, on `TaskType`. The heartbeat interval
  is still the worker's, so the pair is split across two places and
  can again disagree. Two applications registering the same type with
  different values would have the same problem as today.
- **Store the timeout instead of the deadline**, as an interval column.
  Same information, but every query has to add it to a timestamp, and
  the expiry condition can no longer use an index on one column.
- **Use the database's `now()` instead of the worker's clock.** Would
  remove clock skew between hosts, but break the rule that time comes
  from the injected `Clock`, which the in-memory store and the
  time-dependent tests rely on.

## Consequences

- Workers with different lock timeouts can share a table. Each one's
  tasks are released by its own deadline.
- Schema change: `locked_at` becomes `locked_until`. Nothing is
  published, so there is no migration. Users who mapped `lockedAt` to an
  own column name rename the mapping.
- Public API: `TaskTable.lockedAt` and `TaskRecord.lockedAt` become
  `lockedUntil`. The DDL generator, both stores, the dialect, the
  operator DDL, `docs/sql-recipes.md` and the Spring schema check
  follow.
- The expiry query of ADR 0026 is `status = 'RUNNING' AND locked_until <
  ?`. Its index belongs to the separate decision about indexes on the
  claim path.
- Tests: two workers on one table, one with a short and one with a long
  lock timeout; the short one does not take a live task of the long
  one, and does take it once the long one stops beating. The existing
  stale-lock tests write `locked_until` into the past instead of
  `locked_at`.

## Decisions taken with the acceptance (2026-10-25)

1. `locked_until` replaces `locked_at`; there is no second lock column.
2. The deadline is computed with the worker's `Clock`.
3. The column is called `locked_until`.
