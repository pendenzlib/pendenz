package io.github.d4niella.pendenz.examples.demo

import io.github.d4niella.pendenz.ExponentialBackoff
import io.github.d4niella.pendenz.PayloadColumn
import io.github.d4niella.pendenz.Retention
import io.github.d4niella.pendenz.SqlType
import io.github.d4niella.pendenz.TaskType
import io.github.d4niella.pendenz.schema.TaskTable
import java.time.Duration

/** The task types of the demo's billing domain and the table they live in. */
object Billing {
    val TABLE = TaskTable(name = "billing_task")
    const val ARCHIVE = "billing_task_archive"

    /** Stored as two columns, `invoice_id` and `customer`, so SQL can read, write and join them. */
    data class SendInvoice(
        val invoiceId: Long,
        val customer: String,
    )

    // Retried and archived quickly for the demo; a real service would wait minutes and days.
    private const val RETRY_DELAY_MILLIS = 300L
    private const val ARCHIVE_AFTER_SECONDS = 2L
    private const val MAX_ATTEMPTS = 3

    val SEND_INVOICE =
        TaskType.columns<SendInvoice>(
            "send-invoice",
            columns = listOf(PayloadColumn("invoice_id", SqlType.BIGINT), PayloadColumn("customer", SqlType.TEXT)),
            write = { mapOf("invoice_id" to it.invoiceId, "customer" to it.customer) },
            read = { SendInvoice(it.getLong("invoice_id"), it.getString("customer")) },
            retryPolicy =
                ExponentialBackoff(
                    maxAttempts = MAX_ATTEMPTS,
                    initialDelay = Duration.ofMillis(RETRY_DELAY_MILLIS),
                    jitter = 0.0,
                ),
            retention = Retention.archive(after = Duration.ofSeconds(ARCHIVE_AFTER_SECONDS), table = ARCHIVE),
        )
}
