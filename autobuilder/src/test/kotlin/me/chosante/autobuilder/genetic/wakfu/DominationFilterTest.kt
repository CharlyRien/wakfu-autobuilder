package me.chosante.autobuilder.genetic.wakfu

import kotlinx.coroutines.runBlocking
import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.autobuilder.domain.DamageScenario
import me.chosante.autobuilder.domain.Orientation
import me.chosante.autobuilder.domain.RangeBand
import me.chosante.autobuilder.domain.SpellElement
import me.chosante.autobuilder.domain.TargetStat
import me.chosante.autobuilder.domain.TargetStats
import me.chosante.autobuilder.genetic.SolverResult
import me.chosante.common.Character
import me.chosante.common.CharacterClass
import me.chosante.common.Characteristic
import me.chosante.common.Equipment
import me.chosante.common.I18nText
import me.chosante.common.ItemType
import me.chosante.common.Rarity
import me.chosante.common.RuneType
import me.chosante.common.Sublimation
import me.chosante.common.skills.CharacterSkills
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/**
 * The domination pre-filter's contract ([dominationShape] / [filterDominatedPool], CERTIFIER_VERSION 53 audit —
 * `docs/perf-review-backlog.md` §E): the relation clause by clause and in both directions (CI), plus three manual
 * harnesses — the pool sizes before / after per slot, the certificate and CP-SAT cost on the same pools, and the
 * production chain over main's pool vs the contract's (was a real badge wrong?). The end-to-end repros live in
 * [DominationSoundnessReproTest], which only uses APIs that predate the fix so it also runs against main.
 */
class DominationFilterTest {
    private fun params(
        level: Int,
        mode: ScoreComputationMode,
        targets: List<TargetStat>,
        runes: Boolean = true,
        subs: Boolean = true,
    ) = WakfuBestBuildParams(
        character = Character(CharacterClass.CRA, level, 0, CharacterSkills(level)),
        targetStats = TargetStats(targets),
        searchDuration = 60.seconds,
        stopWhenBuildMatch = false,
        maxRarity = Rarity.EPIC,
        forcedItems = emptyList(),
        excludedItems = emptyList(),
        scoreComputationMode = mode,
        useRunes = runes,
        useSublimations = subs
    )

    private val guiRows =
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

    private val frontierRows =
        listOf(
            TargetStat(Characteristic.MASTERY_DISTANCE, 9999),
            TargetStat(Characteristic.ACTION_POINT, 16),
            TargetStat(Characteristic.MOVEMENT_POINT, 8),
            TargetStat(Characteristic.CRITICAL_HIT, 100),
            TargetStat(Characteristic.HP, 12000)
        )

    private fun shapesFor(level: Int): List<Pair<String, WakfuBestBuildParams>> {
        val md = ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE
        val mm = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT
        val free = listOf(TargetStat(Characteristic.MASTERY_DISTANCE, 1))
        return listOf(
            "MD-gui" to params(level, md, guiRows),
            "MD-free" to params(level, md, free),
            "MD-free-noSubs" to params(level, md, free, subs = false),
            "MD-free-bare" to params(level, md, free, runes = false, subs = false),
            "MM-gui" to params(level, mm, guiRows),
            "S2" to params(level, mm, frontierRows),
            "S3" to params(level, mm, listOf(TargetStat(Characteristic.MASTERY_DISTANCE, 9999))),
            "S3-noSubs" to params(level, mm, listOf(TargetStat(Characteristic.MASTERY_DISTANCE, 9999)), subs = false)
        )
    }

    private val fire = Characteristic.MASTERY_ELEMENTARY_FIRE
    private val fireDistance = DamageScenario(element = SpellElement.FIRE, rangeBand = RangeBand.DISTANCE, orientation = Orientation.FACE)

    private fun catalogSub(fr: String): Sublimation = WakfuBestBuildFinderAlgorithm.sublimations.single { it.name.fr == fr }

    private fun equipment(
        id: Int,
        type: ItemType,
        stats: Map<Characteristic, Int>,
        rarity: Rarity = Rarity.LEGENDARY,
        sockets: Int = 0,
        level: Int = 200,
        name: String = "e$id",
    ) = Equipment(id, id, level, I18nText(name, name, name, name), rarity, type, stats, sockets)

