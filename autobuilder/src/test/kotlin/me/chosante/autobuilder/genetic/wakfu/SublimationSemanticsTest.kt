package me.chosante.autobuilder.genetic.wakfu

import me.chosante.autobuilder.domain.DamageScenario
import me.chosante.common.Characteristic
import me.chosante.common.SECONDARY_MASTERY_CHARACTERISTICS
import me.chosante.common.ScenarioGate
import me.chosante.common.SublimationCondition
import me.chosante.common.SublimationConditionType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Locks the B3 single-source sublimation decisions that the CP-SAT objective and the scalar re-scorers now BOTH
 * consume, so the two engines can no longer drift on WHAT a condition/gate means (only on HOW they evaluate it):
 * the scenario-gate predicate [scenarioGateMatchesCore] and the condition descriptor [subConditionSpec] (whose
 * modeled types must stay exactly [SUPPORTED_SUB_CONDITIONS]).
 */
class SublimationSemanticsTest {
    private val fire = DamageScenario() // FIRE / DISTANCE / BACK by default
    private val fireGate = ScenarioGate(element = "FIRE")
    private val waterGate = ScenarioGate(element = "WATER")
    private val distanceGate = ScenarioGate(rangeBand = "DISTANCE")
    private val maxDamage = ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE
    private val mostMasteries = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT
    private val precision = ScoreComputationMode.FIND_CLOSEST_BUILD_FROM_INPUT
    private val fireWanted = setOf(Characteristic.MASTERY_ELEMENTARY_FIRE)

    @Test
    fun `an ungated effect always fires`() {
        assertThat(scenarioGateMatchesCore(null, maxDamage, fire, 100, emptySet())).isTrue()
        assertThat(scenarioGateMatchesCore(null, precision, null, 100, emptySet())).isTrue()
    }

    @Test
    fun `in max-damage a gate fires only when the scenario matches`() {
        assertThat(scenarioGateMatchesCore(fireGate, maxDamage, fire, 100, emptySet())).isTrue()
        assertThat(scenarioGateMatchesCore(waterGate, maxDamage, fire, 100, emptySet())).isFalse()
        assertThat(scenarioGateMatchesCore(distanceGate, maxDamage, fire, 100, emptySet())).isTrue()
    }

    @Test
    fun `a null scenario in max-damage fires nothing`() {
        assertThat(scenarioGateMatchesCore(fireGate, maxDamage, null, 100, fireWanted)).isFalse()
    }

    @Test
    fun `outside max-damage only a wanted pure-element gate fires, in most-masteries`() {
        assertThat(scenarioGateMatchesCore(fireGate, mostMasteries, fire, 100, fireWanted)).isTrue()
        assertThat(scenarioGateMatchesCore(waterGate, mostMasteries, fire, 100, fireWanted)).isFalse()
        // a non-pure-element (orientation/range/berserk) gate never fires in most-masteries
        assertThat(scenarioGateMatchesCore(distanceGate, mostMasteries, fire, 100, fireWanted)).isFalse()
        // precision has no attack scenario, so nothing scenario-gated fires
        assertThat(scenarioGateMatchesCore(fireGate, precision, fire, 100, fireWanted)).isFalse()
    }

    @Test
    fun `a minCharacterLevel gate respects the character level in max-damage`() {
        val lvlGate = ScenarioGate(minCharacterLevel = 150)
        assertThat(scenarioGateMatchesCore(lvlGate, maxDamage, fire, 200, emptySet())).isTrue()
        assertThat(scenarioGateMatchesCore(lvlGate, maxDamage, fire, 100, emptySet())).isFalse()
    }

    @Test
    fun `subConditionSpec models exactly the SUPPORTED_SUB_CONDITIONS`() {
        // The descriptor and the supported-set are two single sources; this ties them so a new condition type
        // taught to one but not the other fails loudly instead of silently gating a sub in only one engine.
        val modeled =
            SublimationConditionType.entries
                .filter { subConditionSpec(SublimationCondition(it, value = 0), level = 100) != SubConditionSpec.AlwaysApplies }
                .toSet()
        assertThat(modeled).isEqualTo(SUPPORTED_SUB_CONDITIONS)
    }

    @Test
    fun `subConditionSpec normalizes the dodge percent-of-level bound and sums the secondary masteries`() {
        // DODGE_LT_PCT_OF_LEVEL: v < n·level/100  ⇒  AT_MOST n·level/100 − 1  (25·200/100 = 50 ⇒ ≤ 49).
        assertThat(subConditionSpec(SublimationCondition(SublimationConditionType.DODGE_LT_PCT_OF_LEVEL, value = 25), level = 200))
            .isEqualTo(SubConditionSpec.StatBound(listOf(Characteristic.DODGE), ConditionComparison.AT_MOST, 49))
        val secondary = subConditionSpec(SublimationCondition(SublimationConditionType.SECONDARY_MASTERIES_AT_MOST, value = 0), level = 100)
        assertThat((secondary as SubConditionSpec.StatBound).stats.toSet()).isEqualTo(SECONDARY_MASTERY_CHARACTERISTICS)
    }

    @Test
    fun `ConditionComparison holds evaluates each operator`() {
        assertThat(ConditionComparison.AT_MOST.holds(5, 5)).isTrue()
        assertThat(ConditionComparison.AT_MOST.holds(6, 5)).isFalse()
        assertThat(ConditionComparison.AT_LEAST.holds(5, 5)).isTrue()
        assertThat(ConditionComparison.AT_LEAST.holds(4, 5)).isFalse()
        assertThat(ConditionComparison.EXACT.holds(5, 5)).isTrue()
        assertThat(ConditionComparison.EXACT.holds(6, 5)).isFalse()
    }
}
