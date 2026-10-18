plugins {
    id("pendenz.kotlin-common")
    id("org.jetbrains.dokka")
    id("com.vanniktech.maven.publish")
}

kotlin {
    // Jede öffentliche Signatur muss ausdrücklich public sein: keine versehentliche API.
    explicitApi()
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
