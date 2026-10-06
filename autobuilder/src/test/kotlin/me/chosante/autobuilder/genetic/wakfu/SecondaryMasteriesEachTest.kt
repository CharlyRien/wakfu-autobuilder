package me.chosante.autobuilder.genetic.wakfu

import kotlinx.coroutines.runBlocking
import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.autobuilder.domain.DamageScenario
import me.chosante.autobuilder.domain.Orientation
import me.chosante.autobuilder.domain.PassiveCatalog
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
import me.chosante.common.SECONDARY_MASTERY_CHARACTERISTICS
import me.chosante.common.Sublimation
import me.chosante.common.SublimationConditionType
import me.chosante.common.skills.CharacterSkills
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/**
 * The Neutralité family (`SECONDARY_MASTERIES_AT_MOST`: Neutralité, Ambition, Inflexibilité, Prétention, Abandon) holds
 * EACH secondary mastery ≤ 0 on its own — the game's criterion is an `and` of six per-stat atoms (State 67 →
 * StaticEffect 68). It used to be read as their SUM, so a positive mastery offset by a negative one kept the bonus.
 */
class SecondaryMasteriesEachTest {
    private val subsById = WakfuBestBuildFinderAlgorithm.sublimations.associateBy { it.stateId }
    private val neutraliteIII = subsById.getValue(6931)
    private val ambitionIII = subsById.getValue(7115)
    private val inflexibiliteII = subsById.getValue(7256)

    /**
     * The player's report (October 2026): a saved Xelor 200 most-masteries build (fire / water, distance and critical
     * mastery rows, scenario fire / distance / back, forced passive Mémoire) carrying Neutralité III + Ambition III +
     * Inflexibilité II with first-turn distance +76, critical +240, rear −304 and berserk −12. The SUM is 0, so the app
     * showed all three active; in game none fires (each secondary must be ≤ 0). Rebuilt from the catalog by id.
     */
    private fun playerBuild(): BuildCombination {
        val level = 200
        val equipmentById = WakfuBestBuildFinderAlgorithm.equipments.associateBy { it.equipmentId }
        // The build predates the item equip conditions: it wears Épée de Brâkmar (26497) without the ring the game requires
        // with it (see the test below) — read without them, it is about the secondary-cap reading.
        val items =
            listOf(27557, 27923, 26924, 26897, 27416, 26996, 26497, 27298, 27294, 26287, 26323, 33418, 14422, 27304)
                .map { equipmentById.getValue(it).atLevel(level).copy(equipCriterion = null) }
        val byId = items.associateBy { it.equipmentId }
        val runeById = WakfuBestBuildFinderAlgorithm.runes.associateBy { it.id }
        val elem = 27094
        val runes =
            mapOf(
                27557 to List(4) { elem },
                26924 to List(4) { elem },
                26897 to List(4) { 27103 }, // dodge
                27416 to List(4) { elem },
                26996 to listOf(27107, 27107, elem, elem), // earth resistance ×2
                26497 to List(4) { 27100 }, // critical mastery ×4
                27298 to List(4) { elem },
                27294 to List(4) { elem },
                26323 to List(4) { elem },
                27304 to listOf(27098, elem, 27105, 27105) // distance mastery, fire resistance ×2
            ).map { (item, ids) -> byId.getValue(item) to ids.map { runeById.getValue(it) } }.toMap()
        val subs =
            mapOf(
                27557 to 5983, // Carnage III
                26924 to 5983,
                26897 to 6931, // Neutralité III
                27416 to 7077, // Armure lourde II
                26996 to 7088, // Poids Plume III
                26497 to 7115, // Ambition III
                26287 to 7256, // Inflexibilité II (on the EPIC off-hand)
                27298 to 7862, // Influence vitale III
                27294 to 7862,
                26323 to 8518, // Brûlure III
                27304 to 8519 // Gel III
            ).map { (item, sub) -> byId.getValue(item) to listOf(subsById.getValue(sub)) }.toMap()
        val skills = CharacterSkills(level)
        // Per branch: "Resistance Elementary" names both a Major and an Intelligence skill (the saved 10 points are the
        // Intelligence ones — the four Major points went to AP, MP, range and % damage).
        val points =
            listOf(
                skills.major to mapOf("Action Point" to 1, "Movement Point and damage" to 1, "Range and damage" to 1, "% Inflicted Damage" to 1),
                skills.intelligence to mapOf("Resistance Elementary" to 10),
                skills.strength to mapOf("Mastery Elementary" to 48, "Mastery Distance" to 2),
                skills.agility to mapOf("Dodge" to 50),
                skills.luck to mapOf("% Critical Hit" to 16, "Mastery Back" to 21, "Mastery Berserk" to 12)
            )
        for ((branch, assigned) in points) {
            for (skill in branch.getCharacteristics()) assigned[skill.name]?.let { skill.setPointAssigned(it) }
        }
        val memoire = PassiveCatalog.forClass(CharacterClass.XELOR).single { it.spellId == 756 }
        return BuildCombination(items, skills, runes, subs, listOf(memoire))
    }

