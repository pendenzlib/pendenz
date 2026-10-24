package io.github.d4niella.pendenz.examples.spring

import io.github.d4niella.pendenz.examples.spring.Billing.SEND_INVOICE
import io.github.d4niella.pendenz.spring.boot.TaskRegistration
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
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

/** The handler is a bean; its dependencies come in like those of any other bean. */
@Configuration(proxyBeanMethods = false)
class MailConfiguration {
    @Bean
    fun mailServer(): MailServer = MailServer()

    @Bean
    fun sendInvoice(
        mail: MailServer,
        invoices: InvoiceRepository,
    ): TaskRegistration<Long> =
        TaskRegistration.of(SEND_INVOICE) { _, task ->
            mail.send(task.invoiceId, invoices.customerOf(task.invoiceId))
            invoices.markSent(task.invoiceId)
        }
}
