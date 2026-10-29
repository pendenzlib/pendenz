package io.github.pendenzlib.spring.boot

import org.springframework.beans.factory.BeanCreationException
import org.springframework.beans.factory.BeanCurrentlyInCreationException
import org.springframework.boot.diagnostics.AbstractFailureAnalyzer
import org.springframework.boot.diagnostics.FailureAnalysis
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order

/**
 * A bean the queue is built from, a `TaskRegistration`, `Permissions`,
 * `TaskListener` or `PendenzCustomizer`, could not be created because one
 * of its dependencies needs the queue (ADR 0025). Spring's own exception
 * stays the cause.
 */
internal class QueueCycleException(
    val bean: String,
    val kind: String,
    cause: Throwable,
) : IllegalStateException(
        "the $kind bean '$bean' depends on a bean that needs the Pendenz queue, but the queue is built from it, " +
            "so neither can be created first. $WAYS_OUT",
        cause,
    ) {
    companion object {
        const val WAYS_OUT =
            "Inject the dependency as an ObjectProvider, or mark the parameter @Lazy. A TaskRegistration can " +
                "name the handler's bean type instead, TaskRegistration.of(type, MyHandler::class.java), so the " +
                "handler is looked up when it first runs. See ADR 0025."

        /**
         * This exception when [failure] is a circle through one of the
         * beans the queue is built from, given as bean name to kind; null
         * otherwise.
         */
        fun from(
            failure: Throwable,
            queueBeans: Map<String, String>,
        ): QueueCycleException? {
            val chain = generateSequence(failure) { it.cause }.toList()
            if (chain.none { it is BeanCurrentlyInCreationException }) return null
            val bean = chain.filterIsInstance<BeanCreationException>().firstOrNull { it.beanName in queueBeans }
            return bean?.beanName?.let { QueueCycleException(it, queueBeans.getValue(it), failure) }
        }
    }
}

/**
 * Puts the explanation of a [QueueCycleException] in front of Spring
 * Boot's general report on circular references, which would otherwise
 * win, since the cycle exception is in the chain too.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
internal class QueueCycleFailureAnalyzer : AbstractFailureAnalyzer<QueueCycleException>() {
    override fun analyze(
        rootFailure: Throwable,
        cause: QueueCycleException,
    ): FailureAnalysis =
        FailureAnalysis(
            "The ${cause.kind} bean '${cause.bean}' depends on a bean that needs the Pendenz queue, " +
                "and the queue is built from it.",
            QueueCycleException.WAYS_OUT,
            cause,
        )
}
