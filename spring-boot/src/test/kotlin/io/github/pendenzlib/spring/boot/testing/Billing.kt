package io.github.pendenzlib.spring.boot.testing

import io.github.pendenzlib.TaskResult
import io.github.pendenzlib.TaskType
import io.github.pendenzlib.Verdict
import kotlinx.serialization.Serializable
import java.util.concurrent.CopyOnWriteArrayList

/** The task the tests enqueue: an invoice to send, by a mail service the handler gets injected. */
object Billing {
    @Serializable
    data class Invoice(
        val number: String,
    )

    @JvmField
    val SEND_INVOICE: TaskType<Invoice> = TaskType.json<Invoice>("send-invoice")

    @JvmField
    val SEND_REMINDER: TaskType<Invoice> = TaskType.json<Invoice>("send-reminder")

    /** What sending produced: the invoice's number, as a result to store (ADR 0020). */
    @Serializable
    data class Receipt(
        val number: String,
    )

    @JvmField
    val SEND_WITH_RECEIPT: TaskType<Invoice> = TaskType.json<Invoice>("send-with-receipt")

    @JvmField
    val RECEIPT: TaskResult<Invoice, Receipt> = TaskResult.json<Invoice, Receipt> { _, _ -> Verdict.accept() }
}

/** A dependency of the handler, as a real one would have: records what it was asked to send. */
class MailService {
    val sent: MutableList<String> = CopyOnWriteArrayList()

    fun send(invoice: Billing.Invoice) {
        sent += invoice.number
    }
}
