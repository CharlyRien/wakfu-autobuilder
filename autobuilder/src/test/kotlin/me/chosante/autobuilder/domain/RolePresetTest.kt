package me.chosante.autobuilder.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RolePresetTest {
    @Test
    fun `apply never clobbers a user-set berserk`() {
        val berserkScenario = DamageScenario(berserk = true)
        RolePreset.entries.forEach { preset ->
            assertThat(preset.apply(berserkScenario).berserk)
                .describedAs("$preset must preserve the caller's berserk flag")
                .isTrue()
        }
    }

    @Test
    fun `tank faces front and turns the floor on, leaving the floor value to the scenario`() {
        val base = DamageScenario()
        val tank = RolePreset.TANK.apply(base)
        assertThat(tank.orientation).isEqualTo(Orientation.FACE)
        assertThat(tank.survivabilityFloor).isTrue()
        assertThat(tank.minEffectiveHp).describedAs("the EHP value is defaulted by the CLI/GUI, not the preset").isEqualTo(base.minEffectiveHp)
    }

    @Test
    fun `dps presets leave the floor off but never turn an already-on floor off`() {
        assertThat(RolePreset.DISTANCE_DPS.apply(DamageScenario()).survivabilityFloor).isFalse()
        assertThat(RolePreset.MELEE_DPS.apply(DamageScenario()).rangeBand).isEqualTo(RangeBand.MELEE)
        assertThat(RolePreset.DISTANCE_DPS.apply(DamageScenario(survivabilityFloor = true)).survivabilityFloor)
            .describedAs("a preset only opts the floor IN, never out")
            .isTrue()
    }
}
