package me.chosante.autobuilder.genetic.wakfu

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
import me.chosante.common.SECONDARY_MASTERY_CHARACTERISTICS
import me.chosante.common.Sublimation
import me.chosante.common.SublimationEffect
import me.chosante.common.SublimationRarity
import me.chosante.common.skills.CharacterSkills
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/**
 * The max-damage rune CHOICE COLLAPSE keeps, per carrier, the Pareto set of its candidate runes over everything the
 * model reads from them ([MaxDamageRuneReads]), and gates the choices only a choosable conditional sub keeps
 * ([MaxDamageRuneReads.choiceGates]). Both are optimum-preserving search cuts: the rule locks below pin what each carrier
 * keeps on the real rune catalog, and the model lock checks the pruned + gated model against the full-choice one (every
 * candidate rune on every carrier, no gate) and the general single-type fold on seeded pools.
 */
class RuneChoicePruningTest {
    private val runes = WakfuBestBuildFinderAlgorithm.runes
    private val catalog = WakfuBestBuildFinderAlgorithm.sublimations

    private fun sub(fr: String) = catalog.single { it.name.fr == fr }

    private val secondaryCaps = listOf("Neutralité III", "Prétention III", "Ambition III", "Inflexibilité II").map(::sub)
    private val critSecret = sub("Secret critique")
    private val unraveling = sub("Dénouement")

    private val distanceRear = DamageScenario(element = SpellElement.FIRE, rangeBand = RangeBand.DISTANCE, orientation = Orientation.BACK)

    private fun params(
        scenario: DamageScenario,
        level: Int = 230,
        forcedSubs: List<String> = emptyList(),
    ) = WakfuBestBuildParams(
        character = Character(CharacterClass.CRA, level, 0, CharacterSkills(level)),
        targetStats = TargetStats(listOf(TargetStat(scenario.rangeBand.masteryCharacteristic, 1))),
        searchDuration = 60.seconds,
        stopWhenBuildMatch = false,
        maxRarity = Rarity.EPIC,
        forcedItems = emptyList(),
        excludedItems = emptyList(),
        scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE,
        damageScenario = scenario,
        useRunes = true,
        useSublimations = true,
        forcedSublimations = forcedSubs
    )

    /** The collapse's candidates on a carrier, in [createRuneModel]'s order (scenario masteries, then critical mastery). */
    private fun candidates(
        scenario: DamageScenario,
        type: ItemType,
        itemLevel: Int = 230,
    ): List<RuneChoice> {
        val stats =
            buildList {
                add(Characteristic.MASTERY_ELEMENTARY)
                add(scenario.rangeBand.masteryCharacteristic)
                if (scenario.orientation.grantsRearMastery) add(Characteristic.MASTERY_BACK)
                if (scenario.berserk) add(Characteristic.MASTERY_BERSERK)
                if (scenario.healing) add(Characteristic.MASTERY_HEALING)
                add(Characteristic.MASTERY_CRITICAL)
            }
        return stats.map { stat ->
            val rune = runes.single { it.characteristic == stat }
            RuneChoice(stat, rune, rune.valueOn(type, itemLevel).toLong())
        }
    }

    private fun kept(
        reads: MaxDamageRuneReads,
        scenario: DamageScenario,
        type: ItemType,
    ) = reads.paretoChoices(candidates(scenario, type)).map { it.stat }

    private val elem = Characteristic.MASTERY_ELEMENTARY
    private val dist = Characteristic.MASTERY_DISTANCE
    private val back = Characteristic.MASTERY_BACK
    private val crit = Characteristic.MASTERY_CRITICAL

