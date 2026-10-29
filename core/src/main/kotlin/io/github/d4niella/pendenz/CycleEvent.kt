package io.github.d4niella.pendenz

/**
 * One operation on a cycle, as the cycle events of [TaskListener] report
 * it once the operation has committed: one event per operation, with the
 * number of tasks it touched, not one per task (ADR 0032).
 *
 * @property action [Action.START_CYCLE], [Action.APPROVE_CYCLE], [Action.PAUSE_CYCLE], [Action.RESUME_CYCLE]
 *   or [Action.CANCEL_CYCLE].
 * @property from the status the cycle left, read from the row the operation changed; null for a start.
 * @property to the status the cycle is in afterwards; an approval leaves it ACTIVE.
 * @property actor who ran the operation, the creator for a start, or null.
 * @property tasks how many tasks the operation inserted, approved, set aside, gave back or cancelled.
 * @property awaitingApproval how many of the inserted tasks wait for approval; 0 for every other operation.
 */
@Suppress("LongParameterList") // one parameter per aspect of the operation, by design
public class CycleEvent<ID : Any> internal constructor(
    public val id: ID,
    public val type: String,
    public val action: Action,
    public val from: CycleStatus?,
    public val to: CycleStatus,
    public val actor: String?,
    public val tasks: Int,
    public val awaitingApproval: Int,
) {
    override fun toString(): String =
        "CycleEvent(id=$id, type=$type, action=$action, $from -> $to, actor=$actor, tasks=$tasks)"
}
