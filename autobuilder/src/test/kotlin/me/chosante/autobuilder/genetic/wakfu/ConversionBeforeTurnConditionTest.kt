package me.chosante.autobuilder.genetic.wakfu

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.autobuilder.domain.DamageScenario
import me.chosante.autobuilder.domain.Orientation
import me.chosante.autobuilder.domain.RangeBand
import me.chosante.autobuilder.domain.SpellElement
import me.chosante.autobuilder.domain.TargetStat
import me.chosante.autobuilder.domain.TargetStats
import me.chosante.common.Character
import me.chosante.common.CharacterClass
import me.chosante.common.Characteristic
import me.chosante.common.ConditionTestMoment
import me.chosante.common.Equipment
import me.chosante.common.I18nText
import me.chosante.common.ItemType
import me.chosante.common.Rarity
import me.chosante.common.SECONDARY_MASTERY_CHARACTERISTICS
import me.chosante.common.Sublimation
import me.chosante.common.SublimationConditionType
import me.chosante.common.SublimationEffect
import me.chosante.common.SublimationKind
import me.chosante.common.SublimationRarity
import me.chosante.common.skills.CharacterSkills
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

/**
 * A conversion lands at start of combat (client trigger 1028), a Neutralité-family condition is tested at start of turn (1001):
 * Dénouement moves the critical mastery out before the check, so it no longer breaks `each secondary mastery ≤ 0`. Ravage's
 * start-of-combat masteries still do (in-game 2026-07-14), and Ambition's start-of-turn +crit still does not feed Dénouement's
 * crit ≥ 40.
 */
class ConversionBeforeTurnConditionTest {
    private val subsById = WakfuBestBuildFinderAlgorithm.sublimations.associateBy { it.stateId }
    private val denouement = subsById.getValue(5077)
    private val neutralite = subsById.getValue(6931)
    private val ambition = subsById.getValue(7115)
    private val ravage = subsById.getValue(5982)

    private val character = Character(CharacterClass.CRA, 100, 1, CharacterSkills(100))

    private fun item(
        id: Int,
        type: ItemType,
        stats: Map<Characteristic, Int>,
        rarity: Rarity = Rarity.LEGENDARY,
    ) = Equipment(
        equipmentId = id,
        level = 100,
        name = I18nText("item$id", "item$id", "item$id", "item$id"),
        rarity = rarity,
        itemType = type,
        characteristics = stats,
        guiId = id,
        // A NORMAL sublimation sits in sockets.
        maxShardSlots = 4
    )

