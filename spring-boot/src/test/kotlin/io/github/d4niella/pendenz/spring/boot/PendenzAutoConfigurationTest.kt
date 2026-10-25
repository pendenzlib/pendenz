package io.github.d4niella.pendenz.spring.boot

import io.github.d4niella.pendenz.ClaimRequest
import io.github.d4niella.pendenz.ClaimStrategy
import io.github.d4niella.pendenz.Claims
import io.github.d4niella.pendenz.FairClaim
import io.github.d4niella.pendenz.NotPermittedException
import io.github.d4niella.pendenz.Pendenz
import io.github.d4niella.pendenz.Permissions
import io.github.d4niella.pendenz.ResultHandler
import io.github.d4niella.pendenz.TaskContext
import io.github.d4niella.pendenz.TaskHandler
import io.github.d4niella.pendenz.TaskStatus
import io.github.d4niella.pendenz.TaskType
import io.github.d4niella.pendenz.javaapi.EnqueueOptions
import io.github.d4niella.pendenz.javaapi.PendenzJava
import io.github.d4niella.pendenz.micrometer.PendenzMetrics
import io.github.d4niella.pendenz.notify.PostgresNotify
import io.github.d4niella.pendenz.schema.IdType
import io.github.d4niella.pendenz.schema.PostgresDdl
import io.github.d4niella.pendenz.schema.TaskTable
import io.github.d4niella.pendenz.spring.boot.testing.Billing.Invoice
import io.github.d4niella.pendenz.spring.boot.testing.Billing.RECEIPT
import io.github.d4niella.pendenz.spring.boot.testing.Billing.Receipt
import io.github.d4niella.pendenz.spring.boot.testing.Billing.SEND_INVOICE
import io.github.d4niella.pendenz.spring.boot.testing.Billing.SEND_WITH_RECEIPT
import io.github.d4niella.pendenz.spring.boot.testing.MailService
import io.github.d4niella.pendenz.spring.boot.testing.application
import io.github.d4niella.pendenz.store.ClaimedTask
import io.github.d4niella.pendenz.testing.PostgresDatabase
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.ThrowableAssert.ThrowingCallable
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.NestedExceptionUtils
import org.springframework.core.ResolvableType
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.PlatformTransactionManager
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

class PendenzAutoConfigurationTest {
    private lateinit var table: TaskTable<Long>
    private lateinit var app: ApplicationContextRunner

    @BeforeEach
    fun createTable() {
        table = db.createTable("boot")
        app = application(db, table.name)
    }

    private fun tasksIn(
        id: Long,
        status: TaskStatus,
    ): Long = db.count("SELECT count(*) FROM ${table.name} WHERE id = $id AND status = '${status.name}'")

    @Nested
    inner class QueueBeans {
        @Test
        fun `queue beans - defaults - a Pendenz and a PendenzJava for bigint ids over the same queue`() {
            // Act
            app.run { context ->
                // Assert
                val bigint = ResolvableType.forClassWithGenerics(Pendenz::class.java, Long::class.javaObjectType)
                assertThat(context).hasNotFailed()
                assertThat(context.getBeanNamesForType(bigint)).hasSize(1)
                assertThat(context.queue<Long>().asKotlin()).isSameAs(context.getBean(Pendenz::class.java))
            }
        }

        @Test
        fun `queue beans - id type uuid on a uuid table - the queue hands out UUID ids`() {
            // Arrange
            val uuidTable = db.createTable(IdType.UUID, "boot_uuid")

            // Act
            application(db, uuidTable.name)
                .withPropertyValues("pendenz.id-type=uuid")
                .withUserConfiguration(UuidHandler::class.java)
                .run { context ->
                    // Assert
                    val id = context.queue<UUID>().enqueue(SEND_INVOICE, Invoice("2026-0101"))
                    assertThat(id).isInstanceOf(UUID::class.java)
                }
        }

        @Test
        fun `task table bean - id type other than the id type property - startup fails naming the property`() {
            // Act
            app.withUserConfiguration(UuidTable::class.java).run { context ->
                // Assert
                assertThat(context).hasFailed()
                assertThat(context.failure()).contains("pendenz.id-type")
            }
        }
    }

