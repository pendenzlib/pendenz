package io.github.d4niella.pendenz.javaapi

import io.github.d4niella.pendenz.ClaimStrategy
import io.github.d4niella.pendenz.FairClaim
import io.github.d4niella.pendenz.WakeUp
import io.github.d4niella.pendenz.WorkerOptions
import java.time.Duration

/** Builds [WorkerOptions] one setting at a time, for callers without named arguments. */
@Suppress("TooManyFunctions") // one setter per option, the options dictate the count
public class WorkerOptionsBuilder {
    private var parallelism = WorkerOptions.DEFAULT_PARALLELISM
    private var pollInterval = WorkerOptions.DEFAULT_POLL_INTERVAL
    private var heartbeatInterval = WorkerOptions.DEFAULT_HEARTBEAT_INTERVAL
    private var lockTimeout = WorkerOptions.DEFAULT_LOCK_TIMEOUT
    private var executionTimeout = WorkerOptions.DEFAULT_EXECUTION_TIMEOUT
    private var shutdownTimeout = WorkerOptions.DEFAULT_SHUTDOWN_TIMEOUT
    private var maintenanceInterval = WorkerOptions.DEFAULT_MAINTENANCE_INTERVAL
    private var retentionBatchSize = WorkerOptions.DEFAULT_RETENTION_BATCH_SIZE
    private var wakeUp = WorkerOptions.DEFAULT_WAKE_UP
    private var claimStrategy: ClaimStrategy = FairClaim()

    public fun parallelism(value: Int): WorkerOptionsBuilder = apply { parallelism = value }

    public fun pollInterval(value: Duration): WorkerOptionsBuilder = apply { pollInterval = value }

    public fun heartbeatInterval(value: Duration): WorkerOptionsBuilder = apply { heartbeatInterval = value }

    public fun lockTimeout(value: Duration): WorkerOptionsBuilder = apply { lockTimeout = value }

    public fun executionTimeout(value: Duration): WorkerOptionsBuilder = apply { executionTimeout = value }

    public fun shutdownTimeout(value: Duration): WorkerOptionsBuilder = apply { shutdownTimeout = value }

    public fun maintenanceInterval(value: Duration): WorkerOptionsBuilder = apply { maintenanceInterval = value }

    public fun retentionBatchSize(value: Int): WorkerOptionsBuilder = apply { retentionBatchSize = value }

    /** How the worker waits when a poll found nothing, for example `PostgresNotify` from `pendenz-postgres-notify`. */
    public fun wakeUp(value: WakeUp): WorkerOptionsBuilder = apply { wakeUp = value }

    /** Which due tasks a poll takes; `FairClaim` by default, `FifoClaim` for arrival order. */
    public fun claimStrategy(value: ClaimStrategy): WorkerOptionsBuilder = apply { claimStrategy = value }

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
            wakeUp = wakeUp,
            claimStrategy = claimStrategy,
        )
}
