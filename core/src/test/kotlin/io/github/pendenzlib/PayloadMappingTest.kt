package io.github.pendenzlib

import io.github.pendenzlib.schema.payloadColumnsOf
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant

/** Payload columns (ADR 0013): the mapping itself, without a store. */
class PayloadMappingTest {
    data class SendInvoice(
        val invoiceId: Long,
        val currency: String,
        val dueAt: Instant?,
    )

    private val columns =
        listOf(
            PayloadColumn("invoice_id", SqlType.BIGINT),
            PayloadColumn("currency", SqlType.TEXT),
            PayloadColumn("due_at", SqlType.TIMESTAMPTZ),
        )

    private val sendInvoice =
        TaskType.columns<SendInvoice>(
            "send-invoice",
            columns,
            write = { mapOf("invoice_id" to it.invoiceId, "currency" to it.currency, "due_at" to it.dueAt) },
            read = { SendInvoice(it.getLong("invoice_id"), it.getString("currency"), it.getInstantOrNull("due_at")) },
        )

    @Nested
    inner class Encode {
        @Test
        fun `encode - optional field null - writes null into the column`() {
            val values = sendInvoice.encode(SendInvoice(42, "EUR", null))

            assertThat(values)
                .containsEntry("invoice_id", 42L)
                .containsEntry("currency", "EUR")
                .containsEntry("due_at", null)
        }

        @Test
        fun `encode - mapping writes an undeclared column - throws IllegalArgumentException`() {
            // Arrange
            val sloppy =
                TaskType.columns<SendInvoice>(
                    "sloppy",
                    columns,
                    write = { mapOf("invoice_id" to it.invoiceId, "customer" to "nobody") },
                    read = { SendInvoice(it.getLong("invoice_id"), "EUR", null) },
                )

            assertThatThrownBy { sloppy.encode(SendInvoice(1, "EUR", null)) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("customer")
        }
    }

    @Nested
    inner class Decode {
        @Test
        fun `decode - all columns present - builds the payload`() {
            val due = Instant.parse("2026-11-30T00:00:00Z")

            val payload = sendInvoice.decode(mapOf("invoice_id" to 42L, "currency" to "CHF", "due_at" to due), 1)

            assertThat(payload).isEqualTo(SendInvoice(42, "CHF", due))
        }

        @Test
        fun `decode - required column absent - throws NoSuchElementException naming the column`() {
            assertThatThrownBy { sendInvoice.decode(mapOf("currency" to "EUR"), 1) }
                .isInstanceOf(NoSuchElementException::class.java)
                .hasMessageContaining("invoice_id")
        }

        @Test
        fun `decode - column holds another type - throws ClassCastException naming both types`() {
            assertThatThrownBy { sendInvoice.decode(mapOf("invoice_id" to "forty-two", "currency" to "EUR"), 1) }
                .isInstanceOf(ClassCastException::class.java)
                .hasMessageContaining("String")
                .hasMessageContaining("Long")
        }

        @Test
        fun `decode - older version with a column upgrade - applies it before reading`() {
            // Arrange
            val addCurrency = PayloadUpgrade { it + ("currency" to "EUR") }
            val v2 =
                TaskType.columns<SendInvoice>(
                    "send-invoice",
                    columns,
                    write = { mapOf("invoice_id" to it.invoiceId, "currency" to it.currency) },
                    read = { SendInvoice(it.getLong("invoice_id"), it.getString("currency"), null) },
                    version = 2,
                    upgrades = mapOf(1 to addCurrency),
                )

            val payload = v2.decode(mapOf("invoice_id" to 7L), storedVersion = 1)

            assertThat(payload.currency).isEqualTo("EUR")
        }
    }

    @Nested
    inner class SharedTable {
        @Test
        fun `payloadColumnsOf - types sharing a column of the same type - lists it once`() {
            // Arrange
            val mail =
                TaskType.columns<Long>("mail", listOf(PayloadColumn("ticket_id", SqlType.BIGINT)), {
                    mapOf("ticket_id" to it)
                }, { it.getLong("ticket_id") })
            val credit =
                TaskType.columns<Long>("credit", listOf(PayloadColumn("ticket_id", SqlType.BIGINT)), {
                    mapOf("ticket_id" to it)
                }, { it.getLong("ticket_id") })

            val union = payloadColumnsOf(listOf(mail, credit, sendInvoice))

            assertThat(union.map { it.name }).containsExactly("ticket_id", "invoice_id", "currency", "due_at")
        }

        @Test
        fun `payloadColumnsOf - two types disagree on a column's type - throws IllegalArgumentException`() {
            // Arrange
            val asText =
                TaskType.columns<String>("a", listOf(PayloadColumn("ticket_id", SqlType.TEXT)), {
                    mapOf("ticket_id" to it)
                }, { it.getString("ticket_id") })
            val asNumber =
                TaskType.columns<Long>("b", listOf(PayloadColumn("ticket_id", SqlType.BIGINT)), {
                    mapOf("ticket_id" to it)
                }, { it.getLong("ticket_id") })

            assertThatThrownBy { payloadColumnsOf(listOf(asText, asNumber)) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("ticket_id")
        }

        @Test
        fun `payloadColumnsOf - no types - the default JSON column`() {
            val union = payloadColumnsOf(emptyList())

            assertThat(union).containsExactly(PayloadColumn("payload", SqlType.JSONB))
        }
    }
}
