package io.github.pendenzlib.javaapi;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pendenzlib.Retention;
import io.github.pendenzlib.TaskType;
import io.github.pendenzlib.javaapi.GreetingCodec.Greeting;
import io.github.pendenzlib.testing.MutableClock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** A retention rule per final status, set from Java (ADR 0045). */
class RetentionByStatusFromJavaTest {

    final MutableClock clock = new MutableClock(Instant.parse("2026-11-08T09:00:00Z"));

    @Test
    void retention_byStatusDeletingSuccessesKeepingCancellations_removesOnlyTheSuccess() {
        TaskType<Greeting> greet = TaskType.builder("greet", GreetingCodec.INSTANCE)
                .retention(Retention.byStatus(Retention.delete(Duration.ofDays(1)), Retention.keep()))
                .build();
        try (PendenzJava<Long> pendenz = PendenzJava.builder()
                .inMemory()
                .clock(clock)
                .register(greet, (ctx, p) -> {})
                .build()) {
            // Arrange
            Long greeted = pendenz.enqueue(greet, new Greeting("Ada"));
            pendenz.runPending();
            Long cancelled = pendenz.enqueue(greet, new Greeting("Bob"));
            pendenz.cancel(cancelled, "ops@example.com");
            clock.advance(Duration.ofDays(2));

            // Act
            pendenz.runMaintenance();

            // Assert
            assertThat(pendenz.find(greeted)).isEmpty();
            assertThat(pendenz.find(cancelled)).isPresent();
        }
    }
}
