package io.github.d4niella.pendenz

import io.github.d4niella.pendenz.store.StoreTransaction
import io.github.d4niella.pendenz.store.TaskStore
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * One retention sweep over all registered task types (ADR 0011). Rows go
 * in batches of [WorkerOptions.retentionBatchSize] so that a large backlog
 * never holds one long lock.
 */
internal class Maintenance<ID : Any>(
    private val store: TaskStore<ID>,
    private val registry: TaskRegistry<ID>,
    private val clock: Clock,
    private val options: WorkerOptions,
) {
    private val log = LoggerFactory.getLogger(Maintenance::class.java)

    /** Applies every retention rule once and returns how many rows were deleted or archived. */
    suspend fun run(): Int = registry.types.sumOf { type -> apply(type) }

    private suspend fun apply(type: TaskType<*>): Int =
        when (val retention = type.retention) {
            Retention.Keep -> {
                0
            }

            is Retention.Delete -> {
                sweep(type.name, retention.after) { tx, before, limit ->
                    store.deleteFinished(tx, type.name, before, limit)
                }
            }

            is Retention.Archive -> {
                sweep(type.name, retention.after) { tx, before, limit ->
                    store.archiveFinished(tx, type.name, before, limit, retention.table)
                }
            }
        }

    private suspend fun sweep(
        type: String,
        after: Duration,
        batch: (tx: StoreTransaction, finishedBefore: Instant, limit: Int) -> Int,
    ): Int {
        val limit = options.retentionBatchSize
        var total = 0
        do {
            val finishedBefore = clock.instant().minus(after)
            val removed = store.transaction { tx -> batch(tx, finishedBefore, limit) }
            total += removed
        } while (removed == limit)
        if (total > 0) log.info("retention removed {} finished task(s) of type {}", total, type)
        return total
    }
}
