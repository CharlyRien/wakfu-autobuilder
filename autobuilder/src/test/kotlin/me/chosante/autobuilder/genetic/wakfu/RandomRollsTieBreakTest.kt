package me.chosante.autobuilder.genetic.wakfu

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.autobuilder.domain.TargetStat
import me.chosante.autobuilder.domain.TargetStats
import me.chosante.common.Character
import me.chosante.common.CharacterClass
import me.chosante.common.Characteristic
import me.chosante.common.Characteristic.MASTERY_ELEMENTARY_EARTH
import me.chosante.common.Characteristic.MASTERY_ELEMENTARY_FIRE
import me.chosante.common.Characteristic.MASTERY_ELEMENTARY_ONE_RANDOM_ELEMENT
import me.chosante.common.Characteristic.MASTERY_ELEMENTARY_THREE_RANDOM_ELEMENT
import me.chosante.common.Characteristic.MASTERY_ELEMENTARY_TWO_RANDOM_ELEMENT
import me.chosante.common.Characteristic.MASTERY_ELEMENTARY_WATER
import me.chosante.common.Characteristic.MASTERY_ELEMENTARY_WIND
import me.chosante.common.Characteristic.RESISTANCE_ELEMENTARY_EARTH
import me.chosante.common.Characteristic.RESISTANCE_ELEMENTARY_FIRE
import me.chosante.common.Characteristic.RESISTANCE_ELEMENTARY_ONE_RANDOM_ELEMENT
import me.chosante.common.Characteristic.RESISTANCE_ELEMENTARY_THREE_RANDOM_ELEMENT
import me.chosante.common.Characteristic.RESISTANCE_ELEMENTARY_TWO_RANDOM_ELEMENT
import me.chosante.common.Characteristic.RESISTANCE_ELEMENTARY_WATER
import me.chosante.common.Characteristic.RESISTANCE_ELEMENTARY_WIND
import me.chosante.common.Equipment
import me.chosante.common.I18nText
import me.chosante.common.ItemType
import me.chosante.common.Rarity
import me.chosante.common.skills.CharacterSkills
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.ThrowingSupplier
import java.math.BigDecimal
import java.time.Duration
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

/**
 * The random-element roll placement keeps EVERY roll, whatever the objective can see of it.
 *
 * The exact assignments ([assignMaxCappedMasteryRandomValues] for precision, [assignMaxMinMasteryRandomValues] for
 * most-masteries) used to start from "no roll placed" and to replace that only on a STRICT gain of their objective. Once
 * the non-random stats already met the targets (precision) or the minimum could not move (most-masteries) nothing gained
 * anything, so every random-element mastery silently vanished from the displayed stats — and, in precision, from the score
 * above 100 %. The fix keeps the objective exactly as is and breaks the tie deterministically:
 *  - precision: among the assignments with the optimal capped sum, the one the score reads best above 100 %;
 *  - most-masteries: among the assignments with the optimal minimum, the one with the most mastery on the elements the
 *    minimum is taken over (and so every roll placed).
 * Each tie-break is locked against an exhaustive enumeration's LEXICOGRAPHIC optimum, plus the end-to-end figures.
 */
class RandomRollsTieBreakTest {
    private val elements = listOf(MASTERY_ELEMENTARY_FIRE, MASTERY_ELEMENTARY_WATER, MASTERY_ELEMENTARY_EARTH, MASTERY_ELEMENTARY_WIND)
    private val oneTwoThree = listOf(MASTERY_ELEMENTARY_ONE_RANDOM_ELEMENT, MASTERY_ELEMENTARY_TWO_RANDOM_ELEMENT, MASTERY_ELEMENTARY_THREE_RANDOM_ELEMENT)

    private data class Roll(
        val value: Int,
        val count: Int,
    )

