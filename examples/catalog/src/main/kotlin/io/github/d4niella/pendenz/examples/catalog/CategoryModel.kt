package io.github.d4niella.pendenz.examples.catalog

/** A language model that names a product's category. */
interface CategoryModel {
    /** Which model answers, recorded as provenance with every result. */
    val name: String

    /** The model's answer for [product], one of [categories] if it behaves. */
    fun categorise(
        product: Product,
        categories: List<String>,
    ): String
}
