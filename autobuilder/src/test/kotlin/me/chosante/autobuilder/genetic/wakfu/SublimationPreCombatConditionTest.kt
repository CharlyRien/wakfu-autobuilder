package me.chosante.autobuilder.genetic.wakfu

import me.chosante.common.Characteristic
import me.chosante.common.I18nText
import me.chosante.common.Sublimation
import me.chosante.common.SublimationCondition
import me.chosante.common.SublimationConditionType
import me.chosante.common.SublimationEffect
import me.chosante.common.SublimationKind
import me.chosante.common.SublimationRarity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Regression for the pre-combat crit timing bug (reported 2026-06-27). A build-static start-of-combat
 * condition (Measure III: `CRIT_AT_MOST 50`) is evaluated against the **pre-combat / character-sheet** crit.
 * A PERMANENT sublimation crit (Influence II, +15, `appliesBeforeCombat=true`) IS part of that pre-combat
 * crit, so it can push the build past the threshold; a START-OF-COMBAT / conditional crit (Ambition,
 * Secondary Devastation II — `appliesBeforeCombat=false`) is applied *at* combat start, after the condition
 * is read, so it must NOT count. The scorer ([sublimationFixedContributions]) mirrors the solver's
 * `preCombatStat` exactly, so this is the deterministic test of both.
 */
class SublimationPreCombatConditionTest {
    /** Influence II (6026): permanent +9 crit — shows on the sheet, so it feeds a pre-combat condition. */
    private fun influenceII() =
        Sublimation(
            stateId = 6026,
            name = I18nText("Influence II", "Influence II", "Influencia II", "Influência II"),
            rarity = SublimationRarity.NORMAL,
            maxStackLevel = 6,
            kind = SublimationKind.FLAT,
            solverChoosable = true,
            effects = listOf(SublimationEffect.Flat(Characteristic.CRITICAL_HIT, 9, appliesBeforeCombat = true))
        )

    /** Measure III (8492): "+20% damage if pre-combat crit ≤ 50%". */
    private fun measureIII() =
        Sublimation(
            stateId = 8492,
            name = I18nText("Mesure III", "Measure III", "Medida III", "Medida III"),
            rarity = SublimationRarity.NORMAL,
            maxStackLevel = 3,
            kind = SublimationKind.STATIC_CONDITIONAL,
            solverChoosable = true,
            condition = SublimationCondition(SublimationConditionType.CRIT_AT_MOST, value = 50),
            // appliesBeforeCombat defaults false: a conditional sub's effects apply only at combat start.
            effects = listOf(SublimationEffect.Flat(Characteristic.DAMAGE_INFLICTED, 20))
        )

    /** Ambition I (7115): start-of-combat +25 crit if secondary masteries ≤ 0 (NOT permanent). */
    private fun ambitionI() =
        Sublimation(
            stateId = 7115,
            name = I18nText("Ambition I", "Ambition I", "Ambición I", "Ambição I"),
            rarity = SublimationRarity.NORMAL,
            maxStackLevel = 1,
            kind = SublimationKind.STATIC_CONDITIONAL,
            solverChoosable = true,
            condition = SublimationCondition(SublimationConditionType.SECONDARY_MASTERIES_AT_MOST, value = 0),
            effects = listOf(SublimationEffect.Flat(Characteristic.CRITICAL_HIT, 25))
        )

    /** Secondary Devastation II (6013): start-of-combat +7 crit/willpower/block (FLAT but NOT permanent). */
    private fun secondaryDevastationII() =
        Sublimation(
            stateId = 6013,
            name = I18nText("Dévastation secondaire II", "Secondary Devastation II", "Devastación II", "Devastação II"),
            rarity = SublimationRarity.NORMAL,
            maxStackLevel = 5,
            kind = SublimationKind.FLAT,
            solverChoosable = true,
            effects =
                listOf(
                    SublimationEffect.Flat(Characteristic.CRITICAL_HIT, 7),
                    SublimationEffect.Flat(Characteristic.WILLPOWER, 7),
                    SublimationEffect.Flat(Characteristic.BLOCK_PERCENTAGE, 7)
                )
        )

