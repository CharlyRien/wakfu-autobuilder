package me.chosante.autobuilder.genetic.wakfu

import com.google.ortools.sat.CpSolverStatus
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
import me.chosante.common.Equipment
import me.chosante.common.I18nText
import me.chosante.common.ItemType
import me.chosante.common.Rarity
import me.chosante.common.skills.CharacterSkills
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

/**
 * Locks the per-element-row fold: a request reading one elemental family through several per-element rows (the four rows
 * the GUI sends for "all resistances", fire + water mastery in precision…) folds that family ONCE — each random-element
 * roll on exactly `min(k, wanted)` of its wanted elements — and the scorers place the rolls at the exact optimum of the same
 * objective ([ElementRowObjective]). Before, each row folded on its own and credited every random roll in full, so a
 * "+X resistance on 1 random element" line counted on all four elements: the hard leg called targets met that the scorer
 * (which places each roll once) read as missed.
 */
class PerElementRowFoldTest {
    private val tuning = WakfuBuildSolver.SolverTuning(numSearchWorkers = 1, randomSeed = 1, interleaveSearch = true, maxDeterministicTime = 30.0)

    private fun params(
        mode: ScoreComputationMode,
        targets: List<TargetStat>,
    ) = WakfuBestBuildParams(
        character = Character(CharacterClass.CRA, 1, 1, CharacterSkills(1)),
        targetStats = TargetStats(targets),
        searchDuration = 5.seconds,
        stopWhenBuildMatch = false,
        maxRarity = Rarity.EPIC,
        forcedItems = emptyList(),
        excludedItems = emptyList(),
        scoreComputationMode = mode,
        useRunes = false,
        useSublimations = false
    )

    private fun item(
        id: Int,
        type: ItemType,
        stats: Map<Characteristic, Int>,
    ) = Equipment(
        equipmentId = id,
        guiId = id,
        level = 1,
        name = I18nText("item$id", "item$id", "", ""),
        rarity = Rarity.LEGENDARY,
        itemType = type,
        characteristics = stats,
        maxShardSlots = 0
    )

    /** The stats the scorer of [p]'s mode resolves for [build] — the exact `computeCharacteristicsValues` call it makes. */
    private fun scorerStats(
        p: WakfuBestBuildParams,
        build: BuildCombination,
    ): Map<Characteristic, Int> {
        val ts = p.targetStats
        val base = p.character.baseCharacteristicValues
        val rows = ts.elementRowObjectives(p.scoreComputationMode)
        return when (p.scoreComputationMode) {
            ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT ->
                computeCharacteristicsValues(
                    build,
                    base,
                    ts.masteryElementsWanted,
                    ts.resistanceElementsWanted,
                    scoreComputationMode = p.scoreComputationMode,
                    masteryElementsToMinimize = ts.masteryElementsToMinimize,
                    resistanceElementsToMinimize = if (ts.any { it.characteristic == Characteristic.RESISTANCE_ELEMENTARY }) ts.resistanceElementsWanted.keys.toList() else null,
                    elementRows = rows
                )

            ScoreComputationMode.FIND_CLOSEST_BUILD_FROM_INPUT ->
                computeCharacteristicsValues(
                    build,
                    base,
                    ts.masteryElementsWanted,
                    ts.resistanceElementsWanted,
                    scoreComputationMode = p.scoreComputationMode,
                    elementRows = rows
                )

            ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE ->
                computeCharacteristicsValues(
                    build,
                    base,
                    mapOf(p.damageScenario.element.masteryCharacteristic to 1),
                    ts.resistanceElementsWanted,
                    scoreComputationMode = p.scoreComputationMode,
                    damageScenario = p.damageScenario,
                    elementRows = rows
                )
        }
    }

    private fun score(
        p: WakfuBestBuildParams,
        items: List<Equipment>,
    ): BigDecimal {
        val build = BuildCombination(items, CharacterSkills(1))
        return when (p.scoreComputationMode) {
            ScoreComputationMode.FIND_CLOSEST_BUILD_FROM_INPUT -> FindClosestBuildFromInputScoring.computeScore(p.targetStats, build, p.character.baseCharacteristicValues)
            ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT ->
                FindMostMasteriesFromInputScoring.computeScore(
                    p.targetStats,
                    build,
                    p.character.baseCharacteristicValues
                )
            ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE -> FindMaxDamageScoring.computeScore(p.targetStats, build, p.character.baseCharacteristicValues, p.damageScenario)
        }
    }

    /** Every required row (target > 0) met by the scorer's [stats]: per-element rows on their element, the aggregate on the min. */
    private fun scorerMeetsRequired(
        p: WakfuBestBuildParams,
        stats: Map<Characteristic, Int>,
    ): Boolean = p.targetStats.filter { it.characteristic.isRequiredMostMasteriesTarget() && it.target > 0 }.all { (stats[it.characteristic] ?: 0) >= it.target }

    /** Every precision row met by [stats] (an aggregate row on each of its four elements): the score then reads above 100 %. */
    private fun precisionMeetsEveryRow(
        p: WakfuBestBuildParams,
        stats: Map<Characteristic, Int>,
    ): Boolean =
        p.targetStats.all { row ->
            val family = ElementFamily.entries.firstOrNull { it.aggregate == row.characteristic }
            (family?.elements ?: listOf(row.characteristic)).all { (stats[it] ?: 0) >= row.target }
        }

    // ---- The audit's repro (each: one pool, the OPTIMAL build on the full pool must be the scorer's best) -------------

