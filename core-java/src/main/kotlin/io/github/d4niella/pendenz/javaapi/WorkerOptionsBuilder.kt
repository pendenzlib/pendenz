package io.github.d4niella.pendenz.javaapi

import io.github.d4niella.pendenz.WorkerOptions
import java.time.Duration

/** Builds [WorkerOptions] one setting at a time, for callers without named arguments. */
public class WorkerOptionsBuilder {
    private var parallelism = WorkerOptions.DEFAULT_PARALLELISM
    private var pollInterval = WorkerOptions.DEFAULT_POLL_INTERVAL
    private var heartbeatInterval = WorkerOptions.DEFAULT_HEARTBEAT_INTERVAL
    private var lockTimeout = WorkerOptions.DEFAULT_LOCK_TIMEOUT
    private var executionTimeout = WorkerOptions.DEFAULT_EXECUTION_TIMEOUT
    private var shutdownTimeout = WorkerOptions.DEFAULT_SHUTDOWN_TIMEOUT
    private var maintenanceInterval = WorkerOptions.DEFAULT_MAINTENANCE_INTERVAL
    private var retentionBatchSize = WorkerOptions.DEFAULT_RETENTION_BATCH_SIZE

    public fun parallelism(value: Int): WorkerOptionsBuilder = apply { parallelism = value }

    public fun pollInterval(value: Duration): WorkerOptionsBuilder = apply { pollInterval = value }

    public fun heartbeatInterval(value: Duration): WorkerOptionsBuilder = apply { heartbeatInterval = value }

    public fun lockTimeout(value: Duration): WorkerOptionsBuilder = apply { lockTimeout = value }

    public fun executionTimeout(value: Duration): WorkerOptionsBuilder = apply { executionTimeout = value }

    public fun shutdownTimeout(value: Duration): WorkerOptionsBuilder = apply { shutdownTimeout = value }

    public fun maintenanceInterval(value: Duration): WorkerOptionsBuilder = apply { maintenanceInterval = value }

    public fun retentionBatchSize(value: Int): WorkerOptionsBuilder = apply { retentionBatchSize = value }

    public fun build(): WorkerOptions =
        WorkerOptions(
            parallelism = parallelism,
            pollInterval = pollInterval,
            heartbeatInterval = heartbeatInterval,
            lockTimeout = lockTimeout,
            executionTimeout = executionTimeout,
            shutdownTimeout = shutdownTimeout,
            maintenanceInterval = maintenanceInterval,
            retentionBatchSize = retentionBatchSize,
        )
}
