package io.github.d4niella.pendenz.store

import io.github.d4niella.pendenz.CycleLane
import io.github.d4niella.pendenz.PayloadColumn
import io.github.d4niella.pendenz.SqlType
import io.github.d4niella.pendenz.TaskQuery
import io.github.d4niella.pendenz.schema.CycleTable
import io.github.d4niella.pendenz.schema.PostgresDdl
import io.github.d4niella.pendenz.schema.ResourceLimitTable
import io.github.d4niella.pendenz.schema.SqlIdentifiers
import io.github.d4niella.pendenz.schema.TaskTable

/** The [SqlDialect] for PostgreSQL: `SKIP LOCKED`, `jsonb`, arrays and `RETURNING`. */
public object PostgresDialect : SqlDialect {
    override fun createTable(table: TaskTable<*>): String = PostgresDdl.createTable(table)

    override fun statements(
        table: TaskTable<*>,
        payloadColumns: List<PayloadColumn>,
        limitTable: ResourceLimitTable?,
    ): TaskSql {
        val s = Statements(table, payloadColumns, limitTable)
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
            search = s::search,
            backlog = s.backlog,
            move = s.move,
            approve = s.approve,
            approveLane = s.approveLane,
            deleteFinished = s.deleteFinished,
            archiveFinished = s::archiveFinished,
        )
    }

    override fun resourceLimitStatements(
        table: TaskTable<*>,
        limitTable: ResourceLimitTable,
    ): ResourceLimitSql {
        val lt = SqlIdentifiers.table(limitTable.name)
        val name = SqlIdentifiers.column(limitTable.columns.name)
        val max = SqlIdentifiers.column(limitTable.columns.maxRunning)
        return ResourceLimitSql(
            setLimit =
                "INSERT INTO $lt ($name, $max) VALUES (?, ?) ON CONFLICT ($name) DO UPDATE SET $max = EXCLUDED.$max",
            seedLimit = "INSERT INTO $lt ($name, $max) VALUES (?, ?) ON CONFLICT ($name) DO NOTHING",
            removeLimit = "DELETE FROM $lt WHERE $name = ?",
            limits = "SELECT $name, $max FROM $lt ORDER BY $name",
            lockClaims = "SELECT pg_advisory_xact_lock(hashtext(?))",
        )
    }

    override fun cycleStatements(
        table: TaskTable<*>,
        cycleTable: CycleTable<*>,
    ): CycleSql {
        val s = CycleStatements(table, cycleTable)
        return CycleSql(
            insertCycle = s.insertCycle,
            insertCycleWithId = s.insertCycleWithId,
            insertTasksFromQuery = s::insertTasksFromQuery,
            findCycle = s.findCycle,
            updateCycleStatus = s.updateCycleStatus,
            quarantineLane = s.quarantineLane,
            releaseLane = s.releaseLane,
            cancelLane = s.cancelLane,
            countLane = s.countLane,
            finishCycles = s.finishCycles,
            deleteFinishedCycles = s.deleteFinishedCycles,
        )
    }

    /** The cycle statements, one per property. */
    private class CycleStatements(
        table: TaskTable<*>,
        cycleTable: CycleTable<*>,
    ) {
        private val t = SqlIdentifiers.table(table.name)
        private val c = QuotedColumns(table)
        private val ct = SqlIdentifiers.table(cycleTable.name)
        private val cc = QuotedCycleColumns(cycleTable)
        private val cycleColumns =
            "${cc.type}, ${cc.status}, ${cc.description}, ${cc.createdAt}, ${cc.createdBy}, ${cc.version}"
        private val allCycleColumns = "${cc.id}, $cycleColumns, ${cc.finishedAt}"

        val insertCycle =
            """
            INSERT INTO $ct ($cycleColumns) VALUES (?, ?, ?, ?, ?, 0)
            RETURNING ${cc.id}
            """.trimIndent()

        val insertCycleWithId =
            """
            INSERT INTO $ct (${cc.id}, $cycleColumns) VALUES (?, ?, ?, ?, ?, ?, 0)
            RETURNING ${cc.id}
            """.trimIndent()

        fun insertTasksFromQuery(
            query: String,
            columns: List<PayloadColumn>,
            resourceSql: String?,
        ): String {
            val payload = columns.joinToString("") { ", ${SqlIdentifiers.column(it.name)}" }
            return """
                INSERT INTO $t (${c.type}, ${c.status}, ${c.runAt}, ${c.attempts}, ${c.version}, ${c.createdAt},
                                ${c.createdBy}, ${c.payloadVersion}, ${c.lane}, ${c.priority}, ${c.resource},
                                ${c.correlationId}$payload)
                SELECT ?, ?, ?, 0, 0, ?, ?, ?, ?, ?, ${resourceSql ?: "?"}, ?, q.*
                FROM ($query) AS q
                """.trimIndent()
        }

        val findCycle =
            """
            SELECT $allCycleColumns FROM $ct WHERE ${cc.id} = ?
            """.trimIndent()

        val updateCycleStatus =
            """
            UPDATE $ct SET ${cc.status} = ?, ${cc.finishedAt} = ?, ${cc.version} = ${cc.version} + 1
            WHERE ${cc.id} = ? AND ${cc.status} = ?
            """.trimIndent()

        val quarantineLane =
            """
            UPDATE $t SET ${c.status} = ?, ${c.lastError} = ?, ${c.version} = ${c.version} + 1
            WHERE ${c.lane} = ? AND ${c.status} IN (?, ?, ?)
            """.trimIndent()

        val releaseLane =
            """
            UPDATE $t
            SET ${c.status} = CASE WHEN ${c.runAt} > ? THEN ? ELSE ? END, ${c.lastError} = NULL,
                ${c.version} = ${c.version} + 1
            WHERE ${c.lane} = ? AND ${c.status} = ? AND ${c.lastError} = ?
            """.trimIndent()

        val cancelLane =
            """
            UPDATE $t
            SET ${c.status} = ?, ${c.lockedBy} = NULL, ${c.lockedAt} = NULL, ${c.finishedAt} = ?,
                ${c.version} = ${c.version} + 1
            WHERE ${c.lane} = ? AND ${c.status} NOT IN (?, ?)
            """.trimIndent()

        val countLane =
            """
            SELECT ${c.status}, count(*) AS count FROM $t WHERE ${c.lane} = ? GROUP BY ${c.status}
            """.trimIndent()

        val finishCycles =
            """
            UPDATE $ct AS cycle SET ${cc.status} = ?, ${cc.finishedAt} = ?, ${cc.version} = cycle.${cc.version} + 1
            WHERE cycle.${cc.status} = ? AND NOT EXISTS (
                SELECT 1 FROM $t AS task
                WHERE task.${c.lane} = ${CycleLane.sql("cycle.${cc.id}")} AND task.${c.status} NOT IN (?, ?)
            )
            """.trimIndent()

        val deleteFinishedCycles =
            """
            DELETE FROM $ct WHERE ${cc.id} IN (
                SELECT cycle.${cc.id} FROM $ct AS cycle
                WHERE cycle.${cc.type} = ? AND cycle.${cc.status} IN (?, ?) AND cycle.${cc.finishedAt} < ?
                  AND NOT EXISTS (SELECT 1 FROM $t AS task WHERE task.${c.lane} = ${CycleLane.sql("cycle.${cc.id}")})
                LIMIT ?
            )
            """.trimIndent()
    }

    /** The mapped cycle column names, quoted for use in SQL text. */
    private class QuotedCycleColumns(
        table: CycleTable<*>,
    ) {
        private val cols = table.columns
        val id = SqlIdentifiers.column(cols.id)
        val type = SqlIdentifiers.column(cols.type)
        val status = SqlIdentifiers.column(cols.status)
        val description = SqlIdentifiers.column(cols.description)
        val createdAt = SqlIdentifiers.column(cols.createdAt)
        val createdBy = SqlIdentifiers.column(cols.createdBy)
        val finishedAt = SqlIdentifiers.column(cols.finishedAt)
        val version = SqlIdentifiers.column(cols.version)
    }

    /** One statement per property, so each reads as a whole. */
    private class Statements(
        table: TaskTable<*>,
        payloadColumns: List<PayloadColumn>,
        limitTable: ResourceLimitTable?,
    ) {
        private val t = SqlIdentifiers.table(table.name)
        private val c = QuotedColumns(table)

        /** With a limit table: the candidate's resource is null or below the longest matching limit. */
        private val withinLimit =
            if (limitTable == null) {
                ""
            } else {
                val lt = SqlIdentifiers.table(limitTable.name)
                val name = SqlIdentifiers.column(limitTable.columns.name)
                val max = SqlIdentifiers.column(limitTable.columns.maxRunning)
                """
                |AND (candidate.${c.resource} IS NULL OR (
                |    SELECT count(*) FROM $t AS running
                |    WHERE running.${c.status} = 'RUNNING' AND running.${c.resource} = candidate.${c.resource}
                |) < COALESCE((
                |    SELECT $max FROM $lt WHERE candidate.${c.resource} LIKE $name ORDER BY length($name) DESC LIMIT 1
                |), 2147483647))
                """.trimMargin().prependIndent("      ")
            }
        private val payload = payloadColumns.joinToString("") { ", ${SqlIdentifiers.column(it.name)}" }
        private val payloadInTask = payloadColumns.joinToString("") { ", task.${SqlIdentifiers.column(it.name)}" }
        private val payloadValues =
            payloadColumns.joinToString("") { if (it.type == SqlType.JSONB) ", ?::jsonb" else ", ?" }
        private val insertColumns =
            "${c.type}, ${c.status}, ${c.runAt}, ${c.attempts}, ${c.version}, ${c.createdAt}, " +
                "${c.idempotencyKey}, ${c.createdBy}, ${c.payloadVersion}, ${c.lane}, ${c.priority}, " +
                "${c.resource}, ${c.parentId}, ${c.correlationId}, ${c.preview}$payload"
        private val claimed =
            "task.${c.id}, task.${c.type}, task.${c.attempts}, task.${c.version}, task.${c.createdBy}, " +
                "task.${c.payloadVersion}, task.${c.lane}, task.${c.priority}, task.${c.resource}, " +
                "task.${c.parentId}, task.${c.correlationId}, task.${c.runAt}$payloadInTask"
        private val onDuplicateKey = "ON CONFLICT (${c.type}, ${c.idempotencyKey}) DO NOTHING"

        val insert =
            """
            INSERT INTO $t ($insertColumns)
            VALUES (?, ?, ?, 0, 0, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?$payloadValues)
            $onDuplicateKey
            RETURNING ${c.id}
            """.trimIndent()

        val insertWithId =
            """
            INSERT INTO $t (${c.id}, $insertColumns)
            VALUES (?, ?, ?, ?, 0, 0, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?$payloadValues)
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
                SELECT ${c.id} FROM $t AS candidate
                WHERE ${c.status} = ? AND ${c.runAt} <= ? AND ${c.type} = ANY (?)
            $withinLimit
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
                SELECT ${c.id} FROM $t AS candidate
                WHERE ${c.status} = ? AND ${c.runAt} <= ?
                  AND ${c.type} = ? AND ${c.lane} IS NOT DISTINCT FROM ? AND ${c.priority} = ?
            $withinLimit
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
                ${c.result} = ?::jsonb, ${c.provenance} = ?::jsonb, ${c.version} = ${c.version} + 1
            WHERE ${c.id} = ? AND ${c.version} = ? AND ${c.status} = ?
            """.trimIndent()

        val release =
            """
            UPDATE $t
            SET ${c.status} = ?, ${c.lockedBy} = NULL, ${c.lockedAt} = NULL, ${c.version} = ${c.version} + 1
            WHERE ${c.id} = ? AND ${c.version} = ? AND ${c.status} = ?
            """.trimIndent()

        private val selectRecord =
            """
            SELECT ${c.id}, ${c.type}, ${c.status}, ${c.runAt}, ${c.attempts},
                   ${c.lastError}, ${c.lockedBy}, ${c.lockedAt}, ${c.version}, ${c.createdAt}, ${c.idempotencyKey},
                   ${c.createdBy}, ${c.payloadVersion}, ${c.finishedAt}, ${c.lane}, ${c.priority}, ${c.resource},
                   ${c.approvedBy}, ${c.approvedAt}, ${c.parentId}, ${c.correlationId}, ${c.result}, ${c.provenance},
                   ${c.preview}$payload
            FROM $t
            """.trimIndent()

        val find = "$selectRecord WHERE ${c.id} = ?"

        fun search(query: TaskQuery): String {
            val conditions =
                listOfNotNull(
                    "${c.createdBy} = ?".takeIf { query.createdBy != null },
                    "${c.type} = ANY (?)".takeIf { query.types.isNotEmpty() },
                    "${c.status} = ANY (?)".takeIf { query.statuses.isNotEmpty() },
                    "${c.correlationId} = ?".takeIf { query.correlationId != null },
                )
            val where = if (conditions.isEmpty()) "" else " WHERE " + conditions.joinToString(" AND ")
            return "$selectRecord$where ORDER BY ${c.createdAt} DESC, ${c.id} DESC LIMIT ?"
        }

        val backlog =
            """
            SELECT ${c.type}, ${c.status}, count(*) AS ${TaskSql.BACKLOG_COUNT},
                   min(${c.runAt}) FILTER (WHERE ${c.runAt} <= ?) AS ${TaskSql.BACKLOG_OLDEST_DUE}
            FROM $t WHERE ${c.status} = ANY (?)
            GROUP BY ${c.type}, ${c.status}
            """.trimIndent()

        val move =
            """
            UPDATE $t
            SET ${c.status} = ?, ${c.lockedBy} = NULL, ${c.lockedAt} = NULL, ${c.finishedAt} = ?,
                ${c.lastError} = COALESCE(?, ${c.lastError}), ${c.version} = ${c.version} + 1
            WHERE ${c.id} = ? AND ${c.status} = ANY (?)
            """.trimIndent()

        private val approveSet =
            """
            SET ${c.status} = CASE WHEN ${c.runAt} > ? THEN ? ELSE ? END,
                ${c.approvedBy} = ?, ${c.approvedAt} = ?, ${c.version} = ${c.version} + 1
            """.trimIndent()

        val approve =
            """
            UPDATE $t
            $approveSet
            WHERE ${c.id} = ? AND ${c.status} = ?
            """.trimIndent()

        val approveLane =
            """
            UPDATE $t
            $approveSet
            WHERE ${c.lane} = ? AND ${c.type} = ? AND ${c.status} = ?
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
        val resource = SqlIdentifiers.column(cols.resource)
        val approvedBy = SqlIdentifiers.column(cols.approvedBy)
        val approvedAt = SqlIdentifiers.column(cols.approvedAt)
        val parentId = SqlIdentifiers.column(cols.parentId)
        val correlationId = SqlIdentifiers.column(cols.correlationId)
        val result = SqlIdentifiers.column(cols.result)
        val provenance = SqlIdentifiers.column(cols.provenance)
        val preview = SqlIdentifiers.column(cols.preview)
    }
}
