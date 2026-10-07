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
import me.chosante.common.RuneType
import me.chosante.common.Sublimation
import me.chosante.common.SublimationCondition
import me.chosante.common.SublimationConditionType
import me.chosante.common.SublimationEffect
import me.chosante.common.SublimationKind
import me.chosante.common.SublimationRarity
import me.chosante.common.skills.CharacterSkills
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/**
 * CERTIFIER_VERSION 52 — the TARGET-AWARE AP-cell certificate (`docs/CERTIFICATE_PROD_PLAN.md` §P5.6). A HARD-LEG result's
 * ledger enforces the request's required AP / MP / CC / RANGE rows in every pass, so it bounds the TARGETS-MET builds only.
 * The oracle of every lock here is therefore the pinned HARD-LEG CP-SAT optimum of each AP cell (`actual ≥ target` enforced,
 * plain damage objective — 1 worker, fixed seed, interleaved search: deterministic): no target-aware pass may fall below
 * it, the tiers stay ordered `fast ≥ tier-1.5 ≥ exact`, and the target-aware value never exceeds the target-blind one.
 */
class MaxDamageTargetAwareCertificateTest {
    private val catalog = WakfuBestBuildFinderAlgorithm.sublimations

    private fun sub(nameFr: String): Sublimation = catalog.single { it.name.fr == nameFr }

    private fun item(
        id: Int,
        type: ItemType,
        stats: Map<Characteristic, Int>,
        rarity: Rarity = Rarity.LEGENDARY,
        sockets: Int = 3,
        name: String = "ta$id",
    ) = Equipment(
        equipmentId = id,
        guiId = id,
        level = 200,
        name = I18nText(name, name, "", ""),
        rarity = rarity,
        itemType = type,
        characteristics = stats,
        maxShardSlots = sockets
    )

    private fun params(
        rows: List<TargetStat>,
        level: Int = 200,
        useRunes: Boolean = false,
        scenario: DamageScenario = DamageScenario(element = SpellElement.FIRE, rangeBand = RangeBand.DISTANCE, orientation = Orientation.FACE),
    ) = WakfuBestBuildParams(
        character = Character(CharacterClass.CRA, level, 0, CharacterSkills(level)),
        targetStats = TargetStats(rows + TargetStat(Characteristic.MASTERY_DISTANCE, 1)),
        searchDuration = 60.seconds,
        stopWhenBuildMatch = false,
        maxRarity = Rarity.EPIC,
        forcedItems = emptyList(),
        excludedItems = emptyList(),
        scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE,
        useRunes = useRunes,
        useSublimations = true,
        damageScenario = scenario
    )

    /** The pinned HARD-LEG optimum of AP cell [ap] (null = the cell holds no targets-met build). */
    private fun hardLeg(
        p: WakfuBestBuildParams,
        pool: Map<ItemType, List<Equipment>>,
        runes: List<RuneType>,
        subs: List<Sublimation>,
        ap: Int,
    ): WakfuBuildSolver.MaxDamageTimedProfile? =
        WakfuBuildSolver
            .timedMaxDamageProfileForTest(
                p.copy(maxDamageApTarget = ap),
                pool,
                runes,
                subs,
                workers = 1,
                seconds = 120.0,
                applyDomination = false,
                randomSeed = 1,
                interleave = true,
                deterministicLimit = 20.0,
                hardConstraints = true
            ).takeIf { it.hasSolution }

    private class Outcome(
        val compared: Int,
        val tightened: Int,
        val tight: Int,
        val bindingCarried: Int,
        val notOptimal: Int,
        val failures: List<String>,
    )

    /**
     * Every per-cell soundness invariant of the target-aware passes on one pool: exact / tier-1.5 / fast ≥ the pinned hard-leg
     * optimum, `fast ≥ tier-1.5 ≥ exact`, target-aware ≤ target-blind, and the production ledger (elimination at the true
     * hard-leg optimum, then the refinement) never below it. [binding] tells whether a pinned optimum carries the fixture's
     * binding source.
     */
    private fun check(
        label: String,
        p: WakfuBestBuildParams,
        pool: Map<ItemType, List<Equipment>>,
        subs: List<Sublimation>,
        runes: List<RuneType> = emptyList(),
        binding: (WakfuBuildSolver.MaxDamageTimedProfile) -> Boolean = { false },
    ): Outcome {
        val (onExact, onFast, onT15) = WakfuBuildSolver.certifierExactFastTier15CellObjectivesForTest(p, pool, runes, subs, targetAware = true)
        val (offExact, offFast, offT15) = WakfuBuildSolver.certifierExactFastTier15CellObjectivesForTest(p, pool, runes, subs, targetAware = false)
        val failures = mutableListOf<String>()
        var compared = 0
        var tightened = 0
        var tight = 0
        var bindingCarried = 0
        var notOptimal = 0
        val truthByAp = LinkedHashMap<Int, Long>()
        for (ap in onExact.keys.sorted()) {
            val on = listOf("exact" to (onExact[ap] ?: -1L), "tier-1.5" to (onT15[ap] ?: -1L), "fast" to (onFast[ap] ?: -1L))
            val off = listOf(offExact[ap] ?: -1L, offT15[ap] ?: -1L, offFast[ap] ?: -1L)
            for (i in on.indices) {
                val (tier, v) = on[i]
                if (v >= 0 && off[i] >= 0 && v > off[i]) failures += "$label AP=$ap: target-aware $tier $v > target-blind ${off[i]}"
            }
            val (e, t, f) = Triple(on[0].second, on[1].second, on[2].second)
            if (e >= 0 && t >= 0 && t < e) failures += "$label AP=$ap: tier-1.5 $t < exact $e"
            if (t >= 0 && f >= 0 && f < t) failures += "$label AP=$ap: fast $f < tier-1.5 $t"
            val hard = hardLeg(p, pool, runes, subs, ap) ?: continue
            if (hard.status != "OPTIMAL") {
                notOptimal++
                continue
            }
            truthByAp[ap] = hard.rawObjective
            if (binding(hard)) bindingCarried++
            for ((tier, v) in on) {
                if (v < 0) continue
                compared++
                if (v < hard.rawObjective) failures += "$label AP=$ap: target-aware $tier $v < hard-leg optimum ${hard.rawObjective} (UNDER-COUNT)"
            }
            if (e >= 0 && off[0] >= 0 && e < off[0]) tightened++
            if (e == hard.rawObjective) tight++
        }
        val trueOptimum = truthByAp.values.maxOrNull()
        if (trueOptimum != null && trueOptimum > 0) {
            val ledger = WakfuBuildSolver.certifyLedgerForTest(p, pool, runes, subs, incumbentObjective = trueOptimum, targetAware = true)
            ledger.maxCellObjective?.let { if (it < trueOptimum) failures += "$label LEDGER(incumbent) max=$it < hard-leg optimum $trueOptimum" }
            val all = WakfuBuildSolver.certifyLedgerForTest(p, pool, runes, subs, forceTier2All = true, targetAware = true)
            all.maxCellObjective?.let { if (it < trueOptimum) failures += "$label LEDGER(forceTier2All) max=$it < hard-leg optimum $trueOptimum" }
        }
        println(
            "TA_LOCK $label compared=$compared tightened=$tightened tight=$tight binding=$bindingCarried notOptimal=$notOptimal " +
                "trueOpt=$trueOptimum on=${onExact.toSortedMap()} off=${offExact.toSortedMap()}"
        )
        return Outcome(compared, tightened, tight, bindingCarried, notOptimal, failures)
    }

