package io.github.d4niella.pendenz.store

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
) : TaskStore<ID> {
    private val lock = Any()
    private val rows = LinkedHashMap<ID, Row<ID>>()
    private val sequence = AtomicLong()

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
                )
            id
        }

    override fun promoteDue(
        tx: StoreTransaction,
        now: Instant,
        staleBefore: Instant,
    ): Int =
        synchronized(lock) {
            var changed = 0
            rows.values.forEach { row ->
                val due = row.status in DUE_WHEN_PROMOTED && !row.runAt.isAfter(now)
                val stale = row.status == TaskStatus.RUNNING && row.lockedAt?.isBefore(staleBefore) == true
                if (due || stale) {
                    row.status = TaskStatus.PENDING
                    row.lockedBy = null
                    row.lockedAt = null
                    row.version++
                    changed++
                }
            }
            changed
        }

    override fun claim(
        tx: StoreTransaction,
        workerId: String,
        types: Collection<String>,
        limit: Int,
        now: Instant,
    ): List<ClaimedTask<ID>> =
        synchronized(lock) {
            rows.values
                .filter { it.status == TaskStatus.PENDING && !it.runAt.isAfter(now) && it.type in types }
                .sortedWith(compareBy<Row<ID>> { it.runAt }.thenBy { it.sequence })
                .take(limit.coerceAtLeast(0))
                .map { row ->
                    row.status = TaskStatus.RUNNING
                    row.lockedBy = workerId
                    row.lockedAt = now
                    row.finishedAt = null
                    row.attempts++
                    row.version++
                    ClaimedTask(
                        row.id,
                        row.type,
                        row.payload,
                        row.attempts,
                        row.version,
                        row.createdBy,
                        row.payloadVersion,
                    )
                }
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

    private fun running(
        id: ID,
        version: Long,
    ): Row<ID>? = rows[id]?.takeIf { it.status == TaskStatus.RUNNING && it.version == version }

    @Suppress("LongParameterList") // one parameter per column, by design
    private class Row<ID : Any>(
        val id: ID,
        val type: String,
        var status: TaskStatus,
        val payload: String,
        var runAt: Instant,
        val createdAt: Instant,
        val idempotencyKey: String?,
        val createdBy: String?,
        val payloadVersion: Int,
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
            )

        private companion object {
            val ordinal = AtomicLong()
        }
    }

    private companion object {
        val DUE_WHEN_PROMOTED = setOf(TaskStatus.SCHEDULED, TaskStatus.RETRY_PENDING)
    }
}
