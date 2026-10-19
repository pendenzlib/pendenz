package io.github.d4niella.pendenz.store

import io.github.d4niella.pendenz.PayloadColumn
import io.github.d4niella.pendenz.SqlType
import io.github.d4niella.pendenz.schema.PostgresDdl
import io.github.d4niella.pendenz.schema.SqlIdentifiers
import io.github.d4niella.pendenz.schema.TaskTable

/** The [SqlDialect] for PostgreSQL: `SKIP LOCKED`, `jsonb`, arrays and `RETURNING`. */
public object PostgresDialect : SqlDialect {
    override fun createTable(table: TaskTable<*>): String = PostgresDdl.createTable(table)

    override fun statements(
        table: TaskTable<*>,
        payloadColumns: List<PayloadColumn>,
    ): TaskSql {
        val s = Statements(table, payloadColumns)
        return TaskSql(
            insert = s.insert,
            insertWithId = s.insertWithId,
            findIdByKey = s.findIdByKey,
            promoteDue = s.promoteDue,
            releaseStale = s.releaseStale,
            claim = s.claim,
            dueGroups = s.dueGroups,
            claimFromGroup = s.claimFromGroup,
            heartbeat = s.heartbeat,
            finish = s.finish,
            release = s.release,
            find = s.find,
            deleteFinished = s.deleteFinished,
            archiveFinished = s::archiveFinished,
        )
    }