    private fun contributions(
        subs: List<Sublimation>,
        baseCrit: Int,
    ) = sublimationFixedContributions(
        sublimations = subs,
        preSub = mapOf(Characteristic.CRITICAL_HIT to baseCrit),
        mode = ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE,
        scenario = null,
        level = 245
    )

    @Test
    fun `Influence II + 47-base crit cannot also carry Measure III (the reported bug)`() {
        // 47 base + Influence's permanent +9 = 56% pre-combat crit > 50, so Measure III must NOT apply.
        val result = contributions(listOf(influenceII(), measureIII()), baseCrit = 47)

        assertThat(result)
            .describedAs("Influence II's permanent +9 crit IS part of the pre-combat crit (47 -> 56)")
            .containsEntry(Characteristic.CRITICAL_HIT, 9)
        assertThat(result)
            .describedAs("56%% pre-combat crit > 50, so Measure III's +20%% damage must not be credited")
            .doesNotContainKey(Characteristic.DAMAGE_INFLICTED)
    }

    @Test
    fun `Measure III alone applies at 47-base crit`() {
        // Control: without the permanent crit sub, 47 <= 50 so the condition holds.
        assertThat(contributions(listOf(measureIII()), baseCrit = 47))
            .containsEntry(Characteristic.DAMAGE_INFLICTED, 20)
    }

    @Test
    fun `Influence II does not block Measure III when the base crit is low enough`() {
        // 30 base + 9 = 39 <= 50: the permanent crit is counted but stays within the threshold.
        val result = contributions(listOf(influenceII(), measureIII()), baseCrit = 30)
        assertThat(result).containsEntry(Characteristic.DAMAGE_INFLICTED, 20)
        assertThat(result).containsEntry(Characteristic.CRITICAL_HIT, 9)
    }

    @Test
    fun `Ambition's start-of-combat crit does not block Measure III (so it beats Influence here)`() {
        // No secondary masteries, so Ambition applies; its +25 crit is start-of-combat, NOT pre-combat, so
        // the pre-combat crit stays at 47 <= 50 and Measure III still applies. This is why, for a Measure-III
        // build, a start-of-combat crit sub is strictly better than permanent Influence.
        val result = contributions(listOf(ambitionI(), measureIII()), baseCrit = 47)
        assertThat(result)
            .describedAs("Ambition's start-of-combat crit must not feed Measure III's pre-combat condition")
            .containsEntry(Characteristic.DAMAGE_INFLICTED, 20)
        assertThat(result)
            .describedAs("Ambition's +25 crit is still credited to the in-combat build")
            .containsEntry(Characteristic.CRITICAL_HIT, 25)
    }

    @Test
    fun `Secondary Devastation II's start-of-combat crit does not block Measure III`() {
        // SecDev II is decoded FLAT but its crit applies at start of combat (appliesBeforeCombat=false), so it
        // must not count toward the pre-combat CRIT_AT_MOST — exactly the "FLAT != permanent" case.
        val result = contributions(listOf(secondaryDevastationII(), measureIII()), baseCrit = 47)
        assertThat(result).containsEntry(Characteristic.DAMAGE_INFLICTED, 20)
        assertThat(result).containsEntry(Characteristic.CRITICAL_HIT, 7)
    }

    /** Ravage II (5982): FLAT, start-of-combat +masteries on EVERY secondary axis (here +3 distance/critical). */
    private fun ravageII() =
        Sublimation(
            stateId = 5982,
            name = I18nText("Ravage II", "Devastate II", "Estragos II", "Assolação II"),
            rarity = SublimationRarity.NORMAL,
            maxStackLevel = 4,
            kind = SublimationKind.FLAT,
            solverChoosable = true,
            effects =
                listOf(
                    SublimationEffect.Flat(Characteristic.MASTERY_DISTANCE, 3),
                    SublimationEffect.Flat(Characteristic.MASTERY_CRITICAL, 3)
                )
        )

