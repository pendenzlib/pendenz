package io.github.d4niella.pendenz.examples.catalog

import io.github.d4niella.pendenz.CycleTasks
import io.github.d4niella.pendenz.Pendenz
import io.github.d4niella.pendenz.TaskStatus
import io.github.d4niella.pendenz.WorkerOptions
import io.github.d4niella.pendenz.micrometer.PendenzMetrics
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

private val log = LoggerFactory.getLogger("catalog")

/** How the run paces itself; the defaults suit a person watching, tests shorten them. */
data class RunSettings(
    /** How many generated products the catalogue gets, besides the three troublesome ones. */
    val products: Int = DEFAULT_PRODUCTS,
    val pollInterval: Duration = Duration.ofMillis(DEFAULT_POLL_MILLIS),
    /** The share of the run after which the operator pauses the cycle. */
    val pauseAt: Double = DEFAULT_PAUSE_AT,
    /** How long the cycle stays paused, to show that nothing new starts meanwhile. */
    val pauseFor: Duration = Duration.ofSeconds(DEFAULT_PAUSE_SECONDS),
    val progressEvery: Duration = Duration.ofSeconds(DEFAULT_PROGRESS_SECONDS),
    /** The longest the run waits for the model to work through the catalogue. */
    val settleWithin: Duration = Duration.ofHours(DEFAULT_SETTLE_HOURS),
) {
    companion object {
        const val DEFAULT_PRODUCTS = 60
        private const val DEFAULT_POLL_MILLIS = 200L
        private const val DEFAULT_PAUSE_AT = 0.3
        private const val DEFAULT_PAUSE_SECONDS = 5L
        private const val DEFAULT_PROGRESS_SECONDS = 15L
        private const val DEFAULT_SETTLE_HOURS = 2L
    }
}

/** What a run left behind, read from the tables and the meters. */
data class CatalogReport(
    val tasks: Int,
    val statuses: Map<TaskStatus, Int>,
    /** Tasks the pause set aside as QUARANTINED, released again on resume. */
    val setAsideByPause: Int,
    /** FAILED and REJECTED tasks the operator restarted with SQL. */
    val restartedByOperator: Int,
    /** The recalled product's task, taken out of the run with SQL. */
    val quarantinedByOperator: Int,
    /** Runs that ended in a retry, from the execution timer. */
    val retries: Long,
    /** Classified products whose category matches what a person would say. */
    val correct: Int,
    val classified: Int,
)

/**
 * The demo cycle: a cycle classifies the whole catalogue through the
 * model, one request at a time. Midway an operator pauses and resumes it.
 * Injected faults cause retries and refused answers, three products fail
 * for reasons the handler cannot fix; once the model is done, the
 * operator repairs the data and restarts or quarantines with SQL, and the
 * cycle finishes what is left.
 */
fun runCatalog(
    database: DatabaseAccess,
    model: CategoryModel,
    settings: RunSettings = RunSettings(),
): CatalogReport =
    runBlocking {
        database.dataSource().use { dataSource ->
            database.migrate(dataSource, settings.products)
            val registry = SimpleMeterRegistry()
            val metrics = PendenzMetrics<Long>(registry, Tags.of("queue", "catalog"))
            val pendenz = catalogQueue(dataSource, model, metrics)
            val worker = pendenz.startWorker(this, WorkerOptions(pollInterval = settings.pollInterval))
            val watch = metrics.watchBacklog(pendenz, this, settings.pollInterval)
            try {
                val report = Run(pendenz, dataSource, settings).play()
                log.info("meters: {}", registry.summary())
                report.copy(retries = registry.retries())
            } finally {
                watch.cancelAndJoin()
                worker.cancelAndJoin()
            }
        }
    }

private class Run(
    private val pendenz: Pendenz<Long>,
    private val dataSource: DataSource,
    private val settings: RunSettings,
) {
    private val operator = Operator(dataSource)

    suspend fun play(): CatalogReport {
        val cycle =
            pendenz.startCycle(
                Catalog.CLASSIFY,
                CycleTasks.query("SELECT id FROM product ORDER BY id"),
                description = "Categorise the catalogue",
                createdBy = "catalog-run",
            )
        val tasks = progress(cycle).values.sum()
        log.info(
            "cycle {} started with {} tasks; limits in {}: {}",
            cycle,
            tasks,
            Catalog.LIMITS.name,
            pendenz.resourceLimits(),
        )

        val pauseAt = (tasks * settings.pauseAt).toInt()
        waitUntil(cycle, "pausing at $pauseAt") { done(it) >= pauseAt }
        check(pendenz.pauseCycle(cycle, actor = OPERATOR)) { "cycle $cycle could not be paused" }
        val setAside = progress(cycle)[TaskStatus.QUARANTINED] ?: 0
        log.info("operator paused the cycle: {} waiting tasks set aside, the running one finishes", setAside)
        delay(settings.pauseFor.toMillis())
        logProgress(cycle, "while paused")
        check(pendenz.resumeCycle(cycle, actor = OPERATOR)) { "cycle $cycle could not be resumed" }
        log.info("operator resumed the cycle")

        waitUntil(cycle, "waiting for the rest") { active(it) == 0 }
        logProgress(cycle, "the model is through; these wait for a person")
        operator.showNeedsHuman()
        val restarted = operator.repairAndRestart()
        val quarantined = operator.quarantineRecalled()

        waitUntil(cycle, "after the operator's restart") { active(it) == 0 }
        logProgress(cycle, "final")
        val (correct, classified) = operator.accuracy()
        log.info("{} of {} classified products got the category a person would give", correct, classified)
        return CatalogReport(tasks, progress(cycle), setAside, restarted, quarantined, 0, correct, classified)
    }

    private suspend fun progress(cycle: Long): Map<TaskStatus, Int> = pendenz.cycleProgress(cycle)

    private fun done(progress: Map<TaskStatus, Int>): Int =
        progress.filterKeys { it !in ACTIVE && it != TaskStatus.QUARANTINED }.values.sum()

    private fun active(progress: Map<TaskStatus, Int>): Int = progress.filterKeys { it in ACTIVE }.values.sum()

    /** Polls the cycle's progress until [reached], logging how far it got every [RunSettings.progressEvery]. */
    private suspend fun waitUntil(
        cycle: Long,
        stage: String,
        reached: (Map<TaskStatus, Int>) -> Boolean,
    ) {
        val deadline = Instant.now().plus(settings.settleWithin)
        var nextLog = Instant.now()
        while (true) {
            val progress = progress(cycle)
            if (reached(progress)) return
            check(Instant.now().isBefore(deadline)) { "the cycle did not get there within ${settings.settleWithin}" }
            if (!Instant.now().isBefore(nextLog)) {
                log.info("{} of {} done, {}", done(progress), progress.values.sum(), stage)
                nextLog = Instant.now().plus(settings.progressEvery)
            }
            delay(settings.pollInterval.toMillis())
        }
    }

    private suspend fun logProgress(
        cycle: Long,
        headline: String,
    ) {
        val progress = progress(cycle).entries.sortedBy { it.key }.joinToString { "${it.key} ${it.value}" }
        log.info("{}: {}", headline, progress)
    }

    companion object {
        const val OPERATOR = "operator"
        val ACTIVE = setOf(TaskStatus.PENDING, TaskStatus.SCHEDULED, TaskStatus.RETRY_PENDING, TaskStatus.RUNNING)
    }
}