    /** A one-slot request of [mode] at level 230 (fire / distance / face in max-damage). */
    private fun request(
        mode: ScoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE,
        runes: Boolean = false,
        subs: Boolean = true,
        forcedSubs: List<String> = emptyList(),
        scenario: DamageScenario = fireDistance,
        rows: List<TargetStat> = listOf(TargetStat(Characteristic.MASTERY_DISTANCE, 1)),
    ) = params(230, mode, rows, runes, subs).copy(forcedSublimations = forcedSubs, damageScenario = scenario)

    /** The ids [shape] keeps among [items] (one slot). */
    private fun kept(
        shape: DominationShape,
        vararg items: Equipment,
    ): Set<Int> {
        val slot = items.first().itemType
        return filterDominatedPool(mapOf(slot to items.toList()), shape).getValue(slot).mapTo(HashSet()) { it.equipmentId }
    }

    @Test
    fun `an epic item is only dominated by another epic item while an epic sub is modelled`() {
        val shape = requireNotNull(dominationShape(request(), listOf(catalogSub("Mesure III"))))
        assertThat(shape.epicCarriers).isTrue()
        assertThat(shape.relicCarriers).isFalse()
        // Max-damage does not compare HP: the legendary belt reads ≥ on every compared stat, yet it cannot carry Mesure III.
        val epicHp = equipment(1, ItemType.BELT, mapOf(Characteristic.HP to 400), rarity = Rarity.EPIC, sockets = 3)
        val legendary = equipment(2, ItemType.BELT, mapOf(fire to 50), sockets = 3)
        assertThat(kept(shape, epicHp, legendary)).describedAs("the only epic carrier stays").containsExactlyInAnyOrder(1, 2)
        // The rarity budget direction is unchanged: an epic never evicts a non-epic.
        val epicBig = equipment(3, ItemType.BELT, mapOf(fire to 100), rarity = Rarity.EPIC, sockets = 3)
        assertThat(kept(shape, epicBig, legendary)).describedAs("an epic never evicts a non-epic").containsExactlyInAnyOrder(2, 3)
        // Another epic still replaces it — carrier for carrier.
        assertThat(kept(shape, epicHp, epicBig)).describedAs("an epic evicts a weaker epic").containsExactly(3)
    }

    @Test
    fun `with no epic sub modelled the one-way rarity guard is unchanged`() {
        val shape = requireNotNull(dominationShape(request(subs = false), emptyList()))
        assertThat(shape.epicCarriers || shape.relicCarriers).isFalse()
        val epicHp = equipment(1, ItemType.BELT, mapOf(Characteristic.HP to 400), rarity = Rarity.EPIC, sockets = 3)
        val legendary = equipment(2, ItemType.BELT, mapOf(fire to 50), sockets = 3)
        assertThat(kept(shape, epicHp, legendary)).describedAs("no epic sub ⇒ a non-epic may evict an epic").containsExactly(2)
        val epicBig = equipment(3, ItemType.BELT, mapOf(fire to 100), rarity = Rarity.EPIC, sockets = 3)
        assertThat(kept(shape, epicBig, legendary)).describedAs("an epic never evicts a non-epic").containsExactlyInAnyOrder(2, 3)
        val relicHp = equipment(4, ItemType.BELT, mapOf(Characteristic.HP to 400), rarity = Rarity.RELIC, sockets = 3)
        assertThat(kept(shape, relicHp, legendary)).describedAs("no relic sub ⇒ a non-relic may evict a relic").containsExactly(2)
    }

