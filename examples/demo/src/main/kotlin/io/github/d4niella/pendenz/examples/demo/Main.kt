package io.github.d4niella.pendenz.examples.demo

import io.github.d4niella.pendenz.TaskStatus
import io.github.d4niella.pendenz.TaskStatusTransitions

/**
 * Placeholder until the first end-to-end feature exists. Prints the status model
 * as a Markdown table so the docs can be regenerated from the single source of truth.
 */
fun main() {
    println("| From | Kind | May move to |")
    println("|---|---|---|")
    TaskStatus.entries.forEach { status ->
        val targets = TaskStatusTransitions.targetsFrom(status).joinToString(", ").ifEmpty { "–" }
        println("| ${status.name} | ${status.kind} | $targets |")
    }
}
