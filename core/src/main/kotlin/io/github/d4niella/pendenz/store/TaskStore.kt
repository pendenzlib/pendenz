package io.github.d4niella.pendenz.store

import io.github.d4niella.pendenz.TaskStatus
import java.sql.Connection
import java.time.Instant

/**
 * Everything Pendenz does to the task table, as plain operations on a
 * connection the caller provides. The caller owns the transaction.
 *
 * This is the seam for other databases and for in-memory testing.
 */
public interface TaskStore {
    /** Inserts a task and returns its id. */
    public fun insert(
        connection: Connection,
        task: NewTask,
    ): Long

    /**
     * Moves due [TaskStatus.SCHEDULED] and [TaskStatus.RETRY_PENDING] tasks to
     * [TaskStatus.PENDING], and releases [TaskStatus.RUNNING] tasks whose lock
     * is older than [staleBefore]. Returns how many rows changed.
     */
    public fun promoteDue(
        connection: Connection,
        now: Instant,
        staleBefore: Instant,
    ): Int

    /** Claims up to [limit] pending tasks of the given types for [workerId]. */
    public fun claim(
        connection: Connection,
        workerId: String,
        types: Collection<String>,
        limit: Int,
        now: Instant,
    ): List<ClaimedTask>

    /** Refreshes the lock of a running task. Returns false if the row moved on without us. */
    public fun heartbeat(
        connection: Connection,
        id: Long,
        version: Long,
        now: Instant,
    ): Boolean

    /**
     * Finishes a claimed task. Only succeeds if [expectedVersion] still matches,
     * so a concurrent SQL intervention wins and the outcome is dropped.
     */
    public fun finish(
        connection: Connection,
        id: Long,
        expectedVersion: Long,
        outcome: Outcome,
    ): Boolean

    /** Reads one task, mainly for operations and tests. */
    public fun find(
        connection: Connection,
        id: Long,
    ): TaskRecord?
}

/** A task about to be inserted. */
public class NewTask(
    public val type: String,
    public val payload: String,
    public val runAt: Instant,
    public val status: TaskStatus,
)

/** A task a worker has just claimed. */
public class ClaimedTask(
    public val id: Long,
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
public class TaskRecord(
    public val id: Long,
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
)
