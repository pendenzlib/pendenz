package io.github.d4niella.pendenz.examples.demo

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.github.d4niella.pendenz.Pendenz
import io.github.d4niella.pendenz.TaskContext
import io.github.d4niella.pendenz.TaskListener
import io.github.d4niella.pendenz.WorkerOptions
import io.github.d4niella.pendenz.examples.demo.Billing.SendInvoice
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.plus
import kotlinx.coroutines.runBlocking
import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import javax.sql.DataSource

private val log = LoggerFactory.getLogger("demo")

private const val MIGRATIONS = "classpath:io/github/d4niella/pendenz/examples/demo/db/migration"
private const val POLL_MILLIS = 100L
private const val MAINTENANCE_SECONDS = 1L
private const val ROUND_SECONDS = 30L
private const val INVOICES = 3L
private const val POOL_SIZE = 5
private const val GLOBEX_INVOICE = 1001L
private const val ACME_INVOICE = 1002L
private const val INITECH_INVOICE = 1003L

/**
 * A billing service in miniature: Flyway creates the table, HikariCP pools
 * the connections, a worker sends invoices. One customer's mail server is
 * down, so you see retries and a failure; an operator restarts the failed
 * task with SQL; retention moves finished rows into the archive table.
 *
 * Runs against `PENDENZ_JDBC_URL` (with `PENDENZ_DB_USER` and
 * `PENDENZ_DB_PASSWORD`) when set, otherwise against an embedded PostgreSQL.
 */
fun main() {
    val dataSource = dataSource()
    runDemo(dataSource)
    (dataSource as AutoCloseable).close()
}

/** The demo on [dataSource]: migrates, runs both rounds and stops its worker; the tables stay for a look. */
fun runDemo(dataSource: DataSource) =
    runBlocking<Unit> {
        Flyway
            .configure()
            .dataSource(dataSource)
            .locations(MIGRATIONS)
            .load()
            .migrate()

        val mailServer = FlakyMailServer(brokenFor = "ACME")
        val pendenz =
            Pendenz.create(Billing.TABLE) {
                dataSource(dataSource)
                listener(LogEvents)
                register(Billing.SEND_INVOICE) { ctx, invoice -> mailServer.send(invoice, attempt = ctx.attempt) }
            }
        val options =
            WorkerOptions(
                pollInterval = Duration.ofMillis(POLL_MILLIS),
                maintenanceInterval = Duration.ofSeconds(MAINTENANCE_SECONDS),
            )
        // Handlers of real services block on I/O, so the worker runs on the IO dispatcher
        val worker = pendenz.startWorker(this + Dispatchers.IO, options)

        log.info("enqueuing three invoices, one of them for the customer whose mail server is down")
        pendenz.enqueue(Billing.SEND_INVOICE, SendInvoice(GLOBEX_INVOICE, "Globex"), createdBy = "billing-run")
        pendenz.enqueue(Billing.SEND_INVOICE, SendInvoice(ACME_INVOICE, "ACME"), createdBy = "billing-run")
        val initech = SendInvoice(INITECH_INVOICE, "Initech")
        pendenz.enqueue(Billing.SEND_INVOICE, initech, idempotencyKey = "invoice-$INITECH_INVOICE")
        pendenz.enqueue(Billing.SEND_INVOICE, initech, idempotencyKey = "invoice-$INITECH_INVOICE")
        log.info("the fourth enqueue repeated the third one's key, so only three tasks exist")

        waitFor(dataSource, "ACME to fail three times while the others are sent") {
            count(it, "SELECT count(*) FROM billing_task WHERE status = 'FAILED'") == 1L &&
                count(it, "SELECT count(*) FROM billing_task WHERE status = 'SUCCEEDED'") == INVOICES - 1
        }
        printTable(dataSource, "after the first round: ACME failed three times and waits for a human")

        log.info("the mail server is fixed; an operator restarts the failed task with the generated SQL function")
        mailServer.brokenFor = null
        dataSource.connection.use { c ->
            c.createStatement().use { statement ->
                statement
                    .executeQuery(
                        "SELECT billing_task_restart(id, version) FROM billing_task WHERE status = 'FAILED'",
                    ).close()
            }
        }

        waitFor(dataSource, "the restarted task to succeed and retention to archive all three") {
            count(it, "SELECT count(*) FROM billing_task") == 0L &&
                count(it, "SELECT count(*) FROM billing_task_archive WHERE status = 'SUCCEEDED'") == INVOICES
        }
        printTable(dataSource, "after the restart and a retention sweep: everything succeeded and was archived")
        printArchive(dataSource)

        worker.cancel()
        worker.join()
    }

