package io.github.d4niella.pendenz

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

class TaskStatusTransitionsTest {
    @Test
    fun `transitions - every status - never targets itself`() {
        TaskStatus.entries.forEach { status ->
            assertThat(TaskStatusTransitions.isAllowed(status, status))
                .describedAs("%s -> %s", status, status)
                .isFalse()
        }
    }

    @Nested
    inner class FinalStates {
        @ParameterizedTest
        @EnumSource(TaskStatus::class, names = ["SUCCEEDED", "CANCELLED"])
        fun `transitions - final state - has no targets`(status: TaskStatus) {
            assertThat(status.isFinal).isTrue()
            assertThat(TaskStatusTransitions.targetsFrom(status)).isEmpty()
        }
    }

    @Nested
    inner class Claiming {
        @Test
        fun `claim - any status - only PENDING may become RUNNING`() {
            val claimable = TaskStatus.entries.filter { TaskStatusTransitions.isAllowed(it, TaskStatus.RUNNING) }

            assertThat(claimable).containsExactly(TaskStatus.PENDING)
        }
    }

    @Nested
    inner class Finishing {
        @Test
        fun `finish - running task - ends only in a handler outcome, a lock release or a cancellation`() {
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
    }

    @Nested
    inner class OperatorDecisions {
        @ParameterizedTest
        @EnumSource(TaskStatus::class, mode = EnumSource.Mode.EXCLUDE, names = ["SUCCEEDED", "CANCELLED"])
        fun `cancel - any non-final status - is allowed`(status: TaskStatus) {
            assertThat(TaskStatusTransitions.isAllowed(status, TaskStatus.CANCELLED)).isTrue()
        }

        @Test
        fun `release - QUARANTINED task with a run time ahead - may go back to SCHEDULED`() {
            assertThat(TaskStatusTransitions.isAllowed(TaskStatus.QUARANTINED, TaskStatus.SCHEDULED)).isTrue()
        }

        @ParameterizedTest
        @EnumSource(TaskStatus::class, names = ["FAILED", "REJECTED", "QUARANTINED"])
        fun `restart or complete - status waiting for a human - is allowed`(status: TaskStatus) {
            assertThat(status.kind).isEqualTo(TaskStatus.Kind.WAITING_FOR_HUMAN)
            assertThat(TaskStatusTransitions.isAllowed(status, TaskStatus.PENDING)).isTrue()
            assertThat(TaskStatusTransitions.isAllowed(status, TaskStatus.SUCCEEDED)).isTrue()
        }

        @Test
        fun `restartable and completable - every status - exactly the states waiting for a human but approval`() {
            val waiting =
                TaskStatus.entries.filter {
                    it.kind == TaskStatus.Kind.WAITING_FOR_HUMAN &&
                        it != TaskStatus.AWAITING_APPROVAL
                }

            // Assert
            assertThat(TaskStatusTransitions.restartable).containsExactlyInAnyOrderElementsOf(waiting)
            assertThat(TaskStatusTransitions.completable).containsExactlyInAnyOrderElementsOf(waiting)
        }

        @Test
        fun `restart or complete - AWAITING_APPROVAL - is not an operator's move, only an approval moves it on`() {
            assertThat(TaskStatusTransitions.restartable).doesNotContain(TaskStatus.AWAITING_APPROVAL)
            assertThat(TaskStatusTransitions.completable).doesNotContain(TaskStatus.AWAITING_APPROVAL)
            assertThat(TaskStatusTransitions.isAllowed(TaskStatus.AWAITING_APPROVAL, TaskStatus.QUARANTINED)).isFalse()
        }
    }
}
