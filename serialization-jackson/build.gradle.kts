plugins {
    id("pendenz.kotlin-library")
    java
}

dependencies {
    api(project(":pendenz-core"))
    api(libs.jackson.databind)

    testImplementation(project(":pendenz-core-java"))
    testImplementation(testFixtures(project(":pendenz-core")))
    testRuntimeOnly(libs.logback.classic)
}
