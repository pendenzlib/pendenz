plugins {
    id("pendenz.kotlin-library")
    java
    id("pendenz.latest-libraries-test")
}

description = "The Java facade of Pendenz: blocking calls, builders and handlers without coroutines."

dependencies {
    api(project(":pendenz-core"))

    testImplementation(testFixtures(project(":pendenz-core")))
    testImplementation(libs.awaitility)
    testRuntimeOnly(libs.logback.classic)
}
