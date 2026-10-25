package io.github.d4niella.pendenz

import java.time.Duration

/**
 * How a worker polls and runs tasks.
 *
 * @property parallelism how many tasks one worker runs at the same time.
 * @property pollInterval how long to wait when no task was found.
 * @property heartbeatInterval how often a running task refreshes its lock.
 * @property lockTimeout how long other workers wait for this worker's heartbeat before they take its tasks over;
 *   each claim and heartbeat writes now plus this value into the row. See ADR 0027.
 * @property executionTimeout how long a single handler may run before it is cancelled.
 * @property shutdownTimeout how long a stopping worker lets running handlers finish before cancelling them
 *   and releasing their tasks; zero cancels at once. See ADR 0005.
 * @property maintenanceInterval how often the worker applies the retention rules of its task types, beside the
 *   claim loop, the first time one interval after the start; zero means no maintenance on this worker.
 *   See ADR 0011 and ADR 0029.
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
        require(lockTimeout > heartbeatInterval) { "lockTimeout must be longer than heartbeatInterval" }
        require(!shutdownTimeout.isNegative) { "shutdownTimeout must not be negative" }
        require(!maintenanceInterval.isNegative) { "maintenanceInterval must not be negative" }
        require(retentionBatchSize >= 1) { "retentionBatchSize must be at least 1" }
    }

    public companion object {
        public const val DEFAULT_PARALLELISM: Int = 4
        public val DEFAULT_POLL_INTERVAL: Duration = Duration.ofMillis(500)
        public val DEFAULT_HEARTBEAT_INTERVAL: Duration = Duration.ofSeconds(5)
        public val DEFAULT_LOCK_TIMEOUT: Duration = Duration.ofSeconds(60)
        public val DEFAULT_EXECUTION_TIMEOUT: Duration = Duration.ofMinutes(10)
        public val DEFAULT_SHUTDOWN_TIMEOUT: Duration = Duration.ofSeconds(30)
        public val DEFAULT_MAINTENANCE_INTERVAL: Duration = Duration.ofMinutes(1)
        public const val DEFAULT_RETENTION_BATCH_SIZE: Int = 1000
        public val DEFAULT_WAKE_UP: WakeUp = WakeUp.POLLING
    }
}
