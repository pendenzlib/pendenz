package io.github.pendenzlib.mcp

import io.github.pendenzlib.Action
import io.github.pendenzlib.Approval
import io.github.pendenzlib.NoRetry
import io.github.pendenzlib.Pendenz
import io.github.pendenzlib.Permissions
import io.github.pendenzlib.TaskResult
import io.github.pendenzlib.TaskStatus
import io.github.pendenzlib.TaskType
import io.github.pendenzlib.Verdict
import io.github.pendenzlib.WorkerOptions
import io.github.pendenzlib.mcp.testing.McpHttpClient
import io.github.pendenzlib.schema.IdType
import io.github.pendenzlib.schema.TaskTable
import io.github.pendenzlib.testing.PostgresDatabase
import io.github.pendenzlib.testing.awaitStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.time.Duration

/** An agent's view of the queue through MCP over HTTP, against PostgreSQL and a worker (ADR 0021). */
class McpEndToEndTest {
    @Serializable
    data class Escalation(
        val ticketId: Long,
        val reason: String,
    )

    @Serializable
    data class Reply(
        val ticketId: Long,
        val text: String,
    )

    @Serializable
    data class Answer(
        val sentences: Int,
    )

    private val escalate =
        TaskType.json<Escalation>(
            "escalate-ticket",
            retryPolicy = NoRetry,
            approval = Approval.rule { createdBy, _ -> createdBy == AGENT },
            preview = {
                require(it.reason.isNotBlank()) { "an escalation needs a reason" }
                "Escalate ticket ${it.ticketId}: ${it.reason}"
            },
        )
    private val reply = TaskType.json<Reply>("reply-to-ticket", retryPolicy = NoRetry)
    private val notify = TaskType.json<Reply>("notify-customer", retryPolicy = NoRetry)
    private val counted =
        TaskResult.json<Reply, Answer> { _, answer ->
            if (answer.sentences >
                0
            ) {
                Verdict.accept()
            } else {
                Verdict.reject("empty")
            }
        }

    private lateinit var table: TaskTable<Long>
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val workers = mutableListOf<Job>()
    private val closeables = mutableListOf<AutoCloseable>()

    @BeforeEach
    fun createTable() {
        table = db.createTable("mcp")
    }

    @AfterEach
    fun stop() =
        runBlocking<Unit> {
            closeables.reversed().forEach { it.close() }
            workers.forEach { it.cancelAndJoin() }
        }

    @Nested
    inner class Tools {
        @Test
        fun `tools - two types, one with a preview - an enqueue tool each, its preview, get_task, list_tasks`() {
            // Arrange
            val client = client(pendenz())

            // Act
            val tools = client.tools()

            // Assert
            assertThat(tools.map { it.name() })
                .containsExactlyInAnyOrder(
                    "escalate-ticket",
                    "reply-to-ticket",
                    "preview_escalate-ticket",
                    "get_task",
                    "list_tasks",
                )
            assertThat(tools.filter { it.annotations().readOnlyHint() }.map { it.name() })
                .containsExactlyInAnyOrder("preview_escalate-ticket", "get_task", "list_tasks")
        }

        @Test
        fun `tools - enqueue tool - payload schema nested under payload, beside the queue's own fields`() {
            // Arrange
            val client = client(pendenz())

            // Act
            val schema = client.tools().single { it.name() == "reply-to-ticket" }.inputSchema()

            // Assert
            @Suppress("UNCHECKED_CAST") // the SDK keeps schemas as plain maps
            val properties = schema["properties"] as Map<String, Map<String, Any>>
            assertThat(schema["required"] as List<*>).containsExactly("payload")
            assertThat(properties.keys).containsExactlyInAnyOrder("payload", "runAt", "idempotencyKey", "correlationId")
            assertThat(properties.getValue("payload")["required"] as List<*>).containsExactly("ticketId", "text")
        }

        @Test
        fun `tools - a task type registered but not exposed - has no tool`() {
            // Arrange
            val pendenz = pendenz()
            val mcp =
                PendenzMcp
                    .create(
                        pendenz,
                        IdType.BIGINT,
                        AGENT,
                    ) { tool(reply, "Replies.", JsonSchema.of<Reply>()) }
                    .also {
                        closeables +=
                            it
                    }

            // Act
            val tools = client(mcp).tools()

            // Assert
            assertThat(tools.map { it.name() }).doesNotContain("escalate-ticket", "notify-customer")
        }
    }

