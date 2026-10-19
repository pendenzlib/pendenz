package io.github.d4niella.pendenz

import io.github.d4niella.pendenz.schema.TaskTable
import io.github.d4niella.pendenz.store.InMemoryTaskStore
import io.github.d4niella.pendenz.store.JdbcTaskStore
import io.github.d4niella.pendenz.store.NewTask
import io.github.d4niella.pendenz.store.StoreTransaction
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
 * Everything replaceable is a method of the builder with a default: the
 * store, the connection provider, the clock, the worker id.
 *
 * @param ID the id type of the table, see [io.github.d4niella.pendenz.schema.IdType].
 */
public class Pendenz<ID : Any> private constructor(
    private val store: TaskStore<ID>,
    private val registry: TaskRegistry<ID>,
    private val clock: Clock,
    private val workerId: String,
) {
    /**
     * Enqueues a task in its own transaction.
     *
     * @param runAt when the task is due; now when omitted.
     * @param id the id to store the task under; generated when omitted.
     * @param idempotencyKey a key unique per task type. A repeat enqueue with the same key inserts nothing
     *   and returns the existing task's id; see ADR 0009.
     * @param createdBy who or what enqueues: a user, a system, an agent. Stored for operators and handlers.
     * @return the task's id.
     */
    @Suppress("LongParameterList") // one parameter per concept the caller may set, by design
    public suspend fun <P : Any> enqueue(
        type: TaskType<P>,
        payload: P,
        runAt: Instant? = null,
        id: ID? = null,
        idempotencyKey: String? = null,
        createdBy: String? = null,
    ): ID = store.transaction { insert(it, type, payload, runAt, id, idempotencyKey, createdBy) }

    /**
     * Enqueues a task inside the caller's transaction. The task becomes
     * visible to workers when that transaction commits, and disappears with
     * it on rollback, together with whatever business change caused it.
     */
    @Suppress("LongParameterList") // the connection plus what the suspend overload takes
    public fun <P : Any> enqueue(
        connection: Connection,
        type: TaskType<P>,
        payload: P,
        runAt: Instant? = null,
        id: ID? = null,
        idempotencyKey: String? = null,
        createdBy: String? = null,
    ): ID = insert(store.adopt(connection), type, payload, runAt, id, idempotencyKey, createdBy)

    @Suppress("LongParameterList")
    private fun <P : Any> insert(
        tx: StoreTransaction,
        type: TaskType<P>,
        payload: P,
        runAt: Instant?,
        id: ID?,
        idempotencyKey: String?,
        createdBy: String?,
    ): ID {
        requireRegistered(type)
        val now = clock.instant()
        val due = runAt ?: now
        val status = if (due.isDue(now)) TaskStatus.PENDING else TaskStatus.SCHEDULED
        val encoded = type.codec.encode(payload)
        val task = NewTask(id, type.name, encoded, due, status, now, idempotencyKey, createdBy, type.version)
        return store.insert(tx, task)
    }

    /** Reads a task back, for operations and tests. */
    public suspend fun find(id: ID): TaskRecord<ID>? = store.transaction { store.find(it, id) }

    /** Runs a worker until the calling coroutine is cancelled. */
    public suspend fun runWorker(options: WorkerOptions = WorkerOptions()) {
        Worker(workerId, store, registry, clock, options).run()
    }

    /**
     * Runs every due task in the calling coroutine, one after the other, until
     * nothing is due, and returns how many ran. Outcomes are recorded as a
     * worker would record them; a throwing handler does not throw here.
     *
     * Meant for tests, with [Builder.inMemory] and a controlled [Clock], and
     * for driving the queue from a scheduler of your own instead of a worker.
     */
    public suspend fun runPending(options: WorkerOptions = WorkerOptions()): Int =
        Worker(workerId, store, registry, clock, options).runPending()

    /**
     * Applies the retention rule of every registered task type once and
     * returns how many rows were deleted or archived. Workers do this on
     * their own every [WorkerOptions.maintenanceInterval]; see ADR 0011.
     */
    public suspend fun runMaintenance(options: WorkerOptions = WorkerOptions()): Int =
        Maintenance(store, registry, clock, options).run()

    /** Starts a worker in [scope] and returns its job; cancel the job to stop. */
    public fun startWorker(
        scope: CoroutineScope,
        options: WorkerOptions = WorkerOptions(),
    ): Job = scope.launch { runWorker(options) }

    private fun requireRegistered(type: TaskType<*>) {
        require(type.name in registry.typeNames) { "task type '${type.name}' is not registered" }
    }

    /** Collects what a [Pendenz] needs. Obtain one through [Pendenz.create]. */
    public class Builder<ID : Any> internal constructor(
        private val table: TaskTable<ID>,
    ) {
        private var connections: ConnectionProvider? = null
        private var store: TaskStore<ID>? = null
        private var clock: Clock = Clock.systemUTC()
        private var workerId: String = defaultWorkerId()
        private val registrations = LinkedHashMap<String, TaskRegistry.Registration<ID, *>>()

        /** Use this data source, one connection and transaction per operation. */
        public fun dataSource(dataSource: DataSource): Builder<ID> =
            apply { connections = DataSourceConnectionProvider(dataSource) }

        /** Use connections from an existing transaction manager. */
        public fun connectionProvider(provider: ConnectionProvider): Builder<ID> = apply { connections = provider }

        /** Replace the storage implementation entirely; no data source is needed then. */
        public fun store(store: TaskStore<ID>): Builder<ID> = apply { this.store = store }

        /** Keep tasks in memory instead of a database, for tests. See [InMemoryTaskStore]. */
        public fun inMemory(): Builder<ID> = store(InMemoryTaskStore(table.idType))

        /** Use this clock, for tests or for a time source other than the system's. */
        public fun clock(clock: Clock): Builder<ID> = apply { this.clock = clock }

        /** Name under which this process locks tasks; defaults to host and a random suffix. */
        public fun workerId(workerId: String): Builder<ID> = apply { this.workerId = workerId }

        /** Register a task type with the handler that runs it. */
        public fun <P : Any> register(
            type: TaskType<P>,
            handler: TaskHandler<ID, P>,
        ): Builder<ID> =
            apply {
                require(registrations.put(type.name, TaskRegistry.Registration(type, handler)) == null) {
                    "task type '${type.name}' is registered twice"
                }
            }

        public fun build(): Pendenz<ID> {
            val store =
                store ?: JdbcTaskStore(
                    table,
                    requireNotNull(connections) { "a dataSource, connectionProvider or store is required" },
                )
            return Pendenz(
                store = store,
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
        /** Builds a queue on the default table with `bigint` ids. */
        @JvmStatic
        public fun create(configure: Builder<Long>.() -> Unit): Pendenz<Long> = create(TaskTable(), configure)

        /** Builds a queue on [table], whose id type becomes the queue's. */
        @JvmStatic
        public fun <ID : Any> create(
            table: TaskTable<ID>,
            configure: Builder<ID>.() -> Unit,
        ): Pendenz<ID> = Builder(table).apply(configure).build()
    }
}
