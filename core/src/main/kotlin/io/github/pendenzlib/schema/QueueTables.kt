package io.github.pendenzlib.schema

/**
 * The tables a queue on the database store uses: the task table and the
 * optional tables turned on with `enableCycles()`, `enableResourceLimits()`
 * and `enableResourceBlocks()`, `null` when not turned on. Read through
 * `Pendenz.tables`, for example by a startup check that the tables exist
 * (ADR 0037).
 */
public class QueueTables<ID : Any>(
    public val task: TaskTable<ID>,
    public val cycles: CycleTable<ID>?,
    public val resourceLimits: ResourceLimitTable?,
    public val resourceBlocks: ResourceBlockTable?,
) {
    override fun toString(): String =
        "QueueTables(task=$task, cycles=$cycles, resourceLimits=$resourceLimits, resourceBlocks=$resourceBlocks)"
}