    /** An objective pair compared lexicographically: [primary] first, [secondary] only to break its ties. */
    private data class Lex(
        val primary: Long,
        val secondary: Long,
    ) : Comparable<Lex> {
        override fun compareTo(other: Lex): Int = compareValuesBy(this, other, Lex::primary, Lex::secondary)
    }

    private fun <T> combinations(
        items: List<T>,
        k: Int,
    ): List<List<T>> {
        if (k == 0) return listOf(emptyList())
        if (k > items.size) return emptyList()
        val head = items.first()
        val tail = items.drop(1)
        return combinations(tail, k - 1).map { listOf(head) + it } + combinations(tail, k)
    }

    private fun rollsToRandomMap(rolls: List<Roll>): Map<Characteristic, List<Int>> =
        rolls
            .groupBy { it.count }
            .mapValues { (_, rs) -> rs.map { it.value } }
            .mapKeys { (count, _) -> oneTwoThree[count - 1] }

    /** Every way to place each roll on `min(count, #wanted)` distinct wanted elements — the unreduced search space. */
    private fun forEachPlacement(
        base: Map<Characteristic, Int>,
        wanted: List<Characteristic>,
        rolls: List<Roll>,
        visit: (Map<Characteristic, Int>) -> Unit,
    ) {
        fun rec(
            idx: Int,
            current: Map<Characteristic, Int>,
        ) {
            if (idx == rolls.size) {
                visit(current)
                return
            }
            val (value, count) = rolls[idx]
            val eff = minOf(count, wanted.size)
            if (eff == 0 || value == 0) {
                rec(idx + 1, current)
                return
            }
            for (combo in combinations(wanted, eff)) {
                val next = current.toMutableMap()
                combo.forEach { next[it] = next.getValue(it) + value }
                rec(idx + 1, next)
            }
        }
        rec(0, base)
    }

    /** The lexicographic maximum of [key] over every placement. */
    private fun exhaustiveBest(
        base: Map<Characteristic, Int>,
        wanted: List<Characteristic>,
        rolls: List<Roll>,
        key: (Map<Characteristic, Int>) -> Lex,
    ): Lex {
        var best: Lex? = null
        forEachPlacement(base, wanted, rolls) { state ->
            val candidate = key(state)
            val incumbent = best
            if (incumbent == null || candidate > incumbent) best = candidate
        }
        return best!!
    }

    /** What the precision score multiplies a row of [target] by, in hundredths — read off [TargetStats] itself. */
    private fun scoreWeightHundredths(
        element: Characteristic,
        target: Int,
    ): Long {
        val row = TargetStat(element, target)
        return Math.round(TargetStats(listOf(row)).weight(row) * 100)
    }

    // ----- precision: the capped assignment -------------------------------------------------------

    @Test
    fun `precision keeps every roll when the targets are already met`() {
        // The audited shape (a Sram at level 245 asking fire and wind 3500): both elements are over their target
        // without any roll, so no placement can raise Σ min(value, target) — and the rolls used to vanish.
        val targets = mapOf(MASTERY_ELEMENTARY_FIRE to 3500, MASTERY_ELEMENTARY_WIND to 3500)
        val base = mapOf(MASTERY_ELEMENTARY_FIRE to 4259, MASTERY_ELEMENTARY_WIND to 4259)
        val rolls = listOf(Roll(value = 1000, count = 2), Roll(value = 500, count = 1))

        val assigned = assignMaxCappedMasteryRandomValues(rollsToRandomMap(rolls), base, targets)

        // 1000 on BOTH elements (a 2-element line on 2 wanted elements has no choice) and the 500 on one of them.
        assertThat(assigned.getValue(MASTERY_ELEMENTARY_FIRE) + assigned.getValue(MASTERY_ELEMENTARY_WIND))
            .describedAs("every roll is placed: 4259 + 4259 + 2 × 1000 + 500")
            .isEqualTo(11_018)
        assertThat(assigned.values.min()).describedAs("the 1000 lands on both").isGreaterThanOrEqualTo(5259)
    }

