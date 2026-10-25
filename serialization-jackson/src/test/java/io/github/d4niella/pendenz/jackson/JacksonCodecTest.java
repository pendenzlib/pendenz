package io.github.d4niella.pendenz.jackson;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.d4niella.pendenz.ExponentialBackoff;
import io.github.d4niella.pendenz.PayloadUpgrade;
import io.github.d4niella.pendenz.TaskStatus;
import io.github.d4niella.pendenz.TaskType;
import io.github.d4niella.pendenz.UnsupportedPayloadVersionException;
import io.github.d4niella.pendenz.javaapi.PendenzJava;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Written in Java on purpose: the module exists for teams that write their payloads in Java. */
class JacksonCodecTest {

    record SendInvoice(long invoiceId, String currency, LocalDate dueOn) {}

    @Nested
    class RoundTrip {
        @Test
        void codec_recordWithADate_encodesToJsonAndDecodesBack() {
            // Arrange
            JacksonCodec<SendInvoice> codec = JacksonCodec.of(SendInvoice.class);
            SendInvoice invoice = new SendInvoice(42, "EUR", LocalDate.of(2026, 11, 30));

            String text = codec.encode(invoice);

            // Assert
            assertThat(text).contains("\"invoiceId\":42").contains("2026-11-30");
            assertThat(codec.decode(text)).isEqualTo(invoice);
        }

        @Test
        void codec_genericListType_decodesTheElements() {
            // Arrange
            JacksonCodec<List<SendInvoice>> codec = JacksonCodec.of(new TypeReference<>() {});
            List<SendInvoice> batch = List.of(new SendInvoice(1, "EUR", LocalDate.of(2026, 1, 1)));

            List<SendInvoice> decoded = codec.decode(codec.encode(batch));

            assertThat(decoded).containsExactlyElementsOf(batch);
        }

        @Test
        void codec_ownMapper_isUsedForEncoding() {
            // Arrange
            JsonMapper mapper = JsonMapper.builder().enable(tools.jackson.databind.SerializationFeature.INDENT_OUTPUT).build();
            JacksonCodec<SendInvoice> codec = JacksonCodec.of(SendInvoice.class, mapper);

            String text = codec.encode(new SendInvoice(7, "CHF", LocalDate.of(2026, 2, 2)));

            assertThat(text).contains("\n");
        }
    }

    @Nested
    class Upgrades {
        @Test
        void upgrade_storedVersionWithoutCurrency_addsTheFieldBeforeDecoding() {
            // Arrange
            PayloadUpgrade addCurrency = JacksonPayloadUpgrade.of(node -> node.put("currency", "EUR"));
            TaskType<SendInvoice> type = new TaskType<>(
                    "send-invoice", JacksonCodec.of(SendInvoice.class), new ExponentialBackoff(), 2, Map.of(1, addCurrency));

            SendInvoice decoded = type.decode(Map.of("payload", "{\"invoiceId\":42,\"dueOn\":\"2026-11-30\"}"), 1);

            assertThat(decoded).isEqualTo(new SendInvoice(42, "EUR", LocalDate.of(2026, 11, 30)));
        }

        @Test
        void upgrade_storedVersionNewerThanTheType_throwsUnsupportedPayloadVersionException() {
            TaskType<SendInvoice> type = new TaskType<>("send-invoice", JacksonCodec.of(SendInvoice.class));

            assertThatThrownBy(() -> type.decode(Map.of("payload", "{}"), 2))
                    .isInstanceOf(UnsupportedPayloadVersionException.class);
        }

        @Test
        void upgrade_customColumn_rewritesThatColumnAndKeepsTheOthers() {
            // Arrange
            PayloadUpgrade addCurrency = JacksonPayloadUpgrade.of(
                    node -> node.put("currency", "EUR"), JsonMapper.builder().build(), "invoice");
            Map<String, Object> stored = Map.of("invoice", "{\"invoiceId\":42}", "customer", "Globex");

            Map<String, Object> upgraded = addCurrency.upgrade(stored);

            // Assert
            assertThat(upgraded.get("invoice")).isEqualTo("{\"invoiceId\":42,\"currency\":\"EUR\"}");
            assertThat(upgraded.get("customer")).isEqualTo("Globex");
        }

        @Test
        void upgrade_arrayPayload_throwsIllegalArgumentException() {
            PayloadUpgrade addCurrency = JacksonPayloadUpgrade.of(node -> node.put("currency", "EUR"));

            assertThatThrownBy(() -> addCurrency.upgrade(Map.of("payload", "[1, 2]")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not a JSON object");
        }

        @Test
        void upgrade_columnMissing_throwsIllegalArgumentExceptionNamingIt() {
            PayloadUpgrade addCurrency = JacksonPayloadUpgrade.of(node -> node.put("currency", "EUR"));

            assertThatThrownBy(() -> addCurrency.upgrade(Map.of("invoice", "{}")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("payload");
        }
    }

    @Nested
    class WithTheQueue {
        @Test
        void runPending_recordPayloadThroughTheJavaFacade_reachesTheHandlerDecoded() {
            // Arrange
            TaskType<SendInvoice> sendInvoice = new TaskType<>("send-invoice", JacksonCodec.of(SendInvoice.class));
            List<SendInvoice> sent = new CopyOnWriteArrayList<>();
            try (PendenzJava<Long> pendenz =
                    PendenzJava.builder().inMemory().register(sendInvoice, (ctx, p) -> sent.add(p)).build()) {
                SendInvoice invoice = new SendInvoice(42, "EUR", LocalDate.of(2026, 11, 30));
                Long id = pendenz.enqueue(sendInvoice, invoice);

                pendenz.runPending();

                // Assert
                assertThat(sent).containsExactly(invoice);
                assertThat(pendenz.find(id).orElseThrow().getStatus()).isEqualTo(TaskStatus.SUCCEEDED);
            }
        }
    }
}
