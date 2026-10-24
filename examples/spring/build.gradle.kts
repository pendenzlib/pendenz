plugins {
    id("pendenz.spring-boot-application")
}

// Spring Boot 4.0 pins kotlinx-coroutines 1.10 and kotlinx-serialization 1.9; Pendenz is built
// against 1.11 and fails with NoSuchMethodError on the older ones. Raise both, as the README says.
extra["kotlin-coroutines.version"] = libs.versions.coroutines.get()
extra["kotlin-serialization.version"] = libs.versions.serialization.get()

dependencies {
    implementation(project(":pendenz-spring-boot-starter"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
    // Only so that the example runs without a database at hand; a real service has one.
    implementation(libs.embedded.postgres)

    testImplementation(testFixtures(project(":pendenz-core")))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(libs.awaitility.kotlin)
}
