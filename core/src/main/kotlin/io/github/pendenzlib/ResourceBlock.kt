package io.github.pendenzlib

import java.time.Instant

/**
 * A row of the resource block table (ADR 0035): no task of a resource
 * matching [name] is claimed before [blockedUntil].
 */
public class ResourceBlock(
    /** An exact resource name or a `LIKE` pattern such as `partner-api:%`. */
    public val name: String,
    public val blockedUntil: Instant,
    /** Why, as the handler or the operator gave it. */
    public val reason: String,
    /** When the block was last set or extended. */
    public val blockedAt: Instant,
) {
    init {
        require(name.isNotBlank()) { "resource name must not be blank" }
    }

    override fun equals(other: Any?): Boolean =
        other is ResourceBlock && other.name == name && other.blockedUntil == blockedUntil &&
            other.reason == reason && other.blockedAt == blockedAt

    override fun hashCode(): Int = (name.hashCode() * 31 + blockedUntil.hashCode()) * 31 + reason.hashCode()

    override fun toString(): String = "ResourceBlock($name until $blockedUntil: $reason)"
}
