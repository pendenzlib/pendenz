package io.github.d4niella.pendenz

/**
 * Whether a task of a type must be approved by a person before it runs
 * (ADR 0018). Decided when the task is enqueued; a task that needs
 * approval is stored as [TaskStatus.AWAITING_APPROVAL] and never claimed
 * until someone other than its creator approves it.
 */
public fun interface Approval<in P : Any> {
    /**
     * Whether this task needs approval. [payload] is null for tasks a
     * cycle creates from a query, which the rule cannot see.
     */
    public fun requires(
        createdBy: String?,
        payload: P?,
    ): Boolean

    public companion object {
        /** No task of the type needs approval. The default. */
        @JvmField
        public val NEVER: Approval<Any> = Approval { _, _ -> false }

        /** Every task of the type needs approval. */
        @JvmField
        public val ALWAYS: Approval<Any> = Approval { _, _ -> true }

        /** A rule over creator and payload, for example "refunds above a thousand" or "what an agent created". */
        @JvmStatic
        public fun <P : Any> rule(rule: Approval<P>): Approval<P> = rule
    }
}

/** An actor tried something the permissions or the approval rules do not allow. */
public class NotPermittedException(
    public val actor: String?,
    public val action: Action,
    public val taskType: String,
    reason: String = "not permitted",
) : RuntimeException("${actor ?: "an anonymous caller"} may not $action tasks of type '$taskType': $reason")
