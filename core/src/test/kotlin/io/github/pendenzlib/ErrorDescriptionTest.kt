package io.github.pendenzlib

import io.github.pendenzlib.schema.IdType
import io.github.pendenzlib.store.InMemoryTaskStore
import io.github.pendenzlib.testing.MutableClock
import io.github.pendenzlib.testing.claim
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/** The text a failed attempt leaves in `last_error` (ADR 0043), through the in-memory mode. */
class ErrorDescriptionTest {
    @Serializable
    data class Transfer(
        val iban: String,
    )

    private val clock = MutableClock()
    private val store = InMemoryTaskStore(IdType.BIGINT)
    private val transfer = TaskType.json<Transfer>("transfer", retryPolicy = NoRetry)

    /** What a driver might report: the value that broke the insert, quoted. */
    private val leakyFailure = IllegalStateException("insert failed for iban=DE89370400440532013000")

    /** Keeps the class name and replaces anything that looks like an IBAN. */
    private val redacting =
        ErrorDescription { _, error ->
            error.toString().replace(Regex("[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}"), "<iban>")
        }

    @Nested
    inner class FailedAttempt {
        @Test
        fun `failed attempt - no description configured - last_error holds class name and message`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz { register(transfer) { _, _ -> throw leakyFailure } }
                val id = pendenz.enqueue(transfer, Transfer("DE89370400440532013000"))

                pendenz.runPending()

                assertThat(pendenz.find(id)!!.lastError).isEqualTo(leakyFailure.toString())
            }

        @Test
        fun `failed attempt - final - last_error holds the description`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz =
                    pendenz {
                        errorDescription(redacting)
                        register(transfer) { _, _ -> throw leakyFailure }
                    }
                val id = pendenz.enqueue(transfer, Transfer("DE89370400440532013000"))

                pendenz.runPending()

                // Assert
                val record = pendenz.find(id)!!
                assertThat(record.status).isEqualTo(TaskStatus.FAILED)
                assertThat(record.lastError).isEqualTo("java.lang.IllegalStateException: insert failed for iban=<iban>")
            }

        @Test
        fun `failed attempt - retry scheduled - last_error holds the description`() =
            runBlocking<Unit> {
                // Arrange
                val retried = TaskType.json<Transfer>("transfer", retryPolicy = ExponentialBackoff(jitter = 0.0))
                val pendenz =
                    pendenz {
                        errorDescription(redacting)
                        register(retried) { _, _ -> throw leakyFailure }
                    }
                val id = pendenz.enqueue(retried, Transfer("DE89370400440532013000"))

                pendenz.runPending()

                // Assert
                val record = pendenz.find(id)!!
                assertThat(record.status).isEqualTo(TaskStatus.RETRY_PENDING)
                assertThat(record.lastError).doesNotContain("DE89370400440532013000").contains("<iban>")
            }

        @Test
        fun `failed attempt - description asked - sees the task's context and the thrown exception`() =
            runBlocking<Unit> {
                // Arrange
                val asked = CopyOnWriteArrayList<String>()
                val pendenz =
                    pendenz {
                        errorDescription { context, error ->
                            asked += "${context.type} attempt ${context.attempt}: ${error.message}"
                            "described"
                        }
                        register(transfer) { _, _ -> throw leakyFailure }
                    }
                pendenz.enqueue(transfer, Transfer("DE89370400440532013000"))

                pendenz.runPending()

                assertThat(asked).containsExactly("transfer attempt 1: ${leakyFailure.message}")
            }

        @Test
        fun `failed attempt - description throws - last_error holds only the class name`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz =
                    pendenz {
                        errorDescription { _, _ -> error("redaction rules not loaded") }
                        register(transfer) { _, _ -> throw leakyFailure }
                    }
                val id = pendenz.enqueue(transfer, Transfer("DE89370400440532013000"))

                pendenz.runPending()

                // Assert
                val record = pendenz.find(id)!!
                assertThat(record.status).isEqualTo(TaskStatus.FAILED)
                assertThat(record.lastError).isEqualTo("java.lang.IllegalStateException")
            }

        @Test
        fun `failed attempt - description configured - listener still receives the original exception`() =
            runBlocking<Unit> {
                // Arrange
                val failures = CopyOnWriteArrayList<Throwable>()
                val pendenz =
                    pendenz {
                        errorDescription(redacting)
                        listener(
                            object : TaskListener<Long> {
                                override fun onFailed(
                                    task: TaskContext<Long>,
                                    error: Throwable,
                                ) {
                                    failures += error
                                }
                            },
                        )
                        register(transfer) { _, _ -> throw leakyFailure }
                    }
                pendenz.enqueue(transfer, Transfer("DE89370400440532013000"))

                pendenz.runPending()

                // Assert
                assertThat(failures).hasSize(1)
                assertThat(failures.single())
                    .isInstanceOf(IllegalStateException::class.java)
                    .hasMessage(leakyFailure.message)
            }

        @Test
        fun `failed attempt - payload newer than the handler - last_error holds the description`() =
            runBlocking<Unit> {
                // Arrange
                val newer = TaskType.json<Transfer>("transfer", version = 2)
                val producer = pendenz { register(newer) { _, _ -> } }
                val consumer =
                    pendenz {
                        errorDescription { _, error -> "unreadable: ${error.javaClass.simpleName}" }
                        register(transfer) { _, _ -> }
                    }
                val id = producer.enqueue(newer, Transfer("DE89370400440532013000"))

                consumer.runPending()

                assertThat(consumer.find(id)!!.lastError).isEqualTo("unreadable: UnsupportedPayloadVersionException")
            }

        @Test
        fun `failed attempt - lock of a dead worker expired - last_error holds the description`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz =
                    pendenz {
                        errorDescription { _, error -> "lost: ${error.javaClass.simpleName}" }
                        register(transfer) { _, _ -> }
                    }
                val id = pendenz.enqueue(transfer, Transfer("DE89370400440532013000"))
                store.transaction { store.claim(it, "dead-worker", setOf("transfer"), 1, clock.now) }
                clock.advance(Duration.ofMinutes(2))

                pendenz.runPending()

                assertThat(pendenz.find(id)!!.lastError).isEqualTo("lost: LockExpiredException")
            }
    }

    @Test
    fun `errorDescription - set twice - throws IllegalArgumentException`() {
        assertThatThrownBy { pendenz { errorDescription(redacting).errorDescription(ErrorDescription.DEFAULT) } }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("set twice")
    }

    private fun pendenz(configure: Pendenz.Builder<Long>.() -> Unit): Pendenz<Long> =
        Pendenz.create {
            store(store)
            clock(clock)
            configure()
        }
}
