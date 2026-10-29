package io.github.pendenzlib.store

import io.github.pendenzlib.CycleRecord
import io.github.pendenzlib.CycleStatus
import io.github.pendenzlib.CycleStatusTransitions
import io.github.pendenzlib.PayloadColumn
import io.github.pendenzlib.TaskStatus
import io.github.pendenzlib.schema.CycleColumns
import io.github.pendenzlib.schema.IdType
import io.github.pendenzlib.schema.TaskColumns
import java.sql.ResultSet
import java.time.Instant

/** The [CycleStore] half of [JdbcTaskStore]: binds the [CycleSql] and reads cycle rows. */
@Suppress("TooManyFunctions") // one function per CycleStore operation, the interface dictates the count
internal class JdbcCycleStore<ID : Any>(
    private val sql: CycleSql,
    private val idType: IdType<ID>,
    private val task: TaskColumns,
    private val cycle: CycleColumns,
) : CycleStore<ID> {
    override fun insertCycle(
        tx: StoreTransaction,
        cycle: NewCycle<ID>,
    ): ID {
        val id = cycle.id ?: idType.generate()
        val statement = if (id == null) sql.insertCycle else sql.insertCycleWithId
        return tx.jdbc.query(statement, { bind ->
            if (id != null) bind.id(id)
            bind.string(cycle.type)
            bind.string(CycleStatus.ACTIVE.name)
            bind.string(cycle.description)
            bind.instant(cycle.createdAt)
            bind.string(cycle.createdBy)
        }) { rs ->
            check(rs.next()) { "INSERT ... RETURNING produced no row" }
            rs.id()
        }
    }

    override fun insertTasksFromQuery(
        tx: StoreTransaction,
        query: String,
        columns: List<PayloadColumn>,
        lane: String,
        fixed: NewTask<ID>,
        resourceSql: String?,
    ): Int =
        tx.jdbc.update(sql.insertTasksFromQuery(query, columns, resourceSql)) { bind ->
            bind.string(fixed.type)
            bind.string(fixed.status.name)
            bind.instant(fixed.runAt)
            bind.instant(fixed.createdAt)
            bind.string(fixed.createdBy)
            bind.int(fixed.payloadVersion)
            bind.string(lane)
            bind.int(fixed.priority)
            if (resourceSql == null) bind.string(fixed.resource)
            bind.string(fixed.correlationId)
        }

    override fun findCycle(
        tx: StoreTransaction,
        id: ID,
    ): CycleRecord<ID>? =
        tx.jdbc.query(sql.findCycle, { bind -> bind.id(id) }) { rs ->
            if (!rs.next()) {
                null
            } else {
                CycleRecord(
                    id = rs.id(),
                    type = rs.getString(cycle.type),
                    status = CycleStatus.valueOf(rs.getString(cycle.status)),
                    description = rs.getString(cycle.description),
                    createdAt = rs.instant(cycle.createdAt)!!,
                    createdBy = rs.getString(cycle.createdBy),
                    finishedAt = rs.instant(cycle.finishedAt),
                    version = rs.getLong(cycle.version),
                )
            }
        }

    override fun updateCycleStatus(
        tx: StoreTransaction,
        id: ID,
        from: Collection<CycleStatus>,
        to: CycleStatus,
        now: Instant,
    ): CycleStatus? {
        val connection = tx.jdbc
        return connection.query(sql.updateCycleStatus, { bind ->
            bind.string(to.name)
            bind.instant(now.takeIf { to.isFinal })
            bind.id(id)
            bind.stringArray(connection, from.map { it.name })
        }) { rs -> if (rs.next()) CycleStatus.valueOf(rs.getString(CycleSql.MOVED_FROM)) else null }
    }

    override fun quarantineLane(
        tx: StoreTransaction,
        lane: String,
        reason: String,
        now: Instant,
    ): Int {
        val connection = tx.jdbc
        return connection.update(sql.quarantineLane) { bind ->
            bind.instant(now)
            bind.string(reason)
            bind.string(lane)
            bind.stringArray(connection, OperatorMoves.pausable.map { it.name })
        }
    }

    override fun releaseLane(
        tx: StoreTransaction,
        lane: String,
        reason: String,
        now: Instant,
    ): Int =
        tx.jdbc.update(sql.releaseLane) { bind ->
            bind.instant(now)
            bind.string(reason)
            bind.string(lane)
        }

    override fun cancelLane(
        tx: StoreTransaction,
        lane: String,
        now: Instant,
    ): Int {
        val connection = tx.jdbc
        return connection.update(sql.cancelLane) { bind ->
            bind.instant(now)
            bind.string(null)
            bind.string(lane)
            bind.stringArray(connection, OperatorMoves.cancellable.map { it.name })
        }
    }

    override fun countLane(
        tx: StoreTransaction,
        lane: String,
    ): Map<TaskStatus, Int> =
        tx.jdbc.query(sql.countLane, { bind -> bind.string(lane) }) { rs ->
            buildMap {
                while (rs.next()) put(TaskStatus.valueOf(rs.getString(task.status)), rs.getInt("count"))
            }
        }

    override fun finishCycles(
        tx: StoreTransaction,
        now: Instant,
    ): Int {
        val connection = tx.jdbc
        return connection.update(sql.finishCycles) { bind ->
            bind.string(CycleStatus.FINISHED.name)
            bind.instant(now)
            bind.stringArray(connection, CycleStatusTransitions.sourcesOf(CycleStatus.FINISHED).map { it.name })
            bind.stringArray(connection, OperatorMoves.unfinished.map { it.name })
        }
    }

    override fun deleteFinishedCycles(
        tx: StoreTransaction,
        type: String,
        finishedBefore: Instant,
        limit: Int,
    ): Int =
        tx.jdbc.update(sql.deleteFinishedCycles) { bind ->
            bind.string(type)
            bind.string(CycleStatus.FINISHED.name)
            bind.string(CycleStatus.CANCELLED.name)
            bind.instant(finishedBefore)
            bind.int(limit)
        }

    private fun ResultSet.id(): ID = getObject(cycle.id, idType.javaType)
}
