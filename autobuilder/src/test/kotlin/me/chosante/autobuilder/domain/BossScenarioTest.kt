package me.chosante.autobuilder.domain

import me.chosante.common.I18nText
import me.chosante.common.Monster
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class BossScenarioTest {
    private fun monster(
        fire: Int = 0,
        water: Int = 0,
        earth: Int = 0,
        air: Int = 0,
        hp: Int = 10_000,
    ) = Monster(
        id = 1,
        name = I18nText("Boss", "Boss", "Boss", "Boss"),
        level = 200,
        hp = hp,
        rank = 1,
        fireResistance = fire,
        waterResistance = water,
        earthResistance = earth,
        airResistance = air
    )

    @Test
    fun `flat resistance converts to percentage and caps at 90`() {
        // Res% = floor((1 - 0.8^(flat/100)) * 100), verified against MethodWakfu's displayed values.
        assertThat(flatResistanceToPercent(0)).isZero()
        assertThat(flatResistanceToPercent(500)).isEqualTo(67)
        assertThat(flatResistanceToPercent(800)).isEqualTo(83)
        assertThat(flatResistanceToPercent(1000)).isEqualTo(89)
        assertThat(flatResistanceToPercent(2000)).isEqualTo(90) // capped
    }

    @Test
    fun `negative flat resistance stays negative (weakness raises damage)`() {
        assertThat(flatResistanceToPercent(-500)).isNegative()
        assertThat(flatResistanceToPercent(-100)).isLessThan(0)
    }

    @Test
    fun `resistancePercent reads the right element`() {
        val boss = monster(fire = 800, water = 0, earth = -500, air = 1000)
        assertThat(boss.resistancePercent(SpellElement.FIRE)).isEqualTo(83)
        assertThat(boss.resistancePercent(SpellElement.WATER)).isZero()
        assertThat(boss.resistancePercent(SpellElement.EARTH)).isNegative()
        assertThat(boss.resistancePercent(SpellElement.AIR)).isEqualTo(89)
    }

    @Test
    fun `against keeps user scenario fields and fills element plus resistance`() {
        val base =
            DamageScenario(
                element = SpellElement.FIRE,
                rangeBand = RangeBand.MELEE,
                orientation = Orientation.SIDE,
                berserk = true,
                healing = false,
                critCapPercent = 50,
                baseDamage = 250
            )
        val boss = monster(water = 800)

        val scenario = base.against(boss, SpellElement.WATER)

        assertThat(scenario.element).isEqualTo(SpellElement.WATER)
        assertThat(scenario.targetResistancePercent).isEqualTo(83)
        // every other (user-chosen) field is preserved
        assertThat(scenario.rangeBand).isEqualTo(RangeBand.MELEE)
        assertThat(scenario.orientation).isEqualTo(Orientation.SIDE)
        assertThat(scenario.berserk).isTrue()
        assertThat(scenario.critCapPercent).isEqualTo(50)
        assertThat(scenario.baseDamage).isEqualTo(250)
    }

    @Test
    fun `againstAllElements fills every element's resistance for the boss-aware objective`() {
        val boss = monster(fire = 800, water = 0, earth = -500, air = 1000)

        val scenario = DamageScenario().againstAllElements(boss)

        val resistances = scenario.elementResistances!!
        assertThat(resistances[SpellElement.FIRE]).isEqualTo(83)
        assertThat(resistances[SpellElement.WATER]).isZero()
        assertThat(resistances[SpellElement.EARTH]).isNegative()
        assertThat(resistances[SpellElement.AIR]).isEqualTo(89)
        // candidateElements now enumerates all four — the seam the fused objective optimizes over.
        assertThat(scenario.candidateElements()).hasSize(4)
    }

    @Test
    fun `turnsToKill rounds up and is at least one`() {
        val boss = monster(hp = 10_000)
        assertThat(BossDisplay.turnsToKill(boss, BigDecimal(1_000))).isEqualTo(10)
        assertThat(BossDisplay.turnsToKill(boss, BigDecimal(999))).isEqualTo(11) // ceiling
        assertThat(BossDisplay.turnsToKill(boss, BigDecimal(50_000))).isEqualTo(1) // never below 1
        assertThat(BossDisplay.turnsToKill(boss, BigDecimal.ZERO)).isZero()
    }

    @Test
    fun `difficulty multiplier scales HP (turns-to-kill), not resistances`() {
        val boss = monster(fire = 500, hp = 10_000)
        // x3 difficulty triples the HP, so triples the turns-to-kill…
        assertThat(BossDisplay.effectiveHp(boss, 3.0)).isEqualTo(30_000L)
        assertThat(BossDisplay.turnsToKill(boss, BigDecimal(1_000), hpMultiplier = 3.0)).isEqualTo(30)
        assertThat(BossDisplay.turnsToKill(boss, BigDecimal(1_000), hpMultiplier = 1.0)).isEqualTo(10)
        // …but never touches the resistance percentage that drives the optimal build.
        assertThat(boss.resistancePercent(SpellElement.FIRE)).isEqualTo(67)
    }
}
