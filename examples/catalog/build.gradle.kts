plugins {
    id("pendenz.kotlin-application")
    id("pendenz.kotlin-serialization")
}

dependencies {
    implementation(project(":pendenz-core"))
    implementation(project(":pendenz-micrometer"))
    implementation(libs.hikaricp)
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.postgresql)
    runtimeOnly(libs.postgresql)
    runtimeOnly(libs.logback.classic)
    // Only so that the example runs without a database at hand; a real service has one.
    implementation(libs.embedded.postgres)

    testImplementation(testFixtures(project(":pendenz-core")))
}

application {
    mainClass.set("io.github.d4niella.pendenz.examples.catalog.CatalogMainKt")
}
