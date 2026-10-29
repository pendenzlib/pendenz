package io.github.pendenzlib.spring.boot

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.configurationmetadata.ConfigurationMetadataProperty
import org.springframework.boot.configurationmetadata.ConfigurationMetadataRepositoryJsonBuilder
import org.springframework.boot.convert.ApplicationConversionService
import java.beans.Introspector

/**
 * The metadata IDEs read for completion of `pendenz.*` is written by hand;
 * these tests hold it to what `PendenzProperties` binds.
 */
class PendenzPropertiesMetadataTest {
    private val metadata: Map<String, ConfigurationMetadataProperty> =
        checkNotNull(javaClass.getResourceAsStream("/META-INF/spring-configuration-metadata.json")).use {
            ConfigurationMetadataRepositoryJsonBuilder.create(it).build().allProperties
        }

    /** Every property Spring can bind, with its default, found the way the binder finds them: by setters. */
    private val bound: Map<String, Any?> = properties("pendenz", PendenzProperties())

    private fun properties(
        prefix: String,
        bean: Any,
    ): Map<String, Any?> =
        Introspector
            .getBeanInfo(bean.javaClass, Any::class.java)
            .propertyDescriptors
            .flatMap { property ->
                val name = "$prefix.${kebab(property.name)}"
                val value = property.readMethod.invoke(bean)
                when {
                    property.writeMethod != null -> {
                        listOf(name to value)
                    }

                    value != null && value.javaClass.declaringClass == PendenzProperties::class.java -> {
                        properties(name, value).toList()
                    }

                    else -> {
                        emptyList()
                    }
                }
            }.toMap()

    private fun kebab(name: String): String = name.replace(Regex("([A-Z])")) { "-" + it.value.lowercase() }

    @Test
    fun `metadata - every bound property - is listed with a description, and nothing else is`() {
        // Act
        val listed = metadata.keys

        // Assert
        assertThat(listed).containsExactlyInAnyOrderElementsOf(bound.keys)
        metadata.values.forEach { assertThat(it.description).describedAs(it.id).isNotBlank() }
    }

    @Test
    fun `metadata - defaults - are the defaults of PendenzProperties`() {
        // Arrange
        val conversion = ApplicationConversionService.getSharedInstance()

        // Act
        val listed =
            bound.mapValues { (name, value) ->
                metadata[name]?.defaultValue?.let { conversion.convert(it, value?.javaClass ?: String::class.java) }
            }

        // Assert
        bound.forEach { (name, value) -> assertThat(listed[name]).describedAs(name).isEqualTo(value) }
    }

    @Test
    fun `metadata - id type - offers bigint and uuid as values`() {
        // Act
        val values =
            metadata
                .getValue("pendenz.id-type")
                .hints.valueHints
                .map { it.value }

        // Assert
        assertThat(values).containsExactly("bigint", "uuid")
    }
}