    private fun assertSound(outcome: Outcome) {
        outcome.failures.forEach { println("TA_LOCK_FAIL $it") }
        assertThat(outcome.failures).describedAs("SOUNDNESS — the target-aware certificate never under-counts the hard leg").isEmpty()
        assertThat(outcome.notOptimal).describedAs("every reachable cell is proven OPTIMAL by the pinned hard-leg solve").isZero()
    }

    // ---- Seeded fuzz: binding AP / MP / RANGE / CC rows, real range / MP / AP subs ------------------------------------

    private class FuzzCase(
        val label: String,
        val params: WakfuBestBuildParams,
        val pool: Map<ItemType, List<Equipment>>,
        val subs: List<Sublimation>,
    )

    // The track-1 research fuzz (docs/perf-research-2026-10 §1.6) with Furie II and Combat rapproché II added and RANGE rows
    // up to 5, so both sides of the RANGE_AT_LEAST exclusion (row ≤ 4: excluded, row 5: credited) and a negative-range sub
    // the free credit ignores are exercised.
    private fun fuzzCase(seed: Long): FuzzCase {
        val rng = java.util.Random(seed)
        val level = listOf(110, 170, 230)[rng.nextInt(3)]
        val melee = rng.nextInt(4) == 0
        val scenario = DamageScenario(rangeBand = if (melee) RangeBand.MELEE else RangeBand.DISTANCE)
        val rows = mutableListOf<TargetStat>()
        rows += TargetStat(Characteristic.ACTION_POINT, 7 + rng.nextInt(3))
        if (rng.nextInt(10) < 7) rows += TargetStat(Characteristic.MOVEMENT_POINT, 4 + rng.nextInt(2))
        if (rng.nextInt(10) < 7) rows += TargetStat(Characteristic.RANGE, 1 + rng.nextInt(5))
        if (rng.nextInt(10) < 4) rows += TargetStat(Characteristic.CRITICAL_HIT, 10 + rng.nextInt(21))
        if (rng.nextInt(3) == 0) rows += TargetStat(Characteristic.HP, if (rng.nextBoolean()) 0 else 1200 + rng.nextInt(1200))
        if (rng.nextInt(3) == 0) rows += TargetStat(Characteristic.DODGE, 0)
        var id = (seed % 100_000).toInt() * 100

        fun randomItem(
            type: ItemType,
            name: String,
            rarity: Rarity? = null,
        ): Equipment {
            val stats = mutableMapOf<Characteristic, Int>()
            stats[if (rng.nextInt(3) == 0) Characteristic.MASTERY_ELEMENTARY else Characteristic.MASTERY_ELEMENTARY_FIRE] = 100 + rng.nextInt(700)
            if (rng.nextInt(10) < 4) stats[scenario.rangeBand.masteryCharacteristic] = rng.nextInt(600) - 200
            if (rng.nextInt(10) < 3) stats[Characteristic.MASTERY_CRITICAL] = rng.nextInt(300) - 50
            if (rng.nextInt(10) < 4) stats[Characteristic.CRITICAL_HIT] = rng.nextInt(16) - 4
            if (rng.nextInt(10) < 3) stats[Characteristic.ACTION_POINT] = rng.nextInt(3) - 1
            if (rng.nextInt(10) < 4) stats[Characteristic.MOVEMENT_POINT] = rng.nextInt(4) - 1
            if (rng.nextInt(10) < 5) stats[Characteristic.RANGE] = rng.nextInt(4) - 1
            if (type != ItemType.RING && rng.nextInt(6) == 0) stats[Characteristic.DAMAGE_INFLICTED] = 5 + rng.nextInt(20)
            if (rng.nextInt(3) == 0) stats[Characteristic.HP] = rng.nextInt(600) - 50
            if (rng.nextInt(4) == 0) stats[Characteristic.DODGE] = rng.nextInt(120) - 40
            id++
            return Equipment(
                equipmentId = id,
                guiId = id,
                level = 50 + rng.nextInt(60),
                name = I18nText("$name$id", "$name$id", "", ""),
                rarity = rarity ?: if (rng.nextInt(8) == 0) Rarity.RELIC else Rarity.COMMON,
                itemType = type,
                characteristics = stats.filterValues { it != 0 },
                maxShardSlots = listOf(0, 3, 3, 4)[rng.nextInt(4)]
            )
        }
        val items = mutableListOf<Equipment>()
        val singles = listOf(ItemType.AMULET, ItemType.BELT, ItemType.CAPE, ItemType.BOOTS, ItemType.HELMET, ItemType.CHEST_PLATE, ItemType.SHOULDER_PADS)
        val slots = singles.shuffled(rng).take(3 + rng.nextInt(3))
        for (slot in slots) repeat(2 + rng.nextInt(2)) { items += randomItem(slot, "ta") }
        if (rng.nextInt(3) != 0) {
            items += randomItem(ItemType.RING, "ringA")
            items += randomItem(ItemType.RING, "ringB")
            if (rng.nextBoolean()) items += randomItem(ItemType.RING, "ringC")
        }
        if (rng.nextInt(3) == 0) items += randomItem(ItemType.TWO_HANDED_WEAPONS, "w2")
        if (rng.nextInt(3) != 0) items += randomItem(ItemType.ONE_HANDED_WEAPONS, "w1")
        if (rng.nextInt(3) == 0) items += randomItem(ItemType.OFF_HAND_WEAPONS, "off")
        if (rng.nextInt(3) != 0) items += randomItem(slots.first(), "epic", rarity = Rarity.EPIC)
        val choosable = catalog.filter { it.solverChoosable }
        val wanted = mutableListOf<String>()
        if (rng.nextBoolean()) wanted += "Visibilité II"
        if (rng.nextBoolean()) wanted += "Poids Plume III"
        if (rng.nextBoolean()) wanted += "Vivacité II"
        if (rng.nextBoolean()) wanted += "Armure lourde II"
        if (rng.nextInt(3) == 0) wanted += "Vélocité II"
        if (rng.nextInt(3) == 0) wanted += "Carapace II"
        if (rng.nextBoolean()) wanted += "Furie II"
        if (rng.nextInt(3) == 0) wanted += "Combat rapproché II"
        val subs = (choosable.filter { it.name.fr in wanted } + choosable.shuffled(rng).take(2 + rng.nextInt(3))).distinct()
        val p =
            WakfuBestBuildParams(
                character = Character(CharacterClass.CRA, level, 0, CharacterSkills(level)),
                targetStats = TargetStats(rows + TargetStat(Characteristic.MASTERY_DISTANCE, 1)),
                searchDuration = 60.seconds,
                stopWhenBuildMatch = false,
                maxRarity = Rarity.EPIC,
                forcedItems = emptyList(),
                excludedItems = emptyList(),
                scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE,
                useRunes = rng.nextInt(3) != 0,
                useSublimations = true,
                damageScenario = scenario
            )
        return FuzzCase("ta-seed$seed", p, items.groupBy { it.itemType }, subs)
    }

