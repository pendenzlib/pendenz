package io.github.d4niella.pendenz.javaapi

import io.github.d4niella.pendenz.Backlog
import io.github.d4niella.pendenz.CycleTasks
import io.github.d4niella.pendenz.Pendenz
import io.github.d4niella.pendenz.Permissions
import io.github.d4niella.pendenz.ResourceLimit
import io.github.d4niella.pendenz.TaskListener
import io.github.d4niella.pendenz.TaskQuery
import io.github.d4niella.pendenz.TaskResult
import io.github.d4niella.pendenz.TaskStatus
import io.github.d4niella.pendenz.TaskType
import io.github.d4niella.pendenz.WorkerOptions
import io.github.d4niella.pendenz.schema.CycleTable
import io.github.d4niella.pendenz.schema.ResourceLimitTable
import io.github.d4niella.pendenz.schema.TaskTable
import io.github.d4niella.pendenz.store.CycleRecord
import io.github.d4niella.pendenz.store.TaskRecord
import io.github.d4niella.pendenz.store.TaskStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.future.future
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import java.sql.Connection
import java.time.Clock
import java.util.Optional
import java.util.concurrent.CompletableFuture
import javax.sql.DataSource

/**
 * The queue for Java callers: the same [Pendenz] underneath, with blocking
 * methods and `CompletableFuture` instead of suspend functions, and
 * handlers that are plain blocking methods.
 *
 * Build one with [PendenzJava.builder], or put it in front of a queue
 * built elsewhere with [PendenzJava.wrap]. Close it to stop every worker it
 * started.
 */
