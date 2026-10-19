package io.github.d4niella.pendenz.javaapi

import java.time.Instant

/**
 * The optional parts of an enqueue, for callers without named arguments.
 * Every setter returns the options, so calls chain:
 * `EnqueueOptions.<Long>none().runAt(later).idempotencyKey("invoice-42")`.
 */
public class EnqueueOptions<ID : Any> private constructor() {
    internal var runAt: Instant? = null
        private set
    internal var id: ID? = null
        private set
    internal var idempotencyKey: String? = null
        private set
    internal var createdBy: String? = null
        private set

    /** When the task is due; now when not set. */
    public fun runAt(runAt: Instant): EnqueueOptions<ID> = apply { this.runAt = runAt }

    /** The id to store the task under; generated when not set. */
    public fun id(id: ID): EnqueueOptions<ID> = apply { this.id = id }

    /** A key unique per task type; a repeat with the same key returns the existing task's id. */
    public fun idempotencyKey(key: String): EnqueueOptions<ID> = apply { this.idempotencyKey = key }

    /** Who or what enqueues. */
    public fun createdBy(createdBy: String): EnqueueOptions<ID> = apply { this.createdBy = createdBy }

    public companion object {
        /** Options with nothing set. */
        @JvmStatic
        public fun <ID : Any> none(): EnqueueOptions<ID> = EnqueueOptions()
    }
}
