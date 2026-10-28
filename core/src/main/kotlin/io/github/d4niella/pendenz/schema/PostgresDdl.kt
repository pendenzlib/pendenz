package io.github.d4niella.pendenz.schema

import io.github.d4niella.pendenz.CycleLane
import io.github.d4niella.pendenz.CycleStatus
import io.github.d4niella.pendenz.JsonPayload
import io.github.d4niella.pendenz.PayloadColumn
import io.github.d4niella.pendenz.SqlType
import io.github.d4niella.pendenz.TaskStatus
import io.github.d4niella.pendenz.TaskType
import java.security.MessageDigest

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
     * or by status change, on the channel [notifyChannel] names, with the
     * task type as the payload. Opt-in: run it beside [createTable] when
     * workers use `pendenz-postgres-notify`. See ADR 0014.
     */
    @JvmStatic
    public fun notifyTrigger(table: TaskTable<*>): String {
        val c = QuotedColumns(table)
        val t = SqlIdentifiers.table(table.name)
        // The function lives in the table's schema, so two tables of one name in two schemas do not collide
        val function = SqlIdentifiers.derived(table.name, "_notify")
        val trigger = SqlIdentifiers.column(SqlIdentifiers.fit("", table.name.substringAfterLast('.'), "_notify"))
        val channel = notifyChannel(table)
        return """
            |CREATE FUNCTION $function() RETURNS trigger AS ${'$'}${'$'}
            |BEGIN
            |    PERFORM pg_notify('$channel', NEW.${c.type});
            |    RETURN NULL;
            |END ${'$'}${'$'} LANGUAGE plpgsql;
            |
            |CREATE TRIGGER $trigger
            |    AFTER INSERT OR UPDATE OF ${c.status} ON $t
            |    FOR EACH ROW WHEN (NEW.${c.status} = '${TaskStatus.PENDING.name}')
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
        val c = QuotedCycleColumns(table)
        val t = SqlIdentifiers.table(table.name)
        val statusCheck = CycleStatus.entries.sqlList()
        return """
            |CREATE TABLE $t (
            |    ${c.id} ${table.idType.columnDefinition} PRIMARY KEY,
            |    ${c.type} varchar(200) NOT NULL,
            |    ${c.status} varchar(32) NOT NULL CHECK (${c.status} IN ($statusCheck)),
            |    ${c.description} text,
            |    ${c.createdAt} timestamptz NOT NULL DEFAULT now(),
            |    ${c.createdBy} varchar(200),
            |    ${c.finishedAt} timestamptz,
            |    ${c.version} bigint NOT NULL DEFAULT 0
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
        val c = QuotedColumns(table)
        val cc = QuotedCycleColumns(cycleTable)
        val t = SqlIdentifiers.table(table.name)
        val ct = SqlIdentifiers.table(cycleTable.name)
        val view = SqlIdentifiers.derived(cycleTable.name, "_progress")
        val counts =
            TaskStatus.entries.joinToString(",\n|    ") {
                "count(*) FILTER (WHERE task.${c.status} = '${it.name}') AS ${SqlIdentifiers.column(
                    it.name.lowercase(),
                )}"
            }
        return """
            |CREATE VIEW $view AS
            |SELECT cycle.${cc.id}, cycle.${cc.type}, cycle.${cc.status}, cycle.${cc.description},
            |    cycle.${cc.createdAt}, cycle.${cc.createdBy}, cycle.${cc.finishedAt},
            |    count(task.${c.id}) AS tasks,
            |    $counts,
            |    min(task.${c.runAt}) FILTER (WHERE task.${c.status} = 'PENDING') AS oldest_pending,
            |    max(task.${c.finishedAt}) AS newest_finished
            |FROM $ct AS cycle
            |LEFT JOIN $t AS task ON task.${c.lane} = ${CycleLane.sql("cycle.${cc.id}")}
            |GROUP BY cycle.${cc.id};
            """.trimMargin()
    }

    /**
     * The channel a table's trigger announces on: `pendenz_` plus the table
     * name with its schema, if it has one, and anything but letters, digits and
     * underscores replaced, so `billing.task` announces on `pendenz_billing_task`.
     * A name longer than PostgreSQL's 63 bytes is cut and given a hash of the
     * table name, so trigger and listener still meet (ADR 0028).
     */
    @JvmStatic
    public fun notifyChannel(table: TaskTable<*>): String =
        SqlIdentifiers.fit("pendenz_", table.name.replace(Regex("[^A-Za-z0-9_]"), "_"), "")

    /**
     * `CREATE TABLE` for the queue's columns and the given payload columns, followed by [indexes].
     *
     * @throws IllegalArgumentException when a payload column has the name of a column of the queue.
     */
    @JvmStatic
    public fun createTable(
        table: TaskTable<*>,
        payloadColumns: Collection<PayloadColumn>,
    ): String {
        requireApartFromQueueColumns(table, payloadColumns)
        val c = QuotedColumns(table)
        val t = SqlIdentifiers.table(table.name)
        val statusCheck = TaskStatus.entries.sqlList()
        val payload = payloadColumns.joinToString("") { ",\n|    ${SqlIdentifiers.column(it.name)} ${it.type.ddl}" }
        return """
            |CREATE TABLE $t (
            |    ${c.id} ${table.idType.columnDefinition} PRIMARY KEY,
            |    ${c.type} varchar(200) NOT NULL,
            |    ${c.status} varchar(32) NOT NULL CHECK (${c.status} IN ($statusCheck)),
            |    ${c.runAt} timestamptz NOT NULL,
            |    ${c.attempts} integer NOT NULL DEFAULT 0,
            |    ${c.lastError} text,
            |    ${c.lockedBy} varchar(200),
            |    ${c.lockedUntil} timestamptz,
            |    ${c.version} bigint NOT NULL DEFAULT 0,
            |    ${c.createdAt} timestamptz NOT NULL DEFAULT now(),
            |    ${c.idempotencyKey} varchar(200),
            |    ${c.createdBy} varchar(200),
            |    ${c.payloadVersion} integer NOT NULL DEFAULT 1,
            |    ${c.finishedAt} timestamptz,
            |    ${c.lane} varchar(200),
            |    ${c.priority} integer NOT NULL DEFAULT 0,
            |    ${c.resource} varchar(200),
            |    ${c.approvedBy} varchar(200),
            |    ${c.approvedAt} timestamptz,
            |    ${c.parentId} ${table.idType.sqlType},
            |    ${c.correlationId} varchar(200),
            |    ${c.result} jsonb,
            |    ${c.provenance} jsonb,
            |    ${c.preview} text$payload
            |);
            """.trimMargin() + "\n\n" + indexes(table)
    }

    /**
     * The indexes the library's queries rely on (ADR 0028), on their own for
     * a table written by hand or one Pendenz is added to. Each is named after
     * the table: claim, due tasks, running tasks, cancelled tasks whose
     * handler still holds the lock, lanes, retention, creator, correlation
     * and the unique index for idempotency keys.
     */
    @JvmStatic
    public fun indexes(table: TaskTable<*>): String {
        val c = QuotedColumns(table)
        val t = SqlIdentifiers.table(table.name)
        val bareName = table.name.substringAfterLast('.')

        fun index(suffix: String) = SqlIdentifiers.column(SqlIdentifiers.fit("", bareName, suffix))

        fun inStates(vararg states: TaskStatus) = "${c.status} IN (${states.joinToString { "'${it.name}'" }})"
        val claimColumns = listOf(c.status, c.type, c.lane, c.priority, c.runAt, c.id).joinToString()
        val due = inStates(TaskStatus.SCHEDULED, TaskStatus.RETRY_PENDING)
        val running = inStates(TaskStatus.RUNNING)
        // Cancelled while running, the handler not yet returned (ADR 0031). `locked_by`, not `locked_until`, in
        // the predicate: the heartbeat writes only `locked_until` and stays a HOT update.
        val held = "${inStates(TaskStatus.CANCELLED)} AND ${c.lockedBy} IS NOT NULL"
        val final = inStates(TaskStatus.SUCCEEDED, TaskStatus.CANCELLED)
        val creatorOrder = "${c.createdBy}, ${c.createdAt} DESC, ${c.id} DESC"
        return """
            |CREATE INDEX ${index("_claim_idx")} ON $t ($claimColumns);
            |CREATE INDEX ${index("_due_idx")} ON $t (${c.runAt}) WHERE $due;
            |CREATE INDEX ${index("_running_idx")} ON $t (${c.resource}) WHERE $running;
            |CREATE INDEX ${index("_held_idx")} ON $t (${c.resource}) WHERE $held;
            |CREATE INDEX ${index("_lane_idx")}
            |    ON $t (${c.lane}, ${c.status}) WHERE ${c.lane} IS NOT NULL;
            |CREATE INDEX ${index("_retention_idx")} ON $t (${c.type}, ${c.finishedAt}) WHERE $final;
            |CREATE INDEX ${index("_creator_idx")} ON $t ($creatorOrder) WHERE ${c.createdBy} IS NOT NULL;
            |CREATE INDEX ${index("_correlation_idx")}
            |    ON $t (${c.correlationId}) WHERE ${c.correlationId} IS NOT NULL;
            |CREATE UNIQUE INDEX ${index("_idempotency_idx")} ON $t (${c.type}, ${c.idempotencyKey});
            """.trimMargin()
    }
}