    @Nested
    inner class Enqueue {
        @Test
        fun `enqueue tool - valid payload - PENDING task created by the agent, id and status in the answer`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz()
                val client = client(pendenz)

                // Act
                val answer =
                    client.call(
                        "reply-to-ticket",
                        """{"payload": {"ticketId": 4711, "text": "We are on it."}}""",
                    )

                // Assert
                assertThat(answer.isError).describedAs(answer.toString()).isFalse()
                assertThat(answer.text("status")).isEqualTo("PENDING")
                val task = pendenz.find(answer.text("id")!!.toLong())!!
                assertThat(task.createdBy).isEqualTo(AGENT)
                assertThat(task.type).isEqualTo("reply-to-ticket")
            }

        @Test
        fun `enqueue tool - approval rule holds what the agent creates - AWAITING_APPROVAL with preview and a note`() {
            // Arrange
            val client = client(pendenz())

            // Act
            val answer =
                client.call(
                    "escalate-ticket",
                    """{"payload": {"ticketId": 4711, "reason": "printer on fire"}}""",
                )

            // Assert
            assertThat(answer.text("status")).isEqualTo("AWAITING_APPROVAL")
            assertThat(answer.text("preview")).isEqualTo("Escalate ticket 4711: printer on fire")
            assertThat(answer.text("note")).contains("approve")
        }

        @Test
        fun `enqueue tool - same idempotency key twice - the second call returns the first task`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz()
                val client = client(pendenz)
                val payload = """{"ticketId": 4711, "text": "We are on it."}"""
                val call = """{"payload": $payload, "idempotencyKey": "reply-4711"}"""
                val first = client.call("reply-to-ticket", call)

                // Act
                val second = client.call("reply-to-ticket", call)

