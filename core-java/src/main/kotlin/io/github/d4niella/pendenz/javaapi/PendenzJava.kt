package io.github.d4niella.pendenz.javaapi

import io.github.d4niella.pendenz.Pendenz
import io.github.d4niella.pendenz.TaskListener
import io.github.d4niella.pendenz.TaskType
import io.github.d4niella.pendenz.WorkerOptions
import io.github.d4niella.pendenz.schema.TaskTable
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
 * Build one with [PendenzJava.builder]. Close it to stop every worker it
 * started.
 */
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
            )
        }

    /** Reads a task back, empty when there is none. */
    public fun find(id: ID): Optional<TaskRecord<ID>> = Optional.ofNullable(runBlocking { pendenz.find(id) })

    public fun findAsync(id: ID): CompletableFuture<Optional<TaskRecord<ID>>> =
        scope.future { Optional.ofNullable(pendenz.find(id)) }

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

        public fun clock(clock: Clock): Builder<ID> = apply { inner.clock(clock) }

        public fun workerId(workerId: String): Builder<ID> = apply { inner.workerId(workerId) }

        public fun listener(listener: TaskListener<ID>): Builder<ID> = apply { inner.listener(listener) }

        /** Where blocking handlers run; `Dispatchers.IO` by default. */
        public fun handlerDispatcher(dispatcher: CoroutineDispatcher): Builder<ID> =
            apply { handlerDispatcher = dispatcher }

        /** Registers a task type with a blocking handler. */
        public fun <P : Any> register(
            type: TaskType<P>,
            handler: JavaTaskHandler<ID, P>,
        ): Builder<ID> = apply { inner.register(type, handler.asTaskHandler(handlerDispatcher)) }

        public fun build(): PendenzJava<ID> = PendenzJava(inner.build())
    }

    public companion object {
        /** A builder for the default table with `bigint` ids. */
        @JvmStatic
        public fun builder(): Builder<Long> = builder(TaskTable())

        /** A builder for [table], whose id type becomes the queue's. */
        @JvmStatic
        public fun <ID : Any> builder(table: TaskTable<ID>): Builder<ID> = Builder(Pendenz.builder(table))
    }
}
