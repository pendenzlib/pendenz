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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
@Suppress("TooManyFunctions") // one method per thing the queue does: enqueue, find, run, cycles; the API dictates it
public class Pendenz<ID : Any> private constructor(
    private val store: TaskStore<ID>,
    private val registry: TaskRegistry<ID>,
    private val clock: Clock,
    private val workerId: String,
    private val listener: TaskListener<ID>,
    private val permissions: Permissions,
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
     *   Lanes starting with [CycleLane.PREFIX] belong to cycles and are refused.
     *   Lanes take turns at the workers, see ADR 0015.
     * @param priority higher runs first, strictly; 0 by default.
     * @param resource what the task runs against, for concurrency limits; the type's choice when omitted.
     *   See ADR 0017.
     * @param correlationId ties the task and its follow-ups together; see ADR 0019.
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
        correlationId: String? = null,
    ): ID =
        store.transaction {
            val spec = NewTaskSpec(runAt, id, idempotencyKey, createdBy, lane, priority, resource, null, correlationId)
            insert(it, type, payload, spec)
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
        correlationId: String? = null,
    ): ID {
        val spec = NewTaskSpec(runAt, id, idempotencyKey, createdBy, lane, priority, resource, null, correlationId)
        return insert(store.adopt(connection), type, payload, spec)
    }

    private fun <P : Any> insert(
        tx: StoreTransaction,
        type: TaskType<P>,
        payload: P,
        spec: NewTaskSpec<ID>,
    ): ID {
        requireRegistered(type)
        require(!CycleLane.isCycleLane(spec.lane)) { "lane '${spec.lane}' belongs to a cycle; start a cycle instead" }
        permit(spec.createdBy, Action.ENQUEUE, type.name)
        return insertPermitted(tx, type, payload, spec)
    }

    /** Stores one task; the caller has checked the permission, ENQUEUE for a single task, START_CYCLE for a cycle. */
    private fun <P : Any> insertPermitted(
        tx: StoreTransaction,
        type: TaskType<P>,
        payload: P,
        spec: NewTaskSpec<ID>,
    ): ID {
        val task = type.newTask(payload, spec, clock.instant())
        return store.insert(tx, task).also {
            listener.onEnqueued(it, type.name)
            if (task.status == TaskStatus.AWAITING_APPROVAL) listener.onAwaitingApproval(it, type.name)
        }
    }

    private fun permit(
        actor: String?,
        action: Action,
        type: String,
    ) {
        if (!permissions.allows(actor, action, type)) throw NotPermittedException(actor, action, type)
    }

    /**
     * Approves a task that waits for approval: records [actor] and moves it
     * on to run. Returns false when the task is not AWAITING_APPROVAL.
     *
     * @throws NotPermittedException when [actor] created the task, or may not approve its type. See ADR 0018.
     */
    public suspend fun approve(
        id: ID,
        actor: String,
    ): Boolean =
        store.transaction { tx ->
            val task = store.find(tx, id) ?: return@transaction false
            permit(actor, Action.APPROVE, task.type)
            if (task.createdBy == actor) {
                throw NotPermittedException(actor, Action.APPROVE, task.type, "the creator may not approve")
            }
            store.approve(tx, id, actor, clock.instant()).also { if (it) listener.onApproved(id, task.type, actor) }
        }

    /** Declines a task that waits for approval: CANCELLED with [reason]. Returns false when it is not waiting. */
    public suspend fun decline(
        id: ID,
        actor: String,
        reason: String,
    ): Boolean =
        store.transaction { tx ->
            val task = store.find(tx, id) ?: return@transaction false
            permit(actor, Action.DECLINE, task.type)
            val declined =
                store.move(tx, id, setOf(TaskStatus.AWAITING_APPROVAL), TaskStatus.CANCELLED, reason, clock.instant())
            if (declined) listener.onDeclined(id, task.type, actor, reason)
            declined
        }

    /** Restarts a task that waits for a human: back to PENDING, attempts kept. Returns whether it changed. */
    public suspend fun restart(
        id: ID,
        actor: String? = null,
    ): Boolean = operate(id, actor, Action.RESTART, TaskStatusTransitions.restartable, TaskStatus.PENDING, null)

    /** Marks a task that waits for a human as SUCCEEDED without running it. Returns whether it changed. */
    public suspend fun complete(
        id: ID,
        actor: String? = null,
    ): Boolean = operate(id, actor, Action.COMPLETE, TaskStatusTransitions.completable, TaskStatus.SUCCEEDED, null)

    /** Sets a task aside as QUARANTINED with [reason] until a human decides. Returns whether it changed. */
    public suspend fun quarantine(
        id: ID,
        reason: String,
        actor: String? = null,
    ): Boolean =
        operate(id, actor, Action.QUARANTINE, TaskStatusTransitions.quarantinable, TaskStatus.QUARANTINED, reason)

    /** Cancels a task in any non-final state; a running one is cancelled at its next heartbeat. */
    public suspend fun cancel(
        id: ID,
        actor: String? = null,
    ): Boolean = operate(id, actor, Action.CANCEL, TaskStatusTransitions.cancellable, TaskStatus.CANCELLED, null)

    @Suppress("LongParameterList")
    private suspend fun operate(
        id: ID,
        actor: String?,
        action: Action,
        from: Set<TaskStatus>,
        to: TaskStatus,
        reason: String?,
    ): Boolean =
        store.transaction { tx ->
            val task = store.find(tx, id) ?: return@transaction false
            permit(actor, action, task.type)
            store.move(tx, id, from, to, reason, clock.instant())
        }

    /** Reads a task back, for operations and tests. */
    public suspend fun find(id: ID): TaskRecord<ID>? = store.readTransaction { store.find(it, id) }

    /** The tasks matching [query], newest first, for screens, agents and tests. */
    public suspend fun tasks(query: TaskQuery = TaskQuery()): List<TaskRecord<ID>> =
        store.readTransaction { store.search(it, query) }

    /**
     * What waits in the table, one [Backlog] per task type with rows that
     * are not final: counts per status and the oldest due run time. One
     * grouped query; meant for metrics and screens, not for every request.
     */
    public suspend fun backlog(): List<Backlog> = store.readTransaction { store.backlog(it, clock.instant()) }

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
        correlationId: String? = null,
    ): ID =
        store.transaction {
            startCycle(it, type, tasks, CycleSpec(description, createdBy, priority, id, resource, correlationId))
        }

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
        correlationId: String? = null,
    ): ID =
        startCycle(
            store.adopt(connection),
            type,
            tasks,
            CycleSpec(description, createdBy, priority, id, resource, correlationId),
        )

    /** The optional parts of a cycle start, bundled so that the two overloads share one implementation. */
    @Suppress("LongParameterList")
    private inner class CycleSpec(
        val description: String?,
        val createdBy: String?,
        val priority: Int,
        val id: ID?,
        val resource: CycleResource,
        val correlationId: String?,
    )

    private fun <P : Any> startCycle(
        tx: StoreTransaction,
        type: TaskType<P>,
        tasks: CycleTasks<P>,
        spec: CycleSpec,
    ): ID {
        requireRegistered(type)
        permit(spec.createdBy, Action.START_CYCLE, type.name)
        val now = clock.instant()
        val cycleId = cycles.insertCycle(tx, NewCycle(spec.id, type.name, spec.description, now, spec.createdBy))
        val lane = CycleLane.of(cycleId)
        when (tasks) {
            is CycleTasks.Query -> {
                val approval = type.approval.requires(spec.createdBy, null)
                val fixed =
                    NewTask<ID>(
                        id = null,
                        type = type.name,
                        payload = emptyMap(),
                        runAt = now,
                        status = if (approval) TaskStatus.AWAITING_APPROVAL else TaskStatus.PENDING,
                        createdAt = now,
                        createdBy = spec.createdBy,
                        payloadVersion = type.version,
                        lane = lane,
                        priority = spec.priority,
                        resource =
                            (spec.resource as? CycleResource.Fixed)?.name
                                ?: (type.resource as? TaskResource.Fixed)?.name,
                        correlationId = spec.correlationId,
                    )
                val expression = (spec.resource as? CycleResource.Expression)?.sql
                cycles.insertTasksFromQuery(tx, tasks.sql, type.payload.columns, lane, fixed, expression)
            }

            is CycleTasks.Payloads -> {
                val own =
                    when (val resource = spec.resource) {
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
                val taskSpec =
                    NewTaskSpec<ID>(
                        createdBy = spec.createdBy,
                        lane = lane,
                        priority = spec.priority,
                        resource = own,
                        correlationId = spec.correlationId,
                    )
                tasks.payloads.forEach { insertPermitted(tx, type, it, taskSpec) }
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
    public suspend fun resourceLimits(): List<ResourceLimit> = store.readTransaction { limits.limits(it) }

    @Volatile
    private var seeded = false
    private val seeding = Mutex()

    /**
     * Seeds the limits the task types declare where the table has no row yet,
     * once it has worked: a failed attempt is tried again by the next start.
     */
    private suspend fun seedResourceLimits() {
        val limits = store.resourceLimits ?: return
        if (seeded) return
        seeding.withLock {
            if (seeded) return
            val defaults = registry.types.mapNotNull { it.resource.defaultLimit }
            if (defaults.isNotEmpty()) store.transaction { tx -> defaults.forEach { limits.seedLimit(tx, it) } }
            seeded = true
        }
    }

    /** Whether [type] is the very instance registered under its name; only that one may enqueue (ADR 0018). */
    public fun isRegistered(type: TaskType<*>): Boolean = registry.isRegistered(type)

    /** Reads a cycle back. */
    public suspend fun findCycle(id: ID): CycleRecord<ID>? = store.readTransaction { cycles.findCycle(it, id) }

    /** How many tasks of the cycle are in each status; statuses without tasks are absent. */
    public suspend fun cycleProgress(id: ID): Map<TaskStatus, Int> =
        store.readTransaction { cycles.countLane(it, CycleLane.of(id)) }

    /**
     * Pauses an ACTIVE cycle: its waiting tasks are set aside as QUARANTINED,
     * running ones finish. Returns false when the cycle is not ACTIVE.
     */
    public suspend fun pauseCycle(
        id: ID,
        actor: String? = null,
    ): Boolean =
        store.transaction { tx ->
            permitCycle(tx, id, actor, Action.PAUSE_CYCLE) ?: return@transaction false
            val paused = cycles.updateCycleStatus(tx, id, CycleStatus.ACTIVE, CycleStatus.PAUSED, clock.instant())
            if (paused) cycles.quarantineLane(tx, CycleLane.of(id), CycleStore.PAUSED_REASON)
            paused
        }

    /** Resumes a PAUSED cycle: the tasks the pause set aside wait again. Returns false when it is not PAUSED. */
    public suspend fun resumeCycle(
        id: ID,
        actor: String? = null,
    ): Boolean =
        store.transaction { tx ->
            permitCycle(tx, id, actor, Action.RESUME_CYCLE) ?: return@transaction false
            val now = clock.instant()
            val resumed = cycles.updateCycleStatus(tx, id, CycleStatus.PAUSED, CycleStatus.ACTIVE, now)
            if (resumed) cycles.releaseLane(tx, CycleLane.of(id), CycleStore.PAUSED_REASON, now)
            resumed
        }

    /** Cancels an ACTIVE or PAUSED cycle and every task of it that is not final. Returns false otherwise. */
    public suspend fun cancelCycle(
        id: ID,
        actor: String? = null,
    ): Boolean =
        store.transaction { tx ->
            val cycle = permitCycle(tx, id, actor, Action.CANCEL_CYCLE) ?: return@transaction false
            val now = clock.instant()
            val from = cycle.status.takeIf { !it.isFinal } ?: return@transaction false
            val cancelled = cycles.updateCycleStatus(tx, id, from, CycleStatus.CANCELLED, now)
            if (cancelled) cycles.cancelLane(tx, CycleLane.of(id), now)
            cancelled
        }

    /**
     * Approves every task of an ACTIVE cycle that waits for approval, in one
     * statement, and returns how many; a paused or final cycle gets 0. The
     * approver may not be the cycle's creator.
     */
    public suspend fun approveCycle(
        id: ID,
        actor: String,
    ): Int =
        store.transaction { tx ->
            val cycle = permitCycle(tx, id, actor, Action.APPROVE_CYCLE) ?: return@transaction 0
            if (cycle.status != CycleStatus.ACTIVE) return@transaction 0
            if (cycle.createdBy == actor) {
                throw NotPermittedException(actor, Action.APPROVE_CYCLE, cycle.type, "the creator may not approve")
            }
            store.approveLane(tx, CycleLane.of(id), cycle.type, actor, clock.instant())
        }

    /** The cycle, after checking [actor] may do [action] to its type; null when there is no such cycle. */
    private fun permitCycle(
        tx: StoreTransaction,
        id: ID,
        actor: String?,
        action: Action,
    ): CycleRecord<ID>? {
        val cycle = cycles.findCycle(tx, id) ?: return null
        permit(actor, action, cycle.type)
        return cycle
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
     * their own every [WorkerOptions.maintenanceInterval], unless it is zero; see ADR 0011 and ADR 0029.
     */
    public suspend fun runMaintenance(options: WorkerOptions = WorkerOptions()): Int =
        Maintenance(store, registry, clock, options, listener).run()

    /** Starts a worker in [scope] and returns its job; cancel the job to stop. */
    public fun startWorker(
        scope: CoroutineScope,
        options: WorkerOptions = WorkerOptions(),
    ): Job = scope.launch { runWorker(options) }

    private fun requireRegistered(type: TaskType<*>) = registry.requireRegistered(type)

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
        private var permissions: Permissions = Permissions.ALLOW_ALL

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

        /** Who may do what, checked in the API before anything is written; everyone may, by default. See ADR 0018. */
        public fun permissions(permissions: Permissions): Builder<ID> = apply { this.permissions = permissions }

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

        /** Register a task type with a handler that produces a result, stored and validated as [result] says. */
        public fun <P : Any, R : Any> register(
            type: TaskType<P>,
            result: TaskResult<P, R>,
            handler: ResultHandler<ID, P, R>,
        ): Builder<ID> =
            apply {
                require(registrations.put(type.name, TaskRegistry.Registration(type, result, handler)) == null) {
                    "task type '${type.name}' is registered twice"
                }
            }

        public fun build(): Pendenz<ID> {
            val types = registrations.values.map { it.type }
            require(store == null || (cycleTable == null && limitTable == null)) {
                "cycles() and resourceLimits() configure the tables of the database store; " +
                    "with store() or inMemory() the store brings its own"
            }
            val store =
                store ?: JdbcTaskStore(
                    table,
                    requireNotNull(connections) { "a dataSource, connectionProvider or store is required" },
                    payloadColumnsOf(types),
                    cycleTable = cycleTable,
                    limitTable = limitTable,
                )
            val limited = types.filter { it.resource.defaultLimit != null }.map { it.name }
            require(limited.isEmpty() || store.resourceLimits != null) {
                "task types $limited declare a resource limit, but resource limits are not enabled; " +
                    "call resourceLimits()"
            }
            return Pendenz(
                store = store,
                registry = TaskRegistry(registrations.toMap()),
                clock = clock,
                workerId = workerId,
                listener = Listeners(listeners.toList()),
                permissions = permissions,
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
