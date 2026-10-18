package io.github.d4niella.pendenz

import io.github.d4niella.pendenz.store.ClaimedTask
import io.github.d4niella.pendenz.store.Outcome
import io.github.d4niella.pendenz.store.TaskStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant

/**
 * The poll-claim-run loop. One instance per worker; several workers on several
 * JVMs may share one table, `SKIP LOCKED` keeps them from colliding.
 */
internal class Worker(
    private val workerId: String,
    private val store: TaskStore,
    private val connections: ConnectionProvider,
    private val registry: TaskRegistry,
    private val clock: Clock,
    private val options: WorkerOptions,
) {
    private val log = LoggerFactory.getLogger(Worker::class.java)
    private val permits = Semaphore(options.parallelism)

    /** Runs until the coroutine is cancelled. A database hiccup is logged and retried, not fatal. */
    suspend fun run(): Unit =
        coroutineScope {
            log.info("worker {} started for task types {}", workerId, registry.typeNames)
            while (isActive) {
                val claimed = pollSafely()
                if (claimed.isEmpty()) {
                    delay(options.pollInterval.toMillis())
                    continue
                }
                claimed.forEach { task ->
                    launch { permits.withPermit { executeSafely(task) } }
                }
            }
        }

    /** One pass: promote due tasks, then claim as many as there are free permits. */
    internal suspend fun pollOnce(): List<ClaimedTask> {
        val free = permits.availablePermits
        if (free == 0) return emptyList()
        val now = clock.instant()
        return connections.inTransaction { connection ->
            store.promoteDue(connection, now, now.minus(options.lockTimeout))
            store.claim(connection, workerId, registry.typeNames, free, now)
        }
    }

    // The worker must outlive any single failure, so it catches everything but cancellation.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun pollSafely(): List<ClaimedTask> =
        try {
            pollOnce()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("worker {} could not poll, retrying after {}", workerId, options.pollInterval, e)
            emptyList()
        }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun executeSafely(task: ClaimedTask) {
        try {
            execute(task)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("worker {} could not record the outcome of task {}", workerId, task.id, e)
        }
    }

    /**
     * Runs the handler with a heartbeat beside it. If the heartbeat finds that
     * the row moved on without us, for example because an operator cancelled
     * the task with SQL, the handler is cancelled and its outcome dropped.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun execute(task: ClaimedTask) =
        supervisorScope {
            var takenAway = false
            val handler = async { withTimeout(options.executionTimeout.toMillis()) { registry.run(task) } }
            val heartbeat =
                launch {
                    while (isActive) {
                        delay(options.heartbeatInterval.toMillis())
                        val alive =
                            connections.inTransaction { store.heartbeat(it, task.id, task.version, clock.instant()) }
                        if (!alive) {
                            log.warn("task {} moved on without worker {}, cancelling its handler", task.id, workerId)
                            takenAway = true
                            handler.cancel()
                            break
                        }
                    }
                }
            val outcome =
                try {
                    handler.await()
                    Outcome.Succeeded
                } catch (e: TimeoutCancellationException) {
                    outcomeFor(task, e)
                } catch (e: CancellationException) {
                    heartbeat.cancel()
                    if (takenAway) return@supervisorScope
                    throw e
                } catch (e: Exception) {
                    outcomeFor(task, e)
                }
            heartbeat.cancel()
            val applied = connections.inTransaction { store.finish(it, task.id, task.version, outcome) }
            if (!applied) {
                log.warn("task {} was changed while running, dropping outcome {}", task.id, outcome::class.simpleName)
            }
        }

    private fun outcomeFor(
        task: ClaimedTask,
        cause: Throwable,
    ): Outcome {
        val error = cause.toString()
        val next = registry.retryPolicy(task.type).nextAttemptAt(task.attempt, clock.instant(), cause)
        log.debug("task {} attempt {} failed: {}", task.id, task.attempt, error)
        return if (next == null) Outcome.Failed(error) else Outcome.Retry(next, error)
    }
}

/** What is registered: task types and their handlers, looked up by type name. */
internal class TaskRegistry(
    private val entries: Map<String, Registration<*>>,
) {
    val typeNames: Set<String> = entries.keys

    fun retryPolicy(type: String): RetryPolicy = registration(type).type.retryPolicy

    suspend fun run(task: ClaimedTask) {
        registration(task.type).run(task)
    }

    private fun registration(type: String): Registration<*> =
        entries[type] ?: error("no handler registered for task type '$type'")

    class Registration<P : Any>(
        val type: TaskType<P>,
        val handler: TaskHandler<P>,
    ) {
        suspend fun run(task: ClaimedTask) {
            val payload = type.codec.decode(task.payload)
            handler.handle(TaskContext(task.id, task.type, task.attempt), payload)
        }
    }
}

internal fun Instant.isDue(now: Instant): Boolean = !isAfter(now)
