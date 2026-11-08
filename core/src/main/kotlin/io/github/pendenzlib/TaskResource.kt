package io.github.pendenzlib

import java.time.Duration

/**
 * How a task type names the resource its tasks run against (ADR 0017).
 * A resource is a text; a row in the resource limit table says how many
 * tasks of it may run at once, a row in the rate table how many may start
 * per window (ADR 0040). `enqueue` may override the type's choice.
 */
public sealed class TaskResource<in P : Any> {
    /** The resource of a task with this payload, or null for none. */
    public abstract fun resourceFor(payload: P): String?

    /** A limit the type wants seeded where the limit table has no row yet, or null. */
    public open val defaultLimit: ResourceLimit? = null

    /** A rate the type wants seeded where the rate table has no row yet, or null (ADR 0040). */
    public open val defaultRate: ResourceRate? = null

    /** No resource: the tasks run as the workers' parallelism allows. The default. */
    public object None : TaskResource<Any>() {
        override fun resourceFor(payload: Any): String? = null
    }

    /** Every task of the type runs against [name]; [defaultLimit] and [defaultRate] are seeded if given. */
    public class Fixed
        @JvmOverloads
        constructor(
            public val name: String,
            maxRunning: Int? = null,
            maxStarts: Int? = null,
            per: Duration? = null,
        ) : TaskResource<Any>() {
            init {
                require(name.isNotBlank()) { "resource name must not be blank" }
                requireRatePair(maxStarts, per)
            }

            override val defaultLimit: ResourceLimit? = maxRunning?.let { ResourceLimit(name, it) }

            override val defaultRate: ResourceRate? = rateOf(name, maxStarts, per)

            override fun resourceFor(payload: Any): String = name
        }

    /** The resource is derived from the payload, for example `"customer:${payload.customerId}"`. */
    public class FromPayload<P : Any>
        @JvmOverloads
        constructor(
            private val resourceOf: (P) -> String?,
            pattern: String? = null,
            maxRunning: Int? = null,
            maxStarts: Int? = null,
            per: Duration? = null,
        ) : TaskResource<P>() {
            init {
                requireRatePair(maxStarts, per)
                require((pattern == null) == (maxRunning == null && maxStarts == null)) {
                    "a pattern goes with a limit or a rate to seed: either needs the pattern, the pattern needs one"
                }
            }

            override val defaultLimit: ResourceLimit? =
                if (pattern != null && maxRunning != null) ResourceLimit(pattern, maxRunning) else null

            override val defaultRate: ResourceRate? = pattern?.let { rateOf(it, maxStarts, per) }

            override fun resourceFor(payload: P): String? = resourceOf(payload)
        }

    public companion object {
        @JvmStatic
        public fun none(): TaskResource<Any> = None

        /**
         * A shared resource such as `"mail-server"`, with an optional limit and an optional rate to seed;
         * [maxStarts] and [per] go together.
         */
        @JvmStatic
        @JvmOverloads
        public fun fixed(
            name: String,
            maxRunning: Int? = null,
            maxStarts: Int? = null,
            per: Duration? = null,
        ): TaskResource<Any> = Fixed(name, maxRunning, maxStarts, per)

        /**
         * A resource per payload, with an optional [pattern] and a limit or a rate to seed for it,
         * for example `fromPayload({ "customer:${it.id}" }, "customer:%", 2)`; [maxStarts] and [per] go together.
         */
        @JvmStatic
        @JvmOverloads
        public fun <P : Any> fromPayload(
            resourceOf: (P) -> String?,
            pattern: String? = null,
            maxRunning: Int? = null,
            maxStarts: Int? = null,
            per: Duration? = null,
        ): TaskResource<P> = FromPayload(resourceOf, pattern, maxRunning, maxStarts, per)
    }
}

private fun requireRatePair(
    maxStarts: Int?,
    per: Duration?,
) = require((maxStarts == null) == (per == null)) {
    "maxStarts and per go together: a rate to seed needs both, none needs neither"
}

private fun rateOf(
    name: String,
    maxStarts: Int?,
    per: Duration?,
): ResourceRate? = if (maxStarts != null && per != null) ResourceRate(name, maxStarts, per) else null

private const val NANOS_PER_MILLI = 1_000_000

/** A row of the resource limit table: how many tasks of resources matching [name] may run at once. */
public class ResourceLimit(
    /** An exact resource name or a `LIKE` pattern such as `customer:%`. */
    public val name: String,
    public val maxRunning: Int,
) {
    init {
        require(name.isNotBlank()) { "resource name must not be blank" }
        require(maxRunning >= 0) { "maxRunning must not be negative" }
    }

    override fun equals(other: Any?): Boolean =
        other is ResourceLimit && other.name == name && other.maxRunning == maxRunning

    override fun hashCode(): Int = name.hashCode() * 31 + maxRunning

    override fun toString(): String = "ResourceLimit($name <= $maxRunning)"
}

/**
 * A row of the resource rate table: at most [maxStarts] tasks of resources
 * matching [name] are claimed within any window of length [per] (ADR 0040).
 */
public class ResourceRate(
    /** An exact resource name or a `LIKE` pattern such as `partner-api:%`. */
    public val name: String,
    public val maxStarts: Int,
    public val per: Duration,
) {
    init {
        require(name.isNotBlank()) { "resource name must not be blank" }
        require(maxStarts >= 1) { "maxStarts must be at least 1; a limit of 0 or a block pauses a resource" }
        require(per.toMillis() >= 1) { "per must be at least 1 ms, was $per" }
        require(per.nano % NANOS_PER_MILLI == 0) { "per must be whole milliseconds, was $per" }
    }

    override fun equals(other: Any?): Boolean =
        other is ResourceRate && other.name == name && other.maxStarts == maxStarts && other.per == per

    override fun hashCode(): Int = (name.hashCode() * 31 + maxStarts) * 31 + per.hashCode()

    override fun toString(): String = "ResourceRate($name <= $maxStarts per $per)"
}
