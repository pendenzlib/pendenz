package io.github.d4niella.pendenz.jackson;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.d4niella.pendenz.ExponentialBackoff;
import io.github.d4niella.pendenz.TaskType;
import io.github.d4niella.pendenz.javaapi.PendenzJava;
import io.github.d4niella.pendenz.schema.TaskTable;
import io.github.d4niella.pendenz.testing.PostgresDatabase;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** The Jackson codec through a real jsonb column, which stores a document in its own form, and a worker. */
class JacksonPostgresTest {

    record SendInvoice(long invoiceId, String currency, LocalDate dueOn) {}

    @RegisterExtension
    static final PostgresDatabase db = new PostgresDatabase();

    private TaskTable<Long> table;

    @BeforeEach
    void createTable() {
        table = db.createTable("jackson");
    }

    @Test
    void worker_recordStoredAsJsonb_handlerReceivesTheRecordAsEnqueued() {
        // Arrange
        TaskType<SendInvoice> type = new TaskType<>("send-invoice", JacksonCodec.of(SendInvoice.class));
        List<SendInvoice> received = new CopyOnWriteArrayList<>();
        SendInvoice invoice = new SendInvoice(42, "EUR", LocalDate.of(2026, 11, 30));
        try (PendenzJava<Long> pendenz = PendenzJava.builder(table)
                .dataSource(db.getDataSource())
                .register(type, (ctx, payload) -> received.add(payload))
                .build()) {

            // Act
            pendenz.enqueue(type, invoice);
            pendenz.runPending();

            assertThat(received).containsExactly(invoice);
        }
    }

    @Test
    void worker_rowWrittenWithSqlInVersionOne_isUpgradedBeforeTheHandlerSeesIt() {
        // Arrange
        TaskType<SendInvoice> type = new TaskType<>(
                "send-invoice",
                JacksonCodec.of(SendInvoice.class),
                new ExponentialBackoff(),
                2,
                Map.of(1, JacksonPayloadUpgrade.of(node -> node.put("currency", "EUR"))));
        List<SendInvoice> received = new CopyOnWriteArrayList<>();
        db.sql("INSERT INTO " + table.getName() + " (task_type, status, run_at, payload, payload_version) "
                + "VALUES ('send-invoice', 'PENDING', now(), '{\"dueOn\": \"2026-11-30\", \"invoiceId\": 7}', 1)");
        try (PendenzJava<Long> pendenz = PendenzJava.builder(table)
                .dataSource(db.getDataSource())
                .register(type, (ctx, payload) -> received.add(payload))
                .build()) {

            // Act
            pendenz.runPending();

            assertThat(received).containsExactly(new SendInvoice(7, "EUR", LocalDate.of(2026, 11, 30)));
        }
    }
}
