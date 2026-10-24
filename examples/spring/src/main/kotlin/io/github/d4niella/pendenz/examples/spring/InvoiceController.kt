package io.github.d4niella.pendenz.examples.spring

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal

data class NewInvoice(
    val customer: String,
    val amount: BigDecimal,
)

data class Billed(
    val id: Long,
)

/** `POST /invoices` bills a customer, `GET /invoices/{id}` shows the invoice and its mail. */
@RestController
@RequestMapping("/invoices")
class InvoiceController(
    private val invoices: Invoices,
) {
    @PostMapping
    fun bill(
        @RequestBody invoice: NewInvoice,
    ): ResponseEntity<Billed> =
        ResponseEntity.status(HttpStatus.CREATED).body(Billed(invoices.bill(invoice.customer, invoice.amount)))

    @GetMapping("/{id}")
    fun show(
        @PathVariable id: Long,
    ): ResponseEntity<InvoiceView> =
        invoices.find(id)?.let { ResponseEntity.ok(it) } ?: ResponseEntity.notFound().build()

    @ExceptionHandler(InvalidInvoiceException::class)
    fun invalid(e: InvalidInvoiceException): ResponseEntity<Map<String, String?>> =
        ResponseEntity.badRequest().body(mapOf("error" to e.message))
}
