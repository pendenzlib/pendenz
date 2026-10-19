package io.github.d4niella.pendenz.schema

import io.github.d4niella.pendenz.CycleStatus
import io.github.d4niella.pendenz.TaskStatus
import io.github.d4niella.pendenz.TaskStatusTransitions
import java.time.Duration

/**
 * Views and functions for operators, generated from a mapping. The views
 * answer the questions the SQL recipes ask; the functions apply the
 * transitions of ADR 0002 with a version check, so a UI or a script can
 * restart, complete, quarantine or cancel a task without knowing the rules.
 * All of it is optional: run it beside `createTable` when wanted.
 */
public object PostgresOperatorDdl {
    /**
     * Three views, named after the table: `_failures` groups `FAILED`
     * tasks by type and error, `_stuck` lists `RUNNING` tasks without a
     * heartbeat for [stuckAfter], `_needs_human` lists every task that
     * waits for a decision.
     */
    @JvmStatic
    @JvmOverloads
    public fun views(
        table: TaskTable<*>,
        stuckAfter: Duration = DEFAULT_STUCK_AFTER,
    ): String {
        val c = table.columns
        val t = SqlIdentifiers.table(table.name)

        fun col(name: String) = SqlIdentifiers.column(name)

        fun view(suffix: String) = SqlIdentifiers.table(table.name + suffix)
        val waiting = TaskStatus.entries.filter { it.kind == TaskStatus.Kind.WAITING_FOR_HUMAN }.sqlList()
        return """
            |CREATE VIEW ${view("_failures")} AS
            |SELECT ${col(c.type)}, ${col(c.lastError)}, count(*) AS tasks,
            |    min(${col(c.finishedAt)}) AS first_failed, max(${col(c.finishedAt)}) AS last_failed
            |FROM $t
            |WHERE ${col(c.status)} = '${TaskStatus.FAILED.name}'
            |GROUP BY ${col(c.type)}, ${col(c.lastError)};
            |
            |CREATE VIEW ${view("_stuck")} AS
            |SELECT ${col(c.id)}, ${col(c.type)}, ${col(c.lane)}, ${col(c.lockedBy)}, ${col(c.lockedAt)},
            |    now() - ${col(c.lockedAt)} AS locked_for, ${col(c.attempts)}
            |FROM $t
            |WHERE ${col(c.status)} = '${TaskStatus.RUNNING.name}'
            |  AND ${col(c.lockedAt)} < now() - interval '${stuckAfter.seconds} seconds';
            |
            |CREATE VIEW ${view("_needs_human")} AS
            |SELECT ${col(c.id)}, ${col(c.type)}, ${col(c.status)}, ${col(c.lane)}, ${col(c.priority)},
            |    ${col(c.attempts)}, ${col(c.lastError)}, ${col(c.createdBy)}, ${col(c.createdAt)}, ${col(c.finishedAt)}
            |FROM $t
            |WHERE ${col(c.status)} IN ($waiting);
            """.trimMargin()
    }