/** Polls [reached] until it holds; the demo waits for states, not for a fixed time. */
private suspend fun waitFor(
    dataSource: DataSource,
    what: String,
    reached: (DataSource) -> Boolean,
) {
    log.info("waiting for {}", what)
    val deadline = Instant.now().plusSeconds(ROUND_SECONDS)
    while (!reached(dataSource)) {
        check(Instant.now().isBefore(deadline)) { "gave up waiting for $what after $ROUND_SECONDS s" }
        delay(POLL_MILLIS)
    }
}

private fun count(
    dataSource: DataSource,
    query: String,
): Long =
    dataSource.connection.use { c ->
        c.createStatement().use { statement ->
            statement.executeQuery(query).use { rs ->
                rs.next()
                rs.getLong(1)
            }
        }
    }

/** Sends mail, except to the customer whose server is down. */
private class FlakyMailServer(
    @Volatile var brokenFor: String?,
) {
    fun send(
        invoice: SendInvoice,
        attempt: Int,
    ) {
        check(invoice.customer != brokenFor) { "mail server of ${invoice.customer} refused the connection" }
        log.info("sent invoice {} to {} on attempt {}", invoice.invoiceId, invoice.customer, attempt)
    }
}

/** The listener hooks, used here for logging; Micrometer would plug in the same way. */
private object LogEvents : TaskListener<Long> {
    override fun onRetryScheduled(
        task: TaskContext<Long>,
        error: Throwable,
        nextRunAt: Instant,
    ) = log.warn("task {} attempt {} failed, retry at {}: {}", task.id, task.attempt, nextRunAt, error.message)

    override fun onFailed(
        task: TaskContext<Long>,
        error: Throwable,
    ) = log.error("task {} gave up after {} attempts: {}", task.id, task.attempt, error.message)

    override fun onRetentionApplied(
        type: String,
        count: Int,
    ) = log.info("retention archived {} task(s) of type {}", count, type)
}

private fun dataSource(): DataSource {
    val url = System.getenv("PENDENZ_JDBC_URL")
    val config = HikariConfig()
    if (url != null) {
        config.jdbcUrl = url
        config.username = System.getenv("PENDENZ_DB_USER")
        config.password = System.getenv("PENDENZ_DB_PASSWORD")
    } else {
        log.info("PENDENZ_JDBC_URL not set, starting an embedded PostgreSQL")
        val embedded = EmbeddedPostgres.start()
        config.jdbcUrl = embedded.getJdbcUrl("postgres", "postgres")
        config.username = "postgres"
        config.password = "postgres"
    }
    config.maximumPoolSize = POOL_SIZE
    return HikariDataSource(config)
}

private fun printTable(
    dataSource: DataSource,
    headline: String,
) {
    log.info(headline)
    val columns = "id, status, attempts, customer, created_by, last_error"
    dataSource.connection.use { c ->
        c.createStatement().executeQuery("SELECT $columns FROM billing_task ORDER BY id").use { rs ->
            while (rs.next()) {
                log.info(
                    "  #{} {} attempts={} customer={} created_by={} {}",
                    rs.getLong("id"),
                    rs.getString("status"),
                    rs.getInt("attempts"),
                    rs.getString("customer"),
                    rs.getString("created_by"),
                    rs.getString("last_error") ?: "",
                )
            }
        }
    }
}

private fun printArchive(dataSource: DataSource) {
    val query = "SELECT id, status, finished_at FROM billing_task_archive ORDER BY id"
    dataSource.connection.use { c ->
        c.createStatement().executeQuery(query).use { rs ->
            while (rs.next()) {
                val finishedAt = rs.getTimestamp("finished_at")
                log.info("  archived #{} {} finished at {}", rs.getLong("id"), rs.getString("status"), finishedAt)
            }
        }
    }
}
