package compatibility

import io.github.pendenzlib.Pendenz
import io.github.pendenzlib.TaskType
import io.github.pendenzlib.WorkerOptions
import io.github.pendenzlib.jackson.JacksonCodec
import io.github.pendenzlib.javaapi.PendenzJava
import io.github.pendenzlib.mcp.JsonSchema
import io.github.pendenzlib.mcp.PendenzMcp
import io.github.pendenzlib.micrometer.PendenzMetrics
import io.github.pendenzlib.notify.PostgresNotify
import io.github.pendenzlib.schema.IdType
import io.github.pendenzlib.schema.PostgresDdl
import io.github.pendenzlib.schema.TaskTable
import io.github.pendenzlib.spring.SpringConnectionProvider
import io.github.pendenzlib.spring.boot.TaskRegistration
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
