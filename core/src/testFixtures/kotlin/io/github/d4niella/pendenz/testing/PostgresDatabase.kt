package io.github.d4niella.pendenz.testing

import io.github.d4niella.pendenz.TaskType
import io.github.d4niella.pendenz.schema.IdType
import io.github.d4niella.pendenz.schema.PostgresDdl
import io.github.d4niella.pendenz.schema.TaskTable
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.postgresql.ds.PGSimpleDataSource
import org.slf4j.LoggerFactory
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.ResultSet
import java.util.concurrent.atomic.AtomicLong
import javax.sql.DataSource

/**
 * A real PostgreSQL for tests. Uses Testcontainers when Docker is available,
 * otherwise an embedded PostgreSQL binary, so the tests run on every
 * developer machine. Never H2: the claim query depends on `SKIP LOCKED` and
 * the payload column on `jsonb`.
 *
 * One server per JVM, started on first use and stopped when the JVM exits.
 * Every test still gets its own table through [createTable], so tests stay
 * independent and may run in parallel.
 */
class PostgresDatabase : BeforeAllCallback {
    val dataSource: DataSource
        get() = Server.dataSource

    /** How to reach the server from another process. */
    val jdbcUrl: String get() = Server.access.jdbcUrl
    val username: String get() = Server.access.username
    val password: String get() = Server.access.password

    override fun beforeAll(context: ExtensionContext) {
        Server.dataSource
    }

    /** Creates a fresh task table with `bigint` ids and a unique name and returns its mapping. */
    @JvmOverloads
    fun createTable(prefix: String = "task"): TaskTable<Long> = createTable(IdType.BIGINT, prefix)

    /** Creates a fresh task table with `bigint` ids and the payload columns of [types]. */
    fun createTable(
        prefix: String,
        vararg types: TaskType<*>,
    ): TaskTable<Long> {
        val table = TaskTable(name = uniqueName(prefix))
        sql(PostgresDdl.createTable(table, *types))
        return table
    }

    /** [prefix] and a [uniqueSuffix], for tables, schemas and other objects a test creates. */
    fun uniqueName(prefix: String): String = "${prefix}_${uniqueSuffix()}"

    /**
     * Unique among all tests of this JVM, even in parallel: a mark of the
     * run, so names from an earlier run on the same server do not meet,
     * and a counter.
     */
    fun uniqueSuffix(): String = "${RUN}_${counter.incrementAndGet()}"

    /** Creates a fresh task table with the given id type and a unique name and returns its mapping. */
    @JvmOverloads
    fun <ID : Any> createTable(
        idType: IdType<ID>,
        prefix: String = "task",
    ): TaskTable<ID> {
        val table = TaskTable(idType, name = uniqueName(prefix))
        dataSource.connection.use { connection ->
            connection.createStatement().use { it.execute(PostgresDdl.createTable(table)) }
        }
        return table
    }

    /** Runs a statement that changes rows and returns how many it changed. */
    fun sql(statement: String): Int =
        dataSource.connection.use { connection ->
            connection.createStatement().use { it.executeUpdate(statement) }
        }

    /** Runs a query that counts, such as `SELECT count(*) ...`, and returns the count. */
    fun count(query: String): Long = long(query)

    /** Runs a query whose single row and column is a whole number, such as an id or a function's result. */
    fun long(query: String): Long = single(query) { it.getLong(1) }

    /** Runs a query whose single row and column is text, or null. */
    fun string(query: String): String? = single(query) { it.getString(1) }

    private fun <T> single(
        query: String,
        read: (ResultSet) -> T,
    ): T =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(query).use { rs ->
                    check(rs.next()) { "query returned no row: $query" }
                    read(rs)
                }
            }
        }

    /** URL and credentials of the test server. */
    class Access(
        val jdbcUrl: String,
        val username: String,
        val password: String,
    ) {
        /** A plain driver data source for these credentials, one connection per call. */
        fun dataSource(): DataSource =
            PGSimpleDataSource().apply {
                setUrl(jdbcUrl)
                user = username
                password = this@Access.password
            }
    }

    private object Server {
        private val log = LoggerFactory.getLogger(PostgresDatabase::class.java)

        val access: Access by lazy { start() }

        val dataSource: DataSource by lazy { access.dataSource() }

        private fun start(): Access =
            if (dockerAvailable()) {
                log.info("using PostgreSQL via Testcontainers")
                // The same major as the embedded binaries in the version catalog (ADR 0004)
                val pg = PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine")).also { it.start() }
                stopOnExit { pg.stop() }
                Access(pg.jdbcUrl, pg.username, pg.password)
            } else {
                log.info("Docker unavailable, using embedded PostgreSQL")
                val pg = EmbeddedPostgres.start()
                stopOnExit { pg.close() }
                Access(pg.getJdbcUrl("postgres", "postgres"), "postgres", "postgres")
            }

        private fun stopOnExit(stop: () -> Unit) {
            Runtime.getRuntime().addShutdownHook(Thread(stop))
        }

        private fun dockerAvailable(): Boolean =
            runCatching {
                DockerClientFactory.instance().isDockerAvailable
            }.getOrDefault(false)
    }
}

private val RUN = System.nanoTime().toString(36)
private val counter = AtomicLong()
