package io.github.d4niella.pendenz.store

import io.github.d4niella.pendenz.schema.PostgresDdl
import io.github.d4niella.pendenz.schema.SqlIdentifiers
import io.github.d4niella.pendenz.schema.TaskTable

/** The [SqlDialect] for PostgreSQL: `SKIP LOCKED`, `jsonb`, arrays and `RETURNING`. */
public object PostgresDialect : SqlDialect {
    override fun createTable(table: TaskTable<*>): String = PostgresDdl.createTable(table)

    override fun statements(table: TaskTable<*>): TaskSql {
        val s = Statements(table)
        return TaskSql(
            insert = s.insert,
            insertWithId = s.insertWithId,
            findIdByKey = s.findIdByKey,
            promoteDue = s.promoteDue,
            releaseStale = s.releaseStale,
            claim = s.claim,
            heartbeat = s.heartbeat,
            finish = s.finish,
            release = s.release,
            find = s.find,
        )
    }

    /** One statement per property, so each reads as a whole. */
    private class Statements(
        table: TaskTable<*>,
    ) {
        private val t = SqlIdentifiers.table(table.name)
        private val c = QuotedColumns(table)
        private val insertColumns =
            "${c.type}, ${c.status}, ${c.payload}, ${c.runAt}, ${c.attempts}, ${c.version}, ${c.createdAt}, " +
                "${c.idempotencyKey}, ${c.createdBy}"
        private val onDuplicateKey = "ON CONFLICT (${c.type}, ${c.idempotencyKey}) DO NOTHING"

        val insert =
            """
            INSERT INTO $t ($insertColumns)
            VALUES (?, ?, ?::jsonb, ?, 0, 0, ?, ?, ?)
            $onDuplicateKey
            RETURNING ${c.id}
            """.trimIndent()

        val insertWithId =
            """
            INSERT INTO $t (${c.id}, $insertColumns)
            VALUES (?, ?, ?, ?::jsonb, ?, 0, 0, ?, ?, ?)
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
                ORDER BY ${c.runAt}, ${c.id}
                LIMIT ?
                FOR UPDATE SKIP LOCKED
            )
            UPDATE $t AS task
            SET ${c.status} = ?, ${c.lockedBy} = ?, ${c.lockedAt} = ?,
                ${c.attempts} = task.${c.attempts} + 1, ${c.version} = task.${c.version} + 1
            FROM candidates
            WHERE task.${c.id} = candidates.${c.id}
            RETURNING task.${c.id}, task.${c.type}, task.${c.payload}, task.${c.attempts}, task.${c.version},
                      task.${c.createdBy}
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
                ${c.runAt} = COALESCE(?, ${c.runAt}), ${c.lastError} = ?, ${c.version} = ${c.version} + 1
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
            SELECT ${c.id}, ${c.type}, ${c.status}, ${c.payload}, ${c.runAt}, ${c.attempts},
                   ${c.lastError}, ${c.lockedBy}, ${c.lockedAt}, ${c.version}, ${c.createdAt}, ${c.idempotencyKey},
                   ${c.createdBy}
            FROM $t WHERE ${c.id} = ?
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
        val payload = SqlIdentifiers.column(cols.payload)
        val runAt = SqlIdentifiers.column(cols.runAt)
        val attempts = SqlIdentifiers.column(cols.attempts)
        val lastError = SqlIdentifiers.column(cols.lastError)
        val lockedBy = SqlIdentifiers.column(cols.lockedBy)
        val lockedAt = SqlIdentifiers.column(cols.lockedAt)
        val version = SqlIdentifiers.column(cols.version)
        val createdAt = SqlIdentifiers.column(cols.createdAt)
        val idempotencyKey = SqlIdentifiers.column(cols.idempotencyKey)
        val createdBy = SqlIdentifiers.column(cols.createdBy)
    }
}
