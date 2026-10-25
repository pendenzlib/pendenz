package io.github.d4niella.pendenz

/**
 * The resource of a cycle's tasks (ADR 0017): the type's choice or one
 * name for the run. A resource per row comes from the query, see
 * [CycleTasks.query].
 */
public sealed class CycleResource {
    /** Whatever the task type decides; for a query, the type's fixed resource if it has one, else none. */
    public object Default : CycleResource()

    /** Every task of the cycle runs against [name]. */
    public class Fixed(
        public val name: String,
    ) : CycleResource()

    public companion object {
        @JvmStatic
        public fun fixed(name: String): CycleResource = Fixed(name)
    }
}

/**
 * Where the tasks of a cycle come from (ADR 0016): a SQL query the
 * application writes, or a list of payloads for small runs.
 */

public sealed class CycleTasks<P : Any> {
    /**
     * A query that yields one row per task, with the payload columns of the
     * task type in their declared order and nothing else. Pendenz wraps it
     * in `INSERT ... SELECT`, so the rows never leave the database.
     *
     * @property resource a SQL expression over the query's columns, aliased
     *   `q`, for each task's resource, such as `'customer:' || q.customer_id`;
     *   null leaves the resource to the cycle and the task type.
     */
    public class Query<P : Any>(
        public val sql: String,
        public val resource: String? = null,
    ) : CycleTasks<P>() {
        init {
            require(sql.isNotBlank()) { "the query must not be blank" }
        }
    }

    /** Payloads given by the application, inserted one by one in the same transaction. */
    public class Payloads<P : Any>(
        public val payloads: List<P>,
    ) : CycleTasks<P>()

    public companion object {
        /** Tasks from [sql]; [resource] is an optional SQL expression per row, see [Query.resource]. */
        @JvmStatic
        @JvmOverloads
        public fun <P : Any> query(
            sql: String,
            resource: String? = null,
        ): CycleTasks<P> = Query(sql, resource)

        @JvmStatic
        public fun <P : Any> of(payloads: List<P>): CycleTasks<P> = Payloads(payloads)
    }
}
