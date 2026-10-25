package io.github.d4niella.pendenz.examples.spring

import io.github.d4niella.pendenz.examples.spring.Billing.SEND_INVOICE
import io.github.d4niella.pendenz.examples.spring.Billing.SendInvoice
import io.github.d4niella.pendenz.javaapi.EnqueueOptions
import io.github.d4niella.pendenz.javaapi.PendenzJava
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant

/** An invoice as the shop shows it, with the state of its mail read from the task table. */
data class InvoiceView(
    val id: Long,
    val customer: String,
    val amount: BigDecimal,
    val sentAt: Instant?,
    val mailStatus: String?,
    val mailAttempts: Int?,
)

class InvalidInvoiceException(
    message: String,
) : IllegalArgumentException(message)

/**
 * Stores invoices and queues their mail in the same transaction: the
 * invoice and its task are committed together, or neither is.
 *
 * The service uses the blocking `PendenzJava`: a Kotlin `suspend` function
 * cannot be `@Transactional` with JDBC's transaction manager.
 */
@Service
class Invoices(
    private val repository: InvoiceRepository,
    private val pendenz: PendenzJava<Long>,
    private val mail: MailServer,
) {
    @Transactional
    fun bill(
        customer: String,
        amount: BigDecimal,
    ): Long {
        val id = repository.insert(customer, amount)
        pendenz.enqueue(SEND_INVOICE, SendInvoice(id), EnqueueOptions.defaults<Long>().createdBy("shop"))
        // Checked after both writes on purpose: the rollback takes the task with the invoice.
        if (amount.signum() <= 0) throw InvalidInvoiceException("an invoice needs a positive amount, got $amount")
        return id
    }

    fun find(id: Long): InvoiceView? = repository.find(id)

    /**
     * Mails an invoice and records it; what the handler does when the task
     * runs. Pendenz runs a task at least once: after a lost lock, or when an
     * operator restarts it with SQL, it runs again, and then finds the
     * invoice sent and mails nothing.
     */
    fun send(id: Long) {
        if (repository.isSent(id)) return
        mail.send(id, repository.customerOf(id))
        repository.markSent(id)
    }
}

/** The invoice table. */
@Repository
class InvoiceRepository(
    private val jdbc: JdbcClient,
) {
    fun insert(
        customer: String,
        amount: BigDecimal,
    ): Long =
        jdbc
            .sql("INSERT INTO invoice (customer, amount) VALUES (?, ?) RETURNING id")
            .params(customer, amount)
            .query(Long::class.java)
            .single()

    /** The invoice joined to its mail task: the task table is the application's, so SQL may read it. */
    fun find(id: Long): InvoiceView? =
        jdbc
            .sql(
                """
                SELECT i.id, i.customer, i.amount, i.sent_at, t.status, t.attempts
                FROM invoice i LEFT JOIN billing_task t ON t.invoice_id = i.id
                WHERE i.id = ?
                """.trimIndent(),
            ).param(id)
            .query { rs, _ ->
                InvoiceView(
                    id = rs.getLong("id"),
                    customer = rs.getString("customer"),
                    amount = rs.getBigDecimal("amount"),
                    sentAt = rs.getTimestamp("sent_at")?.toInstant(),
                    mailStatus = rs.getString("status"),
                    mailAttempts = rs.getObject("attempts") as Int?,
                )
            }.optional()
            .orElse(null)

    fun customerOf(id: Long): String =
        jdbc
            .sql("SELECT customer FROM invoice WHERE id = ?")
            .param(id)
            .query(String::class.java)
            .single()

    fun isSent(id: Long): Boolean =
        jdbc
            .sql("SELECT sent_at IS NOT NULL FROM invoice WHERE id = ?")
            .param(id)
            .query(Boolean::class.java)
            .single()

    /** Records that the mail went out. */
    fun markSent(id: Long) {
        jdbc.sql("UPDATE invoice SET sent_at = now() WHERE id = ?").param(id).update()
    }
}
