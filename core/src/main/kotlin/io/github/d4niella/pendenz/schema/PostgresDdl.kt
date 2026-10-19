package io.github.d4niella.pendenz.schema

import io.github.d4niella.pendenz.CycleStatus
import io.github.d4niella.pendenz.JsonPayload
import io.github.d4niella.pendenz.PayloadColumn
import io.github.d4niella.pendenz.SqlType
import io.github.d4niella.pendenz.TaskStatus
import io.github.d4niella.pendenz.TaskType

/**
 * Generates PostgreSQL DDL for a [TaskTable] mapping.
 *
 * Pendenz does not run migrations. Copy the output into your Flyway or
 * Liquibase migration and adapt it: add columns, foreign keys, indexes.
 * Only the mapped columns and their types matter to the library.
 */
public object PostgresDdl {
    /**
     * `CREATE TABLE` with the queue's columns and the payload columns of
     * [types], plus the index the claim query relies on and the unique index
     * for idempotency keys. Without types the table gets the default JSON
     * payload column.
     */
    @JvmStatic
    public fun createTable(
        table: TaskTable<*>,
        vararg types: TaskType<*>,
    ): String = createTable(table, payloadColumnsOf(types.toList()))

    /**
     * A trigger that announces every task that becomes `PENDING`, by insert
     * or by status change, on the channel [notifyChannelFor] names, with the
     * task type as the payload. Opt-in: run it beside [createTable] when
     * workers use `pendenz-postgres-notify`. See ADR 0014.
     */
    @JvmStatic
    public fun notifyTrigger(table: TaskTable<*>): String {
        val c = table.columns
        val t = SqlIdentifiers.table(table.name)
        val function = SqlIdentifiers.column(table.name.substringAfterLast('.') + "_notify")
        val channel = notifyChannelFor(table)
        return """
            |CREATE FUNCTION $function() RETURNS trigger AS ${'$'}${'$'}
            |BEGIN
            |    PERFORM pg_notify('$channel', NEW.${SqlIdentifiers.column(c.type)});
            |    RETURN NULL;
            |END ${'$'}${'$'} LANGUAGE plpgsql;
            |
            |CREATE TRIGGER $function
            |    AFTER INSERT OR UPDATE OF ${SqlIdentifiers.column(c.status)} ON $t
            |    FOR EACH ROW WHEN (NEW.${SqlIdentifiers.column(c.status)} = '${TaskStatus.PENDING.name}')
            |    EXECUTE FUNCTION $function();
            """.trimMargin()
    }

    /** `CREATE TABLE` for the resource limit table (ADR 0017). */
    @JvmStatic
    public fun createResourceLimitTable(table: ResourceLimitTable): String {
        val c = table.columns
        val t = SqlIdentifiers.table(table.name)
        return """
            |CREATE TABLE $t (
            |    ${SqlIdentifiers.column(c.name)} varchar(200) PRIMARY KEY,
            |    ${SqlIdentifiers.column(
            c.maxRunning,
        )} integer NOT NULL CHECK (${SqlIdentifiers.column(c.maxRunning)} >= 0)
            |);
            """.trimMargin()
    }

    /** `CREATE TABLE` for a cycle table (ADR 0016). */
    @JvmStatic
    public fun createCycleTable(table: CycleTable<*>): String {
        val c = table.columns
        val t = SqlIdentifiers.table(table.name)

        fun col(name: String) = SqlIdentifiers.column(name)
        val statusCheck = CycleStatus.entries.joinToString(", ") { "'${it.name}'" }
        return """
            |CREATE TABLE $t (
            |    ${col(c.id)} ${table.idType.columnDefinition} PRIMARY KEY,
            |    ${col(c.type)} varchar(200) NOT NULL,
            |    ${col(c.status)} varchar(32) NOT NULL CHECK (${col(c.status)} IN ($statusCheck)),
            |    ${col(c.description)} text,
            |    ${col(c.createdAt)} timestamptz NOT NULL DEFAULT now(),
            |    ${col(c.createdBy)} varchar(200),
            |    ${col(c.finishedAt)} timestamptz,
            |    ${col(c.version)} bigint NOT NULL DEFAULT 0
            |);
            """.trimMargin()
    }