    private val critItem = item(1, ItemType.BELT, mapOf(Characteristic.MASTERY_CRITICAL to 600))
    private val fireItem = item(2, ItemType.AMULET, mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 100))
    private val critRate = item(3, ItemType.HELMET, mapOf(Characteristic.CRITICAL_HIT to 60))
    private val epicCarrier = item(4, ItemType.CAPE, emptyMap(), Rarity.EPIC)
    private val basePool = listOf(critItem, fireItem, critRate, epicCarrier)

    private fun mostMasteriesParams() =
        WakfuBestBuildParams(
            character = character,
            targetStats = TargetStats(listOf(TargetStat(Characteristic.MASTERY_ELEMENTARY_FIRE, 1))),
            searchDuration = 60.seconds,
            stopWhenBuildMatch = false,
            maxRarity = Rarity.EPIC,
            forcedItems = emptyList(),
            excludedItems = emptyList(),
            scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT,
            useSublimations = true,
            useRunes = false
        )

    private fun maxDamageParams() =
        mostMasteriesParams().copy(
            targetStats = TargetStats(emptyList()),
            scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE,
            damageScenario = DamageScenario(SpellElement.FIRE, RangeBand.DISTANCE, Orientation.FACE)
        )

    private fun solve(
        params: WakfuBestBuildParams,
        pool: List<Equipment>,
        subs: List<Sublimation>,
    ) = runBlocking {
        WakfuBuildSolver
            .optimize(params, pool.groupBy { it.itemType }, emptyList(), subs, WakfuBuildSolver.SolverTuning(numSearchWorkers = 4))
            .toList()
            .last()
    }

    private fun BuildCombination.subIds() =
        sublimations.values
            .flatten()
            .map { it.stateId }
            .toSet()

    @Test
    fun `the solver wears Denouement with Neutralite and the critical mastery, and the scorer agrees`() {
        val params = mostMasteriesParams()
        val result = solve(params, basePool, listOf(denouement, neutralite))
        val build = result.individual
        assertThat(build.subIds()).contains(denouement.stateId, neutralite.stateId)
        assertThat(build.equipments).contains(critItem)
        assertThat(result.isOptimal).isTrue()
        assertThat(WakfuBestBuildFinderAlgorithm.rescore(params, build)).isEqualByComparingTo(result.matchPercentage)
    }

    @Test
    fun `without Denouement Neutralite still excludes the critical mastery`() {
        val build = solve(maxDamageParams(), basePool, listOf(neutralite)).individual
        val carriesCrit = critItem in build.equipments
        val carriesNeutralite = neutralite.stateId in build.subIds()
        assertThat(carriesCrit && carriesNeutralite).isFalse()
    }

    @Test
    fun `Ravage still breaks Neutralite beside Denouement`() {
        val params = mostMasteriesParams()
        val build = solve(params, basePool, listOf(denouement, neutralite, ravage)).individual
        assertThat(build.subIds().containsAll(listOf(ravage.stateId, neutralite.stateId))).isFalse()
    }

    @Test
    fun `below 40 critical hit Denouement is inactive and the critical mastery breaks Neutralite`() {
        val noRate = listOf(critItem, fireItem, epicCarrier)
        val build = solve(mostMasteriesParams(), noRate, listOf(denouement, neutralite)).individual
        assertThat(build.subIds()).doesNotContain(denouement.stateId)
        assertThat(critItem in build.equipments && neutralite.stateId in build.subIds()).isFalse()
    }

    @Test
    fun `Ambition's start-of-turn critical hit does not feed Denouement's condition`() {
        // 30 % on the sheet: Ambition's +15 would reach 40, but it lands after Dénouement's test.
        val preSub = mapOf(Characteristic.CRITICAL_HIT to 30, Characteristic.MASTERY_CRITICAL to 600)
        val out = sublimationFixedContributions(listOf(denouement, ambition), preSub, ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT, null, 100)
        assertThat(out[Characteristic.MASTERY_CRITICAL] ?: 0).describedAs("nothing moved").isEqualTo(0)
        assertThat(out[Characteristic.CRITICAL_HIT] ?: 0).describedAs("600 critical mastery breaks Ambition").isEqualTo(0)
    }

    @Test
    fun `the scorer reads Neutralite after the conversion, and only above 40 critical hit`() {
        val mode = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT
        val di =
            neutralite.effects
                .filterIsInstance<SublimationEffect.Flat>()
                .single { it.characteristic == Characteristic.DAMAGE_INFLICTED }
                .value
        val preSub = mapOf(Characteristic.CRITICAL_HIT to 40, Characteristic.MASTERY_CRITICAL to 600)
        val active = sublimationFixedContributions(listOf(denouement, neutralite), preSub, mode, null, 100)
        assertThat(active[Characteristic.DAMAGE_INFLICTED]).isEqualTo(di)
        assertThat(active[Characteristic.MASTERY_CRITICAL]).isEqualTo(-600)
        val below = sublimationFixedContributions(listOf(denouement, neutralite), preSub + (Characteristic.CRITICAL_HIT to 39), mode, null, 100)
        assertThat(below[Characteristic.DAMAGE_INFLICTED] ?: 0).isEqualTo(0)
        // Ravage's start-of-combat masteries land on the first-turn sheet and are not moved: they still break it.
        val ravaged = sublimationFixedContributions(listOf(denouement, neutralite, ravage), preSub, mode, null, 100)
        assertThat(ravaged[Characteristic.DAMAGE_INFLICTED] ?: 0).isEqualTo(0)
    }

    @Test
    fun `max-damage takes the combo and the AP-cell ledger covers it`() {
        val params = maxDamageParams()
        val result = solve(params, basePool, listOf(denouement, neutralite))
        assertThat(result.individual.subIds()).contains(denouement.stateId, neutralite.stateId)
        val certificate = WakfuBuildSolver.maxDamageCertificate(params, basePool.groupBy { it.itemType }, emptyList(), listOf(denouement, neutralite))
        assertThat(requireNotNull(certificate?.maxCellObjective)).isGreaterThanOrEqualTo(requireNotNull(result.maxDamageObjective))
    }

    /** Seeded pools with Dénouement and the Neutralité family in play: every bound stays above the CP-SAT optimum. */
    @Test
    fun `the AP-cell ledger and the soft certificate stay above the optimum on seeded pools`() {
        val subs = listOf(denouement, neutralite, ambition, subsById.getValue(6933), ravage)
        var combos = 0
        repeat(24) { seed ->
            val random = Random(seed)
            val slots = listOf(ItemType.AMULET, ItemType.BELT, ItemType.HELMET, ItemType.BOOTS, ItemType.CAPE, ItemType.CHEST_PLATE)
            val pool =
                slots
                    .flatMapIndexed { slotIndex, slot ->
                        List(3) { i ->
                            val stats = mutableMapOf<Characteristic, Int>()
                            stats[Characteristic.MASTERY_CRITICAL] = random.nextInt(-100, 500)
                            stats[Characteristic.MASTERY_ELEMENTARY_FIRE] = random.nextInt(0, 300)
                            stats[Characteristic.CRITICAL_HIT] = random.nextInt(0, 30)
                            if (random.nextBoolean()) stats[SECONDARY_MASTERY_CHARACTERISTICS.random(random)] = random.nextInt(-200, 200)
                            item(100 * (slotIndex + 1) + i, slot, stats, if (i == 0 && slotIndex == 0) Rarity.EPIC else Rarity.LEGENDARY)
                        }
                    }.groupBy { it.itemType }
            val free = maxDamageParams()
            val freeOptimum = solve(free, pool.values.flatten(), subs)
            if (freeOptimum.individual.subIds().containsAll(listOf(denouement.stateId, neutralite.stateId))) combos++
            val ledger = WakfuBuildSolver.maxDamageCertificate(free, pool, emptyList(), subs)
            assertThat(ledger?.maxCellObjective).describedAs("seed $seed bailed").isNotNull()
            assertThat(ledger!!.maxCellObjective!!).describedAs("seed $seed").isGreaterThanOrEqualTo(requireNotNull(freeOptimum.maxDamageObjective))

            val soft = free.copy(targetStats = TargetStats(listOf(TargetStat(Characteristic.ACTION_POINT, 7))))
            var softOptimum: Long? = null
            runBlocking {
                WakfuBuildSolver
                    .optimize(soft, pool, emptyList(), subs, WakfuBuildSolver.SolverTuning(numSearchWorkers = 4), hardConstraints = false)
                    .collect { softOptimum = it.maxDamageObjective ?: softOptimum }
            }
            val production =
                MaxDamageSoftCertificate.bound(
                    soft,
                    pool,
                    emptyList(),
                    subs,
                    blockGate = false,
                    coupleSecondaryItemNegative = true,
                    netSecondaryItemBudget = true
                )
            assertThat(production).describedAs("seed $seed soft bailed").isNotNull()
            assertThat(production!!.foldedBound).describedAs("seed $seed soft").isGreaterThanOrEqualTo(requireNotNull(softOptimum))
        }
        assertThat(combos).describedAs("the pools must exercise the combo").isGreaterThan(0)
    }

    /**
     * The player's saved request (Xelor 65, most-masteries). With Dénouement on it, the engine used to stop at 1232 (exactly the saved
     * build: no critical mastery to convert, or Neutralité would break). It now reaches 1384 with converted critical mastery and
     * Neutralité active. The free search keeps a +20 % damage EPIC sub on this request (Mesure III or Inflexibilité II, 1392, proven): the combo is legal, not always the best.
     */
    @Tag("slow")
    @Test
    fun `the player's Xelor 65 request converts critical mastery beside Neutralite`() {
        val params =
            WakfuBestBuildParams(
                character = Character(CharacterClass.XELOR, 65, 0, CharacterSkills(65)),
                targetStats =
                    TargetStats(
                        listOf(
                            TargetStat(Characteristic.ACTION_POINT, 12),
                            TargetStat(Characteristic.MOVEMENT_POINT, 5),
                            TargetStat(Characteristic.MASTERY_CRITICAL, 1),
                            TargetStat(Characteristic.CRITICAL_HIT, 70),
                            TargetStat(Characteristic.MASTERY_ELEMENTARY_FIRE, 1)
                        )
                    ),
                searchDuration = 300.seconds,
                stopWhenBuildMatch = false,
                maxRarity = Rarity.EPIC,
                forcedItems = emptyList(),
                excludedItems = listOf("Torse des Mouss'", "Anneau Mouss'", "Bottes Mouss'"),
                excludedSublimations = listOf("Vélocité II", "Vivacité II", "Visibilité II", "Influence vitale III"),
                scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT
            )

        fun search(p: WakfuBestBuildParams) = runBlocking { WakfuBestBuildFinderAlgorithm.run(p).toList().last() }

        val forced = search(params.copy(forcedSublimations = listOf(denouement.name.fr)))
        val build = forced.individual
        assertThat(build.subIds()).contains(denouement.stateId, neutralite.stateId)
        assertThat(build.equipments.sumOf { it.characteristics[Characteristic.MASTERY_CRITICAL] ?: 0 }).isGreaterThan(0)
        assertThat(forced.matchPercentage.toDouble()).isGreaterThan(1232.0)
        assertThat(WakfuBestBuildFinderAlgorithm.rescore(params, build)).isEqualByComparingTo(forced.matchPercentage)
        assertThat(search(params).matchPercentage).isGreaterThanOrEqualTo(forced.matchPercentage)
    }

    /** The data this reading rests on: a data refresh that breaks one of these needs a review of the sheets. */
    @Test
    fun `the client's condition moments match the sheets the engine reads`() {
        val modelled = WakfuBestBuildFinderAlgorithm.sublimations.filter { it.solverChoosable && it.condition != null }
        assertThat(modelled.filter { it.condition?.testedAt == ConditionTestMoment.START_OF_TURN }.map { it.condition?.type }.toSet())
            .containsExactly(SublimationConditionType.SECONDARY_MASTERIES_AT_MOST)
        assertThat(denouement.condition?.testedAt).isEqualTo(ConditionTestMoment.START_OF_COMBAT)
        assertThat(neutralite.condition?.testedAt).isEqualTo(ConditionTestMoment.START_OF_TURN)
        assertThat(ambition.condition?.testedAt).isEqualTo(ConditionTestMoment.START_OF_TURN)

        // A secondary cap tested at start of combat shares its phase with the conversion: it must not be able to meet one.
        val combatTestedCaps =
            WakfuBestBuildFinderAlgorithm.sublimations.filter {
                it.condition?.type == SublimationConditionType.SECONDARY_MASTERIES_AT_MOST && it.condition?.testedAt == ConditionTestMoment.START_OF_COMBAT
            }
        assertThat(combatTestedCaps.map { it.stateId }).containsExactly(7256)
        val conversions = WakfuBestBuildFinderAlgorithm.sublimations.filter { it.kind == SublimationKind.CONVERSION }
        assertThat((combatTestedCaps + conversions).map { it.rarity }.toSet()).containsExactly(SublimationRarity.EPIC)
    }
}
