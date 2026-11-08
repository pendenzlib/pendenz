package io.github.pendenzlib

import io.github.pendenzlib.store.ClaimedTask
import io.github.pendenzlib.store.NewTask
import io.github.pendenzlib.store.TaskStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
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
    private val listener: TaskListener<ID>,
) {
    private val log = LoggerFactory.getLogger(Worker::class.java)
    private val permits = Semaphore(options.parallelism)

    /** Signalled when one of this worker's handlers ends: a permit and perhaps a resource slot are free. */
    private val handlerEnded = Channel<Unit>(Channel.CONFLATED)
    private val runner = TaskRunner(workerId, store, registry, clock, options, listener)
    private val maintenance = Maintenance(store, registry, clock, options, listener)
    private val lockExpiry = LockExpiry(workerId, store, registry, listener)

    /** Runs until the coroutine is cancelled, then drains running handlers before returning. */
    suspend fun run() {
        val uncaught =
            CoroutineExceptionHandler { _, e ->
                log.error("worker {} lost a handler coroutine to an uncaught exception", workerId, e)
            }
        val handlers = CoroutineScope(currentCoroutineContext().minusKey(Job) + SupervisorJob() + uncaught)
        log.info("worker {} started for task types {}", workerId, registry.typeNames)
        maintenance.checkSchedules()
        try {
            coroutineScope {
                // Beside the loop, so a long sweep never delays a claim (ADR 0029); stops with the worker
                launch { maintenance.runRegularly(workerId) }
                claimLoop(handlers)
            }
        } finally {
            withContext(NonCancellable) { shutdown(handlers) }
        }
    }

    private suspend fun claimLoop(handlers: CoroutineScope) {
        while (currentCoroutineContext().isActive) {
            val reserved = reservePermits()
            // A handler that ended before this poll is seen by it; only later ends should wake the next wait
            handlerEnded.tryReceive()
            val claimed = keepOrGiveBack(pollSafely(reserved))
            repeat(reserved - claimed.size) { permits.release() }
            claimed.forEach { task ->
                handlers.launch {
                    try {
                        runner.runSafely(task)
                    } finally {
                        permits.release()
                        handlerEnded.trySend(Unit)
                    }
                }
            }
            if (claimed.isEmpty()) awaitWork()
        }
    }

    /**
     * Claims and runs due tasks in this coroutine, one at a time, until
     * nothing is due. Returns how many ran. One at a time, because a task
     * claimed but not yet running has no heartbeat and would go stale behind
     * a long-running predecessor. Schedules get their next occurrence
     * first, so that one that is due runs too (ADR 0033).
     */
    suspend fun runPending(): Int {
        maintenance.checkSchedules()
        var ran = 0
        while (true) {
            val task = pollOnce(1).singleOrNull() ?: return ran
            runner.runSafely(task)
            ran++
        }
    }

    /**
     * One pass: promote due tasks, record expired locks as failed attempts,
     * read the limits, the rates and the active blocks, then claim up to [limit] tasks and record the starts
     * of rate-limited ones. The transaction runs to its end even
     * when the worker is being cancelled, because its claim commits either
     * way; the caller gives the tasks back when it finds itself stopping.
     */
    internal suspend fun pollOnce(limit: Int): List<ClaimedTask<ID>> {
        val now = clock.instant()
        val (expired, claimed) =
            withContext(NonCancellable) {
                store.transaction { tx ->
                    store.promoteDue(tx, now)
                    val expired = lockExpiry.failExpired(tx, now)
                    val tally =
                        store.resourceLimits?.let { limits ->
                            limits.lockClaims(tx)
                            ResourceTally(store.runningPerResource(tx, now), limits.limits(tx))
                        }
                    // Rates count under the claim lock the limits take (ADR 0040)
                    val rateStore = store.resourceRates?.takeIf { tally != null }
                    val rates = rateStore?.let { RateTally(it.startsPerResource(tx, now), it.rates(tx)) }
                    val blocked = store.resourceBlocks?.activeBlocks(tx, now).orEmpty()
                    val lockedUntil = now.plus(options.lockTimeout)
                    val resources = PollResources(tally, blocked, rates)
                    val claims = PollClaims(store, tx, registry.typeNames, workerId, now, lockedUntil, resources)
                    val claimed = options.claimStrategy.claim(claims, ClaimRequest(registry.batchSizes, limit))
                    rateStore?.recordStarts(tx, resources.starts, now)
                    expired to claimed
                }
            }
        lockExpiry.report(expired)
        claimed.forEach { listener.onClaimed(it.context()) }
        return claimed
    }

    /** Releases tasks claimed in the poll during which the worker was cancelled; nobody would run them. */
    private suspend fun keepOrGiveBack(claimed: List<ClaimedTask<ID>>): List<ClaimedTask<ID>> {
        if (claimed.isEmpty() || currentCoroutineContext().isActive) return claimed
        log.info("worker {} stopping, giving back {} task(s) it had just claimed", workerId, claimed.size)
        withContext(NonCancellable) {
            val released = store.transaction { tx -> claimed.filter { store.release(tx, it.id, it.version) } }
            released.forEach { listener.onReleased(it.context()) }
        }
        return emptyList()
    }

    /**
     * Waits for the configured [WakeUp], or for one of this worker's own
     * handlers to end, whichever comes first. A handler that ends may have
     * freed a resource slot (ADR 0017) that a pending task was waiting for,
     * and nothing else would announce that before the poll interval. A
     * [WakeUp] that throws is logged and stood in for by the poll interval.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun awaitWork() =
        coroutineScope {
            val external =
                async {
                    try {
                        options.wakeUp.await(registry.typeNames, options.pollInterval)
                    } catch (e: CancellationException) {
                        // Someone else's cancellation, say a timeout inside the WakeUp, is a failure, not a stop
                        if (!currentCoroutineContext().isActive) throw e
                        log.error(
                            "worker {} could not wait for work, polling after {}",
                            workerId,
                            options.pollInterval,
                            e,
                        )
                        delay(options.pollInterval.toMillis())
                    } catch (e: Exception) {
                        log.error(
                            "worker {} could not wait for work, polling after {}",
                            workerId,
                            options.pollInterval,
                            e,
                        )
                        delay(options.pollInterval.toMillis())
                    }
                }
            select {
                external.onAwait {}
                handlerEnded.onReceive {}
            }
            external.cancel()
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
            if (!currentCoroutineContext().isActive) throw e
            log.error("worker {} could not poll, retrying after {}", workerId, options.pollInterval, e)
            emptyList()
        } catch (e: Exception) {
            log.error("worker {} could not poll, retrying after {}", workerId, options.pollInterval, e)
            emptyList()
        }
}

/** What is registered: task types and their handlers, looked up by type name, and the schedules. */
internal class TaskRegistry<ID : Any>(
    private val entries: Map<String, Registration<ID, *>>,
    /** The schedules, each of a registered type (ADR 0033). */
    val schedules: List<ScheduledTask<*>> = emptyList(),
    /** What encloses every handler run, outermost first (ADR 0036). */
    private val interceptors: List<TaskInterceptor<ID>> = emptyList(),
    /** What turns the exception of a failed attempt into `last_error` (ADR 0043). */
    private val errorDescription: ErrorDescription = ErrorDescription.DEFAULT,
) {
    private val log = LoggerFactory.getLogger(TaskRegistry::class.java)
    val typeNames: Set<String> = entries.keys
    val types: List<TaskType<*>> = entries.values.map { it.type }
    val batchSizes: Map<String, Int> = entries.mapValues { it.value.type.claimBatchSize }

    /** How long one run of [type]'s handler may take: the type's own timeout, or [default] (ADR 0039). */
    fun executionTimeout(
        type: String,
        default: Duration,
    ): Duration = registration(type).type.executionTimeout ?: default

    /**
     * Asks the type's retry policy about [task]'s failed attempt, with a
     * context that collects nothing. A policy that throws gives up: the
     * exception is logged and the answer is `null` (ADR 0038).
     */
    @Suppress("TooGenericExceptionCaught") // whatever the policy throws, the task must still get an outcome
    fun nextAttemptAt(
        task: ClaimedTask<ID>,
        failedAt: Instant,
        cause: Throwable,
    ): Instant? =
        try {
            registration(task.type).type.retryPolicy.nextAttemptAt(FailedAttempt(task.context(), failedAt, cause))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log.error("retry policy of task type '{}' threw for task {}, giving up", task.type, task.id, e)
            null
        }

    /**
     * The text to store for [error], which ended an attempt of the task in
     * [context]. A description that throws is logged, and the class name
     * stands in: the message is what it may have been meant to keep out.
     */
    @Suppress("TooGenericExceptionCaught") // whatever the description throws, the attempt must still be recorded
    fun describe(
        context: TaskContext<ID>,
        error: Throwable,
    ): String =
        try {
            errorDescription.describe(context, error)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log.error("error description threw for task {}, storing the class name only", context.id, e)
            error.javaClass.name
        }

    /** Decodes the payload and runs the handler, both inside the interceptors. */
    suspend fun run(
        task: ClaimedTask<ID>,
        context: TaskContext<ID>,
    ): Produced {
        val registration = registration(task.type)
        if (interceptors.isEmpty()) return registration.run(task, context)
        return InterceptedRun(interceptors, context) { registration.run(task, context) }.run()
    }

    /**
     * Refuses a type that is not registered, and another instance under a
     * registered name: only the registered one carries the approval rule,
     * payload mapping and version the queue was built with (ADR 0018).
     */
    fun isRegistered(type: TaskType<*>): Boolean = entries[type.name]?.type === type

    fun requireRegistered(type: TaskType<*>) {
        val registered = requireNotNull(entries[type.name]?.type) { "task type '${type.name}' is not registered" }
        require(registered === type) {
            "task type '${type.name}' is registered as another instance; use the registered one"
        }
    }

    private fun registration(type: String): Registration<ID, *> =
        entries[type] ?: error("no handler registered for task type '$type'")

    /** A task type with the code that runs it: a plain handler, or one that produces a validated result. */
    class Registration<ID : Any, P : Any> private constructor(
        val type: TaskType<P>,
        private val run: suspend (TaskContext<ID>, P) -> Produced,
    ) {
        constructor(type: TaskType<P>, handler: TaskHandler<ID, P>) : this(
            type,
            { context, payload ->
                handler.handle(context, payload)
                Produced(null, Verdict.Accept)
            },
        )

        constructor(
            type: TaskType<P>,
            result: TaskResult<P, *>,
            handler: ResultHandler<ID, P, *>,
        ) : this(type, producing(result, handler))

        suspend fun run(
            task: ClaimedTask<ID>,
            context: TaskContext<ID>,
        ): Produced = run(context, type.decode(task.payload, task.payloadVersion))

        private companion object {
            fun <ID : Any, P : Any, R : Any> producing(
                result: TaskResult<P, R>,
                handler: ResultHandler<ID, P, *>,
            ): suspend (TaskContext<ID>, P) -> Produced =
                { context, payload ->
                    @Suppress("UNCHECKED_CAST") // registered together, so the handler's R is the result's R
                    val produced = handler.handle(context, payload) as R
                    Produced(result.codec.encode(produced), result.validator.validate(payload, produced))
                }
        }
    }
}

/** What a handler produced: the encoded result, if any, and the validator's verdict on it. */
internal class Produced(
    val result: String?,
    val verdict: Verdict,
)

/** Builds the follow-ups a handler enqueues on its context (ADR 0019). */
internal class Chain<ID : Any>(
    private val registry: TaskRegistry<ID>,
    private val clock: Clock,
) {
    @Suppress("LongParameterList") // the context plus what the handler may set, by design
    fun <P : Any> followUp(
        parent: TaskContext<ID>,
        type: TaskType<P>,
        payload: P,
        runAt: Instant?,
        idempotencyKey: String?,
        lane: String?,
        priority: Int?,
        resource: String?,
    ): NewTask<ID> {
        registry.requireRegistered(type)
        ScheduleMark.requireNotReserved("idempotencyKey", idempotencyKey)
        require(!CycleLane.isCycleLane(lane) || lane == parent.lane) {
            "lane '$lane' belongs to another cycle; a follow-up may only keep its parent's cycle lane"
        }
        val spec =
            NewTaskSpec(
                runAt = runAt,
                idempotencyKey = idempotencyKey,
                createdBy = parent.createdBy,
                lane = lane,
                priority = priority ?: parent.priority,
                resource = resource,
                parentId = parent.id,
                correlationId = parent.correlationId,
            )
        return type.newTask(payload, spec, clock.instant())
    }
}

internal fun Instant.isDue(now: Instant): Boolean = !isAfter(now)

internal fun <ID : Any> ClaimedTask<ID>.context(deadline: Instant? = null): TaskContext<ID> =
    TaskContext(
        id,
        type,
        attempt,
        createdBy,
        lane,
        priority,
        resource,
        parentId,
        correlationId,
        runAt,
        claimedAt,
        createdAt,
        deadline,
    )
