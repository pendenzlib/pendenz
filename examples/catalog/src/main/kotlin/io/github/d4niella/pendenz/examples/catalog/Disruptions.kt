package io.github.d4niella.pendenz.examples.catalog

/**
 * Faults the demo injects on purpose, so that every run shows the same
 * failures whatever the model does. They hit the first attempt only, so a
 * retry or an operator's restart gets through:
 *
 * - every [overloadedEvery]th product finds the model overloaded and is retried;
 * - every [garbledEvery]th product gets a garbled answer, which validation refuses.
 */
class Disruptions(
    private val model: CategoryModel,
    private val overloadedEvery: Long = OVERLOADED_EVERY,
    private val garbledEvery: Long = GARBLED_EVERY,
) {
    /** The model's category for [product] on [attempt], unless this attempt is one to disrupt. */
    fun categorise(
        product: Product,
        attempt: Int,
    ): String {
        val first = attempt == 1
        if (first && product.id % overloadedEvery == 0L) throw ModelOverloaded()
        if (first && product.id % garbledEvery == 0L) return GARBLED
        return model.categorise(product, Catalog.CATEGORIES)
    }

    /** What the model server says when it has too much to do; the retry policy deals with it. */
    class ModelOverloaded : RuntimeException("the model server is overloaded (injected)")

    companion object {
        private const val OVERLOADED_EVERY = 11L
        private const val GARBLED_EVERY = 13L

        /** No shop has this category. */
        const val GARBLED = "Miscellaneous (injected)"
    }
}