    @Test
    fun `precision spreads free rolls over the lowest elements first`() {
        // With every target met and equal weights, nothing the score reads depends on WHERE the rolls land, so the
        // placement is the balanced one (water-fill): three 1-element rolls land on three different elements, which
        // is also what lifts the displayed all-elements mastery (the minimum) the most.
        val targets = elements.associateWith { 3000 }
        val base = elements.associateWith { 3500 }
        val rolls = listOf(Roll(value = 500, count = 1), Roll(value = 400, count = 1), Roll(value = 300, count = 1))

        val assigned = assignMaxCappedMasteryRandomValues(rollsToRandomMap(rolls), base, targets)

        assertThat(assigned.values.sorted()).containsExactly(3500, 3800, 3900, 4000)
    }

    @Test
    fun `precision keeps the resistance rolls too`() {
        val resistances = listOf(RESISTANCE_ELEMENTARY_FIRE, RESISTANCE_ELEMENTARY_WATER, RESISTANCE_ELEMENTARY_EARTH, RESISTANCE_ELEMENTARY_WIND)
        val targets = resistances.associateWith { 200 }
        val base = resistances.associateWith { 250 }
        val randoms =
            mapOf(
                RESISTANCE_ELEMENTARY_ONE_RANDOM_ELEMENT to listOf(40),
                RESISTANCE_ELEMENTARY_TWO_RANDOM_ELEMENT to listOf(30),
                RESISTANCE_ELEMENTARY_THREE_RANDOM_ELEMENT to listOf(20)
            )

        val assigned = assignMaxCappedResistanceRandomValues(randoms, base, targets)

        assertThat(assigned.values.sum()).describedAs("1000 + 40 + 2 × 30 + 3 × 20").isEqualTo(1160)
    }

    @Test
    fun `precision tie-break is the exhaustive lexicographic optimum of capped sum then score weight`() {
        // Primary = Σ min(value, target) (unchanged); secondary = Σ weight · value, the quantity the precision score
        // reads above 100 %. Targets are small next to the bases and rolls so both the "already met" regime (only the
        // secondary matters) and the "still short" regime (the primary decides) are drawn; negative rolls (the one
        // resistance malus in the data) and unequal targets (unequal weights) are drawn too, and the wanted elements
        // arrive in a shuffled order — the optimum must not care.
        val random = Random(20261005)
        repeat(3000) {
            val m = 2 + random.nextInt(3)
            val wanted = elements.shuffled(random).take(m)
            val targets = wanted.associateWith { 3 + random.nextInt(60) }
            val base = wanted.associateWith { random.nextInt(0, 70) }
            val rolls =
                List(1 + random.nextInt(5)) {
                    val value = if (random.nextInt(12) == 0) -(1 + random.nextInt(15)) else 1 + random.nextInt(40)
                    Roll(value = value, count = 1 + random.nextInt(3))
                }
            val weight = wanted.associateWith { scoreWeightHundredths(it, targets.getValue(it)) }

            fun key(state: Map<Characteristic, Int>) =
                Lex(
                    primary = wanted.sumOf { minOf(state.getValue(it), targets.getValue(it)).toLong() },
                    secondary = wanted.sumOf { weight.getValue(it) * state.getValue(it) }
                )

            val assigned = assignMaxCappedMasteryRandomValues(rollsToRandomMap(rolls), base, targets)

            assertThat(key(assigned))
                .describedAs("lexicographic (capped, weighted) optimum; base=%s targets=%s rolls=%s", base, targets, rolls)
                .isEqualTo(exhaustiveBest(base, wanted, rolls, ::key))
            val placedMass = rolls.sumOf { it.value * minOf(it.count, m) }
            assertThat(assigned.values.sum() - base.values.sum())
                .describedAs("every roll is placed; base=%s targets=%s rolls=%s", base, targets, rolls)
                .isEqualTo(placedMass)
        }
    }

