package io.github.pendenzlib.examples.spring

import io.github.pendenzlib.TaskContext
import io.github.pendenzlib.TaskHandler
import io.github.pendenzlib.examples.spring.Billing.SEND_INVOICE
import io.github.pendenzlib.examples.spring.Billing.SendInvoice
import io.github.pendenzlib.spring.boot.TaskRegistration
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A stand-in for the mail server. One customer's server rejects the first
 * attempt of every invoice, so the example shows a retry.
 */
class MailServer(
    private val flakyFor: String = FLAKY_CUSTOMER,
) {
    private val log = LoggerFactory.getLogger(MailServer::class.java)
    private val refused = ConcurrentHashMap.newKeySet<Long>()

    /** Every invoice mailed, once per mail that went out. */
    val mailed: MutableList<Long> = CopyOnWriteArrayList()

    fun send(
        invoiceId: Long,
        customer: String,
    ) {
        if (customer == flakyFor && refused.add(invoiceId)) {
            error("the mail server of $customer is not answering")
        }
        mailed += invoiceId
        log.info("invoice {} mailed to {}", invoiceId, customer)
    }

    companion object {
        const val FLAKY_CUSTOMER = "ACME"
    }
}

/**
 * The handler, a bean like any other. It uses [Invoices], which itself
 * injects the queue; the registration names the handler's type, so the
 * queue is built without it and the handler is looked up when it first
 * runs (ADR 0025).
 */
@Component
class SendInvoiceHandler(
    private val invoices: Invoices,
) : TaskHandler<Long, SendInvoice> {
    override suspend fun handle(
        context: TaskContext<Long>,
        payload: SendInvoice,
    ) {
        invoices.send(payload.invoiceId)
    }
}

@Configuration(proxyBeanMethods = false)
class MailConfiguration {
    @Bean
    fun mailServer(): MailServer = MailServer()

    @Bean
    fun sendInvoice(): TaskRegistration<Long> = TaskRegistration.of(SEND_INVOICE, SendInvoiceHandler::class.java)
}
