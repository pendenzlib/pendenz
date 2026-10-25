import io.gitlab.arturbosch.detekt.Detekt
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.gradle.accessors.dm.LibrariesForLibs
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jlleitschuh.gradle.ktlint")
    id("io.gitlab.arturbosch.detekt")
    id("org.jetbrains.kotlinx.kover")
}

val libs = the<LibrariesForLibs>()

kotlin {
    // Bytecode for 17: runs on every JVM from 17 on. CI runs the tests on 21 and 25 as well, see below.
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

dependencies {
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// The JDK the tests run on, 17 from the toolchain unless -Ppendenz.testJdk names another.
// CI sets it per matrix entry (ADR 0003); Gradle, the compiler and detekt stay on 17.
val testJdk = providers.gradleProperty("pendenz.testJdk").orNull
val testLauncher =
    testJdk?.let { version ->
        the<JavaToolchainService>().launcherFor { languageVersion.set(JavaLanguageVersion.of(version)) }
    }

tasks.withType<Test>().configureEach {
    testLauncher?.let { javaLauncher.set(it) }
    useJUnitPlatform()
    testLogging {
        events = setOf(TestLogEvent.FAILED, TestLogEvent.SKIPPED)
        exceptionFormat = TestExceptionFormat.FULL
    }
}

ktlint {
    version.set(libs.versions.ktlint.asProvider())
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.files("config/detekt/detekt.yml"))
}

tasks.withType<Detekt>().configureEach {
    jvmTarget = "17"
}

// The Java tests prove the API usable from Java; a warning there is as much a finding as in Kotlin
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

