plugins {
    id("pendenz.kotlin-library")
    // Only the tests use @Serializable payloads; the module itself does not.
    id("pendenz.kotlin-serialization")
}

description = "Wakes Pendenz workers through PostgreSQL LISTEN/NOTIFY instead of polling."

dependencies {
    api(project(":pendenz-core"))
    implementation(libs.postgresql)
    implementation(libs.slf4j.api)

    testImplementation(testFixtures(project(":pendenz-core")))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.awaitility)
    testImplementation(libs.awaitility.kotlin)
    testRuntimeOnly(libs.logback.classic)
}