    /**
     * Functions named after the table, each taking the id and the version
     * the caller saw and returning whether the row changed: `_restart`,
     * `_complete`, `_quarantine(id, version, reason)` and `_cancel`. With a
     * [cycleTable], `<cycle table>_cancel(id, version)` cancels a cycle and
     * every non-final task of it. The allowed source states come from
     * `TaskStatusTransitions`, the single source of truth.
     */
    @JvmStatic
    @JvmOverloads
    public fun functions(
        table: TaskTable<*>,
        cycleTable: CycleTable<*>? = null,
    ): String {
        val c = table.columns
        val t = SqlIdentifiers.table(table.name)
        val id = table.idType.sqlType

        fun col(name: String) = SqlIdentifiers.column(name)

        fun fn(suffix: String) = SqlIdentifiers.table(table.name + suffix)

        fun from(
            to: TaskStatus,
            humanOnly: Boolean,
        ) = TaskStatus.entries
            .filter { TaskStatusTransitions.isAllowed(it, to) }
            .filter { !humanOnly || it.kind == TaskStatus.Kind.WAITING_FOR_HUMAN }
            .sqlList()
        val unlock = "${col(c.lockedBy)} = NULL, ${col(c.lockedAt)} = NULL"
        val bump = "${col(c.version)} = ${col(c.version)} + 1"
        val guard = "${col(c.id)} = task_id AND ${col(c.version)} = expected_version"
        val functions =
            """
            |CREATE FUNCTION ${fn("_restart")}(task_id $id, expected_version bigint) RETURNS boolean AS $$
            |BEGIN
            |    UPDATE $t SET ${col(
                c.status,
            )} = '${TaskStatus.PENDING.name}', $unlock, ${col(c.finishedAt)} = NULL, $bump
            |    WHERE $guard AND ${col(c.status)} IN (${from(TaskStatus.PENDING, humanOnly = true)});
            |    RETURN FOUND;
            |END $$ LANGUAGE plpgsql;
            |
            |CREATE FUNCTION ${fn("_complete")}(task_id $id, expected_version bigint) RETURNS boolean AS $$
            |BEGIN
            |    UPDATE $t SET ${col(
                c.status,
            )} = '${TaskStatus.SUCCEEDED.name}', $unlock, ${col(c.finishedAt)} = now(), $bump
            |    WHERE $guard AND ${col(c.status)} IN (${from(TaskStatus.SUCCEEDED, humanOnly = true)});
            |    RETURN FOUND;
            |END $$ LANGUAGE plpgsql;
            |
            |CREATE FUNCTION ${fn(
                "_quarantine",
            )}(task_id $id, expected_version bigint, reason text) RETURNS boolean AS $$
            |BEGIN
            |    UPDATE $t SET ${col(c.status)} = '${TaskStatus.QUARANTINED.name}', ${col(c.lastError)} = reason, $bump
            |    WHERE $guard AND ${col(c.status)} IN (${from(TaskStatus.QUARANTINED, humanOnly = false)});
            |    RETURN FOUND;
            |END $$ LANGUAGE plpgsql;
            |
            |CREATE FUNCTION ${fn("_cancel")}(task_id $id, expected_version bigint) RETURNS boolean AS $$
            |BEGIN
            |    UPDATE $t SET ${col(
                c.status,
            )} = '${TaskStatus.CANCELLED.name}', $unlock, ${col(c.finishedAt)} = now(), $bump
            |    WHERE $guard AND ${col(c.status)} IN (${from(TaskStatus.CANCELLED, humanOnly = false)});
            |    RETURN FOUND;
            |END $$ LANGUAGE plpgsql;
            """.trimMargin()
        return if (cycleTable == null) functions else functions + "\n\n" + cycleCancel(table, cycleTable)
    }

    private fun cycleCancel(
        table: TaskTable<*>,
        cycleTable: CycleTable<*>,
    ): String {
        val c = table.columns
        val cc = cycleTable.columns
        val t = SqlIdentifiers.table(table.name)
        val ct = SqlIdentifiers.table(cycleTable.name)
        val fn = SqlIdentifiers.table(cycleTable.name + "_cancel")

        fun col(name: String) = SqlIdentifiers.column(name)
        val cancellable = CycleStatus.entries.filter { !it.isFinal }.joinToString(", ") { "'${it.name}'" }
        val final = listOf(TaskStatus.SUCCEEDED, TaskStatus.CANCELLED).sqlList()
        return """
            |CREATE FUNCTION $fn(cycle_id ${cycleTable.idType.sqlType}, expected_version bigint) RETURNS boolean AS $$
            |BEGIN
            |    UPDATE $ct SET ${col(cc.status)} = '${CycleStatus.CANCELLED.name}', ${col(cc.finishedAt)} = now(),
            |        ${col(cc.version)} = ${col(cc.version)} + 1
            |    WHERE ${col(cc.id)} = cycle_id AND ${col(cc.version)} = expected_version
            |      AND ${col(cc.status)} IN ($cancellable);
            |    IF NOT FOUND THEN
            |        RETURN false;
            |    END IF;
            |    UPDATE $t SET ${col(c.status)} = '${TaskStatus.CANCELLED.name}', ${col(c.lockedBy)} = NULL,
            |        ${col(c.lockedAt)} = NULL, ${col(c.finishedAt)} = now(), ${col(c.version)} = ${col(c.version)} + 1
            |    WHERE ${col(c.lane)} = cycle_id::text AND ${col(c.status)} NOT IN ($final);
            |    RETURN true;
            |END $$ LANGUAGE plpgsql;
            """.trimMargin()
    }

    private fun List<TaskStatus>.sqlList(): String = joinToString(", ") { "'${it.name}'" }

    private const val DEFAULT_STUCK_MINUTES = 5L

    /** Five minutes without a heartbeat is five times the default lock timeout: certainly stuck. */
    @JvmField
    public val DEFAULT_STUCK_AFTER: Duration = Duration.ofMinutes(DEFAULT_STUCK_MINUTES)
}
