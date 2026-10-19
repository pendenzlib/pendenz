package io.github.d4niella.pendenz.examples.demo

import io.github.d4niella.pendenz.ExponentialBackoff
import io.github.d4niella.pendenz.Retention
import io.github.d4niella.pendenz.TaskType
import io.github.d4niella.pendenz.schema.TaskTable
import kotlinx.serialization.Serializable
import java.time.Duration

/** The task types of the demo's billing domain and the table they live in. */
object Billing {
    val TABLE = TaskTable(name = "billing_task")
    const val ARCHIVE = "billing_task_archive"

    @Serializable
    data class SendInvoice(
        val invoiceId: Long,
        val customer: String,
    )

    // Retried and archived quickly for the demo; a real service would wait minutes and days.
    private const val RETRY_DELAY_MILLIS = 300L
    private const val ARCHIVE_AFTER_SECONDS = 2L
    private const val MAX_ATTEMPTS = 3

    val SEND_INVOICE =
        TaskType.json<SendInvoice>(
            "send-invoice",
            retryPolicy =
                ExponentialBackoff(
                    maxAttempts = MAX_ATTEMPTS,
                    initialDelay = Duration.ofMillis(RETRY_DELAY_MILLIS),
                    jitter = 0.0,
                ),
            retention = Retention.archive(after = Duration.ofSeconds(ARCHIVE_AFTER_SECONDS), table = ARCHIVE),
        )
}
