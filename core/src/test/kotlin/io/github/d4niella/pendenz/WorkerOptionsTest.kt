package io.github.d4niella.pendenz

import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Duration

class WorkerOptionsTest {
    @ParameterizedTest
    @ValueSource(strings = ["pollInterval", "heartbeatInterval", "executionTimeout"])
    fun `worker options - interval of zero - throws IllegalArgumentException naming it`(option: String) {
        val zero = Duration.ZERO

        val attempt = {
            when (option) {
                "pollInterval" -> WorkerOptions(pollInterval = zero)
                "heartbeatInterval" -> WorkerOptions(heartbeatInterval = zero)
                else -> WorkerOptions(executionTimeout = zero)
            }
        }

        assertThatThrownBy { attempt() }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining(option)
    }
}
