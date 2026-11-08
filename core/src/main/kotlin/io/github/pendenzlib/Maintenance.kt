package io.github.pendenzlib

import io.github.pendenzlib.store.StoreTransaction
import io.github.pendenzlib.store.TaskStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * One maintenance sweep: schedules get their next occurrence (ADR 0033),
 * finished cycles are closed, and retention runs over all registered task
 * types (ADR 0011). Rows go in batches of [WorkerOptions.retentionBatchSize]
 * so that a large backlog never holds one long lock.
 */
internal class Maintenance<ID : Any>(
    private val store: TaskStore<ID>,
    private val registry: TaskRegistry<ID>,
    private val clock: Clock,
    private val options: WorkerOptions,
    private val listener: TaskListener<ID>,
) {
    private val log = LoggerFactory.getLogger(Maintenance::class.java)
    private val occurrences = Occurrences(store, registry.schedules)

    /**
     * Runs the sweep every [WorkerOptions.maintenanceInterval], the first
     * time one interval after the start, until cancelled; zero means no
     * maintenance on this worker (ADR 0029). A failed sweep is logged, not fatal.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun runRegularly(workerId: String) {
        if (options.maintenanceInterval.isZero) return
        while (true) {
            delay(options.maintenanceInterval.toMillis())
            try {
                run()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error(
                    "worker {} could not run maintenance, retrying after {}",
                    workerId,
                    options.maintenanceInterval,
                    e,
                )
            }
        }
    }

    /**
     * One sweep: schedules, finished cycles, expired locks of cancelled tasks, expired resource blocks, starts
     * older than every rate's window, then every retention rule; returns how many rows retention removed.
     */
    suspend fun run(): Int {
        checkSchedules()
        isolated("finishing cycles") { finishCycles() }
        isolated("clearing expired locks of cancelled tasks") { unlockExpiredCancelled() }
        isolated("deleting expired resource blocks") { deleteExpiredBlocks() }
        isolated("deleting old resource starts") { deleteOldStarts() }
        return registry.types.sumOf { type -> isolated("retention of type ${type.name}") { apply(type) } ?: 0 }
    }

    /** Runs one part of the sweep; a failure is logged and the other parts still run. */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> isolated(
        what: String,
        part: suspend () -> T,
    ): T? =
        try {
            part()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("maintenance failed at {}, going on with the rest", what, e)
            null
        }

    /**
     * Inserts the next occurrence of every schedule that has none open
     * (ADR 0033), each in a transaction of its own; a schedule that fails
     * is logged and the others still get theirs.
     */
    suspend fun checkSchedules() {
        occurrences.schedules.forEach { scheduled ->
            isolated("checking schedule ${scheduled.name}") {
                val occurrence = store.transaction { tx -> occurrences.ensure(tx, scheduled, clock.instant()) }
                store.afterCommit { occurrence?.report(listener) }
            }
        }
    }

    /** Clears locks a dead worker left on tasks cancelled while it ran them (ADR 0031). */
    private suspend fun unlockExpiredCancelled() {
        val cleared = store.transaction { tx -> store.unlockExpiredCancelled(tx, clock.instant()) }
        if (cleared > 0) log.info("{} expired lock(s) of cancelled tasks cleared", cleared)
    }

    /** Deletes blocks whose time has passed (ADR 0035); the listener event of each block is its history. */
    private suspend fun deleteExpiredBlocks() {
        val blocks = store.resourceBlocks ?: return
        val deleted = store.transaction { tx -> blocks.deleteExpired(tx, clock.instant()) }
        if (deleted > 0) log.info("{} expired resource block(s) deleted", deleted)
    }

    /**
     * Deletes the starts older than the longest window of all rates, or all
     * of them when there is no rate (ADR 0040); no poll counts them any more.
     */
    private suspend fun deleteOldStarts() {
        val rates = store.resourceRates ?: return
        val deleted =
            store.transaction { tx ->
                val now = clock.instant()
                val longest = rates.rates(tx).maxOfOrNull { it.per }
                rates.deleteStartsBefore(tx, if (longest == null) now else now.minus(longest))
            }
        if (deleted > 0) log.debug("{} old resource start(s) deleted", deleted)
    }

    /** Marks cycles FINISHED whose tasks are all final (ADR 0016); nothing to do without cycles. */
    private suspend fun finishCycles() {
        val cycles = store.cycles ?: return
        val finished = store.transaction { tx -> cycles.finishCycles(tx, clock.instant()) }
        if (finished > 0) log.info("{} cycle(s) finished", finished)
    }

    /**
     * Removes the type's finished tasks past their retention, each rule over
     * its statuses (ADR 0045), then its finished cycles that have no task
     * left; returns how many tasks went. Cycle rows are logged but not
     * counted, the listener hears of tasks.
     */
    private suspend fun apply(type: TaskType<*>): Int {
        val rules = store.retentionRules(type.name, type.retention, FINAL_STATUSES)
        if (rules.isEmpty()) return 0
        val removed = rules.sumOf { rule -> sweep(type.name, rule.after, "task(s)", rule.remove) }
        if (removed > 0) listener.onRetentionApplied(type.name, removed)
        store.cycles?.let { cycles ->
            sweep(
                type.name,
                rules.minOf { it.after },
                "cycle(s)",
            ) { tx, before, limit -> cycles.deleteFinishedCycles(tx, type.name, before, limit) }
        }
        return removed
    }

    private suspend fun sweep(
        type: String,
        after: Duration,
        what: String,
        batch: (tx: StoreTransaction, finishedBefore: Instant, limit: Int) -> Int,
    ): Int {
        val limit = options.retentionBatchSize
        var total = 0
        do {
            val finishedBefore = clock.instant().minus(after)
            val removed = store.transaction { tx -> batch(tx, finishedBefore, limit) }
            total += removed
        } while (removed == limit)
        if (total > 0) log.info("retention removed {} finished {} of type {}", total, what, type)
        return total
    }
}

/** One rule that removes rows of some final statuses once [after] has passed since they finished. */
private class Rule(
    val after: Duration,
    val remove: (tx: StoreTransaction, finishedBefore: Instant, limit: Int) -> Int,
)

/** The rules of [retention] over the final [statuses] of [type]; none for rows that are kept (ADR 0045). */
private fun TaskStore<*>.retentionRules(
    type: String,
    retention: Retention,
    statuses: Set<TaskStatus>,
): List<Rule> =
    when (retention) {
        Retention.Keep -> {
            emptyList()
        }

        is Retention.Delete -> {
            listOf(Rule(retention.after) { tx, before, limit -> deleteFinished(tx, type, statuses, before, limit) })
        }

        is Retention.Archive -> {
            listOf(
                Rule(retention.after) { tx, before, limit ->
                    archiveFinished(tx, type, statuses, before, limit, retention.table)
                },
            )
        }

        is Retention.ByStatus -> {
            retentionRules(type, retention.succeeded, setOf(TaskStatus.SUCCEEDED)) +
                retentionRules(type, retention.cancelled, setOf(TaskStatus.CANCELLED))
        }
    }

/** The statuses retention removes (ADR 0011). */
private val FINAL_STATUSES = setOf(TaskStatus.SUCCEEDED, TaskStatus.CANCELLED)
