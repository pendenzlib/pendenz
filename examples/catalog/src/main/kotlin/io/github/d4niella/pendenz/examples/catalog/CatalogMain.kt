package io.github.d4niella.pendenz.examples.catalog

import io.github.d4niella.pendenz.examples.support.DatabaseAccess
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("catalog")

/**
 * The demo cycle: classifies a generated catalogue with a language model,
 * with injected faults, a pause and an operator who repairs with SQL.
 *
 * The model is a local Ollama by default; `CATALOG_MODEL_URL`,
 * `CATALOG_MODEL`, `CATALOG_API_KEY` and `CATALOG_MODEL_TIMEOUT` point it
 * elsewhere. `CATALOG_PRODUCTS` sets the catalogue's size, 60 by default.
 * The database is embedded unless `PENDENZ_JDBC_URL` names one.
 */
fun main() {
    val database = DatabaseAccess.fromEnvironment() ?: DatabaseAccess.embedded()
    val products = System.getenv("CATALOG_PRODUCTS")?.toInt() ?: RunSettings.DEFAULT_PRODUCTS
    val report = runCatalog(database, OpenAiCompatibleModel.fromEnvironment(), RunSettings(products = products))
    log.info("{}", report)
}
