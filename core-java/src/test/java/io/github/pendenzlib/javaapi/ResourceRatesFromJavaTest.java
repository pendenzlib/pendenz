package io.github.pendenzlib.javaapi;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pendenzlib.NoRetry;
import io.github.pendenzlib.ResourceRate;
import io.github.pendenzlib.Retention;
import io.github.pendenzlib.TaskResource;
import io.github.pendenzlib.TaskType;
import io.github.pendenzlib.javaapi.GreetingCodec.Greeting;
import io.github.pendenzlib.testing.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Rate limits from Java: the facade's operations and a rate the task type seeds (ADR 0040). */
class ResourceRatesFromJavaTest {

    static final TaskType<Greeting> GREET = new TaskType<>(
            "greet",
            GreetingCodec.INSTANCE,
            NoRetry.INSTANCE,
            1,
            Map.of(),
            Retention.keep(),
            TaskResource.fixed("mail-server", null, 2, Duration.ofMinutes(1)));

    final MutableClock clock = new MutableClock(Instant.parse("2026-11-08T09:00:00Z"));

    @Test
    void resourceRate_seededByTheTaskType_twoOfFiveRunWithinTheMinute() {
        try (PendenzJava<Long> pendenz = PendenzJava.builder()
                .inMemory()
                .clock(clock)
                .register(GREET, (ctx, p) -> {})
                .build()) {
            for (String name : new String[] {"Ada", "Bob", "Cleo", "Dan", "Eve"}) {
                pendenz.enqueue(GREET, new Greeting(name));
            }

            // Act
            int ran = pendenz.runPending();

            // Assert
            assertThat(ran).isEqualTo(2);
            assertThat(pendenz.resourceRates()).containsExactly(new ResourceRate("mail-server", 2, Duration.ofMinutes(1)));
        }
    }

    @Test
    void setResourceRate_thenRemoved_noLongerListed() {
        try (PendenzJava<Long> pendenz = PendenzJava.builder().inMemory().build()) {
            pendenz.setResourceRate("partner-api", 100, Duration.ofMinutes(1));

            // Act
            boolean removed = pendenz.removeResourceRate("partner-api");

            // Assert
            assertThat(removed).isTrue();
            assertThat(pendenz.resourceRates()).isEmpty();
        }
    }
}
