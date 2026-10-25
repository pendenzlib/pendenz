package io.github.d4niella.pendenz.spring.boot

import io.github.d4niella.pendenz.ClaimStrategy
import io.github.d4niella.pendenz.WakeUp
import io.github.d4niella.pendenz.WorkerOptions
import io.github.d4niella.pendenz.schema.TaskTable
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** The `pendenz.*` properties of the starter. See ADR 0023. */
@ConfigurationProperties("pendenz")
public class PendenzProperties {
    /** The task table, used when the application defines no `TaskTable` bean. */
    public var table: String = TaskTable.DEFAULT_NAME

    /** The type of the table's id column; fixes the type argument of the `Pendenz` and `PendenzJava` beans. */
    public var idType: IdType = IdType.BIGINT

    /** The transaction manager bean to join, when the application has more than one. */
    public var transactionManager: String? = null

    /** Whether startup checks that the task table exists with every column the mapping names. */
    public var schemaCheck: Boolean = true

    public val worker: Worker = Worker()

    public val metrics: Metrics = Metrics()

    public enum class IdType {
        BIGINT,
        UUID,
    }

    /** `pendenz.worker.*`: whether this node runs a worker, and how; see [WorkerOptions]. */
    public class Worker {
        /** Off for nodes that only enqueue, and in the application's tests, which call `runPending()` instead. */
        public var enabled: Boolean = true
        public var parallelism: Int = WorkerOptions.DEFAULT_PARALLELISM
        public var pollInterval: Duration = WorkerOptions.DEFAULT_POLL_INTERVAL
        public var heartbeatInterval: Duration = WorkerOptions.DEFAULT_HEARTBEAT_INTERVAL
        public var lockTimeout: Duration = WorkerOptions.DEFAULT_LOCK_TIMEOUT
        public var executionTimeout: Duration = WorkerOptions.DEFAULT_EXECUTION_TIMEOUT

        /** Below Spring Boot's `spring.lifecycle.timeout-per-shutdown-phase`, which startup checks. */
        public var shutdownTimeout: Duration = DEFAULT_SHUTDOWN_TIMEOUT
        public var maintenanceInterval: Duration = WorkerOptions.DEFAULT_MAINTENANCE_INTERVAL
        public var retentionBatchSize: Int = WorkerOptions.DEFAULT_RETENTION_BATCH_SIZE

        /** Wake the worker through LISTEN/NOTIFY; needs `pendenz-postgres-notify` and the trigger. See ADR 0014. */
        public var notify: Boolean = false

        internal fun toOptions(
            wakeUp: WakeUp,
            claimStrategy: ClaimStrategy,
        ): WorkerOptions =
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

        private companion object {
            /** Shorter than the core's 30 seconds, so that Spring Boot's 30-second phase does not give up first. */
            val DEFAULT_SHUTDOWN_TIMEOUT: Duration = Duration.ofSeconds(20)
        }
    }

    /** `pendenz.metrics.*`, used when `pendenz-micrometer` is on the classpath. */
    public class Metrics {
        /** How often the backlog gauges are refreshed; null, the default, leaves them off. See ADR 0022. */
        public var backlogInterval: Duration? = null
    }
}
