package io.github.d4niella.pendenz.store

import io.github.d4niella.pendenz.TaskStatus
import java.sql.Connection
import java.time.Instant

/**
 * Everything Pendenz does to the task table, as plain operations inside a
 * [StoreTransaction] the store defines.
 *
 * This is the seam for other databases and for in-memory testing (ADR 0006).
 *
 * @param ID the id type of the table, see [io.github.d4niella.pendenz.schema.IdType].
 */
public interface TaskStore<ID : Any> {
    /** Runs [block] inside a transaction this store manages and returns its result. */
    public suspend fun <T> transaction(block: (StoreTransaction) -> T): T

    /**
     * Wraps a JDBC connection the caller owns. The caller commits or rolls
     * back; the store never does. A store without a database ignores it.
     */
    public fun adopt(connection: Connection): StoreTransaction

    /**
     * Inserts a task and returns its id, generated unless [NewTask.id] was
     * given. With an idempotency key that already exists for the type,
     * nothing is inserted and the existing task's id is returned.
     */
    public fun insert(
        tx: StoreTransaction,
        task: NewTask<ID>,
    ): ID

    /**
     * Moves due [TaskStatus.SCHEDULED] and [TaskStatus.RETRY_PENDING] tasks to
     * [TaskStatus.PENDING], and releases [TaskStatus.RUNNING] tasks whose lock
     * is older than [staleBefore]. Returns how many rows changed.
     */
    public fun promoteDue(
        tx: StoreTransaction,
        now: Instant,
        staleBefore: Instant,
    ): Int

    /** Claims up to [limit] pending tasks of the given types for [workerId]. */
    public fun claim(
        tx: StoreTransaction,
        workerId: String,
        types: Collection<String>,
        limit: Int,
        now: Instant,
    ): List<ClaimedTask<ID>>

    /** Refreshes the lock of a running task. Returns false if the row moved on without us. */
    public fun heartbeat(
        tx: StoreTransaction,
        id: ID,
        version: Long,
        now: Instant,
    ): Boolean

    /**
     * Finishes a claimed task. Only succeeds if [expectedVersion] still matches,
     * so a concurrent SQL intervention wins and the outcome is dropped.
     */
    public fun finish(
        tx: StoreTransaction,
        id: ID,
        expectedVersion: Long,
        outcome: Outcome,
    ): Boolean

    /**
     * Gives a claimed task back as [TaskStatus.PENDING] without an outcome,
     * because the worker is stopping. Same version check as [finish].
     */
    public fun release(
        tx: StoreTransaction,
        id: ID,
        expectedVersion: Long,
    ): Boolean

    /** Reads one task, mainly for operations and tests. */
    public fun find(
        tx: StoreTransaction,
        id: ID,
    ): TaskRecord<ID>?
}

/**
 * A unit of work in one [TaskStore]. Each store implements its own and
 * accepts no other; the handle carries no operations of its own.
 */
public interface StoreTransaction

/**
 * A task about to be inserted.
 *
 * @property id the id the caller chose, or null to let the id type generate one.
 * @property createdAt the queue's clock at the time of enqueuing.
 * @property idempotencyKey the caller's key, unique per type, or null.
 */
@Suppress("LongParameterList") // one parameter per concept the caller may set, by design
public class NewTask<ID : Any>(
    public val id: ID?,
    public val type: String,
    public val payload: String,
    public val runAt: Instant,
    public val status: TaskStatus,
    public val createdAt: Instant,
    public val idempotencyKey: String? = null,
)

/** A task a worker has just claimed. */
public class ClaimedTask<ID : Any>(
    public val id: ID,
    public val type: String,
    public val payload: String,
    public val attempt: Int,
    public val version: Long,
)

/** How a claimed task ended. */
public sealed interface Outcome {
    public data object Succeeded : Outcome

    public class Retry(
        public val runAt: Instant,
        public val error: String,
    ) : Outcome

    public class Failed(
        public val error: String,
    ) : Outcome
}

/** A full row, as read back. One parameter per mapped column, by design. */
@Suppress("LongParameterList")
public class TaskRecord<ID : Any>(
    public val id: ID,
    public val type: String,
    public val status: TaskStatus,
    public val payload: String,
    public val runAt: Instant,
    public val attempts: Int,
    public val lastError: String?,
    public val lockedBy: String?,
    public val lockedAt: Instant?,
    public val version: Long,
    public val createdAt: Instant,
    public val idempotencyKey: String?,
)
