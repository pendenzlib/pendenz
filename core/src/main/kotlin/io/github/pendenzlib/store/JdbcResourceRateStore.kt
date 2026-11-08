package io.github.pendenzlib.store

import io.github.pendenzlib.ResourceRate
import io.github.pendenzlib.schema.ResourceRateColumns
import io.github.pendenzlib.schema.ResourceStartColumns
import java.time.Duration
import java.time.Instant

/** The [ResourceRateStore] half of [JdbcTaskStore] (ADR 0040). */
internal class JdbcResourceRateStore(
    private val sql: ResourceRateSql,
    private val columns: ResourceRateColumns,
    private val startColumns: ResourceStartColumns,
) : ResourceRateStore {
    override fun setRate(
        tx: StoreTransaction,
        rate: ResourceRate,
    ) {
        tx.jdbc.update(sql.setRate) { bind -> bindRate(bind, rate) }
    }

    override fun seedRate(
        tx: StoreTransaction,
        rate: ResourceRate,
    ) {
        tx.jdbc.update(sql.seedRate) { bind -> bindRate(bind, rate) }
    }

    private fun bindRate(
        bind: Binder,
        rate: ResourceRate,
    ) {
        bind.string(rate.name)
        bind.int(rate.maxStarts)
        bind.long(rate.per.toMillis())
    }

    override fun removeRate(
        tx: StoreTransaction,
        name: String,
    ): Boolean = tx.jdbc.update(sql.removeRate) { bind -> bind.string(name) } == 1

    override fun rates(tx: StoreTransaction): List<ResourceRate> =
        tx.jdbc.query(sql.rates, {}) { rs ->
            buildList {
                while (rs.next()) {
                    val per = Duration.ofMillis(rs.getLong(ResourceRateSql.PER_MILLIS))
                    add(ResourceRate(rs.getString(columns.name), rs.getInt(columns.maxStarts), per))
                }
            }
        }

    override fun startsPerResource(
        tx: StoreTransaction,
        now: Instant,
    ): Map<String, Int> =
        tx.jdbc.query(
            sql.startsPerResource,
            { bind ->
                bind.instant(now)
                bind.instant(now)
            },
        ) { rs ->
            buildMap {
                while (rs.next()) put(rs.getString(startColumns.resource), rs.getInt(ResourceRateSql.STARTS_COUNT))
            }
        }

    override fun recordStarts(
        tx: StoreTransaction,
        resources: List<String>,
        now: Instant,
    ) {
        if (resources.isEmpty()) return
        val connection = tx.jdbc
        connection.update(sql.recordStarts) { bind ->
            bind.stringArray(connection, resources)
            bind.instant(now)
        }
    }

    override fun deleteStartsBefore(
        tx: StoreTransaction,
        before: Instant,
    ): Int = tx.jdbc.update(sql.deleteStartsBefore) { bind -> bind.instant(before) }
}
