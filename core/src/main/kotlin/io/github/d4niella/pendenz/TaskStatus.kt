package io.github.d4niella.pendenz

/**
 * Lifecycle state of a task.
 *
 * The status model is part of the public schema contract: the names are stored
 * verbatim in the user's status column and are read by user interfaces built on
 * that schema. See ADR 0002.
 *
 * @property kind who is expected to act next while a task is in this state.
 */
public enum class TaskStatus(
    public val kind: Kind,
) {
    /** Enqueued and due. Waiting to be claimed by a worker. */
    PENDING(Kind.ACTIVE),

    /** Enqueued with a run time in the future. Becomes [PENDING] when due. */
    SCHEDULED(Kind.ACTIVE),

    /** The task type requires a human decision before the task may run. */
    AWAITING_APPROVAL(Kind.WAITING_FOR_HUMAN),

    /** Claimed by a worker that is sending heartbeats. */
    RUNNING(Kind.ACTIVE),

    /** Handler finished and the result was accepted. */
    SUCCEEDED(Kind.FINAL),

    /** Handler threw, attempts remain. Becomes [PENDING] once the backoff elapsed. */
    RETRY_PENDING(Kind.ACTIVE),

    /** Attempts exhausted. Stays until a human restarts, completes or cancels it. */
    FAILED(Kind.WAITING_FOR_HUMAN),

    /** Handler finished, but the result was refused by the task type's validation. */
    REJECTED(Kind.WAITING_FOR_HUMAN),

    /** Deliberately taken out of circulation, with a reason, until a human decides. */
    QUARANTINED(Kind.WAITING_FOR_HUMAN),

    /** Will never run. */
    CANCELLED(Kind.FINAL),
    ;

    /** Convenience for [Kind.FINAL]. */
    public val isFinal: Boolean
        get() = kind == Kind.FINAL

    /** Who acts next while a task is in a given state. */
    public enum class Kind {
        /** The library moves the task on by itself: scheduling, claiming, retrying. */
        ACTIVE,

        /** Nothing happens until a person restarts, completes, cancels or releases the task. */
        WAITING_FOR_HUMAN,

        /** Nothing happens any more, except retention. */
        FINAL,
    }
}
