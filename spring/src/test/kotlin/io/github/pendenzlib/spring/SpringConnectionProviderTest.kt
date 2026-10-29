package io.github.pendenzlib.spring

import io.github.pendenzlib.NotPermittedException
import io.github.pendenzlib.Pendenz
import io.github.pendenzlib.Permissions
import io.github.pendenzlib.TaskListener
import io.github.pendenzlib.TaskStatus
import io.github.pendenzlib.WorkerOptions
import io.github.pendenzlib.schema.TaskTable
import io.github.pendenzlib.spring.testing.Billing.Invoice
import io.github.pendenzlib.spring.testing.Billing.SEND_INVOICE
import io.github.pendenzlib.spring.testing.Invoices
import io.github.pendenzlib.spring.testing.Jpa
import io.github.pendenzlib.testing.PostgresDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.ThrowableAssert.ThrowingCallable
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import javax.sql.DataSource

class SpringConnectionProviderTest {
    private val dataSource = db.dataSource
    private val transactionManager = DataSourceTransactionManager(dataSource)
    private lateinit var table: TaskTable<Long>
    private lateinit var invoices: Invoices

    @BeforeEach
    fun createTables() {
        table = db.createTable("spring")
        invoices = Invoices(db)
    }

    private fun pendenz(
        provider: SpringConnectionProvider = SpringConnectionProvider(dataSource, transactionManager),
        permissions: Permissions = Permissions.ALLOW_ALL,
        listener: TaskListener<Long> = object : TaskListener<Long> {},
    ): Pendenz<Long> =
        Pendenz
            .builder(table)
            .connectionProvider(provider)
            .permissions(permissions)
            .listener(listener)
            .register(SEND_INVOICE) { _, _ -> }
            .build()

    /** Records the ids of enqueued tasks. */
    private class Enqueued : TaskListener<Long> {
        val ids = CopyOnWriteArrayList<Long>()

        override fun onEnqueued(
            id: Long,
            type: String,
        ) {
            ids += id
        }
    }

    private fun tasks(): Long = db.count("SELECT count(*) FROM ${table.name}")

    private fun <T> inTransaction(
        manager: PlatformTransactionManager = transactionManager,
        configure: TransactionTemplate.() -> Unit = {},
        block: (TransactionStatus) -> T,
    ): T? = TransactionTemplate(manager).apply(configure).execute(block)

    @Nested
    inner class ListenerEvents {
        @Test
        fun `enqueue - caller's transaction commits - the listener hears of the task after the commit, not before`() {
            // Arrange
            val enqueued = Enqueued()
            val pendenz = pendenz(listener = enqueued)

            // Act
            val (id, heardBeforeCommit) =
                inTransaction {
                    val id = runBlocking { pendenz.enqueue(SEND_INVOICE, Invoice("2026-0101")) }
                    id to enqueued.ids.toList()
                }!!

            // Assert
            assertThat(heardBeforeCommit).isEmpty()
            assertThat(enqueued.ids).containsExactly(id)
        }

        @Test
        fun `enqueue - caller's transaction rolls back - the listener hears nothing`() {
            // Arrange
            val enqueued = Enqueued()
            val pendenz = pendenz(listener = enqueued)

            // Act
            inTransaction { status ->
                runBlocking { pendenz.enqueue(SEND_INVOICE, Invoice("2026-0102")) }
                status.setRollbackOnly()
            }

            // Assert
            assertThat(tasks()).isZero()
            assertThat(enqueued.ids).isEmpty()
        }

        @Test
        fun `enqueue - outside a transaction - the listener hears of the task when enqueue returns`() =
            runBlocking<Unit> {
                // Arrange
                val enqueued = Enqueued()
                val pendenz = pendenz(listener = enqueued)

                val id = pendenz.enqueue(SEND_INVOICE, Invoice("2026-0103"))

                assertThat(enqueued.ids).containsExactly(id)
            }
    }

    @Nested
    inner class JoiningTransactions {
        @Test
        fun `enqueue - caller's transaction rolls back - leaves neither the invoice nor the task`() {
            // Arrange
            val pendenz = pendenz()

            // Act
            inTransaction { status ->
                invoices.store(dataSource, "2026-0001")
                runBlocking { pendenz.enqueue(SEND_INVOICE, Invoice("2026-0001")) }
                status.setRollbackOnly()
            }

            // Assert
            assertThat(invoices.count()).isZero()
            assertThat(tasks()).isZero()
        }

        @Test
        fun `enqueue - caller's transaction commits - stores the invoice and the task together`() {
            // Arrange
            val pendenz = pendenz()

            // Act
            inTransaction {
                invoices.store(dataSource, "2026-0002")
                runBlocking { pendenz.enqueue(SEND_INVOICE, Invoice("2026-0002")) }
            }

            // Assert
            assertThat(invoices.count()).isOne()
            assertThat(tasks()).isOne()
        }

        @Test
        fun `enqueue - refused permission caught by the caller - the invoice still commits, without a task`() {
            // Arrange
            val pendenz = pendenz(permissions = { actor, _, _ -> actor != "intern" })

            // Act
            val refused =
                inTransaction {
                    invoices.store(dataSource, "2026-0003")
                    runCatching {
                        runBlocking { pendenz.enqueue(SEND_INVOICE, Invoice("2026-0003"), createdBy = "intern") }
                    }.exceptionOrNull()
                }

            // Assert
            assertThat(refused).isInstanceOf(NotPermittedException::class.java)
            assertThat(invoices.count()).isOne()
            assertThat(tasks()).isZero()
        }

        @Test
        fun `enqueue - JPA transaction rolls back - leaves neither the invoice nor the task`() {
            // Arrange
            val jpaManager = Jpa.transactionManager(jpa)
            val pendenz = pendenz(SpringConnectionProvider(dataSource, jpaManager))

            // Act
            inTransaction(jpaManager) { status ->
                invoices.storeWithJpa(jpa, "2026-0004")
                runBlocking { pendenz.enqueue(SEND_INVOICE, Invoice("2026-0004")) }
                status.setRollbackOnly()
            }

            // Assert
            assertThat(invoices.count()).isZero()
            assertThat(tasks()).isZero()
        }

        @Test
        fun `enqueue - JPA transaction commits - stores the invoice and the task together`() {
            // Arrange
            val jpaManager = Jpa.transactionManager(jpa)
            val pendenz = pendenz(SpringConnectionProvider(dataSource, jpaManager))

            // Act
            inTransaction(jpaManager) {
                invoices.storeWithJpa(jpa, "2026-0005")
                runBlocking { pendenz.enqueue(SEND_INVOICE, Invoice("2026-0005")) }
            }

            // Assert
            assertThat(invoices.count()).isOne()
            assertThat(tasks()).isOne()
        }

        @Test
        fun `enqueue - transaction-aware proxy on the provider's side only - joins the transaction`() {
            // Arrange
            val proxy = TransactionAwareDataSourceProxy(dataSource)
            val pendenz = pendenz(SpringConnectionProvider(proxy, transactionManager))

            // Act
            inTransaction { status ->
                runBlocking { pendenz.enqueue(SEND_INVOICE, Invoice("2026-0006")) }
                status.setRollbackOnly()
            }

            // Assert
            assertThat(tasks()).isZero()
        }
    }

