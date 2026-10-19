package io.github.d4niella.pendenz.schema

/**
 * Maps the concepts Pendenz needs onto a table the application owns.
 *
 * The library never assumes a table or column name. Everything is read from
 * this mapping, so the application may add columns, foreign keys and indexes
 * of its own, and may keep several task tables for different domains.
 *
 * `TaskTable(name = ...)` builds a mapping with `bigint` ids; pass an
 * [IdType] for anything else.
 *
 * @param ID the type of the id column, see [IdType].
 * @property idType the type of the id column and who generates ids.
 * @property name the table, optionally schema-qualified as `schema.table`.
 * @property columns the column names for each concept.
 */
public class TaskTable<ID : Any>
    @JvmOverloads
    constructor(
        public val idType: IdType<ID>,
        public val name: String = DEFAULT_NAME,
        public val columns: TaskColumns = TaskColumns(),
    ) {
        init {
            require(name.isNotBlank()) { "table name must not be blank" }
        }

        override fun toString(): String = "TaskTable($name, $idType)"

        public companion object {
            public const val DEFAULT_NAME: String = "pendenz_task"

            /** A mapping with `bigint` ids, the default. */
            public operator fun invoke(
                name: String = DEFAULT_NAME,
                columns: TaskColumns = TaskColumns(),
            ): TaskTable<Long> = TaskTable(IdType.BIGINT, name, columns)
        }
    }

/**
 * Column names for the concepts Pendenz needs. Every concept must map to a
 * column; the names are the application's choice.
 *
 * @property id primary key, see [IdType].
 * @property type the registered task type name.
 * @property status one of the [io.github.d4niella.pendenz.TaskStatus] names.
 * @property payload the encoded payload, `jsonb` by default.
 * @property runAt when the task is due.
 * @property attempts how many times a worker has claimed the task.
 * @property lastError message of the most recent failure, null when none.
 * @property lockedBy id of the worker that holds the task, null when none.
 * @property lockedAt last heartbeat of that worker, null when none.
 * @property version optimistic lock, incremented on every write.
 * @property createdAt when the row was inserted.
 * @property idempotencyKey the caller's key, unique per task type, null when none. See ADR 0009.
 * @property createdBy who or what enqueued the task, null when not given.
 */
@Suppress("LongParameterList") // one parameter per mapped concept, by design
public class TaskColumns(
    public val id: String = "id",
    public val type: String = "task_type",
    public val status: String = "status",
    public val payload: String = "payload",
    public val runAt: String = "run_at",
    public val attempts: String = "attempts",
    public val lastError: String = "last_error",
    public val lockedBy: String = "locked_by",
    public val lockedAt: String = "locked_at",
    public val version: String = "version",
    public val createdAt: String = "created_at",
    public val idempotencyKey: String = "idempotency_key",
    public val createdBy: String = "created_by",
) {
    /** All column names, used to check the mapping for duplicates. */
    public val all: List<String> =
        listOf(
            id,
            type,
            status,
            payload,
            runAt,
            attempts,
            lastError,
            lockedBy,
            lockedAt,
            version,
            createdAt,
            idempotencyKey,
            createdBy,
        )

    init {
        all.forEach { require(it.isNotBlank()) { "column names must not be blank" } }
        require(all.toSet().size == all.size) { "column names must be distinct, got $all" }
    }
}
