package io.github.pendenzlib.javaapi;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pendenzlib.FailedAttempt;
import io.github.pendenzlib.RetryPolicy;
import io.github.pendenzlib.TaskContext;
import io.github.pendenzlib.TaskStatus;
import io.github.pendenzlib.TaskType;
import io.github.pendenzlib.javaapi.GreetingCodec.Greeting;
import io.github.pendenzlib.testing.MutableClock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** A retry policy written in Java reads the failed task's context (ADR 0038). */
class RetryPolicyFromJavaTest {

    final MutableClock clock = new MutableClock(Instant.parse("2026-11-08T09:00:00Z"));

    static final RetryPolicy BY_RESOURCE = failure -> "mail-server".equals(failure.getTask().getResource())
            ? failure.getFailedAt().plus(Duration.ofMinutes(10))
            : null;

    @Test
    void retryPolicy_lambdaReadingTheResource_retriesOnlyTasksOfThatResource() {
        TaskType<Greeting> greet = new TaskType<>("greet", GreetingCodec.INSTANCE, BY_RESOURCE);
        try (PendenzJava<Long> pendenz = PendenzJava.builder()
                .inMemory()
                .clock(clock)
                .register(greet, (ctx, p) -> {
                    throw new IllegalStateException("mail server down");
                })
                .build()) {
            // Arrange
            Long mailed = pendenz.enqueue(
                    greet, new Greeting("Ada"), EnqueueOptions.<Long>defaults().resource("mail-server"));
            Long other = pendenz.enqueue(greet, new Greeting("Bob"));

            // Act
            pendenz.runPending();

            // Assert
            assertThat(pendenz.find(mailed).orElseThrow().getRunAt()).isEqualTo(clock.getNow().plus(Duration.ofMinutes(10)));
            assertThat(pendenz.find(other).orElseThrow().getLastError()).contains("mail server down");
            assertThat(pendenz.find(other).orElseThrow().getStatus()).isEqualTo(TaskStatus.FAILED);
        }
    }

    @Test
    void retryPolicy_calledFromAJavaTest_answersForAFailedAttemptBuiltInTheTest() {
        Instant failedAt = clock.getNow();
        TaskContext<Long> task =
                new TaskContext<>(1L, "greet", 1, null, null, 0, "mail-server", null, null, failedAt, failedAt);

        Instant next = BY_RESOURCE.nextAttemptAt(new FailedAttempt(task, failedAt, new IllegalStateException("down")));

        assertThat(next).isEqualTo(failedAt.plus(Duration.ofMinutes(10)));
    }
}