    private fun assertOptimumIsScorerBest(
        p: WakfuBestBuildParams,
        pool: List<Equipment>,
        hard: Boolean,
        scorerBestId: Int,
    ) {
        // Skills pinned to none, like the scorer's bare builds (the level-1 character's one point adds generic resistance).
        val solved = WakfuBuildSolver.elementRowSolveForTest(p, mapOf(pool.first().itemType to pool), tuning, hardConstraints = hard, pinSkillsToZero = true)
        assertThat(solved.isOptimal).describedAs("status %s", solved.status).isTrue()
        val picked = solved.build!!.equipments.map { it.equipmentId }
        val scores = pool.associate { it.equipmentId to score(p, listOf(it)) }
        assertThat(scores.maxBy { it.value }.key).describedAs("scorer's best of %s", scores).isEqualTo(scorerBestId)
        assertThat(picked).describedAs("model optimum vs scorer scores %s", scores).containsExactly(scorerBestId)
    }

    @Test
    fun `precision - fire and water mastery rows place a one-element roll once`() {
        // Item 1's 100 on ONE random element fills one row (capped sum 100); item 2's 60 + 60 fills 120. The old per-row
        // fold credited item 1's roll to fire AND water (200) and picked it.
        assertOptimumIsScorerBest(
            params(
                ScoreComputationMode.FIND_CLOSEST_BUILD_FROM_INPUT,
                listOf(TargetStat(Characteristic.MASTERY_ELEMENTARY_FIRE, 100), TargetStat(Characteristic.MASTERY_ELEMENTARY_WATER, 100))
            ),
            listOf(
                item(1, ItemType.AMULET, mapOf(Characteristic.MASTERY_ELEMENTARY_ONE_RANDOM_ELEMENT to 100)),
                item(2, ItemType.AMULET, mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 60, Characteristic.MASTERY_ELEMENTARY_WATER to 60))
            ),
            hard = false,
            scorerBestId = 2
        )
    }

    @Test
    fun `most-masteries hard leg - two resistance rows cannot both be met by a one-element roll`() {
        assertOptimumIsScorerBest(
            params(
                ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT,
                listOf(
                    TargetStat(Characteristic.MASTERY_DISTANCE, 1),
                    TargetStat(Characteristic.RESISTANCE_ELEMENTARY_FIRE, 100),
                    TargetStat(Characteristic.RESISTANCE_ELEMENTARY_WATER, 100)
                )
            ),
            listOf(
                item(1, ItemType.AMULET, mapOf(Characteristic.RESISTANCE_ELEMENTARY_ONE_RANDOM_ELEMENT to 100, Characteristic.MASTERY_DISTANCE to 50)),
                item(
                    2,
                    ItemType.AMULET,
                    mapOf(
                        Characteristic.RESISTANCE_ELEMENTARY_FIRE to 100,
                        Characteristic.RESISTANCE_ELEMENTARY_WATER to 100,
                        Characteristic.MASTERY_DISTANCE to 10
                    )
                )
            ),
            hard = true,
            scorerBestId = 2
        )
    }

    @Test
    fun `most-masteries hard leg - the four rows of all resistances cannot all be met by a three-element roll`() {
        assertOptimumIsScorerBest(
            params(
                ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT,
                listOf(TargetStat(Characteristic.MASTERY_DISTANCE, 1)) + ElementFamily.RESISTANCE.elements.map { TargetStat(it, 100) }
            ),
            listOf(
                item(1, ItemType.AMULET, mapOf(Characteristic.RESISTANCE_ELEMENTARY_THREE_RANDOM_ELEMENT to 100, Characteristic.MASTERY_DISTANCE to 50)),
                item(2, ItemType.AMULET, mapOf(Characteristic.RESISTANCE_ELEMENTARY to 100, Characteristic.MASTERY_DISTANCE to 10))
            ),
            hard = true,
            scorerBestId = 2
        )
    }

    @Test
    fun `max-damage hard leg - two resistance rows cannot both be met by a one-element roll`() {
        val p =
            params(
                ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE,
                listOf(TargetStat(Characteristic.RESISTANCE_ELEMENTARY_FIRE, 100), TargetStat(Characteristic.RESISTANCE_ELEMENTARY_WATER, 100))
            )
        val pool =
            listOf(
                item(1, ItemType.AMULET, mapOf(Characteristic.RESISTANCE_ELEMENTARY_ONE_RANDOM_ELEMENT to 100, Characteristic.MASTERY_ELEMENTARY_FIRE to 50)),
                item(
                    2,
                    ItemType.AMULET,
                    mapOf(
                        Characteristic.RESISTANCE_ELEMENTARY_FIRE to 100,
                        Characteristic.RESISTANCE_ELEMENTARY_WATER to 100,
                        Characteristic.MASTERY_ELEMENTARY_FIRE to 10
                    )
                )
            )
        // Item 1 hits harder but meets one row only: the hard leg must hold only item 2, which meets both.
        val solved = WakfuBuildSolver.elementRowSolveForTest(p, mapOf(ItemType.AMULET to pool), tuning, hardConstraints = true, pinSkillsToZero = true)
        assertThat(solved.isOptimal).isTrue()
        assertThat(solved.build!!.equipments.map { it.equipmentId }).containsExactly(2)
        assertThat(scorerMeetsRequired(p, scorerStats(p, solved.build))).isTrue()
        val pinnedItem1 =
            WakfuBuildSolver.elementRowSolveForTest(
                p,
                mapOf(ItemType.AMULET to pool),
                tuning,
                hardConstraints = true,
                pinnedEquipmentIds = setOf(1),
                pinSkillsToZero = true
            )
        assertThat(pinnedItem1.status).isEqualTo(CpSolverStatus.INFEASIBLE)
    }

    @Test
    fun `controls - the aggregate resistance row and the maximized mastery rows keep their exact folds`() {
        assertOptimumIsScorerBest(
            params(
                ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT,
                listOf(TargetStat(Characteristic.MASTERY_DISTANCE, 1), TargetStat(Characteristic.RESISTANCE_ELEMENTARY, 100))
            ),
            listOf(
                item(1, ItemType.AMULET, mapOf(Characteristic.RESISTANCE_ELEMENTARY_THREE_RANDOM_ELEMENT to 100, Characteristic.MASTERY_DISTANCE to 50)),
                item(2, ItemType.AMULET, mapOf(Characteristic.RESISTANCE_ELEMENTARY to 100, Characteristic.MASTERY_DISTANCE to 10))
            ),
            hard = true,
            scorerBestId = 2
        )
        assertOptimumIsScorerBest(
            params(
                ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT,
                listOf(TargetStat(Characteristic.MASTERY_ELEMENTARY_FIRE, 1), TargetStat(Characteristic.MASTERY_ELEMENTARY_WATER, 1))
            ),
            listOf(
                item(1, ItemType.AMULET, mapOf(Characteristic.MASTERY_ELEMENTARY_ONE_RANDOM_ELEMENT to 100)),
                item(2, ItemType.AMULET, mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 60, Characteristic.MASTERY_ELEMENTARY_WATER to 60))
            ),
            hard = true,
            scorerBestId = 2
        )
    }

    @Test
    fun `a single per-element row keeps its single-element fold`() {
        // One wanted element: every roll lands on it, the old (exact) fold — no joint fold, no assignment variable.
        val p =
            params(
                ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT,
                listOf(TargetStat(Characteristic.MASTERY_DISTANCE, 1), TargetStat(Characteristic.RESISTANCE_ELEMENTARY_FIRE, 150))
            )
        assertThat(p.targetStats.elementRowObjectives(p.scoreComputationMode)).isNull()
        val pool =
            listOf(
                item(
                    1,
                    ItemType.AMULET,
                    mapOf(
                        Characteristic.RESISTANCE_ELEMENTARY_ONE_RANDOM_ELEMENT to 100,
                        Characteristic.RESISTANCE_ELEMENTARY_THREE_RANDOM_ELEMENT to 60,
                        Characteristic.MASTERY_DISTANCE to 5
                    )
                )
            )
        // (The level-1 character's one skill point would add 10 generic resistance: kept out, so the fold alone is read.)
        val solved = WakfuBuildSolver.elementRowSolveForTest(p, mapOf(ItemType.AMULET to pool), tuning, hardConstraints = true, pinSkillsToZero = true)
        assertThat(solved.modelElementValues[Characteristic.RESISTANCE_ELEMENTARY_FIRE]).isEqualTo(160L)
        assertThat(scorerStats(p, solved.build!!)[Characteristic.RESISTANCE_ELEMENTARY_FIRE]).isEqualTo(160)
    }

    @Test
    fun `precision - a mastery row of target 0 below 0 halves the score, so a free roll lifts it when that is worth more`() {
        // "Fire mastery 300" beside "water mastery 0", one jointly read family (a mastery row of target 0 keeps its element wanted).
        // A "−20 elemental mastery" line leaves water at −20, which halves the whole score; one item carries "+30 mastery on 1
        // random element".
        val water = Characteristic.MASTERY_ELEMENTARY_WATER
        val fire = Characteristic.MASTERY_ELEMENTARY_FIRE
        val p = params(ScoreComputationMode.FIND_CLOSEST_BUILD_FROM_INPUT, listOf(TargetStat(fire, 300), TargetStat(water, 0)))
        val roll = item(2, ItemType.BOOTS, mapOf(Characteristic.MASTERY_ELEMENTARY_ONE_RANDOM_ELEMENT to 30))

        fun check(
            amuletFire: Int,
            expectedFire: Int,
            expectedWater: Int,
        ) {
            val amulet = item(1, ItemType.AMULET, mapOf(fire to amuletFire, Characteristic.MASTERY_ELEMENTARY to -20))
            val stats = scorerStats(p, BuildCombination(listOf(amulet, roll), CharacterSkills(1)))
            assertThat(stats[fire]).describedAs("fire, amulet fire $amuletFire").isEqualTo(expectedFire)
            assertThat(stats[water]).describedAs("water, amulet fire $amuletFire").isEqualTo(expectedWater)
            // The solver, build pinned, takes the same placement for the same objective.
            val solved =
                WakfuBuildSolver.elementRowSolveForTest(
                    p,
                    mapOf(ItemType.AMULET to listOf(amulet), ItemType.BOOTS to listOf(roll)),
                    tuning,
                    hardConstraints = false,
                    pinnedEquipmentIds = setOf(1, 2),
                    pinSkillsToZero = true
                )
            assertThat(solved.modelElementValues)
                .describedAs("model, amulet fire $amuletFire")
                .isEqualTo(mapOf(fire to expectedFire.toLong(), water to expectedWater.toLong()))
            assertThat(precisionModelObjective(p.targetStats, stats)).describedAs("objective, amulet fire $amuletFire").isEqualTo(solved.objective)
        }

        // Fire already met (310): the roll on water lifts it to 10 — 103.3 % (310 / 300 above 100 %), not the halved 50 % of the
        // roll on fire.
        check(amuletFire = 330, expectedFire = 310, expectedWater = 10)
        val metAmulet = item(1, ItemType.AMULET, mapOf(fire to 330, Characteristic.MASTERY_ELEMENTARY to -20))
        assertThat(score(p, listOf(metAmulet, roll))).isEqualByComparingTo("103.3")
        // Fire 15 short: lifting water still beats meeting fire under a halved score (285 unhalved against 315 halved).
        check(amuletFire = 305, expectedFire = 285, expectedWater = 10)
        // Fire far short (20): the roll's 30 on fire more than doubles the capped sum, worth more than the halving costs.
        check(amuletFire = 40, expectedFire = 50, expectedWater = -20)
    }

    @Test
    fun `precision - a resistance floor below 0 halves the score, so a free roll lifts it when that is worth more`() {
        // The GUI's precision request: the user's "fire resistance 300" beside the default "air resistance 0". Air is not wanted —
        // a request on one element — but it is a floor read on the family's joint fold: the game lets the player put the "+30
        // resistance on 1 random element" on air. A "−20 on all elements" line leaves air at −20, which halves the whole score.
        val fire = Characteristic.RESISTANCE_ELEMENTARY_FIRE
        val air = Characteristic.RESISTANCE_ELEMENTARY_WIND
        val p = params(ScoreComputationMode.FIND_CLOSEST_BUILD_FROM_INPUT, listOf(TargetStat(fire, 300), TargetStat(air, 0)))
        assertThat(p.targetStats.needsItemPrefilter).describedAs("one wanted element").isFalse()
        assertThat(
            p.targetStats
                .elementRowObjectives(p.scoreComputationMode)!!
                .resistance!!
                .elements
        ).containsExactly(fire, air)
        val roll = item(2, ItemType.BOOTS, mapOf(Characteristic.RESISTANCE_ELEMENTARY_ONE_RANDOM_ELEMENT to 30))

        fun check(
            amuletFire: Int,
            expectedFire: Int,
            expectedAir: Int,
        ) {
            val amulet = item(1, ItemType.AMULET, mapOf(fire to amuletFire, Characteristic.RESISTANCE_ELEMENTARY to -20))
            val stats = scorerStats(p, BuildCombination(listOf(amulet, roll), CharacterSkills(1)))
            assertThat(stats[fire]).describedAs("fire, amulet fire $amuletFire").isEqualTo(expectedFire)
            assertThat(stats[air]).describedAs("air, amulet fire $amuletFire").isEqualTo(expectedAir)
            // The solver, build pinned, takes the same placement for the same objective.
            val solved =
                WakfuBuildSolver.elementRowSolveForTest(
                    p,
                    mapOf(ItemType.AMULET to listOf(amulet), ItemType.BOOTS to listOf(roll)),
                    tuning,
                    hardConstraints = false,
                    pinnedEquipmentIds = setOf(1, 2),
                    pinSkillsToZero = true
                )
            assertThat(solved.modelElementValues)
                .describedAs("model, amulet fire $amuletFire")
                .isEqualTo(mapOf(fire to expectedFire.toLong(), air to expectedAir.toLong()))
            assertThat(solved.modelHalved).isEqualTo(expectedAir < 0)
            assertThat(precisionModelObjective(p.targetStats, stats)).describedAs("objective, amulet fire $amuletFire").isEqualTo(solved.objective)
        }

        // Fire already met (310): the roll on air lifts it to 10 — 103.3 %, not the halved 50 % of the roll on fire.
        check(amuletFire = 330, expectedFire = 310, expectedAir = 10)
        // Fire 15 short: lifting air still beats meeting fire under a halved score (285 unhalved against 315 halved).
        check(amuletFire = 305, expectedFire = 285, expectedAir = 10)
        // Fire far short (20): the roll's 30 on fire more than doubles the capped sum, worth more than the halving costs.
        check(amuletFire = 40, expectedFire = 50, expectedAir = -20)
    }

    @Test
    fun `precision halves once, like the solver, however many rows of target 0 are below 0`() {
        // The GUI's precision defaults (air resistance 0, dodge 0) beside the user's "fire resistance 300"; a ring with −5 dodge
        // breaks the dodge floor whatever the rolls, so the halving is certain: the roll goes to fire and air stays at −20 — and
        // the score must read that build halved once (50 %), not once per floor below 0 (25 %).
        val p =
            params(
                ScoreComputationMode.FIND_CLOSEST_BUILD_FROM_INPUT,
                listOf(
                    TargetStat(Characteristic.RESISTANCE_ELEMENTARY_FIRE, 300),
                    TargetStat(Characteristic.RESISTANCE_ELEMENTARY_WIND, 0),
                    TargetStat(Characteristic.DODGE, 0)
                )
            )
        val amulet = item(1, ItemType.AMULET, mapOf(Characteristic.RESISTANCE_ELEMENTARY_FIRE to 310, Characteristic.RESISTANCE_ELEMENTARY to -20))
        val ring = item(2, ItemType.RING, mapOf(Characteristic.DODGE to -5))
        val roll = item(3, ItemType.BOOTS, mapOf(Characteristic.RESISTANCE_ELEMENTARY_ONE_RANDOM_ELEMENT to 30))

        fun solved(vararg items: Equipment) =
            WakfuBuildSolver.elementRowSolveForTest(
                p,
                items.groupBy { it.itemType },
                tuning,
                hardConstraints = false,
                pinnedEquipmentIds = items.map { it.equipmentId }.toSet(),
                pinSkillsToZero = true
            )

        val withRoll = BuildCombination(listOf(amulet, ring, roll), CharacterSkills(1))
        val stats = scorerStats(p, withRoll)
        assertThat(stats[Characteristic.RESISTANCE_ELEMENTARY_FIRE]).isEqualTo(320)
        assertThat(stats[Characteristic.RESISTANCE_ELEMENTARY_WIND]).isEqualTo(-20)
        assertThat(score(p, withRoll.equipments)).isEqualByComparingTo("50.0")
        val model = solved(amulet, ring, roll)
        assertThat(model.modelElementValues)
            .isEqualTo(mapOf(Characteristic.RESISTANCE_ELEMENTARY_FIRE to 320L, Characteristic.RESISTANCE_ELEMENTARY_WIND to -20L))
        assertThat(precisionModelObjective(p.targetStats, stats)).isEqualTo(model.objective).isEqualTo(49_500L)

        // No roll at all: air −20 and dodge −5, two rows of target 0 below 0 — the solver and the score halve once, no more.
        val twoBelow = BuildCombination(listOf(amulet, ring), CharacterSkills(1))
        val twoBelowStats = scorerStats(p, twoBelow)
        assertThat(twoBelowStats[Characteristic.RESISTANCE_ELEMENTARY_WIND]).isEqualTo(-20)
        assertThat(twoBelowStats[Characteristic.DODGE]).isEqualTo(-5)
        assertThat(score(p, twoBelow.equipments)).isEqualByComparingTo("48.33")
        assertThat(precisionModelObjective(p.targetStats, twoBelowStats)).isEqualTo(solved(amulet, ring).objective).isEqualTo(47_850L)
    }

    // ---- Seeded fuzz: per build, what the model claims is what the scorer places --------------------------------------

    private class FuzzCase(
        val p: WakfuBestBuildParams,
        val pool: Map<ItemType, List<Equipment>>,
    )

    private fun fuzzCase(
        random: Random,
        mode: ScoreComputationMode,
    ): FuzzCase {
        val families =
            when (mode) {
                ScoreComputationMode.FIND_CLOSEST_BUILD_FROM_INPUT ->
                    listOf(ElementFamily.MASTERY, ElementFamily.RESISTANCE)
                        .filter {
                            random.nextBoolean()
                        }.ifEmpty { listOf(ElementFamily.MASTERY) }
                else -> listOf(ElementFamily.RESISTANCE)
            }
        val precision = mode == ScoreComputationMode.FIND_CLOSEST_BUILD_FROM_INPUT
        val rows = mutableListOf<TargetStat>()
        for (family in families) {
            family.elements.shuffled(random).take(2 + random.nextInt(3)).forEachIndexed { index, element ->
                // Precision: the occasional row of target 0, whose sign halves the whole objective — never the first (a mastery
                // row of target 0 keeps its element wanted: the family stays jointly read), nor the second of a resistance family
                // (a resistance row of target 0 is a floor that wants nothing — the GUI's default "air resistance 0" — though it
                // joins the family's fold). The other modes also get the CLI's 0-weight rows, which the hard leg still requires.
                val firstZeroIndex = if (family == ElementFamily.RESISTANCE) 2 else 1
                if (precision && index >= firstZeroIndex && random.nextInt(3) == 0) {
                    rows += TargetStat(element, 0, 1 + random.nextInt(5))
                } else {
                    val weight = if (!precision && random.nextInt(10) == 0) 0 else 1 + random.nextInt(5)
                    rows += TargetStat(element, 10 + random.nextInt(50), weight)
                }
            }
            if (random.nextInt(4) == 0) rows += TargetStat(family.aggregate, 10 + random.nextInt(40), 1 + random.nextInt(5))
        }
        if (mode == ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT) rows += TargetStat(Characteristic.MASTERY_DISTANCE, 1)
        // Precision: sometimes a non-elemental row of target 0 too (the GUI's default "dodge 0"), which can halve on its own.
        if (precision && random.nextInt(4) == 0) rows += TargetStat(Characteristic.DODGE, 0)
        val randomLines = families.flatMap { it.randomByCount }
        var id = 0
        val pool =
            listOf(ItemType.AMULET, ItemType.BOOTS, ItemType.CAPE).associateWith { type ->
                List(2) {
                    val stats = mutableMapOf<Characteristic, Int>()
                    for (family in families) {
                        for (element in family.elements) if (random.nextInt(3) == 0) stats[element] = random.nextInt(-5, 25)
                        // The "−X on all elements" line of some real items is what drives a row of target 0 below 0.
                        if (random.nextInt(4) == 0) stats[family.aggregate] = random.nextInt(-12, 12)
                    }
                    if (random.nextInt(4) == 0) stats[Characteristic.DODGE] = random.nextInt(-10, 10)
                    // One or two random-element lines, k = 1..3 (the occasional negative one).
                    repeat(1 + random.nextInt(2)) {
                        val (line, _) = randomLines[random.nextInt(randomLines.size)]
                        stats[line] = if (random.nextInt(12) == 0) -random.nextInt(1, 15) else random.nextInt(5, 40)
                    }
                    stats[Characteristic.MASTERY_DISTANCE] = random.nextInt(0, 30)
                    stats[Characteristic.MASTERY_ELEMENTARY_FIRE] = (stats[Characteristic.MASTERY_ELEMENTARY_FIRE] ?: 0) + random.nextInt(0, 30)
                    item(1000 + id++, type, stats)
                }
            }
        return FuzzCase(params(mode, rows), pool)
    }

    /** Every build of [pool]: one item or none per slot. */
    private fun allBuilds(pool: Map<ItemType, List<Equipment>>): List<List<Equipment>> =
        pool.values.fold(listOf(emptyList())) { acc, items -> acc.flatMap { partial -> listOf(partial) + items.map { partial + it } } }

    private fun values(
        objective: ElementRowObjective,
        stats: Map<Characteristic, Number>,
    ) = IntArray(objective.elements.size) { stats.getValue(objective.elements[it]).toInt() }

    /** Exhaustive best primary over every placement of [build]'s rolls of [objective]'s family, from the scorer's pre-roll values. */
    private fun exhaustiveBestPrimary(
        p: WakfuBestBuildParams,
        objective: ElementRowObjective,
        family: ElementFamily,
        build: BuildCombination,
    ): Long {
        val noRolls =
            build.copy(
                equipments =
                    build.equipments.map { e ->
                        e.copy(characteristics = e.characteristics.filterKeys { k -> family.randomByCount.none { it.first == k } })
                    }
            )
        val base = values(objective, scorerStats(p, noRolls))
        val rolls = build.equipments.flatMap { e -> family.randomByCount.mapNotNull { (line, k) -> e.characteristics[line]?.takeIf { it != 0 }?.let { it to k } } }
        val n = base.size
        var best = Long.MIN_VALUE

        fun subsets(
            k: Int,
            start: Int,
            acc: List<Int>,
            out: MutableList<List<Int>>,
        ) {
            if (acc.size == k) {
                out += acc
                return
            }
            for (i in start until n) subsets(k, i + 1, acc + i, out)
        }

        fun rec(
            i: Int,
            v: IntArray,
        ) {
            if (i == rolls.size) {
                best = maxOf(best, objective.primary(v))
                return
            }
            val (value, count) = rolls[i]
            // A resistance family places a roll as the game does: a negative one lands on the fold only when it must.
            val sinks = p.targetStats.freeSinks(family)
            val cover = if (sinks != null && value < 0) (count - sinks).coerceIn(0, n) else minOf(count, n)
            val combos = mutableListOf<List<Int>>().also { subsets(cover, 0, emptyList(), it) }
            for (combo in combos) {
                for (e in combo) v[e] += value
                rec(i + 1, v)
                for (e in combo) v[e] -= value
            }
        }
        rec(0, base)
        return best
    }

    @Test
    fun `seeded fuzz - the model's joint fold claims exactly what the scorer places, and the optimum is the scorer's`() {
        // The default (CI) slice: every precision shape is cheap, the hard-leg modes cost a CP-SAT solve per build — one case
        // each here, the full run is the slow-tagged test below.
        val checked =
            runFuzz(
                seed = 20261005,
                casesPerMode =
                    mapOf(
                        ScoreComputationMode.FIND_CLOSEST_BUILD_FROM_INPUT to 10,
                        ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT to 1,
                        ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE to 1
                    )
            )
        assertThat(checked).isGreaterThan(200)
    }

    @Test
    @Tag("slow")
    fun `seeded fuzz, full run - the model's joint fold claims exactly what the scorer places, and the optimum is the scorer's`() {
        val checked = runFuzz(seed = 20261006, casesPerMode = ScoreComputationMode.entries.associateWith { 12 })
        assertThat(checked).isGreaterThan(600)
    }

    /** [casesPerMode] seeded [fuzzCase]s per mode, every build of each pool checked; returns how many builds were. */
    private fun runFuzz(
        seed: Int,
        casesPerMode: Map<ScoreComputationMode, Int>,
    ): Int {
        val random = Random(seed)
        var checkedBuilds = 0
        for ((mode, cases) in casesPerMode) {
            repeat(cases) { caseIndex ->
                val case = fuzzCase(random, mode)
                val p = case.p
                val described = "mode=$mode case=$caseIndex rows=${p.targetStats.map { "${it.characteristic}:${it.target}x${it.userDefinedWeight}" }}"
                val objectives =
                    listOfNotNull(ElementRowObjective.of(p.targetStats, ElementFamily.MASTERY, mode), ElementRowObjective.of(p.targetStats, ElementFamily.RESISTANCE, mode))
                assertThat(objectives).describedAs(described).isNotEmpty()
                // Precision with a row of target 0 — a mastery one, or a resistance floor on the family's fold: its halving is
                // decided on the whole build (the per-family primary can then lose to keeping that row at 0 or more), so the
                // build-level objective is what is compared.
                val halvable =
                    mode == ScoreComputationMode.FIND_CLOSEST_BUILD_FROM_INPUT &&
                        (p.targetStats.zeroMasteries.isNotEmpty() || p.targetStats.resistanceFloorElements.isNotEmpty())
                val metBuilds = mutableListOf<Pair<List<Equipment>, BigDecimal>>()
                for (items in allBuilds(case.pool)) {
                    val ids = items.map { it.equipmentId }.toSet()
                    val build = BuildCombination(items, CharacterSkills(1))
                    val scorer = scorerStats(p, build)
                    val buildDescription = "$described build=$ids scorer=$scorer"
                    // The scorer's placement is the exhaustive optimum of the primary.
                    if (!halvable) {
                        for (objective in objectives) {
                            val family = if (objective.elements.first() in ElementFamily.MASTERY.elements) ElementFamily.MASTERY else ElementFamily.RESISTANCE
                            assertThat(
                                objective.primary(values(objective, scorer))
                            ).describedAs(buildDescription).isEqualTo(exhaustiveBestPrimary(p, objective, family, build))
                        }
                    }
                    if (mode != ScoreComputationMode.FIND_CLOSEST_BUILD_FROM_INPUT) {
                        // HARD leg, build pinned: feasible ⇔ the scorer meets every required row; feasible ⇒ same primary.
                        val hard = WakfuBuildSolver.elementRowSolveForTest(p, case.pool, tuning, hardConstraints = true, pinnedEquipmentIds = ids, pinSkillsToZero = true)
                        val met = scorerMeetsRequired(p, scorer)
                        assertThat(hard.hasSolution).describedAs("hard leg feasible ⇔ scorer met; $buildDescription model=${hard.modelElementValues}").isEqualTo(met)
                        if (met) metBuilds += items to score(p, items)
                        for (objective in objectives) {
                            if (hard.hasSolution) {
                                assertThat(
                                    objective.primary(values(objective, hard.modelElementValues))
                                ).describedAs(buildDescription).isEqualTo(objective.primary(values(objective, scorer)))
                            }
                        }
                    }
                    // SOFT leg / precision, build pinned: the model never claims more than the scorer places — and in
                    // precision, whose objective weighs the capped sum directly, exactly as much. Its overflow bonus (the
                    // secondary) only exists once EVERY row of the request is met — the only case the score reads it, and
                    // then the model's placement is the scorer's best too; below that, the model is indifferent to it.
                    val soft = WakfuBuildSolver.elementRowSolveForTest(p, case.pool, tuning, hardConstraints = false, pinnedEquipmentIds = ids, pinSkillsToZero = true)
                    assertThat(soft.isOptimal).describedAs(buildDescription).isTrue()
                    if (mode == ScoreComputationMode.FIND_CLOSEST_BUILD_FROM_INPUT) {
                        // The whole build: the scorer's placement reaches the solver's own optimum of its precision objective,
                        // halving included. (Every row of target 0 here is a jointly read family's or a non-elemental one.)
                        assertThat(precisionModelObjective(p.targetStats, scorer))
                            .describedAs("solver objective of the scorer's placement; $buildDescription model=${soft.modelElementValues}")
                            .isEqualTo(soft.objective)
                    }
                    if (!halvable) {
                        for (objective in objectives) {
                            val model = values(objective, soft.modelElementValues)
                            val placed = values(objective, scorer)
                            assertThat(
                                objective.primary(model)
                            ).describedAs("model ≤ scorer; $buildDescription model=${soft.modelElementValues}").isLessThanOrEqualTo(objective.primary(placed))
                            if (mode == ScoreComputationMode.FIND_CLOSEST_BUILD_FROM_INPUT) {
                                assertThat(objective.primary(model)).describedAs(buildDescription).isEqualTo(objective.primary(placed))
                                assertThat(objective.secondary(model)).describedAs(buildDescription).isLessThanOrEqualTo(objective.secondary(placed))
                                if (precisionMeetsEveryRow(p, scorer)) assertThat(objective.secondary(model)).describedAs(buildDescription).isEqualTo(objective.secondary(placed))
                            }
                        }
                    }
                    checkedBuilds++
                }
                // End to end, most-masteries hard leg: it returns a build iff one meets the rows, and then the best of those.
                if (mode == ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT) {
                    val hard = WakfuBuildSolver.elementRowSolveForTest(p, case.pool, tuning, hardConstraints = true, pinSkillsToZero = true)
                    assertThat(hard.hasSolution).describedAs(described).isEqualTo(metBuilds.isNotEmpty())
                    if (hard.hasSolution) {
                        assertThat(score(p, hard.build!!.equipments)).describedAs(described).isEqualByComparingTo(metBuilds.maxOf { it.second })
                    }
                }
            }
        }
        return checkedBuilds
    }

    // ---- Real data: the player's Xelor 200 request, as the GUI sends it ------------------------------------------------

    private val xelorGuiParams: WakfuBestBuildParams by lazy {
        val rows =
            listOf(
                TargetStat(Characteristic.ACTION_POINT, 13, 5),
                TargetStat(Characteristic.MOVEMENT_POINT, 4, 1),
                TargetStat(Characteristic.RANGE, 2, 3),
                TargetStat(Characteristic.CRITICAL_HIT, 100, 5),
                TargetStat(Characteristic.MASTERY_DISTANCE, 1, 1),
                TargetStat(Characteristic.MASTERY_CRITICAL, 1, 1),
                TargetStat(Characteristic.MASTERY_ELEMENTARY_FIRE, 1, 1),
                TargetStat(Characteristic.MASTERY_ELEMENTARY_WATER, 1, 1),
                TargetStat(Characteristic.DODGE, 1000, 2)
            ) + ElementFamily.RESISTANCE.elements.map { TargetStat(it, 640, 4) }
        WakfuBestBuildParams(
            character = Character(CharacterClass.XELOR, 200, 125, CharacterSkills(200)),
            targetStats = TargetStats(rows),
            searchDuration = 60.seconds,
            stopWhenBuildMatch = false,
            maxRarity = Rarity.EPIC,
            forcedItems = emptyList(),
            excludedItems = emptyList(),
            scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT,
            excludedSublimations = listOf("Vivacité II", "Visibilité II"),
            forcedPassives = listOf("Mémoire"),
            damageScenario = DamageScenario(element = SpellElement.FIRE, rangeBand = RangeBand.DISTANCE, orientation = Orientation.BACK)
        )
    }

    // The build the audit's hard leg returned for that request (WakfuData 1.93), every decision the model took: the old
    // per-row fold read 649–669 resistance on each element against 640; the scorer places its two random lines (83 and 111,
    // each on 2 random elements) for 538–586 — every row missed.
    private val auditBuild =
        mapOf(
            "equip_24027" to 1L,
            "equip_24723" to 1L,
            "equip_26924" to 1L,
            "equip_26897" to 1L,
            "equip_27416" to 1L,
            "equip_26996" to 1L,
            "equip_26497" to 1L,
            "equip_27298" to 1L,
            "equip_27419" to 1L,
            "equip_26887" to 1L,
            "equip_21855" to 1L,
            "equip_33418" to 1L,
            "equip_14422" to 1L,
            "equip_27304" to 1L,
            "skill_intel_resistance_elementary" to 10L,
            "skill_strength_mastery_elementary" to 50L,
            "skill_agility_dodge" to 50L,
            "skill_luck_%_critical_hit" to 20L,
            "skill_luck_mastery_berserk" to 13L,
            "skill_major_action_point" to 1L,
            "skill_major_movement_point_and_damage" to 1L,
            "skill_major_range_and_damage" to 1L,
            "skill_major_%_inflicted_damage" to 1L,
            "rune_24027_MASTERY_ELEMENTARY" to 4L,
            "rune_26924_RESISTANCE_ELEMENTARY_WATER" to 1L,
            "rune_26924_MASTERY_ELEMENTARY" to 3L,
            "rune_26897_DODGE" to 4L,
            "rune_27416_MASTERY_ELEMENTARY" to 4L,
            "rune_26996_RESISTANCE_ELEMENTARY_EARTH" to 1L,
            "rune_26996_MASTERY_ELEMENTARY" to 3L,
            "rune_26497_RESISTANCE_ELEMENTARY_WIND" to 2L,
            "rune_26497_MASTERY_ELEMENTARY" to 2L,
            "rune_27298_MASTERY_ELEMENTARY" to 4L,
            "rune_27419_RESISTANCE_ELEMENTARY_WIND" to 1L,
            "rune_27419_MASTERY_ELEMENTARY" to 3L,
            "rune_21855_MASTERY_ELEMENTARY" to 4L,
            "rune_27304_RESISTANCE_ELEMENTARY_FIRE" to 1L,
            "rune_27304_MASTERY_ELEMENTARY" to 3L,
            "sub_5983" to 1L,
            "sub_6931" to 1L,
            "sub_7077" to 1L,
            "sub_7088" to 1L,
            "sub_7115" to 1L,
            "sub_7256" to 1L,
            "sub_7862" to 1L,
            "sub_8518" to 1L,
            "sub_8519" to 1L,
            "subCopy_5983_1" to 1L,
            "subCopy_7862_1" to 1L
        )

    @Test
    fun `xelor 200 GUI request - the audit build no longer reads as meeting 640 resistance on every element`() {
        val p = xelorGuiParams
        val ids =
            auditBuild.keys
                .filter { it.startsWith("equip_") }
                .map { it.removePrefix("equip_").toInt() }
                .toSet()
        // The build's own items only: the decisions it pins are all the model needs. The build predates the item equip
        // conditions — it wears Épée de Brâkmar without the ring the game requires with it — so its items are read without
        // them (the pin would otherwise contradict `sword ≤ ring`): this test is about the resistance reading.
        val pool =
            WakfuBestBuildFinderAlgorithm
                .poolFor(p)
                .mapValues { (_, items) -> items.filter { it.equipmentId in ids }.map { it.copy(equipCriterion = null) } }
                .filterValues { it.isNotEmpty() }
        assertThat(pool.values.sumOf { it.size }).describedAs("the audit build's items are in the 1.93 catalog").isEqualTo(ids.size)
        val subs = WakfuBestBuildFinderAlgorithm.activeSublimations(p)
        val runes = WakfuBestBuildFinderAlgorithm.runes

        val hard = WakfuBuildSolver.elementRowSolveForTest(p, pool, tuning, hardConstraints = true, runes = runes, sublimations = subs, pinnedDecisions = auditBuild)
        assertThat(hard.status).describedAs("no placement of the two random lines meets 640 on all four elements").isEqualTo(CpSolverStatus.INFEASIBLE)

        val soft = WakfuBuildSolver.elementRowSolveForTest(p, pool, tuning, hardConstraints = false, runes = runes, sublimations = subs, pinnedDecisions = auditBuild)
        assertThat(soft.hasSolution).isTrue()
        val objective = checkNotNull(ElementRowObjective.of(p.targetStats, ElementFamily.RESISTANCE, p.scoreComputationMode))
        val scorer = scorerStatsReal(p, soft.build!!)
        val model = values(objective, soft.modelElementValues)
        val placed = values(objective, scorer)
        // The scorer's reading of the audit (538–586: all four missed), and the model now claims no more than that.
        assertThat(placed.toList()).allSatisfy { assertThat(it).isLessThan(640) }
        assertThat(model.toList()).allSatisfy { assertThat(it).isLessThan(640) }
        assertThat(model.sum()).describedAs("the same two lines placed: equal totals").isEqualTo(placed.sum())
        assertThat(objective.primary(model)).isLessThanOrEqualTo(objective.primary(placed))
    }

    private fun scorerStatsReal(
        p: WakfuBestBuildParams,
        build: BuildCombination,
    ): Map<Characteristic, Int> =
        computeCharacteristicsValues(
            build,
            p.character.baseCharacteristicValues,
            p.targetStats.masteryElementsWanted,
            p.targetStats.resistanceElementsWanted,
            scoreComputationMode = p.scoreComputationMode,
            masteryElementsToMinimize = p.targetStats.masteryElementsToMinimize,
            elementRows = p.targetStats.elementRowObjectives(p.scoreComputationMode)
        )
}
