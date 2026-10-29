package io.github.pendenzlib.spring.testing

import io.github.pendenzlib.TaskType
import kotlinx.serialization.Serializable

/** The task the tests enqueue next to a business change: an invoice stored, its mail queued. */
object Billing {
    @Serializable
    data class Invoice(
        val number: String,
    )

    @JvmField
    val SEND_INVOICE: TaskType<Invoice> = TaskType.json<Invoice>("send-invoice")
}
