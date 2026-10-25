// Compiles a small consumer with the oldest Kotlin a supported framework ships, against the
// published modules as an application gets them: proof that their classes are readable by
// that compiler (ADR 0024). Not published.
plugins {
    java
}

val kotlinFloorCompiler: Configuration = configurations.create("kotlinFloorCompiler")

dependencies {
    // Every published module: the compiler reads the metadata only of classes the consumer resolves
    implementation(project(":pendenz-core"))
    implementation(project(":pendenz-core-java"))
    implementation(project(":pendenz-serialization-jackson"))
    implementation(project(":pendenz-postgres-notify"))
    implementation(project(":pendenz-mcp"))
    implementation(project(":pendenz-micrometer"))
    implementation(project(":pendenz-spring"))
    implementation(project(":pendenz-spring-boot-starter"))
    kotlinFloorCompiler(libs.kotlin.compiler.floor)
}

val consumer = layout.projectDirectory.dir("src/kotlin22")

val compileWithKotlinFloor =
    tasks.register<JavaExec>("compileWithKotlinFloor") {
        description = "Compiles the consumer with Kotlin ${libs.versions.kotlin.floor.get()} against the modules."
        group = "verification"
        val output = layout.buildDirectory.dir("kotlin-floor")
        val modules: FileCollection = configurations.runtimeClasspath.get()
        val sources = consumer.asFile.path
        inputs.dir(consumer)
        inputs.files(modules)
        outputs.dir(output)
        classpath = kotlinFloorCompiler
        mainClass.set("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler")
        argumentProviders.add {
            listOf(
                "-no-stdlib",
                "-no-reflect",
                "-jvm-target",
                "17",
                "-Werror",
                "-classpath",
                modules.asPath,
                "-d",
                output.get().asFile.path,
                sources,
            )
        }
    }

tasks.check { dependsOn(compileWithKotlinFloor) }
