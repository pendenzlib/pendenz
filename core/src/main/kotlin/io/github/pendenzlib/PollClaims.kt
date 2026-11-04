package io.github.pendenzlib

import io.github.pendenzlib.store.ClaimGroup
import io.github.pendenzlib.store.ClaimTerms
import io.github.pendenzlib.store.ClaimedTask
import io.github.pendenzlib.store.ResourceFilter
import io.github.pendenzlib.store.StoreTransaction
import io.github.pendenzlib.store.TaskStore
import io.github.pendenzlib.store.likeRegex
import java.time.Instant

/**
 * The [Claims] of one poll: claims for the worker under its lock deadline
 * and, with resource limits, skips full resources and counts what it
 * claims, so the next call of the same poll sees it (ADR 0029). With
 * limits, it claims one task per statement, because a statement cannot
 * respect a limit for the rows it takes itself. Resources an active block
 * names are left out whatever their limit (ADR 0035).
 */
internal class PollClaims<ID : Any>(
    private val store: TaskStore<ID>,
    private val tx: StoreTransaction,
    private val types: Set<String>,
    private val workerId: String,
    private val now: Instant,
    private val lockedUntil: Instant,
    private val resources: PollResources,
) : Claims<ID> {
    override fun dueGroups(): List<ClaimGroup> = store.dueGroups(tx, types, terms())

    override fun fromGroup(
        group: ClaimGroup,
        limit: Int,
    ): List<ClaimedTask<ID>> = take(limit) { n -> store.claimFromGroup(tx, group, n, terms()) }

    override fun oldest(limit: Int): List<ClaimedTask<ID>> = take(limit) { n -> store.claim(tx, types, n, terms()) }

    private fun terms() = ClaimTerms(workerId, now, lockedUntil, resources.filter())

    private fun take(
        limit: Int,
        claim: (Int) -> List<ClaimedTask<ID>>,
    ): List<ClaimedTask<ID>> {
        if (limit <= 0) return emptyList()
        val tally = resources.tally ?: return claim(limit)
        val claimed = ArrayList<ClaimedTask<ID>>(limit)
        while (claimed.size < limit) {
            val next = claim(1)
            if (next.isEmpty()) break
            next.forEach { task -> task.resource?.let(tally::claimed) }
            claimed += next
        }
        return claimed
    }
}

/**
 * What one poll knows about resources: the limits with their running
 * tasks, when limits are enabled, and the names of the active blocks
 * (ADR 0029, ADR 0035).
 */
internal class PollResources(
    val tally: ResourceTally?,
    blocked: List<String>,
) {
    private val blockedExactly: Set<String>
    private val blockedPatterns: List<String>

    init {
        val (patterns, exact) = blocked.partition { name -> name.any { it in LIKE_SPECIALS } }
        blockedExactly = exact.toSet()
        blockedPatterns = patterns
    }

    fun filter(): ResourceFilter {
        val limits = tally?.filter() ?: ResourceFilter.NONE
        if (blockedExactly.isEmpty() && blockedPatterns.isEmpty()) return limits
        return ResourceFilter(
            limits.full + blockedExactly,
            limits.pausedPatterns,
            limits.otherPatterns,
            blockedPatterns,
        )
    }

    private companion object {
        /** A block name with one of these is a `LIKE` pattern; any other matches only itself. */
        const val LIKE_SPECIALS = "%_\\"
    }
}

/**
 * Running tasks per resource against the limit rows, read once per poll
 * under the claim lock; the longest matching pattern wins (ADR 0017).
 */
internal class ResourceTally(
    running: Map<String, Int>,
    private val limits: List<ResourceLimit>,
) {
    private val running = running.toMutableMap()
    private val patterns = limits.associate { it.name to likeRegex(it.name) }
    private val full = running.keys.filterTo(HashSet()) { atLimit(it) }

    fun claimed(resource: String) {
        running.merge(resource, 1, Int::plus)
        if (atLimit(resource)) full += resource
    }

    fun filter(): ResourceFilter {
        val (paused, other) = limits.partition { it.maxRunning == 0 }
        return ResourceFilter(full.toSet(), paused.map { it.name }, other.map { it.name })
    }

    private fun atLimit(resource: String): Boolean {
        val limit = limits.filter { patterns.getValue(it.name).matches(resource) }.maxByOrNull { it.name.length }
        return limit != null && (running[resource] ?: 0) >= limit.maxRunning
    }
}
