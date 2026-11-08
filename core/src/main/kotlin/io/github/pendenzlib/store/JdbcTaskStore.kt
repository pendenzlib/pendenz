package io.github.pendenzlib.store

import io.github.pendenzlib.Backlog
import io.github.pendenzlib.ConnectionProvider
import io.github.pendenzlib.PayloadColumn
import io.github.pendenzlib.TaskQuery
import io.github.pendenzlib.TaskRecord
import io.github.pendenzlib.TaskStatus
import io.github.pendenzlib.schema.CycleTable
import io.github.pendenzlib.schema.IdType
import io.github.pendenzlib.schema.ResourceBlockTable
import io.github.pendenzlib.schema.ResourceLimitTable
import io.github.pendenzlib.schema.ResourceRateTable
import io.github.pendenzlib.schema.ResourceStartTable
import io.github.pendenzlib.schema.TaskColumns
import io.github.pendenzlib.schema.TaskTable
import io.github.pendenzlib.schema.requireApartFromQueueColumns
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
 * @param blockTable the resource block table mapping, or null when blocks are not enabled (ADR 0035).
 * @param rateTable the resource rate table mapping, or null when rates are not enabled (ADR 0040); needs
 *   [limitTable], whose claim lock and filter the rates use.
 * @param startTable the start table mapping of the rates; the default table when [rateTable] is given alone.
 * @throws IllegalArgumentException when a payload column has the name of a column of the queue.
 */
