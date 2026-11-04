package io.github.pendenzlib.store

/**
 * The statements behind [ResourceBlockStore], with `?` placeholders in the
 * order listed per statement. Instants are bound as `OffsetDateTime` in UTC.
 *
 * @property block binds name, blockedUntil, reason, blockedAt; inserts, or keeps the later of the two
 *   times and writes the reason and blockedAt.
 * @property unblock binds name.
 * @property blocks binds nothing; returns the name, blockedUntil, reason and blockedAt columns, by name.
 * @property activeBlocks binds now; returns the name column of the blocks that last beyond it.
 * @property deleteExpired binds now; deletes the blocks that ended at or before it.
 */
public class ResourceBlockSql(
    public val block: String,
    public val unblock: String,
    public val blocks: String,
    public val activeBlocks: String,
    public val deleteExpired: String,
)
