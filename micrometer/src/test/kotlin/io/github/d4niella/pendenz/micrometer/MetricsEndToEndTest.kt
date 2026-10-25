package io.github.d4niella.pendenz.micrometer

import io.github.d4niella.pendenz.Pendenz
import io.github.d4niella.pendenz.TaskStatus
import io.github.d4niella.pendenz.TaskType
import io.github.d4niella.pendenz.WorkerOptions
import io.github.d4niella.pendenz.schema.TaskTable
import io.github.d4niella.pendenz.testing.PostgresDatabase
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.time.Duration

/** The meters against PostgreSQL, with a worker and with rows changed by SQL. */
class MetricsEndToEndTest {
    @Serializable
    data class Invoice(
        val number: String,
    )

    private val sendInvoice = TaskType.json<Invoice>("send-invoice")
    private val registry = SimpleMeterRegistry()
    private val metrics = PendenzMetrics<Long>(registry)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var table: TaskTable<Long>

    @BeforeEach
    fun createTable() {
        table = db.createTable("metrics")
    }

    @AfterEach
    fun stopScope() = scope.cancel()

    @Test
    fun `execution timer - worker runs two invoices - records two succeeded runs and a wait for each`() =
        runBlocking<Unit> {
            // Arrange
            val pendenz = pendenz()
            pendenz.enqueue(sendInvoice, Invoice("2026-0001"))
            pendenz.enqueue(sendInvoice, Invoice("2026-0002"))

            // Act
            pendenz.startWorker(scope, WorkerOptions(pollInterval = Duration.ofMillis(50)))

            // Assert
            await atMost Duration.ofSeconds(10) untilAsserted {
                val succeeded =
                    registry
                        .find(PendenzMetrics.EXECUTION)
                        .tags(PendenzMetrics.OUTCOME, "succeeded")
                        .timer()
                        ?.count()
                assertThat(succeeded).isEqualTo(2)
            }
            assertThat(
                registry
                    .find(PendenzMetrics.WAIT)
                    .tags(PendenzMetrics.TYPE, "send-invoice")
                    .timer()!!
                    .count(),
            ).isEqualTo(2)
        }

    @Test
    fun `backlog gauge - operator quarantines a row with SQL - shows what the table holds, not the events`() =
        runBlocking<Unit> {
            // Arrange
            val pendenz = pendenz()
            val id = pendenz.enqueue(sendInvoice, Invoice("2026-0003"))
            pendenz.enqueue(sendInvoice, Invoice("2026-0004"))
            metrics.watchBacklog(pendenz, scope, Duration.ofMillis(50))

            // Act
            db.sql("UPDATE ${table.name} SET status = 'QUARANTINED' WHERE id = $id")

            // Assert
            await atMost Duration.ofSeconds(10) untilAsserted {
                assertThat(backlog(TaskStatus.QUARANTINED)).isEqualTo(1.0)
                assertThat(backlog(TaskStatus.PENDING)).isEqualTo(1.0)
            }
        }

    private fun backlog(status: TaskStatus): Double? =
        registry
            .find(PendenzMetrics.BACKLOG)
            .tags(PendenzMetrics.TYPE, "send-invoice", PendenzMetrics.STATUS, status.name)
            .gauge()
            ?.value()

    private fun pendenz(): Pendenz<Long> =
        Pendenz.create(table) {
            dataSource(db.dataSource)
            listener(metrics)
            register(sendInvoice) { _, _ -> }
        }

    companion object {
        @JvmField
        @RegisterExtension
        val db = PostgresDatabase()
    }
}
