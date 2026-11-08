package io.github.pendenzlib

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Duration

class TaskTypeTest {
    @Serializable
    data class Greeting(
        val name: String,
    )

    @Test
    fun `task type - execution timeout below a millisecond - throws IllegalArgumentException naming it`() {
        assertThatThrownBy { TaskType.json<Greeting>("greet", executionTimeout = Duration.ofNanos(999_999)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("executionTimeout")
    }

    @Nested
    inner class Builder {
        private val codec = KotlinxJsonCodec.of<Greeting>()

        @Test
        fun `build - claim batch size of zero - throws IllegalArgumentException as the constructor does`() {
            val builder = TaskType.builder("greet", codec).claimBatchSize(0)

            assertThatThrownBy { builder.build() }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("claimBatchSize")
        }

        @Test
        fun `toBuilder - variant under another name - fails without retry like the original with NoRetry`() =
            runBlocking<Unit> {
                // Arrange
                val greet = TaskType.builder("greet", codec).retryPolicy(NoRetry).build()
                val remind = greet.toBuilder().name("remind").build()
                val pendenz =
                    Pendenz.create {
                        inMemory()
                        register(remind) { _, _ -> error("mail server down") }
                    }
                val id = pendenz.enqueue(remind, Greeting("Ada"))

                pendenz.runPending()

                // Assert
                val task = pendenz.find(id)!!
                assertThat(task.type).isEqualTo("remind")
                assertThat(task.status).isEqualTo(TaskStatus.FAILED)
            }
    }
}
