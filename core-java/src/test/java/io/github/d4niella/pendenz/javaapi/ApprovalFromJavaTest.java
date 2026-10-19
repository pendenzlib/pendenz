package io.github.d4niella.pendenz.javaapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.d4niella.pendenz.Action;
import io.github.d4niella.pendenz.Approval;
import io.github.d4niella.pendenz.ExponentialBackoff;
import io.github.d4niella.pendenz.NotPermittedException;
import io.github.d4niella.pendenz.Permissions;
import io.github.d4niella.pendenz.Retention;
import io.github.d4niella.pendenz.TaskResource;
import io.github.d4niella.pendenz.TaskStatus;
import io.github.d4niella.pendenz.TaskType;
import io.github.d4niella.pendenz.javaapi.GreetingCodec.Greeting;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Approval rules and a Permissions implementation written in Java. */
class ApprovalFromJavaTest {

    /** A type that needs approval, built with the full constructor as a Java team would. */
    static final TaskType<Greeting> VETTED = new TaskType<>(
            "vetted",
            GreetingCodec.INSTANCE,
            new ExponentialBackoff(),
            1,
            Map.of(),
            Retention.keep(),
            TaskResource.none(),
            1,
            Approval.rule((createdBy, payload) -> payload == null || payload.name().startsWith("M")));

    /** Permissions from the application's own idea of roles: one lambda, three arguments. */
    static final Permissions MANAGERS_APPROVE = (actor, action, taskType) ->
            action != Action.APPROVE || Set.of("manager").contains(actor);

    @Test
    void approve_ruleHoldsTheTaskAndAManagerApproves_runsAfterwards() {
        try (PendenzJava<Long> pendenz = PendenzJava.builder()
                .inMemory()
                .permissions(MANAGERS_APPROVE)
                .register(VETTED, (ctx, p) -> {})
                .build()) {
            Long id = pendenz.enqueue(VETTED, new Greeting("Mallory"), EnqueueOptions.<Long>none().createdBy("clerk"));
            assertThat(pendenz.find(id).orElseThrow().getStatus()).isEqualTo(TaskStatus.AWAITING_APPROVAL);

            // Act
            boolean approved = pendenz.approve(id, "manager");

            // Assert
            assertThat(approved).isTrue();
            assertThat(pendenz.runPending()).isEqualTo(1);
            assertThat(pendenz.find(id).orElseThrow().getApprovedBy()).isEqualTo("manager");
        }
    }

    @Test
    void approve_actorWithoutThePermission_throwsNotPermittedException() {
        try (PendenzJava<Long> pendenz = PendenzJava.builder()
                .inMemory()
                .permissions(MANAGERS_APPROVE)
                .register(VETTED, (ctx, p) -> {})
                .build()) {
            Long id = pendenz.enqueue(VETTED, new Greeting("Mallory"), EnqueueOptions.<Long>none().createdBy("clerk"));

            assertThatThrownBy(() -> pendenz.approve(id, "intern")).isInstanceOf(NotPermittedException.class);
        }
    }
}
