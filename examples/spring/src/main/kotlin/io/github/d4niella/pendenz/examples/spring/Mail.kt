package io.github.d4niella.pendenz.examples.spring

import io.github.d4niella.pendenz.TaskContext
import io.github.d4niella.pendenz.TaskHandler
import io.github.d4niella.pendenz.examples.spring.Billing.SEND_INVOICE
import io.github.d4niella.pendenz.examples.spring.Billing.SendInvoice
import io.github.d4niella.pendenz.spring.boot.TaskRegistration
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * A stand-in for the mail server. One customer's server rejects the first
 * attempt of every invoice, so the example shows a retry.
 */
class MailServer(
    private val flakyFor: String = FLAKY_CUSTOMER,
) {
    private val log = LoggerFactory.getLogger(MailServer::class.java)
    private val refused = ConcurrentHashMap.newKeySet<Long>()

    fun send(
        invoiceId: Long,
        customer: String,
    ) {
        if (customer == flakyFor && refused.add(invoiceId)) {
            error("the mail server of $customer is not answering")
        }
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
    private val mail: MailServer,
) : TaskHandler<Long, SendInvoice> {
    override suspend fun handle(
        context: TaskContext<Long>,
        payload: SendInvoice,
    ) {
        invoices.send(payload.invoiceId, mail)
    }
}

@Configuration(proxyBeanMethods = false)
class MailConfiguration {
    @Bean
    fun mailServer(): MailServer = MailServer()

    @Bean
    fun sendInvoice(): TaskRegistration<Long> = TaskRegistration.of(SEND_INVOICE, SendInvoiceHandler::class.java)
}