    @Test
    fun `a secondary cap keeps every cheaper rune a budget can need and drops the exact ties`() {
        // Rear distance, rune level 11: secondary 33 (66 doubled), elemental 22 (44 doubled). The Neutralité family sums
        // ALL six secondaries (crit included) at weight 1, so equal-valued distance / rear / crit runes cost the same
        // budget and distance — first in order, and worth more than crit at every crit rate — represents them.
        val reads = maxDamageRuneReads(params(distanceRear), secondaryCaps + critSecret + unraveling, emptySet())
        val expected =
            mapOf(
                ItemType.HELMET to listOf(elem, dist), // 22 / 33 / 33 / 33
                ItemType.CHEST_PLATE to listOf(elem), // 44 doubled beats every 33 on every read
                ItemType.SHOULDER_PADS to listOf(elem, dist, crit), // crit 66 doubled
                ItemType.BOOTS to listOf(elem, dist, back), // rear 66 doubled: distance 33 is the half-budget option
                ItemType.AMULET to listOf(elem, dist),
                ItemType.CAPE to listOf(elem),
                ItemType.BELT to listOf(elem, dist, crit), // distance = rear = 66, crit 33 is the half-budget option
                ItemType.ONE_HANDED_WEAPONS to listOf(elem, dist, back), // distance = crit = 66, rear 33
                ItemType.RING to listOf(elem, dist)
            )
        for ((type, stats) in expected) {
            assertThat(kept(reads, distanceRear, type)).describedAs("%s", type).isEqualTo(stats)
        }
        // While no secondary cap is taken, the best M rune beats the cheaper choices: they are gated on exactly the four
        // caps (Critical Secret prefers the distance rune anyway; Unraveling's crit-into-elemental re-label never helps).
        val helmetGates = reads.choiceGates(reads.paretoChoices(candidates(distanceRear, ItemType.HELMET)))
        assertThat(helmetGates.keys).containsExactly(elem)
        assertThat(helmetGates.getValue(elem)).containsExactlyInAnyOrderElementsOf(secondaryCaps)
        val beltGates = reads.choiceGates(reads.paretoChoices(candidates(distanceRear, ItemType.BELT)))
        assertThat(beltGates.keys).containsExactlyInAnyOrder(elem, crit)
        // The doubled crit rune (66 > distance 33) is the original collapse's crit swap: never gated.
        val shoulderGates = reads.choiceGates(reads.paretoChoices(candidates(distanceRear, ItemType.SHOULDER_PADS)))
        assertThat(shoulderGates.keys).containsExactly(elem)
    }

    @Test
    fun `without a secondary cap the pruning is the original collapse`() {
        // Best M-feeding rune (ties: first in order), plus crit only when larger — main's collapse, whatever else is read.
        for (subs in listOf(emptyList(), listOf(critSecret, unraveling))) {
            val reads = maxDamageRuneReads(params(distanceRear), subs, emptySet())
            val expected =
                mapOf(
                    ItemType.HELMET to listOf(dist),
                    ItemType.CHEST_PLATE to listOf(elem),
                    ItemType.SHOULDER_PADS to listOf(dist, crit),
                    ItemType.BOOTS to listOf(back),
                    ItemType.BELT to listOf(dist),
                    ItemType.ONE_HANDED_WEAPONS to listOf(dist),
                    ItemType.RING to listOf(dist)
                )
            for ((type, stats) in expected) {
                assertThat(kept(reads, distanceRear, type)).describedAs("%s with %s", type, subs.map { it.name.fr }).isEqualTo(stats)
            }
        }
    }

    @Test
    fun `a forced cap and an unknown reader are never pruned across`() {
        // A FORCED sub's effect is gated by its condition and may be a malus, so its stats must match exactly: the
        // doubled elemental rune no longer beats the distance one (it frees budget the build may want to spend).
        val forced = maxDamageRuneReads(params(distanceRear, forcedSubs = listOf("Neutralité III")), listOf(secondaryCaps[0]), emptySet())
        assertThat(kept(forced, distanceRear, ItemType.CHEST_PLATE)).isEqualTo(listOf(elem, dist))
        assertThat(forced.choiceGates(forced.paretoChoices(candidates(distanceRear, ItemType.HELMET)))).isEmpty()

        // A conversion out of distance mastery (or crit mastery converted at > 100 %) is an unknown reader: that type is
        // neither pruned nor pruning.
        val fromDistance = unraveling.copy(stateId = -1, effects = listOf(SublimationEffect.Conversion(from = dist, to = Characteristic.DAMAGE_INFLICTED, percent = 50)))
        val opaqueDistance = maxDamageRuneReads(params(distanceRear), secondaryCaps + fromDistance, emptySet())
        assertThat(kept(opaqueDistance, distanceRear, ItemType.HELMET)).isEqualTo(listOf(elem, dist, back))
        val overConverting = unraveling.copy(stateId = -2, effects = listOf(SublimationEffect.Conversion(from = crit, to = elem, percent = 150)))
        val opaqueCrit = maxDamageRuneReads(params(distanceRear), listOf(overConverting), emptySet())
        assertThat(kept(opaqueCrit, distanceRear, ItemType.HELMET)).isEqualTo(listOf(dist, crit))
        // A %-skill on a candidate type changes its objective weight: never pruned across either.
        val percent = maxDamageRuneReads(params(distanceRear), emptyList(), setOf(back))
        assertThat(kept(percent, distanceRear, ItemType.HELMET)).isEqualTo(listOf(dist, back))
    }