    @Test
    fun `a forced epic or relic sub turns its carrier guard on, sublimations off or not`() {
        val relic = requireNotNull(dominationShape(request(subs = false, forcedSubs = listOf("Directives")), WakfuBestBuildFinderAlgorithm.sublimations))
        assertThat(relic.relicCarriers).describedAs("a forced relic sub").isTrue()
        assertThat(relic.epicCarriers).describedAs("sublimations off: no epic sub is modelled").isFalse()
        val relicHp = equipment(1, ItemType.BELT, mapOf(Characteristic.HP to 400), rarity = Rarity.RELIC, sockets = 3)
        val legendary = equipment(2, ItemType.BELT, mapOf(fire to 50), sockets = 3)
        assertThat(kept(relic, relicHp, legendary)).describedAs("the only relic carrier stays").containsExactlyInAnyOrder(1, 2)
        val epic = requireNotNull(dominationShape(request(subs = false, forcedSubs = listOf("Abnégation")), WakfuBestBuildFinderAlgorithm.sublimations))
        assertThat(epic.epicCarriers).describedAs("a forced epic sub").isTrue()
        // The production defaults: every subs-on request models epic subs (none is a relic one), subs off none.
        for (mode in ScoreComputationMode.entries) {
            val on = requireNotNull(dominationShape(request(mode = mode), WakfuBestBuildFinderAlgorithm.sublimations))
            assertThat(on.epicCarriers).describedAs("$mode, subs on").isTrue()
            assertThat(on.relicCarriers).describedAs("$mode, subs on: no choosable relic sub").isFalse()
            val off = requireNotNull(dominationShape(request(mode = mode, subs = false), WakfuBestBuildFinderAlgorithm.sublimations))
            assertThat(off.epicCarriers || off.relicCarriers).describedAs("$mode, subs off").isFalse()
        }
    }

    @Test
    fun `a ring is only evicted by dominators of two different names`() {
        val shape = requireNotNull(dominationShape(request(subs = false), emptyList()))
        // Two rarity variants of ring N (one name: the model never wears both) dominate B — but a build wearing N can only
        // pair it with B.
        val nLegendary = equipment(1, ItemType.RING, mapOf(fire to 500), name = "Anneau N")
        val nMythic = equipment(2, ItemType.RING, mapOf(fire to 450), rarity = Rarity.MYTHIC, name = "Anneau N")
        val b = equipment(3, ItemType.RING, mapOf(fire to 300), name = "Anneau B")
        assertThat(kept(shape, nLegendary, nMythic, b)).describedAs("dominators of one name ⇒ B stays").containsExactlyInAnyOrder(1, 2, 3)
        // A dominator of a second name settles it: whatever B's partner, one of them can replace B.
        val c = equipment(4, ItemType.RING, mapOf(fire to 400), name = "Anneau C")
        assertThat(kept(shape, nLegendary, nMythic, b, c)).describedAs("two names ⇒ B goes").containsExactlyInAnyOrder(1, 2, 4)
        // A one-item slot still needs a single dominator.
        val helmetBig = equipment(5, ItemType.HELMET, mapOf(fire to 500))
        val helmetSmall = equipment(6, ItemType.HELMET, mapOf(fire to 300))
        assertThat(kept(shape, helmetBig, helmetSmall)).containsExactly(5)
    }

    @Test
    fun `a rune carrier is only evicted by an item whose level caps its runes at least as high`() {
        val runesOn = requireNotNull(dominationShape(request(runes = true, subs = false), emptyList()))
        assertThat(runesOn.runes).isEqualTo(RuneDomination(exact = false, oneTypePerItem = true))
        // Level 200 caps the runes at level 10, level 220 at level 11 (4 × 33 distance vs 4 × 30).
        val low = equipment(1, ItemType.HELMET, mapOf(fire to 105), sockets = 4, level = 200)
        val high = equipment(2, ItemType.HELMET, mapOf(fire to 100), sockets = 4, level = 220)
        assertThat(kept(runesOn, low, high)).describedAs("the better rune carrier stays").containsExactlyInAnyOrder(1, 2)
        val socketless = equipment(3, ItemType.HELMET, mapOf(fire to 100), level = 220)
        assertThat(kept(runesOn, low, socketless)).describedAs("no socket, no rune to lose").containsExactly(1)
        val runesOff = requireNotNull(dominationShape(request(runes = false, subs = false), emptyList()))
        assertThat(runesOff.runes).isNull()
        assertThat(kept(runesOff, low, high)).describedAs("runes off ⇒ the stats decide").containsExactly(1)
        // The max-damage rune collapse picks a carrier's rune TYPE by value; at rune level 1 the elemental and the secondary
        // runes tie, so a level-1 carrier is only replaced by another one there. The most-masteries count model has no such
        // type switch.
        val level30 = equipment(4, ItemType.HELMET, mapOf(fire to 100), sockets = 4, level = 30)
        val level40 = equipment(5, ItemType.HELMET, mapOf(fire to 105), sockets = 4, level = 40)
        assertThat(kept(runesOn, level30, level40)).describedAs("max-damage: rune level 1 vs 2").containsExactlyInAnyOrder(4, 5)
        val mmRunes =
            requireNotNull(
                dominationShape(
                    request(
                        mode = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT,
                        runes = true,
                        subs = false,
                        rows = listOf(TargetStat(Characteristic.MASTERY_DISTANCE, 9999))
                    ),
                    emptyList()
                )
            )
        assertThat(mmRunes.runes).isEqualTo(RuneDomination(exact = false, oneTypePerItem = false))
        assertThat(kept(mmRunes, level30, level40)).describedAs("most-masteries: the higher cap suffices").containsExactly(5)
    }

