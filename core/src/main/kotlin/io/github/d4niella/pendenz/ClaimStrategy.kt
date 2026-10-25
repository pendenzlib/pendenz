package io.github.d4niella.pendenz

import io.github.d4niella.pendenz.store.ClaimGroup
import io.github.d4niella.pendenz.store.ClaimedTask

/**
 * Which due tasks a worker takes when it has free permits. See ADR 0015.
 *
 * The strategy decides the order and the worker does the claiming through
 * [Claims], inside its poll transaction, which is why the strategy does
 * not suspend. Worker id, lock deadline and resource limits are the
 * worker's business, so no strategy can break a limit (ADR 0029).
 * `FairClaim` is the default; `FifoClaim` is arrival order.
 */
public interface ClaimStrategy {
    /** Claims up to [ClaimRequest.limit] due tasks through [claims]. */
    public fun <ID : Any> claim(
        claims: Claims<ID>,
        request: ClaimRequest,
    ): List<ClaimedTask<ID>>
}

/**
 * What a [ClaimStrategy] can do in one poll. Every call claims for the
 * polling worker, under its lock deadline, and skips tasks whose resource
 * is at its limit, counting what earlier calls of the same poll claimed.
 */
public interface Claims<ID : Any> {
    /** The `(priority, type, lane)` groups with due tasks the worker may take, highest priority first. */
    public fun dueGroups(): List<ClaimGroup>

    /** Claims up to [limit] of the oldest due tasks of [group]. */
    public fun fromGroup(
        group: ClaimGroup,
        limit: Int,
    ): List<ClaimedTask<ID>>

    /** Claims up to [limit] of the oldest due tasks of the worker's types, whatever their lane. */
    public fun oldest(limit: Int): List<ClaimedTask<ID>>
}

/**
 * What a worker asks a [ClaimStrategy] for.
 *
 * @property types the task types the worker handles, with the batch size each one allows per lane visit.
 * @property limit how many tasks the worker can take in all: its free permits.
 */
public class ClaimRequest(
    public val types: Map<String, Int>,
    public val limit: Int,
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
        claims: Claims<ID>,
        request: ClaimRequest,
    ): List<ClaimedTask<ID>> = claims.oldest(request.limit)
}

/**
 * Priority as strict tiers, round-robin across lanes within a tier.
 *
 * Each poll asks which `(priority, type, lane)` groups have due work,
 * takes the highest priority present, visits its groups in turn starting
 * after the group visited last, and claims the oldest due tasks from each,
 * as many as the type's `claimBatchSize` allows, until the permits are
 * used up. A group that runs dry drops out of the round; when a whole
 * tier is dry, the next tier gets its turn. A poll ends after as many
 * visits that claimed nothing as it had free permits; the next poll
 * continues after the group visited last (ADR 0029).
 *
 * One instance keeps one cursor. Workers that share options share it,
 * which makes them fairer together.
 */
public class FairClaim : ClaimStrategy {
    @Volatile
    private var lastVisited: ClaimGroup? = null

    override fun <ID : Any> claim(
        claims: Claims<ID>,
        request: ClaimRequest,
    ): List<ClaimedTask<ID>> {
        if (request.limit <= 0 || request.types.isEmpty()) return emptyList()
        val poll = Poll<ID>(request.limit)
        val tiers = claims.dueGroups().groupBy { it.priority }.toSortedMap(reverseOrder())
        for (tier in tiers.values) {
            roundRobin(claims, request, tier, poll)
            if (poll.done) break
        }
        return poll.claimed
    }

    private fun <ID : Any> roundRobin(
        claims: Claims<ID>,
        request: ClaimRequest,
        groups: List<ClaimGroup>,
        poll: Poll<ID>,
    ) {
        val rotation = ArrayDeque(startAfterCursor(groups))
        while (!poll.done && rotation.isNotEmpty()) {
            val group = rotation.removeFirst()
            val batch = minOf(request.batchSizeOf(group.type), poll.free)
            val taken = claims.fromGroup(group, batch)
            lastVisited = group
            if (taken.isEmpty()) {
                poll.emptyVisits++
            } else {
                poll.claimed += taken
                rotation.addLast(group)
            }
        }
    }

    /** What one poll has claimed, and how many of its visits claimed nothing; both end it. */
    private class Poll<ID : Any>(
        private val limit: Int,
    ) {
        val claimed = ArrayList<ClaimedTask<ID>>(limit)
        var emptyVisits = 0
        val free get() = limit - claimed.size
        val done get() = claimed.size >= limit || emptyVisits >= limit
    }

    /**
     * The groups in their given order, rotated so that the one after the
     * cursor comes first. When the cursor's group has run dry and is gone,
     * the rotation starts where it would have stood, so the next lane gets
     * its turn instead of the first.
     */
    private fun startAfterCursor(groups: List<ClaimGroup>): List<ClaimGroup> {
        val cursor = lastVisited ?: return groups
        val after = groups.indexOfFirst { ORDER.compare(it, cursor) > 0 }
        if (after <= 0) return groups
        return groups.drop(after) + groups.take(after)
    }

    private companion object {
        /** The order of [Claims.dueGroups] within one tier: type, then lane with the singles last. */
        val ORDER: Comparator<ClaimGroup> = compareBy<ClaimGroup> { it.type }.thenBy(nullsLast()) { it.lane }
    }
}
