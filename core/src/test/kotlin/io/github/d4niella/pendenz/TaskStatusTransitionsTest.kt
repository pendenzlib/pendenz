package io.github.d4niella.pendenz

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

class TaskStatusTransitionsTest {
    @ParameterizedTest
    @EnumSource(TaskStatus::class, names = ["SUCCEEDED", "CANCELLED"])
    fun `final states have no outgoing transitions`(status: TaskStatus) {
        assertThat(status.isFinal).isTrue()
        assertThat(TaskStatusTransitions.targetsFrom(status)).isEmpty()
    }

    @ParameterizedTest
    @EnumSource(TaskStatus::class, mode = EnumSource.Mode.EXCLUDE, names = ["SUCCEEDED", "CANCELLED"])
    fun `every non-final state can be cancelled`(status: TaskStatus) {
        assertThat(TaskStatusTransitions.isAllowed(status, TaskStatus.CANCELLED)).isTrue()
    }

    @Test
    fun `only pending tasks can be claimed`() {
        val claimable = TaskStatus.entries.filter { TaskStatusTransitions.isAllowed(it, TaskStatus.RUNNING) }
        assertThat(claimable).containsExactly(TaskStatus.PENDING)
    }

    @Test
    fun `a running task ends in exactly the outcomes a handler can produce`() {
        assertThat(TaskStatusTransitions.targetsFrom(TaskStatus.RUNNING))
            .containsExactlyInAnyOrder(
                TaskStatus.SUCCEEDED,
                TaskStatus.RETRY_PENDING,
                TaskStatus.FAILED,
                TaskStatus.REJECTED,
                // lock expired, worker presumed dead
                TaskStatus.PENDING,
                TaskStatus.CANCELLED,
            )
    }

    @ParameterizedTest
    @EnumSource(TaskStatus::class, names = ["FAILED", "REJECTED", "QUARANTINED"])
    fun `states waiting for a human can be restarted or completed`(status: TaskStatus) {
        assertThat(status.kind).isEqualTo(TaskStatus.Kind.WAITING_FOR_HUMAN)
        assertThat(TaskStatusTransitions.isAllowed(status, TaskStatus.PENDING)).isTrue()
        assertThat(TaskStatusTransitions.isAllowed(status, TaskStatus.SUCCEEDED)).isTrue()
    }

    @Test
    fun `no state transitions to itself`() {
        TaskStatus.entries.forEach { status ->
            assertThat(TaskStatusTransitions.isAllowed(status, status))
                .describedAs("%s -> %s", status, status)
                .isFalse()
        }
    }
}
