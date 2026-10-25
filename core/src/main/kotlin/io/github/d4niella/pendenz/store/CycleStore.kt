package io.github.d4niella.pendenz.store

import io.github.d4niella.pendenz.CycleRecord
import io.github.d4niella.pendenz.CycleStatus
import io.github.d4niella.pendenz.PayloadColumn
import io.github.d4niella.pendenz.TaskStatus
import java.time.Instant

/**
 * Everything Pendenz does for cycles (ADR 0016): the cycle table, and bulk
 * operations on the tasks of one lane. Implemented by the stores beside
 * [TaskStore]; the JDBC store needs a cycle table mapping to be enabled.
 */
public interface CycleStore<ID : Any> {
    /** Inserts a cycle row and returns its id. */
    public fun insertCycle(
        tx: StoreTransaction,
        cycle: NewCycle<ID>,
    ): ID

    /**
     * Inserts one task per row of [query] into [lane], with the fixed
     * columns given here and the payload [columns] of the task type taken
     * from the query in their declared order. [resourceSql] is a SQL
     * expression over the query's columns, aliased `q`, for the resource
     * of each task; null uses the fixed resource. Returns how many tasks
     * were created.
     */
    public fun insertTasksFromQuery(
        tx: StoreTransaction,
        query: String,
        columns: List<PayloadColumn>,
        lane: String,
        fixed: NewTask<ID>,
        resourceSql: String?,
    ): Int

    public fun findCycle(
        tx: StoreTransaction,
        id: ID,
    ): CycleRecord<ID>?

    /** Moves a cycle from [from] to [to] with a version check; returns whether a row changed. */
    public fun updateCycleStatus(
        tx: StoreTransaction,
        id: ID,
        from: CycleStatus,
        to: CycleStatus,
        now: Instant,
    ): Boolean

    /** Sets aside the waiting tasks of [lane] as QUARANTINED with [reason]; returns how many. */
    public fun quarantineLane(
        tx: StoreTransaction,
        lane: String,
        reason: String,
    ): Int

    /** Gives back the tasks of [lane] that were quarantined with [reason]; returns how many. */
    public fun releaseLane(
        tx: StoreTransaction,
        lane: String,
        reason: String,
        now: Instant,
    ): Int

    /** Cancels every non-final task of [lane]; returns how many. */
    public fun cancelLane(
        tx: StoreTransaction,
        lane: String,
        now: Instant,
    ): Int

    /** How many tasks of [lane] are in each status. */
    public fun countLane(
        tx: StoreTransaction,
        lane: String,
    ): Map<TaskStatus, Int>

    /** Marks ACTIVE cycles FINISHED whose tasks are all final; returns how many. */
    public fun finishCycles(
        tx: StoreTransaction,
        now: Instant,
    ): Int

    /**
     * Deletes up to [limit] final cycles of [type] that finished before
     * [finishedBefore] and have no task left in their lane. Returns how
     * many rows went. Retention of the cycle row follows the type's rule
     * (ADR 0016).
     */
    public fun deleteFinishedCycles(
        tx: StoreTransaction,
        type: String,
        finishedBefore: Instant,
        limit: Int,
    ): Int

    public companion object {
        /** The reason written into quarantined tasks of a paused cycle; resuming releases exactly those. */
        public const val PAUSED_REASON: String = "cycle paused"
    }
}

/** A cycle about to be inserted. */
public class NewCycle<ID : Any>(
    public val id: ID?,
    public val type: String,
    public val description: String?,
    public val createdAt: Instant,
    public val createdBy: String?,
)
