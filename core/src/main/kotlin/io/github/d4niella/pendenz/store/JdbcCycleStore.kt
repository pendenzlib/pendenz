package io.github.d4niella.pendenz.store

import io.github.d4niella.pendenz.CycleStatus
import io.github.d4niella.pendenz.TaskStatus
import io.github.d4niella.pendenz.schema.CycleColumns
import io.github.d4niella.pendenz.schema.IdType
import io.github.d4niella.pendenz.schema.TaskColumns
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
        lane: String,
        fixed: NewTask<ID>,
        resourceSql: String?,
    ): Int =
        tx.jdbc.update(sql.insertTasksFromQuery(query, resourceSql)) { bind ->
            bind.string(fixed.type)
            bind.string(TaskStatus.PENDING.name)
            bind.instant(fixed.runAt)
            bind.instant(fixed.createdAt)
            bind.string(fixed.createdBy)
            bind.int(fixed.payloadVersion)
            bind.string(lane)
            bind.int(fixed.priority)
            if (resourceSql == null) bind.string(fixed.resource)
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
        from: CycleStatus,
        to: CycleStatus,
        now: Instant,
    ): Boolean {
        val updated =
            tx.jdbc.update(sql.updateCycleStatus) { bind ->
                bind.string(to.name)
                bind.instant(now.takeIf { to.isFinal })
                bind.id(id)
                bind.string(from.name)
            }
        return updated == 1
    }

    override fun quarantineLane(
        tx: StoreTransaction,
        lane: String,
        reason: String,
    ): Int =
        tx.jdbc.update(sql.quarantineLane) { bind ->
            bind.string(TaskStatus.QUARANTINED.name)
            bind.string(reason)
            bind.string(lane)
            bind.string(TaskStatus.PENDING.name)
            bind.string(TaskStatus.SCHEDULED.name)
            bind.string(TaskStatus.RETRY_PENDING.name)
        }

    override fun releaseLane(
        tx: StoreTransaction,
        lane: String,
        reason: String,
        now: Instant,
    ): Int =
        tx.jdbc.update(sql.releaseLane) { bind ->
            bind.instant(now)
            bind.string(TaskStatus.SCHEDULED.name)
            bind.string(TaskStatus.PENDING.name)
            bind.string(lane)
            bind.string(TaskStatus.QUARANTINED.name)
            bind.string(reason)
        }

    override fun cancelLane(
        tx: StoreTransaction,
        lane: String,
        now: Instant,
    ): Int =
        tx.jdbc.update(sql.cancelLane) { bind ->
            bind.string(TaskStatus.CANCELLED.name)
            bind.instant(now)
            bind.string(lane)
            bind.string(TaskStatus.SUCCEEDED.name)
            bind.string(TaskStatus.CANCELLED.name)
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
    ): Int =
        tx.jdbc.update(sql.finishCycles) { bind ->
            bind.string(CycleStatus.FINISHED.name)
            bind.instant(now)
            bind.string(CycleStatus.ACTIVE.name)
            bind.string(TaskStatus.SUCCEEDED.name)
            bind.string(TaskStatus.CANCELLED.name)
        }

    private fun ResultSet.id(): ID = getObject(cycle.id, idType.javaType)
}
