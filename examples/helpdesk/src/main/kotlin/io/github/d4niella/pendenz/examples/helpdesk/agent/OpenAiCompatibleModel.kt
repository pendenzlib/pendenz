package io.github.d4niella.pendenz.examples.helpdesk.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * The chat completions endpoint of an OpenAI-compatible API, through the
 * JDK's own HTTP client: a local model in Ollama by default, any cloud
 * provider with that API by configuration.
 */
class OpenAiCompatibleModel(
    private val baseUrl: String,
    private val model: String,
    private val apiKey: String? = null,
    private val timeout: Duration = Duration.ofMinutes(2),
) : ChatModel {
    // HTTP/1.1, because the JDK offers plain-text HTTP/2 through an upgrade header by default,
    // and LM Studio never answers such a request.
    private val http =
        HttpClient
            .newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(CONNECT_SECONDS))
            .build()

    override fun complete(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>,
    ): ChatMessage {
        val body =
            buildJsonObject {
                put("model", model)
                put("messages", json.encodeToJsonElement(messages))
                put("tools", JsonArray(tools.map { it.toJson() }))
            }
        val request =
            HttpRequest
                .newBuilder(URI.create("${baseUrl.trimEnd('/')}/chat/completions"))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .apply { apiKey?.let { header("Authorization", "Bearer $it") } }
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == HTTP_OK) { "the model answered ${response.statusCode()}: ${response.body()}" }
        return json
            .decodeFromString<Completion>(response.body())
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
        private const val HTTP_OK = 200
        private const val CONNECT_SECONDS = 10L
        private val json =
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
                // A tool call's "type": "function" is a default; LM Studio refuses the history without it.
                encodeDefaults = true
            }

        /** `AGENT_MODEL_URL`, `AGENT_MODEL` and `AGENT_API_KEY`; Ollama on this machine by default. */
        fun fromEnvironment(env: Map<String, String> = System.getenv()): OpenAiCompatibleModel =
            OpenAiCompatibleModel(
                baseUrl = env["AGENT_MODEL_URL"] ?: "http://localhost:11434/v1",
                model = env["AGENT_MODEL"] ?: "qwen3:8b",
                apiKey = env["AGENT_API_KEY"],
            )
    }
}
