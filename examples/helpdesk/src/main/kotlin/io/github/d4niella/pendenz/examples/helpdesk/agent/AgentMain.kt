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
 * `SELECT helpdesk_task_approve(id, version, 'your name')`.
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
}

/** What one run did: a triage per ticket, and the agent's tasks once the helpdesk had dealt with them. */
data class AgentReport(
    val triages: List<Triage>,
    val tasks: List<String>,
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
        AgentReport(triages, settled(helpdesk, settleWithin))
    }

/** The agent's tasks, once none of them is still due or running; asks the helpdesk through list_tasks. */
private fun settled(
    helpdesk: HelpdeskConnection,
    within: Duration,
): List<String> {
    val deadline = Instant.now().plus(within)
    while (true) {
        val tasks = (Json.parseToJsonElement(helpdesk.call("list_tasks", "{}").answer).jsonObject["tasks"] as JsonArray)
        val lines = tasks.map { it.jsonObject }.map { "#${it.text("id")} ${it.text("type")} ${it.text("status")}" }
        val busy = tasks.any { it.jsonObject.text("status") in BUSY }
        if (!busy || Instant.now().isAfter(deadline)) return lines
        Thread.sleep(POLL_MILLIS)
    }
}

private fun kotlinx.serialization.json.JsonObject.text(key: String): String? = get(key)?.jsonPrimitive?.content

private val BUSY = setOf("PENDING", "SCHEDULED", "RUNNING", "RETRY_PENDING")
private const val SETTLE_SECONDS = 30L
private const val POLL_MILLIS = 200L
