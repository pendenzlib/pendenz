package io.github.pendenzlib.javaapi;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pendenzlib.Approval;
import io.github.pendenzlib.NoRetry;
import io.github.pendenzlib.TaskRecord;
import io.github.pendenzlib.TaskResource;
import io.github.pendenzlib.TaskStatus;
import io.github.pendenzlib.TaskType;
import io.github.pendenzlib.javaapi.GreetingCodec.Greeting;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Task types built from Java with the builder, setting only what they need (ADR 0041). */
class TaskTypeBuilderFromJavaTest {

    @Test
    void builder_approvalAndPreviewOnly_taskWaitsForApprovalWithItsPreview() {
        TaskType<Greeting> vetted = TaskType.builder("vetted", GreetingCodec.INSTANCE)
                .approval(Approval.ALWAYS)
                .preview(greeting -> "greets " + greeting.name())
                .build();
        try (PendenzJava<Long> pendenz =
                PendenzJava.builder().inMemory().register(vetted, (ctx, p) -> {}).build()) {
            // Act
            Long id = pendenz.enqueue(vetted, new Greeting("Ada"));

            // Assert
            TaskRecord<Long> task = pendenz.find(id).orElseThrow();
            assertThat(task.getStatus()).isEqualTo(TaskStatus.AWAITING_APPROVAL);
            assertThat(task.getPreview()).isEqualTo("greets Ada");
        }
    }

    @Test
    void builder_executionTimeoutAndNoRetry_blockingHandlerFailsAfterTheTypesTimeout() {
        TaskType<Greeting> quick = TaskType.builder("greet-quickly", GreetingCodec.INSTANCE)
                .retryPolicy(NoRetry.INSTANCE)
                .resource(TaskResource.fixed("mail-server"))
                .executionTimeout(Duration.ofMillis(100))
                .build();
        try (PendenzJava<Long> pendenz = PendenzJava.builder()
                .inMemory()
                .register(quick, (ctx, p) -> Thread.sleep(5_000))
                .build()) {
            Long id = pendenz.enqueue(quick, new Greeting("Bob"));

            // Act
            pendenz.runPending();

            // Assert
            TaskRecord<Long> task = pendenz.find(id).orElseThrow();
            assertThat(task.getStatus()).isEqualTo(TaskStatus.FAILED);
            assertThat(task.getResource()).isEqualTo("mail-server");
        }
    }
}