    /**
     * CI LOCK: the seeded target-aware fuzz on fixed seeds. Per AP cell the target-aware exact / tier-1.5 / fast values
     * upper-bound the pinned HARD-LEG optimum, stay ordered, never exceed the target-blind ones, and the production ledger
     * keeps its max ≥ the true hard-leg optimum — while the rows actually bind (some cells strictly tighter than v51, so
     * the lock is not vacuous). `WAKFU_TA_FUZZ_SEEDS=a,b,…` replays other seeds.
     */
    @Test
    fun `target-aware fuzz lock - binding rows never under-count the pinned hard-leg optimum`() {
        val seeds =
            System
                .getenv("WAKFU_TA_FUZZ_SEEDS")
                ?.split(',')
                ?.mapNotNull { it.trim().toLongOrNull() }
                ?: DEFAULT_FUZZ_SEEDS
        val failures = mutableListOf<String>()
        var compared = 0
        var tightened = 0
        var notOptimal = 0
        for (seed in seeds) {
            val started = System.nanoTime()
            val c = fuzzCase(seed)
            val runes = if (c.params.useRunes) WakfuBestBuildFinderAlgorithm.runes else emptyList()
            val o = check(c.label, c.params, c.pool, c.subs, runes)
            failures += o.failures
            compared += o.compared
            tightened += o.tightened
            notOptimal += o.notOptimal
            println(
                "TA_FUZZ ${c.label} level=${c.params.character.level} rows=${c.params.targetStats.map { "${it.characteristic}=${it.target}" }} " +
                    "runes=${c.params.useRunes} subs=${c.subs.map { it.name.fr }} ms=${(System.nanoTime() - started) / 1_000_000}"
            )
        }
        println("TA_FUZZ_SUMMARY seeds=${seeds.size} compared=$compared tightened=$tightened notOptimal=$notOptimal failures=${failures.size}")
        failures.forEach { println("TA_FUZZ_FAIL $it") }
        assertThat(failures).describedAs("SOUNDNESS — the target-aware certificate never under-counts the hard leg").isEmpty()
        assertThat(notOptimal).describedAs("every reachable cell is proven OPTIMAL by the pinned hard-leg solve").isZero()
        assertThat(compared).describedAs("certified cells compared against the pinned hard leg").isGreaterThanOrEqualTo(6 * seeds.size)
        assertThat(tightened).describedAs("the rows bind: some exact cells are strictly tighter than the target-blind certifier").isGreaterThan(0)
    }

    // ---- Binding-source fixtures --------------------------------------------------------------------------------------

    /**
     * Furie II (+1 range under `range ≥ 4`, EPIC — the only RANGE_AT_LEAST sub). Its line is left out of the RANGE row's
     * free credit only when its threshold already meets the row: at RANGE 5 it is CREDITED — the optimum reaches 5 as a
     * pre-combat 4 + Furie's +1 (a mutation excluding it there under-counts every such cell); at RANGE 4 it is excluded and
     * a build carrying it already reads ≥ 4 without it.
     */
    @Test
    fun `binding source - Furie II's RANGE_AT_LEAST line`() {
        val fire = Characteristic.MASTERY_ELEMENTARY_FIRE
        val range = Characteristic.RANGE
        val pool =
            listOf(
                item(101, ItemType.HELMET, mapOf(fire to 900, range to 2)),
                item(102, ItemType.HELMET, mapOf(fire to 1300)),
                item(103, ItemType.BOOTS, mapOf(fire to 900, range to 1)),
                item(104, ItemType.BOOTS, mapOf(fire to 1250)),
                item(105, ItemType.CAPE, mapOf(fire to 950, range to 1)),
                item(106, ItemType.CAPE, mapOf(fire to 1200)),
                item(107, ItemType.AMULET, mapOf(fire to 700, Characteristic.ACTION_POINT to 1), rarity = Rarity.EPIC)
            ).groupBy { it.itemType }
        val furie = sub("Furie II")
        for (row in listOf(5, 4)) {
            val o =
                check("furie-range$row", params(listOf(TargetStat(Characteristic.RANGE, row))), pool, listOf(furie)) {
                    furie.stateId in it.selectedSublimationStateIds
                }
            assertSound(o)
            assertThat(o.compared).describedAs("RANGE $row: cells compared").isGreaterThan(3)
            assertThat(o.tightened).describedAs("RANGE $row: the row binds").isGreaterThan(0)
            if (row == 5) assertThat(o.bindingCarried).describedAs("RANGE 5: the hard-leg optima reach the row through Furie II").isGreaterThan(0)
        }
    }