    @Nested
    inner class OwnTransactions {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        @AfterEach
        fun stopScope() = scope.cancel()

        @Test
        fun `enqueue - no transaction active - commits the task at once`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz()

                // Act
                pendenz.enqueue(SEND_INVOICE, Invoice("2026-0007"))

                // Assert
                assertThat(tasks()).isOne()
            }

        @Test
        fun `worker - task enqueued in a committed transaction - runs it in transactions of its own`() {
            // Arrange
            val pendenz = pendenz()
            val id = inTransaction { runBlocking { pendenz.enqueue(SEND_INVOICE, Invoice("2026-0008")) } }!!

            // Act
            pendenz.startWorker(scope, WorkerOptions(pollInterval = Duration.ofMillis(50)))

            // Assert
            await atMost Duration.ofSeconds(10) untilAsserted {
                assertThat(runBlocking { pendenz.find(id) }?.status).isEqualTo(TaskStatus.SUCCEEDED)
            }
        }
    }

    @Nested
    inner class Reads {
        @Test
        fun `find - inside a read-only transaction - joins it and reads the task`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz()
                val id = pendenz.enqueue(SEND_INVOICE, Invoice("2026-0010"))

                val found = inTransaction(configure = { isReadOnly = true }) { runBlocking { pendenz.find(id) } }

                assertThat(found?.status).isEqualTo(TaskStatus.PENDING)
            }
    }

    @Nested
    inner class Refusals {
        @Test
        fun `enqueue - read-only transaction - throws IllegalStateException naming it`() {
            // Arrange
            val pendenz = pendenz()

            // Act
            val thrown =
                runCatching {
                    inTransaction(configure = { isReadOnly = true }) {
                        runBlocking { pendenz.enqueue(SEND_INVOICE, Invoice("2026-0009")) }
                    }
                }.exceptionOrNull()

            // Assert
            assertThat(thrown).isInstanceOf(IllegalStateException::class.java).hasMessageContaining("read-only")
            assertThat(tasks()).isZero()
        }

        @Test
        fun `enqueue - transaction over another data source - throws IllegalStateException, stores nothing`() {
            // Arrange
            val pendenz = pendenz()
            val otherManager = DataSourceTransactionManager(otherDataSource())

            // Act
            val thrown =
                runCatching {
                    inTransaction(otherManager) { runBlocking { pendenz.enqueue(SEND_INVOICE, Invoice("2026-0010")) } }
                }.exceptionOrNull()

            // Assert
            assertThat(thrown).isInstanceOf(IllegalStateException::class.java).hasMessageContaining("another")
            assertThat(tasks()).isZero()
        }
    }

    @Nested
    inner class Construction {
        @Test
        fun `construction - manager over another data source - throws IllegalStateException`() {
            // Arrange
            val manager = DataSourceTransactionManager(otherDataSource())

            // Act
            val construction = ThrowingCallable { SpringConnectionProvider(dataSource, manager) }

            // Assert
            assertThatThrownBy(construction)
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("same data source")
        }

        @Test
        fun `construction - manager binding no JDBC connection, such as JTA - throws IllegalStateException`() {
            // Arrange
            val manager =
                object : PlatformTransactionManager {
                    override fun getTransaction(definition: TransactionDefinition?): TransactionStatus = error("unused")

                    override fun commit(status: TransactionStatus) = Unit

                    override fun rollback(status: TransactionStatus) = Unit
                }

            // Act
            val construction = ThrowingCallable { SpringConnectionProvider(dataSource, manager) }

            // Assert
            assertThatThrownBy(construction)
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("JTA")
        }
    }

    /** A second data source for the same database, a different object, as a second pool would be. */
    private fun otherDataSource(): DataSource =
        PostgresDatabase.Access(db.jdbcUrl, db.username, db.password).dataSource()

    companion object {
        @JvmField
        @RegisterExtension
        val db = PostgresDatabase()

        /** One entity manager factory for the whole class; Hibernate takes a while to start. */
        private val jpa by lazy { Jpa.entityManagerFactory(db.dataSource) }
    }
}
