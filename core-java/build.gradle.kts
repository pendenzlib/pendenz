plugins {
    id("pendenz.kotlin-library")
    java
}

dependencies {
    api(project(":pendenz-core"))

    testImplementation(testFixtures(project(":pendenz-core")))
    testImplementation(libs.awaitility)
    testRuntimeOnly(libs.logback.classic)
}
