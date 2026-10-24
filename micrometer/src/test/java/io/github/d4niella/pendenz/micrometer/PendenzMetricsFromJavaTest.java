package io.github.d4niella.pendenz.micrometer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.d4niella.pendenz.PayloadCodec;
import io.github.d4niella.pendenz.TaskType;
import io.github.d4niella.pendenz.javaapi.PendenzJava;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import org.junit.jupiter.api.Test;

/** The metrics registered from Java, through the facade's builder and an executor. */
class PendenzMetricsFromJavaTest {

    record Greeting(String name) {}

    static final PayloadCodec<Greeting> CODEC = new PayloadCodec<>() {
        @Override
        public String encode(Greeting payload) {
            return "{\"name\":\"" + payload.name() + "\"}";
        }

        @Override
        public Greeting decode(String text) {
            return new Greeting(text.replaceAll(".*\"name\":\"([^\"]*)\".*", "$1"));
        }
    };

    static final TaskType<Greeting> GREET = new TaskType<>("greet", CODEC);

    @Test
    void listener_registeredOnTheJavaBuilder_countsASucceededRun() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PendenzMetrics<Long> metrics = new PendenzMetrics<>(registry, Tags.of("queue", "greetings"));
        try (PendenzJava<Long> pendenz = PendenzJava.builder()
                .inMemory()
                .listener(metrics)
                .register(GREET, (ctx, p) -> {})
                .build()) {
            // Act
            pendenz.enqueue(GREET, new Greeting("Ada"));
            pendenz.runPending();

            // Assert
            long succeeded = registry.find(PendenzMetrics.EXECUTION).tags("outcome", "succeeded").timer().count();
            assertThat(succeeded).isEqualTo(1L);
        }
    }

    @Test
    void watchBacklog_onAnExecutor_reportsThePendingTask() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PendenzMetrics<Long> metrics = new PendenzMetrics<>(registry);
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        try (PendenzJava<Long> pendenz = PendenzJava.builder()
                .inMemory()
                .listener(metrics)
                .register(GREET, (ctx, p) -> {})
                .build()) {
            // Arrange
            pendenz.enqueue(GREET, new Greeting("Grace"));

            // Act
            ScheduledFuture<?> watch = metrics.watchBacklog(pendenz.asKotlin(), executor, Duration.ofMillis(20));

            // Assert
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(
                            registry.find(PendenzMetrics.BACKLOG).tags("status", "PENDING").gauge().value())
                    .isEqualTo(1.0));
            watch.cancel(false);
        } finally {
            executor.shutdownNow();
        }
    }
}
