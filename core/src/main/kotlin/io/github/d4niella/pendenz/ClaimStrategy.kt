package io.github.d4niella.pendenz

import io.github.d4niella.pendenz.store.ClaimGroup
import io.github.d4niella.pendenz.store.ClaimedTask
import io.github.d4niella.pendenz.store.StoreTransaction
import io.github.d4niella.pendenz.store.TaskStore
import java.time.Instant

/**
 * Which due tasks a worker takes when it has free permits. See ADR 0015.
 *
 * The strategy composes store operations inside the worker's poll
 * transaction, which is why it does not suspend. `FairClaim` is the
 * default; `FifoClaim` is arrival order.
 */
public interface ClaimStrategy {
    /** Claims up to [limit] due tasks of [types] for [workerId]. */
    public fun <ID : Any> claim(
        store: TaskStore<ID>,
        tx: StoreTransaction,
        workerId: String,
        types: Set<String>,
        limit: Int,
        now: Instant,
    ): List<ClaimedTask<ID>>
}

/**
 * The oldest due tasks first, whatever their lane: first come, first
 * served. A cycle of ten thousand tasks makes a single task enqueued a
 * second later wait behind all of them. Choose it only for an ordering
 * requirement that fairness would break; `priority` is still honoured.
 */
public object FifoClaim : ClaimStrategy {
    override fun <ID : Any> claim(
        store: TaskStore<ID>,
        tx: StoreTransaction,
        workerId: String,
        types: Set<String>,
        limit: Int,
        now: Instant,
    ): List<ClaimedTask<ID>> = store.claim(tx, workerId, types, limit, now)
}

/**
 * Priority as strict tiers, round-robin across lanes within a tier.
 *
 * Each poll asks which `(priority, type, lane)` groups have due work,
 * takes the highest priority present, visits its groups in turn starting
 * after the group visited last, and claims the oldest due task from each
 * until the permits are used up. A group that runs dry drops out of the
 * round; when a whole tier is dry, the next tier gets its turn.
 *
 * One instance keeps one cursor. Workers that share options share it,
 * which makes them fairer together.
 */
public class FairClaim : ClaimStrategy {
    @Volatile
    private var lastVisited: ClaimGroup? = null

    override fun <ID : Any> claim(
        store: TaskStore<ID>,
        tx: StoreTransaction,
        workerId: String,
        types: Set<String>,
        limit: Int,
        now: Instant,
    ): List<ClaimedTask<ID>> {
        if (limit <= 0 || types.isEmpty()) return emptyList()
        val claimed = ArrayList<ClaimedTask<ID>>(limit)
        val tiers = store.dueGroups(tx, types, now).groupBy { it.priority }.toSortedMap(reverseOrder())
        for (tier in tiers.values) {
            claimed += roundRobin(store, tx, workerId, tier, limit - claimed.size, now)
            if (claimed.size >= limit) break
        }
        return claimed
    }

    private fun <ID : Any> roundRobin(
        store: TaskStore<ID>,
        tx: StoreTransaction,
        workerId: String,
        groups: List<ClaimGroup>,
        limit: Int,
        now: Instant,
    ): List<ClaimedTask<ID>> {
        val claimed = ArrayList<ClaimedTask<ID>>(limit)
        val rotation = ArrayDeque(startAfterCursor(groups))
        while (claimed.size < limit && rotation.isNotEmpty()) {
            val group = rotation.removeFirst()
            val taken = store.claimFromGroup(tx, workerId, group, 1, now)
            if (taken.isEmpty()) continue
            claimed += taken
            lastVisited = group
            rotation.addLast(group)
        }
        return claimed
    }

    /** The groups in their given order, rotated so that the one after the cursor comes first. */
    private fun startAfterCursor(groups: List<ClaimGroup>): List<ClaimGroup> {
        val cursor = lastVisited ?: return groups
        val index = groups.indexOfFirst { it.type == cursor.type && it.lane == cursor.lane }
        if (index < 0) return groups
        return groups.drop(index + 1) + groups.take(index + 1)
    }
}