    /** Neutralité I (6931): "+24% damage if secondary masteries ≤ 0" — checked ON THE FIRST TURN in game. */
    private fun neutraliteI() =
        Sublimation(
            stateId = 6931,
            name = I18nText("Neutralité I", "Neutrality I", "Neutralidad I", "Neutralidade I"),
            rarity = SublimationRarity.NORMAL,
            maxStackLevel = 4,
            kind = SublimationKind.STATIC_CONDITIONAL,
            solverChoosable = true,
            condition = SublimationCondition(SublimationConditionType.SECONDARY_MASTERIES_AT_MOST, value = 0),
            effects = listOf(SublimationEffect.Flat(Characteristic.DAMAGE_INFLICTED, 24))
        )

    @Test
    fun `Ravage's start-of-combat secondary masteries DO break Neutralite (first-turn condition — the reported bug)`() {
        // In-game (verified 2026-07-14, Xelor-20 build report): Neutralité's `secondary masteries ≤ 0` is
        // checked on the FIRST TURN — after Ravage's start-of-combat masteries landed — so the pair is
        // incoherent and Neutralité's +24% DI never fires. Unlike CRIT_AT_MOST (pre-combat read, see the
        // SecDev test above), this condition must read the first-turn sheet.
        val result = contributions(listOf(ravageII(), neutraliteI()), baseCrit = 0)
        assertThat(result)
            .describedAs("Neutralité must NOT be credited when Ravage raises first-turn secondary masteries above 0")
            .doesNotContainKey(Characteristic.DAMAGE_INFLICTED)
        assertThat(result)
            .describedAs("Ravage's own masteries are still credited to the in-combat build")
            .containsEntry(Characteristic.MASTERY_DISTANCE, 3)
    }

    @Test
    fun `Neutralite alone applies at zero secondary masteries`() {
        assertThat(contributions(listOf(neutraliteI()), baseCrit = 0))
            .containsEntry(Characteristic.DAMAGE_INFLICTED, 24)
    }

