plugins {
    `kotlin-dsl`
}

dependencies {
    // Makes the type-safe version catalog available in precompiled script plugins.
    implementation(files(libs.javaClass.superclass.protectionDomain.codeSource.location))
    implementation(libs.plugin.kotlin.jvm)
    implementation(libs.plugin.kotlin.serialization)
    implementation(libs.plugin.ktlint)
    implementation(libs.plugin.detekt)
    implementation(libs.plugin.kover)
    implementation(libs.plugin.dokka)
    implementation(libs.plugin.maven.publish)
    implementation(libs.plugin.kotlin.allopen)
    implementation(libs.plugin.spring.boot)
    implementation(libs.plugin.dependency.management)
}