    @Test
    fun `precision places the same rolls whatever order the wanted elements arrive in`() {
        val random = Random(777)
        repeat(300) {
            val wanted = elements.shuffled(random).take(2 + random.nextInt(3))
            val targets = wanted.associateWith { 3 + random.nextInt(60) }
            val base = wanted.associateWith { random.nextInt(0, 70) }
            val rolls = List(1 + random.nextInt(5)) { Roll(value = 1 + random.nextInt(40), count = 1 + random.nextInt(3)) }
            val reversed = wanted.reversed()

            val forward = assignMaxCappedMasteryRandomValues(rollsToRandomMap(rolls), base, targets)
            val backward =
                assignMaxCappedMasteryRandomValues(
                    rollsToRandomMap(rolls),
                    reversed.associateWith { base.getValue(it) },
                    reversed.associateWith { targets.getValue(it) }
                )

            // Same VALUES per element, not just the same objective: the displayed stats must not depend on how the
            // caller's HashSet happened to iterate (it differs between two runs of the JVM).
            assertThat(backward).describedAs("base=%s targets=%s rolls=%s", base, targets, rolls).isEqualTo(forward)
        }
    }

    // ----- most-masteries: the max-min assignment -------------------------------------------------

    @Test
    fun `most-masteries keeps the roll when the minimum cannot move`() {
        // One 1-element roll cannot lift the minimum of two equal elements, so no placement used to beat "no roll"
        // and the roll vanished from the stats.
        val base = mapOf(MASTERY_ELEMENTARY_FIRE to 100, MASTERY_ELEMENTARY_WATER to 100)
        val wanted = base.mapValues { 9999 }

        val assigned = assignMaxMinMasteryRandomValues(rollsToRandomMap(listOf(Roll(value = 50, count = 1))), base, wanted, base.keys.toList())

        assertThat(assigned.values.sorted()).containsExactly(100, 150)
    }

    @Test
    fun `most-masteries spreads rolls that cannot move the minimum over the lowest elements first`() {
        val base = elements.associateWith { 3000 }
        val wanted = elements.associateWith { 9999 }
        val rolls = listOf(Roll(value = 500, count = 1), Roll(value = 400, count = 1), Roll(value = 300, count = 1))

        // 3 one-element rolls cannot lift the minimum of 4 equal elements (the 4th stays at 3000).
        val assigned = assignMaxMinMasteryRandomValues(rollsToRandomMap(rolls), base, wanted, elements)

        assertThat(assigned.values.sorted()).containsExactly(3000, 3300, 3400, 3500)
    }

    @Test
    fun `most-masteries tie-break is the exhaustive lexicographic optimum of minimum then mastery on the minimised elements`() {
        // Primary = the minimum over the subset (unchanged), secondary = the total on the subset (so the rolls are placed
        // and land on the subset rather than beside it). Exhaustive over the UNREDUCED space: rolls may land on any wanted
        // element, including those outside the subset. Both the plain and the per-element-DI weighted minimum.
        val random = Random(20261006)
        repeat(3000) {
            val m = 2 + random.nextInt(3)
            val wanted = elements.take(m)
            val base = wanted.associateWith { random.nextInt(0, 25) }
            val rolls = List(1 + random.nextInt(5)) { Roll(value = 1 + random.nextInt(10), count = 1 + random.nextInt(3)) }
            val subset = wanted.filter { random.nextBoolean() }.ifEmpty { listOf(wanted.first()) }
            val weighted = random.nextBoolean()
            val weights = if (weighted) subset.associateWith { (100 + random.nextInt(51)).toLong() } else null
            val offset = if (weighted) random.nextInt(0, 30).toLong() else 0L

            fun key(state: Map<Characteristic, Int>) =
                Lex(
                    primary =
                        subset.minOf {
                            if (weights == null) state.getValue(it).toLong() else weights.getValue(it) * maxOf(0L, offset + state.getValue(it))
                        },
                    secondary = subset.sumOf { state.getValue(it).toLong() }
                )

            val assigned =
                assignMaxMinMasteryRandomValues(
                    rollsToRandomMap(rolls),
                    base,
                    wanted.associateWith { 9999 },
                    subset,
                    weights = weights,
                    offset = offset
                )

            assertThat(key(assigned))
                .describedAs("lexicographic (min, subset total) optimum; base=%s rolls=%s subset=%s weights=%s offset=%s", base, rolls, subset, weights, offset)
                .isEqualTo(exhaustiveBest(base, wanted, rolls, ::key))
            val placedMass = rolls.sumOf { it.value * minOf(it.count, m) }
            assertThat(assigned.values.sum() - base.values.sum())
                .describedAs("every roll is placed (on the subset, or spilled beside it); base=%s rolls=%s subset=%s", base, rolls, subset)
                .isEqualTo(placedMass)
        }
    }

