package io.github.d4niella.pendenz.examples.catalog

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * A [CategoryModel] behind the OpenAI chat completions API, through the
 * JDK's own HTTP client: a local model in Ollama or LM Studio, or any
 * cloud provider with that API.
 */
class OpenAiCompatibleModel(
    private val baseUrl: String,
    override val name: String,
    private val apiKey: String? = null,
    private val timeout: Duration = DEFAULT_TIMEOUT,
) : CategoryModel {
    // HTTP/1.1, because the JDK offers plain-text HTTP/2 through an upgrade header by default,
    // and LM Studio never answers such a request.
    private val http =
        HttpClient
            .newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(CONNECT_SECONDS))
            .build()

    override fun categorise(
        product: Product,
        categories: List<String>,
    ): String {
        val instructions =
            "You sort the products of an online shop into categories. Answer with exactly one of these " +
                "categories and nothing else: ${categories.joinToString(", ")}."
        val body =
            buildJsonObject {
                put("model", name)
                put(
                    "messages",
                    JsonArray(
                        listOf(
                            message("system", instructions),
                            message("user", "${product.name}. ${product.description}"),
                        ),
                    ),
                )
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
        val content =
            json
                .decodeFromString<Completion>(response.body())
                .choices
                .first()
                .message.content
        return content.orEmpty().trim().trimEnd('.')
    }

    private fun message(
        role: String,
        content: String,
    ) = buildJsonObject {
        put("role", role)
        put("content", content)
    }

    @Serializable
    private data class Completion(
        val choices: List<Choice>,
    )

    @Serializable
    private data class Choice(
        val message: Message,
    )

    @Serializable
    private data class Message(
        val content: String? = null,
    )

    companion object {
        private const val HTTP_OK = 200
        private const val CONNECT_SECONDS = 10L
        private val DEFAULT_TIMEOUT = Duration.ofMinutes(2)
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * `CATALOG_MODEL_URL`, `CATALOG_MODEL`, `CATALOG_API_KEY` and `CATALOG_MODEL_TIMEOUT` (ISO-8601,
         * such as `PT5M`); Ollama on this machine by default.
         */
        fun fromEnvironment(env: Map<String, String> = System.getenv()): OpenAiCompatibleModel =
            OpenAiCompatibleModel(
                baseUrl = env["CATALOG_MODEL_URL"] ?: "http://localhost:11434/v1",
                name = env["CATALOG_MODEL"] ?: "qwen3:8b",
                apiKey = env["CATALOG_API_KEY"],
                timeout = env["CATALOG_MODEL_TIMEOUT"]?.let(Duration::parse) ?: DEFAULT_TIMEOUT,
            )
    }
}
