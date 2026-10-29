package io.github.pendenzlib.examples.demo

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.github.pendenzlib.testing.PostgresDatabase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

/** The billing demo as the README promises it, against PostgreSQL in a schema of its own. */
class DemoEndToEndTest {
    private lateinit var dataSource: HikariDataSource
    private lateinit var schema: String

    @BeforeEach
    fun createSchema() {
        schema = "demo_${db.uniqueSuffix()}"
        db.sql("""CREATE SCHEMA "$schema"""")
        val config = HikariConfig()
        config.jdbcUrl = db.jdbcUrl
        config.username = db.username
        config.password = db.password
        config.schema = schema
        dataSource = HikariDataSource(config)
    }

    @AfterEach
    fun close() = dataSource.close()

    @Test
    fun `demo - full run - three invoices sent and archived, ACME on its fourth attempt after the SQL restart`() {
        // Act
        runDemo(dataSource)

        // Assert
        assertThat(db.count("""SELECT count(*) FROM "$schema".billing_task""")).isZero()
        val archived = """SELECT count(*) FROM "$schema".billing_task_archive WHERE status = 'SUCCEEDED'"""
        assertThat(db.count(archived)).isEqualTo(3)
        val acme = """SELECT attempts FROM "$schema".billing_task_archive WHERE customer = 'ACME'"""
        assertThat(db.count(acme)).isEqualTo(4)
    }

    companion object {
        @JvmField
        @RegisterExtension
        val db = PostgresDatabase()
    }
}
