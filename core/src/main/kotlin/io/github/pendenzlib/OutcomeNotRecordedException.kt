package io.github.pendenzlib

import io.github.pendenzlib.store.Outcome
import java.sql.SQLException

/**
 * What a retry policy sees as the cause when a handler ended without
 * failing, but its outcome could not be written: a follow-up that
 * violates a constraint, a cycle query that fails, a result the table
 * refuses. The attempt counts as failed; the handler's result and its
 * follow-ups are not stored (ADR 0048).
 *
 * The message names the outcome that was intended and the first line of
 * what the write reported, which for a constraint violation names the
 * constraint and leaves out the values of the row. The whole exception of
 * the write is the [cause], for a listener or an [ErrorDescription].
 *
 * @property refused true when the database refused the statement: a SQL state of class 22, 23 or 42. Running
 *   the handler again then ends in the same refusal until its cause is removed, and [ExponentialBackoff] gives
 *   up. False for any other failure, such as a connection pool that stayed exhausted.
 */
public class OutcomeNotRecordedException(
    message: String,
    @get:JvmName("isRefused") public val refused: Boolean,
    cause: Throwable,
) : RuntimeException(message, cause) {
    internal companion object {
        /** The exception for [intended], an outcome that is no failure, whose write ended with [failure]. */
        fun of(
            intended: Outcome,
            failure: Throwable,
        ): OutcomeNotRecordedException {
            val what =
                when (intended) {
                    is Outcome.Succeeded -> TaskStatus.SUCCEEDED.name
                    is Outcome.Rejected -> TaskStatus.REJECTED.name
                    is Outcome.HandedOver -> TaskStatus.QUARANTINED.name
                    is Outcome.Deferred -> "deferral"
                    is Outcome.Retry -> TaskStatus.RETRY_PENDING.name
                    is Outcome.Failed -> TaskStatus.FAILED.name
                }
            val reported = RefusedWrite.reported(failure)
            val firstLine =
                reported.message
                    .orEmpty()
                    .lineSequence()
                    .first()
            val message = "outcome $what could not be recorded: ${reported.javaClass.name}: $firstLine"
            return OutcomeNotRecordedException(message, RefusedWrite.isRefused(failure), failure)
        }
    }
}

/**
 * Tells a write the database refused from one that failed for another
 * reason (ADR 0048). Refused means a [SQLException] in the cause chain
 * whose SQL state is of class 22 (data exception), 23 (integrity
 * constraint violation) or 42 (syntax error or access rule violation):
 * the classes of the SQL standard, so no dialect is asked.
 */
internal object RefusedWrite {
    private const val CHAIN_LIMIT = 20
    private const val CLASS_LENGTH = 2
    private val REFUSED_CLASSES = setOf("22", "23", "42")

    fun isRefused(failure: Throwable): Boolean =
        sqlExceptions(failure).any { it.sqlState?.take(CLASS_LENGTH) in REFUSED_CLASSES }

    /**
     * What the database reported: the innermost [SQLException] of the chain,
     * since a batch wraps the server's error in one that quotes the
     * statement. [failure] itself when the chain has none.
     */
    fun reported(failure: Throwable): Throwable = sqlExceptions(failure).lastOrNull() ?: failure

    private fun sqlExceptions(failure: Throwable): List<SQLException> =
        generateSequence(failure) { it.cause }.take(CHAIN_LIMIT).filterIsInstance<SQLException>().toList()
}
