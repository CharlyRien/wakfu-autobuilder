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
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/**
 * End-to-end soundness locks of the domination pre-filter (CERTIFIER_VERSION 53 audit, `docs/perf-review-backlog.md`
 * §E): on each pool the per-slot filter used to evict an item the optimum NEEDS, so the production search — and the
 * certificate, which reads the same reduced pool — settled below the true optimum while CP-SAT proved OPTIMAL on the
 * reduced pool (a wrong "proven optimal" badge). Each lock compares the domination-ON solve with the full pool, on the
 * deterministic protocol (1 worker, fixed seed, interleaved search). Every test is RED on main @ 379830da.
 */
class DominationSoundnessReproTest {
    private val fire = Characteristic.MASTERY_ELEMENTARY_FIRE
    private val scenario = DamageScenario(element = SpellElement.FIRE, rangeBand = RangeBand.DISTANCE, orientation = Orientation.FACE)
    private val deterministic = WakfuBuildSolver.SolverTuning(numSearchWorkers = 1, randomSeed = 1, interleaveSearch = true, maxDeterministicTime = 30.0)

    private fun item(
        id: Int,
        type: ItemType,
        stats: Map<Characteristic, Int>,
        rarity: Rarity = Rarity.LEGENDARY,
        sockets: Int = 0,
        level: Int = 200,
        name: String = "i$id",
    ) = Equipment(
        equipmentId = id,
        guiId = id,
        level = level,
        name = I18nText(name, name, name, name),
        rarity = rarity,
        itemType = type,
        characteristics = stats,
        maxShardSlots = sockets
    )

    private fun params(
        level: Int,
        rows: List<TargetStat> = listOf(TargetStat(Characteristic.MASTERY_DISTANCE, 1)),
        useRunes: Boolean = false,
        useSublimations: Boolean = true,
    ) = WakfuBestBuildParams(
        character = Character(CharacterClass.CRA, level, 0, CharacterSkills(level)),
        targetStats = TargetStats(rows),
        searchDuration = 60.seconds,
        stopWhenBuildMatch = false,
        maxRarity = Rarity.EPIC,
        forcedItems = emptyList(),
        excludedItems = emptyList(),
        scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE,
        useRunes = useRunes,
        useSublimations = useSublimations,
        damageScenario = scenario
    )

    private fun catalogSub(fr: String): Sublimation = WakfuBestBuildFinderAlgorithm.sublimations.single { it.name.fr == fr }

    /** The production max-damage search (MaxDamageSearch, every phase) — domination ON as in production, or the full pool. */
    private fun search(
        p: WakfuBestBuildParams,
        pool: Map<ItemType, List<Equipment>>,
        runes: List<RuneType>,
        subs: List<Sublimation>,
        domination: Boolean,
    ): SolverResult<BuildCombination> {
        val tuning = deterministic.copy(applyDominationOverride = domination)
        val emissions = mutableListOf<SolverResult<BuildCombination>>()
        runBlocking { MaxDamageSearch.run(p, pool, runes, subs, tuning).collect { emissions += it } }
        return emissions.last()
    }

    /** One deterministic CP-SAT solve of the whole request (no AP enumeration): domination ON vs the full pool. */
    private fun solve(
        p: WakfuBestBuildParams,
        pool: Map<ItemType, List<Equipment>>,
        runes: List<RuneType>,
        subs: List<Sublimation>,
        domination: Boolean,
    ) = WakfuBuildSolver.maxDamageSolveForTest(p, pool, deterministic, tightDomains = true, runes = runes, sublimations = subs, applyDomination = domination)

