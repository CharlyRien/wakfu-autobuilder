package me.chosante.autobuilder.genetic.wakfu

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.chosante.autobuilder.domain.DamageScenario
import me.chosante.autobuilder.domain.Orientation
import me.chosante.autobuilder.domain.RangeBand
import me.chosante.autobuilder.domain.SpellElement
import me.chosante.autobuilder.domain.TargetStat
import me.chosante.autobuilder.domain.TargetStats
import me.chosante.common.Character
import me.chosante.common.CharacterClass
import me.chosante.common.Characteristic
import me.chosante.common.Equipment
import me.chosante.common.I18nText
import me.chosante.common.ItemType
import me.chosante.common.Rarity
import me.chosante.common.ScenarioGate
import me.chosante.common.SublimationEffect
import me.chosante.common.SublimationKind
import me.chosante.common.skills.CharacterSkills
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

/**
 * An HP-percent condition on the bearer is a scenario gate (client: `GetTotalHpInPercent("target") >= 90` under the 1001 / 1002
 * re-checks, tooltip "Lorsque les PV sont supérieurs à 90 %"): Carnage III and Influence vitale III are off in a max-damage
 * BERSERK scenario (HP below 50 %) and unchanged everywhere else. Critique tactique III reads the HIT TARGET's HP, so it no longer
 * decodes to a flat ±9 % damage.
 */
class HpGatedSublimationsTest {
    private val subsById = WakfuBestBuildFinderAlgorithm.sublimations.associateBy { it.stateId }
    private val carnage = subsById.getValue(5983)
    private val vitalInfluence = subsById.getValue(7862)
    private val hpGated = setOf(carnage.stateId, vitalInfluence.stateId)
    private val berserk = DamageScenario(SpellElement.FIRE, RangeBand.DISTANCE, Orientation.FACE, berserk = true)
    private val notBerserk = berserk.copy(berserk = false)
    private val maxDamage = ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE

    @Test
    fun `the data gates the high-HP subs off the berserk range and drops Critique tactique's target-HP branches`() {
        for (sub in listOf(carnage, vitalInfluence)) {
            assertThat(sub.solverChoosable).isTrue()
            assertThat(sub.effects.filterIsInstance<SublimationEffect.StatEffect>().map { it.scenarioGate })
                .describedAs(sub.name.fr)
                .containsOnly(ScenarioGate(notBerserk = true))
        }
        val tactical = subsById.getValue(6930)
        assertThat(tactical.kind).isEqualTo(SublimationKind.COMBAT_CONDITIONAL)
        assertThat(tactical.effects).isEmpty()
        // Every not-berserk gate of the catalog: the four "HP ≥ 90 %" subs, nothing else.
        val gated =
            WakfuBestBuildFinderAlgorithm.sublimations
                .filter { s -> s.effects.filterIsInstance<SublimationEffect.StatEffect>().any { it.scenarioGate?.notBerserk == true } }
                .map { it.stateId }
        assertThat(gated).containsExactlyInAnyOrder(5983, 7860, 7861, 7862)
    }

    @Test
    fun `the gate fires in every request but a max-damage berserk one`() {
        val gate = ScenarioGate(notBerserk = true)
        assertThat(scenarioGateMatchesCore(gate, maxDamage, berserk, 200, emptySet())).isFalse()
        assertThat(scenarioGateMatchesCore(gate, maxDamage, notBerserk, 200, emptySet())).isTrue()
        for (mode in listOf(ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT, ScoreComputationMode.FIND_CLOSEST_BUILD_FROM_INPUT)) {
            assertThat(scenarioGateMatchesCore(gate, mode, null, 200, emptySet())).describedAs("$mode").isTrue()
        }
        // Combined with another gate, the other one still decides outside berserk.
        assertThat(scenarioGateMatchesCore(gate.copy(ranged = true), maxDamage, notBerserk, 200, emptySet())).isTrue()
        assertThat(scenarioGateMatchesCore(gate.copy(ranged = true), maxDamage, notBerserk.copy(rangeBand = RangeBand.MELEE), 200, emptySet())).isFalse()
    }

    @Test
    fun `the scorer credits nothing in berserk and the same as before outside it`() {
        val subs = listOf(carnage, vitalInfluence)
        val berserkOut = sublimationFixedContributions(subs, emptyMap(), maxDamage, berserk, 200)
        assertThat(berserkOut.values.filter { it != 0 }).isEmpty()
        val out = sublimationFixedContributions(subs, emptyMap(), maxDamage, notBerserk, 200)
        assertThat(out[Characteristic.MASTERY_ELEMENTARY]).isEqualTo(
            carnage.effects
                .filterIsInstance<SublimationEffect.StatEffect>()
                .single()
                .magnitudeAtLevel(200)
        )
        assertThat(out[Characteristic.CRITICAL_HIT]).isEqualTo(12)
        val mostMasteries = sublimationFixedContributions(subs, emptyMap(), ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT, null, 200)
        assertThat(mostMasteries).isEqualTo(out)
    }

    private fun item(
        id: Int,
        type: ItemType,
        stats: Map<Characteristic, Int>,
    ) = Equipment(
        equipmentId = id,
        level = 100,
        name = I18nText("item$id", "item$id", "item$id", "item$id"),
        rarity = Rarity.LEGENDARY,
        itemType = type,
        characteristics = stats,
        guiId = id,
        maxShardSlots = 4
    )

    private fun params(scenario: DamageScenario) =
        WakfuBestBuildParams(
            character = Character(CharacterClass.CRA, 100, 1, CharacterSkills(100)),
            targetStats = TargetStats(emptyList()),
            searchDuration = 60.seconds,
            stopWhenBuildMatch = false,
            maxRarity = Rarity.EPIC,
            forcedItems = emptyList(),
            excludedItems = emptyList(),
            scoreComputationMode = maxDamage,
            damageScenario = scenario,
            useSublimations = true,
            useRunes = false
        )