    private val playerTargets =
        TargetStats(
            listOf(
                TargetStat(Characteristic.ACTION_POINT, 13, 5),
                TargetStat(Characteristic.MOVEMENT_POINT, 4, 1),
                TargetStat(Characteristic.RANGE, 2, 3),
                TargetStat(Characteristic.CRITICAL_HIT, 100, 5),
                TargetStat(Characteristic.MASTERY_DISTANCE, 1, 1),
                TargetStat(Characteristic.MASTERY_CRITICAL, 1, 1),
                TargetStat(Characteristic.MASTERY_ELEMENTARY_FIRE, 1, 1),
                TargetStat(Characteristic.MASTERY_ELEMENTARY_WATER, 1, 1),
                TargetStat(Characteristic.RESISTANCE_ELEMENTARY, 640, 4),
                TargetStat(Characteristic.DODGE, 1000, 2)
            )
        )

    private fun achieved(build: BuildCombination): Map<Characteristic, Int> =
        computeCharacteristicsValues(
            build,
            Character(CharacterClass.XELOR, 200, 125, build.characterSkills).baseCharacteristicValues,
            playerTargets.masteryElementsWanted,
            playerTargets.resistanceElementsWanted,
            scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT,
            masteryElementsToMinimize = playerTargets.masteryElementsToMinimize
        )

    private fun BuildCombination.without(removed: Set<Sublimation>) = copy(sublimations = sublimations.mapValues { (_, subs) -> subs - removed })

