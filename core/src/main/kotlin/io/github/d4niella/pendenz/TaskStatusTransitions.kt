package io.github.d4niella.pendenz

import io.github.d4niella.pendenz.TaskStatus.AWAITING_APPROVAL
import io.github.d4niella.pendenz.TaskStatus.CANCELLED
import io.github.d4niella.pendenz.TaskStatus.FAILED
import io.github.d4niella.pendenz.TaskStatus.PENDING
import io.github.d4niella.pendenz.TaskStatus.QUARANTINED
import io.github.d4niella.pendenz.TaskStatus.REJECTED
import io.github.d4niella.pendenz.TaskStatus.RETRY_PENDING
import io.github.d4niella.pendenz.TaskStatus.RUNNING
import io.github.d4niella.pendenz.TaskStatus.SCHEDULED
import io.github.d4niella.pendenz.TaskStatus.SUCCEEDED
import java.util.Collections

/**
 * The allowed transitions between [TaskStatus] values.
 *
 * This is the single source of truth. The library enforces it, the generated SQL
 * functions enforce it, and the documented raw SQL recipes follow it. Anything
 * not listed here is not a legal transition, no matter who attempts it.
 */
public object TaskStatusTransitions {
    /** Transitions a human may trigger from any state that is waiting for a decision. */
    private val humanDecisions: Set<TaskStatus> = setOf(PENDING, SUCCEEDED, CANCELLED, QUARANTINED)

    private val allowed: Map<TaskStatus, Set<TaskStatus>> =
        mapOf(
            SCHEDULED to setOf(PENDING, CANCELLED, QUARANTINED),
            PENDING to setOf(RUNNING, SCHEDULED, CANCELLED, QUARANTINED),
            // Approved, declined or cancelled; never quarantined, which would forget that approval was due.
            AWAITING_APPROVAL to setOf(PENDING, SCHEDULED, CANCELLED),
            RUNNING to setOf(SUCCEEDED, RETRY_PENDING, FAILED, REJECTED, PENDING, CANCELLED),
            RETRY_PENDING to setOf(PENDING, CANCELLED, QUARANTINED),
            FAILED to humanDecisions,
            REJECTED to humanDecisions,
            // Resuming a cycle puts a task back on its schedule when its run time lies ahead (ADR 0031).
            QUARANTINED to humanDecisions - QUARANTINED + SCHEDULED,
            SUCCEEDED to emptySet(),
            CANCELLED to emptySet(),
        ).mapValues { (_, targets) -> frozen(targets) }

    /**
     * The states an operator's restart takes a task from: those waiting for
     * a human, except `AWAITING_APPROVAL`, which only an approval moves on.
     * Restarting it would bypass the approval (ADR 0018).
     */
    @JvmField
    public val restartable: Set<TaskStatus> = frozen(setOf(FAILED, REJECTED, QUARANTINED))

    /** The states an operator may mark done from: those waiting for a human, except `AWAITING_APPROVAL`. */
    @JvmField
    public val completable: Set<TaskStatus> = frozen(setOf(FAILED, REJECTED, QUARANTINED))

    /** The states an operator may quarantine from. */
    @JvmField
    public val quarantinable: Set<TaskStatus> = frozen(TaskStatus.entries.filter { isAllowed(it, QUARANTINED) })

    /** The states an operator may cancel from: every non-final one. */
    @JvmField
    public val cancellable: Set<TaskStatus> = frozen(TaskStatus.entries.filter { isAllowed(it, CANCELLED) })

    /** All states a task in [from] may legally move to. */
    @JvmStatic
    public fun targetsFrom(from: TaskStatus): Set<TaskStatus> = allowed.getValue(from)

    /** A set Java callers cannot change either: `Set` is read-only in Kotlin only. */
    private fun frozen(states: Collection<TaskStatus>): Set<TaskStatus> = Collections.unmodifiableSet(states.toSet())

    /** Whether moving from [from] to [to] is legal. */
    @JvmStatic
    public fun isAllowed(
        from: TaskStatus,
        to: TaskStatus,
    ): Boolean = to in targetsFrom(from)
}
