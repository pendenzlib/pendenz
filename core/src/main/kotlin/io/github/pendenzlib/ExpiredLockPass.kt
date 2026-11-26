package io.github.pendenzlib

import io.github.pendenzlib.store.ClaimedTask
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
     * Returns false when the worker is stopping and rows were left for
     * another poll. Throws what the store threw when the database did not
     * answer or a transaction failed without a row to blame: the poll that
     * started the pass then fails like any other.
     */
    suspend fun run(now: Instant): Boolean {
        var rows = 0
        while (rows++ < LockExpiry.PER_POLL) {
            if (!currentCoroutineContext().isActive) return false
            if (!recordNext(now)) break
        }
        return true
    }

    /**
     * Records the oldest expired lock that is not set aside and decides
     * what follows a write that throws. Returns false when no row is left.
     */
    @Suppress("TooGenericExceptionCaught") // whatever the store throws, the pass must decide what to do with the row
    private suspend fun recordNext(now: Instant): Boolean {
        var read: Read<ID>? = null
        try {
            val failed =
                inTransaction { tx ->
                    read = expiry.due(tx, now, limit = 1).firstOrNull()?.let { Read(it.task, expiry.decide(it, now)) }
                    read?.let { expiry.record(tx, it.task, it.decision, now) }
                }
            failed?.let { expiry.report(listOf(it)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val row = read
            when {
                row == null || !RefusedWrite.isAnswer(e) -> throw e
                RefusedWrite.isRefused(e) -> recordNameOnly(row, now, e)
                else -> setAside(row.task, now, e)
            }
        }
        return read != null
    }

    /**
     * The database refused the failed attempt of [row]: the same decision is
     * written with the class name as the text, which is what a constraint on
     * the text most likely takes. A second answer of the database sets the
     * row aside.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun recordNameOnly(
        row: Read<ID>,
        now: Instant,
        refusal: Exception,
    ) {
        // The message alone: the poll that failed has logged the same refusal with its stack trace
        log.warn(
            "worker {} could not record the expired lock of task {}, writing the class name instead of the text: {}",
            workerId,
            row.task.id,
            RefusedWrite.reported(refusal).toString(),
        )
        val nameOnly = row.decision.copy(error = LockExpiredException::class.java.name)
        try {
            val failed =
                inTransaction { tx ->
                    // Still the oldest row that is not set aside, unless it moved on meanwhile
                    expiry.due(tx, now, limit = 1).firstOrNull { it.task.id == row.task.id }?.let {
                        expiry.record(tx, it.task, nameOnly, now)
                    }
                }
            failed?.let { expiry.report(listOf(it)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!RefusedWrite.isAnswer(e)) throw e
            setAside(row.task, now, e)
        }
    }

    private fun setAside(
        task: ClaimedTask<ID>,
        now: Instant,
        error: Exception,
    ) {
        expiry.setAside(task.id, now.plus(setAsideFor))
        log.error(
            "worker {} could not record the expired lock of task {}, leaving it RUNNING and trying again after {}",
            workerId,
            task.id,
            setAsideFor,
            error,
        )
        listener.onExpiredLockNotRecorded(task.context(), error)
    }

    /** The transaction runs to its end even when the worker is being cancelled, like the poll's. */
    private suspend fun <T> inTransaction(block: (StoreTransaction) -> T): T =
        withContext(NonCancellable) { store.transaction(block) }

    /** A row as the pass read it, with what the retry policy and the description said about it. */
    private class Read<ID : Any>(
        val task: ClaimedTask<ID>,
        val decision: LockExpiry.Decision,
    )
}