    /**
     * Seeded pools: in berserk the optimum with the high-HP subs on offer equals the optimum without them, the scorer agrees with the
     * solver, and the AP-cell ledger and the soft certificate stay above the optimum; outside berserk they still pay.
     */
    @Test
    fun `solver, scorer and certificates agree on seeded pools`() {
        val subs = listOf(carnage, vitalInfluence, subsById.getValue(6026), subsById.getValue(5982))
        val tuning = WakfuBuildSolver.SolverTuning(numSearchWorkers = 4)
        repeat(6) { seed ->
            val random = Random(seed)
            val slots = listOf(ItemType.AMULET, ItemType.BELT, ItemType.HELMET, ItemType.BOOTS, ItemType.CAPE, ItemType.CHEST_PLATE)
            val pool =
                slots
                    .flatMapIndexed { slotIndex, slot ->
                        List(3) { i ->
                            item(
                                100 * (slotIndex + 1) + i,
                                slot,
                                mapOf(
                                    Characteristic.MASTERY_ELEMENTARY_FIRE to random.nextInt(0, 300),
                                    Characteristic.MASTERY_BERSERK to random.nextInt(0, 200),
                                    Characteristic.CRITICAL_HIT to random.nextInt(0, 20)
                                )
                            )
                        }
                    }.groupBy { it.itemType }

            fun solve(
                p: WakfuBestBuildParams,
                offered: List<me.chosante.common.Sublimation>,
            ) = runBlocking { WakfuBuildSolver.optimize(p, pool, emptyList(), offered, tuning).toList().last() }

            val berserkParams = params(berserk)
            val with = solve(berserkParams, subs)
            val without = solve(berserkParams, subs.filterNot { it.stateId in hpGated })
            assertThat(with.maxDamageObjective).describedAs("seed $seed berserk").isEqualTo(without.maxDamageObjective)
            assertThat(WakfuBestBuildFinderAlgorithm.rescore(berserkParams, with.individual)).describedAs("seed $seed rescore").isEqualByComparingTo(with.matchPercentage)
            val ledger = WakfuBuildSolver.maxDamageCertificate(berserkParams, pool, emptyList(), subs)
            assertThat(requireNotNull(ledger?.maxCellObjective)).describedAs("seed $seed ledger").isGreaterThanOrEqualTo(requireNotNull(with.maxDamageObjective))

            val soft = berserkParams.copy(targetStats = TargetStats(listOf(TargetStat(Characteristic.ACTION_POINT, 7))))
            var softOptimum: Long? = null
            runBlocking {
                WakfuBuildSolver.optimize(soft, pool, emptyList(), subs, tuning, hardConstraints = false).collect { softOptimum = it.maxDamageObjective ?: softOptimum }
            }
            val bound = MaxDamageSoftCertificate.bound(soft, pool, emptyList(), subs, blockGate = false, coupleSecondaryItemNegative = true, netSecondaryItemBudget = true)
            assertThat(requireNotNull(bound).foldedBound).describedAs("seed $seed soft").isGreaterThanOrEqualTo(requireNotNull(softOptimum))

            val calm = params(notBerserk)
            val calmWith = solve(calm, subs)
            val calmWithout = solve(calm, subs.filterNot { it.stateId in hpGated })
            assertThat(calmWith.maxDamageObjective!!).describedAs("seed $seed outside berserk they still pay").isGreaterThan(calmWithout.maxDamageObjective!!)
        }
    }

    /**
     * The audit's reproduction (Osamodas 200, max-damage fire / distance / face, berserk, Vision du Corbac): the engine used to wear
     * Carnage III and Influence vitale III ×2, crediting mastery and critical hit the berserk range denies. Whatever it wears now, they
     * add nothing to the build's score.
     */
    @Tag("slow")
    @Test
    fun `the Osamodas 200 berserk request no longer credits the high-HP subs`() {
        val params =
            WakfuBestBuildParams(
                character = Character(CharacterClass.OSAMODAS, 200, 0, CharacterSkills(200)),
                targetStats = TargetStats(emptyList()),
                searchDuration = 60.seconds,
                stopWhenBuildMatch = false,
                maxRarity = Rarity.EPIC,
                forcedItems = emptyList(),
                excludedItems = emptyList(),
                scoreComputationMode = maxDamage,
                damageScenario = berserk,
                useSublimations = true,
                useRunes = false,
                forcedPassives = listOf("Vision du Corbac")
            )
        val result =
            runBlocking {
                WakfuBuildSolver
                    .optimize(params, WakfuBestBuildFinderAlgorithm.poolFor(params), emptyList(), WakfuBestBuildFinderAlgorithm.activeSublimations(params))
                    .toList()
                    .last()
            }
        val build = result.individual
        val worn = build.sublimations.values.flatten()
        val usesOffhandOrTwoHanded = build.equipments.any { it.itemType == ItemType.OFF_HAND_WEAPONS || it.itemType == ItemType.TWO_HANDED_WEAPONS }
        val credited = sublimationFixedContributions(worn, emptyMap(), maxDamage, berserk, 200, usesOffhandOrTwoHanded)
        val withoutHpGated = sublimationFixedContributions(worn.filterNot { it.stateId in hpGated }, emptyMap(), maxDamage, berserk, 200, usesOffhandOrTwoHanded)
        assertThat(credited).isEqualTo(withoutHpGated)
        assertThat(WakfuBestBuildFinderAlgorithm.rescore(params, build)).isEqualByComparingTo(result.matchPercentage)
    }
}
