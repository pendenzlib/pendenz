plugins {
    id("pendenz.kotlin-application")
}

dependencies {
    implementation(project(":pendenz-core"))
    implementation(project(":examples:support"))
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.postgresql)
    runtimeOnly(libs.postgresql)
    runtimeOnly(libs.logback.classic)

    testImplementation(testFixtures(project(":pendenz-core")))
}

application {
    mainClass.set("io.github.d4niella.pendenz.examples.demo.MainKt")
}
