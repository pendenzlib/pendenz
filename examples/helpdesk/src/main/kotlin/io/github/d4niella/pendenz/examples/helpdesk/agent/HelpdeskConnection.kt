package io.github.d4niella.pendenz.examples.helpdesk.agent

import io.github.d4niella.pendenz.examples.helpdesk.DatabaseAccess
import io.modelcontextprotocol.client.McpClient
import io.modelcontextprotocol.client.McpSyncClient
import io.modelcontextprotocol.client.transport.ServerParameters
import io.modelcontextprotocol.client.transport.StdioClientTransport
import io.modelcontextprotocol.json.McpJsonDefaults
import io.modelcontextprotocol.json.TypeRef
import io.modelcontextprotocol.spec.McpSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.time.Duration

/**
 * The agent's MCP session with the helpdesk. Like an agent host, it starts
 * the helpdesk as a child process and talks to it over the process's
 * standard streams; the helpdesk's log arrives on its standard error and
 * is passed through.
 */
class HelpdeskConnection private constructor(
    private val client: McpSyncClient,
) : AutoCloseable {
    private val mapper = McpJsonDefaults.getMapper()

    /** The helpdesk's tools, as the model is offered them. */
    fun tools(): List<ToolDefinition> =
        client.listTools().tools().map { tool ->
            ToolDefinition(
                tool.name(),
                tool.description().orEmpty(),
                Json.parseToJsonElement(mapper.writeValueAsString(tool.inputSchema())).jsonObject,
            )
        }

    /** Calls [tool] with the JSON [arguments] the model wrote; the answer as text, errors marked as such. */
    fun call(
        tool: String,
        arguments: String,
    ): ToolOutcome {
        val args = mapper.readValue(arguments.ifBlank { "{}" }, ARGUMENTS)
        val result = client.callTool(McpSchema.CallToolRequest(tool, args, null))
        val text = result.content().filterIsInstance<McpSchema.TextContent>().joinToString("\n") { it.text() }
        return ToolOutcome(tool, arguments, text, result.isError() == true)
    }

    override fun close() {
        client.closeGracefully()
    }

    companion object {
        private val ARGUMENTS = object : TypeRef<Map<String, Any>>() {}
        private val INITIALIZE_TIMEOUT: Duration = Duration.ofSeconds(60)
        private const val HELPDESK_MAIN = "io.github.d4niella.pendenz.examples.helpdesk.HelpdeskMainKt"

        /** Starts the helpdesk on this JVM's classpath against [database] and opens the session. */
        fun start(database: DatabaseAccess): HelpdeskConnection {
            val java =
                ProcessHandle.current().info().command().orElse(
                    File(System.getProperty("java.home"), "bin/java").path,
                )
            val parameters =
                ServerParameters
                    .builder(java)
                    .args("-cp", System.getProperty("java.class.path"), HELPDESK_MAIN)
                    .env(database.environment())
                    .build()
            val transport = StdioClientTransport(parameters, McpJsonDefaults.getMapper())
            transport.setStdErrorHandler { line -> System.err.println("helpdesk | $line") }
            val client =
                McpClient
                    .sync(transport)
                    .clientInfo(McpSchema.Implementation.builder("ticket-agent", "1").build())
                    .initializationTimeout(INITIALIZE_TIMEOUT)
                    .requestTimeout(INITIALIZE_TIMEOUT)
                    .build()
            client.initialize()
            return HelpdeskConnection(client)
        }
    }
}

/** One tool call and what came back. */
data class ToolOutcome(
    val tool: String,
    val arguments: String,
    val answer: String,
    val isError: Boolean,
)
