package io.github.d4niella.pendenz.javaapi;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.d4niella.pendenz.TaskStatus;
import io.github.d4niella.pendenz.TaskStatusTransitions;
import org.junit.jupiter.api.Test;

/**
 * Written in Java on purpose: these tests fail to compile if the Kotlin API stops
 * being pleasant to call from Java.
 */
class TaskStatusFromJavaTest {

    @Test
    void transitionsAreStaticallyCallable() {
        assertThat(TaskStatusTransitions.isAllowed(TaskStatus.PENDING, TaskStatus.RUNNING)).isTrue();
        assertThat(TaskStatusTransitions.targetsFrom(TaskStatus.SUCCEEDED)).isEmpty();
    }

    @Test
    void kindIsAPlainProperty() {
        assertThat(TaskStatus.FAILED.getKind()).isEqualTo(TaskStatus.Kind.WAITING_FOR_HUMAN);
        assertThat(TaskStatus.CANCELLED.isFinal()).isTrue();
    }
}
