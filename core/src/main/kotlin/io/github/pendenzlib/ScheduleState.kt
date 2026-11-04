package io.github.pendenzlib

import java.time.Instant

/**
 * Where a schedule stands, as [Pendenz.schedules] reads it from the table
 * (ADR 0034). Both records are ordinary occurrences: tasks of [type]
 * created by the schedule.
 *
 * @property name the schedule's name, as registered on the builder.
 * @property type the task type it enqueues.
 * @property latest the newest occurrence, or null when there is none.
 * @property lastSuccess the newest occurrence that ended `SUCCEEDED`, or null when none did or retention
 *   removed it.
 */
public class ScheduleState<ID : Any>(
    public val name: String,
    public val type: String,
    public val latest: TaskRecord<ID>?,
    public val lastSuccess: TaskRecord<ID>?,
) {
    /**
     * The run time of [latest] while it is open: it will still run, or waits
     * for approval. Null when no occurrence is open: before the first check,
     * when the series is broken and no check has repaired it yet, or when
     * the schedule has ended (ADR 0033).
     */
    public val next: Instant?
        get() = latest?.takeIf { it.status in Occurrences.OPEN }?.runAt

    override fun toString(): String =
        "ScheduleState(name=$name, type=$type, next=$next, lastSuccess=${lastSuccess?.finishedAt})"
}
