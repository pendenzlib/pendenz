package io.github.pendenzlib

import io.github.pendenzlib.store.ClaimedTask
import io.github.pendenzlib.store.ExpiredLock
import io.github.pendenzlib.store.StoreTransaction
import io.github.pendenzlib.store.TaskStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant

/**
 * Records expired locks after a poll that failed at them (ADR 0049): each
 * row is read again and written in a transaction of its own. A write the
 * database refuses is followed by one with the class name as the text. A
 * row that still cannot be written stays RUNNING, is set aside for
 * [setAsideFor] and reported to the listener.
 */
internal class ExpiredLockPass<ID : Any>(
    private val workerId: String,
    private val store: TaskStore<ID>,
    private val expiry: LockExpiry<ID>,
    private val listener: TaskListener<ID>,
    private val setAsideFor: Duration,
) {
    private val log = LoggerFactory.getLogger(ExpiredLockPass::class.java)

    /**
     * Returns false when the pass ended early: the database did not answer,
     * a transaction failed without a row to blame, or the worker is stopping.
     */
    suspend fun run(now: Instant): Boolean {
        var step = Step.GO_ON
        var rows = 0
        while (step == Step.GO_ON && rows++ < LockExpiry.PER_POLL) {
            step = if (currentCoroutineContext().isActive) recordNext(now) else Step.STOP
        }
        return step != Step.STOP
    }

    /**
     * Records the oldest expired lock that is not set aside, and decides
     * what follows a write that throws.
     */
    @Suppress("TooGenericExceptionCaught") // whatever the store throws, the pass must decide what to do with the row
    private suspend fun recordNext(now: Instant): Step {
        var current: Pair<ExpiredLock<ID>, LockExpiry.Decision>? = null
        return try {
            val failed =
                inTransaction { tx ->
                    current = expiry.due(tx, now, limit = 1).firstOrNull()?.let { it to expiry.decide(it, now) }
                    current?.let { (expired, decision) -> expiry.record(tx, expired, decision, now) }
                }
            failed?.let { expiry.report(listOf(it)) }
            if (current == null) Step.NOTHING_LEFT else Step.GO_ON
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val found = current
            when {
                !RefusedWrite.isAnswer(e) || found == null -> stop(e)
                RefusedWrite.isRefused(e) -> recordNameOnly(found.first.task, found.second, now, e)
                else -> setAside(found.first.task, now, e)
            }
        }
    }

    /**
     * The database refused the failed attempt of [task]: the same [decision]
     * is written with the class name as the text, which is what a constraint
     * on the text most likely takes. A second failure sets the row aside.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun recordNameOnly(
        task: ClaimedTask<ID>,
        decision: LockExpiry.Decision,
        now: Instant,
        refusal: Exception,
    ): Step =
        try {
            log.warn(
                "worker {} could not record the expired lock of task {}, writing the class name instead of the text",
                workerId,
                task.id,
                refusal,
            )
            val nameOnly = decision.copy(error = LockExpiredException::class.java.name)
            val failed =
                inTransaction { tx ->
                    expiry.due(tx, now).firstOrNull { it.task.id == task.id }?.let {
                        expiry.record(tx, it, nameOnly, now)
                    }
                }
            failed?.let { expiry.report(listOf(it)) }
            Step.GO_ON
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (RefusedWrite.isAnswer(e)) setAside(task, now, e) else stop(e)
        }

    private fun setAside(
        task: ClaimedTask<ID>,
        now: Instant,
        error: Exception,
    ): Step {
        expiry.setAside(task.id, now.plus(setAsideFor))
        log.error(
            "worker {} could not record the expired lock of task {}, leaving it RUNNING and trying again after {}",
            workerId,
            task.id,
            setAsideFor,
            error,
        )
        listener.onExpiredLockNotRecorded(task.context(), error)
        return Step.GO_ON
    }

    private fun stop(error: Exception): Step {
        log.error("worker {} could not record expired locks one by one", workerId, error)
        return Step.STOP
    }

    /** The transaction runs to its end even when the worker is being cancelled, like the poll's. */
    private suspend fun <T> inTransaction(block: (StoreTransaction) -> T): T =
        withContext(NonCancellable) { store.transaction(block) }

    private enum class Step { GO_ON, NOTHING_LEFT, STOP }
}
