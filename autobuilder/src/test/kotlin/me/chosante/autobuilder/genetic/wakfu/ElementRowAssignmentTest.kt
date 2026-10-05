package me.chosante.autobuilder.genetic.wakfu

import me.chosante.autobuilder.domain.TargetStat
import me.chosante.autobuilder.domain.TargetStats
import me.chosante.common.Characteristic
import me.chosante.common.skills.CharacterSkills
import me.chosante.common.skills.SkillCharacteristic
import me.chosante.common.skills.UnitType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * Locks [ElementRowObjective] — the scorers' random-element placement for a family read through one joint fold (per-element
 * rows over several elements, or a resistance floor) — against EXHAUSTIVE enumeration: over every way to put each roll on its
 * [rollCover] distinct fold elements (`min(k, n)`; in a family with a floor, a negative roll on as few as the elements outside
 * the fold leave it), the assignment it returns reaches the lexicographic optimum of the solver's objective for those rows
 * (primary, 0-weight rows met, secondary), recomputed here from the request's rows with the solver's formulas — never through
 * the class's own evaluation — and places every roll; the placement keeping the floors is the optimum among those that do.
 */
class ElementRowAssignmentTest {
    private data class Roll(
        val value: Int,
        val count: Int,
    )

    /** The solver's objective for [family]'s rows of [targetStats] under [mode], on per-element values [v] (StatBuilder's formulas). */
    private fun solverKey(
        targetStats: TargetStats,
        family: ElementFamily,
        mode: ScoreComputationMode,
        v: Map<Characteristic, Int>,
    ): Triple<Long, Int, Long> {
        val precision = mode == ScoreComputationMode.FIND_CLOSEST_BUILD_FROM_INPUT
        var primary = 0L
        var met = 0
        var secondary = 0L
        // The rows' expected total (Σ weight · target): the secondary only ranks placements whose primary reaches it.
        var expected = 0L
        for (row in targetStats) {
            val w = targetStats.fixedPointWeight(row)
            val t = row.target.toLong()
            val read: List<Long> =
                when (row.characteristic) {
                    family.aggregate -> family.elements.map { v.getValue(it).toLong() }
                    // A resistance row of target 0 on an element nobody wants is a FLOOR, no element of the objective.
                    in family.elements -> listOf((v[row.characteristic] ?: continue).toLong())
                    else -> continue
                }
            val aggregate = row.characteristic == family.aggregate
            expected += w * t
            if (precision) {
                // StatBuilder.precisionScore: cappedContribution / averagedContribution (truncating /4).
                if (aggregate) {
                    primary += read.sumOf { minOf(w * it, w * t) } / read.size
                    secondary += read.sumOf { w * it } / read.size
                } else {
                    primary += minOf(w * read.single(), w * t)
                    secondary += w * read.single()
                }
            } else {
                // StatBuilder.totalActualScore (requiredActualStat: the aggregate reads the min) + overshootScore.
                val actual = read.min()
                primary += w * maxOf(minOf(actual, t), -t)
                if (w == 0L && t > 0L && actual >= t) met++
                if (mode == ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT) {
                    val tp = t.coerceAtLeast(0L)
                    secondary += w * maxOf(minOf(actual - tp, tp), 0L)
                }
            }
        }
        // The tie-breaker only ranks the all-met plateau: precisionScore's bonus (uncapped − capped) needs `fullyMet`, and every
        // most-masteries hard-leg result meets every row (below, the soft leg's bucketed penalty has no exact counterpart).
        if (primary != expected) secondary = 0L
        return Triple(primary, met, secondary)
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

    /**
     * Every reachable final per-element map, each roll on its cover of distinct fold elements: `min(count, wanted)`, or — in a family
     * with a floor ([freeSinks] = the elements outside the fold) — as the game places it, a negative roll on `count − freeSinks` of
     * them at least. (Spelled out here, not through [rollCover].)
     */
    private fun allAssignments(
        base: Map<Characteristic, Int>,
        wanted: List<Characteristic>,
        rolls: List<Roll>,
        freeSinks: Int? = null,
    ): List<Map<Characteristic, Int>> {
        var states = listOf(base)
        for ((value, count) in rolls) {
            val cover = if (freeSinks != null && value < 0) (count - freeSinks).coerceIn(0, wanted.size) else minOf(count, wanted.size)
            if (value == 0 || cover == 0) continue
            states =
                states.flatMap { state ->
                    combinations(wanted, cover).map { combo -> state.toMutableMap().apply { combo.forEach { this[it] = getValue(it) + value } } }
                }
        }
        return states
    }

    private val keyOrder = compareBy<Triple<Long, Int, Long>>({ it.first }, { it.second }, { it.third })

    private fun randomCase(random: Random): Triple<TargetStats, ElementFamily, ScoreComputationMode>? {
        val family = if (random.nextBoolean()) ElementFamily.RESISTANCE else ElementFamily.MASTERY
        val mode = ScoreComputationMode.entries[random.nextInt(ScoreComputationMode.entries.size)]
        val rowCount = 2 + random.nextInt(3) // 2..4 per-element rows
        val rows =
            family.elements.shuffled(random).take(rowCount).map { element ->
                // Mostly positive targets; sometimes 0 (an inert sink); weights 1..5 like the GUI's priorities, rarely 0.
                val target = if (random.nextInt(6) == 0) 0 else 5 + random.nextInt(60)
                TargetStat(element, target, if (random.nextInt(12) == 0) 0 else 1 + random.nextInt(5))
            }
        val aggregate = if (random.nextInt(4) == 0) listOf(TargetStat(family.aggregate, 5 + random.nextInt(60), 1 + random.nextInt(5))) else emptyList()
        val targetStats = TargetStats(rows + aggregate)
        return if (targetStats.readsJointPerElementRows(family, mode)) Triple(targetStats, family, mode) else null
    }

    @Test
    fun `the per-element-row assignment reaches the exhaustive optimum of the solver's objective and places every roll`() {
        val random = Random(20261005)
        var checked = 0
        repeat(6000) {
            val (targetStats, family, mode) = randomCase(random) ?: return@repeat
            val objective = checkNotNull(ElementRowObjective.of(targetStats, family, mode))
            val wanted = objective.elements
            val base = wanted.associateWith { random.nextInt(-25, 50) }
            // k = 1..3 rolls (the occasional negative one, like the Kel'Dwa ring's −250 resistance on 1 random element).
            val rolls =
                List(random.nextInt(0, 6)) {
                    Roll(value = if (random.nextInt(10) == 0) -random.nextInt(1, 20) else random.nextInt(1, 30), count = 1 + random.nextInt(3))
                }

            val assigned = objective.assign(rolls.map { it.value to it.count }, base)
            val reachable = allAssignments(base, wanted, rolls, targetStats.freeSinks(family))
            val best = reachable.map { solverKey(targetStats, family, mode, it) }.maxWith(keyOrder)

            assertThat(reachable)
                .describedAs("every roll is placed on its cover of the fold's elements; base=%s rolls=%s", base, rolls)
                .contains(assigned)
            assertThat(solverKey(targetStats, family, mode, assigned))
                .describedAs(
                    "mode=%s family=%s rows=%s base=%s rolls=%s",
                    mode,
                    family,
                    targetStats.map { "${it.characteristic}:${it.target}x${it.userDefinedWeight}" },
                    base,
                    rolls
                ).isEqualTo(best)
            // The class's own evaluation agrees with the independent one on the returned assignment.
            val values = IntArray(wanted.size) { assigned.getValue(wanted[it]) }
            assertThat(Triple(objective.primary(values), objective.zeroWeightMet(values), objective.secondary(values)))
                .isEqualTo(solverKey(targetStats, family, mode, assigned))
            checked++
        }
        assertThat(checked).isGreaterThan(2000)
    }

    @Test
    fun `the placement keeping the floors and precision's rows of target 0 at 0 or more is the exhaustive optimum among those, or none`() {
        val random = Random(4711)
        var checked = 0
        var infeasible = 0
        repeat(8000) {
            val family = if (random.nextBoolean()) ElementFamily.RESISTANCE else ElementFamily.MASTERY
            val mode = if (family == ElementFamily.MASTERY) ScoreComputationMode.FIND_CLOSEST_BUILD_FROM_INPUT else ScoreComputationMode.entries.random(random)
            // The rows sometimes have target 0: a mastery one that precision halves on, a resistance one — a floor — in every mode.
            val rows =
                family.elements.shuffled(random).take(1 + random.nextInt(4)).mapIndexed { index, element ->
                    TargetStat(element, if (random.nextInt(2) == 0 && (index > 0 || family == ElementFamily.RESISTANCE)) 0 else 5 + random.nextInt(60), 1 + random.nextInt(5))
                } + if (random.nextInt(4) == 0) listOf(TargetStat(family.aggregate, 5 + random.nextInt(60), 1 + random.nextInt(5))) else emptyList()
            val targetStats = TargetStats(rows)
            val objective = ElementRowObjective.of(targetStats, family, mode) ?: return@repeat
            if (!objective.hasKeptElements) return@repeat
            // What the request keeps at 0 or more, from its own semantics: its floors, or precision's masteries of target 0.
            val keptElements = if (family == ElementFamily.RESISTANCE) targetStats.resistanceFloorElements else targetStats.zeroMasteries
            val base = objective.elements.associateWith { random.nextInt(-30, 40) }
            val rolls =
                List(random.nextInt(0, 6)) {
                    Roll(value = if (random.nextInt(8) == 0) -random.nextInt(1, 20) else random.nextInt(1, 30), count = 1 + random.nextInt(3))
                }

            val kept = objective.placeKeepingFloors(rolls.map { it.value to it.count }, base)
            val keeping =
                allAssignments(base, objective.elements, rolls, targetStats.freeSinks(family)).filter { state -> keptElements.all { state.getValue(it) >= 0 } }
            val described = "rows=${rows.map { "${it.characteristic}:${it.target}x${it.userDefinedWeight}" }} base=$base rolls=$rolls"
            if (keeping.isEmpty()) {
                assertThat(kept).describedAs(described).isNull()
                infeasible++
                return@repeat
            }
            assertThat(kept).describedAs(described).isNotNull()
            assertThat(kept!!.exact).isTrue()
            assertThat(keeping).describedAs(described).contains(kept.values)
            assertThat(solverKey(targetStats, family, mode, kept.values))
                .describedAs(described)
                .isEqualTo(keeping.map { solverKey(targetStats, family, mode, it) }.maxWith(keyOrder))
            checked++
        }
        assertThat(checked).isGreaterThan(1500)
        assertThat(infeasible).isGreaterThan(10)
    }

    @Test
    fun `the placement depends neither on the order of the rolls nor on the order of the rows`() {
        // A streamed build and the same build reloaded from a save list their items (so their rolls) in different orders, and
        // the request's rows come in the user's order: both must show the same per-element stats.
        val random = Random(808)
        repeat(1500) {
            val (targetStats, family, mode) = randomCase(random) ?: return@repeat
            val objective = checkNotNull(ElementRowObjective.of(targetStats, family, mode))
            val base = objective.elements.associateWith { random.nextInt(-25, 50) }
            val rolls = List(random.nextInt(1, 7)) { (if (random.nextInt(10) == 0) -random.nextInt(1, 20) else random.nextInt(1, 30)) to 1 + random.nextInt(3) }
            val reference = objective.assign(rolls, base)
            val reordered = checkNotNull(ElementRowObjective.of(TargetStats(targetStats.shuffled(random)), family, mode))
            repeat(3) {
                assertThat(reordered.assign(rolls.shuffled(random), base.entries.shuffled(random).associate { it.toPair() }))
                    .describedAs("rows=%s rolls=%s", targetStats.map { "${it.characteristic}:${it.target}x${it.userDefinedWeight}" }, rolls)
                    .isEqualTo(reference)
            }
        }
    }

    @Test
    fun `targets every assignment can meet are met - the hard leg's meaning`() {
        // Four GUI-like rows of one target: whenever SOME placement meets every row, the chosen one does.
        val random = Random(4242)
        repeat(3000) {
            val target = 30 + random.nextInt(40)
            val targetStats =
                TargetStats(ElementFamily.RESISTANCE.elements.map { TargetStat(it, target, 1 + random.nextInt(5)) })
            val objective = checkNotNull(ElementRowObjective.of(targetStats, ElementFamily.RESISTANCE, ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT))
            val base = objective.elements.associateWith { random.nextInt(0, 60) }
            val rolls = List(random.nextInt(1, 7)) { Roll(random.nextInt(1, 40), 1 + random.nextInt(3)) }
            val someMeetsAll = allAssignments(base, objective.elements, rolls).any { state -> state.values.all { it >= target } }
            val assigned = objective.assign(rolls.map { it.value to it.count }, base)
            assertThat(assigned.values.all { it >= target })
                .describedAs("base=%s rolls=%s target=%s", base, rolls, target)
                .isEqualTo(someMeetsAll)
        }
    }

    @Test
    fun `a 0-weight row with a target is met whenever the weighted rows allow it`() {
        // userDefinedWeight 0 (CLI only): the penalty ignores the row but the hard leg requires it.
        val targetStats =
            TargetStats(
                listOf(
                    TargetStat(Characteristic.RESISTANCE_ELEMENTARY_FIRE, 50, 1),
                    TargetStat(Characteristic.RESISTANCE_ELEMENTARY_WATER, 50, 0)
                )
            )
        val objective = checkNotNull(ElementRowObjective.of(targetStats, ElementFamily.RESISTANCE, ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT))
        // Fire is met by its base; the one roll must go to water for the 0-weight row.
        val assigned =
            objective.assign(
                listOf(30 to 1),
                mapOf(Characteristic.RESISTANCE_ELEMENTARY_FIRE to 60, Characteristic.RESISTANCE_ELEMENTARY_WATER to 25)
            )
        assertThat(assigned).isEqualTo(mapOf(Characteristic.RESISTANCE_ELEMENTARY_WATER to 55, Characteristic.RESISTANCE_ELEMENTARY_FIRE to 60))
    }

    @Test
    fun `routing - which requests read a family through joint per-element rows`() {
        fun rows(vararg pairs: Pair<Characteristic, Int>) = TargetStats(pairs.map { TargetStat(it.first, it.second) })
        val mm = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT
        val precision = ScoreComputationMode.FIND_CLOSEST_BUILD_FROM_INPUT
        val maxDamage = ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE
        val fire = Characteristic.RESISTANCE_ELEMENTARY_FIRE
        val water = Characteristic.RESISTANCE_ELEMENTARY_WATER
        val fourRows =
            rows(
                *ElementFamily.RESISTANCE.elements
                    .map { it to 640 }
                    .toTypedArray()
            )

        // The GUI's expanded "all resistances" (four rows), and two rows: every mode reads them jointly.
        for (mode in ScoreComputationMode.entries) {
            assertThat(fourRows.readsJointPerElementRows(ElementFamily.RESISTANCE, mode)).describedAs("$mode").isTrue()
            assertThat(rows(fire to 100, water to 100).readsJointPerElementRows(ElementFamily.RESISTANCE, mode)).describedAs("$mode").isTrue()
        }
        // One wanted element keeps its single-element fold (exact there).
        assertThat(rows(fire to 100).readsJointPerElementRows(ElementFamily.RESISTANCE, mm)).isFalse()
        // The aggregate alone keeps its own exact fold (max-min / max-capped / greedy).
        for (mode in ScoreComputationMode.entries) {
            assertThat(rows(Characteristic.RESISTANCE_ELEMENTARY to 400).readsJointPerElementRows(ElementFamily.RESISTANCE, mode)).isFalse()
        }
        // ...but a per-element row beside it joins the same fold.
        assertThat(rows(Characteristic.RESISTANCE_ELEMENTARY to 400, fire to 500).readsJointPerElementRows(ElementFamily.RESISTANCE, mm)).isTrue()
        // A 0-valued resistance row wants no element — it is a floor: the GUI's default wind 0 beside the aggregate is left to the
        // aggregate (its element is wanted), which keeps its own fold; beside one fire row it leaves a request on ONE wanted element
        // (no pre-filter), but the floor joins the family's fold — {fire, air}, in every mode — so a roll can lift it.
        assertThat(
            rows(Characteristic.RESISTANCE_ELEMENTARY to 400, Characteristic.RESISTANCE_ELEMENTARY_WIND to 0).readsJointPerElementRows(ElementFamily.RESISTANCE, maxDamage)
        ).isFalse()
        for (mode in ScoreComputationMode.entries) {
            val defaultBesideFire = rows(Characteristic.RESISTANCE_ELEMENTARY_WIND to 0, fire to 300)
            assertThat(defaultBesideFire.readsJointPerElementRows(ElementFamily.RESISTANCE, mode)).describedAs("$mode").isTrue()
            assertThat(defaultBesideFire.foldElements(ElementFamily.RESISTANCE)).containsExactly(fire, Characteristic.RESISTANCE_ELEMENTARY_WIND)
            assertThat(defaultBesideFire.needsItemPrefilter).isFalse()
            // The GUI's default alone: a fold over air only.
            assertThat(rows(Characteristic.RESISTANCE_ELEMENTARY_WIND to 0).foldElements(ElementFamily.RESISTANCE)).containsExactly(Characteristic.RESISTANCE_ELEMENTARY_WIND)
        }
        assertThat(rows(Characteristic.RESISTANCE_ELEMENTARY_WIND to 0, fire to 300, water to 100).readsJointPerElementRows(ElementFamily.RESISTANCE, maxDamage)).isTrue()
        // Masteries: per row in precision only (most-masteries maximizes their min; max-damage reads the scenario element).
        val twoMasteries = rows(Characteristic.MASTERY_ELEMENTARY_FIRE to 100, Characteristic.MASTERY_ELEMENTARY_WATER to 100)
        assertThat(twoMasteries.readsJointPerElementRows(ElementFamily.MASTERY, precision)).isTrue()
        assertThat(twoMasteries.readsJointPerElementRows(ElementFamily.MASTERY, mm)).isFalse()
        assertThat(twoMasteries.readsJointPerElementRows(ElementFamily.MASTERY, maxDamage)).isFalse()
        // Precision: a mastery row of target 0 halves the score on its element's fold, so it reads the family jointly too.
        val zeroMasteries = rows(Characteristic.MASTERY_ELEMENTARY_FIRE to 0, Characteristic.MASTERY_ELEMENTARY_WATER to 0)
        assertThat(zeroMasteries.readsJointPerElementRows(ElementFamily.MASTERY, precision)).isTrue()
        assertThat(zeroMasteries.readsJointPerElementRows(ElementFamily.MASTERY, mm)).isFalse()
    }

    @Test
    fun `a row's fixed-point weight is its scorer weight in thousandths - a multiple of 10 the bounds' lattice relies on`() {
        // The solver reads the very same function (WakfuBuildSolver.scaledWeight delegates to it), so this pins the units: 100 /
        // target at 2 decimals, times the priority, in thousandths. Every weight being a multiple of 10 is what lets the search
        // round its bounds down to a lattice of 10 or more (only a speed matter: the rounding uses the actual gcd).
        fun weight(
            target: Int,
            priority: Int = 1,
        ): Long {
            val row = TargetStat(Characteristic.RESISTANCE_ELEMENTARY_FIRE, target, priority)
            return TargetStats(listOf(row)).fixedPointWeight(row)
        }
        assertThat(weight(300)).isEqualTo(330L)
        assertThat(weight(300, priority = 5)).isEqualTo(1_650L)
        assertThat(weight(640, priority = 4)).isEqualTo(640L)
        assertThat(weight(2000)).isEqualTo(50L)
        assertThat(weight(1)).isEqualTo(100_000L)
        assertThat(weight(0)).isEqualTo(0L)
        val random = Random(7)
        repeat(2000) {
            assertThat(weight(1 + random.nextInt(30_000), 1 + random.nextInt(5)) % 10L).isZero()
        }
    }

    private fun rowsOf(vararg rows: Triple<Characteristic, Int, Int>) = TargetStats(rows.map { (c, target, weight) -> TargetStat(c, target, weight) })

    private fun primaryOf(
        objective: ElementRowObjective,
        values: Map<Characteristic, Int>,
    ) = objective.primary(IntArray(objective.elements.size) { values.getValue(objective.elements[it]) })

    // The two slowest of 2 400 random real builds (levels 110-245) under the GUI's request shapes, both with the aggregate row
    // beside per-element rows: max-damage with "all resistances" (a level-140 build), and precision with "elemental mastery"
    // (a level-230 build), which took 3.8 M nodes before the bounds were rounded down to the objective's lattice (its root
    // bound sat 5 solver units above an optimum on a lattice of 10).
    private class SlowShape(
        val objective: ElementRowObjective,
        val rolls: List<Pair<Int, Int>>,
        val base: Map<Characteristic, Int>,
    )

    private val maxDamageAllResistances =
        SlowShape(
            checkNotNull(
                ElementRowObjective.of(
                    rowsOf(
                        Triple(Characteristic.RESISTANCE_ELEMENTARY_EARTH, 283, 2),
                        Triple(Characteristic.RESISTANCE_ELEMENTARY_WATER, 355, 1),
                        Triple(Characteristic.RESISTANCE_ELEMENTARY_FIRE, 176, 3),
                        Triple(Characteristic.RESISTANCE_ELEMENTARY, 322, 4)
                    ),
                    ElementFamily.RESISTANCE,
                    ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE
                )
            ),
            listOf(21 to 2, 28 to 3, 35 to 2, 28 to 2, 44 to 3, 50 to 1, 56 to 2, 29 to 3, 69 to 2, 65 to 2, 117 to 1, 28 to 3),
            ElementFamily.RESISTANCE.elements.associateWith { 19 }
        )

    private val precisionElementalMastery =
        SlowShape(
            checkNotNull(
                ElementRowObjective.of(
                    rowsOf(
                        Triple(Characteristic.MASTERY_ELEMENTARY_WATER, 1993, 1),
                        Triple(Characteristic.MASTERY_ELEMENTARY_EARTH, 1, 1),
                        Triple(Characteristic.MASTERY_ELEMENTARY, 2537, 2)
                    ),
                    ElementFamily.MASTERY,
                    ScoreComputationMode.FIND_CLOSEST_BUILD_FROM_INPUT
                )
            ),
            listOf(455 to 2, 177 to 3, 259 to 3, 118 to 2, 159 to 2, 244 to 2, 209 to 2, 195 to 2, 263 to 2, 188 to 3, 354 to 2, 118 to 3),
            ElementFamily.MASTERY.elements.associateWith { 0 }
        )

    @Test
    fun `the slowest real build shapes are proven well within the node budget`() {
        for ((shape, ceiling) in listOf(maxDamageAllResistances to 1_000_000L, precisionElementalMastery to 10_000L)) {
            val placement = shape.objective.place(shape.rolls, shape.base)
            assertThat(placement.exact).isTrue()
            assertThat(placement.nodes).describedAs("search nodes").isLessThan(ceiling).isLessThan(ElementRowObjective.NODE_BUDGET)
        }
    }

    @Test
    fun `a placement out of its node budget is still complete and never claims more than the optimum`() {
        // The max-damage shape above with 1 000 nodes instead of its budget: the search stops, keeps the best placement it met —
        // every roll placed — and says it is not the proven optimum.
        val shape = maxDamageAllResistances
        val cut = shape.objective.place(shape.rolls, shape.base, nodeBudget = 1_000L)
        val optimum = shape.objective.place(shape.rolls, shape.base)

        assertThat(cut.exact).isFalse()
        assertThat(optimum.exact).isTrue()
        assertThat(cut.values.values.sum() - shape.base.values.sum()).describedAs("every roll placed").isEqualTo(shape.rolls.sumOf { (value, count) -> value * count })
        assertThat(primaryOf(shape.objective, cut.values)).isLessThanOrEqualTo(primaryOf(shape.objective, optimum.values))
        assertThat(shape.objective.assign(shape.rolls, shape.base)).isEqualTo(optimum.values)
    }

    @Test
    fun `an identical search hands back the read-only placement it already found, whatever object asks`() {
        // A build is read several times over (its score, the stats column, the penalty, each emission): the second read of the
        // same rows, values and rolls costs nothing — even from another objective instance of the same request, rolls reordered.
        val shape = maxDamageAllResistances
        val first = shape.objective.place(shape.rolls, shape.base)
        val rebuilt =
            checkNotNull(
                ElementRowObjective.of(
                    rowsOf(
                        Triple(Characteristic.RESISTANCE_ELEMENTARY, 322, 4),
                        Triple(Characteristic.RESISTANCE_ELEMENTARY_FIRE, 176, 3),
                        Triple(Characteristic.RESISTANCE_ELEMENTARY_WATER, 355, 1),
                        Triple(Characteristic.RESISTANCE_ELEMENTARY_EARTH, 283, 2)
                    ),
                    ElementFamily.RESISTANCE,
                    ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE
                )
            )
        assertThat(rebuilt.place(shape.rolls.reversed(), shape.base)).isSameAs(first)
        // The shared placement is read-only: a caller writing into it would change what every later read sees.
        val expected = first.values.toMap()
        assertThatThrownBy {
            @Suppress("UNCHECKED_CAST")
            (first.values as MutableMap<Characteristic, Int>)[Characteristic.RESISTANCE_ELEMENTARY_FIRE] = 1360
        }.isInstanceOf(UnsupportedOperationException::class.java)
        assertThat(shape.objective.place(shape.rolls, shape.base).values).isEqualTo(expected)
        // Anything the result depends on is part of the key: another budget, other values, another mode are searched afresh.
        assertThat(shape.objective.place(shape.rolls, shape.base, nodeBudget = 1_000L)).isNotSameAs(first)
        assertThat(shape.objective.place(shape.rolls, shape.base + (Characteristic.RESISTANCE_ELEMENTARY_FIRE to 20))).isNotSameAs(first)
    }

    @Test
    fun `no skill applies a percentage to an elemental mastery or resistance - the assignment reads pre-percent values`() {
        // [ElementRowObjective] places the rolls on the values BEFORE the percent skills, which the scorer applies last
        // (and the solver after the fold). Exact because no skill scales an elemental stat; a new one must be modelled.
        val elemental = ElementFamily.entries.flatMap { it.elements + it.aggregate }.toSet()
        val skills = CharacterSkills(245).allCharacteristic
        val percentOnElemental =
            skills.filter { skill ->
                when (skill) {
                    is SkillCharacteristic.PairedCharacteristic ->
                        listOf(skill.first, skill.second).any { it.unitType == UnitType.PERCENT && it.characteristic in elemental }
                    else -> skill.unitType == UnitType.PERCENT && skill.characteristic in elemental
                }
            }
        assertThat(percentOnElemental).isEmpty()
    }
}