    // ---- Model lock -----------------------------------------------------------------------------------------------

    private class Case(
        val label: String,
        val params: WakfuBestBuildParams,
        val pool: Map<ItemType, List<Equipment>>,
        val subs: List<Sublimation>,
    )

    /**
     * Small collapse-shaped pools: socketed items over every rune-level cap (ties at rune level 1 included), signed
     * secondary / crit lines (a negative line is the budget a cap can spend), the Neutralité family choosable or one of it
     * forced, Critical Secret / Unraveling / a random extra sub, every orientation and range band.
     */
    private fun case(seed: Long): Case {
        val rng = java.util.Random(seed * 7_907L + 11L)
        val level = listOf(60, 120, 200, 230)[rng.nextInt(4)]
        val element = SpellElement.FIRE
        val range = if (rng.nextBoolean()) RangeBand.DISTANCE else RangeBand.MELEE
        val orientation = if (rng.nextBoolean()) Orientation.BACK else Orientation.FACE
        val berserk = rng.nextInt(4) == 0
        val scenario = DamageScenario(element = element, rangeBand = range, orientation = orientation, berserk = berserk)
        val others = SECONDARY_MASTERY_CHARACTERISTICS.filter { it !in scenarioMasteryStats(scenario) && it != crit }
        var id = 700_000 + (seed % 1_000).toInt() * 100

        fun item(
            type: ItemType,
            rarity: Rarity = Rarity.LEGENDARY,
        ): Equipment {
            val stats = mutableMapOf<Characteristic, Int>()
            stats[if (rng.nextInt(3) == 0) elem else element.masteryCharacteristic] = 40 + rng.nextInt(400)
            if (rng.nextInt(10) < 4) stats[range.masteryCharacteristic] = rng.nextInt(260) - 140
            if (orientation.grantsRearMastery && rng.nextInt(3) == 0) stats[back] = rng.nextInt(200) - 120
            if (berserk && rng.nextInt(4) == 0) stats[Characteristic.MASTERY_BERSERK] = rng.nextInt(200) - 100
            if (rng.nextInt(10) < 3) stats[others[rng.nextInt(others.size)]] = rng.nextInt(200) - 140
            if (rng.nextInt(10) < 3) stats[crit] = rng.nextInt(160) - 60
            if (rng.nextInt(10) < 4) stats[Characteristic.CRITICAL_HIT] = rng.nextInt(15) - 4
            if (rng.nextInt(10) < 2) stats[Characteristic.ACTION_POINT] = 1
            if (rng.nextInt(8) == 0) stats[Characteristic.DAMAGE_INFLICTED] = 5 + rng.nextInt(10)
            id++
            val itemLevel = if (rng.nextInt(5) == 0) 1 + rng.nextInt(35) else 1 + rng.nextInt(level)
            return Equipment(
                equipmentId = id,
                guiId = id,
                level = itemLevel,
                name = I18nText("rp$id", "rp$id", "", ""),
                rarity = rarity,
                itemType = type,
                characteristics = stats.filterValues { it != 0 },
                maxShardSlots = listOf(0, 2, 3, 4, 4)[rng.nextInt(5)]
            )
        }
        val slots =
            listOf(ItemType.HELMET, ItemType.CHEST_PLATE, ItemType.SHOULDER_PADS, ItemType.BOOTS, ItemType.AMULET, ItemType.CAPE, ItemType.BELT)
                .shuffled(rng)
                .take(3 + rng.nextInt(2))
        val items = mutableListOf<Equipment>()
        for (slot in slots) repeat(2) { items += item(slot) }
        if (rng.nextBoolean()) repeat(2) { items += item(ItemType.RING) }
        if (rng.nextBoolean()) items += item(ItemType.ONE_HANDED_WEAPONS)
        if (rng.nextInt(3) == 0) items += item(slots.first(), Rarity.EPIC)
        // A random non-empty part of the family — without Neutralité III a third of the time, so the gates' sub sets are
        // exercised beyond the strongest cap — one of it forced in a quarter of the cases.
        val family =
            secondaryCaps
                .filter { rng.nextBoolean() }
                .ifEmpty { listOf(secondaryCaps[rng.nextInt(secondaryCaps.size)]) }
                .let { drawn -> if (rng.nextInt(3) == 0) drawn.filter { it != secondaryCaps[0] }.ifEmpty { listOf(secondaryCaps[3]) } else drawn }
        // Forcing the EPIC Inflexibilité II would need an epic carrier: only a NORMAL member is ever forced.
        val forcedCap = family.firstOrNull { it.rarity == SublimationRarity.NORMAL }?.takeIf { rng.nextInt(4) == 0 }
        val extras = listOf(critSecret, unraveling).filter { rng.nextBoolean() }
        val random = catalog.filter { it.solverChoosable && it !in secondaryCaps && it !in extras }.shuffled(rng).take(rng.nextInt(3))
        val p =
            params(scenario, level, forcedSubs = listOfNotNull(forcedCap?.name?.fr))
        return Case(
            "seed$seed(${scenario.rangeBand}/${scenario.orientation}/berserk=$berserk lvl$level forced=${forcedCap?.name?.fr})",
            p,
            items.groupBy { it.itemType },
            family + extras + random
        )
    }

