package io.github.pendenzlib.micrometer

import io.github.pendenzlib.ScheduleState
import io.github.pendenzlib.micrometer.PendenzMetrics.Companion.SCHEDULE
import io.github.pendenzlib.micrometer.PendenzMetrics.Companion.SCHEDULE_LAST_SUCCESS
import io.github.pendenzlib.micrometer.PendenzMetrics.Companion.SCHEDULE_NEXT
import io.github.pendenzlib.micrometer.PendenzMetrics.Companion.TYPE
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.TimeGauge
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The gauges per schedule (ADR 0034). Like [BacklogGauges] they keep the
 * instants read at the last [update] and compute the distance from now at
 * every scrape, so an overdue occurrence keeps growing between updates.
 */
internal class ScheduleGauges(
    private val registry: MeterRegistry,
    private val tags: Tags,
    private val clock: Clock,
) {
    // The map holds the gauges' state objects; Micrometer itself keeps only weak references.
    private val gauges = ConcurrentHashMap<String, State>()

    fun update(schedules: List<ScheduleState<*>>) {
        schedules.forEach { schedule ->
            val state = gauges.computeIfAbsent(schedule.name) { register(it, schedule.type) }
            state.next.set(schedule.next)
            state.lastSuccess.set(schedule.lastSuccess?.finishedAt)
        }
    }

    private fun register(
        name: String,
        type: String,
    ): State {
        val state = State()
        TimeGauge
            .builder(SCHEDULE_NEXT, state.next, TimeUnit.MILLISECONDS) { until(it.get()) }
            .description("Time until the schedule's open occurrence is due; negative when overdue, NaN when none")
            .tags(tags)
            .tags(SCHEDULE, name, TYPE, type)
            .register(registry)
        TimeGauge
            .builder(SCHEDULE_LAST_SUCCESS, state.lastSuccess, TimeUnit.MILLISECONDS) { since(it.get()) }
            .description("Time since an occurrence of the schedule last succeeded; NaN when none did")
            .tags(tags)
            .tags(SCHEDULE, name, TYPE, type)
            .register(registry)
        return state
    }

    private fun until(next: Instant?): Double =
        next?.let { Duration.between(clock.instant(), it).toMillis().toDouble() } ?: Double.NaN

    private fun since(finished: Instant?): Double =
        finished?.let { Duration.between(it, clock.instant()).toMillis().toDouble() } ?: Double.NaN

    private class State {
        val next = AtomicReference<Instant?>()
        val lastSuccess = AtomicReference<Instant?>()
    }
}
