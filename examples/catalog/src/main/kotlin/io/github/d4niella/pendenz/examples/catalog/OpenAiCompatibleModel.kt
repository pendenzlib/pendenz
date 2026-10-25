package io.github.d4niella.pendenz.examples.catalog

import io.github.d4niella.pendenz.examples.support.ChatCompletions
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A [CategoryModel] behind an OpenAI-compatible chat completions API. */
class OpenAiCompatibleModel(
    private val endpoint: ChatCompletions,
) : CategoryModel {
    override val name: String get() = endpoint.model

    override fun categorise(
        product: Product,
        categories: List<String>,
    ): String {
        val instructions =
            "You sort the products of an online shop into categories. Answer with exactly one of these " +
                "categories and nothing else: ${categories.joinToString(", ")}."
        val messages =
            JsonArray(
                listOf(message("system", instructions), message("user", "${product.name}. ${product.description}")),
            )
        val content =
            json
                .decodeFromString<Completion>(endpoint.post(mapOf("messages" to messages)))
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
        private val json = Json { ignoreUnknownKeys = true }

        /** The variables with the prefix `CATALOG`; see [ChatCompletions.fromEnvironment]. */
        fun fromEnvironment(env: Map<String, String> = System.getenv()): OpenAiCompatibleModel =
            OpenAiCompatibleModel(ChatCompletions.fromEnvironment("CATALOG", env))
    }
}
