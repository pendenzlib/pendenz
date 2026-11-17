package io.github.pendenzlib

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
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

    @Test
    fun `worker options - lock timeout below twice the heartbeat - throws IllegalArgumentException`() {
        val attempt = { WorkerOptions(heartbeatInterval = Duration.ofSeconds(5), lockTimeout = Duration.ofSeconds(9)) }

        assertThatThrownBy { attempt() }
            .isInstanceOf(
                IllegalArgumentException::class.java,
            ).hasMessageContaining("twice")
    }

    @Nested
    inner class RefusedWriteRetry {
        @Test
        fun `refusedWriteRetry - above half the lock timeout - throws IllegalArgumentException naming it`() {
            val attempt = {
                WorkerOptions(lockTimeout = Duration.ofSeconds(60), refusedWriteRetry = Duration.ofSeconds(31))
            }

            assertThatThrownBy { attempt() }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("refusedWriteRetry")
        }

        @Test
        fun `refusedWriteRetry - not set, lock timeout of 60 s and of 400 ms - 300 ms and half the lock timeout`() {
            val defaults = WorkerOptions()
            val short = WorkerOptions(heartbeatInterval = Duration.ofMillis(100), lockTimeout = Duration.ofMillis(400))

            // Assert
            assertThat(defaults.refusedWriteRetry).isEqualTo(Duration.ofMillis(300))
            assertThat(short.refusedWriteRetry).isEqualTo(Duration.ofMillis(200))
        }

        @Test
        fun `toBuilder - lock timeout shortened, refusedWriteRetry never set - the default follows the lock timeout`() {
            val builder = WorkerOptions().toBuilder().heartbeatInterval(Duration.ofMillis(100))

            val options = builder.lockTimeout(Duration.ofMillis(400)).build()

            assertThat(options.refusedWriteRetry).isEqualTo(Duration.ofMillis(200))
        }

        @Test
        fun `startWorker - type's refusedWriteRetry above half the worker's lock timeout - throws, naming the type`() {
            // Arrange
            val patient = TaskType.json<Report>("monthly-report", refusedWriteRetry = Duration.ofMinutes(5))
            val pendenz =
                Pendenz.create {
                    inMemory()
                    register(patient) { _, _ -> }
                }
            val scope = CoroutineScope(Dispatchers.Default)

            val attempt = { pendenz.startWorker(scope, WorkerOptions(lockTimeout = Duration.ofMinutes(1))) }

            assertThatThrownBy { attempt() }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("monthly-report")
        }
    }

    @Serializable
    data class Report(
        val month: String,
    )
}
