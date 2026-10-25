package io.github.d4niella.pendenz

import io.github.d4niella.pendenz.schema.IdType
import io.github.d4niella.pendenz.store.InMemoryTaskStore
import io.github.d4niella.pendenz.testing.MutableClock
import io.github.d4niella.pendenz.testing.claim
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/** The hooks for metrics and logs, through the in-memory mode. */
class TaskListenerTest {
    @Serializable
    data class Greeting(
        val name: String,
    )

    /** Records every event as one line, so a test can assert the whole sequence. */
    private class Recording : TaskListener<Long> {
        val events = CopyOnWriteArrayList<String>()

        override fun onEnqueued(
            id: Long,
            type: String,
        ) {
            events += "enqueued $type #$id"
        }

        override fun onClaimed(task: TaskContext<Long>) {
            events += "claimed #${task.id} attempt ${task.attempt}"
        }

        override fun onSucceeded(
            task: TaskContext<Long>,
            duration: Duration,
        ) {
            events += "succeeded #${task.id} after $duration"
        }

        override fun onRetryScheduled(
            task: TaskContext<Long>,
            error: Throwable,
            nextRunAt: Instant,
        ) {
            events += "retry #${task.id} at $nextRunAt because ${error.message}"
        }

        override fun onFailed(
            task: TaskContext<Long>,
            error: Throwable,
        ) {
            events += "failed #${task.id} because ${error.message}"
        }

        override fun onRetentionApplied(
            type: String,
            count: Int,
        ) {
            events += "retention $type removed $count"
        }
    }

    private val clock = MutableClock()
    private val store = InMemoryTaskStore(IdType.BIGINT)
    private val recording = Recording()

    @Nested
    inner class Lifecycle {
        @Test
        fun `listener - task succeeds - sees enqueued, claimed and succeeded with the duration`() =
            runBlocking<Unit> {
                // Arrange
                val type = TaskType.json<Greeting>("greet")
                val pendenz = pendenz { register(type) { _, _ -> clock.advance(Duration.ofSeconds(2)) } }

                // Act
                val id = pendenz.enqueue(type, Greeting("Ada"))
                pendenz.runPending()

                // Assert
                assertThat(recording.events)
                    .containsExactly("enqueued greet #$id", "claimed #$id attempt 1", "succeeded #$id after PT2S")
            }

        @Test
        fun `listener - handler throws with attempts left - sees retry with the next run time and the cause`() =
            runBlocking<Unit> {
                // Arrange
                val backoff = ExponentialBackoff(maxAttempts = 2, initialDelay = Duration.ofMinutes(1), jitter = 0.0)
                val type = TaskType.json<Greeting>("greet", retryPolicy = backoff)
                val pendenz = pendenz { register(type) { _, _ -> error("mail server down") } }
                val id = pendenz.enqueue(type, Greeting("Bob"))

                pendenz.runPending()

                // Assert
                val nextRunAt = clock.now.plus(Duration.ofMinutes(1))
                assertThat(recording.events).contains("retry #$id at $nextRunAt because mail server down")
            }

        @Test
        fun `listener - attempts exhausted - sees failed with the cause`() =
            runBlocking<Unit> {
                // Arrange
                val type = TaskType.json<Greeting>("greet", retryPolicy = NoRetry)
                val pendenz = pendenz { register(type) { _, _ -> error("mail server down") } }
                val id = pendenz.enqueue(type, Greeting("Cleo"))

                pendenz.runPending()

                assertThat(recording.events).contains("failed #$id because mail server down")
            }

        @Test
        fun `listener - lock of a dead worker expired - sees a retry caused by the expired lock`() =
            runBlocking<Unit> {
                // Arrange
                val type = TaskType.json<Greeting>("greet", retryPolicy = ExponentialBackoff(jitter = 0.0))
                val pendenz = pendenz { register(type) { _, _ -> } }
                val id = pendenz.enqueue(type, Greeting("Dan"))
                store.transaction { store.claim(it, "dead-worker", setOf("greet"), 1, clock.now) }
                clock.advance(Duration.ofMinutes(5))

                pendenz.runPending()

                // Assert
                assertThat(recording.events).anySatisfy {
                    assertThat(it).startsWith("retry #$id at ${clock.now.plusSeconds(1)} because lock expired")
                }
            }

        @Test
        fun `listener - retention sweep removes rows - sees the type and the count`() =
            runBlocking<Unit> {
                // Arrange
                val type = TaskType.json<Greeting>("greet", retention = Retention.delete(Duration.ZERO))
                val pendenz = pendenz { register(type) { _, _ -> } }
                pendenz.enqueue(type, Greeting("Eve"))
                pendenz.enqueue(type, Greeting("Fay"))
                pendenz.runPending()
                clock.advance(Duration.ofSeconds(1))

                pendenz.runMaintenance()

                assertThat(recording.events).contains("retention greet removed 2")
            }
    }

    @Nested
    inner class Robustness {
        @Test
        fun `listener - throws in a hook - the task still succeeds and other listeners are still called`() =
            runBlocking<Unit> {
                // Arrange
                val broken =
                    object : TaskListener<Long> {
                        override fun onClaimed(task: TaskContext<Long>) = error("metrics backend down")
                    }
                val type = TaskType.json<Greeting>("greet")
                val pendenz =
                    Pendenz.create {
                        store(store)
                        clock(clock)
                        listener(broken)
                        listener(recording)
                        register(type) { _, _ -> }
                    }
                val id = pendenz.enqueue(type, Greeting("Gus"))

                pendenz.runPending()

                // Assert
                assertThat(pendenz.find(id)!!.status).isEqualTo(TaskStatus.SUCCEEDED)
                assertThat(recording.events).contains("claimed #$id attempt 1")
            }
    }

    private fun pendenz(configure: Pendenz.Builder<Long>.() -> Unit): Pendenz<Long> =
        Pendenz.create {
            store(store)
            clock(clock)
            listener(recording)
            configure()
        }
}
