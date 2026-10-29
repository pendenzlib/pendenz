package io.github.pendenzlib

/**
 * How a task type names the resource its tasks run against (ADR 0017).
 * A resource is a text; a row in the resource limit table says how many
 * tasks of it may run at once. `enqueue` may override the type's choice.
 */
public sealed class TaskResource<in P : Any> {
    /** The resource of a task with this payload, or null for none. */
    public abstract fun resourceFor(payload: P): String?

    /** A limit the type wants seeded where the limit table has no row yet, or null. */
    public open val defaultLimit: ResourceLimit? = null

    /** No resource: the tasks run as the workers' parallelism allows. The default. */
    public object None : TaskResource<Any>() {
        override fun resourceFor(payload: Any): String? = null
    }

    /** Every task of the type runs against [name]; [defaultLimit] is seeded if given. */
    public class Fixed(
        public val name: String,
        maxRunning: Int? = null,
    ) : TaskResource<Any>() {
        init {
            require(name.isNotBlank()) { "resource name must not be blank" }
        }

        override val defaultLimit: ResourceLimit? = maxRunning?.let { ResourceLimit(name, it) }

        override fun resourceFor(payload: Any): String = name
    }

    /** The resource is derived from the payload, for example `"customer:${payload.customerId}"`. */
    public class FromPayload<P : Any>(
        private val resourceOf: (P) -> String?,
        pattern: String? = null,
        maxRunning: Int? = null,
    ) : TaskResource<P>() {
        init {
            require((pattern == null) == (maxRunning == null)) {
                "pattern and maxRunning go together: a limit to seed needs both, none needs neither"
            }
        }

        override val defaultLimit: ResourceLimit? =
            if (pattern != null && maxRunning != null) ResourceLimit(pattern, maxRunning) else null

        override fun resourceFor(payload: P): String? = resourceOf(payload)
    }

    public companion object {
        @JvmStatic
        public fun none(): TaskResource<Any> = None

        /** A shared resource such as `"mail-server"`, with an optional limit to seed. */
        @JvmStatic
        @JvmOverloads
        public fun fixed(
            name: String,
            maxRunning: Int? = null,
        ): TaskResource<Any> = Fixed(name, maxRunning)

        /**
         * A resource per payload, with an optional [pattern] and limit to seed,
         * for example `fromPayload({ "customer:${it.id}" }, "customer:%", 2)`.
         */
        @JvmStatic
        @JvmOverloads
        public fun <P : Any> fromPayload(
            resourceOf: (P) -> String?,
            pattern: String? = null,
            maxRunning: Int? = null,
        ): TaskResource<P> = FromPayload(resourceOf, pattern, maxRunning)
    }
}

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
