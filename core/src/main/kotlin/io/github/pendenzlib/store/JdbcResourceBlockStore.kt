package io.github.pendenzlib.store

import io.github.pendenzlib.ResourceBlock
import io.github.pendenzlib.schema.ResourceBlockColumns
import java.time.Instant

/** The [ResourceBlockStore] half of [JdbcTaskStore] (ADR 0035). */
internal class JdbcResourceBlockStore(
    private val sql: ResourceBlockSql,
    private val columns: ResourceBlockColumns,
) : ResourceBlockStore {
    override fun block(
        tx: StoreTransaction,
        name: String,
        until: Instant,
        reason: String,
        now: Instant,
    ) {
        tx.jdbc.update(sql.block) { bind ->
            bind.string(name)
            bind.instant(until)
            bind.string(reason)
            bind.instant(now)
        }
    }

    override fun unblock(
        tx: StoreTransaction,
        name: String,
    ): Boolean = tx.jdbc.update(sql.unblock) { bind -> bind.string(name) } == 1

    override fun blocks(tx: StoreTransaction): List<ResourceBlock> =
        tx.jdbc.query(sql.blocks, {}) { rs ->
            buildList {
                while (rs.next()) {
                    add(
                        ResourceBlock(
                            rs.getString(columns.name),
                            checkNotNull(rs.instant(columns.blockedUntil)),
                            rs.getString(columns.reason),
                            checkNotNull(rs.instant(columns.blockedAt)),
                        ),
                    )
                }
            }
        }

    override fun activeBlocks(
        tx: StoreTransaction,
        now: Instant,
    ): List<String> =
        tx.jdbc.query(sql.activeBlocks, { bind -> bind.instant(now) }) { rs ->
            buildList { while (rs.next()) add(rs.getString(columns.name)) }
        }

    override fun deleteExpired(
        tx: StoreTransaction,
        now: Instant,
    ): Int = tx.jdbc.update(sql.deleteExpired) { bind -> bind.instant(now) }
}
