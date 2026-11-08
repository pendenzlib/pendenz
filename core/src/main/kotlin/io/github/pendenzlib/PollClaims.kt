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
 * and, with resource limits or rates, skips full resources and counts what
 * it claims, so the next call of the same poll sees it (ADR 0029, ADR
 * 0040). With limits or rates, it claims one task per statement, because a
 * statement cannot respect a limit for the rows it takes itself. Resources
 * an active block names are left out whatever their limit (ADR 0035).
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
        if (!resources.counting) return claim(limit)
        val claimed = ArrayList<ClaimedTask<ID>>(limit)
        while (claimed.size < limit) {
            val next = claim(1)
            if (next.isEmpty()) break
            next.forEach { task -> task.resource?.let(resources::claimed) }
            claimed += next
        }
        return claimed
    }
}

/**
 * What one poll knows about resources: the limits with their running
 * tasks, when limits are enabled, the rates with their starts, when rates
 * are enabled, and the names of the active blocks (ADR 0029, ADR 0035,
 * ADR 0040).
 */
internal class PollResources(
    private val tally: ResourceTally?,
    blocked: List<String>,
    private val rates: RateTally? = null,
) {
    /** Whether claims are counted one by one, so that a limit or rate holds within the poll. */
    val counting: Boolean = tally != null || rates != null

    /** The resource values of the claimed tasks that count against a rate, one entry per start. */
    val starts: List<String>
        get() = rates?.starts.orEmpty()

    /** Counts a claimed task of [resource] against its limit and its rate. */
    fun claimed(resource: String) {
        tally?.claimed(resource)
        rates?.claimed(resource)
    }

    private val blockedExactly: Set<String>
    private val blockedPatterns: List<String>

    init {
        val (patterns, exact) = blocked.partition { name -> name.any { it in LIKE_SPECIALS } }
        blockedExactly = exact.toSet()
        blockedPatterns = patterns
    }

    fun filter(): ResourceFilter {
        val limits = tally?.filter() ?: ResourceFilter.NONE
        val used = rates?.used().orEmpty()
        if (blockedExactly.isEmpty() && blockedPatterns.isEmpty() && used.isEmpty()) return limits
        return ResourceFilter(
            limits.full + used + blockedExactly,
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

/**
 * Starts per resource value inside the window of their rate, read once
 * per poll under the claim lock, and the starts this poll adds; the
 * longest matching name wins, ties by name, as for limits (ADR 0040).
 *
 * @param rates by name, as the store lists them.
 */
internal class RateTally(
    starts: Map<String, Int>,
    private val rates: List<ResourceRate>,
) {
    private val counted = starts.toMutableMap()
    private val patterns = rates.associate { it.name to likeRegex(it.name) }
    private val exhausted = counted.keys.filterTo(HashSet()) { usedUp(it) }
    private val added = ArrayList<String>()

    /** The values of the starts this poll made, one entry per start, for the start table. */
    val starts: List<String>
        get() = added

    fun claimed(resource: String) {
        if (rateFor(resource) == null) return
        added += resource
        counted.merge(resource, 1, Int::plus)
        if (usedUp(resource)) exhausted += resource
    }

    /** The resource values that used their rate. */
    fun used(): Set<String> = exhausted.toSet()

    private fun usedUp(resource: String): Boolean {
        val rate = rateFor(resource) ?: return false
        return (counted[resource] ?: 0) >= rate.maxStarts
    }

    private fun rateFor(resource: String): ResourceRate? =
        rates.filter { patterns.getValue(it.name).matches(resource) }.maxByOrNull { it.name.length }
}