@Suppress("TooManyFunctions") // one blocking method per queue operation, the queue dictates the count
public class PendenzJava<ID : Any> private constructor(
    private val pendenz: Pendenz<ID>,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Enqueues in a transaction of its own and blocks until the task is stored. */
    @JvmOverloads
    public fun <P : Any> enqueue(
        type: TaskType<P>,
        payload: P,
        options: EnqueueOptions<ID> = EnqueueOptions.none(),
    ): ID =
        runBlocking {
            pendenz.enqueue(
                type,
                payload,
                options.runAt,
                options.id,
                options.idempotencyKey,
                options.createdBy,
                options.lane,
                options.priority,
                options.resource,
                options.correlationId,
            )
        }

    /** Enqueues inside the caller's transaction; the caller commits. */
    @JvmOverloads
    public fun <P : Any> enqueue(
        connection: Connection,
        type: TaskType<P>,
        payload: P,
        options: EnqueueOptions<ID> = EnqueueOptions.none(),
    ): ID =
        pendenz.enqueue(
            connection,
            type,
            payload,
            options.runAt,
            options.id,
            options.idempotencyKey,
            options.createdBy,
            options.lane,
            options.priority,
            options.resource,
            options.correlationId,
        )

    /** Enqueues in a transaction of its own without blocking the caller. */
    @JvmOverloads
    public fun <P : Any> enqueueAsync(
        type: TaskType<P>,
        payload: P,
        options: EnqueueOptions<ID> = EnqueueOptions.none(),
    ): CompletableFuture<ID> =
        scope.future {
            pendenz.enqueue(
                type,
                payload,
                options.runAt,
                options.id,
                options.idempotencyKey,
                options.createdBy,
                options.lane,
                options.priority,
                options.resource,
                options.correlationId,
            )
        }

    /** Reads a task back, empty when there is none. */
    public fun find(id: ID): Optional<TaskRecord<ID>> = Optional.ofNullable(runBlocking { pendenz.find(id) })

    public fun findAsync(id: ID): CompletableFuture<Optional<TaskRecord<ID>>> =
        scope.future { Optional.ofNullable(pendenz.find(id)) }

    /** The tasks matching [query], newest first. See [Pendenz.tasks]. */
    @JvmOverloads
    public fun tasks(query: TaskQuery = TaskQuery()): List<TaskRecord<ID>> = runBlocking { pendenz.tasks(query) }

    /** What waits in the table, per task type. See [Pendenz.backlog]. */
    public fun backlog(): List<Backlog> = runBlocking { pendenz.backlog() }

    /** The Kotlin queue behind this facade, for modules such as `pendenz-mcp` that build on it. */
    public fun asKotlin(): Pendenz<ID> = pendenz

    /** Starts a cycle and blocks until its row and tasks are stored. See [Pendenz.startCycle]. */
    @JvmOverloads
    public fun <P : Any> startCycle(
        type: TaskType<P>,
        tasks: CycleTasks<P>,
        description: String? = null,
        createdBy: String? = null,
        priority: Int = 0,
    ): ID = runBlocking { pendenz.startCycle(type, tasks, description, createdBy, priority) }

    public fun findCycle(id: ID): Optional<CycleRecord<ID>> = Optional.ofNullable(runBlocking { pendenz.findCycle(id) })

    /** How many tasks of the cycle are in each status. */
    public fun cycleProgress(id: ID): Map<TaskStatus, Int> = runBlocking { pendenz.cycleProgress(id) }

    @JvmOverloads
    public fun pauseCycle(
        id: ID,
        actor: String? = null,
    ): Boolean = runBlocking { pendenz.pauseCycle(id, actor) }

    @JvmOverloads
    public fun resumeCycle(
        id: ID,
        actor: String? = null,
    ): Boolean = runBlocking { pendenz.resumeCycle(id, actor) }

    @JvmOverloads
    public fun cancelCycle(
        id: ID,
        actor: String? = null,
    ): Boolean = runBlocking { pendenz.cancelCycle(id, actor) }

    /** Approves a waiting task; the approver may not be the creator. See [Pendenz.approve]. */
    public fun approve(
        id: ID,
        actor: String,
    ): Boolean = runBlocking { pendenz.approve(id, actor) }

    public fun decline(
        id: ID,
        actor: String,
        reason: String,
    ): Boolean = runBlocking { pendenz.decline(id, actor, reason) }

    public fun approveCycle(
        id: ID,
        actor: String,
    ): Int = runBlocking { pendenz.approveCycle(id, actor) }

    @JvmOverloads
    public fun restart(
        id: ID,
        actor: String? = null,
    ): Boolean = runBlocking { pendenz.restart(id, actor) }

    @JvmOverloads
    public fun complete(
        id: ID,
        actor: String? = null,
    ): Boolean = runBlocking { pendenz.complete(id, actor) }

    @JvmOverloads
    public fun quarantine(
        id: ID,
        reason: String,
        actor: String? = null,
    ): Boolean = runBlocking { pendenz.quarantine(id, reason, actor) }

    @JvmOverloads
    public fun cancel(
        id: ID,
        actor: String? = null,
    ): Boolean = runBlocking { pendenz.cancel(id, actor) }

    /** Sets how many tasks of a resource, or of a `LIKE` pattern, may run at once. See [Pendenz.limitResource]. */
    public fun limitResource(
        name: String,
        maxRunning: Int,
    ): Unit = runBlocking { pendenz.limitResource(name, maxRunning) }

    public fun removeResourceLimit(name: String): Boolean = runBlocking { pendenz.removeResourceLimit(name) }

    public fun resourceLimits(): List<ResourceLimit> = runBlocking { pendenz.resourceLimits() }

    /** Runs every due task on the calling thread and returns how many ran. See [Pendenz.runPending]. */
    @JvmOverloads
    public fun runPending(options: WorkerOptions = WorkerOptions()): Int = runBlocking { pendenz.runPending(options) }

    /** Applies retention once and returns how many rows went. See [Pendenz.runMaintenance]. */
    @JvmOverloads
    public fun runMaintenance(options: WorkerOptions = WorkerOptions()): Int =
        runBlocking { pendenz.runMaintenance(options) }

    /** Starts a worker on this queue's own threads. Close the handle, or this queue, to stop it. */
    @JvmOverloads
    public fun startWorker(options: WorkerOptions = WorkerOptions()): WorkerHandle =
        WorkerHandle(pendenz.startWorker(scope, options))

    /** Stops every worker started here, waiting for the graceful shutdown of each. */
    override fun close() {
        runBlocking { scope.coroutineContext.job.cancelAndJoin() }
    }

    /** Collects what a [PendenzJava] needs; obtain one through [PendenzJava.builder]. */
    public class Builder<ID : Any> internal constructor(
        private val inner: Pendenz.Builder<ID>,
    ) {
        private var handlerDispatcher: CoroutineDispatcher = Dispatchers.IO

        public fun dataSource(dataSource: DataSource): Builder<ID> = apply { inner.dataSource(dataSource) }

        public fun store(store: TaskStore<ID>): Builder<ID> = apply { inner.store(store) }

        public fun inMemory(): Builder<ID> = apply { inner.inMemory() }

        /** Enable cycles in [cycleTable]. */
        public fun cycles(cycleTable: CycleTable<ID>): Builder<ID> = apply { inner.cycles(cycleTable) }

        /** Enable cycles in the default cycle table. */
        public fun cycles(): Builder<ID> = apply { inner.cycles() }

        /** Enable concurrency limits per resource in [limitTable]. */
        public fun resourceLimits(limitTable: ResourceLimitTable): Builder<ID> =
            apply { inner.resourceLimits(limitTable) }

        /** Enable concurrency limits per resource in the default table. */
        public fun resourceLimits(): Builder<ID> = apply { inner.resourceLimits() }

        public fun clock(clock: Clock): Builder<ID> = apply { inner.clock(clock) }

        public fun workerId(workerId: String): Builder<ID> = apply { inner.workerId(workerId) }

        public fun listener(listener: TaskListener<ID>): Builder<ID> = apply { inner.listener(listener) }

        /** Who may do what; everyone may, by default. */
        public fun permissions(permissions: Permissions): Builder<ID> = apply { inner.permissions(permissions) }

        /** Where blocking handlers run; `Dispatchers.IO` by default. */
        public fun handlerDispatcher(dispatcher: CoroutineDispatcher): Builder<ID> =
            apply { handlerDispatcher = dispatcher }

        /** Registers a task type with a blocking handler. */
        public fun <P : Any> register(
            type: TaskType<P>,
            handler: JavaTaskHandler<ID, P>,
        ): Builder<ID> = apply { inner.register(type, handler.asTaskHandler(handlerDispatcher)) }

        /** Registers a task type with a blocking handler that produces a result, stored and validated per [result]. */
        public fun <P : Any, R : Any> register(
            type: TaskType<P>,
            result: TaskResult<P, R>,
            handler: JavaResultHandler<ID, P, R>,
        ): Builder<ID> = apply { inner.register(type, result, handler.asResultHandler(handlerDispatcher)) }

        public fun build(): PendenzJava<ID> = PendenzJava(inner.build())
    }

    public companion object {
        /** A builder for the default table with `bigint` ids. */
        @JvmStatic
        public fun builder(): Builder<Long> = builder(TaskTable())

        /** A builder for [table], whose id type becomes the queue's. */
        @JvmStatic
        public fun <ID : Any> builder(table: TaskTable<ID>): Builder<ID> = Builder(Pendenz.builder(table))

        /**
         * The facade in front of [pendenz], a queue built with the Kotlin
         * builder or by a framework. Both then work on the same queue;
         * closing the facade stops only the workers started through it.
         */
        @JvmStatic
        public fun <ID : Any> wrap(pendenz: Pendenz<ID>): PendenzJava<ID> = PendenzJava(pendenz)
    }
}
