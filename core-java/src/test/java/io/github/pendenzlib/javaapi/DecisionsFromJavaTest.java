package io.github.pendenzlib.javaapi;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pendenzlib.HandOverException;
import io.github.pendenzlib.NoRetry;
import io.github.pendenzlib.ResourceBlock;
import io.github.pendenzlib.ResourceUnavailableException;
import io.github.pendenzlib.RetryLaterException;
import io.github.pendenzlib.TaskRecord;
import io.github.pendenzlib.TaskStatus;
import io.github.pendenzlib.TaskType;
import io.github.pendenzlib.javaapi.GreetingCodec.Greeting;
import io.github.pendenzlib.testing.MutableClock;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Decisions a blocking Java handler reports by throwing, and the resource blocks of the facade (ADR 0035). */
class DecisionsFromJavaTest {

    static final TaskType<Greeting> GREET = new TaskType<>("greet", GreetingCodec.INSTANCE, NoRetry.INSTANCE);

    final MutableClock clock = new MutableClock(Instant.parse("2026-11-04T09:00:00Z"));

    final Instant inAnHour = Instant.parse("2026-11-04T10:00:00Z");

    @Test
    void retryLater_thrownByAJavaHandler_deferredWithoutCountingTheAttempt() {
        try (PendenzJava<Long> pendenz = PendenzJava.builder().inMemory().clock(clock).register(GREET, (ctx, p) -> {
                    throw new RetryLaterException(inAnHour, "mail server in maintenance");
                }).build()) {
            Long id = pendenz.enqueue(GREET, new Greeting("Ada"));

            // Act
            pendenz.runPending();

            // Assert
            TaskRecord<Long> task = pendenz.find(id).orElseThrow();
            assertThat(task.getStatus()).isEqualTo(TaskStatus.RETRY_PENDING);
            assertThat(task.getRunAt()).isEqualTo(inAnHour);
            assertThat(task.getAttempts()).isZero();
        }
    }

    @Test
    void resourceUnavailable_namingAResource_blocksThatResource() {
        try (PendenzJava<Long> pendenz = PendenzJava.builder().inMemory().clock(clock).register(GREET, (ctx, p) -> {
                    throw new ResourceUnavailableException(inAnHour, "quota used up", "mail-server");
                }).build()) {
            pendenz.enqueue(GREET, new Greeting("Ada"));

            // Act
            pendenz.runPending();

            // Assert
            List<ResourceBlock> blocks = pendenz.resourceBlocks();
            assertThat(blocks).extracting(ResourceBlock::getName).containsExactly("mail-server");
            assertThat(blocks).extracting(ResourceBlock::getBlockedUntil).containsExactly(inAnHour);
        }
    }

    @Test
    void handOver_thrownByAJavaHandler_quarantinedWithTheReason() {
        try (PendenzJava<Long> pendenz = PendenzJava.builder().inMemory().clock(clock).register(GREET, (ctx, p) -> {
                    throw new HandOverException("no such recipient");
                }).build()) {
            Long id = pendenz.enqueue(GREET, new Greeting("Ada"));

            // Act
            pendenz.runPending();

            // Assert
            TaskRecord<Long> task = pendenz.find(id).orElseThrow();
            assertThat(task.getStatus()).isEqualTo(TaskStatus.QUARANTINED);
            assertThat(task.getLastError()).isEqualTo("no such recipient");
        }
    }

    @Test
    void blockResource_thenUnblock_theResourcesTaskRunsAtTheNextPoll() {
        try (PendenzJava<Long> pendenz = PendenzJava.builder().inMemory().clock(clock).register(GREET, (ctx, p) -> {
                }).build()) {
            pendenz.blockResource("mail-server", inAnHour, "maintenance window");
            Long id = pendenz.enqueue(GREET, new Greeting("Ada"), EnqueueOptions.<Long>defaults().resource("mail-server"));
            pendenz.runPending();
            pendenz.unblockResource("mail-server");

            // Act
            pendenz.runPending();

            // Assert
            assertThat(pendenz.find(id).orElseThrow().getStatus()).isEqualTo(TaskStatus.SUCCEEDED);
        }
    }
}
