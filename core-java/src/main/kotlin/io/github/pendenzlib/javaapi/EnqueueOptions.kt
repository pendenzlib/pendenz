package io.github.pendenzlib.javaapi

import java.time.Instant

/**
 * The optional parts of an enqueue, for callers without named arguments.
 * Every setter returns new options and leaves these unchanged, so calls
 * chain and a shared base can be reused safely, also by `enqueueAsync`:
 * `EnqueueOptions.<Long>defaults().runAt(later).idempotencyKey("invoice-42")`.
 */
@Suppress("LongParameterList") // one property per optional part of an enqueue
public class EnqueueOptions<ID : Any> private constructor(
    internal val runAt: Instant? = null,
    internal val id: ID? = null,
    internal val idempotencyKey: String? = null,
    internal val createdBy: String? = null,
    internal val lane: String? = null,
    internal val priority: Int = 0,
    internal val resource: String? = null,
    internal val correlationId: String? = null,
) {
    /** When the task is due; now when not set. */
    public fun runAt(runAt: Instant): EnqueueOptions<ID> = copy(runAt = runAt)

    /** The id to store the task under; generated when not set. */
    public fun id(id: ID): EnqueueOptions<ID> = copy(id = id)

    /** A key unique per task type; a repeat with the same key returns the existing task's id. */
    public fun idempotencyKey(key: String): EnqueueOptions<ID> = copy(idempotencyKey = key)

    /** Who or what enqueues. */
    public fun createdBy(createdBy: String): EnqueueOptions<ID> = copy(createdBy = createdBy)

    /** The fairness group within the type, for example a tenant; lanes take turns at the workers. */
    public fun lane(lane: String): EnqueueOptions<ID> = copy(lane = lane)

    /** Higher runs first, strictly; 0 by default. */
    public fun priority(priority: Int): EnqueueOptions<ID> = copy(priority = priority)

    /** What the task runs against, for concurrency limits; the type's choice when not set. */
    public fun resource(resource: String): EnqueueOptions<ID> = copy(resource = resource)

    /** Ties the task and its follow-ups together. */
    public fun correlationId(correlationId: String): EnqueueOptions<ID> = copy(correlationId = correlationId)

    @Suppress("LongParameterList")
    private fun copy(
        runAt: Instant? = this.runAt,
        id: ID? = this.id,
        idempotencyKey: String? = this.idempotencyKey,
        createdBy: String? = this.createdBy,
        lane: String? = this.lane,
        priority: Int = this.priority,
        resource: String? = this.resource,
        correlationId: String? = this.correlationId,
    ) = EnqueueOptions(runAt, id, idempotencyKey, createdBy, lane, priority, resource, correlationId)

    public companion object {
        /** Options with nothing set. */
        @JvmStatic
        public fun <ID : Any> defaults(): EnqueueOptions<ID> = EnqueueOptions()
    }
}
