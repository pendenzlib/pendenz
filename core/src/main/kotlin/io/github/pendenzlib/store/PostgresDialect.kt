package io.github.pendenzlib.store

import io.github.pendenzlib.CycleLane
import io.github.pendenzlib.PayloadColumn
import io.github.pendenzlib.SqlType
import io.github.pendenzlib.TaskQuery
import io.github.pendenzlib.TaskStatus
import io.github.pendenzlib.schema.CycleTable
import io.github.pendenzlib.schema.PostgresMoves
import io.github.pendenzlib.schema.QuotedColumns
import io.github.pendenzlib.schema.QuotedCycleColumns
import io.github.pendenzlib.schema.ResourceLimitTable
import io.github.pendenzlib.schema.SqlIdentifiers
import io.github.pendenzlib.schema.TaskTable

/** The [SqlDialect] for PostgreSQL: `SKIP LOCKED`, `jsonb`, arrays and `RETURNING`. */
public object PostgresDialect : SqlDialect {
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
            expiredLocks = s.expiredLocks,
            claim = s.claim,
            dueGroups = s.dueGroups,
            runningPerResource = s.runningPerResource,
            claimFromLane = s.claimFromLane,
            claimWithoutLane = s.claimWithoutLane,
            heartbeat = s.heartbeat,
            holdCancelled = s.holdCancelled,
            unlockCancelled = s.unlockCancelled,
            unlockExpiredCancelled = s.unlockExpiredCancelled,
            finish = s.finish,
            release = s.release,
            find = s.find,
            search = s::search,
            backlog = s.backlog,
            move = s::move,
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
            lockClaims = lockClaims(table),
        )
    }

    /**
     * The two-key form, Pendenz's class and the table's OID: the same lock
     * however the table is spelled, and apart from the one-key locks of the
     * application. The name is a literal because there is nothing to bind.
     */
    private fun lockClaims(table: TaskTable<*>): String {
        val name = SqlIdentifiers.table(table.name).replace("'", "''")
        return "SELECT pg_advisory_xact_lock($ADVISORY_LOCK_CLASS, '$name'::regclass::oid::int)"
    }

    /** The first key of every advisory lock Pendenz takes: "PEND" in ASCII. */
    private const val ADVISORY_LOCK_CLASS = 0x50454E44

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
        private val moves = PostgresMoves(c)
        private val args = PostgresMoves.BOUND_ARGS
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
            FROM (
                SELECT ${cc.id} AS moved_id, ${cc.status} AS ${CycleSql.MOVED_FROM} FROM $ct
                WHERE ${cc.id} = ? AND ${cc.status} = ANY (?) FOR UPDATE
            ) AS moved
            WHERE ${cc.id} = moved.moved_id
            RETURNING moved.${CycleSql.MOVED_FROM}
            """.trimIndent()

        val quarantineLane =
            """
            UPDATE $t SET ${moves.to(TaskStatus.QUARANTINED)}
            FROM $args
            WHERE ${c.lane} = ? AND ${c.status} = ANY (?)
            """.trimIndent()

        val releaseLane =
            """
            UPDATE $t SET ${moves.release()}, ${c.lastError} = NULL
            FROM $args
            WHERE ${c.lane} = ? AND ${c.status} = '${TaskStatus.QUARANTINED.name}'
              AND ${c.lastError} = ${PostgresMoves.ARGS}.${PostgresMoves.REASON}
            """.trimIndent()

        val cancelLane =
            """
            UPDATE $t SET ${moves.to(TaskStatus.CANCELLED)}
            FROM $args
            WHERE ${c.lane} = ? AND ${c.status} = ANY (?)
            """.trimIndent()

        val countLane =
            """
            SELECT ${c.status}, count(*) AS count FROM $t WHERE ${c.lane} = ? GROUP BY ${c.status}
            """.trimIndent()

        val finishCycles =
            """
            UPDATE $ct AS cycle SET ${cc.status} = ?, ${cc.finishedAt} = ?, ${cc.version} = cycle.${cc.version} + 1
            WHERE cycle.${cc.status} = ANY (?) AND NOT EXISTS (
                SELECT 1 FROM $t AS task
                WHERE task.${c.lane} = ${CycleLane.sql("cycle.${cc.id}")} AND task.${c.status} = ANY (?)
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

    /** One statement per property, so each reads as a whole. */
    private class Statements(
        table: TaskTable<*>,
        payloadColumns: List<PayloadColumn>,
        limitTable: ResourceLimitTable?,
    ) {
        private val t = SqlIdentifiers.table(table.name)
        private val c = QuotedColumns(table)
        private val moves = PostgresMoves(c)
        private val args = PostgresMoves.BOUND_ARGS

        /**
         * With a limit table: the candidate's resource is null, not full, and not paused by a limit of zero
         * that no longer pattern overrides. Binds the full resources, the paused patterns and the other
         * patterns as arrays; reads no table, the worker computed them once for the poll (ADR 0029).
         */
        private val withinLimit =
            if (limitTable == null) {
                ""
            } else {
                """
                |AND (candidate.${c.resource} IS NULL OR (
                |    candidate.${c.resource} <> ALL (?)
                |    AND NOT EXISTS (
                |        SELECT 1 FROM unnest(?::text[]) AS paused(pattern)
                |        WHERE candidate.${c.resource} LIKE paused.pattern AND NOT EXISTS (
                |            SELECT 1 FROM unnest(?::text[]) AS other(pattern)
                |            WHERE length(other.pattern) > length(paused.pattern) AND candidate.${c.resource} LIKE other.pattern
                |        )
                |    )
                |))
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

        // Rows another transaction holds are promoted by the next poll; waiting for them would stall the claim
        val promoteDue =
            """
            UPDATE $t SET ${c.status} = ?, ${c.version} = ${c.version} + 1
            WHERE ${c.id} IN (
                SELECT ${c.id} FROM $t WHERE ${c.status} IN (?, ?) AND ${c.runAt} <= ? FOR UPDATE SKIP LOCKED
            )
            """.trimIndent()

        val expiredLocks =
            """
            SELECT $claimed, task.${c.lockedBy}, task.${c.lockedUntil} FROM $t AS task
            WHERE task.${c.status} = ? AND task.${c.lockedUntil} < ? AND task.${c.type} = ANY (?)
            ORDER BY task.${c.lockedUntil}
            LIMIT ?
            FOR UPDATE SKIP LOCKED
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
            SET ${c.status} = ?, ${c.lockedBy} = ?, ${c.lockedUntil} = ?, ${c.finishedAt} = NULL,
                ${c.attempts} = task.${c.attempts} + 1, ${c.version} = task.${c.version} + 1
            FROM candidates
            WHERE task.${c.id} = candidates.${c.id}
            RETURNING $claimed
            """.trimIndent()

        val dueGroups =
            """
            SELECT DISTINCT ${c.priority}, ${c.type}, ${c.lane} FROM $t AS candidate
            WHERE ${c.status} = ? AND ${c.runAt} <= ? AND ${c.type} = ANY (?)
            $withinLimit
            ORDER BY ${c.priority} DESC, ${c.type}, ${c.lane}
            """.trimIndent()

        // A cancelled task whose handler has not returned holds its slot through its live lock (ADR 0031).
        // Each branch matches the predicate of one partial index, `_running_idx` and `_held_idx` (ADR 0028).
        val runningPerResource =
            """
            SELECT ${c.resource}, count(*) AS ${TaskSql.RUNNING_COUNT} FROM $t
            WHERE ${c.resource} IS NOT NULL
              AND (${c.status} = ? OR (${c.status} = ? AND ${c.lockedBy} IS NOT NULL AND ${c.lockedUntil} > ?))
            GROUP BY ${c.resource}
            """.trimIndent()

        // Two statements instead of `lane IS NOT DISTINCT FROM ?`, which no B-tree index can serve (ADR 0028).
        // The lane leads the order although it is fixed: the planner treats `IS NULL` as no constant, and
        // would sort instead of reading the claim index in order.
        val claimFromLane = claimFromGroup("${c.lane} = ?")
        val claimWithoutLane = claimFromGroup("${c.lane} IS NULL")

        private fun claimFromGroup(lane: String) =
            """
            WITH candidates AS (
                SELECT ${c.id} FROM $t AS candidate
                WHERE ${c.status} = ? AND ${c.runAt} <= ?
                  AND ${c.type} = ? AND $lane AND ${c.priority} = ?
            $withinLimit
                ORDER BY ${c.lane}, ${c.runAt}, ${c.id}
                LIMIT ?
                FOR UPDATE SKIP LOCKED
            )
            UPDATE $t AS task
            SET ${c.status} = ?, ${c.lockedBy} = ?, ${c.lockedUntil} = ?, ${c.finishedAt} = NULL,
                ${c.attempts} = task.${c.attempts} + 1, ${c.version} = task.${c.version} + 1
            FROM candidates
            WHERE task.${c.id} = candidates.${c.id}
            RETURNING $claimed
            """.trimIndent()

        val heartbeat =
            """
            UPDATE $t SET ${c.lockedUntil} = ?
            WHERE ${c.id} = ? AND ${c.version} = ? AND ${c.status} = ?
            """.trimIndent()

        val holdCancelled =
            """
            UPDATE $t SET ${c.lockedUntil} = ?
            WHERE ${c.id} = ? AND ${c.lockedBy} = ? AND ${c.status} = ?
            """.trimIndent()

        val unlockCancelled =
            """
            UPDATE $t SET ${c.lockedBy} = NULL, ${c.lockedUntil} = NULL
            WHERE ${c.id} = ? AND ${c.lockedBy} = ? AND ${c.status} = ?
            """.trimIndent()

        val unlockExpiredCancelled =
            """
            UPDATE $t SET ${c.lockedBy} = NULL, ${c.lockedUntil} = NULL
            WHERE ${c.status} = ? AND ${c.lockedBy} IS NOT NULL AND ${c.lockedUntil} < ?
            """.trimIndent()

        val finish =
            """
            UPDATE $t
            SET ${c.status} = ?, ${c.lockedBy} = NULL, ${c.lockedUntil} = NULL,
                ${c.runAt} = COALESCE(?, ${c.runAt}), ${c.lastError} = ?, ${c.finishedAt} = ?,
                ${c.result} = ?::jsonb, ${c.provenance} = ?::jsonb, ${c.version} = ${c.version} + 1
            WHERE ${c.id} = ? AND ${c.version} = ? AND ${c.status} = ?
            """.trimIndent()

        val release =
            """
            UPDATE $t
            SET ${c.status} = ?, ${c.lockedBy} = NULL, ${c.lockedUntil} = NULL, ${c.version} = ${c.version} + 1
            WHERE ${c.id} = ? AND ${c.version} = ? AND ${c.status} = ?
            """.trimIndent()

        private val selectRecord =
            """
            SELECT ${c.id}, ${c.type}, ${c.status}, ${c.runAt}, ${c.attempts},
                   ${c.lastError}, ${c.lockedBy}, ${c.lockedUntil}, ${c.version}, ${c.createdAt}, ${c.idempotencyKey},
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
                    "${c.lane} = ?".takeIf { query.lane != null },
                    "${c.parentId} = ?".takeIf { query.parentId != null },
                    "(${c.createdAt}, ${c.id}) < (?, ?)".takeIf { query.olderThan != null },
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

        fun move(to: TaskStatus): String =
            """
            UPDATE $t SET ${moves.to(to)}
            FROM $args, (
                SELECT ${c.id} AS moved_id, ${c.status} AS ${TaskSql.MOVED_FROM} FROM $t
                WHERE ${c.id} = ? AND ${c.status} = ANY (?) FOR UPDATE
            ) AS moved
            WHERE ${c.id} = moved.moved_id
            RETURNING moved.${TaskSql.MOVED_FROM}
            """.trimIndent()

        private val approveSet =
            """
            SET ${moves.release()}, ${c.approvedBy} = ?, ${c.approvedAt} = ${PostgresMoves.ARGS}.${PostgresMoves.NOW}
            FROM $args
            """.trimIndent()

        val approve =
            """
            UPDATE $t
            $approveSet
            WHERE ${c.id} = ? AND ${c.status} = '${TaskStatus.AWAITING_APPROVAL.name}'
            """.trimIndent()

        val approveLane =
            """
            UPDATE $t
            $approveSet
            WHERE ${c.lane} = ? AND ${c.type} = ? AND ${c.status} = '${TaskStatus.AWAITING_APPROVAL.name}'
            """.trimIndent()

        private val expired =
            """
            SELECT ${c.id} FROM $t
            WHERE ${c.type} = ? AND ${c.status} IN (?, ?)
              AND (${c.finishedAt} < ? OR (${c.finishedAt} IS NULL AND ${c.createdAt} < ?))
            LIMIT ?
            FOR UPDATE SKIP LOCKED
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
}
