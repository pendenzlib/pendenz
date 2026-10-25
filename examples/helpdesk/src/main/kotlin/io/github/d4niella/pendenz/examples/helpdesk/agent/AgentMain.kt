package io.github.d4niella.pendenz.examples.helpdesk.agent

import io.github.d4niella.pendenz.examples.helpdesk.DatabaseAccess
import io.github.d4niella.pendenz.examples.helpdesk.TicketDesk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant

private val log = LoggerFactory.getLogger("agent")

/**
 * The ticket agent: starts the helpdesk, reads this morning's open tickets
 * with SQL, triages each one through MCP and reports what became of its
 * tasks. Escalations stay waiting for a person; approve one with
 * `SELECT helpdesk_task_approve(id, version, 'your name')`. That needs a
 * database that outlives the run, named by `PENDENZ_JDBC_URL`, and the
 * approved escalation runs when the helpdesk next runs a worker.
 *
 * The model is a local Ollama by default; `AGENT_MODEL_URL`, `AGENT_MODEL`
 * and `AGENT_API_KEY` point it elsewhere, `AGENT_MODEL_TIMEOUT` gives a
 * slow model more time per answer. The database is embedded unless
 * `PENDENZ_JDBC_URL` names one.
 */
fun main() {
    val database = DatabaseAccess.fromEnvironment() ?: DatabaseAccess.embedded()
    val report = runAgent(database, OpenAiCompatibleModel.fromEnvironment())
    report.triages.forEach { triage ->
        log.info("ticket #{} {}: {}", triage.ticket.id, triage.ticket.subject, triage.conclusion)
        triage.outcomes.forEach { log.info("    {} -> {}", it.tool, it.answer) }
    }
    log.info("what became of the agent's tasks:")
    report.tasks.forEach { log.info("    {}", it) }
    if (!report.settled) log.warn("some tasks were still due or running when the agent stopped waiting")
}

/**
 * What one run did: a triage per ticket, and the agent's tasks once the helpdesk had dealt with them;
 * [settled] is false when some were still due or running at the deadline.
 */
data class AgentReport(
    val triages: List<Triage>,
    val tasks: List<String>,
    val settled: Boolean,
)

/** Starts the helpdesk on [database], triages its open tickets with [model], waits until its tasks settled. */
fun runAgent(
    database: DatabaseAccess,
    model: ChatModel,
    settleWithin: Duration = Duration.ofSeconds(SETTLE_SECONDS),
): AgentReport =
    HelpdeskConnection.start(database).use { helpdesk ->
        val tickets = database.dataSource().use { TicketDesk(it).open() }
        log.info("{} open ticket(s) to triage", tickets.size)
        val agent = TicketAgent(model, helpdesk)
        val triages = tickets.map { agent.triage(it) }
        val (tasks, settled) = settled(helpdesk, settleWithin)
        AgentReport(triages, tasks, settled)
    }

/**
 * The agent's tasks, up to a hundred, once none of them is still due or running, and whether that
 * happened before [within] ran out; asks the helpdesk through list_tasks.
 */
private fun settled(
    helpdesk: HelpdeskConnection,
    within: Duration,
): Pair<List<String>, Boolean> {
    val deadline = Instant.now().plus(within)
    while (true) {
        val answer = helpdesk.call("list_tasks", """{"limit": $MAX_TASKS}""")
        check(!answer.isError) { "the helpdesk could not list the agent's tasks: ${answer.answer}" }
        val tasks = Json.parseToJsonElement(answer.answer).jsonObject["tasks"] as JsonArray
        val lines = tasks.map { it.jsonObject }.map { "#${it.text("id")} ${it.text("type")} ${it.text("status")}" }
        val busy = tasks.any { it.jsonObject.text("status") in BUSY }
        if (!busy) return lines to true
        if (Instant.now().isAfter(deadline)) return lines to false
        Thread.sleep(POLL_MILLIS)
    }
}

private fun kotlinx.serialization.json.JsonObject.text(key: String): String? = get(key)?.jsonPrimitive?.content

private val BUSY = setOf("PENDING", "SCHEDULED", "RUNNING", "RETRY_PENDING")
private const val SETTLE_SECONDS = 30L
private const val POLL_MILLIS = 200L
private const val MAX_TASKS = 100
