package io.github.d4niella.pendenz

import io.github.d4niella.pendenz.schema.TaskTable
import io.github.d4niella.pendenz.store.JdbcTaskStore
import io.github.d4niella.pendenz.store.NewTask
import io.github.d4niella.pendenz.store.TaskRecord
import io.github.d4niella.pendenz.store.TaskStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.sql.Connection
import java.time.Clock
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * The queue. Built once per task table with [Pendenz.create], then used to
 * enqueue tasks and to run workers.
 *
 * Everything replaceable is a constructor argument of the builder with a
 * default: the store, the connection provider, the clock, the worker id.
 */
public class Pendenz private constructor(
    private val store: TaskStore,
    private val connections: ConnectionProvider,
    private val registry: TaskRegistry,
    private val clock: Clock,
    private val workerId: String,
) {
    /**
     * Enqueues a task in its own transaction.
     *
     * @param runAt when the task is due; now when omitted.
     * @return the new task's id.
     */
    public suspend fun <P : Any> enqueue(
        type: TaskType<P>,
        payload: P,
        runAt: Instant? = null,
    ): Long = connections.inTransaction { enqueue(it, type, payload, runAt) }

    /**
     * Enqueues a task inside the caller's transaction. The task becomes
     * visible to workers when that transaction commits, and disappears with
     * it on rollback, together with whatever business change caused it.
     */
    public fun <P : Any> enqueue(
        connection: Connection,
        type: TaskType<P>,
        payload: P,
        runAt: Instant? = null,
    ): Long {
        requireRegistered(type)
        val now = clock.instant()
        val due = runAt ?: now
        val status = if (due.isDue(now)) TaskStatus.PENDING else TaskStatus.SCHEDULED
        return store.insert(connection, NewTask(type.name, type.codec.encode(payload), due, status))
    }

    /** Reads a task back, for operations and tests. */
    public suspend fun find(id: Long): TaskRecord? = connections.inTransaction { store.find(it, id) }

    /** Runs a worker until the calling coroutine is cancelled. */
    public suspend fun runWorker(options: WorkerOptions = WorkerOptions()) {
        Worker(workerId, store, connections, registry, clock, options).run()
    }

    /** Starts a worker in [scope] and returns its job; cancel the job to stop. */
    public fun startWorker(
        scope: CoroutineScope,
        options: WorkerOptions = WorkerOptions(),
    ): Job = scope.launch { runWorker(options) }

    private fun requireRegistered(type: TaskType<*>) {
        require(type.name in registry.typeNames) { "task type '${type.name}' is not registered" }
    }

    /** Collects what a [Pendenz] needs. Obtain one through [Pendenz.create]. */
    public class Builder internal constructor() {
        private var connections: ConnectionProvider? = null
        private var store: TaskStore? = null
        private var table: TaskTable = TaskTable()
        private var clock: Clock = Clock.systemUTC()
        private var workerId: String = defaultWorkerId()
        private val registrations = LinkedHashMap<String, TaskRegistry.Registration<*>>()

        /** Use this data source, one connection and transaction per operation. */
        public fun dataSource(dataSource: DataSource): Builder =
            apply { connections = DataSourceConnectionProvider(dataSource) }

        /** Use connections from an existing transaction manager. */
        public fun connectionProvider(provider: ConnectionProvider): Builder = apply { connections = provider }

        /** Map onto this table instead of the default `pendenz_task`. */
        public fun table(table: TaskTable): Builder = apply { this.table = table }

        /** Replace the storage implementation entirely. */
        public fun store(store: TaskStore): Builder = apply { this.store = store }

        /** Use this clock, for tests or for a time source other than the system's. */
        public fun clock(clock: Clock): Builder = apply { this.clock = clock }

        /** Name under which this process locks tasks; defaults to host and a random suffix. */
        public fun workerId(workerId: String): Builder = apply { this.workerId = workerId }

        /** Register a task type with the handler that runs it. */
        public fun <P : Any> register(
            type: TaskType<P>,
            handler: TaskHandler<P>,
        ): Builder =
            apply {
                require(registrations.put(type.name, TaskRegistry.Registration(type, handler)) == null) {
                    "task type '${type.name}' is registered twice"
                }
            }

        public fun build(): Pendenz {
            val connections = requireNotNull(connections) { "a dataSource or connectionProvider is required" }
            return Pendenz(
                store = store ?: JdbcTaskStore(table),
                connections = connections,
                registry = TaskRegistry(registrations.toMap()),
                clock = clock,
                workerId = workerId,
            )
        }

        private fun defaultWorkerId(): String {
            val host = runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("unknown-host")
            return "$host-${UUID.randomUUID().toString().take(RANDOM_SUFFIX_LENGTH)}"
        }

        private companion object {
            const val RANDOM_SUFFIX_LENGTH = 8
        }
    }

    public companion object {
        /** Builds a queue: `Pendenz.create { dataSource(ds); register(type, handler) }`. */
        public fun create(configure: Builder.() -> Unit): Pendenz = Builder().apply(configure).build()
    }
}
