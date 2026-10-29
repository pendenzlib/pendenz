package io.github.pendenzlib.examples.helpdesk

import io.github.pendenzlib.examples.helpdesk.Helpdesk.Priority
import io.github.pendenzlib.examples.helpdesk.Helpdesk.Team
import javax.sql.DataSource

/** A ticket as the helpdesk stores it. */
data class Ticket(
    val id: Long,
    val customer: String,
    val subject: String,
    val body: String,
    val status: String,
    val priority: String,
    val escalatedTo: String?,
)

/**
 * The ticket tables, written by the handlers and read by anyone. Plain
 * JDBC, because the point of the example is the queue, not the data access.
 */
class TicketDesk(
    private val dataSource: DataSource,
) {
    fun reply(
        ticketId: Long,
        text: String,
    ) = dataSource.connection.use { c ->
        c.autoCommit = false
        c.prepareStatement("INSERT INTO ticket_reply (ticket_id, text) VALUES (?, ?)").use {
            it.setLong(1, ticketId)
            it.setString(2, text)
            it.executeUpdate()
        }
        c.prepareStatement("UPDATE ticket SET status = 'ANSWERED' WHERE id = ?").use {
            it.setLong(1, ticketId)
            it.executeUpdate()
        }
        c.commit()
    }

    fun escalate(
        ticketId: Long,
        team: Team,
    ) = update("UPDATE ticket SET status = 'ESCALATED', escalated_to = ? WHERE id = ?", team.name, ticketId)

    fun prioritise(
        ticketId: Long,
        priority: Priority,
    ) = update("UPDATE ticket SET priority = ? WHERE id = ?", priority.name, ticketId)

    /** The tickets nobody has dealt with yet, oldest first. */
    fun open(): List<Ticket> = tickets("WHERE status = 'OPEN'")

    fun find(id: Long): Ticket? = tickets("WHERE id = ?", id).singleOrNull()

    /** What the helpdesk sent to the customer of [ticketId], in order. */
    fun replies(ticketId: Long): List<String> =
        dataSource.connection.use { c ->
            c.prepareStatement("SELECT text FROM ticket_reply WHERE ticket_id = ? ORDER BY id").use {
                it.setLong(1, ticketId)
                it.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() }
            }
        }

    private fun tickets(
        where: String,
        id: Long? = null,
    ): List<Ticket> =
        dataSource.connection.use { c ->
            c
                .prepareStatement(
                    "SELECT id, customer, subject, body, status, priority, escalated_to FROM ticket $where ORDER BY id",
                ).use { s ->
                    id?.let { s.setLong(1, it) }
                    s.executeQuery().use { rs ->
                        generateSequence {
                            if (!rs.next()) {
                                null
                            } else {
                                Ticket(
                                    rs.getLong("id"),
                                    rs.getString("customer"),
                                    rs.getString("subject"),
                                    rs.getString("body"),
                                    rs.getString("status"),
                                    rs.getString("priority"),
                                    rs.getString("escalated_to"),
                                )
                            }
                        }.toList()
                    }
                }
        }

    private fun update(
        sql: String,
        value: String,
        ticketId: Long,
    ) = dataSource.connection.use { c ->
        c.prepareStatement(sql).use {
            it.setString(1, value)
            it.setLong(2, ticketId)
            it.executeUpdate()
        }
    }
}