    /**
     * ENGINE-LEVEL lock of the same rule through the CP-SAT reify path (`firstTurnStat`): on a tiny
     * deterministic pool where Ravage (start-of-combat +elemental AND +secondary masteries) and
     * Neutralité (+24% DI iff first-turn secondary masteries ≤ 0) are both attractive, the solver
     * must never return a build carrying BOTH — taking Ravage breaks Neutralité's first-turn check.
     */
    @Test
    fun `solver never pairs Ravage-class start-of-combat secondaries with Neutralite (first-turn reify)`(): Unit =
        kotlinx.coroutines.runBlocking {
            fun item(
                id: Int,
                type: me.chosante.common.ItemType,
                stats: Map<Characteristic, Int>,
            ) = me.chosante.common.Equipment(
                equipmentId = id,
                guiId = id,
                level = 200,
                name = I18nText("item$id", "item$id", "", ""),
                rarity = me.chosante.common.Rarity.LEGENDARY,
                itemType = type,
                characteristics = stats,
                maxShardSlots = 3
            )

            val ravageLike =
                Sublimation(
                    stateId = 5982,
                    name = I18nText("Ravage II", "Devastate II", "Estragos II", "Assolação II"),
                    rarity = SublimationRarity.NORMAL,
                    maxStackLevel = 4,
                    kind = SublimationKind.FLAT,
                    solverChoosable = true,
                    effects =
                        listOf(
                            SublimationEffect.Flat(Characteristic.MASTERY_ELEMENTARY, 30),
                            SublimationEffect.Flat(Characteristic.MASTERY_CRITICAL, 3)
                        )
                )
            val neutralityLike =
                Sublimation(
                    stateId = 6931,
                    name = I18nText("Neutralité I", "Neutrality I", "Neutralidad I", "Neutralidade I"),
                    rarity = SublimationRarity.NORMAL,
                    maxStackLevel = 4,
                    kind = SublimationKind.STATIC_CONDITIONAL,
                    solverChoosable = true,
                    condition = SublimationCondition(SublimationConditionType.SECONDARY_MASTERIES_AT_MOST, value = 0),
                    effects = listOf(SublimationEffect.Flat(Characteristic.DAMAGE_INFLICTED, 24))
                )
            val pool =
                listOf(
                    item(1, me.chosante.common.ItemType.HELMET, mapOf(Characteristic.MASTERY_ELEMENTARY to 100)),
                    item(2, me.chosante.common.ItemType.CAPE, mapOf(Characteristic.MASTERY_ELEMENTARY to 80))
                ).groupBy { it.itemType }
            val p =
                WakfuBestBuildParams(
                    character =
                        me.chosante.common.Character(
                            me.chosante.common.CharacterClass.CRA,
                            200,
                            0,
                            me.chosante.common.skills
                                .CharacterSkills(200)
                        ),
                    targetStats =
                        me.chosante.autobuilder.domain
                            .TargetStats(
                                listOf(
                                    me.chosante.autobuilder.domain
                                        .TargetStat(Characteristic.MASTERY_ELEMENTARY, 9999)
                                )
                            ),
                    searchDuration = kotlin.time.Duration.parse("60s"),
                    stopWhenBuildMatch = false,
                    maxRarity = me.chosante.common.Rarity.EPIC,
                    forcedItems = emptyList(),
                    excludedItems = emptyList(),
                    scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT,
                    useRunes = false,
                    useSublimations = true
                )
            val tuning =
                WakfuBuildSolver.SolverTuning(
                    numSearchWorkers = 1,
                    randomSeed = 1,
                    interleaveSearch = true,
                    maxDeterministicTime = 60.0
                )
            var last: me.chosante.autobuilder.genetic.SolverResult<me.chosante.autobuilder.domain.BuildCombination>? = null
            WakfuBuildSolver
                .optimize(p, pool, emptyList(), listOf(ravageLike, neutralityLike), tuning, hardConstraints = false)
                .collect { last = it }
            val build = requireNotNull(last).individual
            val chosen =
                build.sublimations.values
                    .flatten()
                    .map { it.stateId }
                    .toSet()
            assertThat(chosen.containsAll(setOf(5982, 6931)))
                .describedAs("Ravage (start-of-combat secondaries) and Neutralité (first-turn secMast ≤ 0) are mutually exclusive; solver chose $chosen")
                .isFalse()
        }

    @Test
    fun `a conditional sub's own crit does not feed its own condition (no circular activation)`() {
        // A CRIT_AT_MOST 50 sub that itself grants +10 (start-of-combat) crit. At 45 base crit it must apply:
        // its own +10 must NOT be added before its condition is read (else 55 > 50 would wrongly block it).
        val selfCrit =
            Sublimation(
                stateId = -1,
                name = I18nText("SelfCrit", "SelfCrit", "SelfCrit", "SelfCrit"),
                rarity = SublimationRarity.NORMAL,
                maxStackLevel = 1,
                kind = SublimationKind.STATIC_CONDITIONAL,
                solverChoosable = true,
                condition = SublimationCondition(SublimationConditionType.CRIT_AT_MOST, value = 50),
                effects = listOf(SublimationEffect.Flat(Characteristic.CRITICAL_HIT, 10))
            )
        assertThat(contributions(listOf(selfCrit), baseCrit = 45))
            .describedAs("the sub's own start-of-combat crit must not feed its own pre-combat condition")
            .containsEntry(Characteristic.CRITICAL_HIT, 10)
    }
}
