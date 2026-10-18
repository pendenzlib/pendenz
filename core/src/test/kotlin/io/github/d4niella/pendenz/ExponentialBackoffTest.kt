package io.github.d4niella.pendenz

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class ExponentialBackoffTest {
    private val t0 = Instant.parse("2026-10-18T12:00:00Z")
    private val cause = RuntimeException("boom")

    @Nested
    inner class Delays {
        @Test
        fun `delay - no jitter - grows by the multiplier per failed attempt`() {
            val policy = ExponentialBackoff(maxAttempts = 10, initialDelay = Duration.ofSeconds(1), jitter = 0.0)

            // Assert
            assertThat(policy.nextAttemptAt(1, t0, cause)).isEqualTo(t0.plusSeconds(1))
            assertThat(policy.nextAttemptAt(2, t0, cause)).isEqualTo(t0.plusSeconds(2))
            assertThat(policy.nextAttemptAt(3, t0, cause)).isEqualTo(t0.plusSeconds(4))
        }

        @Test
        fun `delay - would exceed maxDelay - is capped at maxDelay`() {
            // Arrange
            val policy =
                ExponentialBackoff(
                    maxAttempts = 10,
                    initialDelay = Duration.ofSeconds(1),
                    maxDelay = Duration.ofSeconds(5),
                    jitter = 0.0,
                )

            val next = policy.nextAttemptAt(9, t0, cause)

            assertThat(next).isEqualTo(t0.plusSeconds(5))
        }

        @Test
        fun `delay - jitter of one half - stays between half and one and a half of the base delay`() {
            val policy = ExponentialBackoff(maxAttempts = 10, initialDelay = Duration.ofSeconds(10), jitter = 0.5)

            // Assert
            repeat(200) {
                val next = policy.nextAttemptAt(1, t0, cause)!!
                assertThat(next).describedAs("sample %d", it).isBetween(t0.plusSeconds(5), t0.plusSeconds(15))
            }
        }
    }

    @Nested
    inner class GivingUp {
        @Test
        fun `next attempt - last allowed attempt failed - returns null`() {
            val policy = ExponentialBackoff(maxAttempts = 3, jitter = 0.0)

            // Assert
            assertThat(policy.nextAttemptAt(2, t0, cause)).isNotNull()
            assertThat(policy.nextAttemptAt(3, t0, cause)).isNull()
        }
    }

    @Nested
    inner class Configuration {
        @Test
        fun `constructor - invalid values - throws IllegalArgumentException`() {
            assertThatThrownBy { ExponentialBackoff(maxAttempts = 0) }
                .isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { ExponentialBackoff(jitter = 1.5) }
                .isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { ExponentialBackoff(multiplier = 0.5) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }
}