    // ----- end to end: the displayed stats and the score ------------------------------------------

    private fun equipment(
        id: Int,
        type: ItemType,
        stats: Map<Characteristic, Int>,
    ) = Equipment(
        equipmentId = id,
        guiId = id,
        level = 1,
        name = I18nText("item$id", "item$id", "item$id", "item$id"),
        rarity = Rarity.COMMON,
        itemType = type,
        characteristics = stats
    )

    private val skills = CharacterSkills(1)
    private val character = Character(CharacterClass.CRA, 1, 1, skills)

    @Test
    fun `precision stats and score above 100 count the random rolls once the targets are met`() {
        // 4000 fire + 4000 wind meet the 3500 targets on their own; a 2-element roll of 1000 and a 1-element roll of 700
        // sit on top. The score above 100 % reads Σ weight · value / Σ weight · target with weight = 100/target at 2
        // decimals (0.03 here): (8000 + 2 × 1000 + 700) × 0.03 / 210 → 152.8 — it used to read 8000 → 114.2.
        val build =
            BuildCombination(
                equipments =
                    listOf(
                        equipment(1, ItemType.AMULET, mapOf(MASTERY_ELEMENTARY_FIRE to 4000, MASTERY_ELEMENTARY_WIND to 4000)),
                        equipment(2, ItemType.BELT, mapOf(MASTERY_ELEMENTARY_TWO_RANDOM_ELEMENT to 1000)),
                        equipment(3, ItemType.BOOTS, mapOf(MASTERY_ELEMENTARY_ONE_RANDOM_ELEMENT to 700))
                    ),
                characterSkills = skills
            )
        val targetStats = TargetStats(listOf(TargetStat(MASTERY_ELEMENTARY_FIRE, 3500), TargetStat(MASTERY_ELEMENTARY_WIND, 3500)))

        val stats =
            computeCharacteristicsValues(
                buildCombination = build,
                characterBaseCharacteristics = character.baseCharacteristicValues,
                masteryElementsWanted = targetStats.masteryElementsWanted,
                resistanceElementsWanted = targetStats.resistanceElementsWanted,
                scoreComputationMode = ScoreComputationMode.FIND_CLOSEST_BUILD_FROM_INPUT
            )
        assertThat(stats.getValue(MASTERY_ELEMENTARY_FIRE) + stats.getValue(MASTERY_ELEMENTARY_WIND))
            .describedAs("the displayed fire + wind carries both rolls")
            .isEqualTo(10_700)
        assertThat(stats.getValue(MASTERY_ELEMENTARY_FIRE)).isGreaterThanOrEqualTo(5000)
        assertThat(stats.getValue(MASTERY_ELEMENTARY_WIND)).isGreaterThanOrEqualTo(5000)

        val score = FindClosestBuildFromInputScoring.computeScore(targetStats, build, character.baseCharacteristicValues)
        assertThat(score).isEqualByComparingTo(BigDecimal("152.8"))
    }

