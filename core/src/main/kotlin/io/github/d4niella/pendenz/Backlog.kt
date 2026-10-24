package io.github.d4niella.pendenz

import java.time.Instant

/**
 * What the table holds for one task type that is not final yet, as
 * [Pendenz.backlog] reads it. Final rows are not counted: they grow without
 * bound, and retention removes them (ADR 0011).
 *
 * @property type the task type.
 * @property counts how many tasks are in each status that is not final; every such status is present, 0 when none.
 * @property oldestDue the run time of the task that has waited longest for a worker: a `PENDING` task, or a
 *   `SCHEDULED` or `RETRY_PENDING` one whose run time has passed but that no poll has promoted yet. Null when
 *   nothing is due.
 */
public class Backlog(
    public val type: String,
    public val counts: Map<TaskStatus, Long>,
    public val oldestDue: Instant?,
) {
    /** How many tasks of [type] are in [status]; 0 for a final status. */
    public fun count(status: TaskStatus): Long = counts[status] ?: 0

    override fun toString(): String = "Backlog(type=$type, counts=$counts, oldestDue=$oldestDue)"

    internal companion object {
        /** The statuses a backlog counts: every one that is not final. */
        val STATUSES: List<TaskStatus> = TaskStatus.entries.filterNot { it.isFinal }

        /** The statuses of a task that waits for a worker once its run time has passed. */
        val WAITING: Set<TaskStatus> = setOf(TaskStatus.PENDING, TaskStatus.SCHEDULED, TaskStatus.RETRY_PENDING)

        /** Builds one backlog per type from counts per type and status, sorted by type. */
        fun of(rows: List<Row>): List<Backlog> =
            rows.groupBy { it.type }.toSortedMap().map { (type, ofType) ->
                Backlog(
                    type = type,
                    counts = STATUSES.associateWith { status -> ofType.countOf(status) },
                    oldestDue = ofType.filter { it.status in WAITING }.mapNotNull { it.oldestDue }.minOrNull(),
                )
            }

        private fun List<Row>.countOf(status: TaskStatus): Long = filter { it.status == status }.sumOf { it.count }
    }

    /** One `GROUP BY` row: the count of [type] in [status], and the earliest due run time among them. */
    internal class Row(
        val type: String,
        val status: TaskStatus,
        val count: Long,
        val oldestDue: Instant?,
    )
}
