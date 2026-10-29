package io.github.pendenzlib

import io.github.pendenzlib.schema.CycleTable
import io.github.pendenzlib.schema.PostgresDdl
import io.github.pendenzlib.schema.TaskTable
import io.github.pendenzlib.testing.PostgresDatabase
import io.github.pendenzlib.testing.awaitStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
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
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/** Approval against PostgreSQL (ADR 0018): a polling worker, the approval columns, a cycle approved as a whole. */
class ApprovalEndToEndTest {
    @Serializable
    data class Refund(
        val customer: String,
        val amount: Int,
    )

    private val refund = TaskType.json<Refund>("refund", approval = Approval.ALWAYS)
    private val bigRefund =
        TaskType.json<Refund>(
            "big-refund",
            approval = Approval.rule { _, payload -> payload == null || payload.amount > 1000 },
        )

    private lateinit var table: TaskTable<Long>
    private lateinit var cycleTable: CycleTable<Long>
    private val refunded = CopyOnWriteArrayList<Refund>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val workers = mutableListOf<Job>()

    @BeforeEach
    fun createTables() {
        table = db.createTable("refund")
        cycleTable = CycleTable(name = "cycle_${db.uniqueSuffix()}")
        db.sql(PostgresDdl.createCycleTable(cycleTable))
        db.sql(PostgresDdl.cycleProgressView(table, cycleTable))
    }

    @AfterEach
    fun stopWorkers() =
        runBlocking<Unit> {
            workers.forEach { it.cancelAndJoin() }
        }

    @Nested
    inner class SingleTask {
        @Test
        fun `worker - type requires approval - the task waits as AWAITING_APPROVAL and is not claimed`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz()
                startWorker(pendenz)

                // Act
                val id = pendenz.enqueue(refund, Refund("acme", 50), createdBy = "ada")

                // Assert
                delay(500) // fixed wait on purpose: the worker polls meanwhile and must leave the task alone
                val record = pendenz.find(id)!!
                assertThat(record.status).isEqualTo(TaskStatus.AWAITING_APPROVAL)
                assertThat(record.lockedBy).isNull()
                assertThat(refunded).isEmpty()
            }

        @Test
        fun `approve - by someone else while a worker polls - the row names the approver and the handler runs`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz()
                startWorker(pendenz)
                val id = pendenz.enqueue(refund, Refund("acme", 50), createdBy = "ada")

                // Act
                val approved = pendenz.approve(id, actor = "bob")

                // Assert
                assertThat(approved).isTrue()
                val record = awaitStatus(pendenz, id, TaskStatus.SUCCEEDED)
                assertThat(record.approvedBy).isEqualTo("bob")
                assertThat(record.approvedAt).isNotNull()
                assertThat(refunded).containsExactly(Refund("acme", 50))
                assertThat(
                    db.count("""SELECT count(*) FROM "${table.name}" WHERE approved_by = 'bob'"""),
                ).isEqualTo(1)
            }

        @Test
        fun `decline - waiting task - CANCELLED with the reason in last_error and the handler never runs`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz()
                startWorker(pendenz)
                val id = pendenz.enqueue(refund, Refund("acme", 50), createdBy = "ada")

                // Act
                val declined = pendenz.decline(id, actor = "bob", reason = "duplicate claim")

                // Assert
                assertThat(declined).isTrue()
                val record = pendenz.find(id)!!
                assertThat(record.status).isEqualTo(TaskStatus.CANCELLED)
                assertThat(record.lastError).isEqualTo("duplicate claim")
                assertThat(record.finishedAt).isNotNull()
                assertThat(refunded).isEmpty()
            }

        @Test
        fun `enqueue - rule over the payload - the small refund runs, the large one waits`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz()
                startWorker(pendenz)

                // Act
                val small = pendenz.enqueue(bigRefund, Refund("acme", 10))
                val large = pendenz.enqueue(bigRefund, Refund("acme", 5000))

                // Assert
                awaitStatus(pendenz, small, TaskStatus.SUCCEEDED)
                assertThat(pendenz.find(large)!!.status).isEqualTo(TaskStatus.AWAITING_APPROVAL)
                assertThat(refunded).containsExactly(Refund("acme", 10))
            }
    }

    @Nested
    inner class Cycles {
        @Test
        fun `approveCycle - cycle of a type requiring approval - one call approves all, the worker runs them`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz()
                startWorker(pendenz)
                val payloads = List(5) { Refund("customer-$it", 10) }
                val cycle = pendenz.startCycle(refund, CycleTasks.of(payloads), createdBy = "ada")

                // Act
                val approved = pendenz.approveCycle(cycle, actor = "bob")

                // Assert
                assertThat(approved).isEqualTo(5)
                await atMost Duration.ofSeconds(10) untilAsserted {
                    val progress = runBlocking { pendenz.cycleProgress(cycle) }
                    assertThat(progress).containsExactlyEntriesOf(mapOf(TaskStatus.SUCCEEDED to 5))
                }
                assertThat(
                    db.count("""SELECT count(*) FROM "${table.name}" WHERE approved_by = 'bob'"""),
                ).isEqualTo(5)
            }
    }

    private fun pendenz(): Pendenz<Long> =
        Pendenz.create(table) {
            dataSource(db.dataSource)
            enableCycles(cycleTable)
            register(refund) { _, r -> refunded += r }
            register(bigRefund) { _, r -> refunded += r }
        }

    private fun startWorker(pendenz: Pendenz<Long>): Job {
        val options = WorkerOptions(parallelism = 2, pollInterval = Duration.ofMillis(50))
        return pendenz.startWorker(scope, options).also { workers += it }
    }

    companion object {
        @JvmField
        @RegisterExtension
        val db = PostgresDatabase()
    }
}
