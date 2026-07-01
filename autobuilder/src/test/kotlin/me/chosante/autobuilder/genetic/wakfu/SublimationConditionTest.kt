package me.chosante.autobuilder.genetic.wakfu

import me.chosante.autobuilder.domain.DamageScenario
import me.chosante.autobuilder.domain.SpellElement
import me.chosante.common.Characteristic
import me.chosante.common.I18nText
import me.chosante.common.SECONDARY_MASTERY_CHARACTERISTICS
import me.chosante.common.ScenarioGate
import me.chosante.common.Sublimation
import me.chosante.common.SublimationCondition
import me.chosante.common.SublimationConditionType
import me.chosante.common.SublimationEffect
import me.chosante.common.SublimationKind
import me.chosante.common.SublimationRarity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Regression for the "secondary masteries ≤ 0" leak: the re-scorer used to sum only MELEE + DISTANCE, so a
 * rear/crit-stacking damage build spuriously satisfied the condition and pocketed Neutrality / Ambition for
 * free. The condition must be evaluated against the SUM of all six secondary masteries
 * ([SECONDARY_MASTERY_CHARACTERISTICS]).
 */
class SublimationConditionTest {
    private fun neutralityLike() =
        Sublimation(
            stateId = 6931,
            name = I18nText("Neutralité I", "Neutrality I", "Neutralidad I", "Neutralidade I"),
            rarity = SublimationRarity.NORMAL,
            maxLevel = 4,
            kind = SublimationKind.STATIC_CONDITIONAL,
            solverChoosable = true,
            condition = SublimationCondition(SublimationConditionType.SECONDARY_MASTERIES_AT_MOST, value = 0),
            effects = listOf(SublimationEffect.Flat(Characteristic.DAMAGE_INFLICTED, 24))
        )

    @Test
    fun `secondary-mastery condition is violated by rear mastery alone (the reported CRA leak)`() {
        // The real CRA build had MASTERY_BACK=407 and MASTERY_CRITICAL=203 — neither is melee/distance,
        // so the old melee+distance-only sum saw 0 and wrongly applied the bonus.
        val preSub = mapOf(Characteristic.MASTERY_BACK to 407, Characteristic.MASTERY_CRITICAL to 203)

        val contributions =
            sublimationFixedContributions(
                sublimations = listOf(neutralityLike()),
                preSub = preSub,
                mode = ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE,
                scenario = null,
                level = 110
            )

        assertThat(contributions).doesNotContainKey(Characteristic.DAMAGE_INFLICTED)
    }

    @Test
    fun `each secondary mastery on its own breaks the condition`() {
        SECONDARY_MASTERY_CHARACTERISTICS.forEach { mastery ->
            val contributions =
                sublimationFixedContributions(
                    sublimations = listOf(neutralityLike()),
                    preSub = mapOf(mastery to 50),
                    mode = ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE,
                    scenario = null,
                    level = 110
                )
            assertThat(contributions)
                .withFailMessage("sub should NOT apply with %s=50 (secondary mastery > 0)", mastery)
                .doesNotContainKey(Characteristic.DAMAGE_INFLICTED)
        }
    }

    @Test
    fun `condition holds only when every secondary mastery is at most zero`() {
        // No secondary masteries (a pure-elemental build) — the niche case the sub is actually meant for.
        val contributions =
            sublimationFixedContributions(
                sublimations = listOf(neutralityLike()),
                preSub = mapOf(Characteristic.MASTERY_ELEMENTARY to 300),
                mode = ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE,
                scenario = null,
                level = 110
            )

        assertThat(contributions[Characteristic.DAMAGE_INFLICTED]).isEqualTo(24)
    }

    /** Critical Secret (9074): "+30 Critical Hit if Critical **Mastery** ≤ 0" — a distinct condition from CRIT_AT_MOST. */
    private fun criticalSecretLike() =
        Sublimation(
            stateId = 9074,
            name = I18nText("Secret critique", "Critical Secret", "Secreto crítico", "Segredo Crítico"),
            rarity = SublimationRarity.EPIC,
            maxLevel = 1,
            kind = SublimationKind.STATIC_CONDITIONAL,
            solverChoosable = true,
            condition = SublimationCondition(SublimationConditionType.CRITICAL_MASTERY_AT_MOST, value = 0),
            effects = listOf(SublimationEffect.Flat(Characteristic.CRITICAL_HIT, 30))
        )