    /**
     * A negative-range item is the damage-best piece: the row forces the build to pay for it elsewhere. The range digit
     * floors each item's range at 0 — an over-estimate, so the bound stays sound but cannot price the −2 (it is loose
     * here, legitimately): what this locks is that the floored item never under-counts the hard leg that carries it.
     */
    @Test
    fun `binding source - a negative-range item`() {
        val fire = Characteristic.MASTERY_ELEMENTARY_FIRE
        val range = Characteristic.RANGE
        val pool =
            listOf(
                item(201, ItemType.HELMET, mapOf(fire to 1800, range to -2), name = "glassHelmet"),
                item(202, ItemType.HELMET, mapOf(fire to 1100)),
                item(203, ItemType.BOOTS, mapOf(fire to 800, range to 2)),
                item(204, ItemType.BOOTS, mapOf(fire to 1150)),
                item(205, ItemType.CAPE, mapOf(fire to 700, range to 2)),
                item(206, ItemType.CAPE, mapOf(fire to 1000, range to -1)),
                item(207, ItemType.AMULET, mapOf(fire to 900, range to 1))
            ).groupBy { it.itemType }
        val o =
            check("negative-range", params(listOf(TargetStat(Characteristic.RANGE, 3), TargetStat(Characteristic.ACTION_POINT, 7))), pool, listOf(sub("Visibilité II"))) {
                201 in it.selectedEquipmentIds
            }
        assertSound(o)
        assertThat(o.compared).describedAs("cells compared").isGreaterThan(0)
        assertThat(o.bindingCarried).describedAs("hard-leg optima wear the negative-range helmet").isGreaterThan(0)
    }

    /** A −MP item is the damage-best piece and the MP row forces compensating MP (the Major MP point, an MP item, Vélocité II). */
    @Test
    fun `binding source - a negative-MP item`() {
        val fire = Characteristic.MASTERY_ELEMENTARY_FIRE
        val mp = Characteristic.MOVEMENT_POINT
        val pool =
            listOf(
                item(301, ItemType.BOOTS, mapOf(fire to 1900, mp to -1), name = "leadBoots"),
                item(302, ItemType.BOOTS, mapOf(fire to 1000, mp to 1)),
                item(303, ItemType.HELMET, mapOf(fire to 1200)),
                item(304, ItemType.HELMET, mapOf(fire to 700, mp to 1)),
                item(305, ItemType.CAPE, mapOf(fire to 1100)),
                item(306, ItemType.CAPE, mapOf(fire to 650, mp to 1)),
                item(307, ItemType.AMULET, mapOf(fire to 800, Characteristic.ACTION_POINT to 1))
            ).groupBy { it.itemType }
        val subs = listOf(sub("Vélocité II"), sub("Armure lourde II"))
        for (row in listOf(4, 5)) {
            val o = check("negative-mp$row", params(listOf(TargetStat(Characteristic.MOVEMENT_POINT, row))), pool, subs) { 301 in it.selectedEquipmentIds }
            assertSound(o)
            assertThat(o.tightened).describedAs("MP $row: the row binds").isGreaterThan(0)
            if (row == 4) assertThat(o.bindingCarried).describedAs("MP 4: hard-leg optima wear the −MP boots").isGreaterThan(0)
        }
    }

    /** A damage-less ring that only carries range: worthless to the target-blind certifier, the row's only cheap source here. */
    @Test
    fun `binding source - a pure-range ring`() {
        val fire = Characteristic.MASTERY_ELEMENTARY_FIRE
        val range = Characteristic.RANGE
        val pool =
            listOf(
                item(401, ItemType.RING, mapOf(range to 2), name = "scopeRing"),
                item(402, ItemType.RING, mapOf(fire to 600), name = "fireRingA"),
                item(403, ItemType.RING, mapOf(fire to 550), name = "fireRingB"),
                item(404, ItemType.HELMET, mapOf(fire to 1200)),
                item(405, ItemType.HELMET, mapOf(fire to 500, range to 1)),
                item(406, ItemType.BOOTS, mapOf(fire to 1100)),
                item(407, ItemType.CAPE, mapOf(fire to 1000))
            ).groupBy { it.itemType }
        val o =
            check("pure-range-ring", params(listOf(TargetStat(Characteristic.RANGE, 3), TargetStat(Characteristic.ACTION_POINT, 6))), pool, emptyList()) {
                401 in it.selectedEquipmentIds
            }
        assertSound(o)
        assertThat(o.tightened).describedAs("the RANGE row binds").isGreaterThan(0)
        assertThat(o.bindingCarried).describedAs("hard-leg optima wear the pure-range ring").isGreaterThan(0)
    }

    /** No item carries range: the Major "Range and damage" point (+1 range, +40 mastery) plus Visibilité II are the only sources. */
    @Test
    fun `binding source - the Major range point`() {
        val fire = Characteristic.MASTERY_ELEMENTARY_FIRE
        val pool =
            listOf(
                item(501, ItemType.HELMET, mapOf(fire to 1200)),
                item(502, ItemType.HELMET, mapOf(fire to 900, Characteristic.ACTION_POINT to 1)),
                item(503, ItemType.BOOTS, mapOf(fire to 1100)),
                item(504, ItemType.CAPE, mapOf(fire to 1000, Characteristic.CRITICAL_HIT to 5)),
                item(505, ItemType.AMULET, mapOf(fire to 950, Characteristic.MOVEMENT_POINT to 1))
            ).groupBy { it.itemType }
        // RANGE 2 = Visibilité II (free credit) + the Major point: every targets-met build spends a Major point on range.
        val o =
            check("major-range", params(listOf(TargetStat(Characteristic.RANGE, 2), TargetStat(Characteristic.CRITICAL_HIT, 5))), pool, listOf(sub("Visibilité II")))
        assertSound(o)
        assertThat(o.compared).describedAs("cells compared").isGreaterThan(3)
        assertThat(o.tight).describedAs("the Major range point is priced exactly: the exact pass meets the hard leg").isGreaterThan(0)
    }