    @Test
    fun `the player's Xelor build is credited none of the three secondary-cap bonuses`() {
        val build = playerBuild()
        assertThat(build.isValid()).isTrue()
        // ... which the game would not let the player wear: its Brâkmar sword needs Anneau de Brâkmar (an EPIC ring, so
        // no EPIC off-hand beside it) — the reported bug the item equip conditions fix.
        val catalog = WakfuBestBuildFinderAlgorithm.equipments.associateBy { it.equipmentId }
        assertThat(build.copy(equipments = build.equipments.map { catalog.getValue(it.equipmentId) }).isValid()).isFalse()
        val caps = setOf(neutraliteIII, ambitionIII, inflexibiliteII)
        val capFree = achieved(build.without(caps))
        // The build's own secondary masteries (the caps add none): their SUM is 0 — the old reading held — but distance
        // and critical mastery are positive, so in game (and now here) none of the three fires.
        assertThat(SECONDARY_MASTERY_CHARACTERISTICS.associateWith { capFree[it] ?: 0 })
            .containsEntry(Characteristic.MASTERY_DISTANCE, 76)
            .containsEntry(Characteristic.MASTERY_CRITICAL, 240)
            .containsEntry(Characteristic.MASTERY_BACK, -304)
            .containsEntry(Characteristic.MASTERY_BERSERK, -12)
            .containsEntry(Characteristic.MASTERY_MELEE, 0)
            .containsEntry(Characteristic.MASTERY_HEALING, 0)
        assertThat(SECONDARY_MASTERY_CHARACTERISTICS.sumOf { capFree[it] ?: 0 }).isEqualTo(0)

        val full = achieved(build)
        // Neutralité III (+24 % DI), Inflexibilité II (+20 % DI) and Ambition III (+15 % crit) credit nothing.
        assertThat(full[Characteristic.DAMAGE_INFLICTED]).isEqualTo(capFree[Characteristic.DAMAGE_INFLICTED])
        assertThat(full[Characteristic.CRITICAL_HIT]).isEqualTo(capFree[Characteristic.CRITICAL_HIT])
        assertThat(full).isEqualTo(capFree)
        // The saved build's achieved stats (computed under the sum) read 70 % DI and 100 % crit.
        assertThat(full[Characteristic.DAMAGE_INFLICTED]).isEqualTo(70 - 24 - 20)
        assertThat(full[Characteristic.CRITICAL_HIT]).isEqualTo(100 - 15)

        val score = FindMostMasteriesFromInputScoring.computeScore(playerTargets, build, Character(CharacterClass.XELOR, 200, 125, build.characterSkills).baseCharacteristicValues)
        val capFreeScore =
            FindMostMasteriesFromInputScoring.computeScore(
                playerTargets,
                build.without(caps),
                Character(CharacterClass.XELOR, 200, 125, build.characterSkills).baseCharacteristicValues
            )
        println("PLAYER_BUILD score=$score (saved 5830.0) DI=${full[Characteristic.DAMAGE_INFLICTED]} CC=${full[Characteristic.CRITICAL_HIT]}")
        assertThat(score).isEqualByComparingTo(capFreeScore)
    }

    @Test
    fun `a secondary mastery offset within its own stat keeps the cap, across stats it breaks it`() {
        fun contributions(vararg preSub: Pair<Characteristic, Int>) =
            sublimationFixedContributions(
                listOf(neutraliteIII),
                preSub.toMap(),
                ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE,
                null,
                200
            )
        // Every secondary ≤ 0 (negatives included): active.
        assertThat(contributions(Characteristic.MASTERY_BACK to -304, Characteristic.MASTERY_BERSERK to -12))
            .containsEntry(Characteristic.DAMAGE_INFLICTED, 24)
        assertThat(contributions(Characteristic.MASTERY_DISTANCE to 0)).containsEntry(Characteristic.DAMAGE_INFLICTED, 24)
        // +76 distance offset by −304 rear (sum < 0): one stat is positive ⇒ inactive.
        assertThat(contributions(Characteristic.MASTERY_DISTANCE to 76, Characteristic.MASTERY_BACK to -304))
            .doesNotContainKey(Characteristic.DAMAGE_INFLICTED)
        // Each secondary on its own, against every other secondary's −1000.
        for (positive in SECONDARY_MASTERY_CHARACTERISTICS) {
            val sheet = SECONDARY_MASTERY_CHARACTERISTICS.associateWith { if (it == positive) 1 else -1_000 }
            assertThat(sublimationFixedContributions(listOf(neutraliteIII), sheet, ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE, null, 200))
                .describedAs("%s = +1, every other secondary −1000", positive)
                .doesNotContainKey(Characteristic.DAMAGE_INFLICTED)
        }
        // The spec reads EACH stat.
        val spec = subConditionSpec(neutraliteIII.condition, 200) as SubConditionSpec.StatBound
        assertThat(spec.stats.toSet()).isEqualTo(SECONDARY_MASTERY_CHARACTERISTICS)
        assertThat(spec.holdsOn { if (it == Characteristic.MASTERY_DISTANCE) 1 else -1_000 }).isFalse()
        assertThat(spec.holdsOn { if (it == Characteristic.MASTERY_DISTANCE) -5 else 0 }).isTrue()
        assertThat(neutraliteIII.condition?.type).isEqualTo(SublimationConditionType.SECONDARY_MASTERIES_AT_MOST)
    }

