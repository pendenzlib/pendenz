package io.github.pendenzlib

import io.github.pendenzlib.schema.IdType
import io.github.pendenzlib.store.InMemoryTaskStore
import io.github.pendenzlib.testing.MutableClock
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/** Results, validation, provenance and preview (ADR 0020), through the in-memory mode. */
class ResultTest {
    @Serializable
    data class Ticket(
        val id: Int,
        val text: String,
    )

    @Serializable
    data class Score(
        val value: Int,
    )

    private val clock = MutableClock()
    private val store = InMemoryTaskStore(IdType.BIGINT)
    private val events = CopyOnWriteArrayList<String>()
    private val scoreTicket = TaskType.json<Ticket>("score-ticket", retryPolicy = fastRetry)
    private val inRange =
        TaskResult.json<Ticket, Score> { _, score ->
            if (score.value in 0..100) Verdict.accept() else Verdict.reject("score ${score.value} out of range")
        }

    @Nested
    inner class Results {
        @Test
        fun `result handler - accepted result - SUCCEEDED with the result stored as JSON`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz(inRange) { _, ticket -> Score(ticket.text.length) }
                val id = pendenz.enqueue(scoreTicket, Ticket(1, "hello"))

                pendenz.runPending()

                // Assert
                val record = pendenz.find(id)!!
                assertThat(record.status).isEqualTo(TaskStatus.SUCCEEDED)
                assertThat(record.result).isEqualTo("""{"value":5}""")
            }

        @Test
        fun `result handler - refused result - REJECTED with result and reason, retry policy not consulted`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz(inRange) { _, _ -> Score(500) }
                val id = pendenz.enqueue(scoreTicket, Ticket(1, "x"))

                pendenz.runPending()

                // Assert
                val record = pendenz.find(id)!!
                assertThat(record.status).isEqualTo(TaskStatus.REJECTED)
                assertThat(record.result).isEqualTo("""{"value":500}""")
                assertThat(record.lastError).isEqualTo("score 500 out of range")
                assertThat(record.attempts).isEqualTo(1)
                assertThat(record.finishedAt).isEqualTo(clock.now)
                assertThat(events).contains("rejected #$id: score 500 out of range")
            }

        @Test
        fun `result handler - validator throws - treated as a handler failure and retried`() =
            runBlocking<Unit> {
                // Arrange
                val broken = TaskResult.json<Ticket, Score> { _, _ -> error("validator down") }
                val pendenz = pendenz(broken) { _, _ -> Score(1) }
                val id = pendenz.enqueue(scoreTicket, Ticket(1, "x"))

                pendenz.runPending()

                // Assert
                val record = pendenz.find(id)!!
                assertThat(record.status).isEqualTo(TaskStatus.RETRY_PENDING)
                assertThat(record.lastError).contains("validator down")
            }

        @Test
        fun `restart - REJECTED task - runs the handler again`() =
            runBlocking<Unit> {
                // Arrange
                var value = 500
                val pendenz = pendenz(inRange) { _, _ -> Score(value) }
                val id = pendenz.enqueue(scoreTicket, Ticket(1, "x"))
                pendenz.runPending()
                value = 42

                // Act
                pendenz.restart(id)
                pendenz.runPending()

                // Assert
                val record = pendenz.find(id)!!
                assertThat(record.status).isEqualTo(TaskStatus.SUCCEEDED)
                assertThat(record.result).isEqualTo("""{"value":42}""")
                assertThat(record.attempts).isEqualTo(2)
            }

        @Test
        fun `plain handler - no result registered - result stays null`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz =
                    Pendenz.create {
                        store(store)
                        clock(clock)
                        register(scoreTicket) { _, _ -> }
                    }
                val id = pendenz.enqueue(scoreTicket, Ticket(1, "x"))

                pendenz.runPending()

                assertThat(pendenz.find(id)!!.result).isNull()
            }
    }

    @Nested
    inner class Provenance {
        @Test
        fun `context provenance - handler attaches pairs and succeeds - stored with the outcome`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz =
                    pendenz(inRange) { ctx, _ ->
                        ctx.provenance("model", "claude-sonnet-5-5")
                        ctx.provenance("rules", "2026-10")
                        Score(1)
                    }
                val id = pendenz.enqueue(scoreTicket, Ticket(1, "x"))

                pendenz.runPending()

                assertThat(pendenz.find(id)!!.provenance)
                    .containsExactlyEntriesOf(mapOf("model" to "claude-sonnet-5-5", "rules" to "2026-10"))
            }

        @Test
        fun `context provenance - handler attaches pairs then throws - stored with the failure too`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz =
                    pendenz(inRange) { ctx, _ ->
                        ctx.provenance("model", "claude-sonnet-5-5")
                        error("model timed out")
                    }
                val id = pendenz.enqueue(scoreTicket, Ticket(1, "x"))

                pendenz.runPending()

                // Assert
                val record = pendenz.find(id)!!
                assertThat(record.status).isEqualTo(TaskStatus.RETRY_PENDING)
                assertThat(record.provenance).containsEntry("model", "claude-sonnet-5-5")
            }
    }

    @Nested
    inner class Preview {
        @Test
        fun `enqueue - type with a preview - the preview is computed and stored at enqueue`() =
            runBlocking<Unit> {
                // Arrange
                val refund = TaskType.json<Score>("refund", preview = { "Refund ${it.value}.00 EUR" })
                val pendenz =
                    Pendenz.create {
                        store(store)
                        clock(clock)
                        register(refund) { _, _ -> }
                    }

                val id = pendenz.enqueue(refund, Score(120))

                assertThat(pendenz.find(id)!!.preview).isEqualTo("Refund 120.00 EUR")
            }

        @Test
        fun `enqueue - type without a preview - no preview`() =
            runBlocking<Unit> {
                val pendenz =
                    Pendenz.create {
                        store(store)
                        clock(clock)
                        register(scoreTicket) { _, _ -> }
                    }

                val id = pendenz.enqueue(scoreTicket, Ticket(1, "x"))

                assertThat(pendenz.find(id)!!.preview).isNull()
            }
    }

    private fun pendenz(
        result: TaskResult<Ticket, Score>,
        handler: ResultHandler<Long, Ticket, Score>,
    ): Pendenz<Long> =
        Pendenz.create {
            store(store)
            clock(clock)
            listener(
                object : TaskListener<Long> {
                    override fun onRejected(
                        task: TaskContext<Long>,
                        reason: String,
                    ) {
                        events += "rejected #${task.id}: $reason"
                    }
                },
            )
            register(scoreTicket, result, handler)
        }

    private companion object {
        val fastRetry = ExponentialBackoff(maxAttempts = 3, initialDelay = Duration.ofMinutes(1), jitter = 0.0)
    }
}
