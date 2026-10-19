package io.github.d4niella.pendenz.javaapi;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.d4niella.pendenz.PayloadColumn;
import io.github.d4niella.pendenz.SqlType;
import io.github.d4niella.pendenz.TaskType;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/** A columns mapping written in Java: two lambdas, no reflection. */
class PayloadColumnsFromJavaTest {

    record SendInvoice(long invoiceId, String customer) {}

    static final TaskType<SendInvoice> SEND_INVOICE = TaskType.columns(
            "send-invoice",
            List.of(new PayloadColumn("invoice_id", SqlType.BIGINT), new PayloadColumn("customer", SqlType.TEXT)),
            p -> Map.of("invoice_id", p.invoiceId(), "customer", p.customer()),
            v -> new SendInvoice(v.getLong("invoice_id"), v.getString("customer")));

    @Test
    void runPending_columnsMappingFromJava_roundTripsThroughTheStore() {
        // Arrange
        List<SendInvoice> sent = new CopyOnWriteArrayList<>();
        try (PendenzJava<Long> pendenz =
                PendenzJava.builder().inMemory().register(SEND_INVOICE, (ctx, p) -> sent.add(p)).build()) {
            Long id = pendenz.enqueue(SEND_INVOICE, new SendInvoice(42, "Globex"));

            pendenz.runPending();

            // Assert
            assertThat(sent).containsExactly(new SendInvoice(42, "Globex"));
            assertThat(pendenz.find(id).orElseThrow().getPayload()).containsEntry("customer", "Globex");
        }
    }
}