    @Nested
    inner class Handlers {
        @Test
        fun `registration bean - handler with an injected dependency - the worker runs the task`() {
            app.withUserConfiguration(MailHandler::class.java).run { context ->
                // Act
                context.queue<Long>().enqueue(SEND_INVOICE, Invoice("2026-0102"))

                // Assert
                await atMost Duration.ofSeconds(10) untilAsserted {
                    assertThat(context.getBean(MailService::class.java).sent).containsExactly("2026-0102")
                }
            }
        }

        @Test
        fun `registration bean - declared for uuid ids on a bigint queue - startup fails naming the bean`() {
            // Act
            app.withUserConfiguration(UuidHandler::class.java).run { context ->
                // Assert
                assertThat(context).hasFailed()
                assertThat(context.failure()).contains("uuidInvoice")
            }
        }

        @Test
        fun `customizer bean - registers a type on the builder - the queue accepts that type`() {
            // Arrange
            val app = app.withPropertyValues("pendenz.worker.enabled=false")

            // Act
            app.withUserConfiguration(ReminderCustomizer::class.java).run { context ->
                // Assert
                val id = context.queue<Long>().enqueue(REMINDER, "2026-0103")
                assertThat(tasksIn(id, TaskStatus.PENDING)).isOne()
            }
        }

        @Test
        fun `permissions bean - actor not allowed - enqueue throws NotPermittedException`() {
            app.withUserConfiguration(InternsRefused::class.java, MailHandler::class.java).run { context ->
                // Act
                val enqueue =
                    ThrowingCallable {
                        context
                            .queue<Long>()
                            .enqueue(
                                SEND_INVOICE,
                                Invoice("2026-0104"),
                                EnqueueOptions.none<Long>().createdBy("intern"),
                            )
                    }

                // Assert
                assertThatThrownBy(enqueue).isInstanceOf(NotPermittedException::class.java)
            }
        }

        @Test
        fun `permissions beans - two of them - startup fails naming both`() {
            // Act
            app.withUserConfiguration(InternsRefused::class.java, GuestsRefused::class.java).run { context ->
                // Assert
                assertThat(context).hasFailed()
                assertThat(context.failure()).contains("noInterns", "noGuests")
            }
        }
    }

    @Nested
    inner class ResultHandlers {
        @Test
        fun `registration bean - result handler as a lambda - the worker stores its result`() {
            app.withUserConfiguration(ReceiptByLambda::class.java).run { context ->
                // Act
                val id = context.queue<Long>().enqueue(SEND_WITH_RECEIPT, Invoice("2026-0109"))

                // Assert
                await atMost Duration.ofSeconds(10) untilAsserted {
                    assertThat(
                        context
                            .queue<Long>()
                            .find(id)
                            .orElseThrow()
                            .result,
                    ).contains("2026-0109")
                }
            }
        }

        @Test
        fun `registration bean - result handler named by type - the bean runs and its result is stored`() {
            app.withUserConfiguration(ReceiptByType::class.java).run { context ->
                // Act
                val id = context.queue<Long>().enqueue(SEND_WITH_RECEIPT, Invoice("2026-0110"))

                // Assert
                await atMost Duration.ofSeconds(10) untilAsserted {
                    assertThat(
                        context
                            .queue<Long>()
                            .find(id)
                            .orElseThrow()
                            .result,
                    ).contains("2026-0110")
                }
            }
        }
    }

    @Nested
    inner class HandlerBeans {
        @Test
        fun `handler named by type - depends on a bean that injects the queue - the worker runs the task`() {
            app.withUserConfiguration(BookingByType::class.java).run { context ->
                // Act
                context.getBean(Ledger::class.java).pendenz.enqueue(SEND_INVOICE, Invoice("2026-0109"))

                // Assert
                await atMost Duration.ofSeconds(10) untilAsserted {
                    assertThat(context.getBean(Ledger::class.java).booked).containsExactly("2026-0109")
                }
            }
        }

        @Test
        fun `handler named by type - no bean of that type - startup fails naming the type`() {
            // Act
            app.withUserConfiguration(BookingWithoutHandler::class.java).run { context ->
                // Assert
                assertThat(context).hasFailed()
                assertThat(context.failure()).contains(BookingHandler::class.java.name, "0 beans")
            }
        }

        @Test
        fun `registration bean - depends on a bean that injects the queue - startup fails explaining the circle`() {
            // Act
            app.withUserConfiguration(BookingByLambda::class.java).run { context ->
                // Assert
                val cycle =
                    generateSequence(
                        context.startupFailure,
                    ) { it.cause }.filterIsInstance<HandlerCycleException>()
                assertThat(cycle.firstOrNull()?.message).contains("'booking'", "TaskRegistration.of(type, MyHandler")
            }
        }

        @Test
        fun `failure analysis - circle through the queue - names the registration and the ways out`() {
            app.withUserConfiguration(BookingByLambda::class.java).run { context ->
                // Act
                val analysis = HandlerCycleFailureAnalyzer().analyze(context.startupFailure!!)

                // Assert
                assertThat(analysis?.description).contains("'booking'")
                assertThat(analysis?.action).contains("ObjectProvider", "@Lazy")
            }
        }
    }

