package compatibility

import io.github.d4niella.pendenz.Pendenz
import io.github.d4niella.pendenz.TaskType
import io.github.d4niella.pendenz.WorkerOptions
import io.github.d4niella.pendenz.jackson.JacksonCodec
import io.github.d4niella.pendenz.javaapi.PendenzJava
import io.github.d4niella.pendenz.mcp.JsonSchema
import io.github.d4niella.pendenz.mcp.PendenzMcp
import io.github.d4niella.pendenz.micrometer.PendenzMetrics
import io.github.d4niella.pendenz.notify.PostgresNotify
import io.github.d4niella.pendenz.schema.IdType
import io.github.d4niella.pendenz.schema.PostgresDdl
import io.github.d4niella.pendenz.schema.TaskTable
import io.github.d4niella.pendenz.spring.SpringConnectionProvider
import io.github.d4niella.pendenz.spring.boot.TaskRegistration
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.springframework.transaction.PlatformTransactionManager
import java.time.Duration
import javax.sql.DataSource

// What a Kotlin application on an older compiler writes: the builder DSL, a reified inline
// function, suspend calls, the Java facade, and a type of every other module.

val greet = TaskType.json<String>("greet")

fun queue(): Pendenz<Long> =
    Pendenz.create {
        inMemory()
        listener(PendenzMetrics(SimpleMeterRegistry()))
        register(greet) { context, name -> println("${context.id}: hello $name") }
    }

fun registration(): TaskRegistration<Long> = TaskRegistration.of(greet) { _, name -> println(name) }

val invoice = TaskType("send-invoice", JacksonCodec.of(String::class.java))

fun listener(dataSource: DataSource): PostgresNotify = PostgresNotify.start(dataSource, TaskTable(name = "greeting_task"))

fun server(pendenz: Pendenz<Long>): PendenzMcp<Long> =
    PendenzMcp.create(pendenz, IdType.BIGINT, "agent") { tool(greet, "Greets.", JsonSchema.of<String>()) }

fun provider(
    dataSource: DataSource,
    transactions: PlatformTransactionManager,
): SpringConnectionProvider = SpringConnectionProvider(dataSource, transactions)

fun main() {
    val pendenz = queue()
    runBlocking {
        pendenz.enqueue(greet, "Ada")
        pendenz.runPending(WorkerOptions(pollInterval = Duration.ofMillis(10)))
    }
    PendenzJava.wrap(pendenz).use { println(it.backlog()) }
    println(PostgresDdl.createTable(TaskTable(name = "greeting_task"), greet))
}
