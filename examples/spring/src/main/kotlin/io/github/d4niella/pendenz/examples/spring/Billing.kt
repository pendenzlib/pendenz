package io.github.d4niella.pendenz.examples.spring

import io.github.d4niella.pendenz.ExponentialBackoff
import io.github.d4niella.pendenz.PayloadColumn
import io.github.d4niella.pendenz.SqlType
import io.github.d4niella.pendenz.TaskType
import io.github.d4niella.pendenz.schema.TaskTable
import java.time.Duration

/** The shop's one task: mail an invoice once it is stored. */
object Billing {
    /** The task table; `pendenz.table` in `application.properties` names the same. */
    val TABLE = TaskTable(name = "billing_task")

    data class SendInvoice(
        val invoiceId: Long,
    )

    // Retried quickly for the example; a real service would back off for minutes.
    private const val MAX_ATTEMPTS = 3
    private val RETRY_DELAY = Duration.ofSeconds(1)

    /** Stored as the column `invoice_id`, so SQL joins the tasks to their invoices. */
    val SEND_INVOICE =
        TaskType.columns<SendInvoice>(
            "send-invoice",
            columns = listOf(PayloadColumn("invoice_id", SqlType.BIGINT)),
            write = { mapOf("invoice_id" to it.invoiceId) },
            read = { SendInvoice(it.getLong("invoice_id")) },
            retryPolicy = ExponentialBackoff(maxAttempts = MAX_ATTEMPTS, initialDelay = RETRY_DELAY, jitter = 0.0),
        )
}
