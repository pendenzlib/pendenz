package io.github.pendenzlib.javaapi

import io.github.pendenzlib.Backlog
import io.github.pendenzlib.ConnectionProvider
import io.github.pendenzlib.CycleRecord
import io.github.pendenzlib.CycleTasks
import io.github.pendenzlib.Pendenz
import io.github.pendenzlib.Permissions
import io.github.pendenzlib.ResourceLimit
import io.github.pendenzlib.Schedule
import io.github.pendenzlib.TaskListener
import io.github.pendenzlib.TaskQuery
import io.github.pendenzlib.TaskRecord
import io.github.pendenzlib.TaskResult
import io.github.pendenzlib.TaskStatus
import io.github.pendenzlib.TaskType
import io.github.pendenzlib.WorkerOptions
import io.github.pendenzlib.schema.CycleTable
import io.github.pendenzlib.schema.ResourceLimitTable
import io.github.pendenzlib.schema.TaskTable
import io.github.pendenzlib.store.TaskStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.future.future
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import java.sql.Connection
import java.time.Clock
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
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
    /** The workers' scope; closing the facade cancels it and so stops them. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The scope of `*Async` calls; closing does not cancel it, a call that started finishes. */
    private val calls = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var closed = false

    private fun checkOpen() = check(!closed) { "this PendenzJava is closed" }

    /**
     * Enqueues through the connection provider and blocks until the task is
     * stored: inside the caller's transaction when the provider joins one,
     * as `SpringConnectionProvider` does within `@Transactional`, otherwise
     * in a transaction of its own.
     */
    @JvmOverloads
    public fun <P : Any> enqueue(
        type: TaskType<P>,
        payload: P,
        options: EnqueueOptions<ID> = EnqueueOptions.defaults(),
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
        options: EnqueueOptions<ID> = EnqueueOptions.defaults(),
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

    /**
     * Enqueues without blocking the caller. The write runs on another thread,
     * so it never joins the caller's transaction, a Spring one included.
     */
    @JvmOverloads
    public fun <P : Any> enqueueAsync(
        type: TaskType<P>,
        payload: P,
        options: EnqueueOptions<ID> = EnqueueOptions.defaults(),
    ): CompletableFuture<ID> {
        checkOpen()
        return calls.future {
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
    }

    /** Reads a task back, empty when there is none. */
    public fun find(id: ID): Optional<TaskRecord<ID>> = Optional.ofNullable(runBlocking { pendenz.find(id) })

    public fun findAsync(id: ID): CompletableFuture<Optional<TaskRecord<ID>>> {
        checkOpen()
        return calls.future { Optional.ofNullable(pendenz.find(id)) }
    }

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
        options: CycleOptions<ID> = CycleOptions.defaults(),
    ): ID =
        runBlocking {
            with(
                options,
            ) { pendenz.startCycle(type, tasks, description, createdBy, priority, id, resource, correlationId) }
        }

    /** Starts a cycle inside the caller's transaction; the caller commits. */
    @JvmOverloads
    public fun <P : Any> startCycle(
        connection: Connection,
        type: TaskType<P>,
        tasks: CycleTasks<P>,
        options: CycleOptions<ID> = CycleOptions.defaults(),
    ): ID =
        with(options) {
            pendenz.startCycle(connection, type, tasks, description, createdBy, priority, id, resource, correlationId)
        }

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

    /** Sets how many tasks of a resource, or of a `LIKE` pattern, may run at once. See [Pendenz.setResourceLimit]. */
    public fun setResourceLimit(
        name: String,
        maxRunning: Int,
    ): Unit = runBlocking { pendenz.setResourceLimit(name, maxRunning) }

    public fun removeResourceLimit(name: String): Boolean = runBlocking { pendenz.removeResourceLimit(name) }

    public fun resourceLimits(): List<ResourceLimit> = runBlocking { pendenz.resourceLimits() }

    /**
     * Runs every due task and returns how many ran, blocking the calling
     * thread until they have; Java handlers run on their dispatcher, the IO
     * pool by default. See [Pendenz.runPending].
     */
    @JvmOverloads
    public fun runPending(options: WorkerOptions = WorkerOptions()): Int = runBlocking { pendenz.runPending(options) }

    /** Applies retention once and returns how many rows went. See [Pendenz.runMaintenance]. */
    @JvmOverloads
    public fun runMaintenance(options: WorkerOptions = WorkerOptions()): Int =
        runBlocking { pendenz.runMaintenance(options) }

    /** Starts a worker on this queue's own threads. Close the handle, or this queue, to stop it. */
    @JvmOverloads
    public fun startWorker(options: WorkerOptions = WorkerOptions()): WorkerHandle {
        checkOpen()
        return WorkerHandle(pendenz.startWorker(scope, options))
    }

    /**
     * Stops every worker started here, waiting for the graceful shutdown of
     * each. `*Async` calls already under way finish; new ones and new
     * workers are refused with [IllegalStateException].
     */
    override fun close() {
        closed = true
        runBlocking { scope.coroutineContext.job.cancelAndJoin() }
    }

    /** Collects what a [PendenzJava] needs; obtain one through [PendenzJava.builder]. */
    public class Builder<ID : Any> internal constructor(
        private val inner: Pendenz.Builder<ID>,
    ) {
        private var handlerDispatcher: CoroutineDispatcher = Dispatchers.IO

        /** Registrations wait for [build], so the dispatcher applies however late it is set. */
        private val registrations = mutableListOf<(CoroutineDispatcher) -> Unit>()

        public fun dataSource(dataSource: DataSource): Builder<ID> = apply { inner.dataSource(dataSource) }

        /** Use connections from an existing transaction manager, such as Spring's through `pendenz-spring`. */
        public fun connectionProvider(provider: ConnectionProvider): Builder<ID> =
            apply { inner.connectionProvider(provider) }

        /** Use connections from a transaction manager written in Java. */
        public fun connectionProvider(provider: BlockingConnectionProvider): Builder<ID> =
            apply { inner.connectionProvider(provider.asConnectionProvider()) }

        public fun store(store: TaskStore<ID>): Builder<ID> = apply { inner.store(store) }

        public fun inMemory(): Builder<ID> = apply { inner.inMemory() }

        /** Enable cycles in [cycleTable]. */
        public fun enableCycles(cycleTable: CycleTable<ID>): Builder<ID> = apply { inner.enableCycles(cycleTable) }

        /** Enable cycles in the default cycle table. */
        public fun enableCycles(): Builder<ID> = apply { inner.enableCycles() }

        /** Enable concurrency limits per resource in [limitTable]. */
        public fun enableResourceLimits(limitTable: ResourceLimitTable): Builder<ID> =
            apply { inner.enableResourceLimits(limitTable) }

        /** Enable concurrency limits per resource in the default table. */
        public fun enableResourceLimits(): Builder<ID> = apply { inner.enableResourceLimits() }

        public fun clock(clock: Clock): Builder<ID> = apply { inner.clock(clock) }

        public fun workerId(workerId: String): Builder<ID> = apply { inner.workerId(workerId) }

        public fun listener(listener: TaskListener<ID>): Builder<ID> = apply { inner.listener(listener) }

        /** Who may do what; everyone may, by default. */
        public fun permissions(permissions: Permissions): Builder<ID> = apply { inner.permissions(permissions) }

        /** Where blocking handlers run, also those registered before; the IO pool of the coroutines by default. */
        public fun handlerExecutor(executor: Executor): Builder<ID> =
            apply { handlerDispatcher = executor.asCoroutineDispatcher() }

        /** Registers a task type with a blocking handler. */
        public fun <P : Any> register(
            type: TaskType<P>,
            handler: JavaTaskHandler<ID, P>,
        ): Builder<ID> =
            apply {
                registrations +=
                    { dispatcher -> inner.register(type, handler.asTaskHandler(dispatcher)) }
            }

        /** Registers a task type with a blocking handler that produces a result, stored and validated per [result]. */
        public fun <P : Any, R : Any> register(
            type: TaskType<P>,
            result: TaskResult<P, R>,
            handler: JavaResultHandler<ID, P, R>,
        ): Builder<ID> =
            apply {
                registrations += { dispatcher ->
                    inner.register(type, result, handler.asResultHandler(dispatcher))
                }
            }

        /** Enqueues [payload] as a task of [type] at every fire time of [schedule]; see `Pendenz.Builder.schedule`. */
        public fun <P : Any> schedule(
            name: String,
            type: TaskType<P>,
            payload: P,
            schedule: Schedule,
        ): Builder<ID> = apply { inner.schedule(name, type, payload, schedule) }

        public fun build(): PendenzJava<ID> {
            registrations.forEach { it(handlerDispatcher) }
            registrations.clear()
            return PendenzJava(inner.build())
        }
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
