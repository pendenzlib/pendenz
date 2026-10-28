# Architecture Decision Records

Format: [MADR](https://adr.github.io/madr/). One file per decision, numbered
consecutively, never deleted. A superseded decision gets the status
"superseded by ADR nnnn".

| No. | Title | Status |
|---|---|---|
| [0001](0001-record-architecture-decisions.md) | Record architecture decisions | accepted |
| [0002](0002-status-model.md) | Status model | accepted |
| [0003](0003-tech-stack-and-jvm-baseline.md) | Tech stack and JVM baseline | accepted |
| [0004](0004-test-database.md) | Test database: Testcontainers with embedded PostgreSQL as fallback | accepted |
| [0005](0005-graceful-shutdown.md) | Graceful shutdown of a worker | accepted |
| [0006](0006-store-seam-and-in-memory-mode.md) | Store seam and in-memory mode | accepted |
| [0007](0007-task-id-type.md) | Task id type | accepted |
| [0008](0008-sql-dialect.md) | SQL dialect behind the JDBC store | accepted |
| [0009](0009-idempotency-key.md) | Idempotency key | accepted |
| [0010](0010-payload-versioning.md) | Payload versioning | accepted |
| [0011](0011-retention.md) | Retention of finished tasks | accepted |
| [0012](0012-jackson-module.md) | Jackson codec as a module | accepted |
| [0013](0013-payload-columns.md) | Payload columns instead of one JSON column | accepted |
| [0014](0014-wait-instead-of-poll.md) | Wait instead of poll: LISTEN/NOTIFY as an option | accepted |
| [0015](0015-claim-strategy.md) | Claim strategy: fairness across lanes, priority as tiers | accepted |
| [0016](0016-cycles.md) | Cycles: tasks for a whole dataset | accepted |
| [0017](0017-resource-limits.md) | Concurrency limits per resource | accepted |
| [0018](0018-approval-and-permissions.md) | Approval and permissions | accepted |
| [0019](0019-chaining.md) | Chaining: follow-up tasks in the outcome's transaction | accepted |
| [0020](0020-results-validation-provenance-preview.md) | Results: validation, provenance and preview | accepted |
| [0021](0021-mcp-module.md) | MCP module: registered task types as tools for agents | accepted |
| [0022](0022-micrometer-module.md) | Micrometer module: metrics from the listener and the backlog | accepted |
| [0023](0023-spring-boot-starter.md) | Spring integration: a transaction-joining provider and a Boot starter | accepted |
| [0024](0024-kotlin-library-versions.md) | Kotlin library versions: compile against what the frameworks pin | accepted |
| [0025](0025-handler-beans-without-cycles.md) | Handler beans that may depend on what enqueues | accepted |
| [0026](0026-failures-the-handler-cannot-report.md) | Failures the handler cannot report: errors and expired locks | accepted |
| [0027](0027-lock-deadline-in-the-row.md) | The lock carries its own deadline | accepted |
| [0028](0028-indexes-on-the-task-table.md) | Indexes on the task table, and queries that can use them | accepted |
| [0029](0029-bounded-work-per-poll.md) | Bounded work per poll: full resources, empty visits, maintenance | accepted |
| [0030](0030-read-model-in-the-root-package.md) | The read model in the root package | accepted |
| [0031](0031-status-transitions-of-operator-actions.md) | Status transitions of operator actions | proposed |
