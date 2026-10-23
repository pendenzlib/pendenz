package io.github.d4niella.pendenz.examples.helpdesk

import io.github.d4niella.pendenz.WorkerOptions
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.time.Duration

private val log = LoggerFactory.getLogger("helpdesk")
private const val POLL_MILLIS = 100L

/**
 * The helpdesk service: migrates its tables, runs a worker and serves MCP
 * on standard input and output, as an agent host expects of a server it
 * starts. It runs until the host ends the process. Logs go to standard
 * error, since standard output belongs to MCP.
 */
fun main() =
    runBlocking<Unit> {
        val access = checkNotNull(DatabaseAccess.fromEnvironment()) { "${DatabaseAccess.URL} must name the database" }
        val dataSource = access.dataSource()
        access.migrate(dataSource)
        val pendenz = Helpdesk.pendenz(dataSource)
        pendenz.startWorker(this, WorkerOptions(pollInterval = Duration.ofMillis(POLL_MILLIS)))
        Helpdesk.mcp(pendenz).serveStdio()
        log.info("helpdesk serves MCP on standard input and output")
        awaitCancellation()
    }
