package io.github.d4niella.pendenz

import io.github.d4niella.pendenz.store.ClaimedTask
import io.github.d4niella.pendenz.store.TaskStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
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
    private val runner = TaskRunner(workerId, store, registry, clock, options)
    private val maintenance = Maintenance(store, registry, clock, options)
    private var lastMaintenance: Instant? = null

    /** Runs until the coroutine is cancelled, then drains running handlers before returning. */
    suspend fun run() {
        val handlers = CoroutineScope(currentCoroutineContext().minusKey(Job) + SupervisorJob())
        log.info("worker {} started for task types {}", workerId, registry.typeNames)
        try {
            while (currentCoroutineContext().isActive) {
                maintainIfDue()
                val reserved = reservePermits()
                val claimed = pollSafely(reserved)
                repeat(reserved - claimed.size) { permits.release() }
                claimed.forEach { task ->
                    handlers.launch {
                        try {
                            runner.runSafely(task)
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

    /** Claims and runs due tasks in this coroutine until nothing is due. Returns how many ran. */
    suspend fun runPending(): Int {
        var ran = 0
        while (true) {
            val claimed = pollOnce(options.parallelism)
            if (claimed.isEmpty()) return ran
            claimed.forEach { task ->
                runner.runSafely(task)
                ran++
            }
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

    /** Applies retention once per [WorkerOptions.maintenanceInterval]; a failure is logged, not fatal. */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun maintainIfDue() {
        val now = clock.instant()
        val last = lastMaintenance
        if (last != null && now.isBefore(last.plus(options.maintenanceInterval))) return
        lastMaintenance = now
        try {
            maintenance.run()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error(
                "worker {} could not run maintenance, retrying after {}",
                workerId,
                options.maintenanceInterval,
                e,
            )
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
}

/** What is registered: task types and their handlers, looked up by type name. */
internal class TaskRegistry<ID : Any>(
    private val entries: Map<String, Registration<ID, *>>,
) {
    val typeNames: Set<String> = entries.keys
    val types: List<TaskType<*>> = entries.values.map { it.type }

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
            val payload = type.decode(task.payload, task.payloadVersion)
            handler.handle(TaskContext(task.id, task.type, task.attempt, task.createdBy), payload)
        }
    }
}

internal fun Instant.isDue(now: Instant): Boolean = !isAfter(now)
