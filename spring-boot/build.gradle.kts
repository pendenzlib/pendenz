plugins {
    id("pendenz.kotlin-library")
    id("pendenz.kotlin-serialization")
    java
}

description = "Spring Boot 4 starter for Pendenz: the queue, its worker and its checks from configuration."

dependencies {
    api(project(":pendenz-spring"))
    api(project(":pendenz-core-java"))
    api(libs.spring.boot.starter.jdbc)
    // Configured when the application has them on its classpath (ADR 0023).
    compileOnly(project(":pendenz-micrometer"))
    compileOnly(project(":pendenz-postgres-notify"))

    testImplementation(testFixtures(project(":pendenz-core")))
    testImplementation(project(":pendenz-micrometer"))
    testImplementation(project(":pendenz-postgres-notify"))
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.configuration.metadata)
    testImplementation(libs.awaitility)
    testImplementation(libs.awaitility.kotlin)
    testImplementation(libs.postgresql)
}
