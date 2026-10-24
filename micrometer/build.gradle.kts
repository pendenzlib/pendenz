plugins {
    id("pendenz.kotlin-library")
    id("pendenz.kotlin-serialization")
    java
}

dependencies {
    api(project(":pendenz-core"))
    api(libs.micrometer.core)
    implementation(libs.slf4j.api)

    testImplementation(testFixtures(project(":pendenz-core")))
    testImplementation(project(":pendenz-core-java"))
    testImplementation(libs.awaitility)
    testImplementation(libs.awaitility.kotlin)
    testRuntimeOnly(libs.logback.classic)
}
