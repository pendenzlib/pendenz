package io.github.d4niella.pendenz.store

import io.github.d4niella.pendenz.ConnectionProvider
import io.github.d4niella.pendenz.TaskStatus
import io.github.d4niella.pendenz.schema.IdType
import io.github.d4niella.pendenz.schema.SqlIdentifiers
import io.github.d4niella.pendenz.schema.TaskColumns
import io.github.d4niella.pendenz.schema.TaskTable
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant

/**
 * [TaskStore] for PostgreSQL over plain JDBC. All SQL is built from the
 * [TaskTable] mapping; nothing about the table is hard-coded.
 *
 * @param table the mapping onto the application's table.
 * @param connections where transactions of its own come from.
 */
public class JdbcTaskStore<ID : Any>(
    table: TaskTable<ID>,
    private val connections: ConnectionProvider,
) : TaskStore<ID> {
    private val t = SqlIdentifiers.table(table.name)
    private val idType: IdType<ID> = table.idType
    private val names: TaskColumns = table.columns
    private val c = QuotedColumns(table.columns)

    override suspend fun <T> transaction(block: (StoreTransaction) -> T): T =
        connections.inTransaction { block(JdbcTransaction(it)) }

    override fun adopt(connection: Connection): StoreTransaction = JdbcTransaction(connection)

    private class JdbcTransaction(
        val connection: Connection,
    ) : StoreTransaction

    private val StoreTransaction.connection: Connection
        get() =
            (this as? JdbcTransaction)?.connection
                ?: throw IllegalArgumentException("transaction ${this::class.simpleName} is not from this store")

    override fun insert(
        tx: StoreTransaction,
        task: NewTask<ID>,
    ): ID {
        val connection = tx.connection
        val id = task.id ?: idType.generate()
        val idColumn = if (id == null) "" else "${c.id}, "
        val idValue = if (id == null) "" else "?, "
        val sql =
            """
            INSERT INTO $t ($idColumn${c.type}, ${c.status}, ${c.payload}, ${c.runAt}, ${c.attempts}, ${c.version})
            VALUES ($idValue?, ?, ?::jsonb, ?, 0, 0)
            RETURNING ${c.id}
            """.trimIndent()
        return connection.query(sql, { bind ->
            if (id != null) bind.id(id)
            bind.string(task.type)
            bind.string(task.status.name)
            bind.string(task.payload)
            bind.instant(task.runAt)
        }) { rs ->
            check(rs.next()) { "INSERT ... RETURNING produced no row" }
            rs.id()
        }
    }

    override fun promoteDue(
        tx: StoreTransaction,
        now: Instant,
        staleBefore: Instant,
    ): Int {
        val connection = tx.connection
        val due =
            """
            UPDATE $t SET ${c.status} = ?, ${c.version} = ${c.version} + 1
            WHERE ${c.status} IN (?, ?) AND ${c.runAt} <= ?
            """.trimIndent()
        val stale =
            """
            UPDATE $t SET ${c.status} = ?, ${c.lockedBy} = NULL, ${c.lockedAt} = NULL, ${c.version} = ${c.version} + 1
            WHERE ${c.status} = ? AND ${c.lockedAt} < ?
            """.trimIndent()
        val promoted =
            connection.update(due) { bind ->
                bind.string(TaskStatus.PENDING.name)
                bind.string(TaskStatus.SCHEDULED.name)
                bind.string(TaskStatus.RETRY_PENDING.name)
                bind.instant(now)
            }
        val released =
            connection.update(stale) { bind ->
                bind.string(TaskStatus.PENDING.name)
                bind.string(TaskStatus.RUNNING.name)
                bind.instant(staleBefore)
            }
        return promoted + released
    }

    override fun claim(
        tx: StoreTransaction,
        workerId: String,
        types: Collection<String>,
        limit: Int,
        now: Instant,
    ): List<ClaimedTask<ID>> {
        val connection = tx.connection
        if (types.isEmpty() || limit <= 0) return emptyList()
        val sql =
            """
            WITH candidates AS (
                SELECT ${c.id} FROM $t
                WHERE ${c.status} = ? AND ${c.runAt} <= ? AND ${c.type} = ANY (?)
                ORDER BY ${c.runAt}, ${c.id}
                LIMIT ?
                FOR UPDATE SKIP LOCKED
            )
            UPDATE $t AS task
            SET ${c.status} = ?, ${c.lockedBy} = ?, ${c.lockedAt} = ?,
                ${c.attempts} = task.${c.attempts} + 1, ${c.version} = task.${c.version} + 1
            FROM candidates
            WHERE task.${c.id} = candidates.${c.id}
            RETURNING task.${c.id}, task.${c.type}, task.${c.payload}, task.${c.attempts}, task.${c.version}
            """.trimIndent()
        return connection.query(sql, { bind ->
            bind.string(TaskStatus.PENDING.name)
            bind.instant(now)
            bind.stringArray(connection, types)
            bind.int(limit)
            bind.string(TaskStatus.RUNNING.name)
            bind.string(workerId)
            bind.instant(now)
        }) { rs ->
            buildList {
                while (rs.next()) {
                    add(
                        ClaimedTask(
                            id = rs.id(),
                            type = rs.getString(names.type),
                            payload = rs.getString(names.payload),
                            attempt = rs.getInt(names.attempts),
                            version = rs.getLong(names.version),
                        ),
                    )
                }
            }
        }
    }

    override fun heartbeat(
        tx: StoreTransaction,
        id: ID,
        version: Long,
        now: Instant,
    ): Boolean {
        val connection = tx.connection
        val sql =
            """
            UPDATE $t SET ${c.lockedAt} = ?
            WHERE ${c.id} = ? AND ${c.version} = ? AND ${c.status} = ?
            """.trimIndent()
        val updated =
            connection.update(sql) { bind ->
                bind.instant(now)
                bind.id(id)
                bind.long(version)
                bind.string(TaskStatus.RUNNING.name)
            }
        return updated == 1
    }

    override fun finish(
        tx: StoreTransaction,
        id: ID,
        expectedVersion: Long,
        outcome: Outcome,
    ): Boolean {
        val connection = tx.connection
        val (status, runAt, error) =
            when (outcome) {
                Outcome.Succeeded -> Triple(TaskStatus.SUCCEEDED, null, null)
                is Outcome.Retry -> Triple(TaskStatus.RETRY_PENDING, outcome.runAt, outcome.error)
                is Outcome.Failed -> Triple(TaskStatus.FAILED, null, outcome.error)
            }
        val sql =
            """
            UPDATE $t
            SET ${c.status} = ?, ${c.lockedBy} = NULL, ${c.lockedAt} = NULL,
                ${c.runAt} = COALESCE(?, ${c.runAt}), ${c.lastError} = ?, ${c.version} = ${c.version} + 1
            WHERE ${c.id} = ? AND ${c.version} = ? AND ${c.status} = ?
            """.trimIndent()
        val updated =
            connection.update(sql) { bind ->
                bind.string(status.name)
                bind.instant(runAt)
                bind.string(error)
                bind.id(id)
                bind.long(expectedVersion)
                bind.string(TaskStatus.RUNNING.name)
            }
        return updated == 1
    }

    override fun release(
        tx: StoreTransaction,
        id: ID,
        expectedVersion: Long,
    ): Boolean {
        val connection = tx.connection
        val sql =
            """
            UPDATE $t
            SET ${c.status} = ?, ${c.lockedBy} = NULL, ${c.lockedAt} = NULL, ${c.version} = ${c.version} + 1
            WHERE ${c.id} = ? AND ${c.version} = ? AND ${c.status} = ?
            """.trimIndent()
        val updated =
            connection.update(sql) { bind ->
                bind.string(TaskStatus.PENDING.name)
                bind.id(id)
                bind.long(expectedVersion)
                bind.string(TaskStatus.RUNNING.name)
            }
        return updated == 1
    }

    override fun find(
        tx: StoreTransaction,
        id: ID,
    ): TaskRecord<ID>? {
        val connection = tx.connection
        val sql =
            """
            SELECT ${c.id}, ${c.type}, ${c.status}, ${c.payload}, ${c.runAt}, ${c.attempts},
                   ${c.lastError}, ${c.lockedBy}, ${c.lockedAt}, ${c.version}, ${c.createdAt}
            FROM $t WHERE ${c.id} = ?
            """.trimIndent()
        return connection.query(sql, { bind -> bind.id(id) }) { rs ->
            if (!rs.next()) {
                null
            } else {
                TaskRecord(
                    id = rs.id(),
                    type = rs.getString(names.type),
                    status = TaskStatus.valueOf(rs.getString(names.status)),
                    payload = rs.getString(names.payload),
                    runAt = rs.instant(names.runAt)!!,
                    attempts = rs.getInt(names.attempts),
                    lastError = rs.getString(names.lastError),
                    lockedBy = rs.getString(names.lockedBy),
                    lockedAt = rs.instant(names.lockedAt),
                    version = rs.getLong(names.version),
                    createdAt = rs.instant(names.createdAt)!!,
                )
            }
        }
    }

    private fun ResultSet.id(): ID = getObject(names.id, idType.javaType)

    /** The mapped column names, quoted for use in SQL text. */
    private class QuotedColumns(
        cols: TaskColumns,
    ) {
        val id = SqlIdentifiers.column(cols.id)
        val type = SqlIdentifiers.column(cols.type)
        val status = SqlIdentifiers.column(cols.status)
        val payload = SqlIdentifiers.column(cols.payload)
        val runAt = SqlIdentifiers.column(cols.runAt)
        val attempts = SqlIdentifiers.column(cols.attempts)
        val lastError = SqlIdentifiers.column(cols.lastError)
        val lockedBy = SqlIdentifiers.column(cols.lockedBy)
        val lockedAt = SqlIdentifiers.column(cols.lockedAt)
        val version = SqlIdentifiers.column(cols.version)
        val createdAt = SqlIdentifiers.column(cols.createdAt)
    }
}
