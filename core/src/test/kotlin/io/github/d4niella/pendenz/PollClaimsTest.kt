package io.github.d4niella.pendenz

import io.github.d4niella.pendenz.schema.PostgresDdl
import io.github.d4niella.pendenz.schema.ResourceLimitTable
import io.github.d4niella.pendenz.schema.TaskTable
import io.github.d4niella.pendenz.store.ClaimGroup
import io.github.d4niella.pendenz.store.JdbcTaskStore
import io.github.d4niella.pendenz.store.NewTask
import io.github.d4niella.pendenz.store.StoreTransaction
import io.github.d4niella.pendenz.store.TaskStore
import io.github.d4niella.pendenz.testing.PostgresDatabase
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.time.Instant

/**
 * The claims of one poll against PostgreSQL, where the store does not
 * count limits itself: the poll's tally keeps them (ADR 0029).
 */
class PollClaimsTest {
    private lateinit var store: TaskStore<Long>
    private val now = Instant.parse("2026-10-25T09:00:00Z")

    @BeforeEach
    fun createStore() {
        val table: TaskTable<Long> = db.createTable("poll")
        val limits = ResourceLimitTable(name = "${table.name}_limit")
        db.sql(PostgresDdl.createResourceLimitTable(limits))
        val json = listOf(PayloadColumn("payload", SqlType.JSONB))
        store = JdbcTaskStore(table, DataSourceConnectionProvider(db.dataSource), json, limitTable = limits)
    }

    @Test
    fun `fromGroup - ten free permits, resource limited to two - claims two, the rest wait`() =
        runBlocking<Unit> {
            // Arrange
            store.transaction { store.resourceLimits!!.setLimit(it, ResourceLimit("mail-server", 2)) }
            repeat(5) { enqueue(resource = "mail-server") }

            val claimed = poll { claims -> claims.fromGroup(ClaimGroup(0, "mail", null), 10).size }

            assertThat(claimed).isEqualTo(2)
        }

    @Test
    fun `dueGroups - lanes whose resource is at its limit - only the lane with a free resource is listed`() =
        runBlocking<Unit> {
            // Arrange: tenant-a's resource is busy with a running task, tenant-b's is free
            store.transaction { store.resourceLimits!!.setLimit(it, ResourceLimit("customer:%", 1)) }
            enqueue(lane = "tenant-a", resource = "customer:a")
            poll { claims -> claims.oldest(1).size }
            enqueue(lane = "tenant-a", resource = "customer:a")
            enqueue(lane = "tenant-b", resource = "customer:b")

            val lanes = poll { claims -> claims.dueGroups().map { it.lane } }

            assertThat(lanes).containsExactly("tenant-b")
        }

    @Test
    fun `fromGroup - groups claimed one after another in one poll - the tally carries over between them`() =
        runBlocking<Unit> {
            // Arrange: one slot on the shared resource, two lanes that both want it
            store.transaction { store.resourceLimits!!.setLimit(it, ResourceLimit("mail-server", 1)) }
            enqueue(lane = "tenant-a", resource = "mail-server")
            enqueue(lane = "tenant-b", resource = "mail-server")

            val claimed =
                poll { claims ->
                    claims.fromGroup(ClaimGroup(0, "mail", "tenant-a"), 1).size +
                        claims.fromGroup(ClaimGroup(0, "mail", "tenant-b"), 1).size
                }

            assertThat(claimed).isEqualTo(1)
        }

    private suspend fun enqueue(
        lane: String? = null,
        resource: String,
    ) {
        val task =
            NewTask<Long>(
                null,
                "mail",
                mapOf("payload" to "{}"),
                now,
                TaskStatus.PENDING,
                now,
                lane = lane,
                resource = resource,
            )
        store.transaction { store.insert(it, task).id }
    }

    /** One poll as the worker runs it: lock, tally, then [use] the claims. */
    private suspend fun <T> poll(use: (Claims<Long>) -> T): T =
        store.transaction { tx: StoreTransaction ->
            val limits = store.resourceLimits!!
            limits.lockClaims(tx)
            val tally = ResourceTally(store.runningPerResource(tx, now), limits.limits(tx))
            use(PollClaims(store, tx, setOf("mail"), "w1", now, now.plusSeconds(60), tally))
        }

    companion object {
        @JvmField
        @RegisterExtension
        val db = PostgresDatabase()
    }
}
