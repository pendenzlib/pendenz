package io.github.pendenzlib.javaapi;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pendenzlib.Schedule;
import io.github.pendenzlib.TaskRecord;
import io.github.pendenzlib.TaskStatus;
import io.github.pendenzlib.TaskType;
import io.github.pendenzlib.javaapi.GreetingCodec.Greeting;
import io.github.pendenzlib.testing.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/** Recurring tasks from Java: the schedule factories and the builder method (ADR 0033). */
class SchedulesFromJavaTest {

    static final TaskType<Greeting> MORNING_GREETING = new TaskType<>("morning-greeting", GreetingCodec.INSTANCE);

    @Test
    void schedule_fireTimeReached_occurrenceRunsAndTheNextIsScheduled() {
        // Arrange: Monday 07:00 in Berlin, summer time; the first fire time is 07:30
        MutableClock clock = new MutableClock(Instant.parse("2026-10-19T05:00:00Z"));
        List<String> greeted = new CopyOnWriteArrayList<>();
        try (PendenzJava<Long> pendenz = PendenzJava.builder()
                .inMemory()
                .clock(clock)
                .register(MORNING_GREETING, (ctx, greeting) -> greeted.add(greeting.name()))
                .schedule("morning", MORNING_GREETING, new Greeting("Ada"),
                        Schedule.cron("30 7 * * MON-FRI", ZoneId.of("Europe/Berlin")))
                .build()) {
            pendenz.runPending();
            clock.advance(Duration.ofMinutes(90));

            // Act
            pendenz.runPending();

            // Assert
            List<TaskRecord<Long>> occurrences = pendenz.tasks();
            assertThat(greeted).containsExactly("Ada");
            assertThat(occurrences).extracting(TaskRecord::getStatus)
                    .containsExactlyInAnyOrder(TaskStatus.SUCCEEDED, TaskStatus.SCHEDULED);
            assertThat(occurrences).extracting(TaskRecord::getRunAt)
                    .contains(Instant.parse("2026-10-20T05:30:00Z"));
        }
    }

    @Test
    void every_calledFromJava_nextQuarterHour() {
        Instant next = Schedule.every(Duration.ofMinutes(15)).next(Instant.parse("2026-10-19T05:07:00Z"));

        assertThat(next).isEqualTo(Instant.parse("2026-10-19T05:15:00Z"));
    }

    @Test
    void daily_calledFromJava_todayAtTheLocalTime() {
        Schedule atEight = Schedule.daily(LocalTime.of(8, 0), ZoneId.of("Europe/Berlin"));

        Instant next = atEight.next(Instant.parse("2026-10-19T05:00:00Z"));

        assertThat(next).isEqualTo(Instant.parse("2026-10-19T06:00:00Z"));
    }
}