    /**
     * The PR #222 review repro. Mesure III (EPIC, `crit ≤ 50` ⇒ +20 % DI) can only ride an EPIC item; the pool's only
     * epic is an HP-only belt, which max-damage does not compare, so a legendary belt with 50 fire mastery
     * "dominated" it and the filter evicted the only carrier. Full pool: 1 909 930 (Mesure III on the epic belt);
     * domination ON: 1 636 955 with `isOptimal = true` and a ProvenOptimal badge, 16.7 % below — for the free and the
     * AP-row request alike.
     */
    @Test
    fun `domination keeps the only epic carrier - the production search and its proof reach the full-pool optimum`() {
        val pool =
            listOf(
                item(1, ItemType.BELT, mapOf(Characteristic.HP to 400), rarity = Rarity.EPIC, sockets = 3),
                item(2, ItemType.BELT, mapOf(fire to 50), sockets = 3),
                item(3, ItemType.HELMET, mapOf(fire to 1200), sockets = 3),
                item(4, ItemType.BOOTS, mapOf(fire to 1100), sockets = 3),
                item(5, ItemType.CAPE, mapOf(fire to 1000), sockets = 3)
            ).groupBy { it.itemType }
        val subs = listOf(catalogSub("Mesure III"))
        val failures = mutableListOf<String>()
        for (rows in listOf(
            listOf(TargetStat(Characteristic.MASTERY_DISTANCE, 1)),
            listOf(TargetStat(Characteristic.MASTERY_DISTANCE, 1), TargetStat(Characteristic.ACTION_POINT, 6))
        )) {
            val p = params(200, rows)
            val label = rows.joinToString { "${it.characteristic}=${it.target}" }
            val full = search(p, pool, emptyList(), subs, domination = false)
            val prod = search(p, pool, emptyList(), subs, domination = true)
            val truth = requireNotNull(full.maxDamageRawProxy)
            val proxy = requireNotNull(prod.maxDamageRawProxy)
            assertThat(full.isOptimal).describedAs("$label: the full-pool search proves its optimum").isTrue()
            assertThat(truth).describedAs("$label: the full-pool optimum carries Mesure III on the epic belt").isEqualTo(1_909_930L)
            if (proxy != truth) failures += "$label: domination-ON search $proxy (isOptimal=${prod.isOptimal}) != full-pool optimum $truth"
            if (prod.isOptimal && proxy < truth) failures += "$label: CP-SAT proved OPTIMAL at $proxy < $truth"
            MaxDamageCertificateCache.clear()
            val proof =
                try {
                    MaxDamageSearch.proveOptimality(p, pool, emptyList(), subs, prod.copy(isOptimal = false), threads = 1)
                } finally {
                    MaxDamageCertificateCache.clear()
                }
            when (proof) {
                MaxDamageSearch.MaxDamageProof.ProvenOptimal -> if (proxy < truth) failures += "$label: certificate ProvenOptimal at $proxy < $truth"
                is MaxDamageSearch.MaxDamageProof.ProvenWithin ->
                    if (truth.toDouble() > proxy.toDouble() * (1.0 + proof.fraction) + 1.0) failures += "$label: ProvenWithin ${proof.fraction} under $truth"
                MaxDamageSearch.MaxDamageProof.Unavailable -> {}
            }
            println("DOMREPRO epic-carrier $label truth=$truth proxy=$proxy prodOptimal=${prod.isOptimal} proof=$proof")
        }
        assertThat(failures).describedAs("SOUNDNESS — the only epic carrier").isEmpty()
    }

    /**
     * Rings: the model never wears two rings of the same French name (rarity variants share it). Ring "B" was
     * "dominated" by the legendary AND the mythic variant of ring "N" — two items, so the old `≥ 2 dominators` rule
     * evicted it — yet a build wearing N can only pair it with B: the reduced pool lost the {N, B} optimum.
     */
    @Test
    fun `domination keeps a ring whose dominators all share one name`() {
        val pool =
            listOf(
                item(1, ItemType.RING, mapOf(fire to 500), name = "Anneau N"),
                item(2, ItemType.RING, mapOf(fire to 450), rarity = Rarity.MYTHIC, name = "Anneau N"),
                item(3, ItemType.RING, mapOf(fire to 300), name = "Anneau B"),
                item(4, ItemType.RING, mapOf(fire to 100), name = "Anneau C"),
                item(5, ItemType.HELMET, mapOf(fire to 1000))
            ).groupBy { it.itemType }
        val p = params(200, useSublimations = false)
        val full = solve(p, pool, emptyList(), emptyList(), domination = false)
        val dominated = solve(p, pool, emptyList(), emptyList(), domination = true)
        println("DOMREPRO ring-names full=${full.objective} dominated=${dominated.objective} picked=${dominated.selectedEquipmentIds}")
        assertThat(full.isOptimal && dominated.isOptimal).describedAs("both solves prove OPTIMAL").isTrue()
        assertThat(full.selectedEquipmentIds).describedAs("the optimum wears N with B").contains(1, 3)
        assertThat(dominated.objective).describedAs("domination must keep B, N's only partner").isEqualTo(full.objective)
    }

