package io.github.d4niella.pendenz.examples.helpdesk.agent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** A language model that answers a conversation, possibly by asking for tool calls. */
fun interface ChatModel {
    /** The model's next message after [messages], with [tools] on offer. */
    fun complete(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>,
    ): ChatMessage
}

/** A message in the OpenAI chat format, which Ollama, vLLM and most cloud providers speak. */
@Serializable
data class ChatMessage(
    val role: String,
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
) {
    companion object {
        fun system(text: String) = ChatMessage("system", text)

        fun user(text: String) = ChatMessage("user", text)

        fun tool(
            callId: String,
            result: String,
        ) = ChatMessage("tool", result, toolCallId = callId)
    }
}

@Serializable
data class ToolCall(
    val id: String,
    val type: String = "function",
    val function: FunctionCall,
)

/** The tool's name and its arguments as the model wrote them: JSON, as text. */
@Serializable
data class FunctionCall(
    val name: String,
    val arguments: String,
)

/** A tool as the model is offered it: the MCP tool's name, description and input schema. */
data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)
