package io.github.d4niella.pendenz.examples.helpdesk.agent

import io.github.d4niella.pendenz.examples.helpdesk.Ticket

/**
 * Reads a ticket, lets the model decide what to do, and does it through
 * the helpdesk's MCP tools. That is all an agent is here: a loop between
 * a model and tools. What the tools may do, who approves, what is
 * retried and what is refused is the helpdesk's business, enforced by
 * the queue behind the tools.
 */
class TicketAgent(
    private val model: ChatModel,
    private val helpdesk: HelpdeskConnection,
    private val maxSteps: Int = DEFAULT_STEPS,
) {
    private val tools: List<ToolDefinition> by lazy { helpdesk.tools() }

    fun triage(ticket: Ticket): Triage {
        val messages = mutableListOf(ChatMessage.system(INSTRUCTIONS), ChatMessage.user(describe(ticket)))
        val outcomes = mutableListOf<ToolOutcome>()
        repeat(maxSteps) {
            val reply = model.complete(messages, tools)
            messages += reply
            val calls = reply.toolCalls.orEmpty()
            if (calls.isEmpty()) return Triage(ticket, outcomes, reply.content.orEmpty().trim())
            calls.forEach { call ->
                val outcome = helpdesk.call(call.function.name, call.function.arguments)
                outcomes += outcome
                messages += ChatMessage.tool(call.id, outcome.answer)
            }
        }
        return Triage(ticket, outcomes, "stopped after $maxSteps steps without a conclusion")
    }

    private fun describe(ticket: Ticket): String =
        """
        Ticket #${ticket.id} from ${ticket.customer}, priority ${ticket.priority}
        Subject: ${ticket.subject}

        ${ticket.body}
        """.trimIndent()

    companion object {
        private const val DEFAULT_STEPS = 6

        val INSTRUCTIONS =
            """
            You triage support tickets of a web hosting company. For each ticket decide what the helpdesk
            should do and do it with the tools:
            - Answer questions you can answer from the ticket alone with reply-to-ticket.
            - Escalate outages to NETWORK, money matters to BILLING, breaches to SECURITY with escalate-ticket,
              and set the priority of an outage to URGENT with set-ticket-priority.
            - Leave spam alone: no tool at all.
            Pass "idempotencyKey": "ticket-<id>-<tool>" and "correlationId": "ticket-<id>" with every call, so a
            repeated run changes nothing. A task that waits for approval is fine; do not try again.
            End with one sentence that says what you did.
            """.trimIndent()
    }
}

/** What the agent did about one ticket. */
data class Triage(
    val ticket: Ticket,
    val outcomes: List<ToolOutcome>,
    val conclusion: String,
)
