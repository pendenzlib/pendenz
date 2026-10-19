plugins {
    alias(libs.plugins.kover)
    alias(libs.plugins.bcv)
}

// Aggregierte Testabdeckung über alle Bibliotheksmodule.
dependencies {
    kover(project(":pendenz-core"))
    kover(project(":pendenz-core-java"))
    kover(project(":pendenz-serialization-jackson"))
}


// Jede Änderung an öffentlichen Signaturen landet als Diff in api/*.api.
apiValidation {
    ignoredProjects += listOf("demo")
}
