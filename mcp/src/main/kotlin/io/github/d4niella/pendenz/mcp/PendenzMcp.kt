package io.github.d4niella.pendenz.mcp

import io.github.d4niella.pendenz.JsonPayload
import io.github.d4niella.pendenz.PayloadCodec
import io.github.d4niella.pendenz.PayloadValues
import io.github.d4niella.pendenz.Pendenz
import io.github.d4niella.pendenz.TaskType
import io.github.d4niella.pendenz.schema.IdType
import io.modelcontextprotocol.json.McpJsonDefaults
import io.modelcontextprotocol.json.McpJsonMapper
import io.modelcontextprotocol.server.McpServer
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification
import io.modelcontextprotocol.server.McpSyncServer
import io.modelcontextprotocol.server.transport.DefaultServerTransportSecurityValidator
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider
import io.modelcontextprotocol.spec.McpSchema
import jakarta.servlet.http.HttpServlet
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.InputStream
import java.io.OutputStream
import java.util.Properties
import java.util.concurrent.CopyOnWriteArrayList

/**
 * An MCP server over the task types the application exposes (ADR 0021).
 * Each exposed type is a tool that enqueues; `preview_<type>`,
 * `get_task` and `list_tasks` let the agent look before and after. Every
 * call acts as [actor]: it is the tasks' creator and the identity the
 * permissions check, and the agent sees only the tasks it created.
 *
 * The module opens no port and starts no process. [serveStdio] serves the
 * standard streams of an application an agent host starts; [servlet]
 * returns a servlet for Streamable HTTP that the application mounts in
 * its own container. [close] stops every server this instance started.
 */