    @Test
    fun `a rune on a capped stat must be replicated exactly`() {
        // Neutralité III pins the secondary masteries, and the distance rune is modelled: a rune carrier's contribution to
        // the capped sum must be replicated exactly — the same rune level, and the same sockets under max-damage's
        // one-type-per-item model (extra sockets would carry more of a capped type).
        val subs = listOf(catalogSub("Neutralité III"))
        val exact = requireNotNull(dominationShape(request(runes = true), subs))
        assertThat(exact.runes).isEqualTo(RuneDomination(exact = true, oneTypePerItem = true))
        val level200 = equipment(1, ItemType.HELMET, mapOf(fire to 100), sockets = 4, level = 200)
        val level230 = equipment(2, ItemType.HELMET, mapOf(fire to 105), sockets = 4, level = 230)
        assertThat(kept(exact, level200, level230)).describedAs("a higher rune level is not exact").containsExactlyInAnyOrder(1, 2)
        val threeSockets = equipment(3, ItemType.HELMET, mapOf(fire to 100), sockets = 3, level = 230)
        assertThat(kept(exact, threeSockets, level230)).describedAs("max-damage: more sockets is not exact").containsExactlyInAnyOrder(2, 3)
        val twin = equipment(4, ItemType.HELMET, mapOf(fire to 101), sockets = 4, level = 225)
        assertThat(kept(exact, twin, level230)).describedAs("same rune level and sockets ⇒ the stats decide").containsExactly(2)
        // Most-masteries counts runes per stat (free mix, extra sockets take a safe filler): sockets may grow, not the level.
        val mm =
            requireNotNull(
                dominationShape(
                    request(
                        mode = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT,
                        runes = true,
                        rows = listOf(TargetStat(Characteristic.MASTERY_DISTANCE, 9999))
                    ),
                    subs
                )
            )
        assertThat(mm.runes).isEqualTo(RuneDomination(exact = true, oneTypePerItem = false))
        assertThat(kept(mm, threeSockets, level230)).describedAs("most-masteries: more sockets is fine").containsExactly(2)
        assertThat(kept(mm, level200, level230)).describedAs("most-masteries: a higher rune level is not exact").containsExactlyInAnyOrder(1, 2)
        // No cap reads a modelled rune type (sublimations off): the rune level only has to be at least as high.
        val free = requireNotNull(dominationShape(request(runes = true, subs = false), subs))
        assertThat(kept(free, level200, level230)).describedAs("uncapped ⇒ a higher rune level dominates").containsExactly(2)
    }

    @Test
    fun `a best-element concentration sub is honoured wherever it is modelled`() {
        val ec = catalogSub("Concentration Elémentaire")
        val offElements = ELEMENT_MASTERY_CHARACTERISTICS.toSet() - fire
        // Choosable or forced in a single-element max-damage solve: the off-scenario elemental masteries are minimized.
        val choosable = requireNotNull(dominationShape(request(), listOf(ec)))
        assertThat(choosable.minimized).isEqualTo(offElements)
        val forced = requireNotNull(dominationShape(request(subs = false, forcedSubs = listOf(ec.name.fr)), listOf(ec)))
        assertThat(forced.minimized).describedAs("forced, sublimations off").isEqualTo(offElements)
        // Forced where no single scenario element exists to protect: domination is gated off.
        assertThat(dominationShape(request(mode = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT, forcedSubs = listOf(ec.name.fr)), listOf(ec)))
            .describedAs("forced in most-masteries")
            .isNull()
        val boss = fireDistance.copy(elementResistances = mapOf(SpellElement.FIRE to 0, SpellElement.WATER to 0))
        assertThat(dominationShape(request(subs = false, forcedSubs = listOf(ec.name.fr), scenario = boss), listOf(ec)))
            .describedAs("forced in a multi-element max-damage solve")
            .isNull()
        // Not modelled at all (choosable only in a single-element max-damage solve): no effect in most-masteries.
        assertThat(dominationShape(request(mode = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT), listOf(ec))?.minimized)
            .isEqualTo(emptySet<Characteristic>())
    }