    /**
     * A view with one row per cycle and a count per task status, plus the
     * oldest pending and the newest finished task. Named after the cycle
     * table with `_progress` appended.
     */
    @JvmStatic
    public fun cycleProgressView(
        table: TaskTable<*>,
        cycleTable: CycleTable<*>,
    ): String {
        val c = table.columns
        val cc = cycleTable.columns
        val t = SqlIdentifiers.table(table.name)
        val ct = SqlIdentifiers.table(cycleTable.name)
        val view = SqlIdentifiers.table(cycleTable.name + "_progress")

        fun col(name: String) = SqlIdentifiers.column(name)
        val counts =
            TaskStatus.entries.joinToString(",\n|    ") {
                "count(*) FILTER (WHERE task.${col(c.status)} = '${it.name}') AS ${col(it.name.lowercase())}"
            }
        return """
            |CREATE VIEW $view AS
            |SELECT cycle.${col(cc.id)}, cycle.${col(cc.type)}, cycle.${col(cc.status)}, cycle.${col(cc.description)},
            |    cycle.${col(cc.createdAt)}, cycle.${col(cc.createdBy)}, cycle.${col(cc.finishedAt)},
            |    count(task.${col(c.id)}) AS tasks,
            |    $counts,
            |    min(task.${col(c.runAt)}) FILTER (WHERE task.${col(c.status)} = 'PENDING') AS oldest_pending,
            |    max(task.${col(c.finishedAt)}) AS newest_finished
            |FROM $ct AS cycle
            |LEFT JOIN $t AS task
            |    ON task.${col(c.lane)} = cycle.${col(cc.id)}::text AND task.${col(c.type)} = cycle.${col(cc.type)}
            |GROUP BY cycle.${col(cc.id)};
            """.trimMargin()
    }

    /** `CREATE TABLE` for the queue's columns and the given payload columns. */
    @JvmStatic
    public fun createTable(
        table: TaskTable<*>,
        payloadColumns: Collection<PayloadColumn>,
    ): String {
        val c = table.columns
        val t = SqlIdentifiers.table(table.name)

        fun col(name: String) = SqlIdentifiers.column(name)
        val statusCheck = TaskStatus.entries.joinToString(", ") { "'${it.name}'" }
        val bareName = table.name.substringAfterLast('.')
        val claimIndex = SqlIdentifiers.column(bareName + "_claim_idx")
        val keyIndex = SqlIdentifiers.column(bareName + "_idempotency_idx")
        val resourceIndex = SqlIdentifiers.column(bareName + "_resource_idx")
        val payload = payloadColumns.joinToString("") { ",\n|    ${col(it.name)} ${it.type.ddl}" }
        return """
            |CREATE TABLE $t (
            |    ${col(c.id)} ${table.idType.columnDefinition} PRIMARY KEY,
            |    ${col(c.type)} varchar(200) NOT NULL,
            |    ${col(c.status)} varchar(32) NOT NULL CHECK (${col(c.status)} IN ($statusCheck)),
            |    ${col(c.runAt)} timestamptz NOT NULL,
            |    ${col(c.attempts)} integer NOT NULL DEFAULT 0,
            |    ${col(c.lastError)} text,
            |    ${col(c.lockedBy)} varchar(200),
            |    ${col(c.lockedAt)} timestamptz,
            |    ${col(c.version)} bigint NOT NULL DEFAULT 0,
            |    ${col(c.createdAt)} timestamptz NOT NULL DEFAULT now(),
            |    ${col(c.idempotencyKey)} varchar(200),
            |    ${col(c.createdBy)} varchar(200),
            |    ${col(c.payloadVersion)} integer NOT NULL DEFAULT 1,
            |    ${col(c.finishedAt)} timestamptz,
            |    ${col(c.lane)} varchar(200),
            |    ${col(c.priority)} integer NOT NULL DEFAULT 0,
            |    ${col(c.resource)} varchar(200)$payload
            |);
            |
            |CREATE INDEX $claimIndex
            |    ON $t (${col(c.status)}, ${col(c.type)}, ${col(c.lane)}, ${col(c.priority)}, ${col(c.runAt)});
            |CREATE INDEX $resourceIndex ON $t (${col(c.resource)}) WHERE ${col(c.status)} = 'RUNNING';
            |CREATE UNIQUE INDEX $keyIndex ON $t (${col(c.type)}, ${col(c.idempotencyKey)});
            """.trimMargin()
    }
}

/**
 * The channel a table's trigger announces on: `pendenz_` plus the bare
 * table name, with anything but letters, digits and underscores replaced.
 */
public fun notifyChannelFor(table: TaskTable<*>): String =
    "pendenz_" + table.name.substringAfterLast('.').replace(Regex("[^A-Za-z0-9_]"), "_")

/**
 * The union of the payload columns of [types], or the default JSON column
 * when there are none. Two types may share a column of the same type.
 *
 * @throws IllegalArgumentException when two types declare one name with different types.
 */
public fun payloadColumnsOf(types: Collection<TaskType<*>>): List<PayloadColumn> {
    if (types.isEmpty()) return listOf(PayloadColumn(JsonPayload.DEFAULT_COLUMN, SqlType.JSONB))
    val byName = LinkedHashMap<String, PayloadColumn>()
    types.flatMap { it.payload.columns }.forEach { column ->
        val known = byName.putIfAbsent(column.name, column)
        require(known == null || known.type == column.type) {
            "payload column '${column.name}' is declared as ${known?.type} and as ${column.type}"
        }
    }
    return byName.values.toList()
}

/** Quotes identifiers so that mixed case and reserved words survive. */
internal object SqlIdentifiers {
    fun column(name: String): String = quote(name)

    fun table(name: String): String = name.split('.').joinToString(".") { quote(it) }

    private fun quote(part: String): String {
        require(!part.contains('"')) { "identifier must not contain a double quote: $part" }
        return "\"$part\""
    }
}
