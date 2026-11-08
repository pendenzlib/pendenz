package io.github.pendenzlib

import java.time.Duration
import java.time.Instant
import kotlin.math.pow
import kotlin.random.Random

/**
 * Decides whether and when a failed task runs again.
 *
 * Called after a handler threw, and after a task's lock expired because
 * its worker stopped (ADR 0026). Returning `null` means the task is done
 * trying and moves to [TaskStatus.FAILED]. A policy that throws gives up
 * the same way; the exception is logged (ADR 0038).
 */
public fun interface RetryPolicy {
    /**
     * @param failure the attempt that just failed: the task, when and why.
     * @return when to try again, or `null` to give up.
     */
    public fun nextAttemptAt(failure: FailedAttempt): Instant?
}

/**
 * An attempt that failed, as the [RetryPolicy] sees it (ADR 0038).
 *
 * @property task the task as its handler saw it, without the follow-ups and provenance the handler collected;
 *   for an expired lock, its `claimedAt` is when the expiry was found.
 * @property failedAt when the attempt failed, on the worker's clock.
 * @property cause what the handler threw, or a [LockExpiredException] when the worker stopped.
 */
public class FailedAttempt(
    public val task: TaskContext<*>,
    public val failedAt: Instant,
    public val cause: Throwable,
) {
    /** The attempt that failed, starting at 1; the same as `task.attempt`. */
    public val attempt: Int get() = task.attempt

    override fun toString(): String = "FailedAttempt(task=$task, failedAt=$failedAt, cause=$cause)"
}

/** Never retry. The first failure is final. */
public object NoRetry : RetryPolicy {
    override fun nextAttemptAt(failure: FailedAttempt): Instant? = null
}

/**
 * Exponential backoff with jitter, capped at a maximum delay and a maximum
 * number of attempts. The defaults try five times in all: the first attempt
 * and four retries, after about 1, 2, 4 and 8 seconds.
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
            require(maxDelay >= initialDelay) { "maxDelay must not be shorter than initialDelay" }
        }

        override fun nextAttemptAt(failure: FailedAttempt): Instant? {
            val attempt = failure.attempt
            if (attempt >= maxAttempts) return null
            val exponent = attempt - 1
            val raw = initialDelay.toMillis().toDouble() * multiplier.pow(exponent)
            val capped = minOf(raw, maxDelay.toMillis().toDouble())
            val spread = capped * jitter
            val jittered = capped + (random.nextDouble() * 2 - 1) * spread
            return failure.failedAt.plusMillis(jittered.toLong().coerceAtLeast(0))
        }
    }
