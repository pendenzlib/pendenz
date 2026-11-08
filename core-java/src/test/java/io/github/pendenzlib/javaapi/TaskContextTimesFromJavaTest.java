package io.github.pendenzlib.javaapi;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pendenzlib.TaskType;
import io.github.pendenzlib.javaapi.GreetingCodec.Greeting;
import io.github.pendenzlib.testing.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** A blocking Java handler reads the creation time and the run's deadline (ADR 0042). */
class TaskContextTimesFromJavaTest {

    final Instant enqueuedAt = Instant.parse("2026-11-08T09:00:00Z");
    final MutableClock clock = new MutableClock(enqueuedAt);

    @Test
    void handler_runAnHourAfterTheEnqueue_seesCreationTimeAndDeadlineOfItsRun() {
        TaskType<Greeting> greet = TaskType.builder("greet", GreetingCodec.INSTANCE)
                .executionTimeout(Duration.ofSeconds(30))
                .build();
        AtomicReference<Instant> createdAt = new AtomicReference<>();
        AtomicReference<Instant> deadline = new AtomicReference<>();
        try (PendenzJava<Long> pendenz = PendenzJava.builder()
                .inMemory()
                .clock(clock)
                .register(greet, (ctx, p) -> {
                    createdAt.set(ctx.getCreatedAt());
                    deadline.set(ctx.getDeadline());
                })
                .build()) {
            pendenz.enqueue(greet, new Greeting("Ada"));
            clock.advance(Duration.ofHours(1));

            // Act
            pendenz.runPending();

            // Assert
            assertThat(createdAt.get()).isEqualTo(enqueuedAt);
            assertThat(deadline.get()).isEqualTo(enqueuedAt.plus(Duration.ofHours(1)).plusSeconds(30));
        }
    }
}
