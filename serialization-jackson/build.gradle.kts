plugins {
    id("pendenz.kotlin-library")
    java
}

description = "Jackson 3 payload codec and payload upgrades for Pendenz."

dependencies {
    api(project(":pendenz-core"))
    api(libs.jackson.databind)

    testImplementation(project(":pendenz-core-java"))
    testImplementation(testFixtures(project(":pendenz-core")))
    testRuntimeOnly(libs.logback.classic)
}
