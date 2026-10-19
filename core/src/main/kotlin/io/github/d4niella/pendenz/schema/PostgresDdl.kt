package io.github.d4niella.pendenz.schema

import io.github.d4niella.pendenz.TaskStatus

/**
 * Generates PostgreSQL DDL for a [TaskTable] mapping.
 *
 * Pendenz does not run migrations. Copy the output into your Flyway or
 * Liquibase migration and adapt it: add columns, foreign keys, indexes.
 * Only the mapped columns and their types matter to the library.
 */
public object PostgresDdl {
    /** `CREATE TABLE` plus the index the claim query relies on. */
    @JvmStatic
    public fun createTable(table: TaskTable<*>): String {
        val c = table.columns
        val t = SqlIdentifiers.table(table.name)

        fun col(name: String) = SqlIdentifiers.column(name)
        val statusCheck = TaskStatus.entries.joinToString(", ") { "'${it.name}'" }
        val indexName = SqlIdentifiers.column(table.name.substringAfterLast('.') + "_claim_idx")
        return """
            |CREATE TABLE $t (
            |    ${col(c.id)} ${table.idType.columnDefinition} PRIMARY KEY,
            |    ${col(c.type)} varchar(200) NOT NULL,
            |    ${col(c.status)} varchar(32) NOT NULL CHECK (${col(c.status)} IN ($statusCheck)),
            |    ${col(c.payload)} jsonb NOT NULL,
            |    ${col(c.runAt)} timestamptz NOT NULL,
            |    ${col(c.attempts)} integer NOT NULL DEFAULT 0,
            |    ${col(c.lastError)} text,
            |    ${col(c.lockedBy)} varchar(200),
            |    ${col(c.lockedAt)} timestamptz,
            |    ${col(c.version)} bigint NOT NULL DEFAULT 0,
            |    ${col(c.createdAt)} timestamptz NOT NULL DEFAULT now()
            |);
            |
            |CREATE INDEX $indexName ON $t (${col(c.status)}, ${col(c.runAt)}, ${col(c.id)});
            """.trimMargin()
    }
}

/** Quotes identifiers so that mixed case and reserved words survive. */
internal object SqlIdentifiers {
    fun column(name: String): String = quote(name)

    fun table(name: String): String = name.split('.').joinToString(".") { quote(it) }

    private fun quote(part: String): String {
        require(!part.contains('"')) { "identifier must not contain a double quote: $part" }
        return "\"$part\""
    }
}
