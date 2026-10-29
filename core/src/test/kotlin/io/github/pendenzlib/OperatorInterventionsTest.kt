package io.github.pendenzlib

import io.github.pendenzlib.schema.IdType
import io.github.pendenzlib.store.InMemoryTaskStore
import io.github.pendenzlib.testing.MutableClock
import io.github.pendenzlib.testing.claim
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Duration

/** What an operator does to a single task through the API, in the in-memory mode. */
class OperatorInterventionsTest {
    @Serializable
    data class Greeting(
        val name: String,
    )

    private val greet = TaskType.json<Greeting>("greet", retryPolicy = NoRetry)

    @Nested
    inner class Restart {
        @Test
        fun `restart - PENDING task - returns false, nothing waits for a human`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz()
                val id = pendenz.enqueue(greet, Greeting("Ada"))

                val restarted = pendenz.restart(id)

                assertThat(restarted).isFalse()
            }

        @Test
        fun `restart - quarantined task planned three weeks ahead - PENDING, due now and run`() =
            runBlocking<Unit> {
                // Arrange
                val clock = MutableClock()
                val pendenz = pendenz(clock)
                val id = pendenz.enqueue(greet, Greeting("Ada"), runAt = clock.now.plus(Duration.ofDays(21)))
                pendenz.quarantine(id, "wrong address")

                pendenz.restart(id)

                // Assert
                val task = pendenz.find(id)!!
                assertThat(task.status).isEqualTo(TaskStatus.PENDING)
                assertThat(task.runAt).isEqualTo(clock.now)
                assertThat(pendenz.runPending()).isEqualTo(1)
            }
    }

    @Nested
    inner class Complete {
        @Test
        fun `complete - FAILED task - SUCCEEDED without running`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz { _, _ -> error("printer jam") }
                val id = pendenz.enqueue(greet, Greeting("Ada"))
                pendenz.runPending()

                val completed = pendenz.complete(id)

                // Assert
                assertThat(completed).isTrue()
                assertThat(pendenz.find(id)!!.status).isEqualTo(TaskStatus.SUCCEEDED)
            }
    }

    @Nested
    inner class Quarantine {
        @Test
        fun `quarantine - PENDING task with a reason - QUARANTINED with the reason`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz()
                val id = pendenz.enqueue(greet, Greeting("Ada"))

                val held = pendenz.quarantine(id, reason = "ticket #123")

                // Assert
                assertThat(held).isTrue()
                val record = pendenz.find(id)!!
                assertThat(record.status).isEqualTo(TaskStatus.QUARANTINED)
                assertThat(record.lastError).isEqualTo("ticket #123")
            }
    }

    @Nested
    inner class Cancel {
        @Test
        fun `runMaintenance - task cancelled while running, its worker died - the expired lock is cleared`() =
            runBlocking<Unit> {
                // Arrange: worker w1 claimed the task and vanished; an operator cancelled it meanwhile
                val clock = MutableClock()
                val store = InMemoryTaskStore(IdType.BIGINT)
                val pendenz =
                    Pendenz.create {
                        store(store)
                        clock(clock)
                        register(greet) { _, _ -> }
                    }
                val id = pendenz.enqueue(greet, Greeting("Ada"))
                store.transaction { store.claim(it, "w1", setOf("greet"), 1, clock.now) }
                pendenz.cancel(id)
                clock.advance(WorkerOptions.DEFAULT_LOCK_TIMEOUT.plusSeconds(1))

                // Act
                pendenz.runMaintenance()

                // Assert
                val task = pendenz.find(id)!!
                assertThat(task.status).isEqualTo(TaskStatus.CANCELLED)
                assertThat(task.lockedBy).isNull()
                assertThat(task.lockedUntil).isNull()
            }
    }

    private fun pendenz(handler: TaskHandler<Long, Greeting> = TaskHandler { _, _ -> }): Pendenz<Long> =
        Pendenz.create {
            inMemory()
            register(greet, handler)
        }

    private fun pendenz(clock: MutableClock): Pendenz<Long> =
        Pendenz.create {
            inMemory()
            clock(clock)
            register(greet) { _, _ -> }
        }
}