    @Test
    fun `a multi-element max-damage shape compares every candidate element's mastery`() {
        val boss = fireDistance.copy(elementResistances = mapOf(SpellElement.FIRE to 0, SpellElement.WATER to 20))
        val compared = requireNotNull(requireNotNull(dominationShape(request(subs = false, scenario = boss), emptyList())).compared)
        assertThat(compared).contains(fire, Characteristic.MASTERY_ELEMENTARY_WATER)
        val waterOnly = equipment(1, ItemType.AMULET, mapOf(Characteristic.MASTERY_ELEMENTARY_WATER to 500))
        val fireOnly = equipment(2, ItemType.AMULET, mapOf(fire to 500))
        assertThat(kept(requireNotNull(dominationShape(request(subs = false, scenario = boss), emptyList())), waterOnly, fireOnly)).containsExactlyInAnyOrder(1, 2)
    }

    @Test
    fun `every catalog rune carries a valued characteristic`() {
        val catalog = WakfuBestBuildFinderAlgorithm.runes
        assertThat(catalog).isNotEmpty()
        assertThat(RuneType.VALUED_CHARACTERISTICS).containsAll(catalog.map { it.characteristic })
        for (characteristic in RuneType.VALUED_CHARACTERISTICS) {
            val rune = catalog.first().copy(characteristic = characteristic)
            for (level in listOf(1, 36, 215, 216, 245)) assertThat(rune.valueOn(ItemType.HELMET, level)).isPositive()
        }
        assertThat((1..245).map(RuneType::maxLevelForItemLevel)).isSorted()
        assertThat(RuneType.maxLevelForItemLevel(215)).isEqualTo(10)
        assertThat(RuneType.maxLevelForItemLevel(216)).isEqualTo(11)
    }

    // ------------------------------------------------------------------------------------------------------------
    // The pre-fix relation, verbatim (main @ 379830da) — the "before" side of the pool-size measurement.
    // ------------------------------------------------------------------------------------------------------------
    private fun legacyFilter(
        pool: Map<ItemType, List<Equipment>>,
        shape: DominationShape,
    ): Map<ItemType, List<Equipment>> {
        fun Equipment.legacyDominates(other: Equipment): Boolean {
            if (maxShardSlots < other.maxShardSlots) return false
            if (rarity == Rarity.EPIC && other.rarity != Rarity.EPIC) return false
            if (rarity == Rarity.RELIC && other.rarity != Rarity.RELIC) return false
            val chars = shape.compared ?: (characteristics.keys + other.characteristics.keys)
            return chars.all { c ->
                val mine = characteristics.getOrDefault(c, 0)
                val theirs = other.characteristics.getOrDefault(c, 0)
                when {
                    c in shape.pinned -> mine == theirs
                    c in shape.minimized -> mine <= theirs
                    else -> mine >= theirs
                }
            }
        }
        return pool.mapValues { (slot, items) ->
            val k = if (slot == ItemType.RING) 2 else 1
            items.filter { b ->
                items.count { a -> a !== b && a.legacyDominates(b) && (!b.legacyDominates(a) || a.equipmentId < b.equipmentId) } < k
            }
        }
    }

