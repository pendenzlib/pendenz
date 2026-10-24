package io.github.d4niella.pendenz.spring.boot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.d4niella.pendenz.TaskContext;
import io.github.d4niella.pendenz.javaapi.JavaTaskHandler;
import io.github.d4niella.pendenz.javaapi.PendenzJava;
import io.github.d4niella.pendenz.schema.TaskTable;
import io.github.d4niella.pendenz.spring.boot.testing.Billing;
import io.github.d4niella.pendenz.spring.boot.testing.MailService;
import io.github.d4niella.pendenz.testing.PostgresDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

/**
 * A Spring Boot application written in Java: a handler bean registered with
 * TaskRegistration.blocking, a @Transactional service that stores an invoice
 * and enqueues its mail, and the worker switched off as the README suggests
 * for tests, with runPending() instead.
 */
@SpringBootTest(classes = SpringBootFromJavaTest.BillingApplication.class, properties = "pendenz.worker.enabled=false")
class SpringBootFromJavaTest {

    @RegisterExtension
    static final PostgresDatabase db = new PostgresDatabase();

    static TaskTable<Long> table;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        table = db.createTable("boot_java");
        db.sql("CREATE TABLE IF NOT EXISTS boot_invoice (number text PRIMARY KEY)");
        registry.add("spring.datasource.url", db::getJdbcUrl);
        registry.add("spring.datasource.username", db::getUsername);
        registry.add("spring.datasource.password", db::getPassword);
        registry.add("pendenz.table", table::getName);
    }

    @Autowired
    InvoiceService invoices;

    @Autowired
    PendenzJava<Long> pendenz;

    @Autowired
    MailService mail;

    @Test
    void enqueue_transactionalServiceFails_leavesNeitherInvoiceNorTask() {
        // Act
        Throwable thrown = catchThrowable(() -> invoices.bill("2026-0201", true));

        // Assert
        assertThat(thrown).isInstanceOf(IllegalStateException.class);
        assertThat(db.count("SELECT count(*) FROM boot_invoice WHERE number = '2026-0201'")).isZero();
        assertThat(db.count("SELECT count(*) FROM " + table.getName() + " WHERE payload ->> 'number' = '2026-0201'"))
                .isZero();
    }

    @Test
    void runPending_invoiceBilled_handlerBeanSendsTheMail() {
        // Arrange
        invoices.bill("2026-0202", false);

        // Act
        pendenz.runPending();

        // Assert
        assertThat(mail.getSent()).contains("2026-0202");
    }

    @Test
    void runPending_reminderHandlerNamedByType_handlerUsesTheServiceThatEnqueues() {
        // Arrange
        invoices.bill("2026-0203", false);
        invoices.remind("2026-0203");

        // Act
        pendenz.runPending();

        // Assert
        assertThat(mail.getSent()).contains("reminder for 2026-0203");
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class BillingApplication {

        @Bean
        MailService mailService() {
            return new MailService();
        }

        @Bean
        TaskRegistration<Long> sendInvoice(MailService mail) {
            return TaskRegistration.blocking(Billing.SEND_INVOICE, (ctx, invoice) -> mail.send(invoice));
        }

        @Bean
        InvoiceService invoiceService(JdbcTemplate jdbc, PendenzJava<Long> pendenz, MailService mail) {
            return new InvoiceService(jdbc, pendenz, mail);
        }

        // The handler needs the service that enqueues; named by type, it is looked up on its first run.
        @Bean
        TaskRegistration<Long> sendReminder() {
            return TaskRegistration.blocking(Billing.SEND_REMINDER, ReminderHandler.class);
        }

        @Bean
        ReminderHandler reminderHandler(InvoiceService invoices) {
            return new ReminderHandler(invoices);
        }
    }

    static class ReminderHandler implements JavaTaskHandler<Long, Billing.Invoice> {
        private final InvoiceService invoices;

        ReminderHandler(InvoiceService invoices) {
            this.invoices = invoices;
        }

        @Override
        public void handle(TaskContext<Long> context, Billing.Invoice invoice) {
            invoices.sendReminder(invoice.getNumber());
        }
    }

    /** Stores an invoice and enqueues its mail in one transaction. */
    static class InvoiceService {
        private final JdbcTemplate jdbc;
        private final PendenzJava<Long> pendenz;
        private final MailService mail;

        InvoiceService(JdbcTemplate jdbc, PendenzJava<Long> pendenz, MailService mail) {
            this.jdbc = jdbc;
            this.pendenz = pendenz;
            this.mail = mail;
        }

        void remind(String number) {
            pendenz.enqueue(Billing.SEND_REMINDER, new Billing.Invoice(number));
        }

        void sendReminder(String number) {
            mail.send(new Billing.Invoice("reminder for " + number));
        }

        @Transactional
        public void bill(String number, boolean failAfterwards) {
            jdbc.update("INSERT INTO boot_invoice (number) VALUES (?)", number);
            pendenz.enqueue(Billing.SEND_INVOICE, new Billing.Invoice(number));
            if (failAfterwards) {
                throw new IllegalStateException("the payment provider refused invoice " + number);
            }
        }
    }
}
