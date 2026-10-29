package io.github.pendenzlib

import io.github.pendenzlib.schema.TaskTable
import io.github.pendenzlib.testing.PostgresDatabase
import io.github.pendenzlib.testing.awaitStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Payload columns against PostgreSQL (ADR 0013): SQL reads and writes them, so do the stores. */
class PayloadColumnsEndToEndTest {
    data class SendInvoice(
        val invoiceId: Long,
        val customer: String,
    )

    @Serializable
    data class Greeting(
        val name: String,
    )

    private val sendInvoice =
        TaskType.columns<SendInvoice>(
            "send-invoice",
            listOf(PayloadColumn("invoice_id", SqlType.BIGINT), PayloadColumn("customer", SqlType.TEXT)),
            write = { mapOf("invoice_id" to it.invoiceId, "customer" to it.customer) },
            read = { SendInvoice(it.getLong("invoice_id"), it.getString("customer")) },
        )
    private val greet = TaskType.json<Greeting>("greet")

    private lateinit var table: TaskTable<Long>
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val workers = mutableListOf<Job>()

    @BeforeEach
    fun createTable() {
        table = db.createTable("task", sendInvoice, greet, everyType)
    }

    @AfterEach
    fun stopWorkers() =
        runBlocking<Unit> {
            workers.forEach { it.cancelAndJoin() }
        }

    @Nested
    inner class Columns {
        @Test
        fun `enqueue - columns mapping - the row carries the fields in their columns and the handler gets them back`() =
            runBlocking<Unit> {
                // Arrange
                val seen = CopyOnWriteArrayList<SendInvoice>()
                val pendenz = pendenz { register(sendInvoice) { _, p -> seen += p } }

                // Act
                val id = pendenz.enqueue(sendInvoice, SendInvoice(4711, "Globex"))
                startWorker(pendenz)

                // Assert
                awaitStatus(pendenz, id, TaskStatus.SUCCEEDED)
                assertThat(seen).containsExactly(SendInvoice(4711, "Globex"))
                assertThat(
                    db.long("""SELECT invoice_id FROM "${table.name}" WHERE customer = 'Globex'"""),
                ).isEqualTo(4711)
            }

        @Test
        fun `SQL insert - fields written as columns - the worker runs the task with them`() =
            runBlocking<Unit> {
                // Arrange
                val seen = CopyOnWriteArrayList<SendInvoice>()
                val pendenz = pendenz { register(sendInvoice) { _, p -> seen += p } }

                // Act
                db.sql(
                    """INSERT INTO "${table.name}" (task_type, status, run_at, invoice_id, customer)
                       VALUES ('send-invoice', 'PENDING', now(), 4712, 'Initech')""",
                )
                startWorker(pendenz)

                // Assert
                awaitSeen(seen, SendInvoice(4712, "Initech"))
            }

        @Test
        fun `enqueue - JSON type and columns type in one table - each finds its own columns`() =
            runBlocking<Unit> {
                // Arrange
                val invoices = CopyOnWriteArrayList<SendInvoice>()
                val greetings = CopyOnWriteArrayList<Greeting>()
                val pendenz =
                    pendenz {
                        register(sendInvoice) { _, p -> invoices += p }
                        register(greet) { _, p -> greetings += p }
                    }

                // Act
                val invoice = pendenz.enqueue(sendInvoice, SendInvoice(1, "Globex"))
                val greeting = pendenz.enqueue(greet, Greeting("Ada"))
                startWorker(pendenz)

                // Assert
                awaitStatus(pendenz, invoice, TaskStatus.SUCCEEDED)
                awaitStatus(pendenz, greeting, TaskStatus.SUCCEEDED)
                assertThat(invoices).containsExactly(SendInvoice(1, "Globex"))
                assertThat(greetings).containsExactly(Greeting("Ada"))
                assertThat(pendenz.find(greeting)!!.payload).doesNotContainKey("invoice_id")
            }
    }

    @Nested
    inner class Types {
        @Test
        fun `enqueue - one column of every SQL type - every value comes back equal`() =
            runBlocking<Unit> {
                // Arrange
                val seen = CopyOnWriteArrayList<Map<String, Any?>>()
                val pendenz = pendenz { register(everyType) { _, p -> seen += p } }
                val values =
                    mapOf(
                        "c_bigint" to 9_000_000_000L,
                        "c_integer" to 42,
                        "c_text" to "Ada",
                        "c_boolean" to true,
                        "c_numeric" to BigDecimal("12.50"),
                        "c_timestamptz" to Instant.parse("2026-11-30T12:34:56.789Z"),
                        "c_date" to LocalDate.of(2026, 11, 30),
                        "c_uuid" to UUID.fromString("7d3f0a5e-2c1b-4e8a-9f6d-0b1c2d3e4f50"),
                        "c_jsonb" to """{"nested": [1, 2]}""",
                    )

                // Act
                val id = pendenz.enqueue(everyType, values)
                startWorker(pendenz)

                // Assert
                awaitStatus(pendenz, id, TaskStatus.SUCCEEDED)
                assertThat(seen.single()).containsAllEntriesOf(values)
            }
    }

    private fun pendenz(configure: Pendenz.Builder<Long>.() -> Unit): Pendenz<Long> =
        Pendenz.create(table) {
            dataSource(db.dataSource)
            configure()
        }

    private fun startWorker(pendenz: Pendenz<Long>) {
        workers += pendenz.startWorker(scope, WorkerOptions(pollInterval = Duration.ofMillis(50)))
    }

    private fun awaitSeen(
        seen: List<SendInvoice>,
        expected: SendInvoice,
    ) {
        await atMost Duration.ofSeconds(10) untilAsserted {
            assertThat(seen).containsExactly(expected)
        }
    }

    companion object {
        @JvmField
        @RegisterExtension
        val db = PostgresDatabase()

        /** A payload that is the column map itself, one column per [SqlType]. */
        val everyType: TaskType<Map<String, Any?>> =
            TaskType.columns(
                "every-type",
                SqlType.entries.map { PayloadColumn("c_${it.name.lowercase()}", it) },
                write = { it },
                read = { it.toMap() },
            )
    }
}
