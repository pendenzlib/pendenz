plugins {
    id("pendenz.kotlin-application")
    id("pendenz.kotlin-serialization")
}

dependencies {
    implementation(project(":pendenz-core"))
    implementation(project(":pendenz-mcp"))
    // The agent's side of MCP: the SDK's client, which starts the helpdesk as a child process.
    implementation(libs.mcp.core)
    implementation(libs.mcp.json.jackson3)
    implementation(libs.hikaricp)
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.postgresql)
    runtimeOnly(libs.postgresql)
    runtimeOnly(libs.logback.classic)
    // Only so that the example runs without a database at hand; a real service has one.
    implementation(libs.embedded.postgres)

    testImplementation(testFixtures(project(":pendenz-core")))
    testImplementation(libs.awaitility)
    testImplementation(libs.awaitility.kotlin)
}

application {
    mainClass.set("io.github.d4niella.pendenz.examples.helpdesk.agent.AgentMainKt")
}
