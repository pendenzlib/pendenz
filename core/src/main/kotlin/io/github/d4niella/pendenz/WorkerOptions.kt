package io.github.d4niella.pendenz

import java.time.Duration

/**
 * How a worker polls and runs tasks.
 *
 * @property parallelism how many tasks one worker runs at the same time.
 * @property pollInterval how long to wait when no task was found.
 * @property heartbeatInterval how often a running task refreshes its lock.
 * @property lockTimeout after how long without heartbeat another worker may take over.
 * @property executionTimeout how long a single handler may run before it is cancelled.
 */
public class WorkerOptions(
    public val parallelism: Int = DEFAULT_PARALLELISM,
    public val pollInterval: Duration = DEFAULT_POLL_INTERVAL,
    public val heartbeatInterval: Duration = DEFAULT_HEARTBEAT_INTERVAL,
    public val lockTimeout: Duration = DEFAULT_LOCK_TIMEOUT,
    public val executionTimeout: Duration = DEFAULT_EXECUTION_TIMEOUT,
) {
    init {
        require(parallelism >= 1) { "parallelism must be at least 1" }
        require(lockTimeout > heartbeatInterval) { "lockTimeout must be longer than heartbeatInterval" }
    }

    public companion object {
        public const val DEFAULT_PARALLELISM: Int = 4
        public val DEFAULT_POLL_INTERVAL: Duration = Duration.ofMillis(500)
        public val DEFAULT_HEARTBEAT_INTERVAL: Duration = Duration.ofSeconds(5)
        public val DEFAULT_LOCK_TIMEOUT: Duration = Duration.ofSeconds(60)
        public val DEFAULT_EXECUTION_TIMEOUT: Duration = Duration.ofMinutes(10)
    }
}