    @Nested
    inner class Transactions {
        @Test
        fun `transaction manager - two and none named - startup fails asking for the property`() {
            // Act
            app.withUserConfiguration(TwoTransactionManagers::class.java).run { context ->
                // Assert
                assertThat(context).hasFailed()
                assertThat(context.failure()).contains("pendenz.transaction-manager")
            }
        }

        @Test
        fun `transaction manager - two, one named by the property - starts and joins that one`() {
            // Arrange
            val app = app.withPropertyValues("pendenz.transaction-manager=billingTransactions")

            // Act
            app.withUserConfiguration(TwoTransactionManagers::class.java).run { context ->
                // Assert
                assertThat(context).hasNotFailed()
            }
        }
    }

    @Nested
    inner class Worker {
        @Test
        fun `worker - switched off by property - no lifecycle bean, runPending runs the task`() {
            // Arrange
            val app = app.withPropertyValues("pendenz.worker.enabled=false")

            app.withUserConfiguration(MailHandler::class.java).run { context ->
                context.queue<Long>().enqueue(SEND_INVOICE, Invoice("2026-0105"))

                // Act
                val ran = context.queue<Long>().runPending()

                // Assert
                assertThat(context).doesNotHaveBean(PendenzWorkerLifecycle::class.java)
                assertThat(ran).isOne()
            }
        }

        @Test
        fun `worker - ClaimStrategy bean - the worker claims with it`() {
            app.withUserConfiguration(MailHandler::class.java, CountedClaims::class.java).run { context ->
                // Act
                context.queue<Long>().enqueue(SEND_INVOICE, Invoice("2026-0111"))

                // Assert
                await atMost Duration.ofSeconds(10) untilAsserted {
                    assertThat(context.getBean(CountingClaim::class.java).claimed.get()).isOne()
                }
            }
        }

        @Test
        fun `worker - shutdown timeout not below Spring's shutdown phase - startup fails naming both`() {
            // Arrange
            val app = app.withPropertyValues("pendenz.worker.shutdown-timeout=30s")

            // Act
            app.run { context ->
                // Assert
                assertThat(context).hasFailed()
                assertThat(context.failure()).contains(
                    "pendenz.worker.shutdown-timeout",
                    "spring.lifecycle.timeout-per-shutdown-phase",
                )
            }
        }

        @Test
        fun `worker - context closes while a handler runs - the handler finishes and the task succeeds`() {
            // Arrange
            var id = 0L

            app.withUserConfiguration(SlowHandler::class.java).run { context ->
                id = context.queue<Long>().enqueue(SEND_INVOICE, Invoice("2026-0106"))
                assertThat(context.getBean(CountDownLatch::class.java).await(10, TimeUnit.SECONDS)).isTrue()

                // Act
                context.close()
            }

            // Assert
            assertThat(tasksIn(id, TaskStatus.SUCCEEDED)).isOne()
        }
    }

    @Nested
    inner class SchemaCheck {
        @Test
        fun `schema check - table missing - startup fails naming the table`() {
            // Act
            application(db, "invoice_task_missing").run { context ->
                // Assert
                assertThat(context).hasFailed()
                assertThat(context.failure()).contains("invoice_task_missing")
            }
        }

        @Test
        fun `schema check - column missing - startup fails naming the column`() {
            // Arrange
            db.sql("ALTER TABLE ${table.name} DROP COLUMN preview")

            // Act
            app.run { context ->
                // Assert
                assertThat(context).hasFailed()
                assertThat(context.failure()).contains("preview")
            }
        }

        @Test
        fun `schema check - table named in mixed case, as the core quotes it - finds it and starts`() {
            // Arrange
            val mixed = TaskTable(name = "Invoice_Task_${System.nanoTime().toString(36)}")
            db.sql(PostgresDdl.createTable(mixed))

            // Act
            application(db, mixed.name).withPropertyValues("pendenz.worker.enabled=false").run { context ->
                // Assert
                assertThat(context).hasNotFailed()
            }
        }

        @Test
        fun `schema check - switched off - starts without the table`() {
            // Arrange
            val app = application(db, "invoice_task_missing").withPropertyValues("pendenz.schema-check=false")

            // Act
            app.withPropertyValues("pendenz.worker.enabled=false").run { context ->
                // Assert
                assertThat(context).hasNotFailed()
            }
        }
    }

