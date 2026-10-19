package io.github.d4niella.pendenz

import io.github.d4niella.pendenz.store.ClaimedTask
import io.github.d4niella.pendenz.store.Outcome
import io.github.d4niella.pendenz.store.TaskStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import java.time.Clock

/**
 * Runs one claimed task: the handler with a heartbeat beside it, then records
 * the outcome. Shared by the polling [Worker] and by [Pendenz.runPending].
 */
internal class TaskRunner<ID : Any>(
    private val workerId: String,
    private val store: TaskStore<ID>,
    private val registry: TaskRegistry<ID>,
    private val clock: Clock,
    private val options: WorkerOptions,
) {
    private val log = LoggerFactory.getLogger(TaskRunner::class.java)

    @Suppress("TooGenericExceptionCaught")
    suspend fun runSafely(task: ClaimedTask<ID>) {
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
                } catch (e: UnsupportedPayloadVersionException) {
                    log.error("task {} cannot run on worker {}: {}", task.id, workerId, e.message)
                    Outcome.Failed(e.toString())
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
                store.transaction { store.finish(it, task.id, task.version, outcome, clock.instant()) }
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