    /**
     * Pool sizes before → after the CERTIFIER_VERSION 53 contract, per slot, at levels 110 / 200 / 245, with the clause
     * decomposition: `legacy` (main), `+carriers+rings` (rune clause off), `+R2` (rune level cap ≥, no exactness),
     * `after` (the full contract). Manual: `WAKFU_DOMINATION_POOL_SIZES=1` (levels via `WAKFU_DOMINATION_LEVELS`).
     */
    @Test
    @Tag("manual")
    fun `manual pool sizes before and after the domination contract`() {
        assumeTrue(System.getenv("WAKFU_DOMINATION_POOL_SIZES") == "1")
        val levels = System.getenv("WAKFU_DOMINATION_LEVELS")?.split(',')?.mapNotNull { it.trim().toIntOrNull() } ?: listOf(110, 200, 245)
        for (level in levels) {
            for ((label, p) in shapesFor(level)) {
                val pool = WakfuBestBuildFinderAlgorithm.poolFor(p)
                val subs = WakfuBestBuildFinderAlgorithm.activeSublimations(p)
                val shape = dominationShape(p, subs)
                if (shape == null) {
                    println("DOMPOOL level=$level shape=$label gatedOff")
                    continue
                }
                val t0 = System.nanoTime()
                val after = filterDominatedPool(pool, shape)
                val afterMs = (System.nanoTime() - t0) / 1_000_000
                val legacy = legacyFilter(pool, shape)
                val noRunes = filterDominatedPool(pool, shape.copy(runes = null))
                val r2 = filterDominatedPool(pool, shape.copy(runes = shape.runes?.copy(exact = false)))

                fun total(m: Map<ItemType, List<Equipment>>) = m.values.sumOf { it.size }
                println(
                    "DOMPOOL level=$level shape=$label base=${total(pool)} legacy=${total(legacy)} carriersRings=${total(noRunes)} " +
                        "r2=${total(r2)} after=${total(after)} filterMs=$afterMs epicCarriers=${shape.epicCarriers} relicCarriers=${shape.relicCarriers} " +
                        "runes=${shape.runes}"
                )
                for (slot in pool.keys.sortedBy { it.name }) {
                    if (System.getenv("WAKFU_DOMINATION_SLOTS") != "1") break
                    println(
                        "DOMPOOL_SLOT level=$level shape=$label slot=$slot base=${pool[slot]?.size} legacy=${legacy[slot]?.size} " +
                            "carriersRings=${noRunes[slot]?.size} r2=${r2[slot]?.size} after=${after[slot]?.size} " +
                            "epicKeptLegacy=${legacy[slot].orEmpty().count { it.rarity == Rarity.EPIC }}/${pool[slot].orEmpty().count { it.rarity == Rarity.EPIC }} " +
                            "epicKeptAfter=${after[slot].orEmpty().count { it.rarity == Rarity.EPIC }} " +
                            "relicKeptLegacy=${legacy[slot].orEmpty().count { it.rarity == Rarity.RELIC }}/${pool[slot].orEmpty().count { it.rarity == Rarity.RELIC }} " +
                            "relicKeptAfter=${after[slot].orEmpty().count { it.rarity == Rarity.RELIC }}"
                    )
                }
            }
        }
    }

