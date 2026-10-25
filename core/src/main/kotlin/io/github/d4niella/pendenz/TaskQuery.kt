package io.github.d4niella.pendenz

import java.time.Instant

/**
 * Which tasks [Pendenz.tasks] returns: those matching every given filter,
 * newest first, at most [limit]. An empty set or a null value does not
 * filter. For the next page, ask with [after] and the last task of this
 * one; the pages neither skip nor repeat a task while new ones arrive.
 */
@Suppress("LongParameterList") // one parameter per filter, by design
public class TaskQuery
    @JvmOverloads
    constructor(
        /** Only tasks this actor created; follow-ups carry their parent's creator (ADR 0019). */
        public val createdBy: String? = null,
        public val types: Set<String> = emptySet(),
        public val statuses: Set<TaskStatus> = emptySet(),
        public val correlationId: String? = null,
        public val limit: Int = DEFAULT_LIMIT,
        /** Only tasks in this lane, such as a cycle's. */
        public val lane: String? = null,
        /** Only follow-ups of the task with this id (ADR 0019). */
        public val parentId: Any? = null,
        /** Only tasks older than this one in the query's order; see [after]. */
        public val olderThan: TaskCursor? = null,
    ) {
        init {
            require(limit in 1..MAX_LIMIT) { "limit must be between 1 and $MAX_LIMIT, was $limit" }
        }

        /** The same query for the page after [last], the last task of the page before. */
        public fun after(last: TaskRecord<*>): TaskQuery =
            TaskQuery(
                createdBy,
                types,
                statuses,
                correlationId,
                limit,
                lane,
                parentId,
                TaskCursor(last.createdAt, last.id),
            )

        public companion object {
            public const val DEFAULT_LIMIT: Int = 50
            public const val MAX_LIMIT: Int = 1000
        }
    }

/** A position in the order of [TaskQuery]: newest first, by creation time and then by id. */
public class TaskCursor(
    public val createdAt: Instant,
    public val id: Any,
) {
    override fun toString(): String = "TaskCursor(createdAt=$createdAt, id=$id)"
}
