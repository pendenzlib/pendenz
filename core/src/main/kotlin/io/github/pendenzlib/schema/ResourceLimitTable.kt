package io.github.pendenzlib.schema

/**
 * Maps the resource limit table (ADR 0017): one row per resource name or
 * `LIKE` pattern, with the number of tasks that may run at once. The
 * application owns it and operators change it with SQL. Names are used
 * exactly as given, see [TaskTable].
 */
public class ResourceLimitTable
    @JvmOverloads
    constructor(
        public val name: String = DEFAULT_NAME,
        public val columns: ResourceLimitColumns = ResourceLimitColumns(),
    ) {
        init {
            require(name.isNotBlank()) { "table name must not be blank" }
        }

        override fun toString(): String = "ResourceLimitTable($name)"

        public companion object {
            public const val DEFAULT_NAME: String = "pendenz_resource_limit"
        }
    }

/**
 * @property name the resource name or pattern, the primary key.
 * @property maxRunning how many tasks of matching resources may run at once.
 */
public class ResourceLimitColumns(
    public val name: String = "name",
    public val maxRunning: String = "max_running",
) {
    init {
        require(name.isNotBlank() && maxRunning.isNotBlank()) { "column names must not be blank" }
        require(name != maxRunning) { "column names must be distinct" }
    }
}
