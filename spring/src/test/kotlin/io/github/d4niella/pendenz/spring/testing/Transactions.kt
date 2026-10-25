package io.github.d4niella.pendenz.spring.testing

import io.github.d4niella.pendenz.testing.PostgresDatabase
import jakarta.persistence.EntityManagerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.orm.jpa.EntityManagerFactoryUtils
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter
import javax.sql.DataSource

/** A business table of its own per test, written in the caller's transaction like an application would. */
class Invoices(
    private val db: PostgresDatabase,
) {
    val table = "invoice_${db.uniqueSuffix()}"

    init {
        db.sql("CREATE TABLE $table (number text PRIMARY KEY)")
    }

    /** Stores an invoice through JDBC, joining the Spring transaction of the calling thread. */
    fun store(
        dataSource: DataSource,
        number: String,
    ) {
        JdbcTemplate(dataSource).update("INSERT INTO $table (number) VALUES (?)", number)
    }

    /** Stores an invoice through the transaction's EntityManager, as a JPA application would. */
    fun storeWithJpa(
        factory: EntityManagerFactory,
        number: String,
    ) {
        val entityManager = checkNotNull(EntityManagerFactoryUtils.getTransactionalEntityManager(factory))
        entityManager
            .createNativeQuery("INSERT INTO $table (number) VALUES (?1)")
            .setParameter(1, number)
            .executeUpdate()
    }

    fun count(): Long = db.count("SELECT count(*) FROM $table")
}

/** A JPA setup without entities: Hibernate over the data source, the way Spring Boot builds it. */
object Jpa {
    fun entityManagerFactory(dataSource: DataSource): EntityManagerFactory =
        LocalContainerEntityManagerFactoryBean()
            .apply {
                setDataSource(dataSource)
                jpaVendorAdapter = HibernateJpaVendorAdapter()
                setPackagesToScan(Jpa::class.java.packageName)
                afterPropertiesSet()
            }.`object`!!

    fun transactionManager(factory: EntityManagerFactory): JpaTransactionManager = JpaTransactionManager(factory)
}
