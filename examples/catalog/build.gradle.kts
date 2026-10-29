plugins {
    id("pendenz.kotlin-application")
    id("pendenz.kotlin-serialization")
}

dependencies {
    implementation(project(":pendenz-core"))
    implementation(project(":examples:support"))
    implementation(project(":pendenz-micrometer"))
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.postgresql)
    runtimeOnly(libs.postgresql)
    runtimeOnly(libs.logback.classic)

    testImplementation(testFixtures(project(":pendenz-core")))
}

application {
    mainClass.set("io.github.pendenzlib.examples.catalog.CatalogMainKt")
}