    /**
     * Was a real request's badge wrong before the contract? The production chain (wall-clock multi-worker search, then the
     * proof — and for a free max-damage request the E8 construct of the certificate's optimum) over main's `legacy` pool and
     * over the contract's `after` pool (the production filter on the legacy pool is the identity: it is a stricter relation).
     * The after pool keeps the optimum of the full pool, so a strictly better after-pool build means main's pool had lost
     * it. Prints the readmitted items the after-pool build wears. Manual: `WAKFU_DOMINATION_IMPACT=1`,
     * `WAKFU_DOMINATION_IMPACT_SHAPES=MD-gui@200,MD-gui@245,MD-free@245,S2@245,S3@245`, `WAKFU_DOMINATION_IMPACT_SECONDS` (180).
     */
    @Test
    @Tag("manual")
    fun `manual impact of the domination contract on production shapes`() {
        assumeTrue(System.getenv("WAKFU_DOMINATION_IMPACT") == "1")
        val seconds = System.getenv("WAKFU_DOMINATION_IMPACT_SECONDS")?.toLongOrNull() ?: 180L
        val requested =
            System.getenv("WAKFU_DOMINATION_IMPACT_SHAPES")?.split(',')?.map { it.trim() }
                ?: listOf("MD-gui@200", "MD-gui@245", "MD-free@245", "S2@245", "S3@245")
        val runes = WakfuBestBuildFinderAlgorithm.runes
        for (spec in requested) {
            val (shapeName, levelText) = spec.split('@')
            val level = levelText.toInt()
            val p = shapesFor(level).single { it.first == shapeName }.second.copy(searchDuration = seconds.seconds)
            val base = WakfuBestBuildFinderAlgorithm.poolFor(p)
            val subs = WakfuBestBuildFinderAlgorithm.activeSublimations(p)
            val shape = requireNotNull(dominationShape(p, subs)) { "$spec: domination gated off" }
            val legacy = legacyFilter(base, shape)
            val after = filterDominatedPool(base, shape)
            val legacyIds = legacy.values.flatten().mapTo(HashSet()) { it.equipmentId }
            val readmittedIds = after.values.flatten().mapTo(HashSet()) { it.equipmentId } - legacyIds
            println("DOMIMPACT spec=$spec readmitted=${readmittedIds.size} legacyPool=${legacyIds.size} afterPool=${after.values.sumOf { it.size }}")

            fun wearsReadmitted(build: BuildCombination?) = build?.equipments?.filter { it.equipmentId in readmittedIds }?.map { "${it.name.fr}/${it.rarity}/L${it.level}" }

            for ((variant, pool) in listOf("legacy" to legacy, "after" to after)) {
                MaxDamageCertificateCache.clear()
                MostMasteriesBoundCache.clearForTest()
                val t0 = System.nanoTime()
                var last: SolverResult<BuildCombination>? = null
                val isMd = p.scoreComputationMode == ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE
                runBlocking {
                    val flow =
                        if (isMd) {
                            MaxDamageSearch.run(p, pool, runes, subs)
                        } else {
                            WakfuBestBuildFinderAlgorithm.mostMasteriesHardThenSoft(p, pool, runes, subs)
                        }
                    flow.collect { last = it }
                }
                val r = requireNotNull(last) { "$spec/$variant: no emission" }
                val searchMs = (System.nanoTime() - t0) / 1_000_000
                var line =
                    "DOMIMPACT spec=$spec variant=$variant searchMs=$searchMs optimal=${r.isOptimal} mdProxy=${r.maxDamageRawProxy} " +
                        "mmObj=${r.mostMasteriesObjective} hardMet=${r.maxDamageHardConstraintsMet || r.mostMasteriesHardConstraintsMet} " +
                        "subs=${r.individual.sublimations.values.flatten().map { it.name.fr }} wearsReadmitted=${wearsReadmitted(r.individual)}"
                if (isMd) {
                    val p0 = System.nanoTime()
                    val proof = MaxDamageSearch.proveOptimality(p, pool, runes, subs, r)
                    line += " proof=$proof proofMs=${(System.nanoTime() - p0) / 1_000_000}"
                    if (proof is MaxDamageSearch.MaxDamageProof.ProvenWithin && isFreeMaxDamageShape(p.targetStats)) {
                        val c0 = System.nanoTime()
                        val incumbent = r.maxDamageRawProxy ?: r.maxDamageObjective
                        val constructed = runBlocking { WakfuBuildSolver.dpConstructProvenOptimum(p, pool, runes, subs, incumbentObjective = incumbent) }
                        line +=
                            " construct=${constructed?.maxDamageRawProxy} constructOptimal=${constructed?.isOptimal} " +
                            "constructMs=${(System.nanoTime() - c0) / 1_000_000} constructWearsReadmitted=${wearsReadmitted(constructed?.individual)}"
                    }
                } else if (!r.isOptimal) {
                    MostMasteriesBoundCache.clearForTest()
                    val bound = MostMasteriesCertificate.bound(p, pool, runes, subs)
                    line += " mmBound=${bound?.foldedBound} mmHardBound=${bound?.hardFoldedBound}"
                }
                println(line)
            }
        }
    }