    @Test
    fun `the pruned and gated collapse keeps the full-choice optimum on seeded pools`() {
        // Deterministic protocol: 1 worker, fixed seed, interleaved search. The full-choice model offers every candidate
        // rune on every carrier (no Pareto drop, no gate); the general fold (+ a damage-neutral HP = 0 row) is the
        // independent single-type reference. All three must prove the same optimum.
        val tuning = WakfuBuildSolver.SolverTuning(numSearchWorkers = 1, randomSeed = 1, maxDeterministicTime = 60.0, interleaveSearch = true)
        var familyOptima = 0
        for (seed in 1L..24L) {
            val c = case(seed)
            val pruned = WakfuBuildSolver.maxDamageSolveForTest(c.params, c.pool, tuning, tightDomains = true, runes = runes, sublimations = c.subs)
            val full =
                WakfuBuildSolver.maxDamageSolveForTest(
                    c.params,
                    c.pool,
                    tuning,
                    tightDomains = true,
                    runes = runes,
                    sublimations = c.subs,
                    runeChoicePruning = false,
                    runeChoiceGating = false
                )
            val hp0 = c.params.copy(targetStats = TargetStats(c.params.targetStats.toList() + TargetStat(Characteristic.HP, 0)))
            val general = WakfuBuildSolver.maxDamageSolveForTest(hp0, c.pool, tuning, tightDomains = true, runes = runes, sublimations = c.subs)
            println(
                "RUNE_PRUNING ${c.label} pruned=${pruned.objective}/${pruned.isOptimal} full=${full.objective}/${full.isOptimal} " +
                    "general=${general.objective}/${general.isOptimal} subs=${c.subs.map { it.name.fr }}"
            )
            for ((label, outcome) in listOf("pruned" to pruned, "full-choice" to full, "general fold" to general)) {
                assertThat(outcome.isOptimal).describedAs("%s: the %s model proves its optimum", c.label, label).isTrue()
            }
            assertThat(pruned.objective).describedAs("%s: pruning + gating must keep the full-choice optimum", c.label).isEqualTo(full.objective)
            assertThat(general.objective).describedAs("%s: the general fold agrees", c.label).isEqualTo(full.objective)
            if (c.params.forcedSublimations.isEmpty()) {
                val probe =
                    WakfuBuildSolver.timedMaxDamageProfileForTest(
                        c.params,
                        c.pool,
                        runes,
                        c.subs,
                        workers = 1,
                        seconds = 60.0,
                        applyDomination = false,
                        deterministicLimit = 60.0,
                        interleave = true
                    )
                if (probe.status == "OPTIMAL" && probe.selectedSublimationStateIds.any { id -> secondaryCaps.any { it.stateId == id } }) familyOptima++
            }
        }
        // The pools must exercise the cap: some (choosable) optimum carries a Neutralité-family sub.
        assertThat(familyOptima).describedAs("seeded optima carrying a secondary cap").isGreaterThanOrEqualTo(3)
    }
}
