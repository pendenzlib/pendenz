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
    /** Claims up to [ClaimRequest.limit] due tasks for the worker described by [request]. */
    public fun <ID : Any> claim(
        store: TaskStore<ID>,
        tx: StoreTransaction,
        request: ClaimRequest,
    ): List<ClaimedTask<ID>>
}

/**
 * What a worker asks a [ClaimStrategy] for.
 *
 * @property workerId who claims.
 * @property types the task types the worker handles, with the batch size each one allows per lane visit.
 * @property limit how many tasks the worker can take in all: its free permits.
 * @property now the worker's clock.
 * @property lockedUntil the deadline of the claimed tasks' lock: [now] plus the worker's lock timeout. See ADR 0027.
 */
public class ClaimRequest(
    public val workerId: String,
    public val types: Map<String, Int>,
    public val limit: Int,
    public val now: Instant,
    public val lockedUntil: Instant,
) {
    /** The batch size of [type], 1 when unknown. */
    public fun batchSizeOf(type: String): Int = types[type] ?: 1
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
        request: ClaimRequest,
    ): List<ClaimedTask<ID>> =
        store.claim(tx, request.workerId, request.types.keys, request.limit, request.now, request.lockedUntil)
}

/**
 * Priority as strict tiers, round-robin across lanes within a tier.
 *
 * Each poll asks which `(priority, type, lane)` groups have due work,
 * takes the highest priority present, visits its groups in turn starting
 * after the group visited last, and claims the oldest due tasks from each,
 * as many as the type's `claimBatchSize` allows, until the permits are
 * used up. A group that runs dry drops out of the round; when a whole
 * tier is dry, the next tier gets its turn.
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
        request: ClaimRequest,
    ): List<ClaimedTask<ID>> {
        if (request.limit <= 0 || request.types.isEmpty()) return emptyList()
        val claimed = ArrayList<ClaimedTask<ID>>(request.limit)
        val groups = store.dueGroups(tx, request.types.keys, request.now)
        val tiers = groups.groupBy { it.priority }.toSortedMap(reverseOrder())
        for (tier in tiers.values) {
            claimed += roundRobin(store, tx, request, tier, request.limit - claimed.size)
            if (claimed.size >= request.limit) break
        }
        return claimed
    }

    private fun <ID : Any> roundRobin(
        store: TaskStore<ID>,
        tx: StoreTransaction,
        request: ClaimRequest,
        groups: List<ClaimGroup>,
        limit: Int,
    ): List<ClaimedTask<ID>> {
        val claimed = ArrayList<ClaimedTask<ID>>(limit)
        val rotation = ArrayDeque(startAfterCursor(groups))
        while (claimed.size < limit && rotation.isNotEmpty()) {
            val group = rotation.removeFirst()
            val batch = minOf(request.batchSizeOf(group.type), limit - claimed.size)
            val taken = store.claimFromGroup(tx, request.workerId, group, batch, request.now, request.lockedUntil)
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
