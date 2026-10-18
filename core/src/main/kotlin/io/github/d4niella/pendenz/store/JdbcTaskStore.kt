package io.github.d4niella.pendenz.store

import io.github.d4niella.pendenz.TaskStatus
import io.github.d4niella.pendenz.schema.SqlIdentifiers
import io.github.d4niella.pendenz.schema.TaskColumns
import io.github.d4niella.pendenz.schema.TaskTable
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * [TaskStore] for PostgreSQL over plain JDBC. All SQL is built from the
 * [TaskTable] mapping; nothing about the table is hard-coded.
 */
public class JdbcTaskStore(
    table: TaskTable,
) : TaskStore {
    private val t = SqlIdentifiers.table(table.name)
    private val names: TaskColumns = table.columns
    private val c = QuotedColumns(table.columns)

    override fun insert(
        connection: Connection,
        task: NewTask,
    ): Long {
        val sql =
            """
            INSERT INTO $t (${c.type}, ${c.status}, ${c.payload}, ${c.runAt}, ${c.attempts}, ${c.version})
            VALUES (?, ?, ?::jsonb, ?, 0, 0)
            RETURNING ${c.id}
            """.trimIndent()
        return connection.query(sql, { bind ->
            bind.string(task.type)
            bind.string(task.status.name)
            bind.string(task.payload)
            bind.instant(task.runAt)
        }) { rs ->
            check(rs.next()) { "INSERT ... RETURNING produced no row" }
            rs.getLong(names.id)
        }
    }

    override fun promoteDue(
        connection: Connection,
        now: Instant,
        staleBefore: Instant,
    ): Int {
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
        connection: Connection,
        workerId: String,
        types: Collection<String>,
        limit: Int,
        now: Instant,
    ): List<ClaimedTask> {
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
                            id = rs.getLong(names.id),
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
        connection: Connection,
        id: Long,
        version: Long,
        now: Instant,
    ): Boolean {
        val sql =
            """
            UPDATE $t SET ${c.lockedAt} = ?
            WHERE ${c.id} = ? AND ${c.version} = ? AND ${c.status} = ?
            """.trimIndent()
        val updated =
            connection.update(sql) { bind ->
                bind.instant(now)
                bind.long(id)
                bind.long(version)
                bind.string(TaskStatus.RUNNING.name)
            }
        return updated == 1
    }

    override fun finish(
        connection: Connection,
        id: Long,
        expectedVersion: Long,
        outcome: Outcome,
    ): Boolean {
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
                bind.long(id)
                bind.long(expectedVersion)
                bind.string(TaskStatus.RUNNING.name)
            }
        return updated == 1
    }

    override fun find(
        connection: Connection,
        id: Long,
    ): TaskRecord? {
        val sql =
            """
            SELECT ${c.id}, ${c.type}, ${c.status}, ${c.payload}, ${c.runAt}, ${c.attempts},
                   ${c.lastError}, ${c.lockedBy}, ${c.lockedAt}, ${c.version}, ${c.createdAt}
            FROM $t WHERE ${c.id} = ?
            """.trimIndent()
        return connection.query(sql, { bind -> bind.long(id) }) { rs ->
            if (!rs.next()) {
                null
            } else {
                TaskRecord(
                    id = rs.getLong(names.id),
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

    /** Binds parameters in order, so SQL text and binding read the same way. */
    private class Binder(
        private val stmt: PreparedStatement,
    ) {
        private var index = 0

        fun string(value: String?) = stmt.setString(++index, value)

        fun long(value: Long) = stmt.setLong(++index, value)

        fun int(value: Int) = stmt.setInt(++index, value)

        fun instant(value: Instant?) {
            if (value == null) {
                stmt.setNull(++index, Types.TIMESTAMP_WITH_TIMEZONE)
            } else {
                stmt.setObject(++index, OffsetDateTime.ofInstant(value, ZoneOffset.UTC))
            }
        }

        fun stringArray(
            connection: Connection,
            values: Collection<String>,
        ) = stmt.setArray(++index, connection.createArrayOf("varchar", values.toTypedArray()))
    }

    private inline fun <T> Connection.query(
        sql: String,
        bind: (Binder) -> Unit,
        read: (ResultSet) -> T,
    ): T =
        prepareStatement(sql).use { stmt ->
            bind(Binder(stmt))
            stmt.executeQuery().use(read)
        }

    private inline fun Connection.update(
        sql: String,
        bind: (Binder) -> Unit,
    ): Int =
        prepareStatement(sql).use { stmt ->
            bind(Binder(stmt))
            stmt.executeUpdate()
        }

    private fun ResultSet.instant(column: String): Instant? = getObject(column, OffsetDateTime::class.java)?.toInstant()
}