public class PendenzMcp<ID : Any> internal constructor(
    pendenz: Pendenz<ID>,
    ids: IdType<ID>,
    actor: String,
    exposed: List<ExposedType<*>>,
    private val serverName: String,
    errorText: ErrorText,
) : AutoCloseable {
    private val mapper: McpJsonMapper = McpJsonDefaults.getMapper()
    private val tools: List<Tool> = TaskTools(pendenz, ids, actor, exposed, errorText).all()
    private val servers = CopyOnWriteArrayList<McpSyncServer>()

    /** The names of the tools an agent sees. */
    public val toolNames: List<String> get() = tools.map { it.name }

    /**
     * Serves MCP over [input] and [output], the standard streams by
     * default, and returns at once; the server reads on a thread of its
     * own until [close]. Nothing else may write to [output]: send logs to
     * standard error.
     */
    @JvmOverloads
    public fun serveStdio(
        input: InputStream = System.`in`,
        output: OutputStream = System.out,
    ) {
        val spec = McpServer.sync(StdioServerTransportProvider(mapper, input, output))
        configure(spec)
        servers += spec.build()
    }

    /**
     * A servlet that serves Streamable HTTP at [endpoint]; register it in
     * the application's servlet container under the same path.
     *
     * The servlet has no authentication of its own: every caller that
     * reaches it acts as this server's actor, so put it behind the
     * application's security. A request whose `Origin` header is not in
     * [allowedOrigins] is refused with 403, which keeps web pages from
     * reaching it through DNS rebinding; agent hosts send no `Origin`. An
     * entry may end in `:*` for any port, as in `http://localhost:*`.
     */
    @JvmOverloads
    public fun servlet(
        endpoint: String = "/mcp",
        allowedOrigins: List<String> = emptyList(),
    ): HttpServlet {
        val origins = DefaultServerTransportSecurityValidator.builder().allowedOrigins(allowedOrigins).build()
        val transport =
            HttpServletStreamableServerTransportProvider
                .builder()
                .jsonMapper(mapper)
                .mcpEndpoint(endpoint)
                .httpHeaderValidator(origins)
                .build()
        val spec = McpServer.sync(transport)
        configure(spec)
        servers += spec.build()
        return transport
    }

    /** Stops every server this instance started; their sessions end. */
    override fun close() {
        servers.forEach { it.closeGracefully() }
        servers.clear()
    }

    private fun configure(spec: McpServer.SyncSpecification<*>) {
        spec
            .serverInfo(serverName, VERSION)
            .capabilities(
                McpSchema.ServerCapabilities
                    .builder()
                    .tools(false)
                    .build(),
            ).tools(tools.map { specification(it) })
    }

    private fun specification(tool: Tool): SyncToolSpecification =
        SyncToolSpecification
            .builder()
            .tool(
                McpSchema.Tool
                    .builder(tool.name, mapper, tool.inputSchema.toString())
                    .description(tool.description)
                    .annotations(
                        McpSchema.ToolAnnotations
                            .builder()
                            .readOnlyHint(tool.readOnly)
                            .build(),
                    ).build(),
            ).callHandler { _, request -> respond(tool.call(arguments(request))) }
            .build()

    private fun arguments(request: McpSchema.CallToolRequest): JsonObject =
        Json.parseToJsonElement(mapper.writeValueAsString(request.arguments() ?: emptyMap<String, Any>())).jsonObject

    private fun respond(reply: Reply): McpSchema.CallToolResult {
        val text = reply.body.toString()
        val result =
            McpSchema.CallToolResult
                .builder()
                .addTextContent(text)
                .isError(reply.isError)
        if (!reply.isError) result.structuredContent(mapper, text)
        return result.build()
    }

    /** Collects the task types to expose; nothing is exposed that is not named here. */
    public class Builder<ID : Any> internal constructor(
        private val pendenz: Pendenz<ID>,
        private val ids: IdType<ID>,
        private val actor: String,
    ) {
        private val exposed = mutableListOf<ExposedType<*>>()
        private var serverName = "pendenz"
        private var errorText = ErrorText.REASONS

        /**
         * Exposes [type] as a tool. [inputSchema] is the JSON Schema of the
         * payload, see [JsonSchema]. [arguments] decodes the payload from
         * the tool's JSON; by default the type's own codec, which only a
         * type that stores one JSON document has (ADR 0013).
         */
        @JvmOverloads
        public fun <P : Any> tool(
            type: TaskType<P>,
            description: String,
            inputSchema: String,
            arguments: PayloadCodec<P>? = null,
        ): Builder<ID> =
            apply {
                require(exposed.none { it.type.name == type.name }) { "task type '${type.name}' is exposed twice" }
                val tool = ExposedType(type, description, schemaObject(type, inputSchema), decoder(type, arguments))
                require(pendenz.isRegistered(type)) {
                    "task type '${type.name}' is not registered at the queue, or as another instance"
                }
                exposed += tool
            }

        /** The name the server reports to clients; `pendenz` by default. */
        public fun serverName(name: String): Builder<ID> = apply { serverName = name }

        /** What `get_task` shows of a task's error; [ErrorText.REASONS] by default. */
        public fun errorText(errorText: ErrorText): Builder<ID> = apply { this.errorText = errorText }

        public fun build(): PendenzMcp<ID> = PendenzMcp(pendenz, ids, actor, exposed.toList(), serverName, errorText)

        private fun schemaObject(
            type: TaskType<*>,
            inputSchema: String,
        ): JsonObject =
            requireNotNull(runCatching { Json.parseToJsonElement(inputSchema) as? JsonObject }.getOrNull()) {
                "the input schema of '${type.name}' is not a JSON object"
            }

        private fun <P : Any> decoder(
            type: TaskType<P>,
            arguments: PayloadCodec<P>?,
        ): (String) -> P {
            if (arguments != null) return arguments::decode
            val mapping = type.payload
            require(mapping is JsonPayload<P>) {
                "task type '${type.name}' stores its payload in columns; name a codec for its tool arguments"
            }
            return { text -> mapping.read(PayloadValues(mapOf(mapping.column to text))) }
        }
    }

    public companion object {
        /** Written by the build beside the classes. */
        private val VERSION: String =
            PendenzMcp::class.java
                .getResourceAsStream("version.properties")
                ?.use { Properties().apply { load(it) }.getProperty("version") }
                ?: "unversioned"
        private const val MAX_ACTOR_LENGTH = 100

        /** An MCP server over [pendenz], whose ids are of [ids], acting as [actor]. */
        public fun <ID : Any> create(
            pendenz: Pendenz<ID>,
            ids: IdType<ID>,
            actor: String,
            configure: Builder<ID>.() -> Unit,
        ): PendenzMcp<ID> = builder(pendenz, ids, actor).apply(configure).build()

        /** The builder [create] uses, for Java. */
        @JvmStatic
        public fun <ID : Any> builder(
            pendenz: Pendenz<ID>,
            ids: IdType<ID>,
            actor: String,
        ): Builder<ID> {
            require(actor.isNotBlank()) { "the actor must not be blank" }
            // Stored as creator and in front of the agent's idempotency keys, both columns of 200 characters
            require(actor.length <= MAX_ACTOR_LENGTH) { "the actor is longer than $MAX_ACTOR_LENGTH characters" }
            return Builder(pendenz, ids, actor)
        }
    }
}

/** A task type the application exposes, with what its tool needs. */
internal class ExposedType<P : Any>(
    val type: TaskType<P>,
    val description: String,
    val payloadSchema: JsonObject,
    val decode: (String) -> P,
)
