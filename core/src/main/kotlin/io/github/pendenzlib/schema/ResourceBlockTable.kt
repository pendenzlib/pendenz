package io.github.pendenzlib.schema

/**
 * Maps the resource block table (ADR 0035): one row per resource name or
 * `LIKE` pattern whose tasks are not claimed before a given time. The
 * application owns it and operators change it with SQL. Names are used
 * exactly as given, see [TaskTable].
 */
public class ResourceBlockTable
    @JvmOverloads
    constructor(
        public val name: String = DEFAULT_NAME,
        public val columns: ResourceBlockColumns = ResourceBlockColumns(),
    ) {
        init {
            require(name.isNotBlank()) { "table name must not be blank" }
        }

        override fun toString(): String = "ResourceBlockTable($name)"

        public companion object {
            public const val DEFAULT_NAME: String = "pendenz_resource_block"
        }
    }

/**
 * @property name the resource name or pattern, the primary key.
 * @property blockedUntil until when matching resources take no tasks.
 * @property reason why, as the handler or the operator gave it.
 * @property blockedAt when the block was last set or extended.
 */
public class ResourceBlockColumns
    @JvmOverloads
    constructor(
        public val name: String = "name",
        public val blockedUntil: String = "blocked_until",
        public val reason: String = "reason",
        public val blockedAt: String = "blocked_at",
    ) {
        init {
            val all = listOf(name, blockedUntil, reason, blockedAt)
            require(all.all { it.isNotBlank() }) { "column names must not be blank" }
            require(all.toSet().size == all.size) { "column names must be distinct" }
        }
    }
