package io.github.pendenzlib.store

import io.github.pendenzlib.ResourceBlock
import java.time.Instant

/**
 * The resource block table (ADR 0035). The blocks take effect in the
 * claims of [TaskStore]: the poll reads the active ones once and leaves
 * their resources out through [ResourceFilter].
 */
public interface ResourceBlockStore {
    /**
     * Blocks [name] until [until] with [reason], stamped [now]. An existing
     * block of the name that lasts longer stays as it is, so that two
     * reports leave the later time and its reason.
     */
    public fun block(
        tx: StoreTransaction,
        name: String,
        until: Instant,
        reason: String,
        now: Instant,
    )

    /** Removes the block of [name]; returns whether a row went. */
    public fun unblock(
        tx: StoreTransaction,
        name: String,
    ): Boolean

    /** Every block, expired or not, by name. */
    public fun blocks(tx: StoreTransaction): List<ResourceBlock>

    /** The names of the blocks that last beyond [now]. */
    public fun activeBlocks(
        tx: StoreTransaction,
        now: Instant,
    ): List<String>

    /** Deletes the blocks that ended at or before [now]; returns how many. */
    public fun deleteExpired(
        tx: StoreTransaction,
        now: Instant,
    ): Int
}
