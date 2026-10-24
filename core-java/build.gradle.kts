plugins {
    id("pendenz.kotlin-library")
    java
    id("pendenz.latest-libraries-test")
}

dependencies {
    api(project(":pendenz-core"))

    testImplementation(testFixtures(project(":pendenz-core")))
    testImplementation(libs.awaitility)
    testRuntimeOnly(libs.logback.classic)
}