/**
 * The union of the payload columns of [types], or the default JSON column
 * when there are none. Two types may share a column of the same type.
 *
 * @throws IllegalArgumentException when two types declare one name with different types.
 */
internal fun payloadColumnsOf(types: Collection<TaskType<*>>): List<PayloadColumn> {
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

/** A payload column named like a column of the queue would appear twice in the table and in every insert. */
internal fun requireApartFromQueueColumns(
    table: TaskTable<*>,
    payloadColumns: Collection<PayloadColumn>,
) {
    val clashes = payloadColumns.map { it.name }.filter { it in table.columns.all }
    require(clashes.isEmpty()) { "payload columns $clashes are also columns of the queue in table ${table.name}" }
}

/** Quotes identifiers so that mixed case and reserved words survive. */
internal object SqlIdentifiers {
    /** The most bytes PostgreSQL keeps of an identifier; it cuts longer ones silently. */
    private const val MAX_BYTES = 63
    private const val HASH_CHARS = 8

    fun column(name: String): String = quote(name)

    fun table(name: String): String = name.split('.').joinToString(".") { quote(it) }

    /** A view or function named after [tableName] plus [suffix], in the table's schema, quoted. */
    fun derived(
        tableName: String,
        suffix: String,
    ): String {
        val schema = tableName.substringBeforeLast('.', "")
        val name = fit("", tableName.substringAfterLast('.'), suffix)
        return table(if (schema.isEmpty()) name else "$schema.$name")
    }

    /**
     * [prefix], [base] and [suffix] as one identifier of at most 63 bytes. When
     * it would be longer, [base] is cut and a hash of the whole [base] keeps the
     * name unique per table and the same wherever it is derived (ADR 0028).
     */
    fun fit(
        prefix: String,
        base: String,
        suffix: String,
    ): String {
        val full = prefix + base + suffix
        if (full.utf8Bytes() <= MAX_BYTES) return full
        val hash = "_" + sha256(base).take(HASH_CHARS)
        val budget = MAX_BYTES - prefix.utf8Bytes() - hash.length - suffix.utf8Bytes()
        require(budget > 0) { "prefix and suffix leave no room for the table name: $prefix...$suffix" }
        return prefix + base.cutToBytes(budget) + hash + suffix
    }

    private fun String.utf8Bytes(): Int = toByteArray(Charsets.UTF_8).size

    /** The longest prefix of whole code points that fits into [bytes] bytes of UTF-8. */
    private fun String.cutToBytes(bytes: Int): String {
        val cut = StringBuilder()
        for (codePoint in codePoints()) {
            val next = String(Character.toChars(codePoint))
            if ((cut.toString() + next).utf8Bytes() > bytes) break
            cut.append(next)
        }
        return cut.toString()
    }

    private fun sha256(text: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun quote(part: String): String {
        require(!part.contains('"')) { "identifier must not contain a double quote: $part" }
        return "\"$part\""
    }
}
