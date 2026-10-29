package io.github.pendenzlib.examples.catalog

import io.github.pendenzlib.ExponentialBackoff
import io.github.pendenzlib.PayloadColumn
import io.github.pendenzlib.SqlType
import io.github.pendenzlib.TaskResource
import io.github.pendenzlib.TaskResult
import io.github.pendenzlib.TaskType
import io.github.pendenzlib.Verdict
import io.github.pendenzlib.schema.CycleTable
import io.github.pendenzlib.schema.ResourceLimitTable
import io.github.pendenzlib.schema.TaskTable
import kotlinx.serialization.Serializable
import java.time.Duration

/**
 * The catalogue's domain: products in a table of their own, and one task
 * type that asks a language model for a product's category. A cycle runs
 * it over the whole table; the model is a shared resource with a limit.
 */
object Catalog {
    val TABLE = TaskTable(name = "catalog_task")
    val CYCLES = CycleTable(name = "catalog_cycle")
    val LIMITS = ResourceLimitTable()

    /** The categories the shop knows; anything else the model says is refused. */
    val CATEGORIES = listOf("Kitchen", "Garden", "Electronics", "Toys", "Books", "Sports", "Clothing", "Office")

    /** The language model, the bottleneck every classification goes through. */
    const val MODEL_RESOURCE = "language-model"

    /** One at a time: a local model server answers one request after the other anyway. */
    private const val MODEL_SLOTS = 1

    // Retried quickly for the demo; a real service would back off for minutes.
    private const val MAX_ATTEMPTS = 3
    private val RETRY_DELAY = Duration.ofSeconds(1)

    /** Stored as the column `product_id`, so SQL can join the tasks to the products. */
    data class ClassifyProduct(
        val productId: Long,
    )

    /** What the model decided, stored as the task's result. */
    @Serializable
    data class Classification(
        val category: String,
    )

    val CLASSIFY =
        TaskType.columns<ClassifyProduct>(
            "classify-product",
            columns = listOf(PayloadColumn("product_id", SqlType.BIGINT)),
            write = { mapOf("product_id" to it.productId) },
            read = { ClassifyProduct(it.getLong("product_id")) },
            retryPolicy = ExponentialBackoff(maxAttempts = MAX_ATTEMPTS, initialDelay = RETRY_DELAY, jitter = 0.0),
            resource = TaskResource.fixed(MODEL_RESOURCE, maxRunning = MODEL_SLOTS),
        )

    /** A category outside [CATEGORIES] is refused: the task lands in REJECTED instead of being retried. */
    val KNOWN_CATEGORY =
        TaskResult.json<ClassifyProduct, Classification> { _, classification ->
            if (classification.category in CATEGORIES) {
                Verdict.accept()
            } else {
                Verdict.reject("'${classification.category}' is not a category of this shop")
            }
        }
}
