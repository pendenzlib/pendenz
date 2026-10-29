package io.github.pendenzlib.schema

/** The mapped column names, quoted for use in SQL text. */
internal class QuotedColumns(
    table: TaskTable<*>,
) {
    private val cols = table.columns
    val id = SqlIdentifiers.column(cols.id)
    val type = SqlIdentifiers.column(cols.type)
    val status = SqlIdentifiers.column(cols.status)
    val runAt = SqlIdentifiers.column(cols.runAt)
    val attempts = SqlIdentifiers.column(cols.attempts)
    val lastError = SqlIdentifiers.column(cols.lastError)
    val lockedBy = SqlIdentifiers.column(cols.lockedBy)
    val lockedUntil = SqlIdentifiers.column(cols.lockedUntil)
    val version = SqlIdentifiers.column(cols.version)
    val createdAt = SqlIdentifiers.column(cols.createdAt)
    val idempotencyKey = SqlIdentifiers.column(cols.idempotencyKey)
    val createdBy = SqlIdentifiers.column(cols.createdBy)
    val payloadVersion = SqlIdentifiers.column(cols.payloadVersion)
    val finishedAt = SqlIdentifiers.column(cols.finishedAt)
    val lane = SqlIdentifiers.column(cols.lane)
    val priority = SqlIdentifiers.column(cols.priority)
    val resource = SqlIdentifiers.column(cols.resource)
    val approvedBy = SqlIdentifiers.column(cols.approvedBy)
    val approvedAt = SqlIdentifiers.column(cols.approvedAt)
    val parentId = SqlIdentifiers.column(cols.parentId)
    val correlationId = SqlIdentifiers.column(cols.correlationId)
    val result = SqlIdentifiers.column(cols.result)
    val provenance = SqlIdentifiers.column(cols.provenance)
    val preview = SqlIdentifiers.column(cols.preview)
}

/** The mapped cycle column names, quoted for use in SQL text. */
internal class QuotedCycleColumns(
    table: CycleTable<*>,
) {
    private val cols = table.columns
    val id = SqlIdentifiers.column(cols.id)
    val type = SqlIdentifiers.column(cols.type)
    val status = SqlIdentifiers.column(cols.status)
    val description = SqlIdentifiers.column(cols.description)
    val createdAt = SqlIdentifiers.column(cols.createdAt)
    val createdBy = SqlIdentifiers.column(cols.createdBy)
    val finishedAt = SqlIdentifiers.column(cols.finishedAt)
    val version = SqlIdentifiers.column(cols.version)
}

/** Enum constants as a list of SQL string literals, for `IN (...)` and `CHECK` constraints. */
internal fun Iterable<Enum<*>>.sqlList(): String = joinToString(", ") { "'${it.name}'" }
