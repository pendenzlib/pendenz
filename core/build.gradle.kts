plugins {
    id("pendenz.kotlin-library")
    id("pendenz.kotlin-serialization")
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    implementation(libs.slf4j.api)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.awaitility)
    testImplementation(libs.awaitility.kotlin)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.embedded.postgres)
    testRuntimeOnly(libs.postgresql)
    testRuntimeOnly(libs.logback.classic)
}
