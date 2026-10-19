package io.github.d4niella.pendenz

import java.time.Duration

/**
 * What happens to a task type's rows once they reach a final state. See
 * ADR 0011.
 */
public sealed class Retention {
    /** Rows stay forever. The default. */
    public object Keep : Retention() {
        override fun toString(): String = "Retention.keep()"
    }

    /** Final rows are deleted [after] they finished. */
    public class Delete(
        public val after: Duration,
    ) : Retention() {
        init {
            require(!after.isNegative) { "retention period must not be negative" }
        }

        override fun toString(): String = "Retention.delete(after=$after)"
    }

    /**
     * Final rows are moved to [table] [after] they finished. The archive
     * table has the same columns in the same order as the task table.
     */
    public class Archive(
        public val after: Duration,
        public val table: String,
    ) : Retention() {
        init {
            require(!after.isNegative) { "retention period must not be negative" }
            require(table.isNotBlank()) { "archive table must not be blank" }
        }

        override fun toString(): String = "Retention.archive(after=$after, table=$table)"
    }

    public companion object {
        @JvmStatic
        public fun keep(): Retention = Keep

        @JvmStatic
        public fun delete(after: Duration): Retention = Delete(after)

        @JvmStatic
        public fun archive(
            after: Duration,
            table: String,
        ): Retention = Archive(after, table)
    }
}
