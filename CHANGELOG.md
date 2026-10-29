# Changelog

All notable changes to Pendenz are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and the project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).
Before 1.0, a minor version may break the API; every break is named here.

## [0.5.0] - Unreleased

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

[0.5.0]: https://github.com/pendenzlib/pendenz/releases/tag/v0.5.0
