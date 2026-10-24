package io.github.d4niella.pendenz.store

import io.github.d4niella.pendenz.ConnectionProvider
import io.github.d4niella.pendenz.PayloadColumn
import io.github.d4niella.pendenz.TaskQuery
import io.github.d4niella.pendenz.TaskStatus
import io.github.d4niella.pendenz.schema.CycleTable
import io.github.d4niella.pendenz.schema.IdType
import io.github.d4niella.pendenz.schema.ResourceLimitTable
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
 * @param limitTable the resource limit table mapping, or null when limits are not enabled (ADR 0017).
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
        limitTable: ResourceLimitTable? = null,
    ) : TaskStore<ID> {
        private val sql: TaskSql = dialect.statements(table, payloadColumns, limitTable)
        private val idType: IdType<ID> = table.idType
        private val names: TaskColumns = table.columns

        override val resourceLimits: ResourceLimitStore? =
            limitTable?.let {
                JdbcResourceLimitStore(dialect.resourceLimitStatements(table, it), it.columns, lockKey = table.name)
            }

        override val cycles: CycleStore<ID>? =
            cycleTable?.let {
                JdbcCycleStore(dialect.cycleStatements(table, it), idType, table.columns, it.columns)
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
                    bind.string(task.resource)
                    bind.idOrNull(task.parentId)
                    bind.string(task.correlationId)
                    bind.string(task.preview)
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
            return claimUpTo(limit) { batch ->
                connection.query(sql.claim, { bind ->
                    bind.string(TaskStatus.PENDING.name)
                    bind.instant(now)
                    bind.stringArray(connection, types)
                    bind.int(batch)
                    bind.string(TaskStatus.RUNNING.name)
                    bind.string(workerId)
                    bind.instant(now)
                }) { rs -> rs.claimedTasks(now) }
            }
        }

        /**
         * Without limits, one statement claims up to [limit] rows. With limits,
         * the resource condition counts what is RUNNING when the statement
         * starts, not what the statement itself claims, so rows are claimed
         * one statement at a time until one comes back empty.
         */
        private fun claimUpTo(
            limit: Int,
            statement: (batch: Int) -> List<ClaimedTask<ID>>,
        ): List<ClaimedTask<ID>> {
            if (resourceLimits == null) return statement(limit)
            val claimed = ArrayList<ClaimedTask<ID>>(limit)
            while (claimed.size < limit) {
                val next = statement(1)
                if (next.isEmpty()) break
                claimed += next
            }
            return claimed
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
            return claimUpTo(limit) { batch ->
                tx.connection.query(sql.claimFromGroup, { bind ->
                    bind.string(TaskStatus.PENDING.name)
                    bind.instant(now)
                    bind.string(group.type)
                    bind.string(group.lane)
                    bind.int(group.priority)
                    bind.int(batch)
                    bind.string(TaskStatus.RUNNING.name)
                    bind.string(workerId)
                    bind.instant(now)
                }) { rs -> rs.claimedTasks(now) }
            }
        }

        private fun ResultSet.claimedTasks(claimedAt: Instant): List<ClaimedTask<ID>> =
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
                            priority = getInt(names.priority),
                            resource = getString(names.resource),
                            parentId = idOrNull(names.parentId),
                            correlationId = getString(names.correlationId),
                            runAt = instant(names.runAt)!!,
                            claimedAt = claimedAt,
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
                    is Outcome.Succeeded -> Triple(TaskStatus.SUCCEEDED, null, null)
                    is Outcome.Rejected -> Triple(TaskStatus.REJECTED, null, outcome.reason)
                    is Outcome.Retry -> Triple(TaskStatus.RETRY_PENDING, outcome.runAt, outcome.error)
                    is Outcome.Failed -> Triple(TaskStatus.FAILED, null, outcome.error)
                }
            val result =
                when (outcome) {
                    is Outcome.Succeeded -> outcome.result
                    is Outcome.Rejected -> outcome.result
                    else -> null
                }
            val updated =
                tx.connection.update(sql.finish) { bind ->
                    bind.string(status.name)
                    bind.instant(runAt)
                    bind.string(error)
                    bind.instant(now.takeIf { status != TaskStatus.RETRY_PENDING })
                    bind.string(result)
                    bind.string(ProvenanceJson.encode(outcome.provenance))
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
            tx.connection.query(sql.find, { bind -> bind.id(id) }) { rs -> if (rs.next()) rs.record() else null }

        override fun search(
            tx: StoreTransaction,
            query: TaskQuery,
        ): List<TaskRecord<ID>> {
            val connection = tx.connection
            return connection.query(sql.search(query), { bind ->
                query.createdBy?.let { bind.string(it) }
                if (query.types.isNotEmpty()) bind.stringArray(connection, query.types.toList())
                if (query.statuses.isNotEmpty()) bind.stringArray(connection, query.statuses.map { it.name })
                query.correlationId?.let { bind.string(it) }
                bind.int(query.limit)
            }) { rs -> generateSequence { if (rs.next()) rs.record() else null }.toList() }
        }

        private fun ResultSet.record(): TaskRecord<ID> =
            TaskRecord(
                id = id(),
                type = getString(names.type),
                status = TaskStatus.valueOf(getString(names.status)),
                payload = payload(payloadColumns),
                runAt = instant(names.runAt)!!,
                attempts = getInt(names.attempts),
                lastError = getString(names.lastError),
                lockedBy = getString(names.lockedBy),
                lockedAt = instant(names.lockedAt),
                version = getLong(names.version),
                createdAt = instant(names.createdAt)!!,
                idempotencyKey = getString(names.idempotencyKey),
                createdBy = getString(names.createdBy),
                payloadVersion = getInt(names.payloadVersion),
                finishedAt = instant(names.finishedAt),
                lane = getString(names.lane),
                priority = getInt(names.priority),
                resource = getString(names.resource),
                approvedBy = getString(names.approvedBy),
                approvedAt = instant(names.approvedAt),
                parentId = idOrNull(names.parentId),
                correlationId = getString(names.correlationId),
                result = getString(names.result),
                provenance = ProvenanceJson.decode(getString(names.provenance)),
                preview = getString(names.preview),
            )

        override fun move(
            tx: StoreTransaction,
            id: ID,
            from: Collection<TaskStatus>,
            to: TaskStatus,
            reason: String?,
            now: Instant,
        ): Boolean {
            val connection = tx.connection
            val updated =
                connection.update(sql.move) { bind ->
                    bind.string(to.name)
                    bind.instant(now.takeIf { to.isFinal })
                    bind.string(reason)
                    bind.id(id)
                    bind.stringArray(connection, from.map { it.name })
                }
            return updated == 1
        }

        override fun approve(
            tx: StoreTransaction,
            id: ID,
            actor: String,
            now: Instant,
        ): Boolean =
            tx.connection.update(sql.approve) { bind ->
                bind.approval(actor, now)
                bind.id(id)
                bind.string(TaskStatus.AWAITING_APPROVAL.name)
            } == 1

        override fun approveLane(
            tx: StoreTransaction,
            lane: String,
            type: String,
            actor: String,
            now: Instant,
        ): Int =
            tx.connection.update(sql.approveLane) { bind ->
                bind.approval(actor, now)
                bind.string(lane)
                bind.string(type)
                bind.string(TaskStatus.AWAITING_APPROVAL.name)
            }

        private fun Binder.approval(
            actor: String,
            now: Instant,
        ) {
            instant(now)
            string(TaskStatus.SCHEDULED.name)
            string(TaskStatus.PENDING.name)
            string(actor)
            instant(now)
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

        private fun ResultSet.idOrNull(column: String): ID? = getObject(column, idType.javaType)
    }
