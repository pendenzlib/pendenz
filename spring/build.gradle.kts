plugins {
    id("pendenz.kotlin-library")
    id("pendenz.kotlin-serialization")
    java
}

dependencies {
    api(project(":pendenz-core"))
    api(libs.spring.jdbc)
    // Only for recognising a JpaTransactionManager; applications without JPA do not need it.
    compileOnly(libs.spring.orm)
    implementation(libs.slf4j.api)

    testImplementation(testFixtures(project(":pendenz-core")))
    testImplementation(project(":pendenz-core-java"))
    testImplementation(libs.spring.orm)
    testImplementation(libs.spring.context)
    testImplementation(libs.awaitility)
    testImplementation(libs.awaitility.kotlin)
    testImplementation(libs.hibernate.core)
    testRuntimeOnly(libs.logback.classic)
}

// The same tests once more on Spring Framework 7 with the Hibernate line it runs with,
// so the module compiled against 6.2 is shown to work on both (ADR 0023).
val spring7TestRuntimeClasspath: Configuration =
    configurations.testRuntimeClasspath.get().copyRecursive().apply {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.springframework") useVersion(libs.versions.spring7.get())
            if (requested.group == "org.hibernate.orm") useVersion(libs.versions.hibernate7.get())
        }
    }

val testOnSpring7 =
    tasks.register<Test>("testOnSpring7") {
        description = "Runs the tests on Spring Framework 7."
        group = "verification"
        testClassesDirs =
            sourceSets.test
                .get()
                .output.classesDirs
        classpath = sourceSets.test.get().output + sourceSets.main.get().output + spring7TestRuntimeClasspath
    }

tasks.check { dependsOn(testOnSpring7) }
