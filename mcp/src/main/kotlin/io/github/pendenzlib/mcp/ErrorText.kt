package io.github.pendenzlib.mcp

import io.github.pendenzlib.TaskRecord
import io.github.pendenzlib.TaskStatus

/**
 * What `get_task` tells the agent of a task's `lastError` (ADR 0021): the
 * column holds the handler's exception text for a failed attempt, which
 * may name hosts, queries or data the agent should not see.
 */
public fun interface ErrorText {
    /** The text the agent sees for [task], or null to show none. */
    public fun of(task: TaskRecord<*>): String?

    public companion object {
        /**
         * Only reasons someone gave on purpose: a validator's for a REJECTED
         * task, an approver's or operator's for a CANCELLED one. A handler's
         * exception stays in the log and the table. The default.
         */
        @JvmField
        public val REASONS: ErrorText =
            ErrorText { task ->
                task.lastError.takeIf { task.status == TaskStatus.REJECTED || task.status == TaskStatus.CANCELLED }
            }

        /** The column as it is, exception texts included; for agents trusted with them. */
        @JvmField
        public val EVERYTHING: ErrorText = ErrorText { it.lastError }
    }
}
