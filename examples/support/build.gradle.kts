// What the examples share: database access and the call of an OpenAI-compatible model. Not published.
plugins {
    id("pendenz.kotlin-common")
    id("pendenz.kotlin-serialization")
}

dependencies {
    api(libs.hikaricp)
    api(libs.kotlinx.serialization.json)
    implementation(libs.flyway.core)
    // Only so that the examples run without a database at hand; a real service has one.
    implementation(libs.embedded.postgres)
    // The driver embedded-postgres brings along is older and has a known vulnerability
    runtimeOnly(libs.postgresql)
}