    /**
     * Cost of the contract on the deterministic protocol: the same request solved over the `legacy` pool (main's filter),
     * the `r2` pool (the contract without rune exactness) and the `after` pool (the full contract) — CP-SAT 1 worker +
     * interleaved search + fixed seed, a deterministic-time cap — and the certificate over each pool (the max-damage fast
     * ledger with a huge incumbent, or the most-masteries bound). Manual: `WAKFU_DOMINATION_AB=1`,
     * `WAKFU_DOMINATION_AB_SHAPES=MD-free@110,S2@245` (shape@level, shapes of [shapesFor]), `WAKFU_DOMINATION_AB_DET`
     * (CP-SAT deterministic cap, 300), `WAKFU_DOMINATION_AB_PARTS` (cpsat,cert), `WAKFU_DOMINATION_AB_VARIANTS`
     * (legacy,r2,after), `WAKFU_DOMINATION_AB_THREADS` (certificate threads, 1).
     */
    @Test
    @Tag("manual")
    fun `manual cost of the domination contract`() {
        assumeTrue(System.getenv("WAKFU_DOMINATION_AB") == "1")
        val det = System.getenv("WAKFU_DOMINATION_AB_DET")?.toDoubleOrNull() ?: 300.0
        val parts =
            System
                .getenv("WAKFU_DOMINATION_AB_PARTS")
                ?.split(',')
                ?.map { it.trim() }
                ?.toSet() ?: setOf("cpsat", "cert")
        val variants = System.getenv("WAKFU_DOMINATION_AB_VARIANTS")?.split(',')?.map { it.trim() } ?: listOf("legacy", "r2", "after")
        val threads = System.getenv("WAKFU_DOMINATION_AB_THREADS")?.toIntOrNull() ?: 1
        val requested = System.getenv("WAKFU_DOMINATION_AB_SHAPES")?.split(',')?.map { it.trim() } ?: listOf("MD-free@110")
        val runes = WakfuBestBuildFinderAlgorithm.runes
        for (spec in requested) {
            val (shapeName, levelText) = spec.split('@')
            val level = levelText.toInt()
            val p = shapesFor(level).single { it.first == shapeName }.second
            val base = WakfuBestBuildFinderAlgorithm.poolFor(p)
            val subs = WakfuBestBuildFinderAlgorithm.activeSublimations(p)
            val shape = requireNotNull(dominationShape(p, subs)) { "$spec: domination gated off" }
            val pools =
                mapOf(
                    "legacy" to { legacyFilter(base, shape) },
                    "r2" to { filterDominatedPool(base, shape.copy(runes = shape.runes?.copy(exact = false))) },
                    "after" to { filterDominatedPool(base, shape) }
                )
            for (variant in variants) {
                val pool = pools.getValue(variant)()
                val size = pool.values.sumOf { it.size }
                if ("cpsat" in parts) {
                    val r =
                        WakfuBuildSolver.timedMaxDamageProfileForTest(
                            p,
                            pool,
                            runes,
                            subs,
                            workers = 1,
                            seconds = det * 4,
                            applyDomination = false,
                            deterministicLimit = det,
                            interleave = true
                        )
                    println(
                        "DOMAB cpsat spec=$spec variant=$variant pool=$size status=${r.status} det=${"%.1f".format(r.deterministicTime)} " +
                            "wall=${"%.1f".format(r.wallTimeSec)} objective=${r.objective} bound=${r.bestBound} raw=${r.rawObjective} vars=${r.variables}"
                    )
                }
                if ("cert" in parts) {
                    val t0 = System.nanoTime()
                    val summary =
                        if (p.scoreComputationMode == ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE) {
                            val ledger =
                                WakfuBuildSolver.certifyLedgerForTest(
                                    p,
                                    pool,
                                    runes,
                                    subs,
                                    applyDomination = false,
                                    incumbentObjective = Long.MAX_VALUE / 2,
                                    threads = threads
                                )
                            "fastMax=${ledger.maxCellObjective} bailed=${ledger.bailedCells.size}"
                        } else {
                            val bound = MostMasteriesCertificate.bound(p, pool, runes, subs, parallelism = { threads })
                            "folded=${bound?.foldedBound} hard=${bound?.hardFoldedBound}"
                        }
                    println("DOMAB cert spec=$spec variant=$variant pool=$size ms=${(System.nanoTime() - t0) / 1_000_000} threads=$threads $summary")
                }
            }
        }
    }
}
