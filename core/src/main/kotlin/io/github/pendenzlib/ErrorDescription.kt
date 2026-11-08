package io.github.pendenzlib

/**
 * The text stored in `last_error` when an attempt fails: on a retry and
 * on FAILED. Registered with [Pendenz.Builder.errorDescription], once per
 * queue. See ADR 0043.
 *
 * Every exception that becomes `last_error` is described here: what the
 * handler threw, its timeout, a payload that cannot be decoded or
 * upgraded, and the [LockExpiredException] of a task whose worker died.
 * Reasons given on purpose, for a rejection, a deferral, a hand-over or by
 * an operator, are stored as given and not described.
 *
 * The text is stored as returned, without a length limit. A description
 * that throws is logged, and the attempt is recorded with the exception's
 * class name only. The listener still receives the exception itself.
 */
public fun interface ErrorDescription {
    /** The text to store for [error], which ended an attempt of the task in [context]. */
    public fun describe(
        context: TaskContext<*>,
        error: Throwable,
    ): String

    public companion object {
        /** The class name and the message, `Throwable.toString()`. The default of every queue. */
        @JvmField
        public val DEFAULT: ErrorDescription = ErrorDescription { _, error -> error.toString() }
    }
}
