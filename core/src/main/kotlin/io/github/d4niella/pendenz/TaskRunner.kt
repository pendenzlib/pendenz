package io.github.d4niella.pendenz

import io.github.d4niella.pendenz.store.ClaimedTask
import io.github.d4niella.pendenz.store.Outcome
import io.github.d4niella.pendenz.store.TaskStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

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
    private val listener: TaskListener<ID>,
) {
    private val log = LoggerFactory.getLogger(TaskRunner::class.java)

    /** Runs [task]; a failure to record its outcome is logged, whatever was thrown, and the caller goes on. */
    @Suppress("TooGenericExceptionCaught")
    suspend fun runSafely(task: ClaimedTask<ID>) {
        try {
            execute(task)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log.error("worker {} could not record the outcome of task {}", workerId, task.id, e)
        }
    }

    /**
     * Runs the handler with a heartbeat beside it. If the heartbeat finds that
     * the row moved on without us, for example because an operator cancelled
     * the task with SQL, the handler is cancelled and its outcome dropped. If
     * the worker itself is being stopped, the handler is cancelled and the task
     * released for another worker. Anything else the handler throws, an
     * [Error] included, is a failed attempt for the retry policy (ADR 0026).
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun execute(task: ClaimedTask<ID>) =
        supervisorScope {
            val startedAt = clock.instant()
            val context = task.context().also { it.chain = Chain(registry, clock) }
            // What the handler returned, kept even if a cancellation or timeout is noticed only afterwards:
            // a blocking handler cannot be interrupted, and work that was done is recorded, not thrown away.
            val produced = AtomicReference<Produced?>(null)
            val handler =
                async {
                    withTimeout(options.executionTimeout.toMillis()) {
                        registry.run(task, context).also { produced.set(it) }
                    }
                }
            val heartbeat = heartbeat(task, handler)
            val ended =
                try {
                    ended(handler.await(), context)
                } catch (e: TimeoutCancellationException) {
                    produced.get()?.let { ended(it, context) } ?: outcomeFor(task, context, e)
                } catch (e: UnsupportedPayloadVersionException) {
                    log.error("task {} cannot run on worker {}: {}", task.id, workerId, e.message)
                    Ended(Outcome.Failed(e.toString(), context.provenance), e)
                } catch (e: CancellationException) {
                    // Being cancelled makes await() return before the handler has ended. A blocking handler
                    // cannot be interrupted, so wait for it; what it produced meanwhile is recorded, not lost.
                    withContext(NonCancellable) { handler.join() }
                    heartbeat.cancel()
                    val done = produced.get()
                    when {
                        heartbeat.takenAway -> {
                            listener.onOutcomeDropped(context)
                            return@supervisorScope
                        }

                        done != null -> {
                            ended(done, context)
                        }

                        // The handler threw someone else's CancellationException: a failure of its own, not ours.
                        currentCoroutineContext().isActive -> {
                            outcomeFor(task, context, e)
                        }

                        else -> {
                            release(task)
                            throw e
                        }
                    }
                } catch (e: Throwable) {
                    outcomeFor(task, context, e)
                }
            // The heartbeat keeps the lock while the outcome is written, so a retried write still finds the row ours
            heartbeat.recording = true
            try {
                record(task, context, ended, startedAt)
            } finally {
                heartbeat.cancel()
            }
        }

    /**
     * Refreshes the lock beside the handler, on a dispatcher of its own: a
     * handler that blocks the worker's thread must not keep its own lock
     * from being renewed (ADR 0005, amended). A heartbeat that fails, say
     * because the pool is briefly exhausted, is logged and tried again at
     * the next interval; it must not stop, or the lock would go stale and
     * another worker would run the task a second time. A heartbeat that
     * finds the row moved on cancels the handler.
     */
    private fun CoroutineScope.heartbeat(
        task: ClaimedTask<ID>,
        handler: Job,
    ): Heartbeat {
        val state = Heartbeat()
        state.job =
            launch(HEARTBEATS) {
                var stillOurs = true
                while (isActive && stillOurs) {
                    delay(options.heartbeatInterval.toMillis())
                    stillOurs = refreshLock(task)
                }
                // Once the outcome is being written, the row moving on is that write's own doing
                if (!stillOurs && !state.recording) {
                    log.warn("task {} moved on without worker {}, cancelling its handler", task.id, workerId)
                    state.takenAway = true
                    handler.cancel()
                }
            }
        return state
    }

    /** False only when the row moved on without us; a refresh that fails is logged and counts as still ours. */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun refreshLock(task: ClaimedTask<ID>): Boolean =
        try {
            store.transaction { store.heartbeat(it, task.id, task.version, clock.instant().plus(options.lockTimeout)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("worker {} could not refresh the lock of task {}, trying again", workerId, task.id, e)
            true
        }

    /** The heartbeat's job and whether it found the row taken away. */
    private class Heartbeat {
        lateinit var job: Job

        @Volatile
        var takenAway: Boolean = false

        @Volatile
        var recording: Boolean = false

        fun cancel() = job.cancel()
    }

    /**
     * Recording an outcome must not be lost to a cancellation that arrives
     * at the same moment. A successful outcome takes the handler's
     * follow-ups with it, in the same transaction (ADR 0019).
     */
    private suspend fun record(
        task: ClaimedTask<ID>,
        context: TaskContext<ID>,
        ended: Ended,
        startedAt: Instant,
    ) {
        val now = clock.instant()
        val followUps = if (ended.outcome is Outcome.Succeeded) context.followUps else emptyList()
        val enqueued = ArrayList<ID>(followUps.size)
        val applied =
            withContext(NonCancellable) {
                persistently(task) {
                    store.transaction { tx ->
                        enqueued.clear()
                        val applied = store.finish(tx, task.id, task.version, ended.outcome, now)
                        if (applied) followUps.forEach { enqueued += store.insert(tx, it) }
                        applied
                    }
                }
            }
        if (!applied) {
            log.warn("task {} was changed while running, dropping outcome {}", task.id, ended.outcome::class.simpleName)
            listener.onOutcomeDropped(context)
            return
        }
        followUps.zip(enqueued).forEach { (followUp, id) ->
            listener.onEnqueued(id, followUp.type)
            if (followUp.status == TaskStatus.AWAITING_APPROVAL) listener.onAwaitingApproval(id, followUp.type)
        }
        when (val outcome = ended.outcome) {
            is Outcome.Succeeded -> listener.onSucceeded(context, Duration.between(startedAt, now))
            is Outcome.Rejected -> listener.onRejected(context, outcome.reason)
            is Outcome.Retry -> listener.onRetryScheduled(context, ended.cause!!, outcome.runAt)
            is Outcome.Failed -> listener.onFailed(context, ended.cause!!)
        }
    }

    /**
     * Runs [write], and runs it again after a pause when it fails, as long as
     * half the lock timeout has not passed: the work is done, and losing the
     * outcome to a brief database hiccup would let the lock expire and the
     * work run once more. Past that point the failure propagates.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> persistently(
        task: ClaimedTask<ID>,
        write: suspend () -> T,
    ): T {
        val giveUpAt = System.nanoTime() + options.lockTimeout.toNanos() / 2
        var pause = FIRST_RECORD_PAUSE_MILLIS
        while (true) {
            try {
                return write()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (System.nanoTime() + pause * NANOS_PER_MILLI > giveUpAt) throw e
                log.warn(
                    "worker {} could not record the outcome of task {}, again in {} ms",
                    workerId,
                    task.id,
                    pause,
                    e,
                )
                delay(pause)
                pause = (pause * 2).coerceAtMost(MAX_RECORD_PAUSE_MILLIS)
            }
        }
    }

    /** The handler returned: accepted results succeed, refused ones are REJECTED without a retry. */
    private fun ended(
        produced: Produced,
        context: TaskContext<ID>,
    ): Ended =
        when (val verdict = produced.verdict) {
            Verdict.Accept -> Ended(Outcome.Succeeded(produced.result, context.provenance), null)
            is Verdict.Reject -> Ended(Outcome.Rejected(produced.result, verdict.reason, context.provenance), null)
        }

    private suspend fun release(task: ClaimedTask<ID>) {
        log.info("worker {} stopping, releasing task {} for another worker", workerId, task.id)
        val released =
            withContext(NonCancellable) { store.transaction { store.release(it, task.id, task.version) } }
        if (released) {
            listener.onReleased(task.context())
        } else {
            log.warn("task {} was changed while running, not released", task.id)
        }
    }

    private fun outcomeFor(
        task: ClaimedTask<ID>,
        context: TaskContext<ID>,
        cause: Throwable,
    ): Ended {
        val error = cause.toString()
        val next = registry.retryPolicy(task.type).nextAttemptAt(task.attempt, clock.instant(), cause)
        log.debug("task {} attempt {} failed: {}", task.id, task.attempt, error)
        val provenance = context.provenance
        val outcome = if (next == null) Outcome.Failed(error, provenance) else Outcome.Retry(next, error, provenance)
        return Ended(outcome, cause)
    }

    /** How the handler ended: the outcome to record, and what it threw if it threw. */
    private class Ended(
        val outcome: Outcome,
        val cause: Throwable?,
    )
}

private const val FIRST_RECORD_PAUSE_MILLIS = 100L
private const val MAX_RECORD_PAUSE_MILLIS = 5_000L
private const val NANOS_PER_MILLI = 1_000_000L

/**
 * Where heartbeats run: a few threads of the IO pool kept for them, so a
 * handler blocking the worker's dispatcher cannot starve its own lock.
 * The store's connection provider may still move the write elsewhere.
 */
private const val HEARTBEAT_THREADS = 8
private val HEARTBEATS = Dispatchers.IO.limitedParallelism(HEARTBEAT_THREADS)