@Suppress("TooManyFunctions", "LongParameterList") // one function per store operation, one parameter per table
public class JdbcTaskStore<ID : Any>
    @JvmOverloads
    constructor(
        table: TaskTable<ID>,
        private val connections: ConnectionProvider,
        private val payloadColumns: List<PayloadColumn>,
        dialect: SqlDialect = PostgresDialect,
        cycleTable: CycleTable<ID>? = null,
        limitTable: ResourceLimitTable? = null,
        blockTable: ResourceBlockTable? = null,
        rateTable: ResourceRateTable? = null,
        startTable: ResourceStartTable? = null,
    ) : TaskStore<ID> {
        private val sql: TaskSql = dialect.statements(table, payloadColumns, limitTable, blockTable)
        private val idType: IdType<ID> = table.idType
        private val names: TaskColumns = table.columns

        init {
            requireApartFromQueueColumns(table, payloadColumns)
            require(rateTable == null || limitTable != null) {
                "rate limits use the claim lock of the resource limits; give a limitTable with the rateTable"
            }
            require(startTable == null || rateTable != null) { "a startTable belongs to a rateTable" }
        }

        override val resourceLimits: ResourceLimitStore? =
            limitTable?.let {
                JdbcResourceLimitStore(dialect.resourceLimitStatements(table, it), it.columns)
            }

        override val resourceRates: ResourceRateStore? =
            rateTable?.let {
                val starts = startTable ?: ResourceStartTable()
                JdbcResourceRateStore(dialect.resourceRateStatements(it, starts), it.columns, starts.columns)
            }

        override val resourceBlocks: ResourceBlockStore? =
            blockTable?.let { JdbcResourceBlockStore(dialect.resourceBlockStatements(it), it.columns) }

        /** Whether the claim statements carry the resource filter: with limits or blocks (ADR 0029, ADR 0035). */
        private val filtersResources = limitTable != null || blockTable != null

        override val cycles: CycleStore<ID>? =
            cycleTable?.let {
                JdbcCycleStore(dialect.cycleStatements(table, it), idType, table.columns, it.columns)
            }

        override suspend fun <T> transaction(block: (StoreTransaction) -> T): T =
            connections.inTransaction { block(JdbcTransaction(it)) }

        override suspend fun <T> readTransaction(block: (StoreTransaction) -> T): T =
            connections.inReadTransaction { block(JdbcTransaction(it)) }

        override fun afterCommit(action: () -> Unit): Unit = connections.afterCommit(action)

        override fun adopt(connection: Connection): StoreTransaction = JdbcTransaction(connection)

        override fun insert(
            tx: StoreTransaction,
            task: NewTask<ID>,
        ): InsertedTask<ID> {
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
            if (inserted != null) return InsertedTask(inserted, isNew = true)
            val key = checkNotNull(task.idempotencyKey) { "INSERT ... RETURNING produced no row" }
            if (task.replaceWaiting) replaceWaiting(tx, task, key)?.let { return InsertedTask(it, false, true) }
            val existing =
                tx.jdbc.query(sql.findIdByKey, { bind ->
                    bind.string(task.type)
                    bind.string(key)
                }) { rs ->
                    check(rs.next()) { "task of type '${task.type}' with idempotency key '$key' vanished" }
                    rs.id()
                }
            return InsertedTask(existing, isNew = false)
        }

        /** Replaces the waiting row that holds [key] with [task]; null when there is none (ADR 0044). */
        private fun replaceWaiting(
            tx: StoreTransaction,
            task: NewTask<ID>,
            key: String,
        ): ID? {
            val statement =
                checkNotNull(sql.replaceWaiting) {
                    "the SQL dialect has no replaceWaiting statement; task type '${task.type}' replaces waiting tasks"
                }
            return tx.jdbc.query(statement, { bind ->
                bind.string(task.status.name)
                bind.instant(task.runAt)
                bind.int(task.payloadVersion)
                bind.string(task.lane)
                bind.int(task.priority)
                bind.string(task.resource)
                bind.string(task.preview)
                payloadColumns.forEach { bind.payload(it, task.payload[it.name]) }
                bind.string(task.type)
                bind.string(key)
            }) { rs -> if (rs.next()) rs.id() else null }
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

        override fun runningPerResource(
            tx: StoreTransaction,
            now: Instant,
        ): Map<String, Int> =
            tx.jdbc.query(sql.runningPerResource, { bind ->
                bind.string(TaskStatus.RUNNING.name)
                bind.string(TaskStatus.CANCELLED.name)
                bind.instant(now)
            }) { rs ->
                buildMap {
                    while (rs.next()) put(rs.getString(names.resource), rs.getInt(TaskSql.RUNNING_COUNT))
                }
            }

        /** The resource filter's four arrays, bound only where the statements carry the condition. */
        private fun Binder.resourceFilter(
            connection: Connection,
            filter: ResourceFilter,
        ) {
            if (!filtersResources) return
            stringArray(connection, filter.full)
            stringArray(connection, filter.pausedPatterns)
            stringArray(connection, filter.otherPatterns)
            stringArray(connection, filter.blockedPatterns)
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
                createdAt = instant(names.createdAt)!!,
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

        override fun holdCancelled(
            tx: StoreTransaction,
            id: ID,
            workerId: String,
            lockedUntil: Instant,
        ): Boolean =
            tx.jdbc.update(sql.holdCancelled) { bind ->
                bind.instant(lockedUntil)
                bind.id(id)
                bind.string(workerId)
                bind.string(TaskStatus.CANCELLED.name)
            } == 1

        override fun unlockCancelled(
            tx: StoreTransaction,
            id: ID,
            workerId: String,
        ): Boolean =
            tx.jdbc.update(sql.unlockCancelled) { bind ->
                bind.id(id)
                bind.string(workerId)
                bind.string(TaskStatus.CANCELLED.name)
            } == 1

        override fun unlockExpiredCancelled(
            tx: StoreTransaction,
            now: Instant,
        ): Int =
            tx.jdbc.update(sql.unlockExpiredCancelled) { bind ->
                bind.string(TaskStatus.CANCELLED.name)
                bind.instant(now)
            }

        override fun finish(
            tx: StoreTransaction,
            id: ID,
            expectedVersion: Long,
            outcome: Outcome,
            now: Instant,
        ): Boolean {
            if (outcome is Outcome.Deferred) return defer(tx, id, expectedVersion, outcome)
            val (status, runAt, error) =
                when (outcome) {
                    is Outcome.Succeeded -> Triple(TaskStatus.SUCCEEDED, null, null)
                    is Outcome.Rejected -> Triple(TaskStatus.REJECTED, null, outcome.reason)
                    is Outcome.Retry -> Triple(TaskStatus.RETRY_PENDING, outcome.runAt, outcome.error)
                    is Outcome.Failed -> Triple(TaskStatus.FAILED, null, outcome.error)
                    is Outcome.HandedOver -> Triple(TaskStatus.QUARANTINED, null, outcome.reason)
                    is Outcome.Deferred -> error("handled above")
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
                    // A retry and a hand-over leave the task open, so it has no finish time
                    bind.instant(now.takeIf { status != TaskStatus.RETRY_PENDING && status != TaskStatus.QUARANTINED })
                    bind.string(result)
                    bind.string(ProvenanceJson.encode(outcome.provenance))
                    bind.id(id)
                    bind.long(expectedVersion)
                    bind.string(TaskStatus.RUNNING.name)
                }
            return updated == 1
        }

        private fun defer(
            tx: StoreTransaction,
            id: ID,
            expectedVersion: Long,
            outcome: Outcome.Deferred,
        ): Boolean =
            tx.jdbc.update(sql.defer) { bind ->
                bind.string(TaskStatus.RETRY_PENDING.name)
                bind.instant(outcome.runAt)
                bind.string(outcome.reason)
                bind.string(ProvenanceJson.encode(outcome.provenance))
                bind.id(id)
                bind.long(expectedVersion)
                bind.string(TaskStatus.RUNNING.name)
            } == 1

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

        override fun lockSchedule(
            tx: StoreTransaction,
            name: String,
        ) {
            tx.jdbc.query(sql.lockSchedule, { bind -> bind.string(name) }) { rs -> rs.next() }
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
        ): TaskStatus? {
            val connection = tx.jdbc
            return connection.query(sql.move(to), { bind ->
                bind.instant(now)
                bind.string(reason)
                bind.id(id)
                bind.stringArray(connection, from.map { it.name })
            }) { rs -> if (rs.next()) TaskStatus.valueOf(rs.getString(TaskSql.MOVED_FROM)) else null }
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
        ): Int = deleteFinished(tx, type, FINAL, finishedBefore, limit)

        override fun deleteFinished(
            tx: StoreTransaction,
            type: String,
            statuses: Set<TaskStatus>,
            finishedBefore: Instant,
            limit: Int,
        ): Int = tx.jdbc.update(sql.deleteFinished) { bind -> bind.expired(type, statuses, finishedBefore, limit) }

        override fun archiveFinished(
            tx: StoreTransaction,
            type: String,
            finishedBefore: Instant,
            limit: Int,
            archiveTable: String,
        ): Int = archiveFinished(tx, type, FINAL, finishedBefore, limit, archiveTable)

        override fun archiveFinished(
            tx: StoreTransaction,
            type: String,
            statuses: Set<TaskStatus>,
            finishedBefore: Instant,
            limit: Int,
            archiveTable: String,
        ): Int =
            tx.jdbc.update(
                sql.archiveFinished(archiveTable),
            ) { bind -> bind.expired(type, statuses, finishedBefore, limit) }

        /** The statement takes two final statuses; one of them alone is bound twice (ADR 0045). */
        private fun Binder.expired(
            type: String,
            statuses: Set<TaskStatus>,
            finishedBefore: Instant,
            limit: Int,
        ) {
            require(statuses.isNotEmpty() && statuses.all { it in FINAL }) { "retention removes final rows only" }
            string(type)
            string(statuses.first().name)
            string(statuses.last().name)
            instant(finishedBefore)
            instant(finishedBefore)
            int(limit)
        }

        private fun ResultSet.id(): ID = getObject(names.id, idType.javaType)

        private fun ResultSet.idOrNull(column: String): ID? = getObject(column, idType.javaType)
    }

/** The statuses retention removes (ADR 0011). */
private val FINAL = setOf(TaskStatus.SUCCEEDED, TaskStatus.CANCELLED)
