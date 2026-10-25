package io.github.d4niella.pendenz.mcp

import io.github.d4niella.pendenz.NotPermittedException
import io.github.d4niella.pendenz.Pendenz
import io.github.d4niella.pendenz.TaskQuery
import io.github.d4niella.pendenz.TaskStatus
import io.github.d4niella.pendenz.schema.IdType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.format.DateTimeParseException

/** One tool: what the agent sees of it, and what a call does. */
internal class Tool(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val readOnly: Boolean,
    val call: (JsonObject) -> Reply,
)

/** A tool's answer; an error is one the model can read and correct. */
internal class Reply(
    val body: JsonObject,
    val isError: Boolean,
) {
    companion object {
        fun ok(body: JsonObject) = Reply(body, isError = false)

        fun error(message: String) = Reply(buildJsonObject { put("error", message) }, isError = true)
    }
}

/** Something the agent got wrong; the message goes back to it as the tool's error. */
private class ToolError(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** The tools over the exposed task types, all acting as [actor]. See ADR 0021. */
internal class TaskTools<ID : Any>(
    private val pendenz: Pendenz<ID>,
    private val ids: IdType<ID>,
    private val actor: String,
    private val exposed: List<ExposedType<*>>,
    private val errorText: ErrorText,
) {
    fun all(): List<Tool> =
        exposed.map { enqueueTool(it) } +
            exposed.filter { it.type.preview != null }.map { previewTool(it) } +
            listOf(getTask, listTasks)

    private fun <P : Any> enqueueTool(exposed: ExposedType<P>): Tool =
        Tool(
            name = exposed.type.name,
            description = exposed.description + ENQUEUE_NOTE,
            inputSchema =
                objectSchema(
                    required = listOf("payload"),
                    "payload" to exposed.payloadSchema,
                    "runAt" to text("When the task is due, ISO-8601 with offset; now when omitted.", "date-time"),
                    "idempotencyKey" to
                        text("A key unique per task type; a repeat returns the existing task.", maxLength = keyLength),
                    "correlationId" to
                        text(
                            "Groups this task with others of the same matter, for list_tasks.",
                            maxLength = COLUMN_LENGTH,
                        ),
                ),
            readOnly = false,
        ) { args ->
            guarded {
                val payload = decode(exposed, args)
                val id =
                    refusable {
                        runBlocking {
                            pendenz.enqueue(
                                exposed.type,
                                payload,
                                runAt = args.string("runAt")?.let(::instant),
                                idempotencyKey = args.string("idempotencyKey")?.let { ownKey(it.limitedTo(keyLength)) },
                                createdBy = actor,
                                correlationId = args.string("correlationId")?.limitedTo(COLUMN_LENGTH),
                            )
                        }
                    }
                // A key someone else used under the agent's prefix still names a task the agent may not see
                val task =
                    runBlocking { pendenz.find(id) }?.takeIf { it.createdBy == actor }
                        ?: throw ToolError("idempotency key already used")
                buildJsonObject {
                    put("id", task.id.toString())
                    put("status", task.status.name)
                    task.preview?.let { put("preview", it) }
                    if (task.status == TaskStatus.AWAITING_APPROVAL) put("note", AWAITING_APPROVAL_NOTE)
                }
            }
        }

    /**
     * The agent's idempotency key in a namespace of its own, so it can neither
     * reach a task someone else enqueued under the same key nor take a key the
     * business process will use (ADR 0021, corrected on review).
     */
    private fun ownKey(key: String): String = "mcp:$actor:$key"

    /** What remains of the key column once the agent's namespace is in front. */
    private val keyLength = COLUMN_LENGTH - ownKey("").length

    /** Checked here as well as by the schema, so a client that skips the schema gets a readable error too. */
    private fun String.limitedTo(length: Int): String {
        if (this.length > length) throw ToolError("at most $length characters, got ${this.length}")
        return this
    }

    private fun <P : Any> previewTool(exposed: ExposedType<P>): Tool =
        Tool(
            name = "preview_${exposed.type.name}",
            description = "What a '${exposed.type.name}' task with this payload would do. Writes nothing.",
            inputSchema = objectSchema(required = listOf("payload"), "payload" to exposed.payloadSchema),
            readOnly = true,
        ) { args ->
            guarded {
                val preview = checkNotNull(exposed.type.preview)
                val payload = decode(exposed, args)
                buildJsonObject { put("preview", refusable { preview(payload) }) }
            }
        }

    private val getTask =
        Tool(
            name = "get_task",
            description = "Status, attempts, error, result, provenance and preview of a task you created.",
            inputSchema = objectSchema(required = listOf("id"), "id" to text("The id an enqueue tool returned.")),
            readOnly = true,
        ) { args ->
            guarded {
                val text = args.string("id") ?: throw ToolError("id is missing")
                val id = runCatching { ids.parse(text) }.getOrElse { throw ToolError("no task $text") }
                val task = runBlocking { pendenz.find(id) }?.takeIf { it.createdBy == actor }
                task?.details(errorText) ?: throw ToolError("no task $text")
            }
        }

    private val listTasks =
        Tool(
            name = "list_tasks",
            description = "The tasks you created and their follow-ups, newest first.",
            inputSchema =
                objectSchema(
                    required = emptyList(),
                    "type" to text("Only tasks of this type."),
                    "status" to
                        buildJsonObject {
                            put("type", "string")
                            put("enum", JsonArray(TaskStatus.entries.map { JsonPrimitive(it.name) }))
                        },
                    "correlationId" to text("Only tasks with this correlation id.", maxLength = COLUMN_LENGTH),
                    "limit" to
                        buildJsonObject {
                            put("type", "integer")
                            put("minimum", 1)
                            put("maximum", MAX_LIST)
                        },
                ),
            readOnly = true,
        ) { args ->
            guarded {
                val query =
                    TaskQuery(
                        createdBy = actor,
                        types = setOfNotNull(args.string("type")),
                        statuses = setOfNotNull(args.string("status")?.let(::status)),
                        correlationId = args.string("correlationId"),
                        limit = args.int("limit")?.coerceIn(1, MAX_LIST) ?: DEFAULT_LIST,
                    )
                val tasks = runBlocking { pendenz.tasks(query) }
                buildJsonObject { put("tasks", JsonArray(tasks.map { it.summary() })) }
            }
        }

    // After the tool properties: the check reads them.
    init {
        val names = all().map { it.name }
        val duplicates = names.groupBy { it }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "tool names must be unique; taken twice: $duplicates" }
        names.forEach { require(it.matches(TOOL_NAME)) { "'$it' is not a valid MCP tool name" } }
    }

    private fun <P : Any> decode(
        exposed: ExposedType<P>,
        args: JsonObject,
    ): P {
        val payload = args["payload"] ?: throw ToolError("payload is missing")
        // Codecs throw whatever their library throws; all of it means "bad payload".
        @Suppress("TooGenericExceptionCaught")
        return try {
            exposed.decode(payload.toString())
        } catch (e: RuntimeException) {
            throw ToolError("the payload does not fit '${exposed.type.name}': ${e.message}", e)
        }
    }

    /**
     * Runs the type's own code over the agent's input, its preview or its
     * approval rule. An IllegalArgumentException from there refuses the
     * input: the agent learns why, and the log gets a warning, not an error.
     */
    private fun <T> refusable(block: () -> T): T =
        try {
            block()
        } catch (e: IllegalArgumentException) {
            log.warn("MCP tool call refused for actor {}: {}", actor, e.message)
            throw ToolError("the input was refused: ${e.message}", e)
        }

    private fun status(text: String): TaskStatus =
        TaskStatus.entries.firstOrNull { it.name == text } ?: throw ToolError("no status $text")

    private fun instant(text: String): Instant =
        try {
            Instant.parse(text)
        } catch (e: DateTimeParseException) {
            throw ToolError("runAt is not an ISO-8601 instant: ${e.message}", e)
        }

    @Suppress("TooGenericExceptionCaught") // the last line of defence: an agent gets a reply, never a dead session
    private fun guarded(block: () -> JsonObject): Reply =
        try {
            Reply.ok(block())
        } catch (e: ToolError) {
            Reply.error(e.message.orEmpty())
        } catch (e: NotPermittedException) {
            Reply.error(e.message.orEmpty())
        } catch (e: RuntimeException) {
            log.error("MCP tool call failed", e)
            Reply.error("the call failed on the server; the error is in its log")
        }

    private companion object {
        val log = LoggerFactory.getLogger(TaskTools::class.java)
        val TOOL_NAME = Regex("^[A-Za-z0-9_.-]{1,128}$")
        const val DEFAULT_LIST = 20
        const val MAX_LIST = 100

        /** The length of the queue's text columns in the generated DDL, `varchar(200)`. */
        const val COLUMN_LENGTH = 200
        const val ENQUEUE_NOTE = " Enqueues a task and returns its id and status; it runs later, see get_task."
        const val AWAITING_APPROVAL_NOTE = "A person has to approve this task before it runs."
    }
}
