package io.github.d4niella.pendenz.micrometer

import io.github.d4niella.pendenz.Backlog
import io.github.d4niella.pendenz.TaskStatus
import io.github.d4niella.pendenz.micrometer.PendenzMetrics.Companion.BACKLOG
import io.github.d4niella.pendenz.micrometer.PendenzMetrics.Companion.BACKLOG_OLDEST
import io.github.d4niella.pendenz.micrometer.PendenzMetrics.Companion.STATUS
import io.github.d4niella.pendenz.micrometer.PendenzMetrics.Companion.TYPE
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.TimeGauge
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * The backlog gauges. They read the last backlog handed to [update], so a
 * scrape never queries the database; the age of the oldest due task is
 * computed at the scrape and keeps growing between updates.
 */
internal class BacklogGauges(
    private val registry: MeterRegistry,
    private val tags: Tags,
    private val clock: Clock,
) {
    // The maps hold the gauges' state objects; Micrometer itself keeps only weak references.
    private val counts = ConcurrentHashMap<Pair<String, TaskStatus>, AtomicLong>()
    private val oldestDue = ConcurrentHashMap<String, AtomicReference<Instant?>>()

    fun update(backlog: List<Backlog>) {
        backlog.forEach { ofType ->
            ofType.counts.forEach { (status, count) -> count(ofType.type, status).set(count) }
            oldestDue(ofType.type).set(ofType.oldestDue)
        }
        // A type that drained reports 0 instead of its last value, so a dashboard shows the drain.
        val present = backlog.map { it.type }.toSet()
        counts.forEach { (key, count) -> if (key.first !in present) count.set(0) }
        oldestDue.forEach { (type, due) -> if (type !in present) due.set(null) }
    }

    private fun count(
        type: String,
        status: TaskStatus,
    ): AtomicLong =
        counts.computeIfAbsent(type to status) {
            AtomicLong().also { state ->
                Gauge
                    .builder(BACKLOG, state) { it.get().toDouble() }
                    .description("Tasks in a status that is not final")
                    .baseUnit("tasks")
                    .tags(tags)
                    .tags(TYPE, type, STATUS, status.name)
                    .register(registry)
            }
        }

    private fun oldestDue(type: String): AtomicReference<Instant?> =
        oldestDue.computeIfAbsent(type) {
            AtomicReference<Instant?>().also { state ->
                TimeGauge
                    .builder(BACKLOG_OLDEST, state, TimeUnit.MILLISECONDS) { age(it.get()) }
                    .description("How long the oldest due task has waited for a worker")
                    .tags(tags)
                    .tag(TYPE, type)
                    .register(registry)
            }
        }

    private fun age(due: Instant?): Double =
        due?.let {
            Duration
                .between(it, clock.instant())
                .toMillis()
                .coerceAtLeast(0)
                .toDouble()
        } ?: 0.0
}
