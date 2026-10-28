package io.github.d4niella.pendenz.store

import io.github.d4niella.pendenz.PayloadColumn
import io.github.d4niella.pendenz.TaskQuery
import io.github.d4niella.pendenz.TaskStatus
import io.github.d4niella.pendenz.schema.CycleTable
import io.github.d4niella.pendenz.schema.ResourceLimitTable
import io.github.d4niella.pendenz.schema.TaskTable

/**
 * Produces the SQL that [JdbcTaskStore] runs. The store binds parameters in
 * the order [TaskSql] documents and reads columns by their mapped names;
 * everything database-specific lives here. See ADR 0008.
 */
public interface SqlDialect {
    /**
     * The statements for one mapping and the payload columns of all
     * registered task types. With a [limitTable], the claim statements skip
     * tasks whose resource is at its limit (ADR 0017).
     */
    public fun statements(
        table: TaskTable<*>,
        payloadColumns: List<PayloadColumn>,
        limitTable: ResourceLimitTable?,
    ): TaskSql

    /** The statements for the resource limit table. */
    public fun resourceLimitStatements(
        table: TaskTable<*>,
        limitTable: ResourceLimitTable,
    ): ResourceLimitSql

    /** The statements for cycles over [table], kept in [cycleTable]. See ADR 0016. */
    public fun cycleStatements(
        table: TaskTable<*>,
        cycleTable: CycleTable<*>,
    ): CycleSql
}

/**
 * One statement per store operation, with `?` placeholders. The binding
 * order is part of the contract and is listed per statement. Status values
 * are bound as text, instants as `OffsetDateTime` in UTC, task ids as the
 * id type's Java object.
 *
 * @property insert binds type, status, runAt, createdAt, idempotencyKey, createdBy, payloadVersion, lane,
 *   priority, resource, parentId, correlationId, preview, then one value per payload column in the given order;
 *   returns the id column, or no row when the key already exists for the type.
 * @property insertWithId binds id, then the same as [insert].
 * @property findIdByKey binds type and idempotencyKey; returns the id column.
 * @property promoteDue binds the PENDING status, the SCHEDULED and RETRY_PENDING statuses, now.
 * @property expiredLocks binds the RUNNING status, now, the task types as a string array, the limit;
 *   returns the same columns as [claim] plus lockedBy and lockedUntil, the longest expired first, locked
 *   with `FOR UPDATE SKIP LOCKED`.
 * @property claim binds the PENDING status, now, the task types as a string array, [the resource filter,] the
 *   limit, the RUNNING status, the worker id, the lock deadline; returns id, type, attempts, version, createdBy,
 *   payloadVersion, lane, priority, resource, parentId, correlationId, runAt and every payload column. With
 *   a limit table, the resource filter is three string arrays: the full resources, the paused patterns and the
 *   other patterns of [io.github.d4niella.pendenz.store.ResourceFilter] (ADR 0029); without one it binds nothing.
 * @property dueGroups binds the PENDING status, now, the task types as a string array, [the resource filter];
 *   returns priority, type and lane, highest priority first.
 * @property runningPerResource binds the RUNNING status; returns the resource column and the count of running
 *   tasks as [RUNNING_COUNT], one row per resource with running tasks.
 * @property claimFromLane binds the PENDING status, now, type, lane, priority, [the resource filter,] the limit,
 *   the RUNNING status, the worker id, the lock deadline; returns the same columns as [claim]. Lane and priority
 *   are equality conditions, so the claim index of ADR 0028 yields the rows in run time order.
 * @property claimWithoutLane binds the same as [claimFromLane] without the lane, for tasks whose lane is null.
 * @property heartbeat binds the new lock deadline, id, version, the RUNNING status.
 * @property finish binds the new status, the new runAt or null, the error or null, finishedAt or null, the
 *   result JSON or null, the provenance JSON or null, id, expected version, the RUNNING status.
 * @property release binds the PENDING status, id, expected version, the RUNNING status.
 * @property find binds id; returns all mapped columns.
 * @property search takes the query and returns a statement that binds, for each filter the query sets, in this
 *   order: createdBy, the types as a string array, the statuses as a string array, correlationId, lane, parentId,
 *   the cursor's creation time and id; then the limit. Returns the same columns as [find], newest first: by
 *   creation time and then by id, both descending.
 * @property backlog binds now, then the statuses to count as a string array; returns type, status, the row count
 *   as [BACKLOG_COUNT] and the earliest run time not after now as [BACKLOG_OLDEST_DUE], one row
 *   per type and status.
 * @property move takes the target status and returns a statement that binds now, the reason or null, id, then
 *   the source statuses as a string array. It writes what ADR 0031 describes for an operator's move.
 * @property approve binds the actor, now, null, id; releases an AWAITING_APPROVAL task to PENDING, or to
 *   SCHEDULED when its run time lies ahead.
 * @property approveLane binds the same as [approve] with the lane and the type in place of the id.
 * @property deleteFinished binds type, the SUCCEEDED and CANCELLED statuses, the instant before which a task
 *   counts as expired twice, once for the finish time and once for the creation time of rows without one,
 *   the limit.
 * @property archiveFinished takes the archive table name and returns a statement that binds like
 *   [deleteFinished] and moves the rows into that table.
 */
@Suppress("LongParameterList") // one parameter per statement, by design
public class TaskSql(
    public val insert: String,
    public val insertWithId: String,
    public val findIdByKey: String,
    public val promoteDue: String,
    public val expiredLocks: String,
    public val claim: String,
    public val dueGroups: String,
    public val runningPerResource: String,
    public val claimFromLane: String,
    public val claimWithoutLane: String,
    public val heartbeat: String,
    public val finish: String,
    public val release: String,
    public val find: String,
    public val search: (query: TaskQuery) -> String,
    public val backlog: String,
    public val move: (to: TaskStatus) -> String,
    public val approve: String,
    public val approveLane: String,
    public val deleteFinished: String,
    public val archiveFinished: (archiveTable: String) -> String,
) {
    public companion object {
        /** The column alias [TaskSql.runningPerResource] returns the count under. */
        public const val RUNNING_COUNT: String = "running_count"

        /** The column alias [TaskSql.backlog] returns the row count under. */
        public const val BACKLOG_COUNT: String = "backlog_count"

        /** The column alias [TaskSql.backlog] returns the earliest due run time under. */
        public const val BACKLOG_OLDEST_DUE: String = "backlog_oldest_due"
    }
}
