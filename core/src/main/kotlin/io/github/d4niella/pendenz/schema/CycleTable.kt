package io.github.d4niella.pendenz.schema

/**
 * Maps the concepts a cycle needs onto a table the application owns (ADR
 * 0016). The id type is the task table's: a cycle's id, as text, is the
 * lane of its tasks.
 *
 * @property idType the type of the id column, the same as the task table's.
 * @property name the table, optionally schema-qualified.
 * @property columns the column names for each concept.
 */
public class CycleTable<ID : Any>
    @JvmOverloads
    constructor(
        public val idType: IdType<ID>,
        public val name: String = DEFAULT_NAME,
        public val columns: CycleColumns = CycleColumns(),
    ) {
        init {
            require(name.isNotBlank()) { "table name must not be blank" }
        }

        override fun toString(): String = "CycleTable($name, $idType)"

        public companion object {
            public const val DEFAULT_NAME: String = "pendenz_cycle"

            /** A mapping with `bigint` ids, the default. */
            public operator fun invoke(
                name: String = DEFAULT_NAME,
                columns: CycleColumns = CycleColumns(),
            ): CycleTable<Long> = CycleTable(IdType.BIGINT, name, columns)
        }
    }

/**
 * Column names for the concepts a cycle needs.
 *
 * @property id primary key.
 * @property type the task type of the cycle's tasks.
 * @property status one of the [io.github.d4niella.pendenz.CycleStatus] names.
 * @property description what the run is, for operators.
 * @property createdAt when the cycle was started.
 * @property createdBy who or what started it.
 * @property finishedAt when it was finished or cancelled.
 * @property version optimistic lock, incremented on every write.
 */
@Suppress("LongParameterList") // one parameter per mapped concept, by design
public class CycleColumns(
    public val id: String = "id",
    public val type: String = "task_type",
    public val status: String = "status",
    public val description: String = "description",
    public val createdAt: String = "created_at",
    public val createdBy: String = "created_by",
    public val finishedAt: String = "finished_at",
    public val version: String = "version",
) {
    public val all: List<String> = listOf(id, type, status, description, createdAt, createdBy, finishedAt, version)

    init {
        all.forEach { require(it.isNotBlank()) { "column names must not be blank" } }
        require(all.toSet().size == all.size) { "column names must be distinct, got $all" }
    }
}
