package io.github.d4niella.pendenz.spring.boot

import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.beans.factory.SmartInitializingSingleton

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
