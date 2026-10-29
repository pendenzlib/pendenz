package io.github.pendenzlib

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.Month
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * A five-field cron expression, see [Schedule.cron]. Fire times are local
 * times in [zone]; [next] walks the days from its argument on and takes
 * the first matching minute whose instant lies after the argument.
 */
internal class CronSchedule(
    private val expression: String,
    private val zone: ZoneId,
) : Schedule {
    private val fields: List<String> =
        expression.trim().split(WHITESPACE).also {
            require(it.size == FIELDS) {
                "cron expression '$expression' needs five fields: minute, hour, day of month, month, day of week"
            }
        }

    private val minutes = parsed(MINUTE)
    private val hours = parsed(HOUR)
    private val daysOfMonth = parsed(DAY_OF_MONTH)
    private val months = parsed(MONTH)

    // Sunday is 0 and 7; the lookup uses 0
    private val daysOfWeek = parsed(DAY_OF_WEEK).also { if (it[DAYS_OF_WEEK]) it[0] = true }

    /** Both day fields restricted: either matching is enough, as in Vixie cron. */
    private val eitherDay = listOf(DAY_OF_MONTH, DAY_OF_WEEK).none { fields[it.position].startsWith('*') }

    init {
        require(canFire()) { "cron expression '$expression' names a day that does not exist" }
    }

    private fun parsed(field: Field): BooleanArray =
        try {
            field.parse(fields[field.position])
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("cron expression '$expression': ${e.message}", e)
        }

    override fun next(after: Instant): Instant? {
        val start =
            after
                .atZone(zone)
                .toLocalDateTime()
                .truncatedTo(ChronoUnit.MINUTES)
                .plusMinutes(1)
        var date = start.toLocalDate()
        repeat(SEARCH_DAYS) {
            if (matches(date)) {
                val from = if (date == start.toLocalDate()) start.toLocalTime() else LocalTime.MIDNIGHT
                firstOn(date, from, after)?.let { return it }
            }
            date = date.plusDays(1)
        }
        return null
    }

    /** The first matching minute on [date] from [from] on whose instant lies after [after]. */
    private fun firstOn(
        date: LocalDate,
        from: LocalTime,
        after: Instant,
    ): Instant? {
        for (hour in (from.hour until HOURS).filter { hours[it] }) {
            val firstMinute = if (hour == from.hour) from.minute else 0
            for (minute in (firstMinute until MINUTES).filter { minutes[it] }) {
                // A time in the autumn overlap resolves to its earlier instant, which may lie before after
                val instant = LocalDateTime.of(date, LocalTime.of(hour, minute)).atZone(zone).toInstant()
                if (instant.isAfter(after)) return instant
            }
        }
        return null
    }

    private fun matches(date: LocalDate): Boolean {
        if (!months[date.monthValue]) return false
        val dayOfMonth = daysOfMonth[date.dayOfMonth]
        val dayOfWeek = daysOfWeek[date.dayOfWeek.value % DAYS_OF_WEEK]
        return if (eitherDay) dayOfMonth || dayOfWeek else dayOfMonth && dayOfWeek
    }

    /** False when the day of month never occurs in the selected months, say 30 February. */
    private fun canFire(): Boolean =
        eitherDay ||
            Month.entries.any { month ->
                months[month.value] && (1..month.maxLength()).any { day -> daysOfMonth[day] }
            }

    override fun toString(): String = "Schedule.cron(\"$expression\", $zone)"

    /** The range and names of one field. */
    private class Field(
        val name: String,
        val position: Int,
        val min: Int,
        val max: Int,
        val names: List<String> = emptyList(),
        val nameOffset: Int = 0,
    ) {
        /** Which values [text] selects, indexed by value; index 0 unused for fields starting at 1. */
        fun parse(text: String): BooleanArray {
            val selected = BooleanArray(max + 1)
            text.split(',').forEach { item -> parseItem(item, selected) }
            return selected
        }

        private fun parseItem(
            item: String,
            selected: BooleanArray,
        ) {
            val (range, stepText) = item.split('/', limit = 2).let { it[0] to it.getOrNull(1) }
            val step = stepText?.let { number(it, 1, Int.MAX_VALUE) } ?: 1
            val (from, to) =
                when {
                    range == "*" -> min to max
                    '-' in range -> range.split('-', limit = 2).let { value(it[0]) to value(it[1]) }
                    stepText != null -> value(range) to max
                    else -> value(range).let { it to it }
                }
            require(from <= to) { "$name: range '$range' runs backwards" }
            for (value in from..to step step) selected[value] = true
        }

        private fun value(text: String): Int {
            val index = names.indexOf(text.uppercase())
            return if (index >= 0) index + nameOffset else number(text, min, max)
        }

        private fun number(
            text: String,
            low: Int,
            high: Int,
        ): Int {
            val value = requireNotNull(text.toIntOrNull()) { "$name: '$text' is not a number" }
            require(value in low..high) { "$name: $value is outside $low-$high" }
            return value
        }
    }

    companion object {
        private const val MINUTES = 60
        private const val HOURS = 24
        private const val DAYS_OF_WEEK = 7
        private const val FIELDS = 5

        /** Nine years: a schedule for 29 February may wait eight, across a century that is not a leap year. */
        private const val SEARCH_DAYS = 9 * 366

        private val WHITESPACE = Regex("\\s+")

        private val MINUTE = Field("minute", 0, 0, MINUTES - 1)
        private val HOUR = Field("hour", 1, 0, HOURS - 1)
        private val DAY_OF_MONTH = Field("day of month", 2, 1, 31)
        private val MONTH =
            Field("month", 3, 1, 12, Month.entries.map { it.name.take(3) }, nameOffset = 1)
        private val DAY_OF_WEEK =
            Field("day of week", 4, 0, DAYS_OF_WEEK, listOf("SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT"))
    }
}
