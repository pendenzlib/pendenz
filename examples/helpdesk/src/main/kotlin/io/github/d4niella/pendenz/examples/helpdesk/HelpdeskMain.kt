package io.github.d4niella.pendenz.examples.helpdesk

import io.github.d4niella.pendenz.WorkerOptions
import io.github.d4niella.pendenz.examples.support.DatabaseAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.plus
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.io.FilterInputStream
import java.io.InputStream
import java.time.Duration

private val log = LoggerFactory.getLogger("helpdesk")
private const val POLL_MILLIS = 100L

/**
 * The helpdesk service: migrates its tables, runs a worker and serves MCP
 * on standard input and output, as an agent host expects of a server it
 * starts. It runs until the host closes standard input or ends the
 * process; either way the worker stops gracefully (ADR 0005). Logs go to
 * standard error, since standard output belongs to MCP.
 */
fun main() {
    val access = checkNotNull(DatabaseAccess.fromEnvironment()) { "${DatabaseAccess.URL} must name the database" }
    access.dataSource().use { dataSource ->
        access.migrate(dataSource, MIGRATIONS)
        runBlocking<Unit> {
            val service = coroutineContext.job
            Runtime.getRuntime().addShutdownHook(Thread { runBlocking { service.cancelAndJoin() } })
            val pendenz = Helpdesk.pendenz(dataSource)
            // Handlers write with blocking JDBC, so the worker runs on the IO dispatcher
            pendenz.startWorker(this + Dispatchers.IO, WorkerOptions(pollInterval = Duration.ofMillis(POLL_MILLIS)))
            Helpdesk.mcp(pendenz).serveStdio(UntilEnd(System.`in`) { service.cancel() }, System.out)
            log.info("helpdesk serves MCP on standard input and output")
            awaitCancellation()
        }
    }
}

/** Standard input that reports its end, when the agent host closes the stream. */
private class UntilEnd(
    input: InputStream,
    private val ended: () -> Unit,
) : FilterInputStream(input) {
    override fun read(): Int = super.read().also { if (it < 0) ended() }

    override fun read(
        b: ByteArray,
        off: Int,
        len: Int,
    ): Int = super.read(b, off, len).also { if (it < 0) ended() }
}

/** The helpdesk's tables and this morning's tickets. */
private const val MIGRATIONS = "classpath:io/github/d4niella/pendenz/examples/helpdesk/db/migration"
