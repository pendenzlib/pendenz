package io.github.pendenzlib.store

import io.github.pendenzlib.ResourceRate
import java.time.Instant
import java.util.TreeMap

/**
 * The rate half of [InMemoryTaskStore] (ADR 0040). The claims do not read
 * it: the poll counts the starts and hands the resources that used their
 * rate to the claims in their [ResourceFilter], as with the database.
 *
 * @param requireOwn refuses a transaction handle of another store.
 */
internal class InMemoryResourceRateStore(
    private val requireOwn: (StoreTransaction) -> Unit,
) : ResourceRateStore {
    private val rows = TreeMap<String, ResourceRate>()
    private val starts = ArrayList<Start>()
    private val patterns = HashMap<String, Regex>()

    private class Start(
        val resource: String,
        val at: Instant,
    )

    private inline fun <T> locked(
        tx: StoreTransaction,
        block: () -> T,
    ): T {
        requireOwn(tx)
        return synchronized(rows, block)
    }

    override fun setRate(
        tx: StoreTransaction,
        rate: ResourceRate,
    ): Unit = locked(tx) { rows[rate.name] = rate }

    override fun seedRate(
        tx: StoreTransaction,
        rate: ResourceRate,
    ): Unit = locked(tx) { rows.putIfAbsent(rate.name, rate) }

    override fun removeRate(
        tx: StoreTransaction,
        name: String,
    ): Boolean = locked(tx) { rows.remove(name) != null }

    override fun rates(tx: StoreTransaction): List<ResourceRate> = locked(tx) { rows.values.toList() }

    override fun startsPerResource(
        tx: StoreTransaction,
        now: Instant,
    ): Map<String, Int> =
        locked(tx) {
            starts
                .filter { start -> rateFor(start.resource)?.let { start.at.isAfter(now.minus(it.per)) } == true }
                .groupingBy { it.resource }
                .eachCount()
        }

    override fun recordStarts(
        tx: StoreTransaction,
        resources: List<String>,
        now: Instant,
    ): Unit = locked(tx) { resources.forEach { starts += Start(it, now) } }

    override fun deleteStartsBefore(
        tx: StoreTransaction,
        before: Instant,
    ): Int =
        locked(tx) {
            val size = starts.size
            starts.removeAll { !it.at.isAfter(before) }
            size - starts.size
        }

    /** The longest matching rate, `LIKE` semantics with `%` and `_`, or null. */
    private fun rateFor(resource: String): ResourceRate? =
        rows.values
            .filter { patterns.getOrPut(it.name) { likeRegex(it.name) }.matches(resource) }
            .maxByOrNull { it.name.length }
}
