package io.github.d4niella.pendenz.spring.boot

import io.github.d4niella.pendenz.ConnectionProvider
import io.github.d4niella.pendenz.Pendenz
import io.github.d4niella.pendenz.Permissions
import io.github.d4niella.pendenz.TaskListener
import io.github.d4niella.pendenz.schema.IdType
import io.github.d4niella.pendenz.schema.TaskTable
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
        permissions()?.let(builder::permissions)
        ofThisIdType(TaskListener::class.java).forEach { builder.listener(it as TaskListener<ID>) }
        ofThisIdType(TaskRegistration::class.java).forEach { (it as TaskRegistration<ID>).registerOn(builder) }
        ofThisIdType(PendenzCustomizer::class.java).forEach { (it as PendenzCustomizer<ID>).customize(builder) }
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

    private fun permissions(): Permissions? {
        val names = beans.getBeanNamesForType(Permissions::class.java)
        check(names.size <= 1) {
            "found ${names.size} Permissions beans ${names.toList()}; Pendenz uses one, combine them into one bean"
        }
        return names.firstOrNull()?.let { beans.getBean(it, Permissions::class.java) }
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
