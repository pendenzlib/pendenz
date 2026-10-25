plugins {
    id("pendenz.kotlin-library")
    id("pendenz.kotlin-serialization")
    java
}

description = "Exposes Pendenz task types as MCP tools for AI agents."

// The version the server reports to its clients, written at build time: a manifest is not read when a
// class of the same package was loaded from elsewhere first, as in the tests
val versionResource =
    tasks.register("versionResource") {
        val output = layout.buildDirectory.dir("generated/version")
        val version = project.version.toString()
        inputs.property("version", version)
        outputs.dir(output)
        doLast {
            val file = output.get().file("io/github/d4niella/pendenz/mcp/version.properties").asFile
            file.parentFile.mkdirs()
            file.writeText("version=$version\n")
        }
    }
sourceSets.main {
    resources.srcDir(versionResource)
}

tasks.test {
    systemProperty("pendenz.version", project.version.toString())
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
