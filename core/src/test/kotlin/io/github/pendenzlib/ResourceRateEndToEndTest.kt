package io.github.pendenzlib

import io.github.pendenzlib.schema.PostgresDdl
import io.github.pendenzlib.schema.ResourceLimitTable
import io.github.pendenzlib.schema.ResourceRateTable
import io.github.pendenzlib.schema.ResourceStartTable
import io.github.pendenzlib.schema.TaskTable
import io.github.pendenzlib.testing.MutableClock
import io.github.pendenzlib.testing.PostgresDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.ThrowableAssert.ThrowingCallable
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** Rate limits against PostgreSQL (ADR 0040): exact across workers, set with SQL, per partner, cleaned up. */
class ResourceRateEndToEndTest {
    @Serializable
    data class Call(
        val partner: String,
    )

    private val callPartner =
        TaskType.json<Call>("call-partner", retryPolicy = NoRetry, resource = TaskResource.fromPayload({ it.partner }))

    private lateinit var table: TaskTable<Long>
    private lateinit var limitTable: ResourceLimitTable
    private lateinit var rateTable: ResourceRateTable
    private lateinit var startTable: ResourceStartTable
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val workers = mutableListOf<Job>()

    @BeforeEach
    fun createTables() {
        val suffix = db.uniqueSuffix()
        table = TaskTable(name = "task_$suffix")
        limitTable = ResourceLimitTable(name = "limit_$suffix")
        rateTable = ResourceRateTable(name = "rate_$suffix")
        startTable = ResourceStartTable(name = "start_$suffix")
        db.sql(PostgresDdl.createTable(table, callPartner))
        db.sql(PostgresDdl.createResourceLimitTable(limitTable))
        db.sql(PostgresDdl.createResourceRateTable(rateTable))
        db.sql(PostgresDdl.createResourceStartTable(startTable))
    }

    @AfterEach
    fun stopWorkers() =
        runBlocking<Unit> {
            workers.forEach { it.cancelAndJoin() }
        }

    @Nested
    inner class AcrossWorkers {
        @Test
        fun `worker - two workers, ten tasks, rate of three per hour - three run, seven wait, three starts written`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz()
                pendenz.setResourceRate("partner-api", 3, Duration.ofHours(1))
                repeat(10) { pendenz.enqueue(callPartner, Call("partner-api")) }

                // Act
                startWorker(pendenz)
                startWorker(pendenz)

                // Assert
                await atMost Duration.ofSeconds(10) untilAsserted { assertThat(count("SUCCEEDED")).isEqualTo(3) }
                delay(500) // shows that no fourth task starts: 500 ms are ten polls of each worker
                assertThat(count("SUCCEEDED")).isEqualTo(3)
                assertThat(count("PENDING")).isEqualTo(7)
                assertThat(db.count("""SELECT count(*) FROM "${startTable.name}"""")).isEqualTo(3)
            }

        @Test
        fun `worker - rate per partner inserted with SQL - each partner runs its own two`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz()
                db.sql("""INSERT INTO "${rateTable.name}" (name, max_starts, per) VALUES ('partner:%', 2, '1 hour')""")
                listOf("partner:acme", "partner:globex").forEach { partner ->
                    repeat(4) { pendenz.enqueue(callPartner, Call(partner)) }
                }

                // Act
                startWorker(pendenz)

                // Assert
                await atMost Duration.ofSeconds(10) untilAsserted {
                    assertThat(succeededOf("partner:acme")).isEqualTo(2)
                    assertThat(succeededOf("partner:globex")).isEqualTo(2)
                }
                delay(500) // shows that no third task of a partner starts: 500 ms are ten polls
                assertThat(count("SUCCEEDED")).isEqualTo(4)
            }
    }

    @Nested
    inner class Operating {
        @Test
        fun `setResourceRate - window of ninety seconds - stored as an interval and listed as the same duration`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz()

                pendenz.setResourceRate("partner-api", 100, Duration.ofSeconds(90))

                // Assert
                val listed = pendenz.resourceRates()
                assertThat(listed).containsExactly(ResourceRate("partner-api", 100, Duration.ofSeconds(90)))
                assertThat(db.string("""SELECT per::text FROM "${rateTable.name}"""")).isEqualTo("00:01:30")
            }

        @Test
        fun `maintenance - starts older than the longest window - deleted, newer ones kept`() =
            runBlocking<Unit> {
                // Arrange
                val clock = MutableClock(Instant.parse("2026-11-08T09:00:00Z"))
                val pendenz = pendenz(clock)
                pendenz.setResourceRate("partner-api", 10, Duration.ofMinutes(1))
                pendenz.setResourceRate("mail-server", 10, Duration.ofMinutes(10))
                listOf("08:45:00", "08:55:00", "08:59:30").forEach { time ->
                    db.sql(
                        """INSERT INTO "${startTable.name}" (resource, started_at)
                           VALUES ('partner-api', '2026-11-08T${time}Z')""",
                    )
                }

                pendenz.runMaintenance()

                // Assert
                assertThat(db.count("""SELECT count(*) FROM "${startTable.name}"""")).isEqualTo(2)
            }
    }

    @Nested
    inner class Configuration {
        @Test
        fun `build - enableResourceRates() without enableResourceLimits() - throws IllegalArgumentException`() {
            val attempt =
                ThrowingCallable {
                    Pendenz.create(table) {
                        dataSource(db.dataSource)
                        enableResourceRates(rateTable, startTable)
                    }
                }

            assertThatThrownBy(attempt)
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("enableResourceLimits")
        }

        @Test
        fun `tables - rates enabled - reports the rate table and the start table`() {
            val tables = pendenz().tables!!

            // Assert
            assertThat(tables.resourceRates).isSameAs(rateTable)
            assertThat(tables.resourceStarts).isSameAs(startTable)
        }
    }

    private fun succeededOf(resource: String): Long =
        db.count("""SELECT count(*) FROM "${table.name}" WHERE status = 'SUCCEEDED' AND resource = '$resource'""")

    private fun count(status: String): Long =
        db.count("""SELECT count(*) FROM "${table.name}" WHERE status = '$status'""")

    private fun pendenz(clock: Clock = Clock.systemUTC()): Pendenz<Long> =
        Pendenz.create(table) {
            dataSource(db.dataSource)
            clock(clock)
            enableResourceLimits(limitTable)
            enableResourceRates(rateTable, startTable)
            register(callPartner) { _, _ -> }
        }

    private fun startWorker(pendenz: Pendenz<Long>) {
        workers += pendenz.startWorker(scope, WorkerOptions(parallelism = 4, pollInterval = Duration.ofMillis(50)))
    }

    companion object {
        @JvmField
        @RegisterExtension
        val db = PostgresDatabase()
    }
}
