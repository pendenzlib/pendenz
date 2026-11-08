package io.github.pendenzlib.javaapi;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pendenzlib.Approval;
import io.github.pendenzlib.NoRetry;
import io.github.pendenzlib.Retention;
import io.github.pendenzlib.TaskRecord;
import io.github.pendenzlib.TaskResource;
import io.github.pendenzlib.TaskStatus;
import io.github.pendenzlib.TaskType;
import io.github.pendenzlib.javaapi.GreetingCodec.Greeting;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A task type built in Java with an execution timeout of its own (ADR 0039). */
class ExecutionTimeoutFromJavaTest {

    static final TaskType<Greeting> QUICK = new TaskType<>(
            "greet-quickly",
            GreetingCodec.INSTANCE,
            NoRetry.INSTANCE,
            1,
            Map.of(),
            Retention.keep(),
            TaskResource.none(),
            1,
            Approval.NEVER,
            null,
            Duration.ofMillis(100));

    @Test
    void executionTimeout_ofTheTypeShorterThanTheWorkers_blockingHandlerInterruptedAndFailed() {
        try (PendenzJava<Long> pendenz = PendenzJava.builder()
                .inMemory()
                .register(QUICK, (ctx, p) -> Thread.sleep(5_000))
                .build()) {
            Long id = pendenz.enqueue(QUICK, new Greeting("Ada"));

            // Act
            pendenz.runPending();

            // Assert
            TaskRecord<Long> task = pendenz.find(id).orElseThrow();
            assertThat(task.getStatus()).isEqualTo(TaskStatus.FAILED);
            assertThat(task.getLastError()).contains("Timed out");
        }
    }
}
