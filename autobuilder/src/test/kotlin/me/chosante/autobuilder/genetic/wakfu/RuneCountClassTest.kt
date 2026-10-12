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
import me.chosante.common.Sublimation
import me.chosante.common.skills.CharacterSkills
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/**
 * The search model's rune COUNT CLASSES ([RuneCountClass], docs/MIXED_RUNE_SECONDARY_BUDGET.md "Count classes"): count
 * carriers sharing an item type, a socket count and their rune choices hold one count per type for the whole class. The
 * reference is the per-stat COUNT model (`forceRuneCountModel`, no fold, no class) and the per-carrier counts the classes
 * replace (`runeCountClasses = false`): on pools whose classes hold several carriers — two helmets, three rings (two worn)
 * of one level and socket count — under every threshold shape that makes count carriers (an HP row with a dodge floor, an
 * "all resistances" floor with negative lines, the Neutralité budget with its choice gates), the three must prove the same
 * optimum, and an exported build must fill every socket of every worn carrier.
 */
class RuneCountClassTest {
    private val runes = WakfuBestBuildFinderAlgorithm.runes
    private val neutralite = WakfuBestBuildFinderAlgorithm.sublimations.single { it.name.fr == "Neutralité III" }
    private val tuning = WakfuBuildSolver.SolverTuning(numSearchWorkers = 1, randomSeed = 1, maxDeterministicTime = 60.0, interleaveSearch = true)

    private class Case(
        val label: String,
        val params: WakfuBestBuildParams,
        val pool: Map<ItemType, List<Equipment>>,
        val subs: List<Sublimation>,
    )

    private fun item(
        id: Int,
        type: ItemType,
        level: Int,
        stats: Map<Characteristic, Int>,
        sockets: Int,
    ) = Equipment(
        equipmentId = id,
        guiId = id,
        level = level,
        name = I18nText("rc$id", "rc$id", "", ""),
        rarity = Rarity.LEGENDARY,
        itemType = type,
        characteristics = stats,
        maxShardSlots = sockets
    )

    /**
     * Seeded pools of interchangeable carriers: per item type 1–3 items of ONE level and socket count (so they share a
     * class) with different stats (so none dominates another), in one of three shapes:
     *  0. HP row and dodge 0-row, negative HP / dodge lines (the GUI default's shape);
     *  1. an "all resistances" 0-row with negative resistance lines;
     *  2. Neutralité III choosable, melee / rear scenario, negative melee / rear lines (the collapse's count carriers and
     *     their choice gates).
     */
    private fun case(seed: Long): Case {
        val rng = java.util.Random(seed * 104_729L + 11L)
        val shape = (seed % 3).toInt()
        val level = if (shape == 2) listOf(200, 230, 245)[rng.nextInt(3)] else listOf(35, 50, 80, 110)[rng.nextInt(4)]
        val fire = Characteristic.MASTERY_ELEMENTARY_FIRE
        var id = 870_000 + (seed % 1_000).toInt() * 20
        val items = mutableListOf<Equipment>()
        for ((type, count) in listOf(ItemType.HELMET to 1 + rng.nextInt(2), ItemType.RING to 2 + rng.nextInt(2), ItemType.BOOTS to 1 + rng.nextInt(2))) {
            val sockets = listOf(2, 3, 4)[rng.nextInt(3)]
            repeat(count) {
                val stats = mutableMapOf(fire to 30 + rng.nextInt(level * 3))
                if (rng.nextInt(2) == 0) {
                    when (shape) {
                        0 -> stats[if (rng.nextBoolean()) Characteristic.HP else Characteristic.DODGE] = -(10 + rng.nextInt(level * 3))
                        1 -> stats[if (rng.nextBoolean()) Characteristic.RESISTANCE_ELEMENTARY else Characteristic.RESISTANCE_ELEMENTARY_FIRE] = -(10 + rng.nextInt(100))
                        else -> stats[if (rng.nextBoolean()) Characteristic.MASTERY_MELEE else Characteristic.MASTERY_BACK] = -(20 + rng.nextInt(200))
                    }
                }
                if (shape == 0 && rng.nextInt(3) == 0) stats[Characteristic.HP] = (stats[Characteristic.HP] ?: 0) + rng.nextInt(level * 4)
                items += item(++id, type, level, stats.filterValues { it != 0 }, sockets)
            }
        }
        val baseHp = 50 + level * 10
        val (targets, scenario) =
            when (shape) {
                0 ->
                    listOf(TargetStat(Characteristic.MASTERY_DISTANCE, 1), TargetStat(Characteristic.HP, baseHp + rng.nextInt(level * 6)), TargetStat(Characteristic.DODGE, 0)) to
                        DamageScenario(element = SpellElement.FIRE, rangeBand = RangeBand.DISTANCE, orientation = Orientation.FACE)
                1 ->
                    listOf(TargetStat(Characteristic.MASTERY_DISTANCE, 1), TargetStat(Characteristic.RESISTANCE_ELEMENTARY, 0)) to
                        DamageScenario(element = SpellElement.FIRE, rangeBand = RangeBand.DISTANCE, orientation = Orientation.FACE)
                else ->
                    listOf(TargetStat(Characteristic.MASTERY_MELEE, 1)) to
                        DamageScenario(element = SpellElement.FIRE, rangeBand = RangeBand.MELEE, orientation = Orientation.BACK)
            }
        val params =
            WakfuBestBuildParams(
                character = Character(CharacterClass.CRA, level, 0, CharacterSkills(level)),
                targetStats = TargetStats(targets),
                searchDuration = 60.seconds,
                stopWhenBuildMatch = false,
                maxRarity = Rarity.EPIC,
                forcedItems = emptyList(),
                excludedItems = emptyList(),
                scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE,
                damageScenario = scenario,
                useRunes = true,
                useSublimations = shape == 2
            )
        return Case(
            "seed$seed shape$shape lvl$level items=${items.map { "${it.itemType}/${it.maxShardSlots}:${it.characteristics}" }} " +
                "targets=${targets.map { it.characteristic to it.target }}",
            params,
            items.groupBy { it.itemType },
            if (shape == 2) listOf(neutralite) else emptyList()
        )
    }

