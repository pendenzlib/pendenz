package io.github.d4niella.pendenz.javaapi;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.d4niella.pendenz.NoRetry;
import io.github.d4niella.pendenz.ResourceLimit;
import io.github.d4niella.pendenz.TaskStatus;
import io.github.d4niella.pendenz.TaskType;
import io.github.d4niella.pendenz.javaapi.GreetingCodec.Greeting;
import org.junit.jupiter.api.Test;

/** What an operator does to tasks and resources, from Java (ADR 0018). */
class OperatorsFromJavaTest {

    static final TaskType<Greeting> GREET =
            new TaskType<>("greet", GreetingCodec.INSTANCE, NoRetry.INSTANCE);

    @Test
    void restart_failedTask_runsAgainAndSucceeds() {
        // Arrange: Mallory fails the first time only
        boolean[] failed = {false};
        try (PendenzJava<Long> pendenz = PendenzJava.builder().inMemory().register(GREET, (ctx, p) -> {
                    if (!failed[0]) {
                        failed[0] = true;
                        throw new IllegalStateException("mail server down");
                    }
                }).build()) {
            Long id = pendenz.enqueue(GREET, new Greeting("Mallory"));
            pendenz.runPending();

            // Act
            boolean restarted = pendenz.restart(id, "ops");

            // Assert
            assertThat(restarted).isTrue();
            pendenz.runPending();
            assertThat(pendenz.find(id).orElseThrow().getStatus()).isEqualTo(TaskStatus.SUCCEEDED);
        }
    }

    @Test
    void quarantineThenComplete_pendingTask_endsSucceededWithoutRunning() {
        try (PendenzJava<Long> pendenz = PendenzJava.builder().inMemory().register(GREET, (ctx, p) -> {
                    throw new AssertionError("must not run");
                }).build()) {
            Long id = pendenz.enqueue(GREET, new Greeting("Ada"));
            pendenz.quarantine(id, "ticket #123");

            // Act
            boolean completed = pendenz.complete(id);

            // Assert
            assertThat(completed).isTrue();
            assertThat(pendenz.find(id).orElseThrow().getStatus()).isEqualTo(TaskStatus.SUCCEEDED);
        }
    }

    @Test
    void cancel_pendingTask_isCancelledAndNeverRuns() {
        try (PendenzJava<Long> pendenz = PendenzJava.builder().inMemory().register(GREET, (ctx, p) -> {
                    throw new AssertionError("must not run");
                }).build()) {
            Long id = pendenz.enqueue(GREET, new Greeting("Bob"));

            // Act
            boolean cancelled = pendenz.cancel(id);

            // Assert
            assertThat(cancelled).isTrue();
            assertThat(pendenz.runPending()).isZero();
            assertThat(pendenz.find(id).orElseThrow().getStatus()).isEqualTo(TaskStatus.CANCELLED);
        }
    }

    @Test
    void limitResource_setThenRemoved_limitsListTheChange() {
        try (PendenzJava<Long> pendenz = PendenzJava.builder().inMemory().register(GREET, (ctx, p) -> {}).build()) {
            pendenz.limitResource("mail-server", 2);

            // Act
            boolean removed = pendenz.removeResourceLimit("mail-server");

            // Assert
            assertThat(removed).isTrue();
            assertThat(pendenz.resourceLimits()).doesNotContain(new ResourceLimit("mail-server", 2));
        }
    }
}
