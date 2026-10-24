plugins {
    alias(libs.plugins.kover)
    alias(libs.plugins.bcv)
}

// Aggregierte Testabdeckung über alle Bibliotheksmodule.
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

// Jede Änderung an öffentlichen Signaturen landet als Diff in api/*.api.
apiValidation {
    ignoredProjects += listOf("demo", "helpdesk", "catalog")
}
