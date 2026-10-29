package io.github.pendenzlib.examples.catalog.testing

import io.github.pendenzlib.examples.catalog.CategoryModel
import io.github.pendenzlib.examples.catalog.Product

/**
 * A stand-in for the language model that recognises the phrases the
 * generated catalogue uses, so CI runs the demo without a model. It takes
 * [thinking] per answer, as a model would, which leaves the operator time
 * to pause the cycle midway.
 */
class KeywordModel(
    private val thinking: java.time.Duration = java.time.Duration.ofMillis(THINKING_MILLIS),
) : CategoryModel {
    override val name: String = "keyword-model"

    override fun categorise(
        product: Product,
        categories: List<String>,
    ): String {
        // Simulates the model's latency; nothing in the test waits on it.
        Thread.sleep(thinking.toMillis())
        val text = "${product.name} ${product.description}".lowercase()
        return KEYWORDS.entries.firstOrNull { (keyword, _) -> keyword in text }?.value ?: "Unknown"
    }

    private companion object {
        const val THINKING_MILLIS = 30L
        val KEYWORDS =
            linkedMapOf(
                "cooking" to "Kitchen",
                "beds and lawns" to "Garden",
                "rechargeable battery" to "Electronics",
                "children" to "Toys",
                "hardcover" to "Books",
                "training" to "Sports",
                "sizes" to "Clothing",
                "desk" to "Office",
            )
    }
}
