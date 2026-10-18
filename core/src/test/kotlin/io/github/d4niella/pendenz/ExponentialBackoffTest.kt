package io.github.d4niella.pendenz

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class ExponentialBackoffTest {
    private val t0 = Instant.parse("2026-10-18T12:00:00Z")
    private val cause = RuntimeException("boom")

    @Test
    fun `delays grow by the multiplier without jitter`() {
        val policy =
            ExponentialBackoff(
                maxAttempts = 10,
                initialDelay = Duration.ofSeconds(1),
                multiplier = 2.0,
                jitter = 0.0,
            )
        assertThat(policy.nextAttemptAt(1, t0, cause)).isEqualTo(t0.plusSeconds(1))
        assertThat(policy.nextAttemptAt(2, t0, cause)).isEqualTo(t0.plusSeconds(2))
        assertThat(policy.nextAttemptAt(3, t0, cause)).isEqualTo(t0.plusSeconds(4))
    }

    @Test
    fun `delays are capped at the maximum`() {
        val policy =
            ExponentialBackoff(
                maxAttempts = 10,
                initialDelay = Duration.ofSeconds(1),
                maxDelay = Duration.ofSeconds(5),
                jitter = 0.0,
            )
        assertThat(policy.nextAttemptAt(9, t0, cause)).isEqualTo(t0.plusSeconds(5))
    }

    @Test
    fun `the last allowed attempt gives up`() {
        val policy = ExponentialBackoff(maxAttempts = 3, jitter = 0.0)
        assertThat(policy.nextAttemptAt(2, t0, cause)).isNotNull()
        assertThat(policy.nextAttemptAt(3, t0, cause)).isNull()
    }

    @Test
    fun `jitter stays within the configured fraction`() {
        val policy = ExponentialBackoff(maxAttempts = 10, initialDelay = Duration.ofSeconds(10), jitter = 0.5)
        repeat(200) {
            val next = policy.nextAttemptAt(1, t0, cause)!!
            assertThat(next).isBetween(t0.plusSeconds(5), t0.plusSeconds(15))
        }
    }

    @Test
    fun `invalid configuration is rejected early`() {
        assertThatThrownBy { ExponentialBackoff(maxAttempts = 0) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { ExponentialBackoff(jitter = 1.5) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { ExponentialBackoff(multiplier = 0.5) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
