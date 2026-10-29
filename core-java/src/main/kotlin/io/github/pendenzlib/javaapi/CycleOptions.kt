package io.github.pendenzlib.javaapi

import io.github.pendenzlib.CycleResource

/**
 * The optional parts of starting a cycle, for callers without named
 * arguments; immutable like [EnqueueOptions]:
 * `CycleOptions.<Long>defaults().description("October invoices").createdBy("ops")`.
 */
@Suppress("LongParameterList") // one property per optional part of a cycle
public class CycleOptions<ID : Any> private constructor(
    internal val description: String? = null,
    internal val createdBy: String? = null,
    internal val priority: Int = 0,
    internal val id: ID? = null,
    internal val resource: CycleResource = CycleResource.Default,
    internal val correlationId: String? = null,
) {
    /** What the cycle is for, shown in its row and progress view. */
    public fun description(description: String): CycleOptions<ID> = copy(description = description)

    /** Who or what starts the cycle; it may not approve it. */
    public fun createdBy(createdBy: String): CycleOptions<ID> = copy(createdBy = createdBy)

    /** The priority of every task of the cycle; 0 by default. */
    public fun priority(priority: Int): CycleOptions<ID> = copy(priority = priority)

    /** The id to store the cycle under; generated when not set. */
    public fun id(id: ID): CycleOptions<ID> = copy(id = id)

    /** What the cycle's tasks run against, for concurrency limits; the type's choice when not set. */
    public fun resource(resource: CycleResource): CycleOptions<ID> = copy(resource = resource)

    /** Ties the cycle's tasks and their follow-ups together. */
    public fun correlationId(correlationId: String): CycleOptions<ID> = copy(correlationId = correlationId)

    @Suppress("LongParameterList")
    private fun copy(
        description: String? = this.description,
        createdBy: String? = this.createdBy,
        priority: Int = this.priority,
        id: ID? = this.id,
        resource: CycleResource = this.resource,
        correlationId: String? = this.correlationId,
    ) = CycleOptions(description, createdBy, priority, id, resource, correlationId)

    public companion object {
        /** Options with nothing set. */
        @JvmStatic
        public fun <ID : Any> defaults(): CycleOptions<ID> = CycleOptions()
    }
}
