package io.github.d4niella.pendenz

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