    @Test
    fun `precision score still caps at its targets and below 100 the rolls only count up to them`() {
        // The primary is untouched: a roll that fills a missing part still counts up to the target, and the score stays
        // under 100 as long as one target is short (the surplus is only read once every target is met).
        val build =
            BuildCombination(
                equipments =
                    listOf(
                        equipment(1, ItemType.AMULET, mapOf(MASTERY_ELEMENTARY_FIRE to 3000, MASTERY_ELEMENTARY_WIND to 3000)),
                        equipment(2, ItemType.BELT, mapOf(MASTERY_ELEMENTARY_ONE_RANDOM_ELEMENT to 400))
                    ),
                characterSkills = skills
            )
        val targetStats = TargetStats(listOf(TargetStat(MASTERY_ELEMENTARY_FIRE, 3500), TargetStat(MASTERY_ELEMENTARY_WIND, 3500)))

        val score = FindClosestBuildFromInputScoring.computeScore(targetStats, build, character.baseCharacteristicValues)

        // 3400 for one element + 3000 for the other: (3400 + 3000) × 0.03 / 210 → 91.42 (4 significant digits, floored)
        assertThat(score).isEqualByComparingTo(BigDecimal("91.42"))
    }

    @Test
    fun `precision puts the surplus roll on the row the score weighs most`() {
        // Fire asks 1000 (weight 0.10) and wind 3500 (weight 0.03) and both are met on their own, so all that is left to rank
        // is where a one-element roll of 1000 lands: on fire it reads +100 above 100 %, on wind only +30.
        // (2500 × 0.10 + 4000 × 0.03) / (1000 × 0.10 + 3500 × 0.03) → 180.4 — not 146.3 (on wind), nor 131.7 (no roll).
        val build =
            BuildCombination(
                equipments =
                    listOf(
                        equipment(1, ItemType.AMULET, mapOf(MASTERY_ELEMENTARY_FIRE to 1500, MASTERY_ELEMENTARY_WIND to 4000)),
                        equipment(2, ItemType.BELT, mapOf(MASTERY_ELEMENTARY_ONE_RANDOM_ELEMENT to 1000))
                    ),
                characterSkills = skills
            )
        val targetStats = TargetStats(listOf(TargetStat(MASTERY_ELEMENTARY_FIRE, 1000), TargetStat(MASTERY_ELEMENTARY_WIND, 3500)))

        val score = FindClosestBuildFromInputScoring.computeScore(targetStats, build, character.baseCharacteristicValues)

        assertThat(score).isEqualByComparingTo(BigDecimal("180.4"))
    }

