plugins {
    id("pendenz.kotlin-library")
    id("pendenz.kotlin-serialization")
    `java-test-fixtures`
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    implementation(libs.slf4j.api)

    // The PostgreSQL test helper, shared with the tests of the other modules.
    testFixturesImplementation(libs.junit.jupiter)
    testFixturesImplementation(libs.testcontainers.postgresql)
    testFixturesImplementation(libs.embedded.postgres)
    testFixturesImplementation(libs.postgresql)
    testFixturesImplementation(libs.slf4j.api)
    testFixturesImplementation(libs.kotlinx.coroutines.core)
    testFixturesImplementation(libs.awaitility)
    testFixturesImplementation(libs.awaitility.kotlin)
    testFixturesImplementation(libs.assertj.core)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.awaitility)
    testImplementation(libs.awaitility.kotlin)
    testRuntimeOnly(libs.logback.classic)
}

// The fixtures are for this repository's tests, not for users.
val javaComponent = components["java"] as AdhocComponentWithVariants
javaComponent.withVariantsFromConfiguration(configurations["testFixturesApiElements"]) { skip() }
javaComponent.withVariantsFromConfiguration(configurations["testFixturesRuntimeElements"]) { skip() }
