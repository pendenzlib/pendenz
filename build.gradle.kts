plugins {
    alias(libs.plugins.kover)
    alias(libs.plugins.bcv)
}

// Test coverage aggregated over all library modules.
dependencies {
    kover(project(":pendenz-core"))
    kover(project(":pendenz-core-java"))
    kover(project(":pendenz-serialization-jackson"))
    kover(project(":pendenz-postgres-notify"))
    kover(project(":pendenz-mcp"))
    kover(project(":pendenz-micrometer"))
    kover(project(":pendenz-spring"))
    kover(project(":pendenz-spring-boot-starter"))
}

// The build fails below this line coverage; the report shows where.
kover {
    reports {
        verify {
            rule {
                minBound(85)
            }
        }
    }
}

// Every change to a public signature shows up as a diff in api/*.api.
apiValidation {
    ignoredProjects += listOf("demo", "helpdesk", "catalog", "spring", "compatibility")
}
