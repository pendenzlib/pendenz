package io.github.d4niella.pendenz.schema

import io.github.d4niella.pendenz.Pendenz
import io.github.d4niella.pendenz.TaskStatus
import io.github.d4niella.pendenz.TaskType
import io.github.d4niella.pendenz.testing.PostgresDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

/**
 * A table name close to PostgreSQL's limit of 63 bytes: every name derived
 * from it is cut and given a hash, so nothing collides and the notify
 * trigger does not fail (ADR 0028).
 */
class LongTableNameEndToEndTest {
    @Serializable
    data class Greeting(
        val name: String,
    )

    private val greet = TaskType.json<Greeting>("greet")

    @Test
    fun `generated DDL - table name of sixty characters - every index and view exists, an enqueue succeeds`() =
        runBlocking<Unit> {
            // Arrange
            val table = TaskTable(name = longName())
            db.sql(PostgresDdl.createTable(table, greet))
            db.sql(PostgresOperatorDdl.views(table))
            db.sql(PostgresOperatorDdl.functions(table))
            db.sql(PostgresDdl.notifyTrigger(table))
            val pendenz =
                Pendenz.create(table) {
                    dataSource(db.dataSource)
                    register(greet) { _, _ -> }
                }

            // Act
            val id = pendenz.enqueue(greet, Greeting("Ada"))

            // Assert: the primary key and the nine generated indexes, none lost to a cut name
            assertThat(pendenz.find(id)!!.status).isEqualTo(TaskStatus.PENDING)
            assertThat(db.count("SELECT count(*) FROM pg_indexes WHERE tablename = '${table.name}'")).isEqualTo(10)
            assertThat(db.count("SELECT count(*) FROM pg_views WHERE viewname LIKE '${table.name.take(40)}%'"))
                .isEqualTo(3)
        }

    @Test
    fun `notify channel - table name of sixty characters - fits into 63 bytes`() {
        // Arrange
        val table = TaskTable(name = longName())

        // Act
        val channel = PostgresDdl.notifyChannel(table)

        // Assert
        assertThat(channel.toByteArray(Charsets.UTF_8).size).isLessThanOrEqualTo(63)
    }

    /** Sixty characters, unique per call, as a long but legal table name. */
    private fun longName(): String {
        val unique = db.uniqueSuffix()
        return "customer_onboarding_document_verification_".padEnd(60 - unique.length, 'x') + unique
    }

    companion object {
        @JvmField
        @RegisterExtension
        val db = PostgresDatabase()
    }
}
