// Applies the kotlinx-serialization compiler plugin from the build-logic classpath,
// so modules can use it without naming a version.
plugins {
    id("org.jetbrains.kotlin.plugin.serialization")
}
