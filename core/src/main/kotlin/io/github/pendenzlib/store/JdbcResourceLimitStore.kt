package io.github.pendenzlib.store

import io.github.pendenzlib.ResourceLimit
import io.github.pendenzlib.schema.ResourceLimitColumns

/** The [ResourceLimitStore] half of [JdbcTaskStore]. */
internal class JdbcResourceLimitStore(
    private val sql: ResourceLimitSql,
    private val columns: ResourceLimitColumns,
) : ResourceLimitStore {
    override fun setLimit(
        tx: StoreTransaction,
        limit: ResourceLimit,
    ) {
        tx.jdbc.update(sql.setLimit) { bind ->
            bind.string(limit.name)
            bind.int(limit.maxRunning)
        }
    }

    override fun seedLimit(
        tx: StoreTransaction,
        limit: ResourceLimit,
    ) {
        tx.jdbc.update(sql.seedLimit) { bind ->
            bind.string(limit.name)
            bind.int(limit.maxRunning)
        }
    }

    override fun removeLimit(
        tx: StoreTransaction,
        name: String,
    ): Boolean = tx.jdbc.update(sql.removeLimit) { bind -> bind.string(name) } == 1

    override fun limits(tx: StoreTransaction): List<ResourceLimit> =
        tx.jdbc.query(sql.limits, {}) { rs ->
            buildList {
                while (rs.next()) add(ResourceLimit(rs.getString(columns.name), rs.getInt(columns.maxRunning)))
            }
        }

    override fun lockClaims(tx: StoreTransaction) {
        tx.jdbc.query(sql.lockClaims, {}) { rs -> rs.next() }
    }
}
