package io.github.pendenzlib

import kotlinx.serialization.Serializable
import org.assertj.core.api.Assertions.assertThatThrownBy
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
}
