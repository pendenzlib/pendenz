import org.gradle.accessors.dm.LibrariesForLibs

// The module is compiled against the floor of kotlinx-coroutines and kotlinx-serialization
// (ADR 0024); this runs its tests once more with the newest releases of both.
val libs = the<LibrariesForLibs>()
val coroutinesLatest = libs.versions.coroutines.latest.get()
val serializationLatest = libs.versions.serialization.latest.get()

// Copied lazily: when this plugin is applied, the module has not declared its dependencies yet.
val latestTestRuntimeClasspath =
    provider {
        configurations.getByName("testRuntimeClasspath").copyRecursive().apply {
            resolutionStrategy.eachDependency {
                if (requested.name.startsWith("kotlinx-coroutines")) useVersion(coroutinesLatest)
                if (requested.name.startsWith("kotlinx-serialization")) useVersion(serializationLatest)
            }
        }
    }

val sourceSets = the<SourceSetContainer>()

val testOnLatestLibraries =
    tasks.register<Test>("testOnLatestLibraries") {
        description = "Runs the tests with the newest kotlinx-coroutines and kotlinx-serialization."
        group = "verification"
        testClassesDirs = sourceSets["test"].output.classesDirs
        classpath = sourceSets["test"].output + sourceSets["main"].output + files(latestTestRuntimeClasspath)
        // The copy does not carry the build dependencies, such as the jars of other projects.
        dependsOn(configurations.getByName("testRuntimeClasspath"))
    }

tasks.named("check") { dependsOn(testOnLatestLibraries) }
