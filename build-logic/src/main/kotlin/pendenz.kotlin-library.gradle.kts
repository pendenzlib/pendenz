import org.gradle.accessors.dm.LibrariesForLibs

plugins {
    id("pendenz.kotlin-common")
    id("org.jetbrains.dokka")
    id("com.vanniktech.maven.publish")
}

val libs = the<LibrariesForLibs>()

kotlin {
    // Every public signature must be declared public explicitly: no accidental API.
    explicitApi()
    // The standard library the published metadata names: the floor, not the compiler's own,
    // or an application on that Kotlin would resolve a standard library it cannot read (ADR 0024).
    coreLibrariesVersion = libs.versions.kotlin.floor.get()
}

// The language and API level of the oldest Kotlin a supported framework ships, so its
// compiler can read these classes; the compiler itself stays the newest (ADR 0024).
tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileKotlin") {
    compilerOptions {
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
        // Interface defaults as JVM default methods only, without DefaultImpls classes: nothing compiled
        // against an older Pendenz exists yet, and Java implementers see real default methods
        jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.NO_COMPATIBILITY)
    }
}

// The version a running application can read from the jar, as the MCP server reports it to its clients
tasks.named<Jar>("jar") {
    manifest {
        attributes("Implementation-Title" to project.name, "Implementation-Version" to project.version)
    }
}

mavenPublishing {
    publishToMavenCentral()
    if (providers.environmentVariable("ORG_GRADLE_PROJECT_signingInMemoryKey").isPresent) {
        signAllPublications()
    }
    coordinates(
        groupId = project.group.toString(),
        artifactId = project.name,
        version = project.version.toString(),
    )
    pom {
        name.set(project.name)
        description.set("A task queue for the JVM whose tasks live in tables you own.")
        url.set("https://github.com/D4nielLa/pendenz")
        licenses {
            license {
                name.set("Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        developers {
            developer {
                id.set("D4nielLa")
                name.set("Daniel Laskow")
            }
        }
        scm {
            url.set("https://github.com/D4nielLa/pendenz")
            connection.set("scm:git:https://github.com/D4nielLa/pendenz.git")
            developerConnection.set("scm:git:ssh://git@github.com/D4nielLa/pendenz.git")
        }
    }
}