    /** One statement per property, so each reads as a whole. */
    private class Statements(
        table: TaskTable<*>,
        payloadColumns: List<PayloadColumn>,
    ) {
        private val t = SqlIdentifiers.table(table.name)
        private val c = QuotedColumns(table)
        private val payload = payloadColumns.joinToString("") { ", ${SqlIdentifiers.column(it.name)}" }
        private val payloadInTask = payloadColumns.joinToString("") { ", task.${SqlIdentifiers.column(it.name)}" }
        private val payloadValues =
            payloadColumns.joinToString("") { if (it.type == SqlType.JSONB) ", ?::jsonb" else ", ?" }
        private val insertColumns =
            "${c.type}, ${c.status}, ${c.runAt}, ${c.attempts}, ${c.version}, ${c.createdAt}, " +
                "${c.idempotencyKey}, ${c.createdBy}, ${c.payloadVersion}, ${c.lane}, ${c.priority}$payload"
        private val claimed =
            "task.${c.id}, task.${c.type}, task.${c.attempts}, task.${c.version}, task.${c.createdBy}, " +
                "task.${c.payloadVersion}, task.${c.lane}$payloadInTask"
        private val onDuplicateKey = "ON CONFLICT (${c.type}, ${c.idempotencyKey}) DO NOTHING"

        val insert =
            """
            INSERT INTO $t ($insertColumns)
            VALUES (?, ?, ?, 0, 0, ?, ?, ?, ?, ?, ?$payloadValues)
            $onDuplicateKey
            RETURNING ${c.id}
            """.trimIndent()

        val insertWithId =
            """
            INSERT INTO $t (${c.id}, $insertColumns)
            VALUES (?, ?, ?, ?, 0, 0, ?, ?, ?, ?, ?, ?$payloadValues)
            $onDuplicateKey
            RETURNING ${c.id}
            """.trimIndent()

        val findIdByKey =
            """
            SELECT ${c.id} FROM $t WHERE ${c.type} = ? AND ${c.idempotencyKey} = ?
            """.trimIndent()

        val promoteDue =
            """
            UPDATE $t SET ${c.status} = ?, ${c.version} = ${c.version} + 1
            WHERE ${c.status} IN (?, ?) AND ${c.runAt} <= ?
            """.trimIndent()

        val releaseStale =
            """
            UPDATE $t SET ${c.status} = ?, ${c.lockedBy} = NULL, ${c.lockedAt} = NULL, ${c.version} = ${c.version} + 1
            WHERE ${c.status} = ? AND ${c.lockedAt} < ?
            """.trimIndent()

        val claim =
            """
            WITH candidates AS (
                SELECT ${c.id} FROM $t
                WHERE ${c.status} = ? AND ${c.runAt} <= ? AND ${c.type} = ANY (?)
                ORDER BY ${c.priority} DESC, ${c.runAt}, ${c.id}
                LIMIT ?
                FOR UPDATE SKIP LOCKED
            )
            UPDATE $t AS task
            SET ${c.status} = ?, ${c.lockedBy} = ?, ${c.lockedAt} = ?, ${c.finishedAt} = NULL,
                ${c.attempts} = task.${c.attempts} + 1, ${c.version} = task.${c.version} + 1
            FROM candidates
            WHERE task.${c.id} = candidates.${c.id}
            RETURNING $claimed
            """.trimIndent()

        val dueGroups =
            """
            SELECT DISTINCT ${c.priority}, ${c.type}, ${c.lane} FROM $t
            WHERE ${c.status} = ? AND ${c.runAt} <= ? AND ${c.type} = ANY (?)
            ORDER BY ${c.priority} DESC, ${c.type}, ${c.lane}
            """.trimIndent()

        val claimFromGroup =
            """
            WITH candidates AS (
                SELECT ${c.id} FROM $t
                WHERE ${c.status} = ? AND ${c.runAt} <= ?
                  AND ${c.type} = ? AND ${c.lane} IS NOT DISTINCT FROM ? AND ${c.priority} = ?
                ORDER BY ${c.runAt}, ${c.id}
                LIMIT ?
                FOR UPDATE SKIP LOCKED
            )
            UPDATE $t AS task
            SET ${c.status} = ?, ${c.lockedBy} = ?, ${c.lockedAt} = ?, ${c.finishedAt} = NULL,
                ${c.attempts} = task.${c.attempts} + 1, ${c.version} = task.${c.version} + 1
            FROM candidates
            WHERE task.${c.id} = candidates.${c.id}
            RETURNING $claimed
            """.trimIndent()

        val heartbeat =
            """
            UPDATE $t SET ${c.lockedAt} = ?
            WHERE ${c.id} = ? AND ${c.version} = ? AND ${c.status} = ?
            """.trimIndent()

        val finish =
            """
            UPDATE $t
            SET ${c.status} = ?, ${c.lockedBy} = NULL, ${c.lockedAt} = NULL,
                ${c.runAt} = COALESCE(?, ${c.runAt}), ${c.lastError} = ?, ${c.finishedAt} = ?,
                ${c.version} = ${c.version} + 1
            WHERE ${c.id} = ? AND ${c.version} = ? AND ${c.status} = ?
            """.trimIndent()

        val release =
            """
            UPDATE $t
            SET ${c.status} = ?, ${c.lockedBy} = NULL, ${c.lockedAt} = NULL, ${c.version} = ${c.version} + 1
            WHERE ${c.id} = ? AND ${c.version} = ? AND ${c.status} = ?
            """.trimIndent()

        val find =
            """
            SELECT ${c.id}, ${c.type}, ${c.status}, ${c.runAt}, ${c.attempts},
                   ${c.lastError}, ${c.lockedBy}, ${c.lockedAt}, ${c.version}, ${c.createdAt}, ${c.idempotencyKey},
                   ${c.createdBy}, ${c.payloadVersion}, ${c.finishedAt}, ${c.lane}, ${c.priority}$payload
            FROM $t WHERE ${c.id} = ?
            """.trimIndent()

        private val expired =
            """
            SELECT ${c.id} FROM $t
            WHERE ${c.type} = ? AND ${c.status} IN (?, ?) AND COALESCE(${c.finishedAt}, ${c.createdAt}) < ?
            LIMIT ?
            """.trimIndent()

        val deleteFinished =
            """
            DELETE FROM $t WHERE ${c.id} IN ($expired)
            """.trimIndent()

        fun archiveFinished(archiveTable: String): String =
            """
            WITH moved AS (DELETE FROM $t WHERE ${c.id} IN ($expired) RETURNING *)
            INSERT INTO ${SqlIdentifiers.table(archiveTable)} SELECT * FROM moved
            """.trimIndent()
    }

    /** The mapped column names, quoted for use in SQL text. */
    private class QuotedColumns(
        table: TaskTable<*>,
    ) {
        private val cols = table.columns
        val id = SqlIdentifiers.column(cols.id)
        val type = SqlIdentifiers.column(cols.type)
        val status = SqlIdentifiers.column(cols.status)
        val runAt = SqlIdentifiers.column(cols.runAt)
        val attempts = SqlIdentifiers.column(cols.attempts)
        val lastError = SqlIdentifiers.column(cols.lastError)
        val lockedBy = SqlIdentifiers.column(cols.lockedBy)
        val lockedAt = SqlIdentifiers.column(cols.lockedAt)
        val version = SqlIdentifiers.column(cols.version)
        val createdAt = SqlIdentifiers.column(cols.createdAt)
        val idempotencyKey = SqlIdentifiers.column(cols.idempotencyKey)
        val createdBy = SqlIdentifiers.column(cols.createdBy)
        val payloadVersion = SqlIdentifiers.column(cols.payloadVersion)
        val finishedAt = SqlIdentifiers.column(cols.finishedAt)
        val lane = SqlIdentifiers.column(cols.lane)
        val priority = SqlIdentifiers.column(cols.priority)
    }
}
