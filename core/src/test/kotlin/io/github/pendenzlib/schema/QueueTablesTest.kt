package io.github.pendenzlib.schema

import io.github.pendenzlib.ConnectionProvider
import io.github.pendenzlib.Pendenz
import io.github.pendenzlib.store.InMemoryTaskStore
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.sql.Connection

/** What `Pendenz.tables` reports for the tables a builder turned on (ADR 0037). */
class QueueTablesTest {
    /** The tables are only reported, so no test here needs a connection. */
    private val noConnections =
        object : ConnectionProvider {
            override suspend fun <T> inTransaction(block: (Connection) -> T): T = error("this test opens no connection")
        }

    private val invoices = TaskTable(IdType.BIGINT, "billing.invoice_task")

    @Test
    fun `tables - database store without optional tables - reports the task table and null for the others`() {
        val pendenz = Pendenz.create(invoices) { connectionProvider(noConnections) }

        val tables = pendenz.tables!!

        assertThat(tables.task).isSameAs(invoices)
        assertThat(listOf(tables.cycles, tables.resourceLimits, tables.resourceBlocks)).containsOnlyNulls()
    }

    @Test
    fun `tables - cycles, limits and blocks turned on - reports each table as the builder got it`() {
        // Arrange
        val cycles = CycleTable(IdType.BIGINT, "billing.invoice_cycle")
        val limits = ResourceLimitTable("billing.partner_limit")
        val blocks = ResourceBlockTable("billing.partner_block")

        // Act
        val pendenz =
            Pendenz.create(invoices) {
                connectionProvider(noConnections)
                enableCycles(cycles)
                enableResourceLimits(limits)
                enableResourceBlocks(blocks)
            }

        // Assert
        val tables = pendenz.tables!!
        assertThat(tables.cycles).isSameAs(cycles)
        assertThat(tables.resourceLimits).isSameAs(limits)
        assertThat(tables.resourceBlocks).isSameAs(blocks)
    }

    @Test
    fun `tables - in-memory mode - null`() {
        val pendenz = Pendenz.create { inMemory() }

        assertThat(pendenz.tables).isNull()
    }

    @Test
    fun `tables - store of the application - null, since the store may not use the task table`() {
        val pendenz = Pendenz.create { store(InMemoryTaskStore(IdType.BIGINT)) }

        assertThat(pendenz.tables).isNull()
    }
}