    /**
     * The MP row above Poids Plume III's saturation: the v49 clamp collapses the frontier's MP once the ramp is saturated,
     * which is value-exact for the ramp but would sit BELOW this row's need — the clamp is raised to the row's floor (need +
     * every later debit), so a clamped point still passes exactly when its unclamped self would. MP 9 is reached only past
     * the 8-MP sheet cap, through Vélocité II.
     */
    @Test
    fun `binding source - an MP row above the ramp saturation keeps the clamp exact`() {
        val fire = Characteristic.MASTERY_ELEMENTARY_FIRE
        val mp = Characteristic.MOVEMENT_POINT
        val pool =
            listOf(
                item(1001, ItemType.HELMET, mapOf(fire to 1200)),
                item(1002, ItemType.HELMET, mapOf(fire to 700, mp to 2)),
                item(1003, ItemType.BOOTS, mapOf(fire to 1100)),
                item(1004, ItemType.BOOTS, mapOf(fire to 600, mp to 2)),
                item(1005, ItemType.CAPE, mapOf(fire to 1000)),
                item(1006, ItemType.CAPE, mapOf(fire to 550, mp to 2)),
                item(1007, ItemType.AMULET, mapOf(fire to 900, mp to 1))
            ).groupBy { it.itemType }
        val subs = listOf(sub("Poids Plume III"), sub("Vélocité II"), sub("Armure lourde II"))
        for (row in listOf(9, 7)) {
            val o = check("mp-clamp$row", params(listOf(TargetStat(Characteristic.MOVEMENT_POINT, row))), pool, subs)
            assertSound(o)
            assertThat(o.compared).describedAs("MP $row: cells compared").isGreaterThan(0)
            assertThat(o.tightened).describedAs("MP $row: the row binds").isGreaterThan(0)
        }
    }

    /**
     * The v49 MP clamp must stay VALUE-EXACT under an MP row: target-aware maps and ledgers identical with the clamp on and off.
     * The items alone carry +12 MP — past the 8-MP sheet cap, so CP-SAT never sees such a build, which is why the clamp-off DP
     * is the reference here (as in the v51 clamp lock) — and the MP 12 row sits above Poids Plume III's saturation: a clamp
     * left at the ramp's saturation rewrites those points below the row's need, and Armure lourde II's later −1 then drops
     * them (RED under that mutation); raised to the row's floor (need + every later debit) it changes nothing.
     */
    @Test
    fun `the MP clamp is value-exact under an MP row above the ramp saturation`() {
        val fire = Characteristic.MASTERY_ELEMENTARY_FIRE
        val mp = Characteristic.MOVEMENT_POINT
        val pool =
            listOf(
                item(1201, ItemType.HELMET, mapOf(fire to 900, mp to 4)),
                item(1202, ItemType.BOOTS, mapOf(fire to 900, mp to 4)),
                item(1203, ItemType.CAPE, mapOf(fire to 900, mp to 4)),
                item(1204, ItemType.AMULET, mapOf(fire to 800))
            ).groupBy { it.itemType }
        val subs = listOf(sub("Poids Plume III"), sub("Armure lourde II"))
        val p = params(listOf(TargetStat(Characteristic.MOVEMENT_POINT, 12)))

        fun certify(clamp: Boolean): Pair<Triple<Map<Int, Long>, Map<Int, Long>, Map<Int, Long>>, Map<Int, Long>> {
            val previous = CertifierTuning.mpSaturationClampEnabled
            CertifierTuning.mpSaturationClampEnabled = clamp
            try {
                return WakfuBuildSolver.certifierExactFastTier15CellObjectivesForTest(p, pool, sublimations = subs, targetAware = true) to
                    WakfuBuildSolver.certifyLedgerForTest(p, pool, sublimations = subs, forceTier2All = true, targetAware = true).cellObjectives
            } finally {
                CertifierTuning.mpSaturationClampEnabled = previous
            }
        }
        val rewrites0 = CertifierTuning.mpClampRewritesForTest.get()
        val clamped = certify(clamp = true)
        assertThat(CertifierTuning.mpClampRewritesForTest.get() - rewrites0).describedAs("the clamp rewrites frontiers (else this lock is vacuous)").isGreaterThan(0)
        val unclamped = certify(clamp = false)
        assertThat(
            clamped.first.first.values
                .any { it > 0 }
        ).describedAs("the MP 12 row is reachable in the DP").isTrue
        assertThat(clamped).describedAs("clamp on ≡ clamp off under the MP row (maps and the forced-exact ledger)").isEqualTo(unclamped)
    }

    /**
     * A CC row ABOVE the enumerated crit range ([cEnumMax] — here the scenario caps crit at 50 and items + skills stay below
     * it): the targets-met builds reach the row only through crit subs past the cap, which the certifier covers AT
     * [cEnumMax]. Skipping every step below the row would skip that very step (all cells 0), so such a row is not enforced.
     */
    @Test
    fun `a CC row above the enumerated crit range is not enforced`() {
        val fire = Characteristic.MASTERY_ELEMENTARY_FIRE
        val crit = Characteristic.CRITICAL_HIT
        val pool =
            listOf(
                item(1101, ItemType.HELMET, mapOf(fire to 1000, crit to 10)),
                item(1102, ItemType.BOOTS, mapOf(fire to 900, crit to 8)),
                item(1103, ItemType.CAPE, mapOf(fire to 800, crit to 5)),
                item(1104, ItemType.AMULET, mapOf(fire to 700))
            ).groupBy { it.itemType }
        val subs = listOf(sub("Influence III"), sub("Influence vitale III"))
        val capped = DamageScenario(element = SpellElement.FIRE, rangeBand = RangeBand.DISTANCE, orientation = Orientation.FACE, critCapPercent = 50)
        val o = check("cc-above-enum", params(listOf(TargetStat(Characteristic.CRITICAL_HIT, 60)), scenario = capped), pool, subs)
        assertSound(o)
        assertThat(o.compared).describedAs("the CC 60 hard leg is reachable (through crit subs)").isGreaterThan(0)
    }