    @Nested
    inner class Metrics {
        @Test
        fun `metrics - meter registry present - counts enqueued tasks`() {
            app.withUserConfiguration(Registry::class.java, MailHandler::class.java).run { context ->
                // Act
                context.queue<Long>().enqueue(SEND_INVOICE, Invoice("2026-0107"))

                // Assert
                assertThat(enqueued(context)).isEqualTo(1.0)
            }
        }

        @Test
        fun `metrics - application defines its own PendenzMetrics - each enqueue counted once`() {
            app.withUserConfiguration(OwnMetrics::class.java, MailHandler::class.java).run { context ->
                // Act
                context.queue<Long>().enqueue(SEND_INVOICE, Invoice("2026-0108"))

                // Assert
                assertThat(context).getBeans(PendenzMetrics::class.java).hasSize(1)
                assertThat(enqueued(context)).isEqualTo(1.0)
            }
        }

        @Test
        fun `metrics - backlog interval of zero - startup fails naming the property`() {
            app
                .withUserConfiguration(Registry::class.java, MailHandler::class.java)
                .withPropertyValues("pendenz.metrics.backlog-interval=0s")
                .run { context ->
                    // Assert
                    assertThat(context).hasFailed()
                    assertThat(context.failure()).contains("pendenz.metrics.backlog-interval")
                }
        }

        private fun enqueued(context: AssertableApplicationContext): Double =
            context
                .getBean(MeterRegistry::class.java)
                .find(PendenzMetrics.ENQUEUED)
                .counter()!!
                .count()
    }

    @Test
    fun `notify - switched on but the worker off - no listener takes a connection`() {
        // Act
        app.withPropertyValues("pendenz.worker.notify=true", "pendenz.worker.enabled=false").run { context ->
            // Assert
            assertThat(context).doesNotHaveBean(PostgresNotify::class.java)
        }
    }

    @Test
    fun `notify - switched on with the module present - listens on the table's channel`() {
        // Act
        app.withPropertyValues("pendenz.worker.notify=true").run { context ->
            // Assert
            assertThat(context).hasSingleBean(PostgresNotify::class.java)
        }
    }

    @Test
    fun `boot version - Spring Boot 3 - fails naming the version floor`() {
        // Act
        val check = ThrowingCallable { PendenzAutoConfiguration.requireBoot4("3.5.16") }

        // Assert
        assertThatThrownBy(check).isInstanceOf(IllegalStateException::class.java).hasMessageContaining("ADR 0024")
    }

    @Configuration(proxyBeanMethods = false)
    class ReceiptByLambda {
        @Bean
        fun sendWithReceipt(): TaskRegistration<Long> =
            TaskRegistration.of(SEND_WITH_RECEIPT, RECEIPT) { _, invoice -> Receipt(invoice.number) }
    }

    class ReceiptHandler : ResultHandler<Long, Invoice, Receipt> {
        override suspend fun handle(
            context: TaskContext<Long>,
            payload: Invoice,
        ): Receipt = Receipt(payload.number)
    }

    @Configuration(proxyBeanMethods = false)
    class ReceiptByType {
        @Bean
        fun receiptHandler(): ReceiptHandler = ReceiptHandler()

        @Bean
        fun sendWithReceipt(): TaskRegistration<Long> =
            TaskRegistration.of(SEND_WITH_RECEIPT, RECEIPT, ReceiptHandler::class.java)
    }

    /** FairClaim, counting the tasks it claims, to show the worker uses the bean. */
    class CountingClaim : ClaimStrategy {
        val claimed = AtomicInteger()
        private val fair = FairClaim()

        override fun <ID : Any> claim(
            claims: Claims<ID>,
            request: ClaimRequest,
        ): List<ClaimedTask<ID>> = fair.claim(claims, request).also { claimed.addAndGet(it.size) }
    }

    @Configuration(proxyBeanMethods = false)
    class CountedClaims {
        @Bean
        fun claimStrategy(): CountingClaim = CountingClaim()
    }

    @Configuration(proxyBeanMethods = false)
    class MailHandler {
        @Bean
        fun mailService(): MailService = MailService()

        @Bean
        fun sendInvoice(mail: MailService): TaskRegistration<Long> =
            TaskRegistration.of(SEND_INVOICE) { _, invoice -> mail.send(invoice) }
    }

    /** Enqueues, and its handler books what it enqueued: the shape that closes the circle. */
    class Ledger(
        val pendenz: PendenzJava<Long>,
    ) {
        val booked: MutableList<String> = CopyOnWriteArrayList()
    }

