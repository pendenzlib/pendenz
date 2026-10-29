package io.github.pendenzlib.javaapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pendenzlib.Action;
import io.github.pendenzlib.Approval;
import io.github.pendenzlib.ExponentialBackoff;
import io.github.pendenzlib.NotPermittedException;
import io.github.pendenzlib.PermissionTable;
import io.github.pendenzlib.Permissions;
import io.github.pendenzlib.Retention;
import io.github.pendenzlib.TaskRecord;
import io.github.pendenzlib.TaskResource;
import io.github.pendenzlib.TaskStatus;
import io.github.pendenzlib.TaskType;
import io.github.pendenzlib.javaapi.GreetingCodec.Greeting;
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
    void enqueue_ruleFromJavaHolds_storesTheTaskAsAwaitingApproval() {
        try (PendenzJava<Long> pendenz = PendenzJava.builder()
                .inMemory()
                .permissions(MANAGERS_APPROVE)
                .register(VETTED, (ctx, p) -> {})
                .build()) {
            Long id = pendenz.enqueue(VETTED, new Greeting("Mallory"), EnqueueOptions.<Long>defaults().createdBy("clerk"));

            assertThat(pendenz.find(id).orElseThrow().getStatus()).isEqualTo(TaskStatus.AWAITING_APPROVAL);
        }
    }

    @Test
    void approve_heldTaskAndAManager_becomesPendingWithTheApprover() {
        // Arrange
        try (PendenzJava<Long> pendenz = PendenzJava.builder()
                .inMemory()
                .permissions(MANAGERS_APPROVE)
                .register(VETTED, (ctx, p) -> {})
                .build()) {
            Long id = pendenz.enqueue(VETTED, new Greeting("Mallory"), EnqueueOptions.<Long>defaults().createdBy("clerk"));

            boolean approved = pendenz.approve(id, "manager");

            // Assert
            assertThat(approved).isTrue();
            TaskRecord<Long> task = pendenz.find(id).orElseThrow();
            assertThat(task.getStatus()).isEqualTo(TaskStatus.PENDING);
            assertThat(task.getApprovedBy()).isEqualTo("manager");
        }
    }

    @Test
    void approve_tableBuilderWithoutTheGrant_throwsNotPermittedException() {
        // Arrange
        PermissionTable.Builder grants = Permissions.tableBuilder();
        grants.actor("clerk").may(Action.ENQUEUE);
        grants.actor("manager").may(Action.ENQUEUE).may(Action.APPROVE, "vetted");
        try (PendenzJava<Long> pendenz = PendenzJava.builder()
                .inMemory()
                .permissions(grants.build())
                .register(VETTED, (ctx, p) -> {})
                .build()) {
            Long id = pendenz.enqueue(VETTED, new Greeting("Mallory"), EnqueueOptions.<Long>defaults().createdBy("clerk"));

            assertThatThrownBy(() -> pendenz.approve(id, "clerk")).isInstanceOf(NotPermittedException.class);
        }
    }

    @Test
    void approve_actorWithoutThePermission_throwsNotPermittedException() {
        try (PendenzJava<Long> pendenz = PendenzJava.builder()
                .inMemory()
                .permissions(MANAGERS_APPROVE)
                .register(VETTED, (ctx, p) -> {})
                .build()) {
            Long id = pendenz.enqueue(VETTED, new Greeting("Mallory"), EnqueueOptions.<Long>defaults().createdBy("clerk"));

            assertThatThrownBy(() -> pendenz.approve(id, "intern")).isInstanceOf(NotPermittedException.class);
        }
    }
}