    /**
     * CP-SAT: a cap carrier cannot offset across stats but can within one. Fire / distance / face, Neutralité III
     * choosable, no runes. The helmet's +100 distance can be cancelled by the cape's −100 distance (same stat: the cap
     * holds, +24 % DI) but not by the other cape's −100 rear (the sum would be 0, yet distance stays +100). The SUM
     * reading's optimum was that rear cape with Neutralité; the per-stat optimum takes the distance cape with it.
     */
    @Test
    fun `the solver offsets a secondary within its own stat, never across stats`(): Unit =
        runBlocking {
            fun item(
                id: Int,
                type: ItemType,
                stats: Map<Characteristic, Int>,
            ) = Equipment(
                equipmentId = id,
                guiId = id,
                level = 200,
                name = I18nText("se$id", "se$id", "", ""),
                rarity = Rarity.LEGENDARY,
                itemType = type,
                characteristics = stats,
                maxShardSlots = 3
            )
            val helmet = item(901, ItemType.HELMET, mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 3_000, Characteristic.MASTERY_DISTANCE to 100))
            val rearCape = item(902, ItemType.CAPE, mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 2_000, Characteristic.MASTERY_BACK to -100))
            val distanceCape = item(903, ItemType.CAPE, mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 2_000, Characteristic.MASTERY_DISTANCE to -100))
            val pool = listOf(helmet, rearCape, distanceCape).groupBy { it.itemType }
            val scenario = DamageScenario(element = SpellElement.FIRE, rangeBand = RangeBand.DISTANCE, orientation = Orientation.FACE)
            val params =
                WakfuBestBuildParams(
                    character = Character(CharacterClass.CRA, 200, 0, CharacterSkills(200)),
                    targetStats = TargetStats(listOf(TargetStat(Characteristic.MASTERY_DISTANCE, 1))),
                    searchDuration = 60.seconds,
                    stopWhenBuildMatch = false,
                    maxRarity = Rarity.EPIC,
                    forcedItems = emptyList(),
                    excludedItems = emptyList(),
                    scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE,
                    damageScenario = scenario,
                    useRunes = false,
                    useSublimations = true
                )
            val tuning = WakfuBuildSolver.SolverTuning(numSearchWorkers = 1, randomSeed = 1, interleaveSearch = true, maxDeterministicTime = 60.0)
            var last: me.chosante.autobuilder.genetic.SolverResult<BuildCombination>? = null
            WakfuBuildSolver.optimize(params, pool, emptyList(), listOf(neutraliteIII), tuning, hardConstraints = false).collect { last = it }
            val build = requireNotNull(last).individual
            val chosenSubs = build.sublimations.values.flatten()
            assertThat(build.equipments.map { it.equipmentId }).containsExactlyInAnyOrder(901, 903)
            assertThat(chosenSubs).containsExactly(neutraliteIII)
            // The re-scorer agrees: distance nets to 0 within its own stat, the cap holds, the +24 % DI is credited.
            val stats =
                computeCharacteristicsValues(
                    build,
                    params.character.baseCharacteristicValues,
                    params.targetStats.masteryElementsWanted,
                    params.targetStats.resistanceElementsWanted,
                    scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE,
                    damageScenario = scenario
                )
            assertThat(stats[Characteristic.MASTERY_DISTANCE] ?: 0).isLessThanOrEqualTo(0)
            assertThat(stats[Characteristic.DAMAGE_INFLICTED] ?: 0).isGreaterThanOrEqualTo(24)

            // The rear cape's cross-stat "offset" is no offset: with it forced, Neutralité cannot be carried.
            var rearLast: me.chosante.autobuilder.genetic.SolverResult<BuildCombination>? = null
            val rearOnly = mapOf(ItemType.HELMET to listOf(helmet), ItemType.CAPE to listOf(rearCape))
            WakfuBuildSolver.optimize(params, rearOnly, emptyList(), listOf(neutraliteIII), tuning, hardConstraints = false).collect { rearLast = it }
            val rearBuild = requireNotNull(rearLast).individual
            assertThat(rearBuild.equipments.map { it.equipmentId }).containsExactlyInAnyOrder(901, 902)
            assertThat(rearBuild.sublimations.values.flatten()).doesNotContain(neutraliteIII)
        }
}