    @Test
    fun `a build shows the same stats whatever order its items are listed in`() {
        // A streamed build and the same build reloaded from a save list their items in different orders; both must show the
        // same per-element stats, so no tie of the placement may depend on that order.
        val items =
            listOf(
                equipment(1, ItemType.AMULET, mapOf(Characteristic.MASTERY_ELEMENTARY to 3600, MASTERY_ELEMENTARY_ONE_RANDOM_ELEMENT to 500)),
                equipment(2, ItemType.BELT, mapOf(MASTERY_ELEMENTARY_TWO_RANDOM_ELEMENT to 300)),
                equipment(3, ItemType.BOOTS, mapOf(MASTERY_ELEMENTARY_TWO_RANDOM_ELEMENT to 200)),
                equipment(4, ItemType.CAPE, mapOf(MASTERY_ELEMENTARY_THREE_RANDOM_ELEMENT to 200)),
                equipment(5, ItemType.HELMET, mapOf(MASTERY_ELEMENTARY_THREE_RANDOM_ELEMENT to 350)),
                equipment(6, ItemType.CHEST_PLATE, mapOf(MASTERY_ELEMENTARY_TWO_RANDOM_ELEMENT to 450)),
                equipment(7, ItemType.SHOULDER_PADS, mapOf(MASTERY_ELEMENTARY_ONE_RANDOM_ELEMENT to 120)),
                equipment(8, ItemType.EMBLEM, mapOf(MASTERY_ELEMENTARY_THREE_RANDOM_ELEMENT to 90, MASTERY_ELEMENTARY_FIRE to 700))
            )
        val random = Random(808)
        val requests =
            listOf(
                // all four elements, equal rows ⇒ every placement ties on the weighted sum
                ScoreComputationMode.FIND_CLOSEST_BUILD_FROM_INPUT to TargetStats(listOf(TargetStat(Characteristic.MASTERY_ELEMENTARY, 3500))),
                // three elements, unequal rows
                ScoreComputationMode.FIND_CLOSEST_BUILD_FROM_INPUT to
                    TargetStats(listOf(TargetStat(MASTERY_ELEMENTARY_FIRE, 3000), TargetStat(MASTERY_ELEMENTARY_WATER, 3600), TargetStat(MASTERY_ELEMENTARY_WIND, 4100))),
                ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT to TargetStats(listOf(TargetStat(Characteristic.MASTERY_ELEMENTARY, 1)))
            )
        for ((mode, targetStats) in requests) {
            fun statsOf(listed: List<Equipment>) =
                computeCharacteristicsValues(
                    buildCombination = BuildCombination(listed, skills),
                    characterBaseCharacteristics = character.baseCharacteristicValues,
                    masteryElementsWanted = targetStats.masteryElementsWanted,
                    resistanceElementsWanted = targetStats.resistanceElementsWanted,
                    scoreComputationMode = mode,
                    masteryElementsToMinimize = targetStats.masteryElementsToMinimize.takeIf { mode == ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT }
                )

            val reference = statsOf(items)
            repeat(25) {
                assertThat(statsOf(items.shuffled(random))).describedAs("mode=%s targets=%s", mode, targetStats.map { it.characteristic }).isEqualTo(reference)
            }
        }
    }

    @Test
    fun `precision tie-break stays bounded on a shape no build has`() {
        // 16 one-element rolls over four unmet rows of unequal weight: the shape the weighted tie-break's node budget is for.
        // Every roll is still placed, and it returns at once (the generous limit only catches an exponential search).
        val random = Random(31337)
        val targets =
            mapOf(MASTERY_ELEMENTARY_FIRE to 5200, MASTERY_ELEMENTARY_WATER to 2600, MASTERY_ELEMENTARY_EARTH to 4100, MASTERY_ELEMENTARY_WIND to 3300)
        val base = targets.mapValues { (_, target) -> target - 1000 - random.nextInt(2000) }
        val rolls = List(16) { Roll(value = 40 + random.nextInt(760), count = 1) }

        val assigned =
            assertTimeoutPreemptively(
                Duration.ofSeconds(30),
                ThrowingSupplier { assignMaxCappedMasteryRandomValues(rollsToRandomMap(rolls), base, targets) }
            )

        assertThat(assigned.values.sum() - base.values.sum()).isEqualTo(rolls.sumOf { it.value })
    }

