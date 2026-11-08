package io.github.pendenzlib

/**
 * What an enqueue does when its idempotency key is already taken by a task
 * of the same type (ADR 0009). A property of the task type, see
 * [TaskType.onDuplicateKey] and ADR 0044.
 */
public enum class OnDuplicateKey {
    /** The first enqueue wins; the repeat stores nothing. The default. */
    KEEP,

    /**
     * While the existing task is PENDING or SCHEDULED, the repeat replaces
     * its payload, payload version, run time, lane, priority, resource and
     * preview, and its status follows from the new values as on a first
     * enqueue, approval included. In any other status the repeat stores
     * nothing, as with [KEEP]. Schedule occurrences always keep.
     */
    REPLACE,
}
