package io.github.d4niella.pendenz.examples.helpdesk

import com.zaxxer.hikari.HikariDataSource
import io.github.d4niella.pendenz.WorkerOptions
import io.github.d4niella.pendenz.examples.helpdesk.agent.AgentReport
import io.github.d4niella.pendenz.examples.helpdesk.agent.ChatMessage
import io.github.d4niella.pendenz.examples.helpdesk.agent.ChatModel
import io.github.d4niella.pendenz.examples.helpdesk.agent.FunctionCall
import io.github.d4niella.pendenz.examples.helpdesk.agent.OpenAiCompatibleModel
import io.github.d4niella.pendenz.examples.helpdesk.agent.ToolCall
import io.github.d4niella.pendenz.examples.helpdesk.agent.runAgent
import io.github.d4niella.pendenz.examples.helpdesk.testing.ReplayedModel
import io.github.d4niella.pendenz.testing.PostgresDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.time.Duration

/**
 * The ticket agent end to end: it starts the helpdesk as a child process,
 * reads the open tickets with SQL and acts through MCP over the process's
 * standard streams, with a model that replays prepared answers. Each test
 * gets a schema of its own, so the helpdesk's migrations run fresh.
 */
class TicketAgentEndToEndTest {
    private lateinit var database: DatabaseAccess
    private lateinit var dataSource: HikariDataSource
    private val closeables = mutableListOf<AutoCloseable>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @BeforeEach
    fun createSchema() {
        val schema = "helpdesk_${System.nanoTime().toString(36)}"
        db.sql("""CREATE SCHEMA "$schema"""")
        database = DatabaseAccess(db.jdbcUrl, db.username, db.password, schema)
        dataSource = database.dataSource().also { closeables += it }
    }

    @AfterEach
    fun close() {
        // The worker must have stopped before its data source closes under it
        runBlocking { scope.coroutineContext.job.cancelAndJoin() }
        closeables.reversed().forEach { it.close() }
    }

    @Nested
    inner class Escalation {
        @Test
        fun `agent - outage ticket - escalation AWAITING_APPROVAL, created by the agent, with its preview`() {
            // Act
            run()

            // Assert
            val escalation = task("escalate-ticket", ticket = 1)
            assertThat(escalation.status).isEqualTo("AWAITING_APPROVAL")
            assertThat(escalation.createdBy).isEqualTo(Helpdesk.AGENT)
            assertThat(escalation.preview).isEqualTo("Escalate ticket #1 to NETWORK: all sites answer 502 since 06:10")
            assertThat(desk().find(1)!!.status).isEqualTo("OPEN")
        }

        @Test
        fun `agent - outage ticket - the helpdesk's worker sets it URGENT without anyone's approval`() {
            // Act
            run()

            // Assert
            assertThat(desk().find(1)!!.priority).isEqualTo("URGENT")
        }

        @Test
        fun `approve - a person approves the agent's escalation with SQL - the worker escalates the ticket`() {
            // Arrange
            run()
            Helpdesk
                .pendenz(
                    dataSource,
                ).startWorker(scope, WorkerOptions(pollInterval = Duration.ofMillis(POLL_MILLIS)))
            val id = task("escalate-ticket", ticket = 1).id

            // Act
            val approved = approveAs("ada", id)

            // Assert
            assertThat(approved).isTrue()
            await atMost Duration.ofSeconds(10) untilAsserted {
                val ticket = desk().find(1)!!
                assertThat(ticket.status).isEqualTo("ESCALATED")
                assertThat(ticket.escalatedTo).isEqualTo("NETWORK")
            }
        }
    }

    @Nested
    inner class Replies {
        @Test
        fun `agent - password question - the reply reaches the customer and the ticket is ANSWERED`() {
            // Act
            run()

            // Assert
            assertThat(
                desk().replies(2),
            ).containsExactly("You can reset your password under Account > Security > Reset password.")
            assertThat(desk().find(2)!!.status).isEqualTo("ANSWERED")
        }

        @Test
        fun `agent - reply that promises a refund - REJECTED with the reason, nothing sent, ticket still OPEN`() {
            // Act
            run()

            // Assert
            val reply = task("reply-to-ticket", ticket = 4)
            assertThat(reply.status).isEqualTo("REJECTED")
            assertThat(reply.lastError).isEqualTo("replies may not promise refunds")
            assertThat(desk().find(4)!!.status).isEqualTo("OPEN")
        }

        @Test
        fun `agent - spam - no task for the ticket`() {
            // Act
            run()

            // Assert
            assertThat(db.count("""SELECT count(*) FROM ${table()} WHERE correlation_id = 'ticket-3'""")).isZero()
        }
    }

    @Nested
    inner class Boundaries {
        @Test
        fun `agent - ticket text talks the model into replying to another ticket - refused, that ticket untouched`() {
            // Arrange: whatever the ticket says, this model replies to ticket 4
            val misled =
                scripted("reply-to-ticket", """{"payload": {"ticketId": 4, "text": "Your refund is on its way."}}""")

            // Act
            val report = runAgent(database, misled, Duration.ofSeconds(1))

            // Assert
            val refusals = report.triages.flatMap { it.outcomes }.filter { it.isError }
            assertThat(refusals).isNotEmpty().allSatisfy { assertThat(it.answer).contains("not #4") }
            assertThat(db.count("""SELECT count(*) FROM ${table()} WHERE task_type = 'reply-to-ticket'""")).isEqualTo(
                1,
            )
        }

        @Test
        fun `agent - model calls a tool the helpdesk does not have - an error it can read, the run goes on`() {
            // Arrange
            val inventive = scripted("delete-ticket", """{"ticketId": 1}""")

            // Act
            val report = runAgent(database, inventive, Duration.ofSeconds(1))

            // Assert
            assertThat(report.triages).hasSizeGreaterThan(1)
            assertThat(report.triages.flatMap { it.outcomes }).allSatisfy { assertThat(it.isError).isTrue() }
        }

        /** A model that asks for one tool call per ticket, then says it is done. */
        private fun scripted(
            tool: String,
            arguments: String,
        ) = ChatModel { messages, _ ->
            if (messages.last().role == "user") {
                ChatMessage(
                    "assistant",
                    toolCalls = listOf(ToolCall("call-1", function = FunctionCall(tool, arguments))),
                )
            } else {
                ChatMessage("assistant", "Done.")
            }
        }
    }

    @Nested
    inner class Conversation {
        @Test
        fun `agent - after a tool call - the model reads the tool's answer, AWAITING_APPROVAL included`() {
            // Arrange
            val model = ReplayedModel("/replay/morning-tickets.json").also { closeables += it }

            // Act
            runAgent(database, OpenAiCompatibleModel(model.baseUrl, "qwen3:8b"))

            // Assert
            val answers =
                model
                    .messages(
                        1,
                    ).map { it.jsonObject }
                    .filter { it["role"]?.jsonPrimitive?.content == "tool" }
            assertThat(answers.map { it["tool_call_id"]!!.jsonPrimitive.content }).containsExactly("call_1", "call_2")
            assertThat(answers.first()["content"]!!.jsonPrimitive.content).contains("AWAITING_APPROVAL")
        }

        @Test
        fun `agent - after a tool call - sends the call back with its type, which LM Studio insists on`() {
            // Arrange
            val model = ReplayedModel("/replay/morning-tickets.json").also { closeables += it }

            // Act
            runAgent(database, OpenAiCompatibleModel(model.baseUrl, "qwen3:8b"))

            // Assert
            val call =
                model
                    .messages(1)
                    .map { it.jsonObject }
                    .first { it["role"]?.jsonPrimitive?.content == "assistant" }["tool_calls"]!!
                    .jsonArray
                    .first()
                    .jsonObject
            assertThat(call["type"]?.jsonPrimitive?.content).isEqualTo("function")
        }

        @Test
        fun `agent - any request to the model - offers no upgrade to HTTP 2, which LM Studio never answers`() {
            // Arrange
            val model = ReplayedModel("/replay/morning-tickets.json").also { closeables += it }

            // Act
            runAgent(database, OpenAiCompatibleModel(model.baseUrl, "qwen3:8b"))

            // Assert
            assertThat(model.requests).isNotEmpty()
            assertThat(model.upgradeOffers).isEmpty()
        }

        @Test
        fun `agent - second run over the tickets still open - idempotency keys keep one task per action`() {
            // Arrange
            run()
            val tasksAfterFirst = db.count("SELECT count(*) FROM ${table()}")

            // Act
            val second = run("/replay/second-run.json")

            // Assert
            assertThat(second.triages.map { it.ticket.id }).containsExactly(1L, 3L, 4L)
            assertThat(db.count("SELECT count(*) FROM ${table()}")).isEqualTo(tasksAfterFirst)
        }
    }

    private fun run(replay: String = "/replay/morning-tickets.json"): AgentReport {
        val model = ReplayedModel(replay).also { closeables += it }
        return runAgent(database, OpenAiCompatibleModel(model.baseUrl, "qwen3:8b"))
    }

    private fun desk() = TicketDesk(dataSource)

    private fun table() = """"${database.schema}".helpdesk_task"""

    private class TaskRow(
        val id: Long,
        val status: String,
        val createdBy: String?,
        val preview: String?,
        val lastError: String?,
    )

    private fun task(
        type: String,
        ticket: Long,
    ): TaskRow =
        dataSource.connection.use { c ->
            val sql =
                "SELECT id, status, created_by, preview, last_error FROM helpdesk_task " +
                    "WHERE task_type = ? AND correlation_id = ?"
            c.prepareStatement(sql).use {
                it.setString(1, type)
                it.setString(2, "ticket-$ticket")
                it.executeQuery().use { rs ->
                    check(rs.next()) { "no $type task for ticket $ticket" }
                    TaskRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5))
                }
            }
        }

    /** What an approval screen does: the generated function, with the version the person saw. */
    private fun approveAs(
        approver: String,
        id: Long,
    ): Boolean =
        dataSource.connection.use { c ->
            c.prepareStatement("SELECT helpdesk_task_approve(id, version, ?) FROM helpdesk_task WHERE id = ?").use {
                it.setString(1, approver)
                it.setLong(2, id)
                it.executeQuery().use { rs -> rs.next() && rs.getBoolean(1) }
            }
        }

    private companion object {
        const val POLL_MILLIS = 50L

        @JvmField
        @RegisterExtension
        val db = PostgresDatabase()
    }
}