    /**
     * A ring whose only mastery is another element (zero graw in a fire scenario) but that carries +1 AP or +8 crit is how a
     * build reaches its AP cell / crit step. The EXACT pass's plain-ring collapse dropped every graw-≤-0 ring until v52 —
     * an under-count of the cell, target-blind and target-aware alike: the AP-8 cell read 0 against a pinned optimum of
     * 2 195 545 that wears the AP ring (the fast and tier-1.5 passes, whose rings are explicit options, were right). Red on
     * the v51 exact pass, green since the ring stage keeps every ring with a non-zero cost cell.
     */
    @Test
    fun `zero-graw rings carrying AP or crit stay in the exact ring stage`() {
        val fire = Characteristic.MASTERY_ELEMENTARY_FIRE
        val water = Characteristic.MASTERY_ELEMENTARY_WATER
        val pool =
            listOf(
                item(801, ItemType.RING, mapOf(water to 300, Characteristic.CRITICAL_HIT to 8), name = "critRing"),
                item(802, ItemType.RING, mapOf(water to 300, Characteristic.ACTION_POINT to 1), name = "apRing"),
                item(803, ItemType.RING, mapOf(fire to 600), name = "fireRingA"),
                item(804, ItemType.RING, mapOf(fire to 500), name = "fireRingB"),
                item(805, ItemType.HELMET, mapOf(fire to 1200)),
                item(806, ItemType.BOOTS, mapOf(fire to 1100, Characteristic.CRITICAL_HIT to 3)),
                item(807, ItemType.CAPE, mapOf(fire to 1000))
            ).groupBy { it.itemType }
        // Target-blind: the pinned optimum of every cell over all builds (no row — the free request).
        val free = params(emptyList())
        val (exact, fast, tier15) = WakfuBuildSolver.certifierExactFastTier15CellObjectivesForTest(free, pool)
        val failures = mutableListOf<String>()
        var apRingCells = 0
        for (ap in exact.keys.sorted()) {
            val truth =
                WakfuBuildSolver
                    .timedMaxDamageProfileForTest(
                        free.copy(maxDamageApTarget = ap),
                        pool,
                        emptyList(),
                        emptyList(),
                        workers = 1,
                        seconds = 60.0,
                        applyDomination = false,
                        randomSeed = 1,
                        interleave = true,
                        deterministicLimit = 10.0
                    ).takeIf { it.hasSolution } ?: continue
            assertThat(truth.status).isEqualTo("OPTIMAL")
            if (802 in truth.selectedEquipmentIds) apRingCells++
            for ((tier, v) in listOf("exact" to exact[ap], "tier-1.5" to tier15[ap], "fast" to fast[ap])) {
                if (v != null && v >= 0 && v < truth.rawObjective) failures += "free AP=$ap: $tier $v < pinned optimum ${truth.rawObjective}"
            }
        }
        assertThat(failures).describedAs("SOUNDNESS — a zero-graw AP / crit ring must not be dropped").isEmpty()
        assertThat(apRingCells).describedAs("a pinned optimum wears the zero-graw AP ring (else the lock is vacuous)").isGreaterThan(0)
        // Target-aware: the same pool under AP / CC rows against the hard leg.
        assertSound(check("zero-graw-ring", params(listOf(TargetStat(Characteristic.ACTION_POINT, 7), TargetStat(Characteristic.CRITICAL_HIT, 15))), pool, emptyList()))
    }

    /**
     * A damage-less EPIC item (an HP-only belt) is the carrier the epic sub Mesure III (+20 DI) needs — and the pinned optimum
     * wears it. Until v52 no pass ever saw such an item (the DP's item list held only items carrying a stat it reads), so
     * every pass, target-blind and target-aware, under-counted the cell by ~15 % (AP 6: 1 452 000 vs 1 716 000). Red on
     * v51, green since every epic / relic item enters the DP as a rarity resource.
     */
    @Test
    fun `a damage-less epic item still carries the epic sub`() {
        val fire = Characteristic.MASTERY_ELEMENTARY_FIRE
        val pool =
            listOf(
                item(901, ItemType.BELT, mapOf(Characteristic.HP to 400), rarity = Rarity.EPIC, name = "epicHpBelt"),
                item(902, ItemType.HELMET, mapOf(fire to 1200)),
                item(903, ItemType.BOOTS, mapOf(fire to 1100)),
                item(904, ItemType.CAPE, mapOf(fire to 1000)),
                item(905, ItemType.RING, mapOf(Characteristic.HP to 300), rarity = Rarity.RELIC, name = "relicHpRing")
            ).groupBy { it.itemType }
        val subs = listOf(sub("Mesure III"), sub("Abnégation"))
        val free = params(emptyList())
        val (exact, fast, tier15) = WakfuBuildSolver.certifierExactFastTier15CellObjectivesForTest(free, pool, sublimations = subs)
        val failures = mutableListOf<String>()
        var carried = 0
        for (ap in exact.keys.sorted()) {
            val truth =
                WakfuBuildSolver
                    .timedMaxDamageProfileForTest(
                        free.copy(maxDamageApTarget = ap),
                        pool,
                        emptyList(),
                        subs,
                        workers = 1,
                        seconds = 60.0,
                        applyDomination = false,
                        randomSeed = 1,
                        interleave = true,
                        deterministicLimit = 10.0
                    ).takeIf { it.hasSolution } ?: continue
            assertThat(truth.status).isEqualTo("OPTIMAL")
            if (901 in truth.selectedEquipmentIds && sub("Mesure III").stateId in truth.selectedSublimationStateIds) carried++
            for ((tier, v) in listOf("exact" to exact[ap], "tier-1.5" to tier15[ap], "fast" to fast[ap])) {
                if (v != null && v >= 0 && v < truth.rawObjective) failures += "free AP=$ap: $tier $v < pinned optimum ${truth.rawObjective}"
            }
        }
        assertThat(failures).describedAs("SOUNDNESS — a damage-less epic carrier must stay in reach").isEmpty()
        assertThat(carried).describedAs("a pinned optimum socket Mesure III on the damage-less epic belt (else the lock is vacuous)").isGreaterThan(0)
        assertSound(check("epic-carrier", params(listOf(TargetStat(Characteristic.ACTION_POINT, 7))), pool, subs))
    }

