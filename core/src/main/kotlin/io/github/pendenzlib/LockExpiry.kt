package io.github.pendenzlib

import io.github.pendenzlib.store.ClaimedTask
import io.github.pendenzlib.store.ExpiredLock
import io.github.pendenzlib.store.Outcome
import io.github.pendenzlib.store.StoreTransaction
import io.github.pendenzlib.store.TaskStore
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * A lock of one of the worker's types that expired means its worker died
 * with the task: the attempt failed, and the type's retry policy decides
 * what comes next (ADR 0026). Types the worker does not handle are left
 * to a worker that does.
 *
 * The poll records expired locks in its own transaction. When a write
 * there throws, an [ExpiredLockPass] records them one by one, and a row
 * that cannot be recorded is set aside here (ADR 0049).
 */
internal class LockExpiry<ID : Any>(
    private val workerId: String,
    private val store: TaskStore<ID>,
    private val registry: TaskRegistry<ID>,
    private val listener: TaskListener<ID>,
) {
    private val log = LoggerFactory.getLogger(LockExpiry::class.java)

    /** Rows whose expired lock could not be recorded, and when each is tried again. */
    private val setAside = ConcurrentHashMap<ID, Instant>()

    /**
     * Records expired locks as failed attempts inside the poll's transaction;
     * report them after the commit. A write that throws ends the poll with
     * an [ExpiredLockWriteException].
     */
    fun failExpired(
        tx: StoreTransaction,
        now: Instant,
    ): List<Failed<ID>> =
        due(tx, now).take(PER_POLL).mapNotNull { expired ->
            val decision = decide(expired, now)
            writing { record(tx, expired, decision, now) }
        }

    fun report(failed: List<Failed<ID>>) {
        failed.forEach { (task, cause, nextRunAt) ->
            log.warn("worker {} found the lock of task {} expired: {}", workerId, task.id, cause.message)
            if (nextRunAt == null) {
                listener.onFailed(task.context(), cause)
            } else {
                listener.onRetryScheduled(task.context(), cause, nextRunAt)
            }
        }
    }

    /**
     * The expired locks to record, oldest first. The read asks for the rows
     * set aside on top of [PER_POLL], so that they do not take the places of
     * others; a row whose time aside has passed is among the returned ones.
     */
    fun due(
        tx: StoreTransaction,
        now: Instant,
    ): List<ExpiredLock<ID>> {
        setAside.values.removeIf { !it.isAfter(now) }
        return store
            .expiredLocks(tx, registry.typeNames, now, PER_POLL + setAside.size)
            .filterNot { setAside.containsKey(it.task.id) }
    }

    /** Asks the retry policy and the error description about [expired]. */
    fun decide(
        expired: ExpiredLock<ID>,
        now: Instant,
    ): Decision {
        val cause = LockExpiredException(expired.lockedBy, expired.lockedUntil)
        val next = registry.nextAttemptAt(expired.task, now, cause)
        return Decision(cause, next, registry.describe(expired.task.context(), cause))
    }

    /** Writes the failed attempt of [expired] as decided; null when the row moved on meanwhile. */
    fun record(
        tx: StoreTransaction,
        expired: ExpiredLock<ID>,
        decision: Decision,
        now: Instant,
    ): Failed<ID>? {
        val task = expired.task
        val next = decision.nextRunAt
        val outcome = if (next == null) Outcome.Failed(decision.error) else Outcome.Retry(next, decision.error)
        return if (store.finish(tx, task.id, task.version, outcome, now)) Failed(task, decision.cause, next) else null
    }

    /** Leaves the row with this [id] out of [due] until [until]. */
    fun setAside(
        id: ID,
        until: Instant,
    ) {
        setAside[id] = until
    }

    /** Marks what [write] throws as a failure of the expired locks, so the poll can tell it from its other steps. */
    @Suppress("TooGenericExceptionCaught")
    private inline fun <T> writing(write: () -> T): T =
        try {
            write()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ExpiredLockWriteException(e)
        }

    /** A task whose expired lock was recorded as a failed attempt, and when it runs again, if at all. */
    data class Failed<ID : Any>(
        val task: ClaimedTask<ID>,
        val cause: LockExpiredException,
        val nextRunAt: Instant?,
    )

    /** What the retry policy and the description said about an expired lock. */
    data class Decision(
        val cause: LockExpiredException,
        val nextRunAt: Instant?,
        val error: String,
    )

    companion object {
        /** How many expired locks one poll records at most, so a poll after a mass failure stays short. */
        const val PER_POLL = 100
    }
}

/** A poll failed while it recorded expired locks; [failure] is what the store threw (ADR 0049). */
internal class ExpiredLockWriteException(
    val failure: Exception,
) : RuntimeException(failure)
