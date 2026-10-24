package io.github.d4niella.pendenz.spring.boot

import io.github.d4niella.pendenz.schema.TaskTable
import org.springframework.beans.factory.SmartInitializingSingleton
import javax.sql.DataSource

/**
 * Checks at startup, after every singleton and so after Flyway or
 * Liquibase, that the task table exists with every column its mapping
 * names. Reads `information_schema` only; the schema stays the
 * application's (principle 2).
 */
internal class SchemaCheck(
    private val dataSource: DataSource,
    private val table: TaskTable<*>,
) : SmartInitializingSingleton {
    override fun afterSingletonsInstantiated() {
        val present = columns()
        check(present.isNotEmpty()) {
            "the task table ${table.name} does not exist; create it with a migration (PostgresDdl.createTable), " +
                "or set pendenz.schema-check=false"
        }
        val missing = table.columns.all.filterNot { it.lowercase() in present }
        check(missing.isEmpty()) { "the task table ${table.name} lacks the columns $missing" }
    }

    /** The table's column names; unquoted names are folded to lower case, as PostgreSQL does. */
    private fun columns(): Set<String> {
        val schema = table.name.substringBefore('.', missingDelimiterValue = "").lowercase()
        val name = table.name.substringAfter('.').lowercase()
        val sql =
            """
            SELECT column_name FROM information_schema.columns
            WHERE table_name = ?
              AND (table_schema = ? OR (? = '' AND table_schema = ANY (current_schemas(false))))
            """.trimIndent()
        return dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                listOf(name, schema, schema).forEachIndexed { index, value -> statement.setString(index + 1, value) }
                statement.executeQuery().use { rs ->
                    buildSet { while (rs.next()) add(rs.getString(1)) }
                }
            }
        }
    }
}
