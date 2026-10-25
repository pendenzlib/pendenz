package io.github.d4niella.pendenz.examples.catalog

import io.github.d4niella.pendenz.Pendenz
import io.github.d4niella.pendenz.TaskListener
import io.github.d4niella.pendenz.examples.catalog.Catalog.Classification
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.sql.DataSource

/**
 * The queue of the catalogue: cycles and resource limits enabled, and the
 * handler that asks the model for a product's category. The handler
 * refuses products it cannot classify; those end up FAILED for a person.
 */
fun catalogQueue(
    dataSource: DataSource,
    model: CategoryModel,
    listener: TaskListener<Long>,
): Pendenz<Long> {
    val products = Products(dataSource)
    val disruptions = Disruptions(model)
    return Pendenz.create(Catalog.TABLE) {
        dataSource(dataSource)
        enableCycles(Catalog.CYCLES)
        enableResourceLimits(Catalog.LIMITS)
        listener(listener)
        register(Catalog.CLASSIFY, Catalog.KNOWN_CATEGORY) { ctx, task ->
            val product = checkNotNull(products.find(task.productId)) { "product ${task.productId} does not exist" }
            check(!product.recalled) { "product ${product.id} is recalled and must not be listed" }
            checkNotNull(product.description) { "product ${product.id} has no description to classify" }
            ctx.provenance("model", model.name)
            ctx.provenance("prompt", PROMPT_VERSION)
            val answer = withContext(Dispatchers.IO) { disruptions.categorise(product, ctx.attempt) }
            Classification(canonical(answer))
        }
    }
}

/** Bumped whenever the instructions to the model change, so results can be told apart in SQL. */
private const val PROMPT_VERSION = "categories-v1"

/** The shop's spelling of a category the model named in another case; anything else stays as it was. */
private fun canonical(answer: String): String =
    Catalog.CATEGORIES.firstOrNull { it.equals(answer, ignoreCase = true) } ?: answer