    class BookingHandler(
        private val ledger: Ledger,
    ) : TaskHandler<Long, Invoice> {
        override suspend fun handle(
            context: TaskContext<Long>,
            payload: Invoice,
        ) {
            ledger.booked += payload.number
        }
    }

    @Configuration(proxyBeanMethods = false)
    class BookingByType {
        @Bean
        fun ledger(pendenz: PendenzJava<Long>): Ledger = Ledger(pendenz)

        @Bean
        fun bookingHandler(ledger: Ledger): BookingHandler = BookingHandler(ledger)

        @Bean
        fun booking(): TaskRegistration<Long> = TaskRegistration.of(SEND_INVOICE, BookingHandler::class.java)
    }

    @Configuration(proxyBeanMethods = false)
    class BookingWithoutHandler {
        @Bean
        fun booking(): TaskRegistration<Long> = TaskRegistration.of(SEND_INVOICE, BookingHandler::class.java)
    }

    @Configuration(proxyBeanMethods = false)
    class BookingByLambda {
        @Bean
        fun ledger(pendenz: PendenzJava<Long>): Ledger = Ledger(pendenz)

        @Bean
        fun booking(ledger: Ledger): TaskRegistration<Long> =
            TaskRegistration.of(SEND_INVOICE) { _, invoice -> ledger.booked += invoice.number }
    }

    @Configuration(proxyBeanMethods = false)
    class UuidHandler {
        @Bean
        fun uuidInvoice(): TaskRegistration<UUID> = TaskRegistration.of(SEND_INVOICE) { _, _ -> }
    }

    @Configuration(proxyBeanMethods = false)
    class UuidTable {
        @Bean
        fun billingTable(): TaskTable<UUID> = TaskTable(IdType.UUID, "billing_task")
    }

    @Configuration(proxyBeanMethods = false)
    class ReminderCustomizer {
        @Bean
        fun reminders(): PendenzCustomizer<Long> = PendenzCustomizer { it.register(REMINDER) { _, _ -> } }
    }

    @Configuration(proxyBeanMethods = false)
    class InternsRefused {
        @Bean
        fun noInterns(): Permissions = Permissions { actor, _, _ -> actor != "intern" }
    }

    @Configuration(proxyBeanMethods = false)
    class GuestsRefused {
        @Bean
        fun noGuests(): Permissions = Permissions { actor, _, _ -> actor != "guest" }
    }

    @Configuration(proxyBeanMethods = false)
    class TwoTransactionManagers {
        @Bean
        fun billingTransactions(dataSource: DataSource): PlatformTransactionManager =
            DataSourceTransactionManager(dataSource)

        @Bean
        fun reportingTransactions(dataSource: DataSource): PlatformTransactionManager =
            DataSourceTransactionManager(dataSource)
    }

    @Configuration(proxyBeanMethods = false)
    class SlowHandler {
        @Bean
        fun started(): CountDownLatch = CountDownLatch(1)

        @Bean
        fun slowInvoice(started: CountDownLatch): TaskRegistration<Long> =
            TaskRegistration.of(SEND_INVOICE) { _, _ ->
                started.countDown()
                delay(HANDLER_WORK_MILLIS) // the handler's work, still running when the context closes
            }
    }

    @Configuration(proxyBeanMethods = false)
    class Registry {
        @Bean
        fun meterRegistry(): MeterRegistry = SimpleMeterRegistry()
    }

    @Configuration(proxyBeanMethods = false)
    class OwnMetrics {
        @Bean
        fun meterRegistry(): MeterRegistry = SimpleMeterRegistry()

        @Bean
        fun billingMetrics(registry: MeterRegistry): PendenzMetrics<Long> = PendenzMetrics(registry)
    }

    companion object {
        @JvmField
        @RegisterExtension
        val db = PostgresDatabase()

        val REMINDER: TaskType<String> = TaskType.json<String>("send-reminder")

        private const val HANDLER_WORK_MILLIS = 500L

        /** The message of the exception that stopped the startup, beneath Spring's wrappers. */
        private fun AssertableApplicationContext.failure(): String? =
            startupFailure?.let { NestedExceptionUtils.getMostSpecificCause(it).message }

        @Suppress("UNCHECKED_CAST") // the tests know which id type they configured
        private fun <ID : Any> AssertableApplicationContext.queue(): PendenzJava<ID> =
            getBean(PendenzJava::class.java) as PendenzJava<ID>
    }
}
