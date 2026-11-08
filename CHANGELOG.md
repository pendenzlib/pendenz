# Changelog

All notable changes to Pendenz are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and the project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).
Before 1.0, a minor version may break the API; every break is named here.

## [Unreleased]

### Added

- `Pendenz.schedules()` and `PendenzJava.schedules()` read where every
  schedule stands: its newest occurrence, its last success and its next
  run (ADR 0034).
- `pendenz-micrometer`: the gauges `pendenz.schedules.next` and
  `pendenz.schedules.last.success` per schedule, refreshed with the
  backlog gauges (ADR 0034).
- `pendenz-spring-boot-starter`: `ScheduleRegistration` beans, and the
  properties `pendenz.schedules.<name>.enabled`, `.cron` and `.zone`
  (ADR 0034).
- Decisions a handler reports by throwing (ADR 0035):
  `RetryLaterException` defers the task without counting the attempt,
  `ResourceUnavailableException` also blocks the resource until a given
  time, `HandOverException` quarantines the task for a person. Resource
  blocks live in an optional table (`enableResourceBlocks()`,
  `PostgresDdl.createResourceBlockTable`) and are operated with SQL or
  `blockResource`, `unblockResource` and `resourceBlocks`. The listener
  gains `onDeferred`, `onHandedOver` and `onResourceBlocked`.
- `TaskInterceptor`, registered with `Pendenz.Builder.interceptor`,
  encloses every handler run inside the execution timeout; in
  `pendenz-core-java`, `BlockingTaskInterceptor` does the same on the
  handler's thread for handlers registered through the facade
  (ADR 0036).
- `CallerDefaults`, registered with `callerDefaults` on either builder,
  supplies the actor and correlation id of `enqueue`, `startCycle` and
  the operator and cycle actions when a call leaves them out (ADR 0036).
- `pendenz-spring-boot-starter`: `TaskInterceptor`,
  `BlockingTaskInterceptor` and `CallerDefaults` beans (ADR 0036).
- `Pendenz.tables` reports the task table and the optional cycle,
  resource limit and resource block tables a queue on the database
  store uses; `ResourceLimitColumns` and `ResourceBlockColumns` gain
  `all` (ADR 0037).
- `pendenz-micrometer`: the execution timer records the outcomes
  `deferred` and `handed_over`, and the counter
  `pendenz.resources.blocked` counts resource blocks (ADR 0022, amended
  for ADR 0035).
- `TaskType.executionTimeout` sets how long one run of the type's
  handler may take, shorter or longer than the worker's
  `executionTimeout`, which stays the default for types without one
  (ADR 0039).

### Changed

- The status model allows `RUNNING` to `QUARANTINED`, for a hand-over by
  the handler; an operator still cannot quarantine a running task
  (ADR 0035).
- Breaking for custom dialects and stores: `SqlDialect.statements`
  takes the block table, and dialects implement `resourceBlockStatements`
  and `TaskSql.defer`; with limits or blocks, the claim statements bind
  four arrays instead of three. Custom `TaskStore`s handle
  `Outcome.Deferred` and `Outcome.HandedOver` in `finish` (ADR 0035).
- `pendenz-spring-boot-starter`: the schema check also covers the cycle,
  resource limit and resource block tables a `PendenzCustomizer` turned
  on, so a missing one fails startup instead of every poll; the message
  names the `PostgresDdl` function that creates it. The bean method
  `pendenzSchemaCheck` takes the queue instead of the `TaskTable`
  (ADR 0037).
- Breaking for retry policies of your own: `RetryPolicy.nextAttemptAt`
  takes one `FailedAttempt`, which carries the task's `TaskContext`
  beside the attempt, the time and the cause. A lambda
  `{ attempt, failedAt, cause -> ... }` becomes `{ f -> ... }` with
  `f.attempt`, `f.failedAt` and `f.cause`. `NoRetry` and
  `ExponentialBackoff` are unchanged in use (ADR 0038).

### Fixed

- A retry policy that throws no longer leaves the task `RUNNING` until
  its lock expires, nor fails every poll when it throws for an expired
  lock; the task becomes `FAILED` and the exception is logged
  (ADR 0038).
- `pendenz-core` no longer publishes the sources of its test fixtures
  (`test-fixtures-sources.jar`), which are helpers for this repository's
  own tests.

## [0.5.0] - 2026-10-30

The first release. It contains the milestones 0.1 to 0.5 of the roadmap,
which were developed without separate releases. The decisions behind each
feature are recorded in [`docs/adr`](docs/adr).

