plugins {
    id("pendenz.kotlin-library")
    // Only the tests use @Serializable payloads; the module itself does not.
    id("pendenz.kotlin-serialization")
}

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
