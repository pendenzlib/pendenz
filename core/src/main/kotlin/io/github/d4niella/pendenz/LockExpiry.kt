package io.github.d4niella.pendenz

import io.github.d4niella.pendenz.store.ClaimedTask
import io.github.d4niella.pendenz.store.Outcome
import io.github.d4niella.pendenz.store.StoreTransaction
import io.github.d4niella.pendenz.store.TaskStore
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * A lock of one of the worker's types that expired means its worker died
 * with the task: the attempt failed, and the type's retry policy decides
 * what comes next (ADR 0026). Types the worker does not handle are left
 * to a worker that does.
 */
internal class LockExpiry<ID : Any>(
    private val workerId: String,
    private val store: TaskStore<ID>,
    private val registry: TaskRegistry<ID>,
    private val listener: TaskListener<ID>,
) {
    private val log = LoggerFactory.getLogger(LockExpiry::class.java)

    /** Records expired locks as failed attempts inside the poll's transaction; report them after the commit. */
    fun failExpired(
        tx: StoreTransaction,
        now: Instant,
    ): List<Failed<ID>> =
        store.expiredLocks(tx, registry.typeNames, now, PER_POLL).mapNotNull { expired ->
            val task = expired.task
            val cause = LockExpiredException(expired.lockedBy, expired.lockedUntil)
            val next = registry.retryPolicy(task.type).nextAttemptAt(task.attempt, now, cause)
            val outcome = if (next == null) Outcome.Failed(cause.toString()) else Outcome.Retry(next, cause.toString())
            if (store.finish(tx, task.id, task.version, outcome, now)) Failed(task, cause, next) else null
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

    /** A task whose expired lock was recorded as a failed attempt, and when it runs again, if at all. */
    data class Failed<ID : Any>(
        val task: ClaimedTask<ID>,
        val cause: LockExpiredException,
        val nextRunAt: Instant?,
    )

    private companion object {
        /** How many expired locks one poll records at most, so a poll after a mass failure stays short. */
        const val PER_POLL = 100
    }
}
