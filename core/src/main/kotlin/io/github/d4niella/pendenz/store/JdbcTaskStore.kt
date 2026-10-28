package io.github.d4niella.pendenz.store

import io.github.d4niella.pendenz.Backlog
import io.github.d4niella.pendenz.ConnectionProvider
import io.github.d4niella.pendenz.PayloadColumn
import io.github.d4niella.pendenz.TaskQuery
import io.github.d4niella.pendenz.TaskRecord
import io.github.d4niella.pendenz.TaskStatus
import io.github.d4niella.pendenz.schema.CycleTable
import io.github.d4niella.pendenz.schema.IdType
import io.github.d4niella.pendenz.schema.ResourceLimitTable
import io.github.d4niella.pendenz.schema.TaskColumns
import io.github.d4niella.pendenz.schema.TaskTable
import io.github.d4niella.pendenz.schema.requireApartFromQueueColumns
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
 * @param payloadColumns the payload columns of every task type that uses the table: the union of their
 *   `payload.columns`, a column shared by two types listed once.
 * @param dialect the database's SQL; PostgreSQL by default.
 * @param cycleTable the cycle table mapping, or null when the application does not use cycles (ADR 0016).
 * @param limitTable the resource limit table mapping, or null when limits are not enabled (ADR 0017).
 * @throws IllegalArgumentException when a payload column has the name of a column of the queue.
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

        init {
            requireApartFromQueueColumns(table, payloadColumns)
        }

        override val resourceLimits: ResourceLimitStore? =
            limitTable?.let {
                JdbcResourceLimitStore(dialect.resourceLimitStatements(table, it), it.columns)
            }

        override val cycles: CycleStore<ID>? =
            cycleTable?.let {
                JdbcCycleStore(dialect.cycleStatements(table, it), idType, table.columns, it.columns)
            }

        override suspend fun <T> transaction(block: (StoreTransaction) -> T): T =
            connections.inTransaction { block(JdbcTransaction(it)) }

        override suspend fun <T> readTransaction(block: (StoreTransaction) -> T): T =
            connections.inReadTransaction { block(JdbcTransaction(it)) }

        override fun adopt(connection: Connection): StoreTransaction = JdbcTransaction(connection)

        override fun insert(
            tx: StoreTransaction,
            task: NewTask<ID>,
        ): ID {
            val unknown = task.payload.keys - payloadColumns.map { it.name }.toSet()
            require(unknown.isEmpty()) {
                "payload columns $unknown are not among this store's columns; " +
                    "build the store with every task type's columns"
            }
            val id = task.id ?: idType.generate()
            val statement = if (id == null) sql.insert else sql.insertWithId
            val inserted =
                tx.jdbc.query(statement, { bind ->
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
            return tx.jdbc.query(sql.findIdByKey, { bind ->
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
            tx.jdbc.update(sql.promoteDue) { bind ->
                bind.string(TaskStatus.PENDING.name)
                bind.string(TaskStatus.SCHEDULED.name)
                bind.string(TaskStatus.RETRY_PENDING.name)
                bind.instant(now)
            }

        override fun expiredLocks(
            tx: StoreTransaction,
            types: Collection<String>,
            now: Instant,
            limit: Int,
        ): List<ExpiredLock<ID>> {
            if (types.isEmpty() || limit <= 0) return emptyList()
            val connection = tx.jdbc
            return connection.query(sql.expiredLocks, { bind ->
                bind.string(TaskStatus.RUNNING.name)
                bind.instant(now)
                bind.stringArray(connection, types)
                bind.int(limit)
            }) { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            ExpiredLock(
                                rs.claimedTask(now),
                                rs.getString(names.lockedBy),
                                rs.instant(names.lockedUntil)!!,
                            ),
                        )
                    }
                }
            }
        }

        override fun claim(
            tx: StoreTransaction,
            types: Collection<String>,
            limit: Int,
            terms: ClaimTerms,
        ): List<ClaimedTask<ID>> {
            if (types.isEmpty() || limit <= 0) return emptyList()
            val connection = tx.jdbc
            return connection.query(sql.claim, { bind ->
                bind.string(TaskStatus.PENDING.name)
                bind.instant(terms.now)
                bind.stringArray(connection, types)
                bind.resourceFilter(connection, terms.resources)
                bind.int(limit)
                bind.claimFor(terms)
            }) { rs -> rs.claimedTasks(terms.now) }
        }

        override fun dueGroups(
            tx: StoreTransaction,
            types: Collection<String>,
            terms: ClaimTerms,
        ): List<ClaimGroup> {
            if (types.isEmpty()) return emptyList()
            val connection = tx.jdbc
            return connection.query(sql.dueGroups, { bind ->
                bind.string(TaskStatus.PENDING.name)
                bind.instant(terms.now)
                bind.stringArray(connection, types)
                bind.resourceFilter(connection, terms.resources)
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
            group: ClaimGroup,
            limit: Int,
            terms: ClaimTerms,
        ): List<ClaimedTask<ID>> {
            if (limit <= 0) return emptyList()
            val connection = tx.jdbc
            val lane = group.lane
            return connection.query(if (lane == null) sql.claimWithoutLane else sql.claimFromLane, { bind ->
                bind.string(TaskStatus.PENDING.name)
                bind.instant(terms.now)
                bind.string(group.type)
                if (lane != null) bind.string(lane)
                bind.int(group.priority)
                bind.resourceFilter(connection, terms.resources)
                bind.int(limit)
                bind.claimFor(terms)
            }) { rs -> rs.claimedTasks(terms.now) }
        }

        override fun runningPerResource(tx: StoreTransaction): Map<String, Int> =
            tx.jdbc.query(sql.runningPerResource, { bind -> bind.string(TaskStatus.RUNNING.name) }) { rs ->
                buildMap {
                    while (rs.next()) put(rs.getString(names.resource), rs.getInt(TaskSql.RUNNING_COUNT))
                }
            }

        /** The resource filter's three arrays, bound only where the statements carry the condition. */
        private fun Binder.resourceFilter(
            connection: Connection,
            filter: ResourceFilter,
        ) {
            if (resourceLimits == null) return
            stringArray(connection, filter.full)
            stringArray(connection, filter.pausedPatterns)
            stringArray(connection, filter.otherPatterns)
        }

        private fun Binder.claimFor(terms: ClaimTerms) {
            string(TaskStatus.RUNNING.name)
            string(terms.workerId)
            instant(terms.lockedUntil)
        }

        private fun ResultSet.claimedTasks(claimedAt: Instant): List<ClaimedTask<ID>> =
            buildList {
                while (next()) add(claimedTask(claimedAt))
            }

        private fun ResultSet.claimedTask(claimedAt: Instant): ClaimedTask<ID> =
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
            )

        override fun heartbeat(
            tx: StoreTransaction,
            id: ID,
            expectedVersion: Long,
            lockedUntil: Instant,
        ): Boolean {
            val updated =
                tx.jdbc.update(sql.heartbeat) { bind ->
                    bind.instant(lockedUntil)
                    bind.id(id)
                    bind.long(expectedVersion)
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
                tx.jdbc.update(sql.finish) { bind ->
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
                tx.jdbc.update(sql.release) { bind ->
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
            tx.jdbc.query(sql.find, { bind -> bind.id(id) }) { rs -> if (rs.next()) rs.record() else null }

        override fun search(
            tx: StoreTransaction,
            query: TaskQuery,
        ): List<TaskRecord<ID>> {
            val connection = tx.jdbc
            return connection.query(sql.search(query), { bind ->
                query.createdBy?.let { bind.string(it) }
                if (query.types.isNotEmpty()) bind.stringArray(connection, query.types.toList())
                if (query.statuses.isNotEmpty()) bind.stringArray(connection, query.statuses.map { it.name })
                query.correlationId?.let { bind.string(it) }
                query.lane?.let { bind.string(it) }
                query.parentId?.let { bind.id(it) }
                query.olderThan?.let {
                    bind.instant(it.createdAt)
                    bind.id(it.id)
                }
                bind.int(query.limit)
            }) { rs -> generateSequence { if (rs.next()) rs.record() else null }.toList() }
        }

        override fun backlog(
            tx: StoreTransaction,
            now: Instant,
        ): List<Backlog> {
            val connection = tx.jdbc
            val rows =
                connection.query(sql.backlog, { bind ->
                    bind.instant(now)
                    bind.stringArray(connection, Backlog.STATUSES.map { it.name })
                }) { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                Backlog.Row(
                                    type = rs.getString(names.type),
                                    status = TaskStatus.valueOf(rs.getString(names.status)),
                                    count = rs.getLong(TaskSql.BACKLOG_COUNT),
                                    oldestDue = rs.instant(TaskSql.BACKLOG_OLDEST_DUE),
                                ),
                            )
                        }
                    }
                }
            return Backlog.of(rows)
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
                lockedUntil = instant(names.lockedUntil),
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
            val connection = tx.jdbc
            val updated =
                connection.update(sql.move(to)) { bind ->
                    bind.instant(now)
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
            tx.jdbc.update(sql.approve) { bind ->
                bind.approval(actor, now)
                bind.id(id)
            } == 1

        override fun approveLane(
            tx: StoreTransaction,
            lane: String,
            type: String,
            actor: String,
            now: Instant,
        ): Int =
            tx.jdbc.update(sql.approveLane) { bind ->
                bind.approval(actor, now)
                bind.string(lane)
                bind.string(type)
            }

        private fun Binder.approval(
            actor: String,
            now: Instant,
        ) {
            string(actor)
            instant(now)
            string(null)
        }

        override fun deleteFinished(
            tx: StoreTransaction,
            type: String,
            finishedBefore: Instant,
            limit: Int,
        ): Int = tx.jdbc.update(sql.deleteFinished) { bind -> bind.expired(type, finishedBefore, limit) }

        override fun archiveFinished(
            tx: StoreTransaction,
            type: String,
            finishedBefore: Instant,
            limit: Int,
            archiveTable: String,
        ): Int =
            tx.jdbc.update(
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
            instant(finishedBefore)
            int(limit)
        }

        private fun ResultSet.id(): ID = getObject(names.id, idType.javaType)

        private fun ResultSet.idOrNull(column: String): ID? = getObject(column, idType.javaType)
    }
