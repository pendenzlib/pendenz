package io.github.d4niella.pendenz

/**
 * The lane a cycle's tasks share: `cycle:` and the cycle's id, so that a
 * cycle 42 and a tenant lane "42" never meet (ADR 0016, revised). The
 * format is documented for operators in `docs/sql-recipes.md`.
 */
internal object CycleLane {
    const val PREFIX: String = "cycle:"

    /** Whether [lane] is a cycle's lane; only `startCycle` and follow-ups inside a cycle use those (ADR 0018). */
    internal fun isCycleLane(lane: String?): Boolean = lane?.startsWith(PREFIX) == true

    /** The lane of the cycle with [id]. */
    fun of(id: Any): String = PREFIX + id

    /** A SQL expression for the lane of the cycle whose id is [idExpression]. */
    fun sql(idExpression: String): String = "'$PREFIX' || $idExpression::text"
}