    private fun solve(
        c: Case,
        hard: Boolean,
        count: Boolean = false,
        classes: Boolean = true,
    ) = WakfuBuildSolver.maxDamageSolveForTest(
        c.params,
        c.pool,
        tuning,
        tightDomains = true,
        runes = runes,
        sublimations = c.subs,
        forceRuneCountModel = count,
        hardConstraints = hard,
        runeCountClasses = classes
    )

    @Test
    fun `count classes prove the count-model optimum on pools of interchangeable carriers`() {
        val sharedClassShapes = HashSet<Int>()
        for (seed in 0L until 12L) {
            val c = case(seed)
            val sizes = WakfuBuildSolver.runeCountClassSizesForTest(c.params, c.pool, runes, c.subs, hardConstraints = false)
            if (sizes.any { it >= 2 }) sharedClassShapes += (seed % 3).toInt()
            for (hard in listOf(false, true)) {
                if (hard && seed % 3 == 2L) continue // no required row: the hard leg is the free solve
                val reference = solve(c, hard, count = true)
                if (!reference.hasSolution) continue
                val perCarrier = solve(c, hard, classes = false)
                val classes = solve(c, hard)
                println(
                    "RUNE_CLASS ${c.label} hard=$hard classSizes=$sizes count=${reference.objective}/${reference.isOptimal} " +
                        "perCarrier=${perCarrier.objective}/${perCarrier.isOptimal} classes=${classes.objective}/${classes.isOptimal}"
                )
                for (outcome in listOf(reference, perCarrier, classes)) assertThat(outcome.isOptimal).describedAs("%s hard=%s", c.label, hard).isTrue()
                assertThat(perCarrier.objective).describedAs("%s hard=%s: per-carrier counts = the count model", c.label, hard).isEqualTo(reference.objective)
                assertThat(classes.objective).describedAs("%s hard=%s: count classes = the count model", c.label, hard).isEqualTo(reference.objective)
            }
        }
        assertThat(sharedClassShapes).describedAs("shapes whose model holds a class of several carriers (the lock's reach)").containsExactlyInAnyOrder(0, 1, 2)
    }

