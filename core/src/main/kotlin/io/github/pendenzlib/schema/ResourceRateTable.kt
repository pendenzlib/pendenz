package io.github.pendenzlib.schema

/**
 * Maps the resource rate table (ADR 0040): one row per resource name or
 * `LIKE` pattern, with how many tasks of it may be claimed within a
 * window. The application owns it and operators change it with SQL.
 * Names are used exactly as given, see [TaskTable].
 */
public class ResourceRateTable
    @JvmOverloads
    constructor(
        public val name: String = DEFAULT_NAME,
        public val columns: ResourceRateColumns = ResourceRateColumns(),
    ) {
        init {
            require(name.isNotBlank()) { "table name must not be blank" }
        }

        override fun toString(): String = "ResourceRateTable($name)"

        public companion object {
            public const val DEFAULT_NAME: String = "pendenz_resource_rate"
        }
    }

/**
 * @property name the resource name or pattern, the primary key.
 * @property maxStarts how many tasks of a matching resource may be claimed within [per].
 * @property per the length of the window, an `interval`.
 */
public class ResourceRateColumns
    @JvmOverloads
    constructor(
        public val name: String = "name",
        public val maxStarts: String = "max_starts",
        public val per: String = "per",
    ) {
        /** All column names, for a check that the table has them. */
        public val all: List<String> = listOf(name, maxStarts, per)

        init {
            require(all.all { it.isNotBlank() }) { "column names must not be blank" }
            require(all.toSet().size == all.size) { "column names must be distinct" }
        }
    }

/**
 * Maps the start table of the rate limits (ADR 0040): one row per claim
 * of a task whose resource has a rate, which the poll counts inside the
 * rate's window and the maintenance sweep deletes once it is older than
 * every window. Names are used exactly as given, see [TaskTable].
 */
public class ResourceStartTable
    @JvmOverloads
    constructor(
        public val name: String = DEFAULT_NAME,
        public val columns: ResourceStartColumns = ResourceStartColumns(),
    ) {
        init {
            require(name.isNotBlank()) { "table name must not be blank" }
        }

        override fun toString(): String = "ResourceStartTable($name)"

        public companion object {
            public const val DEFAULT_NAME: String = "pendenz_resource_start"
        }
    }

/**
 * @property resource the resource value of the claimed task.
 * @property startedAt when the task was claimed, on the worker's clock.
 */
public class ResourceStartColumns
    @JvmOverloads
    constructor(
        public val resource: String = "resource",
        public val startedAt: String = "started_at",
    ) {
        /** All column names, for a check that the table has them. */
        public val all: List<String> = listOf(resource, startedAt)

        init {
            require(all.all { it.isNotBlank() }) { "column names must not be blank" }
            require(all.toSet().size == all.size) { "column names must be distinct" }
        }
    }
