package io.github.d4niella.pendenz.mcp.testing

import io.github.d4niella.pendenz.mcp.PendenzMcp
import io.modelcontextprotocol.client.McpClient
import io.modelcontextprotocol.client.McpSyncClient
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport
import io.modelcontextprotocol.spec.McpSchema
import jakarta.servlet.http.HttpServlet
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.eclipse.jetty.ee10.servlet.ServletContextHandler
import org.eclipse.jetty.ee10.servlet.ServletHolder
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.ServerConnector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * The servlet of a [PendenzMcp] in an embedded Jetty on a free port, and
 * the SDK's own client talking Streamable HTTP to it: what an agent host
 * does, inside the test JVM. The servlet must serve [ENDPOINT].
 */
class McpHttpClient(
    servlet: HttpServlet,
) : AutoCloseable {
    constructor(mcp: PendenzMcp<*>) : this(mcp.servlet(ENDPOINT))

    private val server = Server(0)
    private val client: McpSyncClient
    private val uri: URI

    init {
        val context = ServletContextHandler()
        context.addServlet(ServletHolder(servlet), ENDPOINT)
        server.handler = context
        server.start()
        val port = (server.connectors.single() as ServerConnector).localPort
        uri = URI("http://localhost:$port$ENDPOINT")
        val transport = HttpClientStreamableHttpTransport.builder("http://localhost:$port").endpoint(ENDPOINT).build()
        client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(10)).build()
        client.initialize()
    }

    fun tools(): List<McpSchema.Tool> = client.listTools().tools()

    /** Calls [tool] with [arguments] as JSON text and returns its answer. */
    fun call(
        tool: String,
        arguments: String = "{}",
    ): ToolAnswer {
        val args = Json.parseToJsonElement(arguments).jsonObject
        val result = client.callTool(McpSchema.CallToolRequest(tool, JsonMaps.toMap(args), null))
        val text = (result.content().single() as McpSchema.TextContent).text()
        // The SDK answers a call its schema check refused with plain text; our tools answer with JSON.
        val body =
            runCatching { Json.parseToJsonElement(text).jsonObject }.getOrElse {
                JsonObject(
                    mapOf("error" to JsonPrimitive(text)),
                )
            }
        return ToolAnswer(result.isError() == true, body)
    }

    /** Sends an `initialize` request as a browser would, with [origin] as its `Origin` header; returns the status. */
    fun initializeFrom(origin: String): Int {
        val body =
            """{"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {"protocolVersion": "2025-06-18", """ +
                """"capabilities": {}, "clientInfo": {"name": "browser", "version": "1"}}}"""
        val request =
            HttpRequest
                .newBuilder(uri)
                .header("Origin", origin)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
        val http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
        return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
    }

    override fun close() {
        client.closeGracefully()
        server.stop()
    }

    companion object {
        const val ENDPOINT: String = "/mcp"
    }
}

/** A tool's answer as the agent reads it. */
class ToolAnswer(
    val isError: Boolean,
    val body: JsonObject,
) {
    fun text(key: String): String? = (body[key] as? JsonPrimitive)?.content

    override fun toString(): String = "ToolAnswer(isError=$isError, body=$body)"
}
