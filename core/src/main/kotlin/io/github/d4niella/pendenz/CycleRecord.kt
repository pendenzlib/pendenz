package io.github.d4niella.pendenz

import java.time.Instant

/** A cycle row, as read back. */
@Suppress("LongParameterList") // one parameter per mapped column, by design
public class CycleRecord<ID : Any>(
    public val id: ID,
    public val type: String,
    public val status: CycleStatus,
    public val description: String?,
    public val createdAt: Instant,
    public val createdBy: String?,
    public val finishedAt: Instant?,
    public val version: Long,
) {
    override fun toString(): String = "CycleRecord(id=$id, type=$type, status=$status, description=$description)"
}
