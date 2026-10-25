package io.github.d4niella.pendenz.examples.catalog

import io.github.d4niella.pendenz.TaskStatus
import io.github.d4niella.pendenz.examples.catalog.testing.KeywordModel
import io.github.d4niella.pendenz.examples.support.DatabaseAccess
import io.github.d4niella.pendenz.testing.PostgresDatabase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.time.Duration

/**
 * The demo cycle end to end against PostgreSQL, with a model that knows
 * the catalogue's keywords. 40 generated products and the three broken
 * ones; the injected faults hit products 11, 22 and 33 (overloaded) and
 * 13, 26 and 39 (garbled). Each test gets a schema of its own, so the
 * migrations run fresh.
 */
class CatalogEndToEndTest {
    private lateinit var database: DatabaseAccess

    @BeforeEach
    fun createSchema() {
        val schema = "catalog_${db.uniqueSuffix()}"
        db.sql("""CREATE SCHEMA "$schema"""")
        database = DatabaseAccess(db.jdbcUrl, db.username, db.password, schema)
    }

    @Nested
    inner class Outcome {
        @Test
        fun `catalog run - faults and three broken products - all for sale SUCCEEDED, the recalled one QUARANTINED`() {
            // Act
            val report = run()

            // Assert
            assertThat(report.statuses).containsOnly(entry(TaskStatus.SUCCEEDED, 42), entry(TaskStatus.QUARANTINED, 1))
        }

        @Test
        fun `catalog run - keyword model - every classification matches what a person would say`() {
            // Act
            val report = run()

            // Assert
            assertThat(report.classified).isEqualTo(42)
            assertThat(report.correct).isEqualTo(report.classified)
        }

        @Test
        fun `catalog run - every result - carries the model and the prompt version as provenance`() {
            // Act
            run()

            // Assert
            val withoutProvenance =
                db.count(
                    """
                    SELECT count(*) FROM ${table()}
                    WHERE status = 'SUCCEEDED'
                      AND (provenance ->> 'model' IS DISTINCT FROM 'keyword-model'
                           OR provenance ->> 'prompt' IS DISTINCT FROM 'categories-v1')
                    """.trimIndent(),
                )
            assertThat(withoutProvenance).isZero()
        }
    }

    @Nested
    inner class Disruptions {
        @Test
        fun `catalog run - overloaded model and products failing every attempt - nine runs end in a retry`() {
            // Act
            val report = run()

            // Assert
            // Three overloaded first attempts, and two retries for each of the three broken products.
            assertThat(report.retries).isEqualTo(9)
        }

        @Test
        fun `catalog run - garbled answers and missing descriptions - the operator restarts five tasks with SQL`() {
            // Act
            val report = run()

            // Assert
            assertThat(report.restartedByOperator).isEqualTo(5)
            assertThat(report.quarantinedByOperator).isEqualTo(1)
            val reason = db.count("SELECT count(*) FROM ${table()} WHERE last_error LIKE 'recalled%'")
            assertThat(reason).isEqualTo(1)
        }

        @Test
        fun `catalog run - pause at 30 percent - sets waiting tasks aside, and resume brings them back`() {
            // Act
            val report = run()

            // Assert
            assertThat(report.setAsideByPause).isPositive()
            assertThat(report.statuses[TaskStatus.SUCCEEDED]).isEqualTo(42)
        }
    }

    private fun run(): CatalogReport =
        runCatalog(
            database,
            KeywordModel(),
            RunSettings(
                products = PRODUCTS,
                pollInterval = Duration.ofMillis(POLL_MILLIS),
                pauseFor = Duration.ofMillis(PAUSE_MILLIS),
                settleWithin = Duration.ofSeconds(SETTLE_SECONDS),
            ),
        )

    private fun table() = """"${database.schema}".catalog_task"""

    private fun entry(
        status: TaskStatus,
        count: Int,
    ) = org.assertj.core.api.Assertions
        .entry(status, count)

    companion object {
        const val PRODUCTS = 40
        const val POLL_MILLIS = 50L
        const val PAUSE_MILLIS = 200L

        /** Per stage of the run; a run takes about five seconds in all. */
        const val SETTLE_SECONDS = 10L

        @JvmField
        @RegisterExtension
        val db = PostgresDatabase()
    }
}
