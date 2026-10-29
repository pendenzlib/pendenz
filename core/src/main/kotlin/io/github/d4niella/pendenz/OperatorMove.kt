package io.github.d4niella.pendenz

/**
 * An operator's move of one task, as [TaskListener.onOperatorMove] reports
 * it once the move has committed (ADR 0032).
 *
 * @property action [Action.RESTART], [Action.COMPLETE], [Action.QUARANTINE] or [Action.CANCEL].
 * @property from the status the task left, read from the row the move changed.
 * @property to the status the task reached.
 * @property actor who moved it, as passed to the operation, or null.
 * @property reason the reason written into the error column, or null.
 */
@Suppress("LongParameterList") // one parameter per aspect of the move, by design
public class OperatorMove<ID : Any> internal constructor(
    public val id: ID,
    public val type: String,
    public val action: Action,
    public val from: TaskStatus,
    public val to: TaskStatus,
    public val actor: String?,
    public val reason: String?,
) {
    override fun toString(): String = "OperatorMove(id=$id, type=$type, action=$action, $from -> $to, actor=$actor)"
}
