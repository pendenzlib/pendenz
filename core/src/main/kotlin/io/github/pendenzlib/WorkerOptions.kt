package io.github.pendenzlib

import java.time.Duration

/**
 * How a worker polls and runs tasks.
 *
 * @property parallelism how many tasks one worker runs at the same time.
 * @property pollInterval how long to wait when no task was found.
 * @property heartbeatInterval how often a running task refreshes its lock.
 * @property lockTimeout how long other workers wait for this worker's heartbeat before they take its tasks
 *   over, at least twice [heartbeatInterval]; each claim and heartbeat writes now plus this value into the row.
 *   See ADR 0027.
 * @property executionTimeout how long a single handler may run before it is cancelled.
 * @property shutdownTimeout how long a stopping worker lets running handlers finish before cancelling them
 *   and releasing their tasks; zero cancels at once. See ADR 0005.
 * @property maintenanceInterval how often the worker runs its maintenance sweep beside the claim loop: the
 *   schedule check, finished cycles and the retention rules of its task types. The first sweep comes one
 *   interval after the start; zero means no maintenance on this worker, which then still checks the
 *   schedules at its start. See ADR 0011, ADR 0029 and ADR 0033.
 * @property retentionBatchSize how many rows one retention statement removes at most.
 * @property wakeUp how the worker waits when a poll found nothing; polling by default. See ADR 0014.
 * @property claimStrategy which due tasks a poll takes; fair across lanes by default. See ADR 0015.
 */
@Suppress("LongParameterList") // one parameter per knob, by design
public class WorkerOptions(
    public val parallelism: Int = DEFAULT_PARALLELISM,
    public val pollInterval: Duration = DEFAULT_POLL_INTERVAL,
    public val heartbeatInterval: Duration = DEFAULT_HEARTBEAT_INTERVAL,
    public val lockTimeout: Duration = DEFAULT_LOCK_TIMEOUT,
    public val executionTimeout: Duration = DEFAULT_EXECUTION_TIMEOUT,
    public val shutdownTimeout: Duration = DEFAULT_SHUTDOWN_TIMEOUT,
    public val maintenanceInterval: Duration = DEFAULT_MAINTENANCE_INTERVAL,
    public val retentionBatchSize: Int = DEFAULT_RETENTION_BATCH_SIZE,
    public val wakeUp: WakeUp = DEFAULT_WAKE_UP,
    public val claimStrategy: ClaimStrategy = FairClaim(),
) {
    init {
        require(parallelism >= 1) { "parallelism must be at least 1" }
        // Below a millisecond the worker would wait for nothing: a busy loop of polls, heartbeats or timeouts
        require(pollInterval.toMillis() >= 1) { "pollInterval must be at least 1 ms, was $pollInterval" }
        require(heartbeatInterval.toMillis() >= 1) { "heartbeatInterval must be at least 1 ms, was $heartbeatInterval" }
        require(executionTimeout.toMillis() >= 1) { "executionTimeout must be at least 1 ms, was $executionTimeout" }
        // One late heartbeat must not cost the lock: at least two have to fit into it
        require(lockTimeout >= heartbeatInterval.multipliedBy(2)) {
            "lockTimeout must be at least twice the heartbeatInterval, was $lockTimeout and $heartbeatInterval"
        }
        require(!shutdownTimeout.isNegative) { "shutdownTimeout must not be negative" }
        require(!maintenanceInterval.isNegative) { "maintenanceInterval must not be negative" }
        require(retentionBatchSize >= 1) { "retentionBatchSize must be at least 1" }
    }

    /** A builder that starts from these options, to change some of them. */
    public fun toBuilder(): Builder = Builder(this)

    /**
     * Builds [WorkerOptions] one setting at a time, for callers without
     * named arguments; every setting not given keeps the value of the
     * options it started from, the defaults for [WorkerOptions.builder].
     */
    @Suppress("TooManyFunctions") // one setter per option, the options dictate the count
    public class Builder internal constructor(
        from: WorkerOptions,
    ) {
        private var parallelism = from.parallelism
        private var pollInterval = from.pollInterval
        private var heartbeatInterval = from.heartbeatInterval
        private var lockTimeout = from.lockTimeout
        private var executionTimeout = from.executionTimeout
        private var shutdownTimeout = from.shutdownTimeout
        private var maintenanceInterval = from.maintenanceInterval
        private var retentionBatchSize = from.retentionBatchSize
        private var wakeUp = from.wakeUp
        private var claimStrategy = from.claimStrategy

        public fun parallelism(value: Int): Builder = apply { parallelism = value }

        public fun pollInterval(value: Duration): Builder = apply { pollInterval = value }

        public fun heartbeatInterval(value: Duration): Builder = apply { heartbeatInterval = value }

        public fun lockTimeout(value: Duration): Builder = apply { lockTimeout = value }

        public fun executionTimeout(value: Duration): Builder = apply { executionTimeout = value }

        public fun shutdownTimeout(value: Duration): Builder = apply { shutdownTimeout = value }

        public fun maintenanceInterval(value: Duration): Builder = apply { maintenanceInterval = value }

        public fun retentionBatchSize(value: Int): Builder = apply { retentionBatchSize = value }

        /** How the worker waits when a poll found nothing, for example `PostgresNotify`. */
        public fun wakeUp(value: WakeUp): Builder = apply { wakeUp = value }

        /** Which due tasks a poll takes; `FairClaim` by default, `FifoClaim` for arrival order. */
        public fun claimStrategy(value: ClaimStrategy): Builder = apply { claimStrategy = value }

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

    public companion object {
        /** A builder that starts from the defaults. */
        @JvmStatic
        public fun builder(): Builder = Builder(WorkerOptions())

        public const val DEFAULT_PARALLELISM: Int = 4

        @JvmField
        public val DEFAULT_POLL_INTERVAL: Duration = Duration.ofMillis(500)

        @JvmField
        public val DEFAULT_HEARTBEAT_INTERVAL: Duration = Duration.ofSeconds(5)

        @JvmField
        public val DEFAULT_LOCK_TIMEOUT: Duration = Duration.ofSeconds(60)

        @JvmField
        public val DEFAULT_EXECUTION_TIMEOUT: Duration = Duration.ofMinutes(10)

        @JvmField
        public val DEFAULT_SHUTDOWN_TIMEOUT: Duration = Duration.ofSeconds(30)

        @JvmField
        public val DEFAULT_MAINTENANCE_INTERVAL: Duration = Duration.ofMinutes(1)
        public const val DEFAULT_RETENTION_BATCH_SIZE: Int = 1000

        @JvmField
        public val DEFAULT_WAKE_UP: WakeUp = WakeUp.POLLING
    }
}
