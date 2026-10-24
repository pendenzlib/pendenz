package compatibility

import io.github.d4niella.pendenz.Pendenz
import io.github.d4niella.pendenz.TaskType
import io.github.d4niella.pendenz.WorkerOptions
import io.github.d4niella.pendenz.javaapi.PendenzJava
import io.github.d4niella.pendenz.micrometer.PendenzMetrics
import io.github.d4niella.pendenz.schema.PostgresDdl
import io.github.d4niella.pendenz.schema.TaskTable
import io.github.d4niella.pendenz.spring.boot.TaskRegistration
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import java.time.Duration

// What a Kotlin application on an older compiler writes: the builder DSL, a reified inline
// function, suspend calls, the Java facade, a module and a starter type.

val greet = TaskType.json<String>("greet")

fun queue(): Pendenz<Long> =
    Pendenz.create {
        inMemory()
        listener(PendenzMetrics(SimpleMeterRegistry()))
        register(greet) { context, name -> println("${context.id}: hello $name") }
    }

fun registration(): TaskRegistration<Long> = TaskRegistration.of(greet) { _, name -> println(name) }

fun main() {
    val pendenz = queue()
    runBlocking {
        pendenz.enqueue(greet, "Ada")
        pendenz.runPending(WorkerOptions(pollInterval = Duration.ofMillis(10)))
    }
    PendenzJava.wrap(pendenz).use { println(it.backlog()) }
    println(PostgresDdl.createTable(TaskTable(name = "greeting_task"), greet))
}
