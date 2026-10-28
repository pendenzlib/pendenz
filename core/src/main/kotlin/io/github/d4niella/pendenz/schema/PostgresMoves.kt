package io.github.d4niella.pendenz.schema

import io.github.d4niella.pendenz.TaskStatus

/**
 * The SET clauses of the moves in `OperatorMoves`, in SQL, shared by the
 * JDBC statements and the generated operator functions so that both write
 * the same row (ADR 0031).
 *
 * Every clause reads the time and the reason from a derived table [ARGS]
 * with the columns [NOW] and [REASON], which the statement supplies after
 * `FROM`: bound parameters in the JDBC statements, `now()` and the
 * function's arguments in the generated functions. The clause text is
 * therefore the same in both.
 */
internal class PostgresMoves(
    private val c: QuotedColumns,
) {
    private val now = "$ARGS.$NOW"
    private val reason = "$ARGS.$REASON"
    private val unlock = "${c.lockedBy} = NULL, ${c.lockedUntil} = NULL"
    private val bump = "${c.version} = ${c.version} + 1"

    /** Moves the row to [to]: finish time, lock, error and run time as `OperatorMoves` describes. */
    fun to(to: TaskStatus): String =
        listOfNotNull(
            "${c.status} = '${to.name}'",
            "${c.finishedAt} = ${if (to.isFinal) now else "NULL"}",
            unlock,
            "${c.lastError} = COALESCE($reason, ${c.lastError})",
            "${c.runAt} = LEAST(${c.runAt}, $now)".takeIf { to == TaskStatus.PENDING },
            bump,
        ).joinToString(", ")

    /** Releases the row: PENDING when due, SCHEDULED when its run time lies ahead; the error is kept. */
    fun release(): String =
        listOf(
            "${c.status} = CASE WHEN ${c.runAt} > $now THEN '${TaskStatus.SCHEDULED.name}' " +
                "ELSE '${TaskStatus.PENDING.name}' END",
            "${c.finishedAt} = NULL",
            unlock,
            bump,
        ).joinToString(", ")

    companion object {
        const val ARGS = "move_arg"
        const val NOW = "move_now"
        const val REASON = "move_reason"

        /** The derived table of a JDBC statement: binds now, then the reason or null. */
        val BOUND_ARGS = "(SELECT CAST(? AS timestamptz) AS $NOW, CAST(? AS text) AS $REASON) AS $ARGS"

        /** The derived table of a generated function, with [reason] an SQL expression such as an argument. */
        fun functionArgs(reason: String = "NULL"): String =
            "(SELECT now() AS $NOW, CAST($reason AS text) AS $REASON) AS $ARGS"
    }
}
