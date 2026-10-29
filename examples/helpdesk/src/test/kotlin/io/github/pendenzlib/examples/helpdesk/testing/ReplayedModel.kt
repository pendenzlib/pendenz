package io.github.pendenzlib.examples.helpdesk.testing

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * An OpenAI-compatible chat endpoint that answers with prepared responses
 * in order, one per request, and keeps every request it received. CI runs
 * the agent against it, without a model and without a key; the agent's
 * real HTTP client talks to it.
 */
class ReplayedModel(
    resource: String,
) : AutoCloseable {
    private val responses = ConcurrentLinkedQueue(load(resource))
    private val server = HttpServer.create(InetSocketAddress("localhost", 0), 0)

    /** The request bodies in the order they arrived. */
    val requests = ConcurrentLinkedQueue<JsonObject>()

    /** The protocol upgrades the client offered, such as `h2c`, one entry per request that offered one. */
    val upgradeOffers = ConcurrentLinkedQueue<String>()

    val baseUrl: String get() = "http://localhost:${server.address.port}/v1"

    init {
        server.createContext("/v1/chat/completions") { exchange ->
            exchange.requestHeaders.getFirst("Upgrade")?.let { upgradeOffers += it }
            requests += Json.parseToJsonElement(exchange.requestBody.readAllBytes().decodeToString()).jsonObject
            val response = responses.poll()
            val body = (response?.toString() ?: """{"error": "no response left to replay"}""").encodeToByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(if (response != null) HTTP_OK else HTTP_GONE, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
    }

    /** The messages of the [index]th request, counting from 0. */
    fun messages(index: Int): JsonArray = requests.elementAt(index)["messages"]!!.jsonArray

    override fun close() = server.stop(0)

    private companion object {
        const val HTTP_OK = 200
        const val HTTP_GONE = 410

        fun load(resource: String): List<JsonObject> {
            val text =
                checkNotNull(
                    ReplayedModel::class.java.getResource(resource),
                ) { "no replay $resource" }.readText()
            return Json.parseToJsonElement(text).jsonArray.map { it.jsonObject }
        }
    }
}
