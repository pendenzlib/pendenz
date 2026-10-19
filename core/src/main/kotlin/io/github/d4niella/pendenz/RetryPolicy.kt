package io.github.d4niella.pendenz

import java.time.Duration
import java.time.Instant
import kotlin.random.Random

/**
 * Decides whether and when a failed task runs again.
 *
 * Called after a handler threw. Returning `null` means the task is done
 * trying and moves to [TaskStatus.FAILED].
 */
public fun interface RetryPolicy {
    /**
     * @param attempt the attempt that just failed, starting at 1.
     * @param failedAt when it failed.
     * @param cause what the handler threw.
     * @return when to try again, or `null` to give up.
     */
    public fun nextAttemptAt(
        attempt: Int,
        failedAt: Instant,
        cause: Throwable,
    ): Instant?
}

/** Never retry. The first failure is final. */
public object NoRetry : RetryPolicy {
    override fun nextAttemptAt(
        attempt: Int,
        failedAt: Instant,
        cause: Throwable,
    ): Instant? = null
}

/**
 * Exponential backoff with jitter, capped at a maximum delay and a maximum
 * number of attempts. The defaults retry five times over roughly half a minute.
 *
 * @property maxAttempts total attempts including the first one.
 * @property initialDelay delay after the first failure.
 * @property multiplier growth factor per failure.
 * @property maxDelay upper bound for a single delay.
 * @property jitter fraction of the delay that is randomised in both directions, 0 to 1.
 */
public class ExponentialBackoff
    @JvmOverloads
    constructor(
        public val maxAttempts: Int = 5,
        public val initialDelay: Duration = Duration.ofSeconds(1),
        public val multiplier: Double = 2.0,
        public val maxDelay: Duration = Duration.ofHours(1),
        public val jitter: Double = 0.2,
        private val random: Random = Random.Default,
    ) : RetryPolicy {
        init {
            require(maxAttempts >= 1) { "maxAttempts must be at least 1" }
            require(!initialDelay.isNegative) { "initialDelay must not be negative" }
            require(multiplier >= 1.0) { "multiplier must be at least 1" }
            require(jitter in 0.0..1.0) { "jitter must be between 0 and 1" }
        }

        override fun nextAttemptAt(
            attempt: Int,
            failedAt: Instant,
            cause: Throwable,
        ): Instant? {
            if (attempt >= maxAttempts) return null
            val exponent = attempt - 1
            val raw = initialDelay.toMillis().toDouble() * Math.pow(multiplier, exponent.toDouble())
            val capped = minOf(raw, maxDelay.toMillis().toDouble())
            val spread = capped * jitter
            val jittered = capped + (random.nextDouble() * 2 - 1) * spread
            return failedAt.plusMillis(jittered.toLong().coerceAtLeast(0))
        }
    }