    /**
     * Runes: a rune's value is capped by its CARRIER's level (rune level 11 needs an item of level ≥ 216). A level-200
     * helmet with 5 more fire mastery "dominated" a level-220 one with the same 4 sockets, but the latter's level-11
     * distance runes (4 × 33) beat the former's level-10 ones (4 × 30) by more than those 5 points.
     */
    @Test
    fun `domination keeps a carrier whose higher item level carries better runes`() {
        val pool =
            listOf(
                item(1, ItemType.HELMET, mapOf(fire to 105), sockets = 4, level = 200),
                item(2, ItemType.HELMET, mapOf(fire to 100), sockets = 4, level = 220),
                item(3, ItemType.BOOTS, mapOf(fire to 1000))
            ).groupBy { it.itemType }
        val p = params(230, useRunes = true, useSublimations = false)
        val runes = WakfuBestBuildFinderAlgorithm.runes
        val full = solve(p, pool, runes, emptyList(), domination = false)
        val dominated = solve(p, pool, runes, emptyList(), domination = true)
        println("DOMREPRO rune-level full=${full.objective} dominated=${dominated.objective} picked=${dominated.selectedEquipmentIds}")
        assertThat(full.isOptimal && dominated.isOptimal).describedAs("both solves prove OPTIMAL").isTrue()
        assertThat(full.selectedEquipmentIds).describedAs("the optimum wears the level-220 helmet").contains(2)
        assertThat(dominated.objective).describedAs("domination must keep the better rune carrier").isEqualTo(full.objective)
    }

    /**
     * Real catalog (levels 110 / 200 / 245; the GUI-default and the free max-damage request, the GUI-default most-masteries
     * one, and a free max-damage request with a FORCED relic sub): every EPIC / RELIC item that no other item of its own
     * rarity can replace — it survives the filter among the slot's items of that rarity — is still in the filtered pool,
     * since it may be the carrier the build's epic / relic sublimation needs. Main evicted some of them for a non-epic item
     * (the stat-less 4-socket epic rings Anneau d'Amakna / Bonta / Brâkmar / Sufokia, Sain Turastil, Bâton Braider …).
     */
    @Test
    fun `real catalog - no epic or relic carrier that only its own rarity could replace is evicted`() {
        val md = ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE
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
        val free = listOf(TargetStat(Characteristic.MASTERY_DISTANCE, 1))
        val failures = mutableListOf<String>()
        var checked = 0
        for (level in listOf(110, 200, 245)) {
            fun request(
                mode: ScoreComputationMode,
                rows: List<TargetStat>,
                forcedSubs: List<String> = emptyList(),
            ) = WakfuBestBuildParams(
                character = Character(CharacterClass.CRA, level, 0, CharacterSkills(level)),
                targetStats = TargetStats(rows),
                searchDuration = 60.seconds,
                stopWhenBuildMatch = false,
                maxRarity = Rarity.EPIC,
                forcedItems = emptyList(),
                excludedItems = emptyList(),
                scoreComputationMode = mode,
                useRunes = true,
                useSublimations = true,
                forcedSublimations = forcedSubs
            )
            val requests =
                listOf(
                    Triple("MD-gui", request(md, gui), setOf(Rarity.EPIC)),
                    Triple("MD-free", request(md, free), setOf(Rarity.EPIC)),
                    Triple("MM-gui", request(ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT, gui), setOf(Rarity.EPIC)),
                    Triple("MD-free+relic", request(md, free, listOf("Directives")), setOf(Rarity.EPIC, Rarity.RELIC))
                )
            for ((label, p, rarities) in requests) {
                val pool = WakfuBestBuildFinderAlgorithm.poolFor(p)
                val subs = WakfuBestBuildFinderAlgorithm.activeSublimations(p)
                val shape = requireNotNull(dominationShape(p, subs)) { "$label@$level: domination gated off" }
                val kept = WakfuBuildSolver.filterDominatedPoolMemoizedForTest(pool, shape)
                for ((slot, items) in pool) {
                    for (rarity in rarities) {
                        val carriers = items.filter { it.rarity == rarity }
                        if (carriers.isEmpty()) continue
                        val irreplaceable = WakfuBuildSolver.filterDominatedPoolMemoizedForTest(mapOf(slot to carriers), shape).getValue(slot)
                        checked += irreplaceable.size
                        val keptInSlot = kept[slot].orEmpty().toSet()
                        irreplaceable.filter { it !in keptInSlot }.forEach {
                            failures += "$label@$level $slot: $rarity ${it.name.fr} (id ${it.equipmentId}, level ${it.level}, ${it.maxShardSlots} sockets) evicted"
                        }
                    }
                }
            }
        }
        println("DOMREPRO real-catalog carriers checked=$checked evicted=${failures.size}")
        failures.forEach { println("DOMREPRO_FAIL $it") }
        assertThat(checked).describedAs("irreplaceable epic / relic carriers checked").isGreaterThan(100)
        assertThat(failures).describedAs("SOUNDNESS — an epic / relic carrier only its own rarity could replace").isEmpty()
    }
}
