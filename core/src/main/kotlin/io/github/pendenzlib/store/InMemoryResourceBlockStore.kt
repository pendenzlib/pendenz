package io.github.pendenzlib.store

import io.github.pendenzlib.ResourceBlock
import java.time.Instant
import java.util.TreeMap

/**
 * The block half of [InMemoryTaskStore] (ADR 0035). The claims do not read
 * it: the poll reads the active blocks and hands them to the claims in
 * their [ResourceFilter], as with the database.
 *
 * @param requireOwn refuses a transaction handle of another store.
 */
internal class InMemoryResourceBlockStore(
    private val requireOwn: (StoreTransaction) -> Unit,
) : ResourceBlockStore {
    private val rows = TreeMap<String, ResourceBlock>()

    private inline fun <T> locked(
        tx: StoreTransaction,
        block: () -> T,
    ): T {
        requireOwn(tx)
        return synchronized(rows, block)
    }

    override fun block(
        tx: StoreTransaction,
        name: String,
        until: Instant,
        reason: String,
        now: Instant,
    ): Unit =
        locked(tx) {
            val existing = rows[name]
            if (existing == null || !existing.blockedUntil.isAfter(until)) {
                rows[name] = ResourceBlock(name, until, reason, now)
            }
        }

    override fun unblock(
        tx: StoreTransaction,
        name: String,
    ): Boolean = locked(tx) { rows.remove(name) != null }

    override fun blocks(tx: StoreTransaction): List<ResourceBlock> = locked(tx) { rows.values.toList() }

    override fun activeBlocks(
        tx: StoreTransaction,
        now: Instant,
    ): List<String> = locked(tx) { rows.values.filter { it.blockedUntil.isAfter(now) }.map { it.name } }

    override fun deleteExpired(
        tx: StoreTransaction,
        now: Instant,
    ): Int =
        locked(tx) {
            val expired = rows.values.filterNot { it.blockedUntil.isAfter(now) }.map { it.name }
            expired.forEach { rows.remove(it) }
            expired.size
        }
}
