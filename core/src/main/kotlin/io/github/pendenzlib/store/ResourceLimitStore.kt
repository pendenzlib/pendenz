package io.github.pendenzlib.store

import io.github.pendenzlib.ResourceLimit

/**
 * The resource limit table (ADR 0017) and the lock that makes limits exact
 * across workers. The limits themselves take effect inside the claim
 * statements of [TaskStore], which read the table.
 */
public interface ResourceLimitStore {
    /** Inserts or updates the limit for [limit].name. */
    public fun setLimit(
        tx: StoreTransaction,
        limit: ResourceLimit,
    )

    /** Inserts the limit only where no row for its name exists; an operator's change survives a restart. */
    public fun seedLimit(
        tx: StoreTransaction,
        limit: ResourceLimit,
    )

    /** Removes the limit for [name]; returns whether a row went. */
    public fun removeLimit(
        tx: StoreTransaction,
        name: String,
    ): Boolean

    /** Every limit, by name. */
    public fun limits(tx: StoreTransaction): List<ResourceLimit>

    /**
     * Serialises claims across workers for the rest of the transaction, so
     * that two polls cannot both count below a limit and both claim.
     */
    public fun lockClaims(tx: StoreTransaction)
}
