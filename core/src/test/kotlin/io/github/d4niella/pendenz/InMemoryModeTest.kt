package io.github.d4niella.pendenz

import io.github.d4niella.pendenz.testing.MutableClock
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/** The synchronous mode of principle 9: no database, no worker, no waiting. */
class InMemoryModeTest {
    @Serializable
    data class Greeting(
        val name: String,
    )

    private val clock = MutableClock()

    @Nested
    inner class RunPending {
        @Test
        fun `runPending - two pending tasks - runs both in enqueue order and returns 2`() =
            runBlocking<Unit> {
                // Arrange
                val greeted = CopyOnWriteArrayList<String>()
                val greet = TaskType.json<Greeting>("greet")
                val pendenz = pendenz { register(greet) { _, payload -> greeted += payload.name } }
                pendenz.enqueue(greet, Greeting("Ada"))
                pendenz.enqueue(greet, Greeting("Bob"))

                val ran = pendenz.runPending()

                // Assert
                assertThat(ran).isEqualTo(2)
                assertThat(greeted).containsExactly("Ada", "Bob")
            }

        @Test
        fun `runPending - nothing due - returns 0`() =
            runBlocking<Unit> {
                val pendenz = pendenz { register(TaskType.json<Greeting>("greet")) { _, _ -> } }

                val ran = pendenz.runPending()

                assertThat(ran).isZero()
            }

        @Test
        fun `runPending - handler throws - records RETRY_PENDING with the error instead of throwing`() =
            runBlocking<Unit> {
                // Arrange
                val greet = TaskType.json<Greeting>("greet")
                val pendenz = pendenz { register(greet) { _, _ -> error("mail server down") } }
                val id = pendenz.enqueue(greet, Greeting("Cleo"))

                pendenz.runPending()

                // Assert
                val record = pendenz.find(id)!!
                assertThat(record.status).isEqualTo(TaskStatus.RETRY_PENDING)
                assertThat(record.lastError).contains("mail server down")
                assertThat(record.attempts).isEqualTo(1)
            }
    }

    @Nested
    inner class Time {
        @Test
        fun `runPending - scheduled task before its run time - runs nothing, the task stays SCHEDULED`() =
            runBlocking<Unit> {
                // Arrange
                val greet = TaskType.json<Greeting>("greet")
                val pendenz = pendenz { register(greet) { _, _ -> } }
                val id = pendenz.enqueue(greet, Greeting("Dan"), runAt = clock.now.plus(Duration.ofHours(1)))

                val ran = pendenz.runPending()

                // Assert
                assertThat(ran).isZero()
                assertThat(pendenz.find(id)!!.status).isEqualTo(TaskStatus.SCHEDULED)
            }

        @Test
        fun `runPending - clock passed the run time of a scheduled task - runs it to SUCCEEDED`() =
            runBlocking<Unit> {
                // Arrange
                val greet = TaskType.json<Greeting>("greet")
                val pendenz = pendenz { register(greet) { _, _ -> } }
                val id = pendenz.enqueue(greet, Greeting("Dan"), runAt = clock.now.plus(Duration.ofHours(1)))
                clock.advance(Duration.ofHours(1))

                val ran = pendenz.runPending()

                // Assert
                assertThat(ran).isEqualTo(1)
                assertThat(pendenz.find(id)!!.status).isEqualTo(TaskStatus.SUCCEEDED)
            }

        @Test
        fun `runPending - retry due after the backoff - runs the task again once the clock moved`() =
            runBlocking<Unit> {
                // Arrange
                val calls = CopyOnWriteArrayList<Int>()
                val fiveMinutes =
                    ExponentialBackoff(maxAttempts = 3, initialDelay = Duration.ofMinutes(5), jitter = 0.0)
                val flaky = TaskType.json<Greeting>("flaky", retryPolicy = fiveMinutes)
                val pendenz =
                    pendenz {
                        register(flaky) { ctx, _ ->
                            calls += ctx.attempt
                            if (ctx.attempt == 1) error("not yet")
                        }
                    }
                val id = pendenz.enqueue(flaky, Greeting("Eve"))
                pendenz.runPending() // the first attempt fails, the retry is due in five minutes

                // Act
                clock.advance(Duration.ofMinutes(5))
                pendenz.runPending()

                // Assert
                assertThat(calls).containsExactly(1, 2)
                assertThat(pendenz.find(id)!!.status).isEqualTo(TaskStatus.SUCCEEDED)
            }
    }

    @Nested
    inner class Context {
        @Test
        fun `handler context - scheduled task claimed an hour late - carries the due time and the claim time`() =
            runBlocking<Unit> {
                // Arrange
                val seen = CopyOnWriteArrayList<Pair<Instant, Instant>>()
                val greet = TaskType.json<Greeting>("greet")
                val pendenz = pendenz { register(greet) { ctx, _ -> seen += ctx.dueAt to ctx.claimedAt } }
                val dueAt = clock.now.plus(Duration.ofHours(1))
                pendenz.enqueue(greet, Greeting("Ada"), runAt = dueAt)
                clock.advance(Duration.ofHours(2))

                pendenz.runPending()

                assertThat(seen).containsExactly(dueAt to clock.now)
            }
    }

    private fun pendenz(configure: Pendenz.Builder<Long>.() -> Unit): Pendenz<Long> =
        Pendenz.create {
            inMemory()
            clock(clock)
            configure()
        }
}
