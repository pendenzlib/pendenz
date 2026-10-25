package io.github.d4niella.pendenz.examples.demo.db.migration

import io.github.d4niella.pendenz.examples.demo.Billing
import io.github.d4niella.pendenz.schema.PostgresOperatorDdl
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context

/** The functions operators restart, complete, quarantine and cancel tasks with, generated from the mapping. */
@Suppress("ClassName") // Flyway derives version and description from the name
class V2__operator_functions : BaseJavaMigration() {
    override fun migrate(context: Context) {
        context.connection.createStatement().use { it.execute(PostgresOperatorDdl.functions(Billing.TABLE)) }
    }
}
