package io.github.d4niella.pendenz.store

import io.github.d4niella.pendenz.ConnectionProvider
import io.github.d4niella.pendenz.PayloadColumn
import io.github.d4niella.pendenz.TaskStatus
import io.github.d4niella.pendenz.schema.CycleTable
import io.github.d4niella.pendenz.schema.IdType
import io.github.d4niella.pendenz.schema.TaskColumns
import io.github.d4niella.pendenz.schema.TaskTable
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant

/**
 * [TaskStore] over plain JDBC. The SQL comes from the [SqlDialect], the
 * column names from the [TaskTable] mapping; this class binds parameters
 * and reads rows.
 *
 * @param table the mapping onto the application's table.
 * @param connections where transactions of its own come from.
 * @param payloadColumns the payload columns of every task type that uses the table,
 *   see [io.github.d4niella.pendenz.schema.payloadColumnsOf].
 * @param dialect the database's SQL; PostgreSQL by default.
 * @param cycleTable the cycle table mapping, or null when the application does not use cycles (ADR 0016).
 */
@Suppress("TooManyFunctions") // one function per TaskStore operation, the interface dictates the count
public class JdbcTaskStore<ID : Any>
    @JvmOverloads
    constructor(
        table: TaskTable<ID>,
        private val connections: ConnectionProvider,
        private val payloadColumns: List<PayloadColumn>,
        dialect: SqlDialect = PostgresDialect,
        cycleTable: CycleTable<ID>? = null,
    ) : TaskStore<ID> {
        private val sql: TaskSql = dialect.statements(table, payloadColumns)
        private val idType: IdType<ID> = table.idType
        private val names: TaskColumns = table.columns

        override val cycles: CycleStore<ID>? =
            cycleTable?.let {
                JdbcCycleStore(dialect.cycleStatements(table, it, payloadColumns), idType, table.columns, it.columns)
            }

        override suspend fun <T> transaction(block: (StoreTransaction) -> T): T =
            connections.inTransaction { block(JdbcTransaction(it)) }

        override fun adopt(connection: Connection): StoreTransaction = JdbcTransaction(connection)

        private val StoreTransaction.connection: Connection
            get() = jdbc

        override fun insert(
            tx: StoreTransaction,
            task: NewTask<ID>,
        ): ID {
            val id = task.id ?: idType.generate()
            val statement = if (id == null) sql.insert else sql.insertWithId
            val inserted =
                tx.connection.query(statement, { bind ->
                    if (id != null) bind.id(id)
                    bind.string(task.type)
                    bind.string(task.status.name)
                    bind.instant(task.runAt)
                    bind.instant(task.createdAt)
                    bind.string(task.idempotencyKey)
                    bind.string(task.createdBy)
                    bind.int(task.payloadVersion)
                    bind.string(task.lane)
                    bind.int(task.priority)
                    payloadColumns.forEach { bind.payload(it, task.payload[it.name]) }
                }) { rs -> if (rs.next()) rs.id() else null }
            if (inserted != null) return inserted
            val key = checkNotNull(task.idempotencyKey) { "INSERT ... RETURNING produced no row" }
            return tx.connection.query(sql.findIdByKey, { bind ->
                bind.string(task.type)
                bind.string(key)
            }) { rs ->
                check(rs.next()) { "task of type '${task.type}' with idempotency key '$key' vanished" }
                rs.id()
            }
        }

        override fun promoteDue(
            tx: StoreTransaction,
            now: Instant,
        ): Int =
            tx.connection.update(sql.promoteDue) { bind ->
                bind.string(TaskStatus.PENDING.name)
                bind.string(TaskStatus.SCHEDULED.name)
                bind.string(TaskStatus.RETRY_PENDING.name)
                bind.instant(now)
            }

        override fun releaseStale(
            tx: StoreTransaction,
            staleBefore: Instant,
        ): Int =
            tx.connection.update(sql.releaseStale) { bind ->
                bind.string(TaskStatus.PENDING.name)
                bind.string(TaskStatus.RUNNING.name)
                bind.instant(staleBefore)
            }

        override fun claim(
            tx: StoreTransaction,
            workerId: String,
            types: Collection<String>,
            limit: Int,
            now: Instant,
        ): List<ClaimedTask<ID>> {
            if (types.isEmpty() || limit <= 0) return emptyList()
            val connection = tx.connection
            return connection.query(sql.claim, { bind ->
                bind.string(TaskStatus.PENDING.name)
                bind.instant(now)
                bind.stringArray(connection, types)
                bind.int(limit)
                bind.string(TaskStatus.RUNNING.name)
                bind.string(workerId)
                bind.instant(now)
            }) { rs -> rs.claimedTasks() }
        }

        override fun dueGroups(
            tx: StoreTransaction,
            types: Collection<String>,
            now: Instant,
        ): List<ClaimGroup> {
            if (types.isEmpty()) return emptyList()
            val connection = tx.connection
            return connection.query(sql.dueGroups, { bind ->
                bind.string(TaskStatus.PENDING.name)
                bind.instant(now)
                bind.stringArray(connection, types)
            }) { rs ->
                buildList {
                    while (rs.next()) {
                        add(ClaimGroup(rs.getInt(names.priority), rs.getString(names.type), rs.getString(names.lane)))
                    }
                }
            }
        }

        override fun claimFromGroup(
            tx: StoreTransaction,
            workerId: String,
            group: ClaimGroup,
            limit: Int,
            now: Instant,
        ): List<ClaimedTask<ID>> {
            if (limit <= 0) return emptyList()
            return tx.connection.query(sql.claimFromGroup, { bind ->
                bind.string(TaskStatus.PENDING.name)
                bind.instant(now)
                bind.string(group.type)
                bind.string(group.lane)
                bind.int(group.priority)
                bind.int(limit)
                bind.string(TaskStatus.RUNNING.name)
                bind.string(workerId)
                bind.instant(now)
            }) { rs -> rs.claimedTasks() }
        }

        private fun ResultSet.claimedTasks(): List<ClaimedTask<ID>> =
            buildList {
                while (next()) {
                    add(
                        ClaimedTask(
                            id = id(),
                            type = getString(names.type),
                            payload = payload(payloadColumns),
                            attempt = getInt(names.attempts),
                            version = getLong(names.version),
                            createdBy = getString(names.createdBy),
                            payloadVersion = getInt(names.payloadVersion),
                            lane = getString(names.lane),
                        ),
                    )
                }
            }

        override fun heartbeat(
            tx: StoreTransaction,
            id: ID,
            version: Long,
            now: Instant,
        ): Boolean {
            val updated =
                tx.connection.update(sql.heartbeat) { bind ->
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
            now: Instant,
        ): Boolean {
            val (status, runAt, error) =
                when (outcome) {
                    Outcome.Succeeded -> Triple(TaskStatus.SUCCEEDED, null, null)
                    is Outcome.Retry -> Triple(TaskStatus.RETRY_PENDING, outcome.runAt, outcome.error)
                    is Outcome.Failed -> Triple(TaskStatus.FAILED, null, outcome.error)
                }
            val updated =
                tx.connection.update(sql.finish) { bind ->
                    bind.string(status.name)
                    bind.instant(runAt)
                    bind.string(error)
                    bind.instant(now.takeIf { status != TaskStatus.RETRY_PENDING })
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
            val updated =
                tx.connection.update(sql.release) { bind ->
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
        ): TaskRecord<ID>? =
            tx.connection.query(sql.find, { bind -> bind.id(id) }) { rs ->
                if (!rs.next()) {
                    null
                } else {
                    TaskRecord(
                        id = rs.id(),
                        type = rs.getString(names.type),
                        status = TaskStatus.valueOf(rs.getString(names.status)),
                        payload = rs.payload(payloadColumns),
                        runAt = rs.instant(names.runAt)!!,
                        attempts = rs.getInt(names.attempts),
                        lastError = rs.getString(names.lastError),
                        lockedBy = rs.getString(names.lockedBy),
                        lockedAt = rs.instant(names.lockedAt),
                        version = rs.getLong(names.version),
                        createdAt = rs.instant(names.createdAt)!!,
                        idempotencyKey = rs.getString(names.idempotencyKey),
                        createdBy = rs.getString(names.createdBy),
                        payloadVersion = rs.getInt(names.payloadVersion),
                        finishedAt = rs.instant(names.finishedAt),
                        lane = rs.getString(names.lane),
                        priority = rs.getInt(names.priority),
                    )
                }
            }

        override fun deleteFinished(
            tx: StoreTransaction,
            type: String,
            finishedBefore: Instant,
            limit: Int,
        ): Int = tx.connection.update(sql.deleteFinished) { bind -> bind.expired(type, finishedBefore, limit) }

        override fun archiveFinished(
            tx: StoreTransaction,
            type: String,
            finishedBefore: Instant,
            limit: Int,
            archiveTable: String,
        ): Int =
            tx.connection.update(
                sql.archiveFinished(archiveTable),
            ) { bind -> bind.expired(type, finishedBefore, limit) }

        private fun Binder.expired(
            type: String,
            finishedBefore: Instant,
            limit: Int,
        ) {
            string(type)
            string(TaskStatus.SUCCEEDED.name)
            string(TaskStatus.CANCELLED.name)
            instant(finishedBefore)
            int(limit)
        }

        private fun ResultSet.id(): ID = getObject(names.id, idType.javaType)
    }
