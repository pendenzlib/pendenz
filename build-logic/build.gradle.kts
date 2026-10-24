plugins {
    `kotlin-dsl`
}

dependencies {
    // Macht den typsicheren Version Catalog in Precompiled-Script-Plugins verfügbar.
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
