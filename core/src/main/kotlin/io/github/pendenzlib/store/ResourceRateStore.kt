package io.github.pendenzlib.store

import io.github.pendenzlib.ResourceRate
import java.time.Instant

/**
 * The resource rate table and the start table (ADR 0040). The rates take
 * effect in the claims of [TaskStore]: the poll counts the starts once
 * under the claim lock of [ResourceLimitStore] and leaves resources that
 * used their rate out through [ResourceFilter].
 */
public interface ResourceRateStore {
    /** Inserts or updates the rate for [rate].name. */
    public fun setRate(
        tx: StoreTransaction,
        rate: ResourceRate,
    )

    /** Inserts the rate only where no row for its name exists; an operator's change survives a restart. */
    public fun seedRate(
        tx: StoreTransaction,
        rate: ResourceRate,
    )

    /** Removes the rate for [name]; returns whether a row went. */
    public fun removeRate(
        tx: StoreTransaction,
        name: String,
    ): Boolean

    /** Every rate, by name. */
    public fun rates(tx: StoreTransaction): List<ResourceRate>

    /**
     * The starts per resource value inside the window of the rate that
     * matches it, the longest name winning: those after `now - per`. Values
     * without a rate, or without starts inside their window, are left out.
     */
    public fun startsPerResource(
        tx: StoreTransaction,
        now: Instant,
    ): Map<String, Int>

    /** Records one start at [now] per entry of [resources]; a value listed twice started twice. */
    public fun recordStarts(
        tx: StoreTransaction,
        resources: List<String>,
        now: Instant,
    )

    /** Deletes the starts at or before [before]; returns how many. */
    public fun deleteStartsBefore(
        tx: StoreTransaction,
        before: Instant,
    ): Int
}
