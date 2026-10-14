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
 * The search model's rune PICK CLASSES ([RuneCountClass] with `unit = slots`, docs/MIXED_RUNE_SECONDARY_BUDGET.md "Pick classes"):
 * the fold's single-type pick carriers that share an item type, a socket count and their rune choices hold one var per type
 * for the whole class — the number of its worn carriers filled with that type. The aggregation must be EXACT for the model it
 * aggregates: on pools of interchangeable carriers (two helmets, three rings — two worn — of one level and socket count), under
 * the shapes that make multi-choice pick carriers (the general fold of a row on a non-damage rune type, the collapse's explicit
 * picks under a choosable Neutralité cap), the fold with pick classes proves the per-carrier fold's optimum; the mixed-rune
 * hybrid, its fold stage on pick classes, still ends on the count model's optimum, proven; a build from the class model fills
 * each worn carrier with ONE type; and the declared reachable domains are the per-carrier fold's.
 */
class RunePickClassTest {
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
        name = I18nText("pc$id", "pc$id", "", ""),
        rarity = Rarity.LEGENDARY,
        itemType = type,
        characteristics = stats,
        maxShardSlots = sockets
    )

    /**
     * Seeded pools of interchangeable carriers (per item type 1–3 items of ONE level and socket count, different stats), in
     * one of three shapes: 0. an HP row with negative HP lines (general fold: every carrier offers every type); 1. a dodge
     * 0-row and a lock row (two non-damage types); 2. Neutralité III choosable, melee / rear scenario, negative melee / rear
     * lines (the collapse's explicit picks and their choice gates).
     */
    private fun case(seed: Long): Case {
        val rng = java.util.Random(seed * 7_919L + 3L)
        val shape = (seed % 3).toInt()
        val level = if (shape == 2) listOf(200, 230, 245)[rng.nextInt(3)] else listOf(35, 50, 80, 110)[rng.nextInt(4)]
        val fire = Characteristic.MASTERY_ELEMENTARY_FIRE
        var id = 880_000 + (seed % 1_000).toInt() * 20
        val items = mutableListOf<Equipment>()
        for ((type, count) in listOf(ItemType.HELMET to 1 + rng.nextInt(2), ItemType.RING to 2 + rng.nextInt(2), ItemType.BOOTS to 1 + rng.nextInt(2))) {
            val sockets = listOf(2, 3, 4)[rng.nextInt(3)]
            repeat(count) {
                val stats = mutableMapOf(fire to 30 + rng.nextInt(level * 3))
                if (rng.nextInt(2) == 0) {
                    when (shape) {
                        0 -> stats[Characteristic.HP] = -(10 + rng.nextInt(level * 3))
                        1 -> stats[if (rng.nextBoolean()) Characteristic.DODGE else Characteristic.LOCK] = -(10 + rng.nextInt(level * 2))
                        else -> stats[if (rng.nextBoolean()) Characteristic.MASTERY_MELEE else Characteristic.MASTERY_BACK] = -(20 + rng.nextInt(200))
                    }
                }
                items += item(++id, type, level, stats.filterValues { it != 0 }, sockets)
            }
        }
        val baseHp = 50 + level * 10
        val (targets, scenario) =
            when (shape) {
                0 ->
                    listOf(TargetStat(Characteristic.MASTERY_DISTANCE, 1), TargetStat(Characteristic.HP, baseHp + rng.nextInt(level * 6))) to
                        DamageScenario(element = SpellElement.FIRE, rangeBand = RangeBand.DISTANCE, orientation = Orientation.FACE)
                1 ->
                    listOf(TargetStat(Characteristic.MASTERY_DISTANCE, 1), TargetStat(Characteristic.DODGE, 0), TargetStat(Characteristic.LOCK, 1 + rng.nextInt(level))) to
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

    @Test
    fun `pick classes prove the per-carrier fold's optimum on pools of interchangeable carriers`() {
        val sharedClassShapes = HashSet<Int>()
        for (seed in 0L until 12L) {
            val c = case(seed)
            val sizes = WakfuBuildSolver.runePickClassSizesForTest(c.params, c.pool, runes, c.subs, hardConstraints = false)
            if (sizes.any { it >= 2 }) sharedClassShapes += (seed % 3).toInt()
            for (hard in listOf(false, true)) {
                if (hard && seed % 3 == 2L) continue // no required row: the hard leg is the free solve

                fun fold(
                    pickClasses: Boolean,
                    linear: Boolean = false,
                ) = WakfuBuildSolver.maxDamageSolveForTest(
                    c.params,
                    c.pool,
                    tuning,
                    tightDomains = true,
                    runes = runes,
                    sublimations = c.subs,
                    hardConstraints = hard,
                    runeMixedCarriers = false,
                    runeCountClasses = false,
                    runePickClasses = pickClasses,
                    runePickClassLinear = linear
                )
                val perCarrier = fold(false)
                // Both encodings of the classes: the literal one (production: exactly-ones, two ring-slot layers) and the linear one.
                for ((encoding, classes) in listOf("literal" to fold(true), "linear" to fold(true, linear = true))) {
                    println(
                        "RUNE_PICK_CLASS ${c.label} hard=$hard $encoding classSizes=$sizes perCarrier=${perCarrier.objective}/${perCarrier.isOptimal} " +
                            "classes=${classes.objective}/${classes.isOptimal}"
                    )
                    assertThat(classes.hasSolution).describedAs("%s hard=%s %s", c.label, hard, encoding).isEqualTo(perCarrier.hasSolution)
                    if (!perCarrier.hasSolution) continue
                    assertThat(perCarrier.isOptimal).describedAs("%s hard=%s", c.label, hard).isTrue()
                    assertThat(classes.isOptimal).describedAs("%s hard=%s %s", c.label, hard, encoding).isTrue()
                    assertThat(classes.objective)
                        .describedAs("%s hard=%s %s: pick classes = the per-carrier fold", c.label, hard, encoding)
                        .isEqualTo(perCarrier.objective)
                }
            }
        }
        assertThat(sharedClassShapes).describedAs("shapes whose fold holds a pick class of several carriers (the lock's reach)").containsExactlyInAnyOrder(0, 1, 2)
    }

    /**
     * The production leg: the mixed-rune hybrid with its fold stage (and every count stage) on pick classes ends on the
     * per-stat count model's optimum, proven by its verify stage, and its build fills each worn carrier with one or more
     * types (a polished count fill) — every socket filled.
     */
    @Test
    fun `the hybrid on pick classes ends on the count model's optimum`(): Unit =
        runBlocking {
            for (seed in 0L until 9L) {
                val c = case(seed)
                for (hard in listOf(false, true)) {
                    if (hard && seed % 3 == 2L) continue
                    val reference =
                        WakfuBuildSolver.maxDamageSolveForTest(
                            c.params,
                            c.pool,
                            tuning,
                            tightDomains = true,
                            runes = runes,
                            sublimations = c.subs,
                            forceRuneCountModel = true,
                            hardConstraints = hard
                        )
                    if (!reference.hasSolution) continue
                    assertThat(reference.isOptimal).isTrue()
                    var outcome: WakfuBuildSolver.SolveOutcome? = null
                    WakfuBuildSolver
                        .optimize(
                            c.params,
                            c.pool,
                            runes,
                            c.subs,
                            // The hard leg searches LNS first ([WakfuBuildSolver.lnsFirstSearch]) — a search-only switch.
                            tuning.copy(mixedRuneHybrid = true, runePickClasses = true, lnsFirst = hard),
                            hardConstraints = hard,
                            onTermination = { outcome = it }
                        ).toList()
                    val o = outcome!!
                    println("RUNE_PICK_HYBRID ${c.label} hard=$hard count=${reference.objective} hybrid=${o.objectiveValue}/${o.status}")
                    assertThat(o.status).describedAs("%s hard=%s", c.label, hard).isEqualTo(com.google.ortools.sat.CpSolverStatus.OPTIMAL)
                    assertThat(o.objectiveValue).describedAs("%s hard=%s", c.label, hard).isEqualTo(reference.objective)
                    val build = o.finalBuild!!
                    for (equip in build.equipments.filter { it.maxShardSlots > 0 }) {
                        assertThat(build.runes[equip].orEmpty()).describedAs("%s: %s's sockets", c.label, equip.equipmentId).hasSize(equip.maxShardSlots)
                    }
                }
            }
        }

    /** A build exported from the fold on pick classes fills each worn carrier with exactly one type, every socket, rings included. */
    @Test
    fun `a build from the pick-class fold fills each worn carrier with one type`(): Unit =
        runBlocking {
            var ringPairs = 0
            for (seed in 0L until 9L) {
                val c = case(seed)
                var outcome: WakfuBuildSolver.SolveOutcome? = null
                WakfuBuildSolver
                    .optimize(
                        c.params,
                        c.pool,
                        runes,
                        c.subs,
                        tuning.copy(runeMixedCarriers = false, runeCountClasses = false, runePickClasses = true),
                        onTermination = { outcome = it }
                    ).toList()
                val build = outcome!!.finalBuild ?: continue
                for (equip in build.equipments.filter { it.maxShardSlots > 0 }) {
                    val socketed = build.runes[equip].orEmpty()
                    assertThat(socketed).describedAs("%s: %s's sockets", c.label, equip.equipmentId).hasSize(equip.maxShardSlots)
                    assertThat(socketed.map { it.characteristic }.distinct()).describedAs("%s: %s holds one type", c.label, equip.equipmentId).hasSize(1)
                }
                if (build.equipments.count { it.itemType == ItemType.RING } == 2) ringPairs++
            }
            assertThat(ringPairs).describedAs("builds wearing two rings of one class").isGreaterThan(0)
        }

    /**
     * Production's rule: the GUI-default hard-leg fold stage aggregates its pick carriers at every level — level 110 (14,028
     * per-carrier pick vars) included, which 1.18.0's size rule ([WakfuBuildSolver.PICK_CLASS_MIN_PICK_VARS_1_18]) kept per
     * carrier — now that the literal encoding keeps CP-SAT's feasibility pump working there.
     */
    @Test
    fun `production aggregates the fold's pick carriers at every level`() {
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
        val aggregated =
            listOf(110, 200).associateWith { level ->
                val p =
                    WakfuBestBuildParams(
                        character = Character(CharacterClass.CRA, level, 0, CharacterSkills(level)),
                        targetStats = TargetStats(gui),
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
                val always = WakfuBuildSolver.foldPickClassShapeForTest(p, pool, runes, subs, hardConstraints = true)
                val rule118 =
                    WakfuBuildSolver.foldPickClassShapeForTest(p, pool, runes, subs, hardConstraints = true, minPickVars = WakfuBuildSolver.PICK_CLASS_MIN_PICK_VARS_1_18)
                println("RUNE_PICK_RULE level=$level production=$always rule118=$rule118")
                assertThat(always[0]).describedAs("level %s: the fold has multi-choice pick carriers", level).isGreaterThan(0)
                assertThat(always[2]).describedAs("level %s: they share classes", level).isLessThan(always[0])
                rule118[0]
            }
        assertThat(aggregated.getValue(110)).describedAs("level 110: per-carrier picks under 1.18.0's rule").isZero()
        assertThat(aggregated.getValue(200)).describedAs("level 200: pick classes under 1.18.0's rule").isGreaterThan(0)
    }

    /**
     * Production's "LNS first" rule ([WakfuBuildSolver.LNS_FIRST_MAX_PICK_VARS]): the HARD leg of a small fold — the GUI default at
     * level 110 (14,028 pick-class vars), with Neutralité III forced (15,484) — searches without the greedy hint and with one full
     * CP-SAT subsolver; level 140 (21,007) and the soft leg keep the hint and the default portfolio. A search-only switch: the
     * model, and so every optimum and proof, is the same.
     */
    @Test
    fun `small hard-leg folds search LNS first`() {
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

        fun params(
            level: Int,
            forcedSubs: List<String> = emptyList(),
        ) = WakfuBestBuildParams(
            character = Character(CharacterClass.CRA, level, 0, CharacterSkills(level)),
            targetStats = TargetStats(gui),
            searchDuration = 60.seconds,
            stopWhenBuildMatch = false,
            maxRarity = Rarity.EPIC,
            forcedItems = emptyList(),
            excludedItems = emptyList(),
            scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE,
            useRunes = true,
            useSublimations = true,
            forcedSublimations = forcedSubs
        )
        val subs = WakfuBestBuildFinderAlgorithm.sublimations

        fun rule(
            p: WakfuBestBuildParams,
            hard: Boolean,
        ) = WakfuBuildSolver.lnsFirstForTest(p, WakfuBestBuildFinderAlgorithm.poolFor(p), runes, subs, hard)
        val md110 = rule(params(110), hard = true)
        val mdn110 = rule(params(110, listOf("Neutralité III")), hard = true)
        val md140 = rule(params(140), hard = true)
        val md110Soft = rule(params(110), hard = false)
        println("LNS_FIRST md110=$md110 mdn110=$mdn110 md140=$md140 md110Soft=$md110Soft")
        assertThat(md110.first).describedAs("level 110, hard leg (%s pick-class vars)", md110.second).isTrue()
        assertThat(mdn110.first).describedAs("level 110 + Neutralité III, hard leg (%s)", mdn110.second).isTrue()
        assertThat(md140.first).describedAs("level 140, hard leg (%s)", md140.second).isFalse()
        assertThat(md140.second).isGreaterThan(WakfuBuildSolver.LNS_FIRST_MAX_PICK_VARS)
        assertThat(md110Soft.first).describedAs("level 110, soft leg").isFalse()
    }

    /**
     * The fold stage's declared reachable domains ([StatBuilder.reachableSumDomain]) are the per-carrier fold's: a pick class's
     * share rides each of its carriers at one whole fill, exactly like that carrier's own pick. Checked on the GUI-default and
     * free requests at levels 110 and 245 (production pool, domination on), both legs, on the hybrid's fold-stage model.
     */
    @Test
    fun `the pick-class fold declares the per-carrier fold's reachable domains on the GUI default requests`() {
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
                    fun ranges(pickClasses: Boolean) =
                        WakfuBuildSolver
                            .maxDamageTrackedRangesForTest(p, pool, runes, subs, hard, runeCountClasses = false, runePickClasses = pickClasses, runeMixedHybridFold = true)
                            .filterNot { (name, _) -> name.startsWith("rune") }
                    val perCarrier = ranges(false)
                    val classes = ranges(true)
                    assertThat(classes).describedAs("%s %s hard=%s: tracked domains", label, level, hard).hasSameSizeAs(perCarrier)
                    assertThat(classes).describedAs("%s %s hard=%s: tracked domains", label, level, hard).isEqualTo(perCarrier)
                }
            }
        }
    }
}
