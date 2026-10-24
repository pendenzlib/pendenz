pluginManagement {
    includeBuild("build-logic")
}

plugins {
    // Lädt bei Bedarf ein JDK 17 für die Toolchain herunter, falls lokal keines liegt.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "pendenz"

include(":core", ":core-java", ":serialization-jackson", ":postgres-notify", ":mcp", ":micrometer", ":examples:demo", ":examples:helpdesk")

project(":core").name = "pendenz-core"
project(":core-java").name = "pendenz-core-java"
project(":serialization-jackson").name = "pendenz-serialization-jackson"
project(":postgres-notify").name = "pendenz-postgres-notify"
project(":mcp").name = "pendenz-mcp"
project(":micrometer").name = "pendenz-micrometer"