    /**
     * Latent (CERTIFIER_VERSION 53, the PR #222 review): the RANGE row's free credit leaves out a `RANGE_AT_LEAST n` sub with
     * n ≥ the row, which is sound only while the sub's own +range line cannot feed its condition. A PERMANENT line can (the
     * model gates it on the raw sub var): here the hard leg reaches RANGE 3 as items 2 + the sub's own +1, keeping the Major
     * point for damage, while the excluded credit forced the DP to spend that point on range — an under-count. No shipped sub
     * has the shape (the extractor flags only FLAT subs' lines permanent): the ledger now bails on it, and keeps certifying
     * the same sub with a non-permanent line (Furie II's shape).
     */
    @Test
    fun `latent - a RANGE_AT_LEAST sub with its own permanent range line bails the target-aware ledger`() {
        val fire = Characteristic.MASTERY_ELEMENTARY_FIRE
        val range = Characteristic.RANGE

        fun selfFeeding(permanent: Boolean) =
            Sublimation(
                stateId = 99_531,
                name = I18nText("sySelfFeed", "sySelfFeed", "", ""),
                rarity = SublimationRarity.NORMAL,
                kind = SublimationKind.STATIC_CONDITIONAL,
                solverChoosable = true,
                condition = SublimationCondition(SublimationConditionType.RANGE_AT_LEAST, 3),
                effects =
                    listOf(
                        SublimationEffect.Flat(range, 1, appliesBeforeCombat = permanent),
                        SublimationEffect.Flat(Characteristic.DAMAGE_INFLICTED, 6)
                    )
            )
        val pool =
            listOf(
                item(951, ItemType.HELMET, mapOf(fire to 1200, range to 1)),
                item(952, ItemType.HELMET, mapOf(fire to 1300)),
                item(953, ItemType.BOOTS, mapOf(fire to 1100, range to 1)),
                item(954, ItemType.CAPE, mapOf(fire to 1000))
            ).groupBy { it.itemType }
        val p = params(listOf(TargetStat(range, 3)))
        val permanent = listOf(selfFeeding(permanent = true))
        val bailed = WakfuBuildSolver.certifyLedgerForTest(p, pool, emptyList(), permanent, forceTier2All = true, targetAware = true)
        assertThat(bailed.maxCellObjective).describedAs("a self-feeding permanent range line bails the target-aware ledger").isNull()
        assertSound(check("self-feeding-permanent", p, pool, permanent))
        val startOfCombat = listOf(selfFeeding(permanent = false))
        assertThat(WakfuBuildSolver.certifyLedgerForTest(p, pool, emptyList(), startOfCombat, forceTier2All = true, targetAware = true).maxCellObjective)
            .describedAs("the shipped shape (a start-of-combat line) still certifies")
            .isNotNull()
        assertSound(check("self-feeding-start-of-combat", p, pool, startOfCombat))
    }

    // ---- Bit-identity and wiring ----------------------------------------------------------------------------------------

    /**
     * A request WITHOUT an AP / MP / CC / RANGE row gets bit-identical values with the hard-leg flag on (every row reads 0),
     * and a request WITH rows is bit-identical to the target-blind certifier with the kill switch off — every map and both
     * ledgers, so the v51 behaviour of the soft leg, the free request and the seam-off arm is locked.
     */
    @Test
    fun `requests without enforceable rows and the seam off are bit-identical to the target-blind certifier`() {
        val fire = Characteristic.MASTERY_ELEMENTARY_FIRE
        val pool =
            listOf(
                item(601, ItemType.HELMET, mapOf(fire to 900, Characteristic.RANGE to 2, Characteristic.HP to 200)),
                item(602, ItemType.HELMET, mapOf(fire to 1300, Characteristic.MOVEMENT_POINT to -1)),
                item(603, ItemType.BOOTS, mapOf(fire to 900, Characteristic.MOVEMENT_POINT to 1, Characteristic.CRITICAL_HIT to 4)),
                item(604, ItemType.BOOTS, mapOf(fire to 1250, Characteristic.ACTION_POINT to 1)),
                item(605, ItemType.RING, mapOf(fire to 500, Characteristic.RANGE to 1), name = "ringA"),
                item(606, ItemType.RING, mapOf(fire to 450, Characteristic.HP to 300), name = "ringB"),
                item(607, ItemType.AMULET, mapOf(fire to 700, Characteristic.DODGE to 40), rarity = Rarity.EPIC)
            ).groupBy { it.itemType }
        val subs = listOf("Visibilité II", "Poids Plume III", "Vivacité II", "Armure lourde II", "Furie II").map(::sub)
        val runes = WakfuBestBuildFinderAlgorithm.runes

        fun maps(
            p: WakfuBestBuildParams,
            targetAware: Boolean,
        ) = WakfuBuildSolver.certifierExactFastTier15CellObjectivesForTest(p, pool, runes, subs, targetAware = targetAware)

        fun ledger(
            p: WakfuBestBuildParams,
            targetAware: Boolean,
        ) = WakfuBuildSolver.certifyLedgerForTest(p, pool, runes, subs, forceTier2All = true, targetAware = targetAware).cellObjectives

        val noRows =
            params(listOf(TargetStat(Characteristic.HP, 1500), TargetStat(Characteristic.DODGE, 0), TargetStat(Characteristic.RESISTANCE_ELEMENTARY_WIND, 0)), useRunes = true)
        assertThat(targetAwareLedgerApplies(noRows.targetStats)).isFalse
        assertThat(maps(noRows, targetAware = true)).describedAs("no enforceable row ⇒ the target-blind maps").isEqualTo(maps(noRows, targetAware = false))
        assertThat(ledger(noRows, targetAware = true)).describedAs("no enforceable row ⇒ the target-blind ledger").isEqualTo(ledger(noRows, targetAware = false))

        val withRows =
            params(
                listOf(
                    TargetStat(Characteristic.ACTION_POINT, 8),
                    TargetStat(Characteristic.MOVEMENT_POINT, 4),
                    TargetStat(Characteristic.RANGE, 4),
                    TargetStat(Characteristic.CRITICAL_HIT, 10)
                ),
                useRunes = true
            )
        val blind = maps(withRows, targetAware = false)
        val blindLedger = ledger(withRows, targetAware = false)
        assertThat(maps(withRows, targetAware = true)).describedAs("the rows bind on this pool (else the seam-off arm proves nothing)").isNotEqualTo(blind)
        val previous = CertifierTuning.targetAwareEnabled
        CertifierTuning.targetAwareEnabled = false
        try {
            assertThat(targetAwareLedgerApplies(withRows.targetStats)).isFalse
            assertThat(maps(withRows, targetAware = true)).describedAs("seam off ⇒ the target-blind maps").isEqualTo(blind)
            assertThat(ledger(withRows, targetAware = true)).describedAs("seam off ⇒ the target-blind ledger").isEqualTo(blindLedger)
        } finally {
            CertifierTuning.targetAwareEnabled = previous
        }
    }

