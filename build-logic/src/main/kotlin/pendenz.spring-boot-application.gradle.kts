// A Spring Boot service as Spring Initializr sets one up: Boot's plugin with its
// dependency management, and the Kotlin plugin that opens classes for Spring's proxies.
plugins {
    id("pendenz.kotlin-common")
    id("org.jetbrains.kotlin.plugin.spring")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

// Boot's dependency management rewrites every configuration, also the Kotlin compiler
// that detekt and ktlint bring along; give those back the versions they ask for.
configurations
    .matching { it.name.startsWith("detekt") || it.name.startsWith("ktlint") }
    .configureEach {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.jetbrains.kotlin") useVersion(requested.version!!)
        }
    }
