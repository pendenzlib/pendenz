package io.github.pendenzlib

import java.time.Instant

/**
 * A task row, as read back: what `find` and `tasks` return. Part of the
 * read model of the API, not of the store SPI (ADR 0030).
 *
 * One parameter per mapped column, by design.
 */
@Suppress("LongParameterList")
public class TaskRecord<ID : Any>(
    public val id: ID,
    public val type: String,
    public val status: TaskStatus,
    public val payload: Map<String, Any?>,
    public val runAt: Instant,
    public val attempts: Int,
    public val lastError: String?,
    public val lockedBy: String?,
    public val lockedUntil: Instant?,
    public val version: Long,
    public val createdAt: Instant,
    public val idempotencyKey: String?,
    public val createdBy: String?,
    public val payloadVersion: Int,
    public val finishedAt: Instant?,
    public val lane: String?,
    public val priority: Int,
    public val resource: String?,
    public val approvedBy: String?,
    public val approvedAt: Instant?,
    public val parentId: ID?,
    public val correlationId: String?,
    public val result: String?,
    public val provenance: Map<String, String>,
    public val preview: String?,
) {
    override fun toString(): String =
        "TaskRecord(id=$id, type=$type, status=$status, attempts=$attempts, lane=$lane, lastError=$lastError)"
}
