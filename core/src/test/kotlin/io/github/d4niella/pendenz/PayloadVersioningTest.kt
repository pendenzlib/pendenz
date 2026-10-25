package io.github.d4niella.pendenz

import io.github.d4niella.pendenz.schema.IdType
import io.github.d4niella.pendenz.store.InMemoryTaskStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

/** Payload schema versions and upgrades (ADR 0010), through the in-memory mode. */
class PayloadVersioningTest {
    @Serializable
    data class SendInvoice(
        val invoiceId: Long,
        val currency: String,
    )

    /** The payload as version 1 wrote it, before currencies existed. */
    @Serializable
    data class SendInvoiceV1(
        val invoiceId: Long,
    )

    private fun json(document: String): Map<String, Any?> = mapOf("payload" to document)

    private val addCurrency =
        PayloadUpgrade.json { old -> JsonObject(old + ("currency" to JsonPrimitive("EUR"))) }

    @Nested
    inner class Decode {
        @Test
        fun `decode - stored version older with an upgrade registered - upgrades before decoding`() {
            val type = TaskType.json<SendInvoice>("send-invoice", version = 2, upgrades = mapOf(1 to addCurrency))

            val payload = type.decode(json("""{"invoiceId":42}"""), storedVersion = 1)

            assertThat(payload).isEqualTo(SendInvoice(42, "EUR"))
        }

        @Test
        fun `decode - stored version equals the type's - decodes as is`() {
            val type = TaskType.json<SendInvoice>("send-invoice", version = 2, upgrades = mapOf(1 to addCurrency))

            val payload = type.decode(json("""{"invoiceId":42,"currency":"CHF"}"""), storedVersion = 2)

            assertThat(payload.currency).isEqualTo("CHF")
        }

        @Test
        fun `decode - stored version older with a gap in the upgrades - throws UnsupportedPayloadVersionException`() {
            val type = TaskType.json<SendInvoice>("send-invoice", version = 3, upgrades = mapOf(2 to addCurrency))

            assertThatThrownBy { type.decode(json("""{"invoiceId":42}"""), storedVersion = 1) }
                .isInstanceOf(UnsupportedPayloadVersionException::class.java)
                .hasMessageContaining("version 3")
                .hasMessageContaining("version 1")
        }

        @Test
        fun `decode - upgrade step throws - throws UnsupportedPayloadVersionException with that cause`() {
            // Arrange
            val broken = PayloadUpgrade { error("currency table missing") }
            val type = TaskType.json<SendInvoice>("send-invoice", version = 2, upgrades = mapOf(1 to broken))

            val thrown = catchThrowable { type.decode(json("""{"invoiceId":42}"""), storedVersion = 1) }

            // Assert
            assertThat(thrown).isInstanceOf(UnsupportedPayloadVersionException::class.java)
            assertThat(thrown.cause).hasMessage("currency table missing")
        }

        @Test
        fun `decode - stored version newer than the type - throws UnsupportedPayloadVersionException`() {
            val type = TaskType.json<SendInvoice>("send-invoice", version = 1)

            assertThatThrownBy { type.decode(json("""{"invoiceId":42,"currency":"EUR"}"""), storedVersion = 2) }
                .isInstanceOf(UnsupportedPayloadVersionException::class.java)
        }
    }

    @Nested
    inner class Configuration {
        @Test
        fun `constructor - upgrade from a version at or above the type's - throws IllegalArgumentException`() {
            assertThatThrownBy { TaskType.json<SendInvoice>("x", version = 2, upgrades = mapOf(2 to addCurrency)) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Nested
    inner class Execution {
        @Test
        fun `runPending - task of version 1 claimed by a version 2 handler - runs with the upgraded payload`() =
            runBlocking<Unit> {
                // Arrange
                val shared = InMemoryTaskStore(IdType.BIGINT)
                val v1 = TaskType.json<SendInvoiceV1>("send-invoice", version = 1)
                val producer =
                    Pendenz.create {
                        store(shared)
                        register(v1) { _, _ -> }
                    }
                val consumed = CopyOnWriteArrayList<SendInvoice>()
                val v2 = TaskType.json<SendInvoice>("send-invoice", version = 2, upgrades = mapOf(1 to addCurrency))
                val consumer =
                    Pendenz.create {
                        store(shared)
                        register(v2) { _, p -> consumed += p }
                    }
                val id = producer.enqueue(v1, SendInvoiceV1(42))

                consumer.runPending()

                // Assert
                assertThat(consumed).containsExactly(SendInvoice(42, "EUR"))
                assertThat(consumer.find(id)!!.payloadVersion).isEqualTo(1)
            }

        @Test
        fun `runPending - task newer than the handler - FAILED at once without consulting the retry policy`() =
            runBlocking<Unit> {
                // Arrange
                val shared = InMemoryTaskStore(IdType.BIGINT)
                val v2 = TaskType.json<SendInvoice>("send-invoice", version = 2)
                val producer =
                    Pendenz.create {
                        store(shared)
                        register(v2) { _, _ -> }
                    }
                val v1 = TaskType.json<SendInvoice>("send-invoice", version = 1)
                val consumer =
                    Pendenz.create {
                        store(shared)
                        register(v1) { _, _ -> }
                    }
                val id = producer.enqueue(v2, SendInvoice(42, "EUR"))

                consumer.runPending()

                // Assert
                val record = consumer.find(id)!!
                assertThat(record.status).isEqualTo(TaskStatus.FAILED)
                assertThat(record.attempts).isEqualTo(1)
                assertThat(record.lastError).contains("version 2")
            }
    }
}
