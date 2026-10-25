package io.github.d4niella.pendenz.store

import io.github.d4niella.pendenz.Backlog
import io.github.d4niella.pendenz.TaskQuery
import io.github.d4niella.pendenz.TaskRecord
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

    /** Like [transaction] for a [block] that only reads; see [io.github.d4niella.pendenz.ConnectionProvider]. */
    public suspend fun <T> readTransaction(block: (StoreTransaction) -> T): T = transaction(block)

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
     * Up to [limit] [TaskStatus.RUNNING] tasks of the given [types] whose
     * lock expired before [now] (ADR 0027), the longest expired first. The
     * rows stay locked for this transaction and are skipped by every other
     * one, but are not changed: the worker records each as a failed attempt
     * through [finish] (ADR 0026).
     */
    public fun expiredLocks(
        tx: StoreTransaction,
        types: Collection<String>,
        now: Instant,
        limit: Int,
    ): List<ExpiredLock<ID>>

    /**
     * The `(priority, type, lane)` groups that have due pending tasks of
     * the given types, highest priority first, leaving out tasks whose
     * resource [terms] excludes. See ADR 0015 and ADR 0029.
     */
    public fun dueGroups(
        tx: StoreTransaction,
        types: Collection<String>,
        terms: ClaimTerms,
    ): List<ClaimGroup>

    /**
     * Claims up to [limit] due pending tasks of one [group], oldest first, on [terms]; tasks due at the same
     * instant come in no promised order. The store does not
     * count what it claims against resource limits; with limits, the caller claims one task per call and
     * excludes what became full (ADR 0029).
     */
    public fun claimFromGroup(
        tx: StoreTransaction,
        group: ClaimGroup,
        limit: Int,
        terms: ClaimTerms,
    ): List<ClaimedTask<ID>>

    /**
     * Claims up to [limit] due pending tasks of the given types, highest priority and oldest first, whatever
     * their lane, on [terms]; tasks due at the same instant come in no promised order.
     */
    public fun claim(
        tx: StoreTransaction,
        types: Collection<String>,
        limit: Int,
        terms: ClaimTerms,
    ): List<ClaimedTask<ID>>

    /** How many tasks run per resource, for resources with at least one running task (ADR 0029). */
    public fun runningPerResource(tx: StoreTransaction): Map<String, Int>

    /**
     * Moves the lock of a running task on to [lockedUntil], if [expectedVersion] still matches.
     * Returns false if the row moved on without us.
     */
    public fun heartbeat(
        tx: StoreTransaction,
        id: ID,
        expectedVersion: Long,
        lockedUntil: Instant,
    ): Boolean

    /**
     * Finishes a claimed task at [now]. Only succeeds if [expectedVersion]
     * still matches, so a concurrent SQL intervention wins and the outcome
     * is dropped. The worker inserts the handler's follow-ups in the same
     * transaction when the outcome applied (ADR 0019).
     *
     * Every outcome releases the lock and stores its provenance. `Succeeded`
     * and `Rejected` store their result, clear or set the error and set the
     * finish time; `Retry` sets the new run time and the error and clears
     * result and finish time; `Failed` sets the error and the finish time and
     * clears the result.
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

    /** The tasks matching [query], newest first by creation time, at most [TaskQuery.limit]. */
    public fun search(
        tx: StoreTransaction,
        query: TaskQuery,
    ): List<TaskRecord<ID>>

    /** One [Backlog] per task type that has rows in a status that is not final, sorted by type; due means by [now]. */
    public fun backlog(
        tx: StoreTransaction,
        now: Instant,
    ): List<Backlog>

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

    /**
     * Approves every AWAITING_APPROVAL task of [type] in [lane]; returns how
     * many. Follow-ups of other types in the lane are approved one by one.
     */
    public fun approveLane(
        tx: StoreTransaction,
        lane: String,
        type: String,
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
 * @property preview what running the task would do, computed by the type at enqueue, or null. See ADR 0020.
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
    public val preview: String? = null,
)

/**
 * Who claims, when, and which resources are off limits.
 *
 * @property workerId who claims.
 * @property now the worker's clock; tasks due at or before it may be claimed.
 * @property lockedUntil the deadline written into each claimed row (ADR 0027).
 * @property resources the resources to leave alone (ADR 0029).
 */
public class ClaimTerms
    @JvmOverloads
    constructor(
        public val workerId: String,
        public val now: Instant,
        public val lockedUntil: Instant,
        public val resources: ResourceFilter = ResourceFilter.NONE,
    )

/**
 * The resources a claim leaves alone: those at their limit, and those a
 * limit of zero pauses, unless a longer pattern with a limit above zero
 * matches them too (the longest match wins, ADR 0017).
 *
 * @property full resource values whose running tasks reached their limit.
 * @property pausedPatterns `LIKE` patterns of limit rows with a limit of zero.
 * @property otherPatterns `LIKE` patterns of the remaining limit rows, which may override a shorter paused one.
 */
public class ResourceFilter(
    public val full: Set<String>,
    public val pausedPatterns: List<String>,
    public val otherPatterns: List<String>,
) {
    public companion object {
        /** Leaves no resource alone, for stores without limits. */
        @JvmField
        public val NONE: ResourceFilter = ResourceFilter(emptySet(), emptyList(), emptyList())
    }
}

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

/**
 * A task a worker has just claimed.
 *
 * @property runAt the run time the task was due at, as it stood before the claim.
 * @property claimedAt the `now` the claim was made with.
 */
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
    public val runAt: Instant,
    public val claimedAt: Instant,
)

/**
 * A running task whose lock expired: its worker stopped confirming the lock (ADR 0026).
 *
 * @property task the row as a claim returns it; its `claimedAt` is the `now` the expiry was found with.
 * @property lockedBy the worker that held the lock, null when the row named none.
 * @property lockedUntil the deadline that passed.
 */
public class ExpiredLock<ID : Any>(
    public val task: ClaimedTask<ID>,
    public val lockedBy: String?,
    public val lockedUntil: Instant,
)

/**
 * How a claimed task ended. Every outcome carries the provenance pairs
 * the handler attached (ADR 0020).
 */
public sealed interface Outcome {
    public val provenance: Map<String, String>

    /** The handler returned and the result, if any, was accepted. */
    public class Succeeded
        @JvmOverloads
        constructor(
            public val result: String? = null,
            override val provenance: Map<String, String> = emptyMap(),
        ) : Outcome

    /** The handler returned, but the validator refused the result. */
    public class Rejected
        @JvmOverloads
        constructor(
            public val result: String?,
            public val reason: String,
            override val provenance: Map<String, String> = emptyMap(),
        ) : Outcome

    /** The handler threw and the retry policy wants another attempt at [runAt]. */
    public class Retry
        @JvmOverloads
        constructor(
            public val runAt: Instant,
            public val error: String,
            override val provenance: Map<String, String> = emptyMap(),
        ) : Outcome

    /** The handler threw and the retry policy gave up, or the failure is not one to retry. */
    public class Failed
        @JvmOverloads
        constructor(
            public val error: String,
            override val provenance: Map<String, String> = emptyMap(),
        ) : Outcome
}
