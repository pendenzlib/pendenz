package io.github.pendenzlib.examples.helpdesk

import io.github.pendenzlib.Approval
import io.github.pendenzlib.NoRetry
import io.github.pendenzlib.Pendenz
import io.github.pendenzlib.TaskResult
import io.github.pendenzlib.TaskType
import io.github.pendenzlib.Verdict
import io.github.pendenzlib.mcp.JsonSchema
import io.github.pendenzlib.mcp.PendenzMcp
import io.github.pendenzlib.schema.IdType
import io.github.pendenzlib.schema.TaskTable
import kotlinx.serialization.Serializable
import javax.sql.DataSource

/**
 * The helpdesk's domain: tickets in a table of their own, and the three
 * things an agent may ask the helpdesk to do with them. Each is a task
 * type; the helpdesk decides what they do, the agent only picks one and
 * fills in the payload.
 */
object Helpdesk {
    /** The identity the agent acts as; every task it creates carries it in `created_by`. */
    const val AGENT = "ticket-agent"

    val TABLE = TaskTable(name = "helpdesk_task")

    @Serializable
    data class Reply(
        val ticketId: Long,
        val text: String,
    )

    @Serializable
    data class Escalation(
        val ticketId: Long,
        val team: Team,
        val reason: String,
    )

    @Serializable
    data class Prioritisation(
        val ticketId: Long,
        val priority: Priority,
    )

    /** What reply-to-ticket produces: the draft that is about to go out, checked before it does. */
    @Serializable
    data class Draft(
        val characters: Int,
    )

    @Serializable
    enum class Team { NETWORK, BILLING, SECURITY }

    @Serializable
    enum class Priority { LOW, NORMAL, HIGH, URGENT }

    private const val PREVIEW_LENGTH = 60

    val REPLY =
        TaskType.json<Reply>(
            "reply-to-ticket",
            retryPolicy = NoRetry,
            preview = { "Reply to ticket #${it.ticketId}: ${it.text.take(PREVIEW_LENGTH)}" },
        )

    /** Whatever an agent escalates waits for a person: a second level's time is not the agent's to spend. */
    val ESCALATE =
        TaskType.json<Escalation>(
            "escalate-ticket",
            retryPolicy = NoRetry,
            approval = Approval.rule { createdBy, _ -> createdBy == AGENT },
            preview = { "Escalate ticket #${it.ticketId} to ${it.team}: ${it.reason}" },
        )

    val PRIORITISE = TaskType.json<Prioritisation>("set-ticket-priority", retryPolicy = NoRetry)

    /**
     * Sends a reply that passed the check. Not a tool: only reply-to-ticket
     * enqueues it, as a follow-up, which the worker writes only when the
     * draft was accepted (ADR 0019, 0020). Validation looks at a result
     * after the handler ran, so the handler must not have sent anything yet.
     */
    val SEND_REPLY = TaskType.json<Reply>("send-reply")

    /**
     * A reply that mentions a refund is refused: refunds are a person's
     * decision. A keyword is a crude check and an example of validation,
     * not a guarantee; a real helpdesk would route money matters to a
     * person with an approval rule.
     */
    private val NO_PROMISES =
        TaskResult.json<Reply, Draft> { reply, _ ->
            if (Regex("\\brefund", RegexOption.IGNORE_CASE).containsMatchIn(reply.text)) {
                Verdict.reject("replies may not promise refunds")
            } else {
                Verdict.accept()
            }
        }

    /** The queue with the helpdesk's handlers; they write to the ticket tables through [desk]. */
    fun pendenz(
        dataSource: DataSource,
        desk: TicketDesk = TicketDesk(dataSource),
    ): Pendenz<Long> =
        Pendenz.create(TABLE) {
            dataSource(dataSource)
            register(REPLY, NO_PROMISES) { ctx, reply ->
                // An agent can be talked into anything by a ticket's text; the helpdesk replies to open tickets only
                check(desk.find(reply.ticketId)?.status == "OPEN") { "ticket #${reply.ticketId} is not open" }
                ctx.provenance("rules", "helpdesk-2026-10")
                ctx.enqueue(SEND_REPLY, reply)
                Draft(reply.text.length)
            }
            register(SEND_REPLY) { _, reply -> desk.reply(reply.ticketId, reply.text) }
            register(ESCALATE) { _, escalation -> desk.escalate(escalation.ticketId, escalation.team) }
            register(PRIORITISE) { _, p -> desk.prioritise(p.ticketId, p.priority) }
        }

    /** What the agent sees: the three task types as tools, acting as [AGENT]. */
    fun mcp(pendenz: Pendenz<Long>): PendenzMcp<Long> =
        PendenzMcp.create(pendenz, IdType.BIGINT, AGENT) {
            serverName("helpdesk")
            tool(
                REPLY,
                "Sends a reply to the customer of a ticket and marks the ticket answered. " +
                    "For questions you can answer from the ticket alone. Never promise refunds.",
                JsonSchema.of<Reply>(),
            )
            tool(
                ESCALATE,
                "Hands a ticket to a second-level team: NETWORK for outages, BILLING for money, " +
                    "SECURITY for breaches. A person approves every escalation before it happens.",
                JsonSchema.of<Escalation>(),
            )
            tool(PRIORITISE, "Sets the priority of a ticket.", JsonSchema.of<Prioritisation>())
        }
}
