package io.github.pendenzlib.javaapi.testing

import io.github.pendenzlib.schema.IdType
import io.github.pendenzlib.store.ClaimTerms
import io.github.pendenzlib.store.InMemoryTaskStore
import io.github.pendenzlib.store.Outcome
import io.github.pendenzlib.store.StoreTransaction
import io.github.pendenzlib.store.TaskStore
import kotlinx.coroutines.runBlocking
import java.sql.SQLException
import java.time.Instant

/**
 * An in-memory store for the Java tests whose tasks a worker can die with,
 * and which takes no failed attempt: what a table does whose trigger refuses
 * the row of an expired lock (ADR 0049).
 */
class DeadWorkerStore private constructor(
    private val inner: InMemoryTaskStore<Long>,
) : TaskStore<Long> by inner {
    constructor() : this(InMemoryTaskStore(IdType.BIGINT))

    override fun finish(
        tx: StoreTransaction,
        id: Long,
        expectedVersion: Long,
        outcome: Outcome,
        now: Instant,
    ): Boolean {
        if (outcome is Outcome.Failed || outcome is Outcome.Retry) throw SQLException("this row is closed", "P0001")
        return inner.finish(tx, id, expectedVersion, outcome, now)
    }

    /** Claims the next due task of [type] at [now] as a worker that dies right after. */
    fun claimAndDie(
        type: String,
        now: Instant,
    ) {
        runBlocking {
            inner.transaction { inner.claim(it, setOf(type), 1, ClaimTerms("dead-worker", now, now)) }
        }
    }
}
