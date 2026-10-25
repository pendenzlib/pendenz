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
 * Every name, of the table, its schema and its columns, is used exactly as
 * given: the library quotes all identifiers, so `Status` and `status` are
 * two columns. Lower case keeps hand-written SQL free of quotes, since
 * PostgreSQL folds unquoted names to lower case.
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
 * column; the names are the application's choice. Payload columns are not
 * here: each task type declares its own, see
 * [io.github.d4niella.pendenz.PayloadMapping].
 *
 * @property id primary key, see [IdType].
 * @property type the registered task type name.
 * @property status one of the [io.github.d4niella.pendenz.TaskStatus] names.
 * @property runAt when the task is due.
 * @property attempts how many times a worker has claimed the task.
 * @property lastError message of the most recent failure, null when none.
 * @property lockedBy id of the worker that holds the task, null when none.
 * @property lockedUntil until when that worker holds its lock, moved on by each heartbeat, null when none.
 *   See ADR 0027.
 * @property version optimistic lock, incremented on every write.
 * @property createdAt when the row was inserted.
 * @property idempotencyKey the caller's key, unique per task type, null when none. See ADR 0009.
 * @property createdBy who or what enqueued the task, null when not given.
 * @property payloadVersion the schema version of the payload, see ADR 0010.
 * @property finishedAt when the task reached SUCCEEDED or FAILED, null while it has not. See ADR 0011.
 * @property lane the fairness group within a type, null for single tasks. See ADR 0015.
 * @property priority higher runs first, 0 by default. See ADR 0015.
 * @property resource what the task runs against, for concurrency limits; null for none. See ADR 0017.
 * @property approvedBy who approved the task, null while nobody has. See ADR 0018.
 * @property approvedAt when it was approved.
 * @property parentId the task whose handler enqueued this one, null for a root. See ADR 0019.
 * @property correlationId ties a chain of tasks together; given at enqueue, inherited by follow-ups.
 * @property result what the handler produced, as JSON, null when none. See ADR 0020.
 * @property provenance what produced the result, key-value pairs as JSON.
 * @property preview what running the task would do, computed at enqueue for approval screens.
 */
@Suppress("LongParameterList") // one parameter per mapped concept, by design
public class TaskColumns(
    public val id: String = "id",
    public val type: String = "task_type",
    public val status: String = "status",
    public val runAt: String = "run_at",
    public val attempts: String = "attempts",
    public val lastError: String = "last_error",
    public val lockedBy: String = "locked_by",
    public val lockedUntil: String = "locked_until",
    public val version: String = "version",
    public val createdAt: String = "created_at",
    public val idempotencyKey: String = "idempotency_key",
    public val createdBy: String = "created_by",
    public val payloadVersion: String = "payload_version",
    public val finishedAt: String = "finished_at",
    public val lane: String = "lane",
    public val priority: String = "priority",
    public val resource: String = "resource",
    public val approvedBy: String = "approved_by",
    public val approvedAt: String = "approved_at",
    public val parentId: String = "parent_id",
    public val correlationId: String = "correlation_id",
    public val result: String = "result",
    public val provenance: String = "provenance",
    public val preview: String = "preview",
) {
    /** All column names, used to check the mapping for duplicates. */
    public val all: List<String> =
        listOf(
            id,
            type,
            status,
            runAt,
            attempts,
            lastError,
            lockedBy,
            lockedUntil,
            version,
            createdAt,
            idempotencyKey,
            createdBy,
            payloadVersion,
            finishedAt,
            lane,
            priority,
            resource,
            approvedBy,
            approvedAt,
            parentId,
            correlationId,
            result,
            provenance,
            preview,
        )

    init {
        all.forEach { require(it.isNotBlank()) { "column names must not be blank" } }
        require(all.toSet().size == all.size) { "column names must be distinct, got $all" }
    }
}
