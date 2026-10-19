package io.github.d4niella.pendenz

/**
 * What an actor may do to tasks of a type (ADR 0018). Checked in the API
 * before anything is written; SQL is governed by database grants, not by
 * this. Implement it over the application's own permission system, or
 * use [ALLOW_ALL] or [table].
 */
public fun interface Permissions {
    /** Whether [actor] may do [action] to tasks of [taskType]; null is an anonymous caller. */
    public fun allows(
        actor: String?,
        action: Action,
        taskType: String,
    ): Boolean

    public companion object {
        /** Everyone may do everything, anonymous callers included. The default. */
        @JvmField
        public val ALLOW_ALL: Permissions = Permissions { _, _, _ -> true }

        /** A static table of who may do what; anyone not listed, and every anonymous caller, may do nothing. */
        @JvmStatic
        public fun table(configure: PermissionTable.Builder.() -> Unit): Permissions =
            PermissionTable.Builder().apply(configure).build()
    }
}

/** The operations [Permissions] guard. */
public enum class Action {
    ENQUEUE,
    APPROVE,
    DECLINE,
    RESTART,
    COMPLETE,
    QUARANTINE,
    CANCEL,
    START_CYCLE,
    APPROVE_CYCLE,
    PAUSE_CYCLE,
    RESUME_CYCLE,
    CANCEL_CYCLE,
}

/** A [Permissions] implementation from a fixed list of grants, for small applications and tests. */
public class PermissionTable private constructor(
    private val grants: Set<Grant>,
) : Permissions {
    private class Grant(
        val actor: String,
        val action: Action,
        val taskType: String,
    )

    override fun allows(
        actor: String?,
        action: Action,
        taskType: String,
    ): Boolean =
        actor != null &&
            grants.any {
                it.actor == actor && it.action == action &&
                    (it.taskType == ANY_TYPE || it.taskType == taskType)
            }

    /** Collects grants: `actor("clerk").may(Action.ENQUEUE, "send-invoice").may(Action.APPROVE)`. */
    public class Builder internal constructor() {
        private val grants = LinkedHashSet<Grant>()

        public fun actor(name: String): ActorGrants = ActorGrants(name)

        public fun build(): Permissions = PermissionTable(grants.toSet())

        /** The grants of one actor; [may] without a type grants the action on every type. */
        public inner class ActorGrants internal constructor(
            private val name: String,
        ) {
            @JvmOverloads
            public fun may(
                action: Action,
                taskType: String = ANY_TYPE,
            ): ActorGrants = apply { grants += Grant(name, action, taskType) }
        }
    }

    private companion object {
        const val ANY_TYPE = "*"
    }
}
