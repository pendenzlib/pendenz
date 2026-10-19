package io.github.d4niella.pendenz

import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant

/**
 * Hooks for metrics and logs. Every method has an empty default, so a
 * listener implements only what it needs. Listeners are called on the
 * worker's coroutine; a listener that throws is logged and ignored, it
 * never changes what happens to the task.
 *
 * Register listeners with [Pendenz.Builder.listener].
 */
public interface TaskListener<ID : Any> {
    /** `enqueue` returned [id]. Inside the caller's transaction this fires before the commit. */
    public fun onEnqueued(
        id: ID,
        type: String,
    ) {
    }

    /** A worker claimed the task and is about to run its handler. */
    public fun onClaimed(task: TaskContext<ID>) {}

    /** The handler returned and the row is `SUCCEEDED`. */
    public fun onSucceeded(
        task: TaskContext<ID>,
        duration: Duration,
    ) {
    }

    /** The handler threw and the retry policy scheduled another attempt at [nextRunAt]. */
    public fun onRetryScheduled(
        task: TaskContext<ID>,
        error: Throwable,
        nextRunAt: Instant,
    ) {
    }

    /** The handler threw and the row is `FAILED`: attempts exhausted, or the failure is not retryable. */
    public fun onFailed(
        task: TaskContext<ID>,
        error: Throwable,
    ) {
    }

    /** The worker stopped while the handler was running; the task went back to `PENDING` (ADR 0005). */
    public fun onReleased(task: TaskContext<ID>) {}

    /** The handler finished, but the row had been changed meanwhile, so the outcome was dropped. */
    public fun onOutcomeDropped(task: TaskContext<ID>) {}

    /** A poll found [count] `RUNNING` tasks without a live heartbeat and gave them back as `PENDING`. */
    public fun onStaleLocksReleased(count: Int) {}

    /** A maintenance sweep removed or archived [count] finished tasks of [type] (ADR 0011). */
    public fun onRetentionApplied(
        type: String,
        count: Int,
    ) {
    }
}

/** Fans out to every registered listener and shields the worker from their exceptions. */
internal class Listeners<ID : Any>(
    private val listeners: List<TaskListener<ID>>,
) : TaskListener<ID> {
    private val log = LoggerFactory.getLogger(TaskListener::class.java)

    override fun onEnqueued(
        id: ID,
        type: String,
    ) = each { it.onEnqueued(id, type) }

    override fun onClaimed(task: TaskContext<ID>) = each { it.onClaimed(task) }

    override fun onSucceeded(
        task: TaskContext<ID>,
        duration: Duration,
    ) = each { it.onSucceeded(task, duration) }

    override fun onRetryScheduled(
        task: TaskContext<ID>,
        error: Throwable,
        nextRunAt: Instant,
    ) = each { it.onRetryScheduled(task, error, nextRunAt) }

    override fun onFailed(
        task: TaskContext<ID>,
        error: Throwable,
    ) = each { it.onFailed(task, error) }

    override fun onReleased(task: TaskContext<ID>) = each { it.onReleased(task) }

    override fun onOutcomeDropped(task: TaskContext<ID>) = each { it.onOutcomeDropped(task) }

    override fun onStaleLocksReleased(count: Int) = each { it.onStaleLocksReleased(count) }

    override fun onRetentionApplied(
        type: String,
        count: Int,
    ) = each { it.onRetentionApplied(type, count) }

    // A listener must never take the worker down, whatever it throws.
    @Suppress("TooGenericExceptionCaught")
    private inline fun each(notify: (TaskListener<ID>) -> Unit) {
        listeners.forEach { listener ->
            try {
                notify(listener)
            } catch (e: Exception) {
                log.error("listener {} threw and was ignored", listener::class.simpleName, e)
            }
        }
    }
}
