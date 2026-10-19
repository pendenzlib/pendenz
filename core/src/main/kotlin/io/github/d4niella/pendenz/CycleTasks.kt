package io.github.d4niella.pendenz

/** The resource of a cycle's tasks (ADR 0017): the type's choice, one name for the run, or a SQL expression per row. */
public sealed class CycleResource {
    /** Whatever the task type decides; for a query, the type's fixed resource if it has one, else none. */
    public object Default : CycleResource()

    /** Every task of the cycle runs against [name]. */
    public class Fixed(
        public val name: String,
    ) : CycleResource()

    /** A SQL expression over the query's columns, aliased `q`, such as `'customer:' || q.customer_id`. */
    public class Expression(
        public val sql: String,
    ) : CycleResource()

    public companion object {
        @JvmStatic
        public fun fixed(name: String): CycleResource = Fixed(name)

        @JvmStatic
        public fun expression(sql: String): CycleResource = Expression(sql)
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
     */
    public class Query<P : Any>(
        public val sql: String,
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
        @JvmStatic
        public fun <P : Any> query(sql: String): CycleTasks<P> = Query(sql)

        @JvmStatic
        public fun <P : Any> of(payloads: List<P>): CycleTasks<P> = Payloads(payloads)
    }
}