    @Test
    fun `targetAwareLedgerApplies only on a positive AP, MP, CC or RANGE row`() {
        fun applies(vararg rows: TargetStat) = targetAwareLedgerApplies(TargetStats(rows.toList()))
        assertThat(applies()).isFalse
        assertThat(applies(TargetStat(Characteristic.MASTERY_DISTANCE, 1), TargetStat(Characteristic.HP, 2000))).isFalse
        assertThat(applies(TargetStat(Characteristic.RANGE, 0), TargetStat(Characteristic.DODGE, 0))).isFalse
        for (stat in listOf(Characteristic.ACTION_POINT, Characteristic.MOVEMENT_POINT, Characteristic.CRITICAL_HIT, Characteristic.RANGE)) {
            assertThat(applies(TargetStat(stat, 1))).describedAs("$stat").isTrue
        }
    }

    /**
     * The hard-leg flag keys the certificate cache (memory and disk fingerprint): the target-aware and the target-blind
     * ledger of the SAME request are two computes, never served from each other — a wrong-keyed hit would be an unsound
     * badge. And the production consumer: a hard-leg result is compared with the target-aware ledger (here it closes the
     * badge to ProvenOptimal), the same build without the flag with the target-blind one (only "proven within").
     */
    @Test
    fun `the hard-leg flag keys the certificate cache and picks the ledger proveOptimality reads`() {
        val fire = Characteristic.MASTERY_ELEMENTARY_FIRE
        val range = Characteristic.RANGE
        val pool =
            listOf(
                item(701, ItemType.HELMET, mapOf(fire to 900, range to 2)),
                item(702, ItemType.HELMET, mapOf(fire to 1300)),
                item(703, ItemType.BOOTS, mapOf(fire to 900, range to 1)),
                item(704, ItemType.BOOTS, mapOf(fire to 1250)),
                item(705, ItemType.CAPE, mapOf(fire to 1000))
            ).groupBy { it.itemType }
        val p = params(listOf(TargetStat(range, 3)))
        assertThat(MaxDamageCertificateCache.fingerprintForTest(p, pool, emptyList(), emptyList(), applyDomination = true, targetAware = true))
            .describedAs("the hard-leg flag is part of the disk fingerprint")
            .isNotEqualTo(MaxDamageCertificateCache.fingerprintForTest(p, pool, emptyList(), emptyList(), applyDomination = true))

        MaxDamageCertificateCache.clear()
        try {
            val computes0 = MaxDamageCertificateCache.computeCountForTest.get()

            fun cert(targetAware: Boolean) =
                MaxDamageCertificateCache.certificate(
                    p,
                    pool,
                    emptyList(),
                    emptyList(),
                    applyDomination = true,
                    incumbentObjective = null,
                    threads = 1,
                    targetAware = targetAware
                )!!
            val aware = cert(targetAware = true)
            val blind = cert(targetAware = false)
            assertThat(MaxDamageCertificateCache.computeCountForTest.get() - computes0).describedAs("two flavours ⇒ two computes").isEqualTo(2)
            assertThat(aware.maxCellObjective!!).describedAs("the RANGE row binds").isLessThan(blind.maxCellObjective!!)
            assertThat(cert(targetAware = true).cellObjectives).isEqualTo(aware.cellObjectives)
            assertThat(cert(targetAware = false).cellObjectives).isEqualTo(blind.cellObjectives)
            assertThat(MaxDamageCertificateCache.computeCountForTest.get() - computes0).describedAs("repeats are cache hits").isEqualTo(2)

            // The production consumer, on a real hard-leg result (deterministic search; isOptimal stripped so the ledger decides).
            val tuning = WakfuBuildSolver.SolverTuning(numSearchWorkers = 1, randomSeed = 1, maxDeterministicTime = 30.0)
            val best =
                runBlocking { MaxDamageSearch.run(p, pool, emptyList(), tuning).toList() }
                    .maxWithOrNull(compareBy({ it.matchPercentage }, { it.isOptimal }))!!
            assertThat(best.maxDamageHardConstraintsMet).describedAs("a reachable row ⇒ a hard-leg result").isTrue
            val hardLeg = best.copy(isOptimal = false)
            assertThat(MaxDamageSearch.proveOptimality(p, pool, emptyList(), emptyList(), hardLeg, threads = 1))
                .describedAs("a hard-leg result is certified against the targets-met builds")
                .isEqualTo(MaxDamageSearch.MaxDamageProof.ProvenOptimal)
            assertThat(MaxDamageSearch.proveOptimality(p, pool, emptyList(), emptyList(), hardLeg.copy(maxDamageHardConstraintsMet = false), threads = 1))
                .describedAs("without the hard-leg provenance the build is compared with the target-blind ledger")
                .isInstanceOf(MaxDamageSearch.MaxDamageProof.ProvenWithin::class.java)
        } finally {
            MaxDamageCertificateCache.clear()
        }
    }

    private companion object {
        // Picked from a 60-seed survey (41 000–41 059: 309 cells, 29 strictly tightened, 0 under-counts) for coverage and
        // speed (~30 s): MP / RANGE (1–5, Furie II both sides of its threshold) / CC rows, Poids Plume III, Mesure III,
        // Dénouement, Expert des armes légères, runes on and off, levels 110 / 170 / 230.
        val DEFAULT_FUZZ_SEEDS = listOf(41_054L, 41_007L, 41_026L, 41_006L, 41_036L, 41_058L, 41_030L, 41_020L)
    }
}
