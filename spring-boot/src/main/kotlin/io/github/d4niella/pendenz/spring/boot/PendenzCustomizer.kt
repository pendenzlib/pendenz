package io.github.d4niella.pendenz.spring.boot

import io.github.d4niella.pendenz.Pendenz

/**
 * Receives the queue's builder before the starter builds it, for anything
 * beyond handlers: a clock, cycles, resource limits. Declared as a bean
 * with the queue's id type, like [TaskRegistration].
 */
public fun interface PendenzCustomizer<ID : Any> {
    public fun customize(builder: Pendenz.Builder<ID>)
}
