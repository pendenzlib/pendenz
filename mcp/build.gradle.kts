plugins {
    id("pendenz.kotlin-library")
    id("pendenz.kotlin-serialization")
    java
}

dependencies {
    api(project(":pendenz-core"))
    implementation(libs.mcp.core)
    implementation(libs.mcp.json.jackson3)
    implementation(libs.slf4j.api)
    compileOnly(libs.jakarta.servlet.api)

    testImplementation(testFixtures(project(":pendenz-core")))
    testImplementation(project(":pendenz-core-java"))
    testImplementation(libs.awaitility)
    testImplementation(libs.awaitility.kotlin)
    testImplementation(libs.jetty.ee10.servlet)
    testImplementation(libs.mcp.core)
    testRuntimeOnly(libs.logback.classic)
}
