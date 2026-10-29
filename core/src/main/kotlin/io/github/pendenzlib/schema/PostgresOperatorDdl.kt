package io.github.pendenzlib.schema

import io.github.pendenzlib.CycleLane
import io.github.pendenzlib.CycleStatus
import io.github.pendenzlib.CycleStatusTransitions
import io.github.pendenzlib.TaskStatus
import io.github.pendenzlib.TaskStatusTransitions
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
     * tasks by type and error, `_stuck` lists `RUNNING` tasks whose lock
     * expired more than [stuckAfter] ago, `_needs_human` lists every task that
     * waits for a decision.
     */
    @JvmStatic
    @JvmOverloads
    public fun views(
        table: TaskTable<*>,
        stuckAfter: Duration = DEFAULT_STUCK_AFTER,
    ): String {
        val c = QuotedColumns(table)
        val t = SqlIdentifiers.table(table.name)

        fun view(suffix: String) = SqlIdentifiers.derived(table.name, suffix)
        val waiting = TaskStatus.entries.filter { it.kind == TaskStatus.Kind.WAITING_FOR_HUMAN }.sqlList()
        return """
            |CREATE VIEW ${view("_failures")} AS
            |SELECT ${c.type}, ${c.lastError}, count(*) AS tasks,
            |    min(${c.finishedAt}) AS first_failed, max(${c.finishedAt}) AS last_failed
            |FROM $t
            |WHERE ${c.status} = '${TaskStatus.FAILED.name}'
            |GROUP BY ${c.type}, ${c.lastError};
            |
            |CREATE VIEW ${view("_stuck")} AS
            |SELECT ${c.id}, ${c.type}, ${c.lane}, ${c.lockedBy}, ${c.lockedUntil},
            |    now() - ${c.lockedUntil} AS expired_for, ${c.attempts}
            |FROM $t
            |WHERE ${c.status} = '${TaskStatus.RUNNING.name}'
            |  AND ${c.lockedUntil} < now() - interval '${stuckAfter.seconds} seconds';
            |
            |CREATE VIEW ${view("_needs_human")} AS
            |SELECT ${c.id}, ${c.type}, ${c.status}, ${c.lane}, ${c.priority},
            |    ${c.attempts}, ${c.lastError}, ${c.createdBy}, ${c.createdAt}, ${c.finishedAt},
            |    ${c.preview}, ${c.result}, ${c.provenance}
            |FROM $t
            |WHERE ${c.status} IN ($waiting);
            """.trimMargin()
    }

    /**
     * Functions named after the table, each taking the id and the version
     * the caller saw and returning whether the row changed: `_restart`,
     * `_complete`, `_quarantine(id, version, reason)` and `_cancel`. With a
     * [cycleTable], `<cycle table>_cancel(id, version)` cancels a cycle and
     * every non-final task of it. `_approve(id, version, approver)` and
     * `_decline(id, version, actor, reason)` serve approval (ADR 0018);
     * `_approve` refuses the creator. A NULL approver, actor or reason
     * changes nothing and returns false. The allowed source states come from
     * `TaskStatusTransitions`, the single source of truth.
     */
    @JvmStatic
    @JvmOverloads
    public fun functions(
        table: TaskTable<*>,
        cycleTable: CycleTable<*>? = null,
    ): String {
        val functions = taskFunctions(table)
        return if (cycleTable == null) functions else functions + "\n\n" + cycleCancel(table, cycleTable)
    }

    private fun taskFunctions(table: TaskTable<*>): String {
        val f = Fragments(table)
        return f.operatorFunctions() + "\n\n" + f.approvalFunctions()
    }

    /** The pieces every generated function is built from, for one mapping. */
    private class Fragments(
        table: TaskTable<*>,
    ) {
        private val c = QuotedColumns(table)
        private val t = SqlIdentifiers.table(table.name)
        private val id = table.idType.sqlType
        private val tableName = table.name
        private val moves = PostgresMoves(c)
        val guard = "${c.id} = task_id AND ${c.version} = expected_version"
        val status = c.status

        fun fn(suffix: String) = SqlIdentifiers.derived(tableName, suffix)

        fun literal(status: TaskStatus) = "'${status.name}'"

        fun from(states: Set<TaskStatus>) = states.sortedBy { it.ordinal }.sqlList()

        /** The UPDATE of one move, the same SET clause as the API's (ADR 0031). */
        fun move(
            to: TaskStatus,
            from: Set<TaskStatus>,
            reason: String = "NULL",
            condition: String = "",
        ): String =
            """
            |    UPDATE $t SET ${moves.to(to)}
            |    FROM ${PostgresMoves.functionArgs(reason)}
            |    WHERE $guard AND $status IN (${from(from)})$condition;
            """.trimMargin()

        fun operatorFunctions(): String =
            """
            |CREATE FUNCTION ${fn("_restart")}(task_id $id, expected_version bigint) RETURNS boolean AS $$
            |BEGIN
            |${move(TaskStatus.PENDING, TaskStatusTransitions.restartable)}
            |    RETURN FOUND;
            |END $$ LANGUAGE plpgsql;
            |
            |CREATE FUNCTION ${fn("_complete")}(task_id $id, expected_version bigint) RETURNS boolean AS $$
            |BEGIN
            |${move(TaskStatus.SUCCEEDED, TaskStatusTransitions.completable)}
            |    RETURN FOUND;
            |END $$ LANGUAGE plpgsql;
            |
            |CREATE FUNCTION ${fn(
                "_quarantine",
            )}(task_id $id, expected_version bigint, reason text) RETURNS boolean AS $$
            |BEGIN
            |${move(TaskStatus.QUARANTINED, TaskStatusTransitions.quarantinable, "reason", " AND reason IS NOT NULL")}
            |    RETURN FOUND;
            |END $$ LANGUAGE plpgsql;
            |
            |CREATE FUNCTION ${fn("_cancel")}(task_id $id, expected_version bigint) RETURNS boolean AS $$
            |BEGIN
            |${move(TaskStatus.CANCELLED, TaskStatusTransitions.cancellable)}
            |    RETURN FOUND;
            |END $$ LANGUAGE plpgsql;
            """.trimMargin()

        fun approvalFunctions(): String =
            """
            |CREATE FUNCTION ${fn(
                "_approve",
            )}(task_id $id, expected_version bigint, approver text) RETURNS boolean AS $$
            |BEGIN
            |    UPDATE $t
            |    SET ${moves.release()},
            |        ${c.approvedBy} = approver, ${c.approvedAt} = ${PostgresMoves.ARGS}.${PostgresMoves.NOW}
            |    FROM ${PostgresMoves.functionArgs()}
            |    WHERE $guard AND $status = ${literal(TaskStatus.AWAITING_APPROVAL)}
            |      AND approver IS NOT NULL AND ${c.createdBy} IS DISTINCT FROM approver;
            |    RETURN FOUND;
            |END $$ LANGUAGE plpgsql;
            |
            |CREATE FUNCTION ${fn("_decline")}(task_id $id, expected_version bigint, actor text, reason text)
            |    RETURNS boolean AS $$
            |BEGIN
            |${move(
                TaskStatus.CANCELLED,
                setOf(TaskStatus.AWAITING_APPROVAL),
                "reason",
                " AND actor IS NOT NULL AND reason IS NOT NULL",
            )}
            |    RETURN FOUND;
            |END $$ LANGUAGE plpgsql;
            """.trimMargin()
    }

    private fun cycleCancel(
        table: TaskTable<*>,
        cycleTable: CycleTable<*>,
    ): String {
        val c = QuotedColumns(table)
        val cc = QuotedCycleColumns(cycleTable)
        val t = SqlIdentifiers.table(table.name)
        val ct = SqlIdentifiers.table(cycleTable.name)
        val fn = SqlIdentifiers.derived(cycleTable.name, "_cancel")
        val cancellable = CycleStatusTransitions.sourcesOf(CycleStatus.CANCELLED).sqlList()
        val tasks = TaskStatusTransitions.cancellable.sortedBy { it.ordinal }.sqlList()
        return """
            |CREATE FUNCTION $fn(cycle_id ${cycleTable.idType.sqlType}, expected_version bigint) RETURNS boolean AS $$
            |BEGIN
            |    UPDATE $ct SET ${cc.status} = '${CycleStatus.CANCELLED.name}', ${cc.finishedAt} = now(),
            |        ${cc.version} = ${cc.version} + 1
            |    WHERE ${cc.id} = cycle_id AND ${cc.version} = expected_version
            |      AND ${cc.status} IN ($cancellable);
            |    IF NOT FOUND THEN
            |        RETURN false;
            |    END IF;
            |    UPDATE $t SET ${PostgresMoves(c).to(TaskStatus.CANCELLED)}
            |    FROM ${PostgresMoves.functionArgs()}
            |    WHERE ${c.lane} = ${CycleLane.sql("cycle_id")} AND ${c.status} IN ($tasks);
            |    RETURN true;
            |END $$ LANGUAGE plpgsql;
            """.trimMargin()
    }

    private const val DEFAULT_STUCK_MINUTES = 5L

    /** A lock expired for five minutes has outlived many polls of any worker: certainly stuck. */
    @JvmField
    public val DEFAULT_STUCK_AFTER: Duration = Duration.ofMinutes(DEFAULT_STUCK_MINUTES)
}
