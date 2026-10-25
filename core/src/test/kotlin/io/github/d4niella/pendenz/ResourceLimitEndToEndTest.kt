package io.github.d4niella.pendenz

import io.github.d4niella.pendenz.schema.CycleTable
import io.github.d4niella.pendenz.schema.PostgresDdl
import io.github.d4niella.pendenz.schema.ResourceLimitTable
import io.github.d4niella.pendenz.schema.TaskTable
import io.github.d4niella.pendenz.testing.PostgresDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Concurrency limits against PostgreSQL (ADR 0017): exact across workers, changeable with SQL, per customer. */
class ResourceLimitEndToEndTest {
    data class Mail(
        val invoiceId: Long,
        val customer: String,
    )

    private val mail =
        TaskType.columns<Mail>(
            "mail",
            listOf(PayloadColumn("invoice_id", SqlType.BIGINT), PayloadColumn("customer", SqlType.TEXT)),
            write = { mapOf("invoice_id" to it.invoiceId, "customer" to it.customer) },
            read = { Mail(it.getLong("invoice_id"), it.getString("customer")) },
            resource = TaskResource.fixed("mail-server", maxRunning = 2),
        )

    private lateinit var table: TaskTable<Long>
    private lateinit var cycleTable: CycleTable<Long>
    private lateinit var limitTable: ResourceLimitTable
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val workers = mutableListOf<Job>()

    @BeforeEach
    fun createTables() {
        val suffix = db.uniqueSuffix()
        table = TaskTable(name = "task_$suffix")
        cycleTable = CycleTable(name = "cycle_$suffix")
        limitTable = ResourceLimitTable(name = "limit_$suffix")
        db.sql(PostgresDdl.createTable(table, mail))
        db.sql(PostgresDdl.createCycleTable(cycleTable))
        db.sql(PostgresDdl.createResourceLimitTable(limitTable))
    }

    @AfterEach
    fun stopWorkers() =
        runBlocking<Unit> {
            workers.forEach { it.cancelAndJoin() }
        }

    @Nested
    inner class AcrossWorkers {
        @Test
        fun `worker - two of parallelism four, resource limited to two - never more than two run at once`() =
            runBlocking<Unit> {
                // Arrange
                val running = AtomicInteger()
                val mostRunning = AtomicInteger()
                val pendenz =
                    pendenz { _, _ ->
                        mostRunning.accumulateAndGet(running.incrementAndGet(), ::maxOf)
                        delay(100)
                        running.decrementAndGet()
                    }
                (1..12).forEach { pendenz.enqueue(mail, Mail(it.toLong(), "customer-$it")) }

                // Act
                startWorker(pendenz, parallelism = 4)
                startWorker(pendenz, parallelism = 4)

                // Assert
                await atMost Duration.ofSeconds(10) untilAsserted {
                    assertThat(
                        db.count("""SELECT count(*) FROM "${table.name}" WHERE status = 'SUCCEEDED'"""),
                    ).isEqualTo(12)
                }
                assertThat(mostRunning.get()).isBetween(1, 2)
            }

        @Test
        fun `worker - resource limited to one, five minute poll - the second task starts as the first ends`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz { _, _ -> delay(100) }
                pendenz.setResourceLimit("mail-server", 1)
                (1..2).forEach { pendenz.enqueue(mail, Mail(it.toLong(), "c")) }

                // Act
                workers +=
                    pendenz.startWorker(scope, WorkerOptions(parallelism = 2, pollInterval = Duration.ofMinutes(5)))

                // Assert: far below the poll interval, the worker woke itself when its first handler ended
                await atMost Duration.ofSeconds(10) untilAsserted {
                    assertThat(
                        db.count("""SELECT count(*) FROM "${table.name}" WHERE status = 'SUCCEEDED'"""),
                    ).isEqualTo(2)
                }
            }

        @Test
        fun `worker - limit raised with SQL while running - the next polls run more than one at a time`() =
            runBlocking<Unit> {
                // Arrange
                val running = AtomicInteger()
                val mostRunning = AtomicInteger()
                val pendenz =
                    pendenz { _, _ ->
                        mostRunning.accumulateAndGet(running.incrementAndGet(), ::maxOf)
                        delay(200)
                        running.decrementAndGet()
                    }
                pendenz.setResourceLimit("mail-server", 1)
                (1..8).forEach { pendenz.enqueue(mail, Mail(it.toLong(), "c")) }
                startWorker(pendenz, parallelism = 4)
                // The limit of one holds until the operator acts, as the test above shows
                await atMost Duration.ofSeconds(10) untilAsserted {
                    assertThat(
                        db.count("""SELECT count(*) FROM "${table.name}" WHERE status = 'SUCCEEDED'"""),
                    ).isPositive()
                }

                // Act
                val c = limitTable.columns
                db.sql("""UPDATE "${limitTable.name}" SET "${c.maxRunning}" = 4 WHERE "${c.name}" = 'mail-server'""")

                // Assert
                await atMost Duration.ofSeconds(10) untilAsserted {
                    assertThat(mostRunning.get()).isGreaterThanOrEqualTo(2)
                }
            }
    }

    @Nested
    inner class Cycles {
        @Test
        fun `startCycle - resource expression per customer and a pattern limit - one task per customer at a time`() =
            runBlocking<Unit> {
                // Arrange
                val invoices = "invoice_${db.uniqueSuffix()}"
                db.sql("""CREATE TABLE "$invoices" (id bigint PRIMARY KEY, customer text NOT NULL)""")
                (1..6).forEach { db.sql("""INSERT INTO "$invoices" VALUES ($it, 'customer-${it % 2}')""") }
                val perCustomer = ConcurrentHashMap<String, AtomicInteger>()
                val overlap = AtomicInteger()
                val pendenz =
                    pendenz { ctx, _ ->
                        val count = perCustomer.computeIfAbsent(ctx.resource!!) { AtomicInteger() }
                        if (count.incrementAndGet() > 1) overlap.incrementAndGet()
                        delay(100)
                        count.decrementAndGet()
                    }
                pendenz.setResourceLimit("customer:%", 1)

                // Act
                val cycle =
                    pendenz.startCycle(
                        mail,
                        CycleTasks.query(
                            """SELECT id, customer FROM "$invoices" ORDER BY id""",
                            resource = "'customer:' || q.customer",
                        ),
                    )
                startWorker(pendenz, parallelism = 4)

                // Assert
                await atMost Duration.ofSeconds(10) untilAsserted {
                    assertThat(runBlocking { pendenz.cycleProgress(cycle) }[TaskStatus.SUCCEEDED] ?: 0).isEqualTo(6)
                }
                assertThat(overlap.get()).isZero()
                assertThat(db.count("""SELECT count(DISTINCT resource) FROM "${table.name}"""")).isEqualTo(2)
            }
    }

    private fun pendenz(handler: TaskHandler<Long, Mail>): Pendenz<Long> =
        Pendenz.create(table) {
            dataSource(db.dataSource)
            enableCycles(cycleTable)
            enableResourceLimits(limitTable)
            register(mail, handler)
        }

    private fun startWorker(
        pendenz: Pendenz<Long>,
        parallelism: Int,
    ) {
        workers +=
            pendenz.startWorker(scope, WorkerOptions(parallelism = parallelism, pollInterval = Duration.ofMillis(30)))
    }

    companion object {
        @JvmField
        @RegisterExtension
        val db = PostgresDatabase()
    }
}
