package io.github.pendenzlib

import io.github.pendenzlib.testing.MutableClock
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/** The creation time and the run's deadline in the task context (ADR 0042), with a clock the test moves. */
class TaskContextTimesTest {
    @Serializable
    data class Report(
        val month: String,
    )

    private val enqueuedAt = Instant.parse("2026-11-08T09:00:00Z")
    private val clock = MutableClock(enqueuedAt)
    private val seen = CopyOnWriteArrayList<TaskContext<Long>>()

    @Nested
    inner class CreatedAt {
        @Test
        fun `handler - task run two hours after its enqueue - sees the enqueue time, not the claim time`() =
            runBlocking<Unit> {
                // Arrange
                val report = TaskType.json<Report>("monthly-report")
                val pendenz = pendenz(report)
                pendenz.enqueue(report, Report("2026-10"))
                clock.advance(Duration.ofHours(2))

                pendenz.runPending()

                // Assert
                val context = seen.single()
                assertThat(context.createdAt).isEqualTo(enqueuedAt)
                assertThat(context.claimedAt).isEqualTo(enqueuedAt.plus(Duration.ofHours(2)))
            }

        @Test
        fun `retry policy - gives up after a day - a task failing on the second day ends FAILED`() =
            runBlocking<Unit> {
                // Arrange
                val withinADay =
                    RetryPolicy { failure ->
                        val age = Duration.between(failure.task.createdAt, failure.failedAt)
                        if (age > Duration.ofDays(1)) null else failure.failedAt.plus(Duration.ofHours(6))
                    }
                val report = TaskType.json<Report>("monthly-report", retryPolicy = withinADay)
                val pendenz =
                    Pendenz.create {
                        inMemory()
                        clock(clock)
                        register(report) { _, _ -> error("ledger closed") }
                    }
                val id = pendenz.enqueue(report, Report("2026-10"))
                pendenz.runPending()
                clock.advance(Duration.ofHours(25))

                pendenz.runPending()

                // Assert
                val task = pendenz.find(id)!!
                assertThat(task.status).isEqualTo(TaskStatus.FAILED)
                assertThat(task.attempts).isEqualTo(2)
            }
    }

    @Nested
    inner class Deadline {
        @Test
        fun `handler - type with a timeout of its own - deadline is the run's start plus that timeout`() =
            runBlocking<Unit> {
                // Arrange
                val report = TaskType.json<Report>("monthly-report", executionTimeout = Duration.ofMinutes(30))
                val pendenz = pendenz(report)
                pendenz.enqueue(report, Report("2026-10"))

                pendenz.runPending(WorkerOptions(executionTimeout = Duration.ofMinutes(10)))

                assertThat(seen.single().deadline).isEqualTo(enqueuedAt.plus(Duration.ofMinutes(30)))
            }

        @Test
        fun `handler - type without a timeout of its own - deadline from the worker's timeout`() =
            runBlocking<Unit> {
                // Arrange
                val report = TaskType.json<Report>("monthly-report")
                val pendenz = pendenz(report)
                pendenz.enqueue(report, Report("2026-10"))

                pendenz.runPending(WorkerOptions(executionTimeout = Duration.ofMinutes(10)))

                assertThat(seen.single().deadline).isEqualTo(enqueuedAt.plus(Duration.ofMinutes(10)))
            }

        @Test
        fun `listener - claimed, then succeeded - no deadline at the claim, the run's deadline at the outcome`() =
            runBlocking<Unit> {
                // Arrange
                val deadlines = CopyOnWriteArrayList<String>()
                val listener =
                    object : TaskListener<Long> {
                        override fun onClaimed(task: TaskContext<Long>) {
                            deadlines += "claimed: ${task.deadline}"
                        }

                        override fun onSucceeded(
                            task: TaskContext<Long>,
                            duration: Duration,
                        ) {
                            deadlines += "succeeded: ${task.deadline}"
                        }
                    }
                val report = TaskType.json<Report>("monthly-report", executionTimeout = Duration.ofMinutes(30))
                val pendenz =
                    Pendenz.create {
                        inMemory()
                        clock(clock)
                        listener(listener)
                        register(report) { _, _ -> }
                    }
                pendenz.enqueue(report, Report("2026-10"))

                pendenz.runPending()

                assertThat(deadlines).containsExactly("claimed: null", "succeeded: ${enqueuedAt.plusSeconds(1800)}")
            }
    }

    private fun pendenz(type: TaskType<Report>): Pendenz<Long> =
        Pendenz.create {
            inMemory()
            clock(clock)
            register(type) { context, _ -> seen += context }
        }
}
