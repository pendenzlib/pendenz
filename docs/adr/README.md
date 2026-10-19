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
