package io.github.pendenzlib

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/** Fire times of the built-in schedules (ADR 0033). 2026-10-29 is a Thursday. */
class ScheduleTest {
    private val berlin = ZoneId.of("Europe/Berlin")

    @Nested
    inner class Every {
        @Test
        fun `every - after lies between two fire times - the next fire time counted from the anchor`() {
            val quarterHour = Schedule.every(Duration.ofMinutes(15))

            val next = quarterHour.next(at("2026-10-29T10:07:30Z"))

            assertThat(next).isEqualTo(at("2026-10-29T10:15:00Z"))
        }

        @Test
        fun `every - after is a fire time - the following fire time`() {
            val quarterHour = Schedule.every(Duration.ofMinutes(15))

            val next = quarterHour.next(at("2026-10-29T10:15:00Z"))

            assertThat(next).isEqualTo(at("2026-10-29T10:30:00Z"))
        }

        @Test
        fun `every - after lies before the anchor - the first fire time after it`() {
            val hourly = Schedule.every(Duration.ofHours(1), anchor = at("2026-10-29T12:20:00Z"))

            val next = hourly.next(at("2026-10-29T09:30:00Z"))

            assertThat(next).isEqualTo(at("2026-10-29T10:20:00Z"))
        }

        @Test
        fun `every - zero interval - IllegalArgumentException`() {
            assertThatThrownBy { Schedule.every(Duration.ZERO) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("interval")
        }
    }

    @Nested
    inner class Daily {
        private val atThree = Schedule.daily(LocalTime.of(3, 0), berlin)

        @Test
        fun `daily - before today's time - today at that local time`() {
            val next = atThree.next(at("2026-10-29T01:00:00Z"))

            assertThat(next).isEqualTo(at("2026-10-29T02:00:00Z"))
        }

        @Test
        fun `daily - at today's fire time - tomorrow at that local time`() {
            val next = atThree.next(at("2026-10-29T02:00:00Z"))

            assertThat(next).isEqualTo(at("2026-10-30T02:00:00Z"))
        }

        @Test
        fun `daily - spring change skips the time - later by the gap, then back on time`() {
            val halfPastTwo = Schedule.daily(LocalTime.of(2, 30), berlin)

            val fireTimes = fireTimes(halfPastTwo, at("2026-03-28T12:00:00Z"), 2)

            // 03:30 CEST, then 02:30 CEST
            assertThat(fireTimes).containsExactly(at("2026-03-29T01:30:00Z"), at("2026-03-30T00:30:00Z"))
        }

        @Test
        fun `daily - autumn change repeats the time - fires once, at the earlier instant`() {
            val halfPastTwo = Schedule.daily(LocalTime.of(2, 30), berlin)

            val fireTimes = fireTimes(halfPastTwo, at("2026-10-24T12:00:00Z"), 2)

            // 02:30 CEST on the day of the change, then 02:30 CET
            assertThat(fireTimes).containsExactly(at("2026-10-25T00:30:00Z"), at("2026-10-26T01:30:00Z"))
        }
    }

    @Nested
    inner class Cron {
        @Test
        fun `cron - step over the whole range - the next quarter hour`() {
            val quarterHour = Schedule.cron("*/15 * * * *", ZoneOffset.UTC)

            val next = quarterHour.next(at("2026-10-29T10:07:30Z"))

            assertThat(next).isEqualTo(at("2026-10-29T10:15:00Z"))
        }

        @Test
        fun `cron - after is a fire time - the following one`() {
            val nightly = Schedule.cron("0 3 * * *", berlin)

            val next = nightly.next(at("2026-10-29T02:00:00Z"))

            assertThat(next).isEqualTo(at("2026-10-30T02:00:00Z"))
        }

        @Test
        fun `cron - list, and a range with a step - each listed and each stepped hour`() {
            val workingHours = Schedule.cron("0,30 8-18/5 * * *", ZoneOffset.UTC)

            val fireTimes = fireTimes(workingHours, at("2026-10-29T00:00:00Z"), 7)

            assertThat(fireTimes).containsExactly(
                at("2026-10-29T08:00:00Z"),
                at("2026-10-29T08:30:00Z"),
                at("2026-10-29T13:00:00Z"),
                at("2026-10-29T13:30:00Z"),
                at("2026-10-29T18:00:00Z"),
                at("2026-10-29T18:30:00Z"),
                at("2026-10-30T08:00:00Z"),
            )
        }

        @Test
        fun `cron - weekdays by name - Friday is followed by Monday`() {
            val weekdays = Schedule.cron("0 9 * * mon-FRI", ZoneOffset.UTC)

            val next = weekdays.next(at("2026-10-30T09:00:00Z"))

            assertThat(next).isEqualTo(at("2026-11-02T09:00:00Z"))
        }

        @Test
        fun `cron - Sunday as 7 - the same fire times as Sunday as 0`() {
            val asSeven = Schedule.cron("0 0 * * 7", ZoneOffset.UTC)
            val asZero = Schedule.cron("0 0 * * 0", ZoneOffset.UTC)

            val next = asSeven.next(at("2026-10-29T00:00:00Z"))

            // Assert
            assertThat(next).isEqualTo(at("2026-11-01T00:00:00Z"))
            assertThat(asZero.next(at("2026-10-29T00:00:00Z"))).isEqualTo(next)
        }

        @Test
        fun `cron - months by name - first of January and of July`() {
            val halfYearly = Schedule.cron("0 0 1 JAN,jul *", ZoneOffset.UTC)

            val fireTimes = fireTimes(halfYearly, at("2026-10-29T00:00:00Z"), 2)

            assertThat(fireTimes).containsExactly(at("2027-01-01T00:00:00Z"), at("2027-07-01T00:00:00Z"))
        }

        @Test
        fun `cron - day of month and day of week both restricted - either one matching fires`() {
            val tenthOrFriday = Schedule.cron("0 0 10 * FRI", ZoneOffset.UTC)

            val fireTimes = fireTimes(tenthOrFriday, at("2026-11-06T00:00:00Z"), 2)

            // Tuesday the 10th, then Friday the 13th
            assertThat(fireTimes).containsExactly(at("2026-11-10T00:00:00Z"), at("2026-11-13T00:00:00Z"))
        }

        @Test
        fun `cron - day of week restricted, day of month a step from star - both must match`() {
            val earlyMondays = Schedule.cron("0 0 */10 * MON", ZoneOffset.UTC)

            val next = earlyMondays.next(at("2026-10-29T00:00:00Z"))

            // The days 1, 11, 21 and 31 that are Mondays: 21 December 2026
            assertThat(next).isEqualTo(at("2026-12-21T00:00:00Z"))
        }

        @Test
        fun `cron - 29 February - the next leap year`() {
            val leapDay = Schedule.cron("0 0 29 2 *", ZoneOffset.UTC)

            val next = leapDay.next(at("2026-10-29T00:00:00Z"))

            assertThat(next).isEqualTo(at("2028-02-29T00:00:00Z"))
        }
    }

    @Nested
    inner class DaylightSaving {
        private val hourly = Schedule.cron("30 * * * *", berlin)

        @Test
        fun `cron - hourly across the spring change - the skipped hour coincides with the next one`() {
            val fireTimes = fireTimes(hourly, at("2026-03-29T00:00:00Z"), 3)

            // 01:30 CET, 03:30 CEST (02:30 does not exist), 04:30 CEST
            assertThat(fireTimes).containsExactly(
                at("2026-03-29T00:30:00Z"),
                at("2026-03-29T01:30:00Z"),
                at("2026-03-29T02:30:00Z"),
            )
        }

        @Test
        fun `cron - hourly across the autumn change - the repeated hour fires once`() {
            val fireTimes = fireTimes(hourly, at("2026-10-24T23:00:00Z"), 3)

            // 01:30 CEST, 02:30 CEST, 03:30 CET; 02:30 CET is the same local time again
            assertThat(fireTimes).containsExactly(
                at("2026-10-24T23:30:00Z"),
                at("2026-10-25T00:30:00Z"),
                at("2026-10-25T02:30:00Z"),
            )
        }

        @Test
        fun `cron - after lies in the second pass of the repeated hour - the next fire time after it`() {
            // 02:10 CET, after the clocks went back
            val next = hourly.next(at("2026-10-25T01:10:00Z"))

            assertThat(next).isEqualTo(at("2026-10-25T02:30:00Z"))
        }
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "0 3 * *",
            "0 3 * * * *",
            "60 * * * *",
            "* 24 * * *",
            "0 0 0 * *",
            "0 0 * 13 *",
            "0 0 * * 8",
            "*/0 * * * *",
            "5-1 * * * *",
            "1,,2 * * * *",
            "0 0 * FOO *",
            "? * * * *",
            "0 0 30 2 *",
            "0 0 31 4,6 *",
        ],
    )
    fun `cron - invalid or impossible expression - IllegalArgumentException naming the expression`(expression: String) {
        assertThatThrownBy { Schedule.cron(expression, ZoneOffset.UTC) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("'$expression'")
    }

    private fun at(text: String) = Instant.parse(text)

    private fun fireTimes(
        schedule: Schedule,
        after: Instant,
        count: Int,
    ): List<Instant> = generateSequence(schedule.next(after)) { schedule.next(it) }.take(count).toList()
}