/** What a person does with SQL once the model is through; every statement is logged as it runs. */
private class Operator(
    private val dataSource: DataSource,
) {
    fun showNeedsHuman() {
        val query =
            """
            SELECT t.id, t.status, t.attempts, p.name, t.last_error
            FROM catalog_task t JOIN product p ON p.id = t.product_id
            WHERE t.status IN ('FAILED', 'REJECTED') ORDER BY t.id
            """.trimIndent()
        dataSource.query(query) { rs ->
            while (rs.next()) {
                log.info(
                    "  #{} {} after {} attempt(s), {}: {}",
                    rs.getLong("id"),
                    rs.getString("status"),
                    rs.getInt("attempts"),
                    rs.getString("name"),
                    rs.getString("last_error"),
                )
            }
        }
    }

    /** Adds the missing descriptions, then restarts every FAILED or REJECTED task of a product still for sale. */
    fun repairAndRestart(): Int {
        update(
            """
            UPDATE product SET description = CASE name
                WHEN 'Mystery box' THEN 'A surprise box with three small toys for children from five years.'
                WHEN 'Desk lamp' THEN 'An adjustable LED lamp for a desk at work.'
            END
            WHERE description IS NULL
            """.trimIndent(),
        )
        return count(
            """
            SELECT count(*) FILTER (WHERE catalog_task_restart(t.id, t.version))
            FROM catalog_task t JOIN product p ON p.id = t.product_id
            WHERE t.status IN ('FAILED', 'REJECTED') AND NOT p.recalled
            """.trimIndent(),
        )
    }

    /** Takes the recalled product's task out of the run, with the reason in its row. */
    fun quarantineRecalled(): Int =
        count(
            """
            SELECT count(*) FILTER (WHERE catalog_task_quarantine(t.id, t.version, 'recalled, not part of the catalogue'))
            FROM catalog_task t JOIN product p ON p.id = t.product_id
            WHERE t.status = 'FAILED' AND p.recalled
            """.trimIndent(),
        )

    /** How many succeeded classifications match the expected category, and how many there are. */
    fun accuracy(): Pair<Int, Int> =
        dataSource.query(
            """
            SELECT count(*) FILTER (WHERE t.result ->> 'category' = p.expected_category) AS correct,
                   count(*) AS classified
            FROM catalog_task t JOIN product p ON p.id = t.product_id
            WHERE t.status = 'SUCCEEDED'
            """.trimIndent(),
        ) { rs ->
            rs.next()
            rs.getInt("correct") to rs.getInt("classified")
        }

    private fun update(sql: String) {
        log.info("operator runs:\n{}", sql)
        val changed = dataSource.connection.use { c -> c.createStatement().use { it.executeUpdate(sql) } }
        log.info("  {} row(s) changed", changed)
    }

    private fun count(sql: String): Int {
        log.info("operator runs:\n{}", sql)
        val changed =
            dataSource.query(sql) { rs ->
                rs.next()
                rs.getInt(1)
            }
        log.info("  {} task(s) changed", changed)
        return changed
    }
}

private fun SimpleMeterRegistry.retries(): Long =
    find(PendenzMetrics.EXECUTION).tags("outcome", "retry").timers().sumOf { it.count() }

/** The execution timer's runs per outcome and the mean wait from due to claim, for the closing summary. */
internal fun SimpleMeterRegistry.summary(): String {
    val outcomes =
        find(PendenzMetrics.EXECUTION)
            .timers()
            .groupBy { it.id.getTag("outcome") }
            .mapValues { (_, timers) -> timers.sumOf { it.count() } }
    val wait = find(PendenzMetrics.WAIT).timer()
    return "runs by outcome $outcomes, mean wait ${wait?.mean(TimeUnit.SECONDS)?.toLong()} s"
}
