package io.github.d4niella.pendenz.examples.demo.db.migration

import io.github.d4niella.pendenz.examples.demo.Billing
import io.github.d4niella.pendenz.schema.PostgresDdl
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context

/**
 * The task table as a Flyway migration. A real service would paste the
 * generated DDL into a SQL migration and add its own columns and foreign
 * keys; generating it here keeps the demo in step with the library.
 */
@Suppress("ClassName") // Flyway derives version and description from the name
class V1__billing_task : BaseJavaMigration() {
    override fun migrate(context: Context) {
        context.connection.createStatement().use { statement ->
            statement.execute(PostgresDdl.createTable(Billing.TABLE))
            statement.execute(
                """CREATE TABLE "${Billing.ARCHIVE}" (LIKE "${Billing.TABLE.name}" INCLUDING ALL EXCLUDING INDEXES)""",
            )
        }
    }
}
