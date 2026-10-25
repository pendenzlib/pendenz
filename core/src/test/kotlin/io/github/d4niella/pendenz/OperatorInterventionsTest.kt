package io.github.d4niella.pendenz

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

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

    private fun pendenz(handler: TaskHandler<Long, Greeting> = TaskHandler { _, _ -> }): Pendenz<Long> =
        Pendenz.create {
            inMemory()
            register(greet, handler)
        }
}