    /**
     * The model's reachable domains ([StatBuilder.reachableSumDomain] bounds every objective-chain var per item slot) must not
     * shrink with the classes: a class var's share rides each of its carriers at `0..slots` exactly like that carrier's own
     * count, so the class model declares the per-carrier model's domain for every tracked var (the rune leaves aside). An
     * under-estimate would cut the optimum. Checked on the GUI-default request (every carrier a count carrier) and on the
     * free one (the collapse's count carriers), levels 110 and 245, production pool (domination on), both legs.
     */
    @Test
    fun `the class model declares the per-carrier model's reachable domains on the GUI default requests`() {
        val gui =
            listOf(
                TargetStat(Characteristic.ACTION_POINT, 11),
                TargetStat(Characteristic.MOVEMENT_POINT, 4),
                TargetStat(Characteristic.RANGE, 4),
                TargetStat(Characteristic.CRITICAL_HIT, 25),
                TargetStat(Characteristic.MASTERY_DISTANCE, 1),
                TargetStat(Characteristic.HP, 2000),
                TargetStat(Characteristic.RESISTANCE_ELEMENTARY_WIND, 0),
                TargetStat(Characteristic.DODGE, 0)
            )
        for (level in listOf(110, 245)) {
            for ((label, targets) in listOf("gui" to gui, "free" to listOf(TargetStat(Characteristic.MASTERY_DISTANCE, 1)))) {
                val p =
                    WakfuBestBuildParams(
                        character = Character(CharacterClass.CRA, level, 0, CharacterSkills(level)),
                        targetStats = TargetStats(targets),
                        searchDuration = 60.seconds,
                        stopWhenBuildMatch = false,
                        maxRarity = Rarity.EPIC,
                        forcedItems = emptyList(),
                        excludedItems = emptyList(),
                        scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE,
                        useRunes = true,
                        useSublimations = true
                    )
                val pool = WakfuBestBuildFinderAlgorithm.poolFor(p)
                val subs = WakfuBestBuildFinderAlgorithm.sublimations
                for (hard in listOf(false, true)) {
                    fun ranges(classes: Boolean) =
                        WakfuBuildSolver
                            .maxDamageTrackedRangesForTest(p, pool, runes, subs, hard, runeCountClasses = classes)
                            .filterNot { (name, _) -> name.startsWith("rune") }
                    val perCarrier = ranges(false)
                    val classes = ranges(true)
                    assertThat(classes).describedAs("%s %s hard=%s: tracked domains", label, level, hard).hasSameSizeAs(perCarrier)
                    assertThat(classes).describedAs("%s %s hard=%s: tracked domains", label, level, hard).isEqualTo(perCarrier)
                }
            }
        }
    }

    /**
     * The production solve path: a build exported from the class model fills every socket of every worn carrier (the class
     * counts are split over its worn carriers), wears both rings of one class when they are worth it, and the class model
     * reaches the per-carrier model's optimum.
     */
    @Test
    fun `a build from the class model fills every socket of every worn carrier`(): Unit =
        runBlocking {
            var bothRingsOfOneClass = 0
            for (seed in listOf(0L, 6L, 9L, 4L)) {
                val c = case(seed)
                val outcomes =
                    listOf(true, false).map { classes ->
                        var outcome: WakfuBuildSolver.SolveOutcome? = null
                        WakfuBuildSolver
                            .optimize(c.params, c.pool, runes, c.subs, tuning.copy(runeCountClasses = classes), onTermination = { outcome = it })
                            .toList()
                        outcome!!
                    }
                val (classOutcome, perCarrierOutcome) = outcomes
                assertThat(classOutcome.status).isEqualTo(com.google.ortools.sat.CpSolverStatus.OPTIMAL)
                assertThat(perCarrierOutcome.status).isEqualTo(com.google.ortools.sat.CpSolverStatus.OPTIMAL)
                assertThat(classOutcome.objectiveValue).describedAs(c.label).isEqualTo(perCarrierOutcome.objectiveValue)
                val build = classOutcome.finalBuild!!
                for (equip in build.equipments.filter { it.maxShardSlots > 0 }) {
                    assertThat(build.runes[equip].orEmpty()).describedAs("%s: %s's sockets", c.label, equip.equipmentId).hasSize(equip.maxShardSlots)
                }
                val rings = build.equipments.filter { it.itemType == ItemType.RING }
                if (rings.size == 2 && rings[0].maxShardSlots == rings[1].maxShardSlots) bothRingsOfOneClass++
            }
            assertThat(bothRingsOfOneClass).describedAs("builds wearing two rings of one class (the class top is two carriers)").isGreaterThan(0)
        }
}
