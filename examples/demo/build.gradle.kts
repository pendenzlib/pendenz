plugins {
    id("pendenz.kotlin-application")
}

dependencies {
    implementation(project(":pendenz-core"))
    runtimeOnly(libs.logback.classic)
}

application {
    mainClass.set("io.github.d4niella.pendenz.examples.demo.MainKt")
}
