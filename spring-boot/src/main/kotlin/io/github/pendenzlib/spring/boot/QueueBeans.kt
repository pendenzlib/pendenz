package io.github.pendenzlib.spring.boot

import io.github.pendenzlib.ConnectionProvider
import io.github.pendenzlib.Pendenz
import io.github.pendenzlib.Permissions
import io.github.pendenzlib.TaskListener
import io.github.pendenzlib.schema.IdType
import io.github.pendenzlib.schema.TaskTable
import org.springframework.beans.BeansException
import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.core.ResolvableType

/** Builds the queue from the application's beans, for one id type; see ADR 0023. */
internal class QueueBeans<ID : Any>(
    private val beans: ListableBeanFactory,
    private val idType: IdType<ID>,
    private val idClass: Class<ID>,
    private val property: PendenzProperties.IdType,
) {
    @Suppress("UNCHECKED_CAST") // ofThisIdType returns only beans declared with this id type
    fun build(
        table: TaskTable<*>,
        connections: ConnectionProvider,
    ): Pendenz<ID> {
        val builder = Pendenz.builder(tableOfThisType(table)).connectionProvider(connections)
        explainingCycles {
            permissions()?.let(builder::permissions)
            ofThisIdType(TaskListener::class.java).forEach { builder.listener(it as TaskListener<ID>) }
            ofThisIdType(TaskRegistration::class.java).forEach {
                (it as TaskRegistration<ID>).registerOn(builder, beans)
            }
            ofThisIdType(PendenzCustomizer::class.java).forEach { (it as PendenzCustomizer<ID>).customize(builder) }
        }
        return builder.build()
    }

    @Suppress("UNCHECKED_CAST") // the id type is compared right before
    private fun tableOfThisType(table: TaskTable<*>): TaskTable<ID> {
        check(table.idType == idType) {
            "the TaskTable bean $table has the id type ${table.idType}, but pendenz.id-type is " +
                "${property.name.lowercase()}; set the property to the table's id type"
        }
        return table as TaskTable<ID>
    }

    /**
     * Collects the beans; a circle through the queue fails with an explanation of its own, not only Spring's
     * report on circular references.
     */
    private fun explainingCycles(collect: () -> Unit) {
        try {
            collect()
        } catch (e: BeansException) {
            val queueBeans =
                QUEUE_BEAN_KINDS
                    .flatMap { kind -> beans.getBeanNamesForType(kind).map { it to kind.simpleName } }
                    .toMap()
            throw QueueCycleException.from(e, queueBeans) ?: e
        }
    }

    private fun permissions(): Permissions? {
        val names = beans.getBeanNamesForType(Permissions::class.java)
        check(names.size <= 1) {
            "found ${names.size} Permissions beans ${names.toList()}; Pendenz uses one, combine them into one bean"
        }
        return names.firstOrNull()?.let { beans.getBean(it, Permissions::class.java) }
    }

    private companion object {
        val QUEUE_BEAN_KINDS =
            listOf(
                Permissions::class.java,
                TaskListener::class.java,
                TaskRegistration::class.java,
                PendenzCustomizer::class.java,
            )
    }

    /**
     * The beans of [type] whose type argument is this queue's id type, in
     * their declared order. A bean declared for another id type fails
     * startup instead of being left out without a word.
     */
    private fun <T : Any> ofThisIdType(type: Class<T>): List<T> {
        val matching = beans.getBeanNamesForType(ResolvableType.forClassWithGenerics(type, idClass))
        val foreign = beans.getBeanNamesForType(type).filterNot { it in matching }
        check(foreign.isEmpty()) {
            "the ${type.simpleName} beans $foreign are declared for another id type than the queue's " +
                "${property.name.lowercase()}; declare them as ${type.simpleName}<${idClass.simpleName}>"
        }
        @Suppress("UNCHECKED_CAST") // matched by their declared type argument
        return beans
            .getBeanProvider<T>(ResolvableType.forClassWithGenerics(type, idClass))
            .orderedStream()
            .toList() as List<T>
    }
}
