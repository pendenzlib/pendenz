package io.github.pendenzlib

import java.util.Collections

/**
 * Lifecycle state of a cycle, a run of tasks over a dataset (ADR 0016).
 * Stored verbatim in the cycle table's status column.
 */
public enum class CycleStatus {
    /** Its tasks are claimed and run. */
    ACTIVE,

    /** Its waiting tasks are set aside until the cycle is resumed; running ones finish. */
    PAUSED,

    /** Its tasks were cancelled; nothing of it runs again. */
    CANCELLED,

    /** Every task of it reached a final state. Derived by maintenance, not declared. */
    FINISHED,
    ;

    public val isFinal: Boolean
        get() = this == CANCELLED || this == FINISHED
}

/**
 * The allowed transitions between [CycleStatus] values, the single source of
 * truth: pausing, resuming, cancelling and the finishing sweep take their
 * source states from it, in the API and in the generated SQL (ADR 0031).
 */
public object CycleStatusTransitions {
    private val allowed: Map<CycleStatus, Set<CycleStatus>> =
        mapOf(
            CycleStatus.ACTIVE to setOf(CycleStatus.PAUSED, CycleStatus.CANCELLED, CycleStatus.FINISHED),
            CycleStatus.PAUSED to setOf(CycleStatus.ACTIVE, CycleStatus.CANCELLED),
            CycleStatus.CANCELLED to emptySet(),
            CycleStatus.FINISHED to emptySet(),
        ).mapValues { (_, targets) -> Collections.unmodifiableSet(targets) }

    @JvmStatic
    public fun targetsFrom(from: CycleStatus): Set<CycleStatus> = allowed.getValue(from)

    /** All states a cycle may move to [to] from. */
    @JvmStatic
    public fun sourcesOf(to: CycleStatus): Set<CycleStatus> =
        Collections.unmodifiableSet(CycleStatus.entries.filterTo(LinkedHashSet()) { isAllowed(it, to) })

    @JvmStatic
    public fun isAllowed(
        from: CycleStatus,
        to: CycleStatus,
    ): Boolean = to in targetsFrom(from)
}
