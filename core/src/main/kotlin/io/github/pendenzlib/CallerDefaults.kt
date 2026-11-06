package io.github.pendenzlib

/**
 * Where the queue finds the caller's identity and correlation id when a
 * call leaves them out: the authenticated user and the trace id of the
 * current request, for example. Registered with
 * [Pendenz.Builder.callerDefaults]. See ADR 0036.
 *
 * [actor] fills `createdBy` of `enqueue` and `startCycle` and `actor` of
 * the operator moves and cycle moves; [correlationId] fills
 * `correlationId` of `enqueue` and `startCycle`. A value passed in the
 * call is never replaced. `approve`, `approveCycle` and `decline` require
 * their actor and do not ask. The filled actor is the one the permission
 * check sees and the one stored.
 *
 * Both are read on the caller's thread when the call starts, before the
 * queue opens a transaction or switches thread, so thread-local state is
 * visible. Follow-ups and schedule occurrences, enqueued inside the
 * worker, do not ask. An exception thrown here reaches the caller, and the
 * operation does not happen.
 */
public interface CallerDefaults {
    /** The identity of the current caller, or null when there is none. */
    public fun actor(): String? = null

    /** The correlation id of the current call, or null when there is none. */
    public fun correlationId(): String? = null

    public companion object {
        /** Supplies nothing; calls keep what they pass. The default of every queue. */
        @JvmField
        public val NONE: CallerDefaults = object : CallerDefaults {}
    }
}
