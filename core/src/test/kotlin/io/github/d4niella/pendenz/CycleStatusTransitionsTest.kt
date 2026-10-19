package io.github.d4niella.pendenz

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

class CycleStatusTransitionsTest {
    @ParameterizedTest
    @EnumSource(CycleStatus::class, names = ["CANCELLED", "FINISHED"])
    fun `transitions - final status - has no targets`(status: CycleStatus) {
        assertThat(status.isFinal).isTrue()
        assertThat(CycleStatusTransitions.targetsFrom(status)).isEmpty()
    }

    @ParameterizedTest
    @EnumSource(CycleStatus::class, names = ["ACTIVE", "PAUSED"])
    fun `cancel - non-final status - is allowed`(status: CycleStatus) {
        assertThat(CycleStatusTransitions.isAllowed(status, CycleStatus.CANCELLED)).isTrue()
    }

    @ParameterizedTest
    @EnumSource(CycleStatus::class, mode = EnumSource.Mode.EXCLUDE, names = ["ACTIVE"])
    fun `finish - any status but ACTIVE - is not allowed`(status: CycleStatus) {
        assertThat(CycleStatusTransitions.isAllowed(status, CycleStatus.FINISHED)).isFalse()
    }
}