                // Assert
                assertThat(second.text("id")).isEqualTo(first.text("id"))
                assertThat(pendenz.tasks()).hasSize(1)
            }

        @Test
        fun `enqueue tool - key of a task someone else enqueued - a task of its own, nothing of the other revealed`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz()
                val client = client(pendenz)
                val clerks = pendenz.enqueue(reply, Reply(4711, "Refund of 950 EUR"), idempotencyKey = "reply-4711")
                val payload = """{"ticketId": 4711, "text": "We are on it."}"""
                val call = """{"payload": $payload, "idempotencyKey": "reply-4711"}"""

                // Act
                val answer = client.call("reply-to-ticket", call)

                // Assert
                assertThat(answer.isError).isFalse()
                assertThat(answer.text("id")).isNotEqualTo(clerks.toString())
                assertThat(pendenz.tasks().map { it.createdBy }).containsExactlyInAnyOrder(null, AGENT)
            }

        @Test
        fun `enqueue tool - agent used a key first - the business process still gets a task of its own`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz()
                val client = client(pendenz)
                val payload = """{"ticketId": 4711, "text": "Refund of 1000000 EUR"}"""
                val call = """{"payload": $payload, "idempotencyKey": "reply-4711"}"""
                val agents = client.call("reply-to-ticket", call).text("id")

                // Act
                val business = pendenz.enqueue(reply, Reply(4711, "We are on it."), idempotencyKey = "reply-4711")

                // Assert
                assertThat(business.toString()).isNotEqualTo(agents)
                assertThat(pendenz.find(business)!!.createdBy).isNull()
            }

        @Test
        fun `enqueue tool - run time that is no instant - an error naming runAt, nothing written`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz()
                val client = client(pendenz)

                // Act
                val answer =
                    client.call(
                        "reply-to-ticket",
                        """{"payload": {"ticketId": 1, "text": "Hi"}, "runAt": "tomorrow"}""",
                    )

                // Assert
                assertThat(answer.isError).isTrue()
                assertThat(answer.text("error")).contains("runAt")
                assertThat(pendenz.tasks()).isEmpty()
            }

        @Test
        fun `enqueue tool - idempotency key longer than the column allows - a readable error, nothing written`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz()
                val client = client(pendenz)
                val key = "k".repeat(300)

                // Act
                val answer =
                    client.call(
                        "reply-to-ticket",
                        """{"payload": {"ticketId": 1, "text": "Hi"}, "idempotencyKey": "$key"}""",
                    )

                // Assert
                assertThat(answer.isError).isTrue()
                assertThat(answer.text("error")).doesNotContain("the call failed on the server")
                assertThat(pendenz.tasks()).isEmpty()
            }

        @Test
        fun `enqueue tool - run time ahead - SCHEDULED`() {
            // Arrange
            val client = client(pendenz())

            // Act
            val answer =
                client.call(
                    "reply-to-ticket",
                    """{"payload": {"ticketId": 1, "text": "Later."}, "runAt": "2099-01-01T08:00:00Z"}""",
                )

            // Assert
            assertThat(answer.text("status")).isEqualTo("SCHEDULED")
        }

        @Test
        fun `enqueue tool - payload against its schema - an error, nothing written`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz()
                val client = client(pendenz)

                // Act
                val answer =
                    client.call(
                        "reply-to-ticket",
                        """{"payload": {"ticketId": "not a number", "text": "Hi"}}""",
                    )

                // Assert
                assertThat(answer.isError).isTrue()
                assertThat(pendenz.tasks()).isEmpty()
            }

        @Test
        fun `enqueue tool - payload the schema lets through but the codec refuses - an error naming the type`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz()
                val lax =
                    PendenzMcp.create(
                        pendenz,
                        IdType.BIGINT,
                        AGENT,
                    ) { tool(reply, "Replies.", """{"type": "object"}""") }
                closeables += lax
                val client = client(lax)

                // Act
                val answer = client.call("reply-to-ticket", """{"payload": {"text": "no ticket id"}}""")

                // Assert
                assertThat(answer.isError).isTrue()
                assertThat(answer.text("error")).contains("reply-to-ticket")
                assertThat(pendenz.tasks()).isEmpty()
            }

        @Test
        fun `enqueue tool - payload the type's preview refuses - its reason as the error, nothing written`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz()
                val client = client(pendenz)

                // Act
                val answer = client.call("escalate-ticket", """{"payload": {"ticketId": 42, "reason": ""}}""")

                // Assert
                assertThat(answer.isError).isTrue()
                assertThat(answer.text("error")).contains("an escalation needs a reason")
                assertThat(pendenz.tasks()).isEmpty()
            }

        @Test
        fun `enqueue tool - agent lacks the ENQUEUE permission - an error, nothing written`() =
            runBlocking<Unit> {
                // Arrange
                val onlyReplies = Permissions.table { actor(AGENT).may(Action.ENQUEUE, "escalate-ticket") }
                val pendenz = pendenz(onlyReplies)
                val client = client(pendenz)

                // Act
                val answer = client.call("reply-to-ticket", """{"payload": {"ticketId": 1, "text": "Hi"}}""")

                // Assert
                assertThat(answer.isError).isTrue()
                assertThat(answer.text("error")).contains(AGENT).contains("ENQUEUE")
                assertThat(pendenz.tasks()).isEmpty()
            }
    }

    @Nested
    inner class Preview {
        @Test
        fun `preview tool - valid payload - the type's preview, and no task is written`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz()
                val client = client(pendenz)

                // Act
                val answer =
                    client.call(
                        "preview_escalate-ticket",
                        """{"payload": {"ticketId": 42, "reason": "VIP"}}""",
                    )

                // Assert
                assertThat(answer.text("preview")).isEqualTo("Escalate ticket 42: VIP")
                assertThat(pendenz.tasks()).isEmpty()
            }

        @Test
        fun `preview tool - payload the type's preview refuses - an error with the preview's reason`() {
            // Arrange
            val client = client(pendenz())

            // Act
            val answer = client.call("preview_escalate-ticket", """{"payload": {"ticketId": 42, "reason": " "}}""")

            // Assert
            assertThat(answer.isError).isTrue()
            assertThat(answer.text("error")).contains("an escalation needs a reason")
        }
    }

    @Nested
    inner class GetTask {
        @Test
        fun `get_task - task the worker finished - SUCCEEDED with result and provenance`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz()
                startWorker(pendenz)
                val client = client(pendenz)
                val id =
                    client
                        .call(
                            "reply-to-ticket",
                            """{"payload": {"ticketId": 7, "text": "Fixed. Thanks."}}""",
                        ).text("id")!!
                awaitStatus(pendenz, id.toLong(), TaskStatus.SUCCEEDED)

                // Act
                val answer = client.call("get_task", """{"id": "$id"}""")

                // Assert
                assertThat(answer.text("status")).isEqualTo("SUCCEEDED")
                assertThat(
                    answer.body["result"]!!
                        .jsonObject["sentences"]!!
                        .jsonPrimitive.content,
                ).isEqualTo("2")
                assertThat(
                    answer.body["provenance"]!!
                        .jsonObject["rules"]!!
                        .jsonPrimitive.content,
                ).isEqualTo("2026-10")
            }

        @Test
        fun `get_task - unknown id - the error no task with that id`() {
            // Arrange
            val client = client(pendenz())

            // Act
            val answer = client.call("get_task", """{"id": "999999"}""")

            // Assert
            assertThat(answer.isError).isTrue()
            assertThat(answer.text("error")).isEqualTo("no task 999999")
        }

        @Test
        fun `get_task - task another actor created - the same no task error as for an unknown id`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz()
                val client = client(pendenz)
                val clerks = pendenz.enqueue(reply, Reply(1, "Hi"), createdBy = "billing-clerk")

                // Act
                val answer = client.call("get_task", """{"id": "$clerks"}""")

                // Assert
                assertThat(answer.isError).isTrue()
                assertThat(answer.text("error")).isEqualTo("no task $clerks")
            }
    }

    @Nested
    inner class ErrorTexts {
        @Test
        fun `get_task - FAILED with an exception text - no lastError, the status tells what happened`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz()
                val id = failed(pendenz, "FAILED", "java.sql.SQLException: no route to db-internal-7")

                val answer = client(pendenz).call("get_task", """{"id": "$id"}""")

                // Assert
                assertThat(answer.text("status")).isEqualTo("FAILED")
                assertThat(answer.body).doesNotContainKey("lastError")
            }

        @Test
        fun `get_task - REJECTED with the validator's reason - lastError carries the reason`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz()
                val id = failed(pendenz, "REJECTED", "replies may not promise refunds")

                val answer = client(pendenz).call("get_task", """{"id": "$id"}""")

                assertThat(answer.text("lastError")).isEqualTo("replies may not promise refunds")
            }

        @Test
        fun `get_task - errorText EVERYTHING - the exception text reaches the agent`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz()
                val id = failed(pendenz, "FAILED", "java.sql.SQLException: no route to db-internal-7")
                val mcp =
                    PendenzMcp
                        .create(pendenz, IdType.BIGINT, AGENT) {
                            tool(reply, "Sends a reply.", JsonSchema.of<Reply>())
                            errorText(ErrorText.EVERYTHING)
                        }.also { closeables += it }

                val answer = client(mcp).call("get_task", """{"id": "$id"}""")

                assertThat(answer.text("lastError")).contains("db-internal-7")
            }

        /** A task of the agent's, moved to [status] with [error] by SQL, as a worker or a validator would. */
        private suspend fun failed(
            pendenz: Pendenz<Long>,
            status: String,
            error: String,
        ): Long {
            val id = pendenz.enqueue(reply, Reply(9, "Hi"), createdBy = AGENT)
            db.sql("""UPDATE "${table.name}" SET status = '$status', last_error = '$error' WHERE id = $id""")
            return id
        }
    }

    @Nested
    inner class ListTasks {
        @Test
        fun `list_tasks - own task with a follow-up and another actor's task - the own two, newest first`() =
            runBlocking<Unit> {
                // Arrange
                val pendenz = pendenz()
                startWorker(pendenz)
                val client = client(pendenz)
                pendenz.enqueue(reply, Reply(2, "Not mine."), createdBy = "billing-clerk")
                val own =
                    client
                        .call(
                            "reply-to-ticket",
                            """{"payload": {"ticketId": 3, "text": "Done."}}""",
                        ).text("id")!!
                awaitStatus(pendenz, own.toLong(), TaskStatus.SUCCEEDED)

                // Act
                val answer = client.call("list_tasks")

                // Assert
                val tasks = answer.body["tasks"] as JsonArray
                assertThat(
                    tasks.map { it.jsonObject.text("type") },
                ).containsExactly("notify-customer", "reply-to-ticket")
            }

        @Test
        fun `list_tasks - filtered by type - only the tasks of that type`() {
            // Arrange
            val client = client(pendenz())
            enqueueTwoMatters(client)

            // Act
            val answer = client.call("list_tasks", """{"type": "reply-to-ticket"}""")

            // Assert
            assertThat((answer.body["tasks"] as JsonArray).map { it.jsonObject.text("type") })
                .containsExactly("reply-to-ticket", "reply-to-ticket")
        }

        @Test
        fun `list_tasks - filtered by correlation id - only the tasks of that matter`() {
            // Arrange
            val client = client(pendenz())
            enqueueTwoMatters(client)

            // Act
            val answer = client.call("list_tasks", """{"correlationId": "ticket-1"}""")

            // Assert
            assertThat((answer.body["tasks"] as JsonArray).map { it.jsonObject.text("type") })
                .containsExactlyInAnyOrder("escalate-ticket", "reply-to-ticket")
        }

        /** Ticket 1 escalated and answered, ticket 2 answered: two types across two matters. */
        private fun enqueueTwoMatters(client: McpHttpClient) {
            client.call(
                "escalate-ticket",
                """{"payload": {"ticketId": 1, "reason": "angry"}, "correlationId": "ticket-1"}""",
            )
            client.call(
                "reply-to-ticket",
                """{"payload": {"ticketId": 1, "text": "Sorry."}, "correlationId": "ticket-1"}""",
            )
            client.call(
                "reply-to-ticket",
                """{"payload": {"ticketId": 2, "text": "Hello."}, "correlationId": "ticket-2"}""",
            )
        }

        @Test
        fun `list_tasks - limit of two over three tasks - the two newest`() {
            // Arrange
            val client = client(pendenz())
            val ids =
                (1..3).map {
                    client
                        .call(
                            "reply-to-ticket",
                            """{"payload": {"ticketId": $it, "text": "Hi"}}""",
                        ).text("id")
                }

            // Act
            val answer = client.call("list_tasks", """{"limit": 2}""")

            // Assert
            assertThat(
                (answer.body["tasks"] as JsonArray).map { it.jsonObject.text("id") },
            ).containsExactly(ids[2], ids[1])
        }

        @Test
        fun `list_tasks - filtered by status - only tasks in that status`() {
            // Arrange
            val client = client(pendenz())
            client.call("escalate-ticket", """{"payload": {"ticketId": 1, "reason": "angry"}}""")
            client.call("reply-to-ticket", """{"payload": {"ticketId": 1, "text": "Calm down."}}""")

            // Act
            val answer = client.call("list_tasks", """{"status": "AWAITING_APPROVAL"}""")

            // Assert
            val tasks = answer.body["tasks"] as JsonArray
            assertThat(tasks.map { it.jsonObject.text("type") }).containsExactly("escalate-ticket")
        }
    }

    @Nested
    inner class ServerInfo {
        @Test
        fun `server info - any build - reports the version of the build`() {
            // Arrange
            val build = System.getProperty("pendenz.version")

            // Act
            val client = client(pendenz())

            // Assert
            assertThat(client.serverVersion).isEqualTo(build).isNotEqualTo("unversioned")
        }
    }

    @Nested
    inner class Servlet {
        @Test
        fun `servlet - request with an Origin - an allowed one is answered, any other refused with 403`() {
            // Arrange
            val mcp =
                PendenzMcp
                    .create(pendenz(), IdType.BIGINT, AGENT) { tool(reply, "Sends a reply.", JsonSchema.of<Reply>()) }
                    .also { closeables += it }
            val client =
                McpHttpClient(mcp.servlet(McpHttpClient.ENDPOINT, listOf("https://ops.example.com")))
                    .also { closeables += it }

            // Act
            val allowed = client.initializeFrom("https://ops.example.com")
            val foreign = client.initializeFrom("https://attacker.example")

            // Assert
            assertThat(allowed).isEqualTo(200)
            assertThat(foreign).isEqualTo(403)
        }

        @Test
        fun `servlet - no origins allowed - a page's request refused, the agent host without Origin served`() {
            // Arrange
            val client = client(pendenz())

            // Act
            val fromPage = client.initializeFrom("http://localhost:3000")

            // Assert: the client itself sends no Origin and could call a tool
            assertThat(fromPage).isEqualTo(403)
            assertThat(client.tools()).isNotEmpty()
        }
    }

    private fun JsonObject.text(key: String): String? = get(key)?.jsonPrimitive?.content

    private fun pendenz(permissions: Permissions = Permissions.ALLOW_ALL): Pendenz<Long> =
        Pendenz.create(table) {
            dataSource(db.dataSource)
            permissions(permissions)
            register(escalate) { _, _ -> }
            register(reply, counted) { ctx, r ->
                ctx.provenance("rules", "2026-10")
                ctx.enqueue(notify, r)
                Answer(r.text.split(". ").size)
            }
            register(notify) { _, _ -> }
        }

    private fun client(pendenz: Pendenz<Long>): McpHttpClient =
        client(
            PendenzMcp
                .create(pendenz, IdType.BIGINT, AGENT) {
                    tool(escalate, "Escalates a ticket to second level.", JsonSchema.of<Escalation>())
                    tool(reply, "Sends a reply to the customer of a ticket.", JsonSchema.of<Reply>())
                }.also { closeables += it },
        )

    private fun client(mcp: PendenzMcp<Long>): McpHttpClient = McpHttpClient(mcp).also { closeables += it }

    private fun startWorker(pendenz: Pendenz<Long>): Job {
        val options = WorkerOptions(parallelism = 1, pollInterval = Duration.ofMillis(50))
        return pendenz.startWorker(scope, options).also { workers += it }
    }

    companion object {
        const val AGENT = "ticket-agent"

        @JvmField
        @RegisterExtension
        val db = PostgresDatabase()
    }
}
