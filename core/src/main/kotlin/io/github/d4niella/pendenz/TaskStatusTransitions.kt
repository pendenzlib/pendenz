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
            AWAITING_APPROVAL to setOf(PENDING, SCHEDULED, CANCELLED, QUARANTINED),
            RUNNING to setOf(SUCCEEDED, RETRY_PENDING, FAILED, REJECTED, PENDING, CANCELLED),
            RETRY_PENDING to setOf(PENDING, CANCELLED, QUARANTINED),
            FAILED to humanDecisions,
            REJECTED to humanDecisions,
            QUARANTINED to humanDecisions - QUARANTINED,
            SUCCEEDED to emptySet(),
            CANCELLED to emptySet(),
        )

    /** All states a task in [from] may legally move to. */
    @JvmStatic
    public fun targetsFrom(from: TaskStatus): Set<TaskStatus> = allowed.getValue(from)

    /** Whether moving from [from] to [to] is legal. */
    @JvmStatic
    public fun isAllowed(
        from: TaskStatus,
        to: TaskStatus,
    ): Boolean = to in targetsFrom(from)
}
