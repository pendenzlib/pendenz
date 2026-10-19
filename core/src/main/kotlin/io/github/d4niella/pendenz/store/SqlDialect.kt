package io.github.d4niella.pendenz.store

import io.github.d4niella.pendenz.PayloadColumn
import io.github.d4niella.pendenz.schema.CycleTable
import io.github.d4niella.pendenz.schema.TaskTable

/**
 * Produces the SQL that [JdbcTaskStore] runs. The store binds parameters in
 * the order [TaskSql] documents and reads columns by their mapped names;
 * everything database-specific lives here. See ADR 0008.
 */
public interface SqlDialect {
    /** DDL for the table and the index the claim statement relies on. */
    public fun createTable(table: TaskTable<*>): String

    /** The statements for one mapping and the payload columns of all registered task types. */
    public fun statements(
        table: TaskTable<*>,
        payloadColumns: List<PayloadColumn>,
    ): TaskSql

    /** The statements for cycles over [table], kept in [cycleTable]. See ADR 0016. */
    public fun cycleStatements(
        table: TaskTable<*>,
        cycleTable: CycleTable<*>,
        payloadColumns: List<PayloadColumn>,
    ): CycleSql
}

/**
 * One statement per store operation, with `?` placeholders. The binding
 * order is part of the contract and is listed per statement. Status values
 * are bound as text, instants as `OffsetDateTime` in UTC, task ids as the
 * id type's Java object.
 *
 * @property insert binds type, status, runAt, createdAt, idempotencyKey, createdBy, payloadVersion, lane,
 *   priority, then one value per payload column in the given order; returns the id column, or no row when
 *   the key already exists for the type.
 * @property insertWithId binds id, then the same as [insert].
 * @property findIdByKey binds type and idempotencyKey; returns the id column.
 * @property promoteDue binds the PENDING status, the SCHEDULED and RETRY_PENDING statuses, now.
 * @property releaseStale binds the PENDING status, the RUNNING status, the instant before which a lock is stale.
 * @property claim binds the PENDING status, now, the task types as a string array, the limit,
 *   the RUNNING status, the worker id, now; returns id, type, attempts, version, createdBy, payloadVersion,
 *   lane and every payload column.
 * @property dueGroups binds the PENDING status, now, the task types as a string array; returns priority,
 *   type and lane, highest priority first.
 * @property claimFromGroup binds the PENDING status, now, type, lane or null, priority, the limit, the RUNNING
 *   status, the worker id, now; returns the same columns as [claim].
 * @property heartbeat binds now, id, version, the RUNNING status.
 * @property finish binds the new status, the new runAt or null, the error or null, finishedAt or null, id,
 *   expected version, the RUNNING status.
 * @property release binds the PENDING status, id, expected version, the RUNNING status.
 * @property find binds id; returns all mapped columns.
 * @property deleteFinished binds type, the SUCCEEDED and CANCELLED statuses, the instant before which a task
 *   counts as expired, the limit.
 * @property archiveFinished takes the archive table name and returns a statement that binds like
 *   [deleteFinished] and moves the rows into that table.
 */
@Suppress("LongParameterList") // one parameter per statement, by design
public class TaskSql(
    public val insert: String,
    public val insertWithId: String,
    public val findIdByKey: String,
    public val promoteDue: String,
    public val releaseStale: String,
    public val claim: String,
    public val dueGroups: String,
    public val claimFromGroup: String,
    public val heartbeat: String,
    public val finish: String,
    public val release: String,
    public val find: String,
    public val deleteFinished: String,
    public val archiveFinished: (archiveTable: String) -> String,
)
