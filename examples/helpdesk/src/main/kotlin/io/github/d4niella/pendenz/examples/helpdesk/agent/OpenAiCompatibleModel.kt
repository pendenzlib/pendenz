package io.github.d4niella.pendenz.examples.helpdesk.agent

import io.github.d4niella.pendenz.examples.support.ChatCompletions
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

/** A [ChatModel] behind an OpenAI-compatible chat completions API, with tool calling. */
class OpenAiCompatibleModel(
    private val endpoint: ChatCompletions,
) : ChatModel {
    override fun complete(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>,
    ): ChatMessage {
        val fields =
            mapOf(
                "messages" to json.encodeToJsonElement(messages),
                "tools" to JsonArray(tools.map { it.toJson() }),
            )
        return json
            .decodeFromString<Completion>(endpoint.post(fields))
            .choices
            .first()
            .message
    }

    private fun ToolDefinition.toJson(): JsonObject =
        buildJsonObject {
            put("type", "function")
            put(
                "function",
                buildJsonObject {
                    put("name", name)
                    put("description", description)
                    put("parameters", parameters)
                },
            )
        }

    @Serializable
    private data class Completion(
        val choices: List<Choice>,
    )

    @Serializable
    private data class Choice(
        val message: ChatMessage,
    )

    companion object {
        private val json =
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
                // A tool call's "type": "function" is a default; LM Studio refuses the history without it.
                encodeDefaults = true
            }

        /** The variables with the prefix `AGENT`; see [ChatCompletions.fromEnvironment]. */
        fun fromEnvironment(env: Map<String, String> = System.getenv()): OpenAiCompatibleModel =
            OpenAiCompatibleModel(ChatCompletions.fromEnvironment("AGENT", env))
    }
}
