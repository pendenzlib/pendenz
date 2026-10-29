package io.github.pendenzlib

import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant

/**
 * Hooks for metrics and logs. Every method has an empty default, so a
 * listener implements only what it needs.
 *
 * An event means that a row changed, and it arrives once that change has
 * committed (ADR 0032). Events of a task's run come from the worker, after
 * the outcome is written. Enqueues, approvals, operator moves and cycle
 * operations come from the caller once its transaction has committed;
 * inside a transaction the queue joined through its connection provider,
 * such as Spring's, after that transaction commits, and not at all when it
 * rolls back. The overloads that take a `Connection` cannot see the
 * caller's commit and report when they return, before it.
 *
 * Delivery is at most once: a process that dies right after a commit
 * loses its events. A listener must be quick, since the caller waits for
 * it; one that throws is logged and ignored, it never changes what
 * happens to the task.
 *
 * Register listeners with [Pendenz.Builder.listener].
 */
@Suppress("TooManyFunctions") // one method per event in a task's life, the events dictate the count
public interface TaskListener<ID : Any> {
    /**
     * `enqueue` stored the task [id]. A repeat under an idempotency key
     * stores nothing and fires nothing (ADR 0009, ADR 0032). Tasks of a
     * cycle are reported by [onCycleStarted] instead.
     */
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

    /**
     * The handler threw and the retry policy scheduled another attempt at [nextRunAt]. Also fires when
     * the task's lock expired because its worker died; [error] is then a [LockExpiredException] (ADR 0026).
     */
    public fun onRetryScheduled(
        task: TaskContext<ID>,
        error: Throwable,
        nextRunAt: Instant,
    ) {
    }

    /** The handler returned, but the validator refused the result; the row is `REJECTED` (ADR 0020). */
    public fun onRejected(
        task: TaskContext<ID>,
        reason: String,
    ) {
    }

    /**
     * The handler threw and the row is `FAILED`: attempts exhausted, or the failure is not retryable.
     * Also fires for an expired lock, with a [LockExpiredException] as [error] (ADR 0026).
     */
    public fun onFailed(
        task: TaskContext<ID>,
        error: Throwable,
    ) {
    }

    /**
     * The worker stopped and gave the task back as `PENDING` (ADR 0005):
     * while its handler ran, or right after claiming it, before it started.
     */
    public fun onReleased(task: TaskContext<ID>) {}

    /** The handler finished, but the row had been changed meanwhile, so the outcome was dropped. */
    public fun onOutcomeDropped(task: TaskContext<ID>) {}

    /** A maintenance sweep removed or archived [count] finished tasks of [type] (ADR 0011). */
    public fun onRetentionApplied(
        type: String,
        count: Int,
    ) {
    }

    /**
     * `enqueue` stored the task as AWAITING_APPROVAL; it waits for a person (ADR 0018). Not for a repeat under
     * an idempotency key, and not for tasks of a cycle, which [onCycleStarted] counts.
     */
    public fun onAwaitingApproval(
        id: ID,
        type: String,
    ) {
    }

    /** [actor] approved the task; it will run. */
    public fun onApproved(
        id: ID,
        type: String,
        actor: String,
    ) {
    }

    /** [actor] declined the task with [reason]; it is CANCELLED. */
    public fun onDeclined(
        id: ID,
        type: String,
        actor: String,
        reason: String,
    ) {
    }

    /** An operator restarted, completed, quarantined or cancelled a task; see [OperatorMove]. */
    public fun onOperatorMove(move: OperatorMove<ID>) {}

    /** A cycle was started with [CycleEvent.tasks] tasks, [CycleEvent.awaitingApproval] of them waiting. */
    public fun onCycleStarted(cycle: CycleEvent<ID>) {}

    /** [CycleEvent.actor] approved [CycleEvent.tasks] tasks of a cycle at once; not fired when there were none. */
    public fun onCycleApproved(cycle: CycleEvent<ID>) {}

    /** A cycle was paused, resumed or cancelled, together with [CycleEvent.tasks] of its tasks. */
    public fun onCycleMoved(cycle: CycleEvent<ID>) {}
}

/** Fans out to every registered listener and shields the worker from their exceptions. */
@Suppress("TooManyFunctions") // one method per event, the interface dictates the count
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

    override fun onRejected(
        task: TaskContext<ID>,
        reason: String,
    ) = each { it.onRejected(task, reason) }

    override fun onReleased(task: TaskContext<ID>) = each { it.onReleased(task) }

    override fun onOutcomeDropped(task: TaskContext<ID>) = each { it.onOutcomeDropped(task) }

    override fun onRetentionApplied(
        type: String,
        count: Int,
    ) = each { it.onRetentionApplied(type, count) }

    override fun onAwaitingApproval(
        id: ID,
        type: String,
    ) = each { it.onAwaitingApproval(id, type) }

    override fun onApproved(
        id: ID,
        type: String,
        actor: String,
    ) = each { it.onApproved(id, type, actor) }

    override fun onDeclined(
        id: ID,
        type: String,
        actor: String,
        reason: String,
    ) = each { it.onDeclined(id, type, actor, reason) }

    override fun onOperatorMove(move: OperatorMove<ID>) = each { it.onOperatorMove(move) }

    override fun onCycleStarted(cycle: CycleEvent<ID>) = each { it.onCycleStarted(cycle) }

    override fun onCycleApproved(cycle: CycleEvent<ID>) = each { it.onCycleApproved(cycle) }

    override fun onCycleMoved(cycle: CycleEvent<ID>) = each { it.onCycleMoved(cycle) }

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
