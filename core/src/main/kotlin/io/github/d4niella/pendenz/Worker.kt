package io.github.d4niella.pendenz

import io.github.d4niella.pendenz.store.ClaimedTask
import io.github.d4niella.pendenz.store.Outcome
import io.github.d4niella.pendenz.store.TaskStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant

/**
 * The poll-claim-run loop. One instance per worker; several workers on several
 * JVMs may share one table, `SKIP LOCKED` keeps them from colliding.
 *
 * Handlers do not run as children of the loop. Cancelling the loop stops
 * claiming at once, but running handlers get [WorkerOptions.shutdownTimeout]
 * to finish before they are cancelled and their tasks released (ADR 0005).
 */
internal class Worker<ID : Any>(
    private val workerId: String,
    private val store: TaskStore<ID>,
    private val registry: TaskRegistry<ID>,
    private val clock: Clock,
    private val options: WorkerOptions,
) {
    private val log = LoggerFactory.getLogger(Worker::class.java)
    private val permits = Semaphore(options.parallelism)

    /** Runs until the coroutine is cancelled, then drains running handlers before returning. */
    suspend fun run() {
        val handlers = CoroutineScope(currentCoroutineContext().minusKey(Job) + SupervisorJob())
        log.info("worker {} started for task types {}", workerId, registry.typeNames)
        try {
            while (currentCoroutineContext().isActive) {
                val reserved = reservePermits()
                val claimed = pollSafely(reserved)
                repeat(reserved - claimed.size) { permits.release() }
                claimed.forEach { task ->
                    handlers.launch {
                        try {
                            executeSafely(task)
                        } finally {
                            permits.release()
                        }
                    }
                }
                if (claimed.isEmpty()) delay(options.pollInterval.toMillis())
            }
        } finally {
            withContext(NonCancellable) { shutdown(handlers) }
        }
    }

    /** One pass: promote due tasks, then claim up to [limit] tasks. */
    internal suspend fun pollOnce(limit: Int): List<ClaimedTask<ID>> {
        val now = clock.instant()
        return store.transaction { connection ->
            store.promoteDue(connection, now, now.minus(options.lockTimeout))
            store.claim(connection, workerId, registry.typeNames, limit, now)
        }
    }

    /** Waits for one free slot, then takes every other free slot without waiting. */
    private suspend fun reservePermits(): Int {
        permits.acquire()
        var reserved = 1
        while (permits.tryAcquire()) reserved++
        return reserved
    }

    private suspend fun shutdown(handlers: CoroutineScope) {
        val supervisor = handlers.coroutineContext.job
        val running = supervisor.children.toList()
        if (running.isNotEmpty()) {
            val timeout = options.shutdownTimeout
            log.info("worker {} stopping, waiting up to {} for {} running task(s)", workerId, timeout, running.size)
            val finished = withTimeoutOrNull(timeout.toMillis()) { running.joinAll() } != null
            if (!finished) {
                val stillRunning = running.count { it.isActive }
                log.warn("worker {} cancelling {} task(s) still running after {}", workerId, stillRunning, timeout)
            }
        }
        supervisor.cancelAndJoin()
        log.info("worker {} stopped", workerId)
    }

    // The worker must outlive any single failure, so it catches everything but cancellation.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun pollSafely(limit: Int): List<ClaimedTask<ID>> =
        try {
            pollOnce(limit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("worker {} could not poll, retrying after {}", workerId, options.pollInterval, e)
            emptyList()
        }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun executeSafely(task: ClaimedTask<ID>) {
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
     * the task with SQL, the handler is cancelled and its outcome dropped. If
     * the worker itself is being stopped, the handler is cancelled and the task
     * released for another worker.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun execute(task: ClaimedTask<ID>) =
        supervisorScope {
            var takenAway = false
            val handler = async { withTimeout(options.executionTimeout.toMillis()) { registry.run(task) } }
            val heartbeat =
                launch {
                    while (isActive) {
                        delay(options.heartbeatInterval.toMillis())
                        val alive =
                            store.transaction { store.heartbeat(it, task.id, task.version, clock.instant()) }
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
                    if (!takenAway) release(task)
                    throw e
                } catch (e: Exception) {
                    outcomeFor(task, e)
                }
            heartbeat.cancel()
            record(task, outcome)
        }

    /** Recording an outcome must not be lost to a cancellation that arrives at the same moment. */
    private suspend fun record(
        task: ClaimedTask<ID>,
        outcome: Outcome,
    ) {
        val applied =
            withContext(NonCancellable) {
                store.transaction { store.finish(it, task.id, task.version, outcome) }
            }
        if (!applied) {
            log.warn("task {} was changed while running, dropping outcome {}", task.id, outcome::class.simpleName)
        }
    }

    private suspend fun release(task: ClaimedTask<ID>) {
        log.info("worker {} stopping, releasing task {} for another worker", workerId, task.id)
        val released =
            withContext(NonCancellable) { store.transaction { store.release(it, task.id, task.version) } }
        if (!released) {
            log.warn("task {} was changed while running, not released", task.id)
        }
    }

    private fun outcomeFor(
        task: ClaimedTask<ID>,
        cause: Throwable,
    ): Outcome {
        val error = cause.toString()
        val next = registry.retryPolicy(task.type).nextAttemptAt(task.attempt, clock.instant(), cause)
        log.debug("task {} attempt {} failed: {}", task.id, task.attempt, error)
        return if (next == null) Outcome.Failed(error) else Outcome.Retry(next, error)
    }
}

/** What is registered: task types and their handlers, looked up by type name. */
internal class TaskRegistry<ID : Any>(
    private val entries: Map<String, Registration<ID, *>>,
) {
    val typeNames: Set<String> = entries.keys

    fun retryPolicy(type: String): RetryPolicy = registration(type).type.retryPolicy

    suspend fun run(task: ClaimedTask<ID>) {
        registration(task.type).run(task)
    }

    private fun registration(type: String): Registration<ID, *> =
        entries[type] ?: error("no handler registered for task type '$type'")

    class Registration<ID : Any, P : Any>(
        val type: TaskType<P>,
        val handler: TaskHandler<ID, P>,
    ) {
        suspend fun run(task: ClaimedTask<ID>) {
            val payload = type.codec.decode(task.payload)
            handler.handle(TaskContext(task.id, task.type, task.attempt), payload)
        }
    }
}

internal fun Instant.isDue(now: Instant): Boolean = !isAfter(now)
