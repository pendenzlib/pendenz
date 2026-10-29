package io.github.pendenzlib

import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * When a recurring task fires. Registered with a payload and a task type
 * through `Pendenz.Builder.schedule`; see ADR 0033.
 *
 * [next] must be a pure function of its argument: every instance of the
 * application has to compute the same fire times, because a fire time is
 * part of an occurrence's identity. An implementation that consults a
 * clock or a random source breaks deduplication across instances.
 */
public fun interface Schedule {
    /** The first fire time strictly after [after], or null when the series has ended. */
    public fun next(after: Instant): Instant?

    public companion object {
        /**
         * Fires at `anchor + n * interval`. Anchored rather than counted from
         * the start, so that instances started at different moments agree:
         * every fifteen minutes fires at :00, :15, :30 and :45 UTC.
         */
        @JvmStatic
        @JvmOverloads
        public fun every(
            interval: Duration,
            anchor: Instant = Instant.EPOCH,
        ): Schedule = Every(interval, anchor)

        /**
         * Fires once a day at [time] in [zone]. A time that the change to
         * daylight saving skips fires later by the length of the gap; a time
         * that the change back repeats fires once, at the earlier instant.
         */
        @JvmStatic
        public fun daily(
            time: LocalTime,
            zone: ZoneId,
        ): Schedule = Daily(time, zone)

        /**
         * Fires at the times a five-field cron [expression] names in [zone]:
         * minute, hour, day of month, month, day of week. Each field takes
         * `*`, numbers, ranges `a-b`, lists `a,b` and steps `a-b/n`, where `*`
         * stands for the whole range and `a/n` for `a` to its end. Months and
         * days also take their English three-letter names, and
         * Sunday is 0 or 7. When both day fields are restricted, either
         * matching is enough, as in Vixie cron. No seconds field, no
         * macros, no Quartz extensions. Daylight saving is handled as in [daily].
         *
         * @throws IllegalArgumentException when the expression cannot be parsed or can never fire.
         */
        @JvmStatic
        public fun cron(
            expression: String,
            zone: ZoneId,
        ): Schedule = CronSchedule(expression, zone)
    }
}

/** [Schedule.every]. */
private class Every(
    private val interval: Duration,
    private val anchor: Instant,
) : Schedule {
    init {
        require(!interval.isNegative && !interval.isZero) { "interval must be positive, was $interval" }
    }

    override fun next(after: Instant): Instant {
        // dividedBy truncates towards zero, so the candidate is at most one interval short of the answer
        val steps = Duration.between(anchor, after).dividedBy(interval)
        val candidate = anchor.plus(interval.multipliedBy(steps))
        return if (candidate.isAfter(after)) candidate else candidate.plus(interval)
    }

    override fun toString(): String = "Schedule.every($interval, anchor=$anchor)"
}

/** [Schedule.daily]. */
private class Daily(
    private val time: LocalTime,
    private val zone: ZoneId,
) : Schedule {
    override fun next(after: Instant): Instant {
        var date = after.atZone(zone).toLocalDate()
        while (true) {
            val candidate = ZonedDateTime.of(date, time, zone).toInstant()
            if (candidate.isAfter(after)) return candidate
            date = date.plusDays(1)
        }
    }

    override fun toString(): String = "Schedule.daily($time, $zone)"
}
