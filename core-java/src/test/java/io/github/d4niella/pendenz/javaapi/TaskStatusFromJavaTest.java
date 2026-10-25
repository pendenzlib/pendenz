package io.github.d4niella.pendenz.javaapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.d4niella.pendenz.TaskStatus;
import io.github.d4niella.pendenz.TaskStatusTransitions;
import org.junit.jupiter.api.Test;

/**
 * Written in Java on purpose: these tests fail to compile if the Kotlin API stops
 * being pleasant to call from Java.
 */
class TaskStatusFromJavaTest {

    @Test
    void transitions_pendingTask_mayBecomeRunningWithoutInstanceAccess() {
        assertThat(TaskStatusTransitions.isAllowed(TaskStatus.PENDING, TaskStatus.RUNNING)).isTrue();
    }

    @Test
    void transitions_succeededTask_hasNoTargets() {
        assertThat(TaskStatusTransitions.targetsFrom(TaskStatus.SUCCEEDED)).isEmpty();
    }

    @Test
    void status_failedTask_isWaitingForAHumanAndNotFinal() {
        assertThat(TaskStatus.FAILED.getKind()).isEqualTo(TaskStatus.Kind.WAITING_FOR_HUMAN);
        assertThat(TaskStatus.FAILED.isFinal()).isFalse();
    }

    @Test
    void restartable_changedFromJava_throwsUnsupportedOperationException() {
        Throwable thrown = catchThrowable(() -> TaskStatusTransitions.restartable.add(TaskStatus.SUCCEEDED));

        assertThat(thrown).isInstanceOf(UnsupportedOperationException.class);
    }
}
