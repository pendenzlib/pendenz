package io.github.pendenzlib

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Duration
import java.time.Instant
import kotlin.random.Random

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
        fun `delay - jitter of one half at both ends of the random source - half and almost one and a half`() {
            // Arrange: a random source that always yields its lowest value, and one that always yields its highest
            val lowest =
                jittered(
                    object : Random() {
                        override fun nextBits(bitCount: Int) = 0
                    },
                )
            val highest =
                jittered(
                    object : Random() {
                        override fun nextBits(bitCount: Int) = (1 shl bitCount) - 1
                    },
                )

            val earliest = lowest.nextAttemptAt(1, t0, cause)!!
            val latest = highest.nextAttemptAt(1, t0, cause)!!

            // Assert
            assertThat(earliest).isEqualTo(t0.plusSeconds(5))
            assertThat(latest).isBetween(t0.plusMillis(14_990), t0.plusSeconds(15))
        }

        private fun jittered(random: Random) =
            ExponentialBackoff(maxAttempts = 10, initialDelay = Duration.ofSeconds(10), jitter = 0.5, random = random)
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
        @ParameterizedTest
        @ValueSource(strings = ["maxAttempts", "initialDelay", "multiplier", "jitter", "maxDelay"])
        fun `constructor - one value out of range - throws IllegalArgumentException naming it`(value: String) {
            val attempt = {
                when (value) {
                    "maxAttempts" -> ExponentialBackoff(maxAttempts = 0)
                    "initialDelay" -> ExponentialBackoff(initialDelay = Duration.ofSeconds(-1))
                    "multiplier" -> ExponentialBackoff(multiplier = 0.5)
                    "jitter" -> ExponentialBackoff(jitter = 1.5)
                    else -> ExponentialBackoff(initialDelay = Duration.ofMinutes(2), maxDelay = Duration.ofMinutes(1))
                }
            }

            assertThatThrownBy { attempt() }
                .isInstanceOf(
                    IllegalArgumentException::class.java,
                ).hasMessageContaining(value)
        }
    }
}
