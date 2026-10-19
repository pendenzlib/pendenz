package io.github.d4niella.pendenz

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

/** The allowed transitions between [CycleStatus] values, the single source of truth. */
public object CycleStatusTransitions {
    private val allowed: Map<CycleStatus, Set<CycleStatus>> =
        mapOf(
            CycleStatus.ACTIVE to setOf(CycleStatus.PAUSED, CycleStatus.CANCELLED, CycleStatus.FINISHED),
            CycleStatus.PAUSED to setOf(CycleStatus.ACTIVE, CycleStatus.CANCELLED),
            CycleStatus.CANCELLED to emptySet(),
            CycleStatus.FINISHED to emptySet(),
        )

    @JvmStatic
    public fun targetsFrom(from: CycleStatus): Set<CycleStatus> = allowed.getValue(from)

    @JvmStatic
    public fun isAllowed(
        from: CycleStatus,
        to: CycleStatus,
    ): Boolean = to in targetsFrom(from)
}
