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
@Suppress("TooManyFunctions") // one function per thing the queue does to the table; splitting would hide that
public interface TaskStore<ID : Any> {
    /** The cycle operations, or null when this store is not set up for cycles (ADR 0016). */
    public val cycles: CycleStore<ID>?
        get() = null

    /** The resource limit operations, or null when limits are not enabled (ADR 0017). */
    public val resourceLimits: ResourceLimitStore?
        get() = null

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
     * [TaskStatus.PENDING]. Returns how many rows changed.
     */
    public fun promoteDue(
        tx: StoreTransaction,
        now: Instant,
    ): Int

    /**
     * Gives [TaskStatus.RUNNING] tasks whose lock is older than [staleBefore]
     * back as [TaskStatus.PENDING]; their worker is presumed dead. Returns
     * how many rows changed.
     */
    public fun releaseStale(
        tx: StoreTransaction,
        staleBefore: Instant,
    ): Int

    /**
     * The `(priority, type, lane)` groups that have due pending tasks of
     * the given types, highest priority first. See ADR 0015.
     */
    public fun dueGroups(
        tx: StoreTransaction,
        types: Collection<String>,
        now: Instant,
    ): List<ClaimGroup>

    /** Claims up to [limit] due pending tasks of one [group] for [workerId], oldest first. */
    public fun claimFromGroup(
        tx: StoreTransaction,
        workerId: String,
        group: ClaimGroup,
        limit: Int,
        now: Instant,
    ): List<ClaimedTask<ID>>

    /** Claims up to [limit] due pending tasks of the given types for [workerId], oldest first, whatever their lane. */
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
     * Finishes a claimed task at [now]. Only succeeds if [expectedVersion]
     * still matches, so a concurrent SQL intervention wins and the outcome
     * is dropped. The worker inserts the handler's follow-ups in the same
     * transaction when the outcome applied (ADR 0019).
     */
    public fun finish(
        tx: StoreTransaction,
        id: ID,
        expectedVersion: Long,
        outcome: Outcome,
        now: Instant,
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

    /**
     * An operator's move: from one of [from] to [to], clearing the lock,
     * setting the finish time for a final [to] and clearing it otherwise,
     * and writing [reason] into the error column when given. Returns
     * whether the row changed. See ADR 0018.
     */
    @Suppress("LongParameterList") // one parameter per aspect of the move, by design
    public fun move(
        tx: StoreTransaction,
        id: ID,
        from: Collection<TaskStatus>,
        to: TaskStatus,
        reason: String?,
        now: Instant,
    ): Boolean

    /**
     * Approves an AWAITING_APPROVAL task: records [actor] and [now], and
     * moves it to PENDING, or to SCHEDULED when its run time is still
     * ahead. Returns whether the row changed.
     */
    public fun approve(
        tx: StoreTransaction,
        id: ID,
        actor: String,
        now: Instant,
    ): Boolean

    /** Approves every AWAITING_APPROVAL task of [lane]; returns how many. */
    public fun approveLane(
        tx: StoreTransaction,
        lane: String,
        actor: String,
        now: Instant,
    ): Int

    /**
     * Deletes up to [limit] final tasks of [type] that finished before
     * [finishedBefore], counting from creation when they have no finish
     * time. Returns how many rows went.
     */
    public fun deleteFinished(
        tx: StoreTransaction,
        type: String,
        finishedBefore: Instant,
        limit: Int,
    ): Int

    /** Like [deleteFinished], but moves the rows to [archiveTable] instead. */
    public fun archiveFinished(
        tx: StoreTransaction,
        type: String,
        finishedBefore: Instant,
        limit: Int,
        archiveTable: String,
    ): Int
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
 * @property createdBy who or what enqueued the task, or null.
 * @property payload column values, see [io.github.d4niella.pendenz.PayloadMapping].
 * @property payloadVersion the schema version of [payload].
 * @property lane the fairness group within the type, or null for the singles. See ADR 0015.
 * @property priority higher runs first; 0 by default.
 * @property resource what the task runs against, or null. See ADR 0017.
 * @property parentId the task whose handler enqueued this one, or null. See ADR 0019.
 * @property correlationId the chain this task belongs to, or null.
 */
@Suppress("LongParameterList") // one parameter per concept the caller may set, by design
public class NewTask<ID : Any>(
    public val id: ID?,
    public val type: String,
    public val payload: Map<String, Any?>,
    public val runAt: Instant,
    public val status: TaskStatus,
    public val createdAt: Instant,
    public val idempotencyKey: String? = null,
    public val createdBy: String? = null,
    public val payloadVersion: Int = 1,
    public val lane: String? = null,
    public val priority: Int = 0,
    public val resource: String? = null,
    public val parentId: ID? = null,
    public val correlationId: String? = null,
)

/** A group of due tasks the claim treats as one lane: same priority, type and lane. */
public class ClaimGroup(
    public val priority: Int,
    public val type: String,
    public val lane: String?,
) {
    override fun equals(other: Any?): Boolean =
        other is ClaimGroup && other.priority == priority && other.type == type && other.lane == lane

    override fun hashCode(): Int = (priority * 31 + type.hashCode()) * 31 + (lane?.hashCode() ?: 0)

    override fun toString(): String = "ClaimGroup(priority=$priority, type=$type, lane=$lane)"
}

/** A task a worker has just claimed. */
@Suppress("LongParameterList") // one parameter per column the handler may need, by design
public class ClaimedTask<ID : Any>(
    public val id: ID,
    public val type: String,
    public val payload: Map<String, Any?>,
    public val attempt: Int,
    public val version: Long,
    public val createdBy: String?,
    public val payloadVersion: Int,
    public val lane: String?,
    public val priority: Int,
    public val resource: String?,
    public val parentId: ID?,
    public val correlationId: String?,
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
    public val payload: Map<String, Any?>,
    public val runAt: Instant,
    public val attempts: Int,
    public val lastError: String?,
    public val lockedBy: String?,
    public val lockedAt: Instant?,
    public val version: Long,
    public val createdAt: Instant,
    public val idempotencyKey: String?,
    public val createdBy: String?,
    public val payloadVersion: Int,
    public val finishedAt: Instant?,
    public val lane: String?,
    public val priority: Int,
    public val resource: String?,
    public val approvedBy: String?,
    public val approvedAt: Instant?,
    public val parentId: ID?,
    public val correlationId: String?,
)
