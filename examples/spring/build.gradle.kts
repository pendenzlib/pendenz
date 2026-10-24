plugins {
    id("pendenz.spring-boot-application")
}

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