    @Test
    fun `most-masteries stats keep the roll that cannot move the minimum and the score ignores it`() {
        val withoutRoll =
            BuildCombination(
                equipments = listOf(equipment(1, ItemType.AMULET, mapOf(MASTERY_ELEMENTARY_FIRE to 100, MASTERY_ELEMENTARY_WATER to 100))),
                characterSkills = skills
            )
        val withRoll =
            withoutRoll.copy(equipments = withoutRoll.equipments + equipment(2, ItemType.BELT, mapOf(MASTERY_ELEMENTARY_ONE_RANDOM_ELEMENT to 50)))
        val targetStats = TargetStats(listOf(TargetStat(MASTERY_ELEMENTARY_FIRE, 1), TargetStat(MASTERY_ELEMENTARY_WATER, 1)))

        val stats =
            computeCharacteristicsValues(
                buildCombination = withRoll,
                characterBaseCharacteristics = character.baseCharacteristicValues,
                masteryElementsWanted = targetStats.masteryElementsWanted,
                resistanceElementsWanted = targetStats.resistanceElementsWanted,
                scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT,
                masteryElementsToMinimize = targetStats.masteryElementsToMinimize
            )

        assertThat(stats.getValue(MASTERY_ELEMENTARY_FIRE) + stats.getValue(MASTERY_ELEMENTARY_WATER)).describedAs("the roll is displayed").isEqualTo(250)
        assertThat(minOf(stats.getValue(MASTERY_ELEMENTARY_FIRE), stats.getValue(MASTERY_ELEMENTARY_WATER))).isEqualTo(100)
        assertThat(FindMostMasteriesFromInputScoring.computeScore(targetStats, withRoll, character.baseCharacteristicValues))
            .describedAs("the minimum, and so the score, is the same with or without the roll")
            .isEqualByComparingTo(FindMostMasteriesFromInputScoring.computeScore(targetStats, withoutRoll, character.baseCharacteristicValues))
    }

    // ----- model ↔ scorer: the solver's optimum is the scorer's best -------------------------------

    private fun allValidCombinations(equipments: List<Equipment>): List<BuildCombination> =
        (0 until (1 shl equipments.size))
            .map { mask -> BuildCombination(equipments.filterIndexed { index, _ -> mask and (1 shl index) != 0 }, skills) }
            .filter { it.isValid() }

    @Test
    fun `precision solver optimum is the scorer's best when only the random rolls tell the builds apart`(): Unit =
        runBlocking {
            // Fire and wind targets of 100 are met by the belt alone, so what ranks the amulets is what they add ABOVE
            // the targets: BigRoll's 2-element 300 line (600 over the two elements) beats FixedBig's 400 of fire. The
            // model has always counted the rolls (its free assignment feeds the overflow bonus); the scorer used to drop
            // them, so it ranked FixedBig first and the solver's build re-scored 120 against an exhaustive best of 320.
            val equipments =
                listOf(
                    equipment(1, ItemType.AMULET, mapOf(MASTERY_ELEMENTARY_TWO_RANDOM_ELEMENT to 300)),
                    equipment(2, ItemType.AMULET, mapOf(MASTERY_ELEMENTARY_FIRE to 400)),
                    equipment(3, ItemType.BELT, mapOf(MASTERY_ELEMENTARY_FIRE to 120, MASTERY_ELEMENTARY_WIND to 120))
                )
            val targetStats = TargetStats(listOf(TargetStat(MASTERY_ELEMENTARY_FIRE, 100), TargetStat(MASTERY_ELEMENTARY_WIND, 100)))
            val score = { build: BuildCombination -> FindClosestBuildFromInputScoring.computeScore(targetStats, build, character.baseCharacteristicValues) }
            val exhaustive = allValidCombinations(equipments).maxOf { score(it) }
            val params =
                WakfuBestBuildParams(
                    character = character,
                    targetStats = targetStats,
                    searchDuration = 5.seconds,
                    stopWhenBuildMatch = false,
                    maxRarity = Rarity.EPIC,
                    forcedItems = emptyList(),
                    excludedItems = emptyList(),
                    scoreComputationMode = ScoreComputationMode.FIND_CLOSEST_BUILD_FROM_INPUT
                )

            val best =
                WakfuBuildSolver
                    .optimize(params, equipments.groupBy { it.itemType }, emptyList(), emptyList(), WakfuBuildSolver.SolverTuning())
                    .toList()
                    .last()

            // (120 + 300 + 120 + 300) × 1.0 / 200 → 420.0
            assertThat(exhaustive).isEqualByComparingTo(BigDecimal("420.0"))
            assertThat(best.matchPercentage).isEqualByComparingTo(exhaustive)
        }
}
