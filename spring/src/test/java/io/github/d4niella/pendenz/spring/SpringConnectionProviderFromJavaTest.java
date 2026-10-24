package io.github.d4niella.pendenz.spring;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.d4niella.pendenz.javaapi.PendenzJava;
import io.github.d4niella.pendenz.schema.TaskTable;
import io.github.d4niella.pendenz.spring.testing.Billing;
import io.github.d4niella.pendenz.testing.PostgresDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** The blocking facade inside a Spring transaction, as a Java service calls it from a @Transactional method. */
class SpringConnectionProviderFromJavaTest {

    @RegisterExtension
    static final PostgresDatabase db = new PostgresDatabase();

    @Test
    void enqueue_callersTransactionRollsBack_leavesNoTask() {
        // Arrange
        TaskTable<Long> table = db.createTable("spring_java");
        DataSourceTransactionManager manager = new DataSourceTransactionManager(db.getDataSource());
        PendenzJava<Long> pendenz = PendenzJava.builder(table)
                .connectionProvider(new SpringConnectionProvider(db.getDataSource(), manager))
                .register(Billing.SEND_INVOICE, (ctx, invoice) -> {})
                .build();

        // Act
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            pendenz.enqueue(Billing.SEND_INVOICE, new Billing.Invoice("2026-0011"));
            status.setRollbackOnly();
        });

        // Assert
        assertThat(db.count("SELECT count(*) FROM " + table.getName())).isZero();
        pendenz.close();
    }
}
