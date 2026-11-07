package io.github.pendenzlib.spring.boot

import io.github.pendenzlib.Pendenz
import io.github.pendenzlib.schema.QueueTables
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.SmartInitializingSingleton
import javax.sql.DataSource

/**
 * Checks at startup, after every singleton and so after Flyway or
 * Liquibase, that every table the queue uses exists with every column its
 * mapping names: the task table and the optional tables a
 * `PendenzCustomizer` turned on (ADR 0023, ADR 0037). Reads
 * `information_schema` only; the schema stays the application's
 * (principle 2). A queue without tables, in memory or on a store of the
 * application, is not checked.
 */
internal class SchemaCheck(
    private val dataSource: DataSource,
    private val pendenz: ObjectProvider<Pendenz<*>>,
) : SmartInitializingSingleton {
    override fun afterSingletonsInstantiated() {
        val tables = pendenz.getIfUnique()?.tables ?: return
        expected(tables).forEach(::check)
    }

    private fun check(table: Expected) {
        val present = columns(table.name)
        check(present.isNotEmpty()) {
            "the ${table.kind} ${table.name} does not exist; create it with a migration " +
                "(PostgresDdl.${table.ddl}), or set pendenz.schema-check=false"
        }
        val missing = table.columns.filterNot { it in present }
        check(missing.isEmpty()) { "the ${table.kind} ${table.name} lacks the columns $missing" }
    }

    /** The table's column names, compared exactly as the core quotes them (see `TaskTable`). */
    private fun columns(table: String): Set<String> {
        val schema = table.substringBeforeLast('.', missingDelimiterValue = "")
        val name = table.substringAfterLast('.')
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

    /** A table to check, with the words and the DDL function a failure names. */
    private class Expected(
        val kind: String,
        val name: String,
        val columns: List<String>,
        val ddl: String,
    )

    private companion object {
        fun expected(tables: QueueTables<*>): List<Expected> =
            listOfNotNull(
                Expected("task table", tables.task.name, tables.task.columns.all, "createTable"),
                tables.cycles?.let { Expected("cycle table", it.name, it.columns.all, "createCycleTable") },
                tables.resourceLimits?.let {
                    Expected("resource limit table", it.name, it.columns.all, "createResourceLimitTable")
                },
                tables.resourceBlocks?.let {
                    Expected("resource block table", it.name, it.columns.all, "createResourceBlockTable")
                },
            )
    }
}
