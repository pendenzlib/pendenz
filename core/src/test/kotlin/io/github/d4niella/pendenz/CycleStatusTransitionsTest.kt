package io.github.d4niella.pendenz

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

class CycleStatusTransitionsTest {
    @ParameterizedTest
    @EnumSource(CycleStatus::class)
    fun `transitions - every status - never targets itself`(status: CycleStatus) {
        assertThat(CycleStatusTransitions.isAllowed(status, status)).isFalse()
    }

    @ParameterizedTest
    @EnumSource(CycleStatus::class)
    fun `transitions - final status - has no targets, any other at least cancel`(status: CycleStatus) {
        val targets = CycleStatusTransitions.targetsFrom(status)

        if (status.isFinal) {
            assertThat(targets).isEmpty()
        } else {
            assertThat(targets).contains(CycleStatus.CANCELLED)
        }
    }

    @Test
    fun `pause and resume - ACTIVE and PAUSED - lead into each other and only from each other`() {
        assertThat(CycleStatusTransitions.sourcesOf(CycleStatus.PAUSED)).containsExactly(CycleStatus.ACTIVE)
        assertThat(CycleStatusTransitions.sourcesOf(CycleStatus.ACTIVE)).containsExactly(CycleStatus.PAUSED)
    }

    @Test
    fun `finish - any status - only an ACTIVE cycle may become FINISHED`() {
        assertThat(CycleStatusTransitions.sourcesOf(CycleStatus.FINISHED)).containsExactly(CycleStatus.ACTIVE)
    }

    @ParameterizedTest
    @EnumSource(CycleStatus::class)
    fun `sourcesOf - every status - names exactly the states isAllowed accepts`(to: CycleStatus) {
        val sources = CycleStatusTransitions.sourcesOf(to)

        CycleStatus.entries.forEach { from ->
            assertThat(from in sources)
                .describedAs("%s -> %s", from, to)
                .isEqualTo(CycleStatusTransitions.isAllowed(from, to))
        }
    }
}
