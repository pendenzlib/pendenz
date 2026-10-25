package io.github.d4niella.pendenz.examples.support

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * The chat completions endpoint of an OpenAI-compatible API, through the
 * JDK's own HTTP client: a local model in Ollama or LM Studio, or any cloud
 * provider with that API.
 */
class ChatCompletions(
    val baseUrl: String,
    val model: String,
    private val apiKey: String? = null,
    private val timeout: Duration = DEFAULT_TIMEOUT,
) {
    // HTTP/1.1, because the JDK offers plain-text HTTP/2 through an upgrade header by default,
    // and LM Studio never answers such a request.
    private val http =
        HttpClient
            .newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(CONNECT_SECONDS))
            .build()

    /** Sends [fields], such as messages and tools, with the model's name, and returns the answer's body. */
    fun post(fields: Map<String, JsonElement>): String {
        val body = JsonObject(mapOf("model" to JsonPrimitive(model)) + fields)
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
        return response.body()
    }

    companion object {
        private const val HTTP_OK = 200
        private const val CONNECT_SECONDS = 10L
        private val DEFAULT_TIMEOUT = Duration.ofMinutes(2)

        /**
         * `<prefix>_MODEL_URL`, `<prefix>_MODEL`, `<prefix>_API_KEY` and `<prefix>_MODEL_TIMEOUT`
         * (ISO-8601, such as `PT5M`, for models that think long); Ollama on this machine by default.
         */
        fun fromEnvironment(
            prefix: String,
            env: Map<String, String> = System.getenv(),
        ): ChatCompletions =
            ChatCompletions(
                baseUrl = env["${prefix}_MODEL_URL"] ?: "http://localhost:11434/v1",
                model = env["${prefix}_MODEL"] ?: "qwen3:8b",
                apiKey = env["${prefix}_API_KEY"],
                timeout = env["${prefix}_MODEL_TIMEOUT"]?.let(Duration::parse) ?: DEFAULT_TIMEOUT,
            )
    }
}
