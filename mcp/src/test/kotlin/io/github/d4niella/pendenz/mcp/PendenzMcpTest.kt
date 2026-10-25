package io.github.d4niella.pendenz.mcp

import io.github.d4niella.pendenz.PayloadColumn
import io.github.d4niella.pendenz.Pendenz
import io.github.d4niella.pendenz.SqlType
import io.github.d4niella.pendenz.TaskType
import io.github.d4niella.pendenz.schema.IdType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.ThrowableAssert.ThrowingCallable
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilNotNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

class PendenzMcpTest {
    @Serializable
    data class Greeting(
        val name: String,
    )

    private val greet = TaskType.json<Greeting>("greet")
    private val pendenz =
        Pendenz.create {
            inMemory()
            register(greet) { _, _ -> }
        }
    private val closeables = mutableListOf<AutoCloseable>()

    @AfterEach
    fun close() = closeables.reversed().forEach { it.close() }

    @Nested
    inner class Build {
        @Test
        fun `tool - type with payload columns and no codec - throws IllegalArgumentException`() {
            // Arrange
            val columns =
                TaskType.columns<Greeting>(
                    "greet-in-columns",
                    columns = listOf(PayloadColumn("name", SqlType.TEXT)),
                    write = { mapOf("name" to it.name) },
                    read = { Greeting(it.getString("name")) },
                )

            val attempt =
                ThrowingCallable {
                    PendenzMcp.create(
                        pendenz,
                        IdType.BIGINT,
                        AGENT,
                    ) { tool(columns, "Greets.", JsonSchema.of<Greeting>()) }
                }

            assertThatThrownBy(attempt).isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("codec")
        }

        @Test
        fun `tool - type not registered at the queue - throws IllegalArgumentException naming it`() {
            val stranger = TaskType.json<Greeting>("stranger")

            val attempt =
                ThrowingCallable {
                    PendenzMcp.create(
                        pendenz,
                        IdType.BIGINT,
                        AGENT,
                    ) { tool(stranger, "Greets.", JsonSchema.of<Greeting>()) }
                }

            assertThatThrownBy(
                attempt,
            ).isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("stranger")
        }

        @Test
        fun `tool - the same type twice - throws IllegalArgumentException`() {
            val attempt =
                ThrowingCallable {
                    PendenzMcp.create(pendenz, IdType.BIGINT, AGENT) {
                        tool(greet, "Greets.", JsonSchema.of<Greeting>())
                        tool(greet, "Greets again.", JsonSchema.of<Greeting>())
                    }
                }

            assertThatThrownBy(attempt).isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("twice")
        }

        @Test
        fun `tool - schema that is not a JSON object - throws IllegalArgumentException`() {
            val attempt =
                ThrowingCallable {
                    PendenzMcp.create(
                        pendenz,
                        IdType.BIGINT,
                        AGENT,
                    ) { tool(greet, "Greets.", "[1, 2]") }
                }

            assertThatThrownBy(
                attempt,
            ).isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("JSON object")
        }

        @Test
        fun `tool - type named like a fixed tool - throws IllegalArgumentException`() {
            // Arrange
            val clash = TaskType.json<Greeting>("get_task")

            val attempt =
                ThrowingCallable {
                    PendenzMcp.create(
                        pendenz,
                        IdType.BIGINT,
                        AGENT,
                    ) { tool(clash, "Clashes.", JsonSchema.of<Greeting>()) }
                }

            assertThatThrownBy(
                attempt,
            ).isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("get_task")
        }

        @Test
        fun `builder - actor longer than a hundred characters - throws IllegalArgumentException`() {
            assertThatThrownBy {
                PendenzMcp.builder(pendenz, IdType.BIGINT, "agent-".repeat(20))
            }.isInstanceOf(IllegalArgumentException::class.java)
        }

        @Test
        fun `builder - blank actor - throws IllegalArgumentException`() {
            assertThatThrownBy {
                PendenzMcp.builder(pendenz, IdType.BIGINT, " ")
            }.isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Nested
    inner class Stdio {
        @Test
        fun `serveStdio - initialize, then a tool call on the streams - the task is enqueued and its id answered`() =
            runBlocking<Unit> {
                // Arrange
                val mcp =
                    PendenzMcp.create(
                        pendenz,
                        IdType.BIGINT,
                        AGENT,
                    ) { tool(greet, "Greets.", JsonSchema.of<Greeting>()) }
                closeables += mcp
                val agent = StdioAgent()
                mcp.serveStdio(agent.serverInput, agent.serverOutput)
                agent.send("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":$INITIALIZE}""")
                agent.awaitAnswer(1)
                agent.send("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")

                agent.send(
                    """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":$GREET_ADA}""",
                )

                // Assert
                val answer = agent.awaitAnswer(2)
                val text = answer["result"]!!.jsonObject["structuredContent"]!!.jsonObject
                val task = pendenz.find(text["id"]!!.jsonPrimitive.content.toLong())!!
                assertThat(task.createdBy).isEqualTo(AGENT)
                assertThat(task.payload["payload"].toString()).contains("Ada")
            }
    }

    /** The agent host's end of the standard streams: writes requests, collects answers by id. */
    private class StdioAgent {
        val serverInput = PipedInputStream()
        private val toServer = PipedOutputStream(serverInput).bufferedWriter()
        private val fromServer = PipedInputStream()
        val serverOutput = PipedOutputStream(fromServer)
        private val answers = ConcurrentHashMap<Int, JsonObject>()

        init {
            Thread {
                fromServer.bufferedReader().lineSequence().forEach { line ->
                    val message = Json.parseToJsonElement(line).jsonObject
                    message["id"]?.let { answers[it.jsonPrimitive.int] = message }
                }
            }.apply { isDaemon = true }.start()
        }

        fun send(message: String) {
            toServer.write(message)
            toServer.newLine()
            toServer.flush()
        }

        fun awaitAnswer(id: Int): JsonObject = await atMost Duration.ofSeconds(10) untilNotNull { answers[id] }
    }

    private companion object {
        const val AGENT = "ticket-agent"
        const val INITIALIZE =
            """{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"test","version":"1"}}"""
        const val GREET_ADA = """{"name":"greet","arguments":{"payload":{"name":"Ada"}}}"""
    }
}
