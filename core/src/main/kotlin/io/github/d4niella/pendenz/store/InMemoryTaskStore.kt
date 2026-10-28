package io.github.d4niella.pendenz.store

import io.github.d4niella.pendenz.Backlog
import io.github.d4niella.pendenz.CycleLane
import io.github.d4niella.pendenz.CycleRecord
import io.github.d4niella.pendenz.CycleStatus
import io.github.d4niella.pendenz.CycleStatusTransitions
import io.github.d4niella.pendenz.PayloadColumn
import io.github.d4niella.pendenz.ResourceLimit
import io.github.d4niella.pendenz.TaskQuery
import io.github.d4niella.pendenz.TaskRecord
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
 * inside its own JDBC transaction runs unchanged. Like every store, it
 * accepts only its own transaction handles.
 *
 * @param idType decides how ids are generated when the caller gives none.
 */
@Suppress("TooManyFunctions") // one function per TaskStore operation, the interface dictates the count
public class InMemoryTaskStore<ID : Any>(
    private val idType: IdType<ID>,
) : TaskStore<ID>,
    CycleStore<ID>,
    ResourceLimitStore {
    private val lock = Any()
    private val rows = LinkedHashMap<ID, Row<ID>>()
    private val cycleRows = LinkedHashMap<ID, CycleRow<ID>>()
    private val limits = LinkedHashMap<String, Int>()
    private val sequence = AtomicLong()

    /** Cycles count on their own, as the cycle table's identity column does. */
    private val cycleSequence = AtomicLong()

    /** Compiled once per limit name; the claim matches them under the lock. */
    private val patterns = HashMap<String, Regex>()

    /** Limits are always available in memory; one process needs no lock to make them exact. */
    override val resourceLimits: ResourceLimitStore
        get() = this

    /** Cycles are always available in memory; the query form is not, there is no database to run it. */
    override val cycles: CycleStore<ID>
        get() = this

    private object NoTransaction : StoreTransaction

    override suspend fun <T> transaction(block: (StoreTransaction) -> T): T = block(NoTransaction)

    override fun adopt(connection: Connection): StoreTransaction = NoTransaction

    /** Runs [block] under the lock, after making sure [tx] is this store's own handle (ADR 0006). */
    private inline fun <T> locked(
        tx: StoreTransaction,
        block: () -> T,
    ): T {
        require(tx === NoTransaction) { "transaction ${tx::class.simpleName} is not from this store" }
        return synchronized(lock, block)
    }

    private val archive = ArrayList<TaskRecord<ID>>()

    /** Every task in the store, in insertion order, as it is right now. */
    public fun tasks(): List<TaskRecord<ID>> = synchronized(lock) { rows.values.map { it.toRecord() } }

    /** Every task that retention archived, whatever the archive table was called. */
    public fun archived(): List<TaskRecord<ID>> = synchronized(lock) { archive.toList() }

    override fun insert(
        tx: StoreTransaction,
        task: NewTask<ID>,
    ): ID =
        locked(tx) {
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
                    // A copy, so that a caller who changes its map afterwards does not change the stored task
                    task.payload.toMap(),
                    task.runAt,
                    task.createdAt,
                    key,
                    task.createdBy,
                    task.payloadVersion,
                    task.lane,
                    task.priority,
                    task.resource,
                    task.parentId,
                    task.correlationId,
                    task.preview,
                )
            id
        }

    override fun promoteDue(
        tx: StoreTransaction,
        now: Instant,
    ): Int =
        locked(tx) {
            rows.values
                .filter { it.status in DUE_WHEN_PROMOTED && !it.runAt.isAfter(now) }
                .onEach {
                    it.status = TaskStatus.PENDING
                    it.version++
                }.size
        }

    override fun expiredLocks(
        tx: StoreTransaction,
        types: Collection<String>,
        now: Instant,
        limit: Int,
    ): List<ExpiredLock<ID>> =
        locked(tx) {
            rows.values
                .filter { it.status == TaskStatus.RUNNING && it.type in types && it.lockedUntil?.isBefore(now) == true }
                .sortedBy { it.lockedUntil }
                .take(limit.coerceAtLeast(0))
                .map { ExpiredLock(it.claimed(it.runAt, now), it.lockedBy, it.lockedUntil!!) }
        }

    override fun claim(
        tx: StoreTransaction,
        types: Collection<String>,
        limit: Int,
        terms: ClaimTerms,
    ): List<ClaimedTask<ID>> =
        locked(tx) {
            due(terms) { it.type in types }
                .sortedWith(compareByDescending<Row<ID>> { it.priority }.thenBy { it.runAt }.thenBy { it.sequence })
                .claimWithinLimits(limit, terms)
        }

    /** Claims in order, skipping tasks whose resource is at its limit, counting what this call claims too. */
    private fun List<Row<ID>>.claimWithinLimits(
        limit: Int,
        terms: ClaimTerms,
    ): List<ClaimedTask<ID>> {
        val claimed = ArrayList<ClaimedTask<ID>>()
        val wanted = limit.coerceAtLeast(0)
        for (row in this) {
            if (claimed.size >= wanted) break
            if (row.mayRun()) claimed += row.claimFor(terms.workerId, terms.now, terms.lockedUntil)
        }
        return claimed
    }

    /** Whether one more task of the row's resource may run; this store counts exactly, under its lock. */
    private fun Row<ID>.mayRun(): Boolean {
        val resource = resource ?: return true
        return running(resource) < limitFor(resource)
    }

    override fun runningPerResource(tx: StoreTransaction): Map<String, Int> =
        locked(tx) {
            rows.values
                .filter { it.status == TaskStatus.RUNNING }
                .mapNotNull { it.resource }
                .groupingBy { it }
                .eachCount()
        }

    private fun running(resource: String): Int =
        rows.values.count {
            it.status == TaskStatus.RUNNING &&
                it.resource == resource
        }

    /** The longest matching limit, `LIKE` semantics with `%` and `_`; unlimited without a match. */
    private fun limitFor(resource: String): Int =
        limits.entries
            .filter { (pattern, _) -> patterns.getOrPut(pattern) { likeRegex(pattern) }.matches(resource) }
            .maxByOrNull { it.key.length }
            ?.value ?: Int.MAX_VALUE

    override fun dueGroups(
        tx: StoreTransaction,
        types: Collection<String>,
        terms: ClaimTerms,
    ): List<ClaimGroup> =
        locked(tx) {
            due(terms) { it.type in types && it.mayRun() }
                .map { ClaimGroup(it.priority, it.type, it.lane) }
                .distinct()
                // Same order as PostgreSQL: nulls last, so the singles come after the lanes.
                .sortedWith(
                    compareByDescending<ClaimGroup> { it.priority }.thenBy { it.type }.thenBy(nullsLast()) { it.lane },
                )
        }

    override fun claimFromGroup(
        tx: StoreTransaction,
        group: ClaimGroup,
        limit: Int,
        terms: ClaimTerms,
    ): List<ClaimedTask<ID>> =
        locked(tx) {
            due(terms) { it.type == group.type && it.lane == group.lane && it.priority == group.priority }
                .sortedWith(compareBy<Row<ID>> { it.runAt }.thenBy { it.sequence })
                .claimWithinLimits(limit, terms)
        }

    /** Pending rows due at the terms' time, leaving out the resources the terms call full. */
    private fun due(
        terms: ClaimTerms,
        matches: (Row<ID>) -> Boolean,
    ): List<Row<ID>> =
        rows.values.filter {
            it.status == TaskStatus.PENDING && !it.runAt.isAfter(terms.now) &&
                it.resource !in terms.resources.full && matches(it)
        }

    private fun Row<ID>.claimFor(
        workerId: String,
        now: Instant,
        lockedUntil: Instant,
    ): ClaimedTask<ID> {
        val dueAt = runAt
        status = TaskStatus.RUNNING
        lockedBy = workerId
        this.lockedUntil = lockedUntil
        finishedAt = null
        attempts++
        version++
        return claimed(dueAt, now)
    }

    private fun Row<ID>.claimed(
        dueAt: Instant,
        claimedAt: Instant,
    ): ClaimedTask<ID> =
        ClaimedTask(
            id,
            type,
            payload,
            attempts,
            version,
            createdBy,
            payloadVersion,
            lane,
            priority,
            resource,
            parentId,
            correlationId,
            dueAt,
            claimedAt,
        )

    override fun heartbeat(
        tx: StoreTransaction,
        id: ID,
        expectedVersion: Long,
        lockedUntil: Instant,
    ): Boolean =
        locked(tx) {
            val row = running(id, expectedVersion) ?: return false
            row.lockedUntil = lockedUntil
            true
        }

    override fun finish(
        tx: StoreTransaction,
        id: ID,
        expectedVersion: Long,
        outcome: Outcome,
        now: Instant,
    ): Boolean =
        locked(tx) {
            val row = running(id, expectedVersion) ?: return false
            row.provenance = outcome.provenance
            when (outcome) {
                is Outcome.Succeeded -> {
                    row.status = TaskStatus.SUCCEEDED
                    row.lastError = null
                    row.finishedAt = now
                    row.result = outcome.result
                }

                is Outcome.Rejected -> {
                    row.status = TaskStatus.REJECTED
                    row.lastError = outcome.reason
                    row.finishedAt = now
                    row.result = outcome.result
                }

                is Outcome.Retry -> {
                    row.status = TaskStatus.RETRY_PENDING
                    row.runAt = outcome.runAt
                    row.lastError = outcome.error
                    row.finishedAt = null
                    row.result = null
                }

                is Outcome.Failed -> {
                    row.status = TaskStatus.FAILED
                    row.lastError = outcome.error
                    row.finishedAt = now
                    row.result = null
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
        locked(tx) {
            val row = running(id, expectedVersion) ?: return false
            row.status = TaskStatus.PENDING
            row.unlock()
            true
        }

    override fun find(
        tx: StoreTransaction,
        id: ID,
    ): TaskRecord<ID>? = locked(tx) { rows[id]?.toRecord() }

    override fun search(
        tx: StoreTransaction,
        query: TaskQuery,
    ): List<TaskRecord<ID>> =
        locked(tx) {
            val newestFirst = compareByDescending<Row<ID>> { it.createdAt }.thenByDescending { it.id.comparable() }
            val cursor = query.olderThan
            rows.values
                .asSequence()
                .filter { query.createdBy == null || it.createdBy == query.createdBy }
                .filter { query.types.isEmpty() || it.type in query.types }
                .filter { query.statuses.isEmpty() || it.status in query.statuses }
                .filter { query.correlationId == null || it.correlationId == query.correlationId }
                .filter { query.lane == null || it.lane == query.lane }
                .filter { query.parentId == null || it.parentId == query.parentId }
                .filter {
                    cursor == null ||
                        it.createdAt < cursor.createdAt ||
                        (it.createdAt == cursor.createdAt && it.id.comparable() < cursor.id.comparable())
                }.sortedWith(newestFirst)
                .take(query.limit)
                .map { it.toRecord() }
                .toList()
        }

    override fun backlog(
        tx: StoreTransaction,
        now: Instant,
    ): List<Backlog> =
        locked(tx) {
            val rows =
                rows.values
                    .filter { !it.status.isFinal }
                    .groupBy { it.type to it.status }
                    .map { (key, group) ->
                        val due = group.map { it.runAt }.filter { !it.isAfter(now) }
                        Backlog.Row(key.first, key.second, group.size.toLong(), due.minOrNull())
                    }
            Backlog.of(rows)
        }

    override fun move(
        tx: StoreTransaction,
        id: ID,
        from: Collection<TaskStatus>,
        to: TaskStatus,
        reason: String?,
        now: Instant,
    ): Boolean =
        locked(tx) {
            val row = rows[id]?.takeIf { it.status in from } ?: return false
            row.moveTo(to, reason, now)
            true
        }

    override fun approve(
        tx: StoreTransaction,
        id: ID,
        actor: String,
        now: Instant,
    ): Boolean =
        locked(tx) {
            val row = rows[id]?.takeIf { it.status == TaskStatus.AWAITING_APPROVAL } ?: return false
            row.approve(actor, now)
            true
        }

    override fun approveLane(
        tx: StoreTransaction,
        lane: String,
        type: String,
        actor: String,
        now: Instant,
    ): Int =
        locked(tx) {
            rows.values
                .filter { it.lane == lane && it.type == type && it.status == TaskStatus.AWAITING_APPROVAL }
                .onEach { it.approve(actor, now) }
                .size
        }

    private fun Row<ID>.approve(
        actor: String,
        now: Instant,
    ) {
        release(now)
        approvedBy = actor
        approvedAt = now
    }

    /** An operator's move, as [OperatorMoves] describes it. */
    private fun Row<ID>.moveTo(
        to: TaskStatus,
        reason: String?,
        now: Instant,
    ) {
        status = to
        finishedAt = now.takeIf { to.isFinal }
        if (reason != null) lastError = reason
        unlock()
    }

    /** Back to PENDING when due, to SCHEDULED when the run time lies ahead, as [OperatorMoves] describes it. */
    private fun Row<ID>.release(now: Instant) {
        status = OperatorMoves.released(runAt, now)
        finishedAt = null
        unlock()
    }

    override fun deleteFinished(
        tx: StoreTransaction,
        type: String,
        finishedBefore: Instant,
        limit: Int,
    ): Int = locked(tx) { expired(type, finishedBefore, limit).onEach { rows.remove(it.id) }.size }

    override fun archiveFinished(
        tx: StoreTransaction,
        type: String,
        finishedBefore: Instant,
        limit: Int,
        archiveTable: String,
    ): Int =
        locked(tx) {
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
        locked(tx) {
            val id = cycle.id ?: idType.generate() ?: idType.fromSequence(cycleSequence.incrementAndGet())
            require(id !in cycleRows) { "a cycle with id $id already exists" }
            cycleRows[id] = CycleRow(id, cycle.type, cycle.description, cycle.createdAt, cycle.createdBy)
            id
        }

    override fun insertTasksFromQuery(
        tx: StoreTransaction,
        query: String,
        columns: List<PayloadColumn>,
        lane: String,
        fixed: NewTask<ID>,
        resourceSql: String?,
    ): Int = throw UnsupportedOperationException("the in-memory store cannot run a query; use CycleTasks.of(payloads)")

    override fun setLimit(
        tx: StoreTransaction,
        limit: ResourceLimit,
    ) {
        locked(tx) { limits[limit.name] = limit.maxRunning }
    }

    override fun seedLimit(
        tx: StoreTransaction,
        limit: ResourceLimit,
    ) {
        locked(tx) { limits.putIfAbsent(limit.name, limit.maxRunning) }
    }

    override fun removeLimit(
        tx: StoreTransaction,
        name: String,
    ): Boolean = locked(tx) { limits.remove(name) != null }

    override fun limits(tx: StoreTransaction): List<ResourceLimit> =
        locked(tx) { limits.map { ResourceLimit(it.key, it.value) }.sortedBy { it.name } }

    override fun lockClaims(tx: StoreTransaction) {
        // one process, one lock object: claims are exact without it
        locked(tx) {}
    }

    override fun findCycle(
        tx: StoreTransaction,
        id: ID,
    ): CycleRecord<ID>? = locked(tx) { cycleRows[id]?.toRecord() }

    override fun updateCycleStatus(
        tx: StoreTransaction,
        id: ID,
        from: Collection<CycleStatus>,
        to: CycleStatus,
        now: Instant,
    ): Boolean =
        locked(tx) {
            val row = cycleRows[id]?.takeIf { it.status in from } ?: return false
            row.status = to
            if (to.isFinal) row.finishedAt = now
            row.version++
            true
        }

    override fun quarantineLane(
        tx: StoreTransaction,
        lane: String,
        reason: String,
        now: Instant,
    ): Int =
        locked(tx) {
            rows.values
                .filter { it.lane == lane && it.status in OperatorMoves.pausable }
                .onEach { it.moveTo(TaskStatus.QUARANTINED, reason, now) }
                .size
        }

    override fun releaseLane(
        tx: StoreTransaction,
        lane: String,
        reason: String,
        now: Instant,
    ): Int =
        locked(tx) {
            rows.values
                .filter { it.lane == lane && it.status == TaskStatus.QUARANTINED && it.lastError == reason }
                .onEach {
                    it.release(now)
                    it.lastError = null
                }.size
        }

    override fun cancelLane(
        tx: StoreTransaction,
        lane: String,
        now: Instant,
    ): Int =
        locked(tx) {
            rows.values
                .filter { it.lane == lane && it.status in OperatorMoves.cancellable }
                .onEach { it.moveTo(TaskStatus.CANCELLED, null, now) }
                .size
        }

    override fun countLane(
        tx: StoreTransaction,
        lane: String,
    ): Map<TaskStatus, Int> =
        locked(tx) {
            rows.values
                .filter { it.lane == lane }
                .groupingBy { it.status }
                .eachCount()
        }

    override fun finishCycles(
        tx: StoreTransaction,
        now: Instant,
    ): Int =
        locked(tx) {
            cycleRows.values
                .filter { cycle ->
                    cycle.status in FINISHABLE &&
                        rows.values.none {
                            it.lane == CycleLane.of(cycle.id) && it.status in OperatorMoves.unfinished
                        }
                }.onEach {
                    it.status = CycleStatus.FINISHED
                    it.finishedAt = now
                    it.version++
                }.size
        }

    override fun deleteFinishedCycles(
        tx: StoreTransaction,
        type: String,
        finishedBefore: Instant,
        limit: Int,
    ): Int =
        locked(tx) {
            cycleRows.values
                .filter { cycle ->
                    cycle.type == type && cycle.status.isFinal && cycle.finishedAt?.isBefore(finishedBefore) == true &&
                        rows.values.none { it.lane == CycleLane.of(cycle.id) }
                }.take(limit.coerceAtLeast(0))
                .onEach { cycleRows.remove(it.id) }
                .size
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
        val resource: String?,
        val parentId: ID?,
        val correlationId: String?,
        val preview: String?,
    ) {
        val sequence: Long = ordinal.incrementAndGet()
        var result: String? = null
        var provenance: Map<String, String> = emptyMap()
        var attempts: Int = 0
        var lastError: String? = null
        var lockedBy: String? = null
        var lockedUntil: Instant? = null
        var version: Long = 0
        var finishedAt: Instant? = null
        var approvedBy: String? = null
        var approvedAt: Instant? = null

        fun unlock() {
            lockedBy = null
            lockedUntil = null
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
                lockedUntil,
                version,
                createdAt,
                idempotencyKey,
                createdBy,
                payloadVersion,
                finishedAt,
                lane,
                priority,
                resource,
                approvedBy,
                approvedAt,
                parentId,
                correlationId,
                result,
                provenance,
                preview,
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
        val FINISHABLE = CycleStatusTransitions.sourcesOf(CycleStatus.FINISHED)
    }
}

/** Ids of every supported id type are comparable: Long and UUID. */
@Suppress("UNCHECKED_CAST")
private fun Any.comparable(): Comparable<Any> = this as Comparable<Any>