Requires Java 17 or later. Tested against PostgreSQL 17. The Kotlin modules
are compiled for Kotlin 2.2 and need kotlinx-coroutines 1.10.2 and
kotlinx-serialization 1.9.0 or later (ADR 0024).

### Added

#### Queue (`pendenz-core`)

- Tasks as rows in a table you own: the mapping names the table and its
  columns, `PostgresDdl` writes the DDL for your migration, and the id
  type is `bigint` or `uuid` (ADR 0007).
- A status model with an explicit transition table for tasks and cycles
  (ADR 0002, ADR 0031).
- Enqueue in your own transaction, so the task commits or rolls back with
  the business change, or in a transaction of the queue.
- Workers that claim with `SKIP LOCKED`, keep their lock alive with a
  heartbeat, and record the outcome with a version check, so an operator's
  change made meanwhile wins.
- Retry with exponential backoff, deferred tasks, execution timeouts, and a
  lock deadline in the row, after which a dead worker's task counts as a
  failed attempt (ADR 0026, ADR 0027).
- Graceful shutdown with a deadline: running handlers may finish, the rest
  is released (ADR 0005).
- Idempotency keys, unique per task type (ADR 0009).
- Payload columns with typed SQL columns, or a JSON column through
  kotlinx-serialization; payload versions with upgrades per task type
  (ADR 0010, ADR 0013).
- Retention per task type, deleting or archiving finished tasks (ADR 0011).
- Cycles: one task type over a whole dataset, from a SQL query or a list,
  with progress, pause, resume and cancel (ADR 0016).
- Fair claiming across lanes, priorities as strict tiers, and batch claims
  per task type (ADR 0015).
- Concurrency limits per resource, exact across workers and processes
  (ADR 0017).
- Approval before a task runs, and permissions per actor, action and task
  type (ADR 0018).
- Follow-up tasks written in the transaction of the outcome, with parent
  and correlation id (ADR 0019).
- Results with validation, provenance metadata and a preview for approvers
  (ADR 0020).
- Operator actions on the API (`restart`, `complete`, `quarantine`,
  `cancel`) and as generated SQL functions and views, both writing the same
  row (ADR 0031).
- Queries over tasks by type, status, creator, lane, parent and correlation
  id, paged by keyset; the backlog per task type with the oldest due run
  time.
- Listener hooks for metrics and logs, delivered after the commit
  (ADR 0032).
- Recurring tasks: `Builder.schedule` with `Schedule.every`, `daily` and
  `cron`, one occurrence ahead as an ordinary row (ADR 0033). The
  `schedule:` prefix is reserved in `createdBy` and idempotency keys.
- An in-memory store and `runPending()` for tests without a database
  (ADR 0006).
- A SQL dialect seam for further databases; PostgreSQL is the one shipped
  (ADR 0008).

#### Modules

- `pendenz-core-java`: a Java facade with blocking calls,
  `CompletableFuture` and handlers as plain methods, proven by tests
  written in Java.
- `pendenz-serialization-jackson`: a payload codec and payload upgrades over
  Jackson 3 (ADR 0012).
- `pendenz-postgres-notify`: wakes workers through `LISTEN`/`NOTIFY`
  instead of waiting for the poll interval (ADR 0014).
- `pendenz-mcp`: an MCP server over the task types you expose, so AI agents
  enqueue and follow tasks as tools, over stdio or a servlet (ADR 0021).
- `pendenz-micrometer`: meters from the listener events, and gauges for the
  backlog per type and status (ADR 0022).
- `pendenz-spring`: a connection provider that joins Spring transactions,
  for Spring Framework 6.2 and 7 (ADR 0023).
- `pendenz-spring-boot-starter`: auto-configuration for Spring Boot 4, with
  handlers as beans, a worker in the application lifecycle, startup checks
  and configuration metadata (ADR 0023, ADR 0025).

#### Examples

- A billing demo with Flyway, a flaky handler, an SQL restart and
  retention into an archive.
- A shop on Spring Boot with the starter and a handler bean.
- A helpdesk agent that acts only through MCP tools, with approval and
  refused results.
- A catalogue cycle that classifies products with a local model, under a
  resource limit.

[Unreleased]: https://github.com/pendenzlib/pendenz/compare/v0.5.0...HEAD
[0.5.0]: https://github.com/pendenzlib/pendenz/releases/tag/v0.5.0
