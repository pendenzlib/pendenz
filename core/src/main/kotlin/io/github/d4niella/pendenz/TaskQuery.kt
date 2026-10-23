package io.github.d4niella.pendenz

/**
 * Which tasks [Pendenz.tasks] returns: those matching every given filter,
 * newest first, at most [limit]. An empty set or a null value does not
 * filter.
 */
public class TaskQuery
    @JvmOverloads
    constructor(
        /** Only tasks this actor created; follow-ups carry their parent's creator (ADR 0019). */
        public val createdBy: String? = null,
        public val types: Set<String> = emptySet(),
        public val statuses: Set<TaskStatus> = emptySet(),
        public val correlationId: String? = null,
        public val limit: Int = DEFAULT_LIMIT,
    ) {
        init {
            require(limit in 1..MAX_LIMIT) { "limit must be between 1 and $MAX_LIMIT, was $limit" }
        }

        public companion object {
            public const val DEFAULT_LIMIT: Int = 50
            public const val MAX_LIMIT: Int = 1000
        }
    }
