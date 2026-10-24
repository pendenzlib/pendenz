package io.github.d4niella.pendenz.spring.boot

import org.springframework.beans.factory.BeanCreationException
import org.springframework.beans.factory.BeanCurrentlyInCreationException
import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.boot.diagnostics.AbstractFailureAnalyzer
import org.springframework.boot.diagnostics.FailureAnalysis
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order

/**
 * A `TaskRegistration` bean could not be created because one of its
 * dependencies needs the queue, which is built from the registrations
 * (ADR 0025). Spring's own exception stays the cause.
 */
internal class HandlerCycleException(
    val registration: String,
    cause: Throwable,
) : IllegalStateException(
        "the TaskRegistration bean '$registration' depends on a bean that needs the Pendenz queue, but the queue " +
            "is built from its registrations, so neither can be created first. $WAYS_OUT",
        cause,
    ) {
    companion object {
        const val WAYS_OUT =
            "Name the handler's bean type instead, TaskRegistration.of(type, MyHandler::class.java), so the " +
                "handler is looked up when it first runs; or inject the dependency as an ObjectProvider; or " +
                "mark the parameter @Lazy. See ADR 0025."

        /** This exception when [failure] is a circle through one of the [registrations], null otherwise. */
        fun from(
            failure: Throwable,
            registrations: Set<String>,
        ): HandlerCycleException? {
            val chain = generateSequence(failure) { it.cause }.toList()
            if (chain.none { it is BeanCurrentlyInCreationException }) return null
            val registration =
                chain.filterIsInstance<BeanCreationException>().firstOrNull { it.beanName in registrations }
            return registration?.let { HandlerCycleException(checkNotNull(it.beanName), failure) }
        }
    }
}

/**
 * Puts the explanation of a [HandlerCycleException] in front of Spring
 * Boot's general report on circular references, which would otherwise
 * win, since the cycle exception is in the chain too.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
internal class HandlerCycleFailureAnalyzer : AbstractFailureAnalyzer<HandlerCycleException>() {
    override fun analyze(
        rootFailure: Throwable,
        cause: HandlerCycleException,
    ): FailureAnalysis =
        FailureAnalysis(
            "The TaskRegistration bean '${cause.registration}' depends on a bean that needs the Pendenz queue, " +
                "and the queue is built from its registrations.",
            HandlerCycleException.WAYS_OUT,
            cause,
        )
}

/**
 * Checks after all singletons that every handler named by its bean type
 * has exactly one bean, so a mistake fails startup and not the first run.
 */
internal class HandlerBeanCheck(
    private val beans: ListableBeanFactory,
) : SmartInitializingSingleton {
    override fun afterSingletonsInstantiated() {
        beans.getBeansOfType(TaskRegistration::class.java).forEach { (name, registration) ->
            val type = registration.handlerType ?: return@forEach
            val found = beans.getBeanNamesForType(type)
            check(found.size == 1) {
                "the TaskRegistration bean '$name' names the handler type ${type.name} for the task type " +
                    "'${registration.typeName}', but there are ${found.size} beans of it ${found.toList()}; " +
                    "declare exactly one"
            }
        }
    }
}
