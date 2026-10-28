package io.github.d4niella.pendenz.store

import io.github.d4niella.pendenz.TaskStatus
import io.github.d4niella.pendenz.TaskStatusTransitions
import java.time.Instant
import java.util.Collections

/**
 * What a move by an operator, a cycle operation or an approval writes
 * beside the status, described once (ADR 0031). The in-memory store follows
 * these rules directly; [io.github.d4niella.pendenz.schema.PostgresMoves]
 * spells them out in SQL for the JDBC statements and the generated functions.
 *
 * - `finished_at` is now for a final target and cleared otherwise.
 * - The lock is cleared.
 * - The error column takes the reason when there is one, and is kept otherwise.
 * - A move to PENDING runs the task now: a run time ahead becomes now, so PENDING always means due.
 * - A released task is PENDING when due and SCHEDULED when its run time lies ahead.
 */
internal object OperatorMoves {
    /** The states a pause sets aside: those the library moves on by itself and a quarantine may stop. */
    val pausable: Set<TaskStatus> =
        frozen(TaskStatusTransitions.quarantinable.filter { it.kind == TaskStatus.Kind.ACTIVE })

    /** The states a cycle's cancel takes its tasks from. */
    val cancellable: Set<TaskStatus> = TaskStatusTransitions.cancellable

    /** The states that keep a cycle from finishing: every one that is not final. */
    val unfinished: Set<TaskStatus> = frozen(TaskStatus.entries.filter { !it.isFinal })

    /** The run time after a move to [to] at [now]: now for PENDING when the run time lies ahead. */
    fun runAt(
        to: TaskStatus,
        runAt: Instant,
        now: Instant,
    ): Instant = if (to == TaskStatus.PENDING && runAt.isAfter(now)) now else runAt

    /** Where a released task goes: PENDING when due at [now], SCHEDULED when its run time lies ahead. */
    fun released(
        runAt: Instant,
        now: Instant,
    ): TaskStatus = if (runAt.isAfter(now)) TaskStatus.SCHEDULED else TaskStatus.PENDING

    private fun frozen(states: Collection<TaskStatus>): Set<TaskStatus> =
        Collections.unmodifiableSet(states.toCollection(LinkedHashSet()))
}
