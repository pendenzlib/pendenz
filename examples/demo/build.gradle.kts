plugins {
    id("pendenz.kotlin-application")
}

dependencies {
    implementation(project(":pendenz-core"))
    implementation(libs.hikaricp)
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.postgresql)
    runtimeOnly(libs.postgresql)
    runtimeOnly(libs.logback.classic)
    // Only so that the demo runs without a database at hand; a real service has one.
    implementation(libs.embedded.postgres)

    testImplementation(testFixtures(project(":pendenz-core")))
}

application {
    mainClass.set("io.github.d4niella.pendenz.examples.demo.MainKt")
}