    @Test
    fun `critical-mastery condition is violated by any positive critical mastery`() {
        // Crit mastery is the CONDITION stat (MASTERY_CRITICAL), distinct from the +crit-rate effect (CRITICAL_HIT).
        val contributions =
            sublimationFixedContributions(
                sublimations = listOf(criticalSecretLike()),
                preSub = mapOf(Characteristic.MASTERY_CRITICAL to 1),
                mode = ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE,
                scenario = null,
                level = 110
            )

        assertThat(contributions).doesNotContainKey(Characteristic.CRITICAL_HIT)
    }

    /** Tellurisme/Brûlure/… (8518-8521): "+12% <element> damage", modeled as an element-gated DI effect. */
    private fun perElementFireDamageSub() =
        Sublimation(
            stateId = 8518,
            name = I18nText("Brûlure I", "Burn I", "", ""),
            rarity = SublimationRarity.NORMAL,
            maxLevel = 3,
            kind = SublimationKind.FLAT,
            solverChoosable = true,
            effects = listOf(SublimationEffect.Flat(Characteristic.DAMAGE_INFLICTED, 12, ScenarioGate(element = "FIRE")))
        )

    private fun perElementDi(scenario: DamageScenario?): Int? =
        sublimationFixedContributions(
            sublimations = listOf(perElementFireDamageSub()),
            preSub = emptyMap(),
            mode = ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE,
            scenario = scenario,
            level = 110
        )[Characteristic.DAMAGE_INFLICTED]

    @Test
    fun `per-element damage sub is credited only in its own element's max-damage scenario`() {
        // Fire scenario: the +12% fire-damage bonus applies.
        assertThat(perElementDi(DamageScenario(element = SpellElement.FIRE))).isEqualTo(12)
        // Water scenario: element mismatch -> the bonus is dropped (it does not boost water damage).
        assertThat(perElementDi(DamageScenario(element = SpellElement.WATER))).isNull()
        // Non-max-damage modes with no requested element don't know the attack element, so the gate never fires.
        assertThat(perElementDi(null)).isNull()
    }

    private fun perElementMostMasteriesGlobalDi(wantedElements: Set<Characteristic>): Int? =
        sublimationFixedContributions(
            sublimations = listOf(perElementFireDamageSub()),
            preSub = emptyMap(),
            mode = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT,
            scenario = null,
            level = 110,
            wantedElements = wantedElements
        )[Characteristic.DAMAGE_INFLICTED]

    private fun perElementMostMasteriesFireBucket(wantedElements: Set<Characteristic>): Int? =
        perElementDiContributions(
            sublimations = listOf(perElementFireDamageSub()),
            mode = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT,
            scenario = null,
            level = 110,
            wantedElements = wantedElements
        )[Characteristic.MASTERY_ELEMENTARY_FIRE]

    @Test
    fun `per-element damage sub routes to its own element bucket and is credited whenever that element is requested`() {
        // In most-masteries the +12% fire DI is kept OUT of the global DAMAGE_INFLICTED (it must not multiply other
        // elements' damage) — it routes to the fire bucket instead.
        assertThat(perElementMostMasteriesGlobalDi(setOf(Characteristic.MASTERY_ELEMENTARY_FIRE))).isNull()
        // Mono-fire request: credited to the fire bucket.
        assertThat(perElementMostMasteriesFireBucket(setOf(Characteristic.MASTERY_ELEMENTARY_FIRE))).isEqualTo(12)
        // MULTI-element request (fire + water): fire is still requested, so the fire sub IS credited — to ITS OWN
        // bucket, where it only multiplies fire's damage line. This is the multi-element support.
        assertThat(
            perElementMostMasteriesFireBucket(setOf(Characteristic.MASTERY_ELEMENTARY_FIRE, Characteristic.MASTERY_ELEMENTARY_WATER))
        ).isEqualTo(12)
        // Element NOT requested (water-only): the fire sub deals no requested damage -> not credited.
        assertThat(perElementMostMasteriesFireBucket(setOf(Characteristic.MASTERY_ELEMENTARY_WATER))).isNull()
        // Element-agnostic request (e.g. distance mastery only): no element fixed -> not credited.
        assertThat(perElementMostMasteriesFireBucket(emptySet())).isNull()
    }

    @Test
    fun `critical-mastery condition holds when critical mastery is at most zero`() {
        // A build with zero critical mastery (crit-rate built without crit damage) — Critical Secret's intended niche.
        val contributions =
            sublimationFixedContributions(
                sublimations = listOf(criticalSecretLike()),
                preSub = mapOf(Characteristic.CRITICAL_HIT to 20),
                mode = ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE,
                scenario = null,
                level = 110
            )

        assertThat(contributions[Characteristic.CRITICAL_HIT]).isEqualTo(30)
    }
}
