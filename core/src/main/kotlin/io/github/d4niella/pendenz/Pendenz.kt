package io.github.d4niella.pendenz

import io.github.d4niella.pendenz.schema.CycleTable
import io.github.d4niella.pendenz.schema.ResourceLimitTable
import io.github.d4niella.pendenz.schema.TaskTable
import io.github.d4niella.pendenz.schema.payloadColumnsOf
import io.github.d4niella.pendenz.store.CycleRecord
import io.github.d4niella.pendenz.store.CycleStore
import io.github.d4niella.pendenz.store.InMemoryTaskStore
import io.github.d4niella.pendenz.store.JdbcTaskStore
import io.github.d4niella.pendenz.store.NewCycle
import io.github.d4niella.pendenz.store.NewTask
import io.github.d4niella.pendenz.store.ResourceLimitStore
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
import java.util.concurrent.atomic.AtomicBoolean
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
@Suppress("TooManyFunctions") // one method per thing the queue does: enqueue, find, run, cycles; the API dictates it
public class Pendenz<ID : Any> private constructor(
    private val store: TaskStore<ID>,
    private val registry: TaskRegistry<ID>,
    private val clock: Clock,
    private val workerId: String,
    private val listener: TaskListener<ID>,
) {
    /**
     * Enqueues a task in its own transaction.
     *
     * @param runAt when the task is due; now when omitted.
     * @param id the id to store the task under; generated when omitted.
     * @param idempotencyKey a key unique per task type. A repeat enqueue with the same key inserts nothing
     *   and returns the existing task's id; see ADR 0009.
     * @param createdBy who or what enqueues: a user, a system, an agent. Stored for operators and handlers.
     * @param lane the fairness group within the type, for example a tenant; single tasks leave it null.
     *   Lanes take turns at the workers, see ADR 0015.
     * @param priority higher runs first, strictly; 0 by default.
     * @param resource what the task runs against, for concurrency limits; the type's choice when omitted.
     *   See ADR 0017.
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
        lane: String? = null,
        priority: Int = 0,
        resource: String? = null,
    ): ID =
        store.transaction {
            insert(
                it,
                type,
                payload,
                Enqueue(runAt, id, idempotencyKey, createdBy, lane, priority, resource),
            )
        }

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
        lane: String? = null,
        priority: Int = 0,
        resource: String? = null,
    ): ID =
        insert(
            store.adopt(connection),
            type,
            payload,
            Enqueue(runAt, id, idempotencyKey, createdBy, lane, priority, resource),
        )

    /** The optional parts of an enqueue, bundled so that the two overloads share one insert. */
    @Suppress("LongParameterList")
    private inner class Enqueue(
        val runAt: Instant?,
        val id: ID?,
        val idempotencyKey: String?,
        val createdBy: String?,
        val lane: String?,
        val priority: Int,
        val resource: String?,
    )

    private fun <P : Any> insert(
        tx: StoreTransaction,
        type: TaskType<P>,
        payload: P,
        options: Enqueue,
    ): ID {
        requireRegistered(type)
        val now = clock.instant()
        val due = options.runAt ?: now
        val status = if (due.isDue(now)) TaskStatus.PENDING else TaskStatus.SCHEDULED
        val task =
            NewTask(
                id = options.id,
                type = type.name,
                payload = type.encode(payload),
                runAt = due,
                status = status,
                createdAt = now,
                idempotencyKey = options.idempotencyKey,
                createdBy = options.createdBy,
                payloadVersion = type.version,
                lane = options.lane,
                priority = options.priority,
                resource = options.resource ?: type.resource.resourceFor(payload),
            )
        return store.insert(tx, task).also { listener.onEnqueued(it, type.name) }
    }

    /** Reads a task back, for operations and tests. */
    public suspend fun find(id: ID): TaskRecord<ID>? = store.transaction { store.find(it, id) }

    private val cycles: CycleStore<ID>
        get() = checkNotNull(store.cycles) { "cycles are not enabled; call cycles() on the builder" }

    /**
     * Starts a cycle: inserts the cycle row and its tasks in one transaction
     * of its own. The tasks come from a query or a list, see [CycleTasks];
     * they share the cycle's id as their lane, [priority] and [resource].
     * See ADR 0016 and ADR 0017.
     *
     * @return the cycle's id.
     */
    @Suppress("LongParameterList") // one parameter per aspect of a cycle, by design
    public suspend fun <P : Any> startCycle(
        type: TaskType<P>,
        tasks: CycleTasks<P>,
        description: String? = null,
        createdBy: String? = null,
        priority: Int = 0,
        id: ID? = null,
        resource: CycleResource = CycleResource.Default,
    ): ID = store.transaction { startCycle(it, type, tasks, description, createdBy, priority, id, resource) }

    /** Starts a cycle inside the caller's transaction; the query runs there too. */
    @Suppress("LongParameterList")
    public fun <P : Any> startCycle(
        connection: Connection,
        type: TaskType<P>,
        tasks: CycleTasks<P>,
        description: String? = null,
        createdBy: String? = null,
        priority: Int = 0,
        id: ID? = null,
        resource: CycleResource = CycleResource.Default,
    ): ID = startCycle(store.adopt(connection), type, tasks, description, createdBy, priority, id, resource)

    @Suppress("LongParameterList")
    private fun <P : Any> startCycle(
        tx: StoreTransaction,
        type: TaskType<P>,
        tasks: CycleTasks<P>,
        description: String?,
        createdBy: String?,
        priority: Int,
        id: ID?,
        resource: CycleResource,
    ): ID {
        requireRegistered(type)
        val now = clock.instant()
        val cycleId = cycles.insertCycle(tx, NewCycle(id, type.name, description, now, createdBy))
        val lane = cycleId.toString()
        when (tasks) {
            is CycleTasks.Query -> {
                val fixed =
                    NewTask<ID>(
                        id = null,
                        type = type.name,
                        payload = emptyMap(),
                        runAt = now,
                        status = TaskStatus.PENDING,
                        createdAt = now,
                        createdBy = createdBy,
                        payloadVersion = type.version,
                        lane = lane,
                        priority = priority,
                        resource = (resource as? CycleResource.Fixed)?.name,
                    )
                cycles.insertTasksFromQuery(tx, tasks.sql, lane, fixed, (resource as? CycleResource.Expression)?.sql)
            }

            is CycleTasks.Payloads -> {
                val own =
                    when (resource) {
                        CycleResource.Default -> {
                            null
                        }

                        is CycleResource.Fixed -> {
                            resource.name
                        }

                        is CycleResource.Expression -> {
                            throw UnsupportedOperationException("a SQL expression needs a query; use a fixed resource")
                        }
                    }
                val options = Enqueue(null, null, null, createdBy, lane, priority, own)
                tasks.payloads.forEach { insert(tx, type, it, options) }
            }
        }
        return cycleId
    }

    private val limits: ResourceLimitStore
        get() =
            checkNotNull(
                store.resourceLimits,
            ) { "resource limits are not enabled; call resourceLimits() on the builder" }

    /** Sets how many tasks of [name], an exact resource or a `LIKE` pattern, may run at once. See ADR 0017. */
    public suspend fun limitResource(
        name: String,
        maxRunning: Int,
    ): Unit = store.transaction { limits.setLimit(it, ResourceLimit(name, maxRunning)) }

    /** Removes the limit for [name]; returns whether there was one. */
    public suspend fun removeResourceLimit(name: String): Boolean = store.transaction { limits.removeLimit(it, name) }

    /** Every resource limit, by name. */
    public suspend fun resourceLimits(): List<ResourceLimit> = store.transaction { limits.limits(it) }

    private val seeded = AtomicBoolean()

    /** Seeds the limits the task types declare, once, where the table has no row yet. */
    private suspend fun seedResourceLimits() {
        val limits = store.resourceLimits ?: return
        if (!seeded.compareAndSet(false, true)) return
        val defaults = registry.types.mapNotNull { it.resource.defaultLimit }
        if (defaults.isNotEmpty()) store.transaction { tx -> defaults.forEach { limits.seedLimit(tx, it) } }
    }

    /** Reads a cycle back. */
    public suspend fun findCycle(id: ID): CycleRecord<ID>? = store.transaction { cycles.findCycle(it, id) }

    /** How many tasks of the cycle are in each status; statuses without tasks are absent. */
    public suspend fun cycleProgress(id: ID): Map<TaskStatus, Int> =
        store.transaction { cycles.countLane(it, id.toString()) }

    /**
     * Pauses an ACTIVE cycle: its waiting tasks are set aside as QUARANTINED,
     * running ones finish. Returns false when the cycle is not ACTIVE.
     */
    public suspend fun pauseCycle(id: ID): Boolean =
        store.transaction { tx ->
            val paused = cycles.updateCycleStatus(tx, id, CycleStatus.ACTIVE, CycleStatus.PAUSED, clock.instant())
            if (paused) cycles.quarantineLane(tx, id.toString(), CycleStore.PAUSED_REASON)
            paused
        }

    /** Resumes a PAUSED cycle: the tasks the pause set aside wait again. Returns false when it is not PAUSED. */
    public suspend fun resumeCycle(id: ID): Boolean =
        store.transaction { tx ->
            val now = clock.instant()
            val resumed = cycles.updateCycleStatus(tx, id, CycleStatus.PAUSED, CycleStatus.ACTIVE, now)
            if (resumed) cycles.releaseLane(tx, id.toString(), CycleStore.PAUSED_REASON, now)
            resumed
        }

    /** Cancels an ACTIVE or PAUSED cycle and every task of it that is not final. Returns false otherwise. */
    public suspend fun cancelCycle(id: ID): Boolean =
        store.transaction { tx ->
            val now = clock.instant()
            val from = cycles.findCycle(tx, id)?.status?.takeIf { !it.isFinal } ?: return@transaction false
            val cancelled = cycles.updateCycleStatus(tx, id, from, CycleStatus.CANCELLED, now)
            if (cancelled) cycles.cancelLane(tx, id.toString(), now)
            cancelled
        }

    /** Runs a worker until the calling coroutine is cancelled. */
    public suspend fun runWorker(options: WorkerOptions = WorkerOptions()) {
        seedResourceLimits()
        Worker(workerId, store, registry, clock, options, listener).run()
    }

    /**
     * Runs every due task in the calling coroutine, one after the other, until
     * nothing is due, and returns how many ran. Outcomes are recorded as a
     * worker would record them; a throwing handler does not throw here.
     *
     * Meant for tests, with [Builder.inMemory] and a controlled [Clock], and
     * for driving the queue from a scheduler of your own instead of a worker.
     */
    public suspend fun runPending(options: WorkerOptions = WorkerOptions()): Int {
        seedResourceLimits()
        return Worker(workerId, store, registry, clock, options, listener).runPending()
    }

    /**
     * Applies the retention rule of every registered task type once and
     * returns how many rows were deleted or archived. Workers do this on
     * their own every [WorkerOptions.maintenanceInterval]; see ADR 0011.
     */
    public suspend fun runMaintenance(options: WorkerOptions = WorkerOptions()): Int =
        Maintenance(store, registry, clock, options, listener).run()

    /** Starts a worker in [scope] and returns its job; cancel the job to stop. */
    public fun startWorker(
        scope: CoroutineScope,
        options: WorkerOptions = WorkerOptions(),
    ): Job = scope.launch { runWorker(options) }

    private fun requireRegistered(type: TaskType<*>) {
        require(type.name in registry.typeNames) { "task type '${type.name}' is not registered" }
    }

    /** Collects what a [Pendenz] needs. Obtain one through [Pendenz.create]. */
    @Suppress("TooManyFunctions") // one method per thing that can be configured, by design
    public class Builder<ID : Any> internal constructor(
        private val table: TaskTable<ID>,
    ) {
        private var connections: ConnectionProvider? = null
        private var store: TaskStore<ID>? = null
        private var cycleTable: CycleTable<ID>? = null
        private var limitTable: ResourceLimitTable? = null
        private var clock: Clock = Clock.systemUTC()
        private var workerId: String = defaultWorkerId()
        private val registrations = LinkedHashMap<String, TaskRegistry.Registration<ID, *>>()
        private val listeners = ArrayList<TaskListener<ID>>()

        /** Use this data source, one connection and transaction per operation. */
        public fun dataSource(dataSource: DataSource): Builder<ID> =
            apply { connections = DataSourceConnectionProvider(dataSource) }

        /** Use connections from an existing transaction manager. */
        public fun connectionProvider(provider: ConnectionProvider): Builder<ID> = apply { connections = provider }

        /** Replace the storage implementation entirely; no data source is needed then. */
        public fun store(store: TaskStore<ID>): Builder<ID> = apply { this.store = store }

        /** Keep tasks in memory instead of a database, for tests. See [InMemoryTaskStore]. */
        public fun inMemory(): Builder<ID> = store(InMemoryTaskStore(table.idType))

        /** Enable concurrency limits per resource, kept in [limitTable]; `pendenz_resource_limit` by default. */
        @JvmOverloads
        public fun resourceLimits(limitTable: ResourceLimitTable = ResourceLimitTable()): Builder<ID> =
            apply { this.limitTable = limitTable }

        /** Enable cycles, kept in [cycleTable]; the default table is `pendenz_cycle` with the task table's id type. */
        @JvmOverloads
        public fun cycles(cycleTable: CycleTable<ID> = CycleTable(table.idType)): Builder<ID> =
            apply { this.cycleTable = cycleTable }

        /** Use this clock, for tests or for a time source other than the system's. */
        public fun clock(clock: Clock): Builder<ID> = apply { this.clock = clock }

        /** Name under which this process locks tasks; defaults to host and a random suffix. */
        public fun workerId(workerId: String): Builder<ID> = apply { this.workerId = workerId }

        /** Add a hook for metrics or logs; may be called several times. See [TaskListener]. */
        public fun listener(listener: TaskListener<ID>): Builder<ID> = apply { listeners += listener }

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
            val types = registrations.values.map { it.type }
            val store =
                store ?: JdbcTaskStore(
                    table,
                    requireNotNull(connections) { "a dataSource, connectionProvider or store is required" },
                    payloadColumnsOf(types),
                    cycleTable = cycleTable,
                    limitTable = limitTable,
                )
            return Pendenz(
                store = store,
                registry = TaskRegistry(registrations.toMap()),
                clock = clock,
                workerId = workerId,
                listener = Listeners(listeners.toList()),
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
        ): Pendenz<ID> = builder(table).apply(configure).build()

        /** A builder for [table], for callers that cannot pass a Kotlin lambda. */
        @JvmStatic
        public fun <ID : Any> builder(table: TaskTable<ID>): Builder<ID> = Builder(table)
    }
}
