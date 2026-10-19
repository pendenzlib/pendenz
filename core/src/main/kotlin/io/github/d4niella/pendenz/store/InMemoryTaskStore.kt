package io.github.d4niella.pendenz.store

import io.github.d4niella.pendenz.CycleStatus
import io.github.d4niella.pendenz.TaskStatus
import io.github.d4niella.pendenz.schema.IdType
import java.sql.Connection
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * [TaskStore] without a database, for tests of the application (ADR 0006).
 *
 * Every operation is atomic; there are no transactions and nothing rolls
 * back. A connection handed to [adopt] is ignored, so code that enqueues
 * inside its own JDBC transaction runs unchanged.
 *
 * @param idType decides how ids are generated when the caller gives none.
 */
@Suppress("TooManyFunctions") // one function per TaskStore operation, the interface dictates the count
public class InMemoryTaskStore<ID : Any>(
    private val idType: IdType<ID>,
) : TaskStore<ID>,
    CycleStore<ID> {
    private val lock = Any()
    private val rows = LinkedHashMap<ID, Row<ID>>()
    private val cycleRows = LinkedHashMap<ID, CycleRow<ID>>()
    private val sequence = AtomicLong()

    /** Cycles are always available in memory; the query form is not, there is no database to run it. */
    override val cycles: CycleStore<ID>
        get() = this

    private object NoTransaction : StoreTransaction

    override suspend fun <T> transaction(block: (StoreTransaction) -> T): T = block(NoTransaction)

    override fun adopt(connection: Connection): StoreTransaction = NoTransaction

    private val archive = ArrayList<TaskRecord<ID>>()

    /** Every task in the store, in insertion order, as it is right now. */
    public fun tasks(): List<TaskRecord<ID>> = synchronized(lock) { rows.values.map { it.toRecord() } }

    /** Every task that retention archived, whatever the archive table was called. */
    public fun archived(): List<TaskRecord<ID>> = synchronized(lock) { archive.toList() }

    override fun insert(
        tx: StoreTransaction,
        task: NewTask<ID>,
    ): ID =
        synchronized(lock) {
            val key = task.idempotencyKey
            if (key != null) {
                rows.values.firstOrNull { it.type == task.type && it.idempotencyKey == key }?.let { return it.id }
            }
            val id = task.id ?: idType.generate() ?: idType.fromSequence(sequence.incrementAndGet())
            require(id !in rows) { "a task with id $id already exists" }
            rows[id] =
                Row(
                    id,
                    task.type,
                    task.status,
                    task.payload,
                    task.runAt,
                    task.createdAt,
                    key,
                    task.createdBy,
                    task.payloadVersion,
                    task.lane,
                    task.priority,
                )
            id
        }

    override fun promoteDue(
        tx: StoreTransaction,
        now: Instant,
    ): Int =
        synchronized(lock) {
            rows.values
                .filter { it.status in DUE_WHEN_PROMOTED && !it.runAt.isAfter(now) }
                .onEach {
                    it.status = TaskStatus.PENDING
                    it.version++
                }.size
        }

    override fun releaseStale(
        tx: StoreTransaction,
        staleBefore: Instant,
    ): Int =
        synchronized(lock) {
            rows.values
                .filter { it.status == TaskStatus.RUNNING && it.lockedAt?.isBefore(staleBefore) == true }
                .onEach {
                    it.status = TaskStatus.PENDING
                    it.unlock()
                }.size
        }

    override fun claim(
        tx: StoreTransaction,
        workerId: String,
        types: Collection<String>,
        limit: Int,
        now: Instant,
    ): List<ClaimedTask<ID>> =
        synchronized(lock) {
            due(now) { it.type in types }
                .sortedWith(compareByDescending<Row<ID>> { it.priority }.thenBy { it.runAt }.thenBy { it.sequence })
                .take(limit.coerceAtLeast(0))
                .map { it.claimFor(workerId, now) }
        }

    override fun dueGroups(
        tx: StoreTransaction,
        types: Collection<String>,
        now: Instant,
    ): List<ClaimGroup> =
        synchronized(lock) {
            due(now) { it.type in types }
                .map { ClaimGroup(it.priority, it.type, it.lane) }
                .distinct()
                // Same order as PostgreSQL: nulls last, so the singles come after the lanes.
                .sortedWith(
                    compareByDescending<ClaimGroup> { it.priority }.thenBy { it.type }.thenBy(nullsLast()) { it.lane },
                )
        }

    override fun claimFromGroup(
        tx: StoreTransaction,
        workerId: String,
        group: ClaimGroup,
        limit: Int,
        now: Instant,
    ): List<ClaimedTask<ID>> =
        synchronized(lock) {
            due(now) { it.type == group.type && it.lane == group.lane && it.priority == group.priority }
                .sortedWith(compareBy<Row<ID>> { it.runAt }.thenBy { it.sequence })
                .take(limit.coerceAtLeast(0))
                .map { it.claimFor(workerId, now) }
        }

    private fun due(
        now: Instant,
        matches: (Row<ID>) -> Boolean,
    ): List<Row<ID>> = rows.values.filter { it.status == TaskStatus.PENDING && !it.runAt.isAfter(now) && matches(it) }

    private fun Row<ID>.claimFor(
        workerId: String,
        now: Instant,
    ): ClaimedTask<ID> {
        status = TaskStatus.RUNNING
        lockedBy = workerId
        lockedAt = now
        finishedAt = null
        attempts++
        version++
        return ClaimedTask(id, type, payload, attempts, version, createdBy, payloadVersion, lane)
    }

    override fun heartbeat(
        tx: StoreTransaction,
        id: ID,
        version: Long,
        now: Instant,
    ): Boolean =
        synchronized(lock) {
            val row = running(id, version) ?: return false
            row.lockedAt = now
            true
        }

    override fun finish(
        tx: StoreTransaction,
        id: ID,
        expectedVersion: Long,
        outcome: Outcome,
        now: Instant,
    ): Boolean =
        synchronized(lock) {
            val row = running(id, expectedVersion) ?: return false
            when (outcome) {
                Outcome.Succeeded -> {
                    row.status = TaskStatus.SUCCEEDED
                    row.lastError = null
                    row.finishedAt = now
                }

                is Outcome.Retry -> {
                    row.status = TaskStatus.RETRY_PENDING
                    row.runAt = outcome.runAt
                    row.lastError = outcome.error
                }

                is Outcome.Failed -> {
                    row.status = TaskStatus.FAILED
                    row.lastError = outcome.error
                    row.finishedAt = now
                }
            }
            row.unlock()
            true
        }

    override fun release(
        tx: StoreTransaction,
        id: ID,
        expectedVersion: Long,
    ): Boolean =
        synchronized(lock) {
            val row = running(id, expectedVersion) ?: return false
            row.status = TaskStatus.PENDING
            row.unlock()
            true
        }

    override fun find(
        tx: StoreTransaction,
        id: ID,
    ): TaskRecord<ID>? = synchronized(lock) { rows[id]?.toRecord() }

    override fun deleteFinished(
        tx: StoreTransaction,
        type: String,
        finishedBefore: Instant,
        limit: Int,
    ): Int = synchronized(lock) { expired(type, finishedBefore, limit).onEach { rows.remove(it.id) }.size }

    override fun archiveFinished(
        tx: StoreTransaction,
        type: String,
        finishedBefore: Instant,
        limit: Int,
        archiveTable: String,
    ): Int =
        synchronized(lock) {
            expired(type, finishedBefore, limit)
                .onEach {
                    rows.remove(it.id)
                    archive += it.toRecord()
                }.size
        }

    private fun expired(
        type: String,
        finishedBefore: Instant,
        limit: Int,
    ): List<Row<ID>> =
        rows.values
            .filter { it.type == type && it.status.isFinal && (it.finishedAt ?: it.createdAt).isBefore(finishedBefore) }
            .take(limit.coerceAtLeast(0))

    override fun insertCycle(
        tx: StoreTransaction,
        cycle: NewCycle<ID>,
    ): ID =
        synchronized(lock) {
            val id = cycle.id ?: idType.generate() ?: idType.fromSequence(sequence.incrementAndGet())
            require(id !in cycleRows) { "a cycle with id $id already exists" }
            cycleRows[id] = CycleRow(id, cycle.type, cycle.description, cycle.createdAt, cycle.createdBy)
            id
        }

    override fun insertTasksFromQuery(
        tx: StoreTransaction,
        query: String,
        lane: String,
        fixed: NewTask<ID>,
    ): Int = throw UnsupportedOperationException("the in-memory store cannot run a query; use CycleTasks.of(payloads)")

    override fun findCycle(
        tx: StoreTransaction,
        id: ID,
    ): CycleRecord<ID>? = synchronized(lock) { cycleRows[id]?.toRecord() }

    override fun updateCycleStatus(
        tx: StoreTransaction,
        id: ID,
        from: CycleStatus,
        to: CycleStatus,
        now: Instant,
    ): Boolean =
        synchronized(lock) {
            val row = cycleRows[id]?.takeIf { it.status == from } ?: return false
            row.status = to
            if (to.isFinal) row.finishedAt = now
            row.version++
            true
        }

    override fun quarantineLane(
        tx: StoreTransaction,
        lane: String,
        reason: String,
    ): Int =
        synchronized(lock) {
            rows.values
                .filter { it.lane == lane && it.status in WAITING }
                .onEach {
                    it.status = TaskStatus.QUARANTINED
                    it.lastError = reason
                    it.version++
                }.size
        }

    override fun releaseLane(
        tx: StoreTransaction,
        lane: String,
        reason: String,
        now: Instant,
    ): Int =
        synchronized(lock) {
            rows.values
                .filter { it.lane == lane && it.status == TaskStatus.QUARANTINED && it.lastError == reason }
                .onEach {
                    it.status = if (it.runAt.isAfter(now)) TaskStatus.SCHEDULED else TaskStatus.PENDING
                    it.lastError = null
                    it.version++
                }.size
        }

    override fun cancelLane(
        tx: StoreTransaction,
        lane: String,
        now: Instant,
    ): Int =
        synchronized(lock) {
            rows.values
                .filter { it.lane == lane && !it.status.isFinal }
                .onEach {
                    it.status = TaskStatus.CANCELLED
                    it.finishedAt = now
                    it.unlock()
                }.size
        }

    override fun countLane(
        tx: StoreTransaction,
        lane: String,
    ): Map<TaskStatus, Int> =
        synchronized(lock) {
            rows.values
                .filter { it.lane == lane }
                .groupingBy { it.status }
                .eachCount()
        }

    override fun finishCycles(
        tx: StoreTransaction,
        now: Instant,
    ): Int =
        synchronized(lock) {
            cycleRows.values
                .filter { cycle ->
                    cycle.status == CycleStatus.ACTIVE &&
                        rows.values.none {
                            it.lane == cycle.id.toString() && it.type == cycle.type && !it.status.isFinal
                        }
                }.onEach {
                    it.status = CycleStatus.FINISHED
                    it.finishedAt = now
                    it.version++
                }.size
        }

    private fun running(
        id: ID,
        version: Long,
    ): Row<ID>? = rows[id]?.takeIf { it.status == TaskStatus.RUNNING && it.version == version }

    @Suppress("LongParameterList") // one parameter per column, by design
    private class Row<ID : Any>(
        val id: ID,
        val type: String,
        var status: TaskStatus,
        val payload: Map<String, Any?>,
        var runAt: Instant,
        val createdAt: Instant,
        val idempotencyKey: String?,
        val createdBy: String?,
        val payloadVersion: Int,
        val lane: String?,
        val priority: Int,
    ) {
        val sequence: Long = ordinal.incrementAndGet()
        var attempts: Int = 0
        var lastError: String? = null
        var lockedBy: String? = null
        var lockedAt: Instant? = null
        var version: Long = 0
        var finishedAt: Instant? = null

        fun unlock() {
            lockedBy = null
            lockedAt = null
            version++
        }

        fun toRecord() =
            TaskRecord(
                id,
                type,
                status,
                payload,
                runAt,
                attempts,
                lastError,
                lockedBy,
                lockedAt,
                version,
                createdAt,
                idempotencyKey,
                createdBy,
                payloadVersion,
                finishedAt,
                lane,
                priority,
            )

        private companion object {
            val ordinal = AtomicLong()
        }
    }

    private class CycleRow<ID : Any>(
        val id: ID,
        val type: String,
        val description: String?,
        val createdAt: Instant,
        val createdBy: String?,
    ) {
        var status: CycleStatus = CycleStatus.ACTIVE
        var finishedAt: Instant? = null
        var version: Long = 0

        fun toRecord() = CycleRecord(id, type, status, description, createdAt, createdBy, finishedAt, version)
    }

    private companion object {
        val DUE_WHEN_PROMOTED = setOf(TaskStatus.SCHEDULED, TaskStatus.RETRY_PENDING)
        val WAITING = setOf(TaskStatus.PENDING, TaskStatus.SCHEDULED, TaskStatus.RETRY_PENDING)
    }
}
