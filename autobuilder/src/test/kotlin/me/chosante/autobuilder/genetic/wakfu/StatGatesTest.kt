package me.chosante.autobuilder.genetic.wakfu

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.autobuilder.domain.DamageScenario
import me.chosante.autobuilder.domain.Orientation
import me.chosante.autobuilder.domain.PassiveCatalog
import me.chosante.autobuilder.domain.RangeBand
import me.chosante.autobuilder.domain.SpellElement
import me.chosante.autobuilder.domain.TargetStat
import me.chosante.autobuilder.domain.TargetStats
import me.chosante.autobuilder.domain.outOfCombatSheet
import me.chosante.autobuilder.domain.statGateViolations
import me.chosante.common.Character
import me.chosante.common.CharacterClass
import me.chosante.common.Characteristic
import me.chosante.common.CriterionComparison
import me.chosante.common.Equipment
import me.chosante.common.I18nText
import me.chosante.common.ItemEquipCriterion
import me.chosante.common.ItemStatGate
import me.chosante.common.ItemType
import me.chosante.common.Rarity
import me.chosante.common.skills.CharacterSkills
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/**
 * The item STAT GATES (AGENTS.md §4 "Item equip conditions"): an item whose EQUIP criterion compares a characteristic
 * (`GetCharac("RANGE") <= 3`) is inactive in game on a build whose OUT-OF-COMBAT sheet breaks it — base + every item (its own
 * line included) + skills + runes + permanent sublimation effects + passives, never an in-combat bonus. The scorer side
 * ([outOfCombatSheet] / [statGateViolations], `BuildCombination.isValid`), the CP-SAT model (`StatBuilder.applyItemStatGates`), the
 * domination pre-filter, the greedy warm start, the E8 construct and the request validation all read it. Each solver test also
 * runs the pool with the gates STRIPPED, to show the constraint is what keeps the gated item out.
 */
class StatGatesTest {
    private val tuning =
        WakfuBuildSolver.SolverTuning(numSearchWorkers = 1, randomSeed = 1, interleaveSearch = true, maxDeterministicTime = 30.0)
    private val catalog = WakfuBestBuildFinderAlgorithm.equipments.associateBy { it.equipmentId }
    private val subs = WakfuBestBuildFinderAlgorithm.sublimations

    // Real items: Cartes And (LEGENDARY 1H, +1 range, gate range ≤ 3) and four other +1 range items of four other slots.
    private val cartesAnd = catalog.getValue(27378)
    private val needle = catalog.getValue(21761) // Aiguille à Détricoter: 1H, +1 range, 80 distance mastery, ungated
    private val rangeHelmet = catalog.getValue(21916) // Sor'Hombrero
    private val rangeAmulet = catalog.getValue(21924) // Oeil de Sor'Hon
    private val rangeRing = catalog.getValue(23983) // Bague Oubliée
    private val rangeBoots = catalog.getValue(19870) // Tongs Hy

    private fun item(
        id: Int,
        type: ItemType,
        stats: Map<Characteristic, Int>,
        gate: ItemStatGate? = null,
        rarity: Rarity = Rarity.LEGENDARY,
        level: Int = 1,
        sockets: Int = 0,
    ) = Equipment(
        equipmentId = id,
        guiId = id,
        level = level,
        name = I18nText("item$id", "item$id", "item$id", "item$id"),
        rarity = rarity,
        itemType = type,
        characteristics = stats,
        maxShardSlots = sockets,
        equipCriterion = gate?.let { ItemEquipCriterion(id, "gate", statGates = listOf(it)) }
    )

    private fun gate(
        characteristic: Characteristic,
        comparison: CriterionComparison,
        value: Int,
        max: Boolean = false,
    ) = ItemStatGate(characteristic, max, comparison, value)

    private fun build(
        vararg items: Equipment,
        level: Int = 200,
    ) = BuildCombination(items.toList(), CharacterSkills(level))

    private fun stripped(items: Collection<Equipment>) = items.map { it.copy(equipCriterion = null) }

    private fun params(
        targets: List<TargetStat>,
        level: Int = 200,
        mode: ScoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT,
        useRunes: Boolean = false,
        useSublimations: Boolean = false,
        forced: List<String> = emptyList(),
    ) = WakfuBestBuildParams(
        character = Character(CharacterClass.CRA, level, 0, CharacterSkills(level)),
        targetStats = TargetStats(targets),
        searchDuration = 10.seconds,
        stopWhenBuildMatch = false,
        maxRarity = Rarity.EPIC,
        forcedItems = forced,
        excludedItems = emptyList(),
        scoreComputationMode = mode,
        useRunes = useRunes,
        useSublimations = useSublimations,
        damageScenario = DamageScenario(element = SpellElement.FIRE, rangeBand = RangeBand.DISTANCE, orientation = Orientation.FACE)
    )

    private fun solve(
        p: WakfuBestBuildParams,
        items: List<Equipment>,
        runes: List<me.chosante.common.RuneType> = emptyList(),
        t: WakfuBuildSolver.SolverTuning = tuning,
    ): BuildCombination =
        runBlocking {
            WakfuBuildSolver
                .optimize(p, items.groupBy { it.itemType }, runes, emptyList(), t)
                .toList()
                .last()
                .individual
        }

    /** The gates [b] breaks, read with the catalog's criteria (a stripped run's items carry none). */
    private fun violations(b: BuildCombination) = WakfuBestBuildFinderAlgorithm.statGateViolations(b, CharacterClass.CRA)

    // ---- the out-of-combat sheet (scorer side) ----

    @Test
    fun `the gated item's own bonus counts - Cartes And turns inactive at 4 range`() {
        val three = build(cartesAnd, rangeHelmet, rangeAmulet)
        assertThat(outOfCombatSheet(three, CharacterClass.CRA)[Characteristic.RANGE]).isEqualTo(3)
        assertThat(statGateViolations(three, CharacterClass.CRA)).isEmpty()
        assertThat(three.isValid(CharacterClass.CRA)).isTrue()

        val four = build(cartesAnd, rangeHelmet, rangeAmulet, rangeRing)
        val broken = statGateViolations(four, CharacterClass.CRA).single()
        assertThat(broken.item).isEqualTo(cartesAnd)
        assertThat(broken.gate).isEqualTo(gate(Characteristic.RANGE, CriterionComparison.LE, 3))
        assertThat(broken.actual).isEqualTo(4)
        assertThat(broken.describe()).isEqualTo("Cartes And would be inactive in game: RANGE <= 3, the build has 4")
        assertThat(four.isValid(CharacterClass.CRA)).isFalse()
        // The reload check reads the catalog's criteria: a saved build carries none.
        assertThat(WakfuBestBuildFinderAlgorithm.equipConditionViolation(BuildCombination(stripped(four.equipments), four.characterSkills), CharacterClass.CRA))
            .isEqualTo(broken.describe())
    }

    @Test
    fun `a permanent sublimation bonus counts, a start-of-combat one does not`() {
        val visibility = subs.single { it.name.fr == "Visibilité II" } // +1 range, permanent (on the character sheet)
        val abandon = subs.single { it.name.fr == "Abandon II" } // +1 range, given in combat
        val three = build(cartesAnd, rangeHelmet, rangeAmulet)
        assertThat(statGateViolations(three.copy(sublimations = mapOf(rangeHelmet to listOf(visibility))), CharacterClass.CRA))
            .singleElement()
            .satisfies({ assertThat(it.actual).isEqualTo(4) })
        assertThat(statGateViolations(three.copy(sublimations = mapOf(rangeHelmet to listOf(abandon))), CharacterClass.CRA)).isEmpty()
    }

    @Test
    fun `runes count`() {
        val distanceRune = WakfuBestBuildFinderAlgorithm.runes.single { it.characteristic == Characteristic.MASTERY_DISTANCE }
        val gated = item(9101, ItemType.HELMET, mapOf(Characteristic.MASTERY_DISTANCE to 50), gate(Characteristic.MASTERY_DISTANCE, CriterionComparison.LE, 100))
        val carrier = item(9102, ItemType.AMULET, mapOf(Characteristic.MASTERY_DISTANCE to 50), level = 200, sockets = 4)
        val clean = build(gated, carrier)
        assertThat(statGateViolations(clean, CharacterClass.CRA)).isEmpty()
        val runed = clean.copy(runes = mapOf(carrier to listOf(distanceRune)))
        assertThat(statGateViolations(runed, CharacterClass.CRA).single().actual).isEqualTo(100 + distanceRune.valueOn(ItemType.AMULET, 200))
    }

    @Test
    fun `a GetCharacMax gate reads the pool's maximum - the MAX lines included - and the class's base stats`() {
        // Out of combat a pool is full: "max AP ≤ 11" reads base 6 + AP lines + MAX_ACTION_POINT lines.
        val belt = catalog.getValue(27304) // Ceinture Pimentée, GetCharacMax("AP") <= 11
        assertThat(belt.equipCriterion!!.statGates.single()).isEqualTo(gate(Characteristic.ACTION_POINT, CriterionComparison.LE, 11, max = true))
        val apFive = item(9103, ItemType.AMULET, mapOf(Characteristic.ACTION_POINT to 5))
        val maxApOne = item(9104, ItemType.HELMET, mapOf(Characteristic.MAX_ACTION_POINT to 1))
        assertThat(statGateViolations(build(belt, apFive), CharacterClass.CRA)).isEmpty()
        assertThat(statGateViolations(build(belt, apFive, maxApOne), CharacterClass.CRA).single().actual).isEqualTo(12)
        // A Xelor starts with 12 WP: Bottes Ailes Tell's "max WP ≥ 8" holds with nothing else, not for a 6-WP class.
        val boots = catalog.getValue(26996)
        assertThat(statGateViolations(build(boots), CharacterClass.XELOR)).isEmpty()
        assertThat(statGateViolations(build(boots), CharacterClass.CRA).single().actual).isEqualTo(6)
    }

    @Test
    fun `passives count, as they show on the sheet`() {
        val vision = PassiveCatalog.forClass(CharacterClass.SACRIEUR).first { it.flatStats[Characteristic.RANGE] == 2 }
        val three = build(cartesAnd, rangeHelmet)
        assertThat(statGateViolations(three, CharacterClass.CRA)).isEmpty()
        assertThat(statGateViolations(three.copy(passives = listOf(vision)), CharacterClass.CRA).single().actual).isEqualTo(4)
    }

    // ---- the CP-SAT model ----

    @Test
    fun `real catalog - the solver never wears Cartes And at 4 range, and wears it there once the gate is stripped`() {
        val pool = listOf(cartesAnd, needle, rangeHelmet, rangeAmulet, rangeRing, rangeBoots)
        val p = params(listOf(TargetStat(Characteristic.MASTERY_DISTANCE, 1), TargetStat(Characteristic.RANGE, 4)))
        val gated = solve(p, pool)
        assertThat(violations(gated)).isEmpty()
        assertThat(gated.isValid(CharacterClass.CRA)).isTrue()
        if (cartesAnd in gated.equipments) assertThat(outOfCombatSheet(gated, CharacterClass.CRA)[Characteristic.RANGE]).isLessThanOrEqualTo(3)

        // Without the gate the 200 distance mastery of Cartes And wins at range ≥ 4: the constraint is what keeps it out.
        val free = solve(p, stripped(pool))
        assertThat(free.equipments.map { it.equipmentId }).contains(cartesAnd.equipmentId)
        assertThat(violations(free)).isNotEmpty()
    }

    @Test
    fun `real catalog - a max-AP gate holds in the model, and breaks once stripped`() {
        val belt = catalog.getValue(27304) // Ceinture Pimentée: 312 elemental mastery, max AP ≤ 11
        val plainBelt = item(9110, ItemType.BELT, mapOf(Characteristic.MASTERY_ELEMENTARY to 100))
        val apItems =
            listOf(
                item(9111, ItemType.AMULET, mapOf(Characteristic.ACTION_POINT to 3)),
                item(9112, ItemType.HELMET, mapOf(Characteristic.MAX_ACTION_POINT to 2)),
                item(9113, ItemType.CAPE, mapOf(Characteristic.ACTION_POINT to 1))
            )
        val pool = listOf(belt, plainBelt) + apItems
        val p = params(listOf(TargetStat(Characteristic.MASTERY_ELEMENTARY_FIRE, 1), TargetStat(Characteristic.ACTION_POINT, 12)), level = 1)
        val gated = solve(p, pool)
        assertThat(violations(gated)).isEmpty()
        // The belt may stay — at 11 AP, the target missed (the soft leg trades it for the belt's mastery) — never at 12.
        if (belt in gated.equipments) assertThat(outOfCombatSheet(gated, CharacterClass.CRA).getValue(Characteristic.ACTION_POINT)).isLessThanOrEqualTo(11)
        val free = solve(p, stripped(pool))
        assertThat(free.equipments.map { it.equipmentId }).contains(belt.equipmentId)
        assertThat(violations(free)).isNotEmpty()
    }

    @Test
    fun `a permanent sublimation's range counts in the model, a start-of-combat one does not`() {
        val visibility = subs.single { it.name.fr == "Visibilité II" }
        val abandon = subs.single { it.name.fr == "Abandon II" }
        // Cartes And + two range items = 3 range; a forced sub with +1 range decides whether Cartes And stays legal.
        val socketed = listOf(cartesAnd, rangeHelmet, rangeAmulet, needle).map { it.copy(maxShardSlots = 4) }
        for ((sub, allThree) in listOf(visibility to false, abandon to true)) {
            val p =
                params(listOf(TargetStat(Characteristic.MASTERY_DISTANCE, 1)), useSublimations = false)
                    .copy(forcedSublimations = listOf(sub.name.fr))
            val built =
                runBlocking {
                    WakfuBuildSolver
                        .optimize(p, socketed.groupBy { it.itemType }, emptyList(), listOf(sub), tuning)
                        .toList()
                        .last()
                        .individual
                }
            assertThat(built.sublimations.values.flatten()).describedAs("%s is forced", sub.name.fr).contains(sub)
            assertThat(violations(built)).describedAs("%s", sub.name.fr).isEmpty()
            assertThat(built.equipments.map { it.equipmentId }.containsAll(listOf(cartesAnd, rangeHelmet, rangeAmulet).map { it.equipmentId }))
                .describedAs("%s: Cartes And + the two range items (3 range) stay legal iff the sub adds no out-of-combat range", sub.name.fr)
                .isEqualTo(allThree)
        }
    }

    @Test
    fun `runes count in the model`() {
        val distanceRune = WakfuBestBuildFinderAlgorithm.runes.single { it.characteristic == Characteristic.MASTERY_DISTANCE }
        // The gated helmet is worth 1000 fire but caps distance mastery at 100; the carriers' runes could push it past.
        val gated =
            item(
                9120,
                ItemType.HELMET,
                mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 1000, Characteristic.MASTERY_DISTANCE to 40),
                gate(Characteristic.MASTERY_DISTANCE, CriterionComparison.LE, 100)
            )
        val plain = item(9121, ItemType.HELMET, mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 10))
        val carriers =
            listOf(ItemType.AMULET, ItemType.CAPE, ItemType.BOOTS).mapIndexed { i, type ->
                item(9122 + i, type, mapOf(Characteristic.MASTERY_DISTANCE to 20), level = 200, sockets = 4)
            }
        val pool = listOf(gated, plain) + carriers
        val p = params(listOf(TargetStat(Characteristic.MASTERY_ELEMENTARY_FIRE, 1), TargetStat(Characteristic.MASTERY_DISTANCE, 1)), useRunes = true)
        val built = solve(p, pool, listOf(distanceRune))
        assertThat(statGateViolations(built, CharacterClass.CRA)).isEmpty()
        assertThat(built.equipments).contains(gated)
        val free = solve(p, stripped(pool), listOf(distanceRune))
        assertThat(free.runes.values.flatten()).isNotEmpty()
        assertThat(statGateViolations(free.copy(equipments = free.equipments.map { if (it.equipmentId == gated.equipmentId) gated else it }), CharacterClass.CRA))
            .describedAs("without the gate, the runes push the distance mastery past 100")
            .isNotEmpty()
    }

    @Test
    fun `real catalog - an item gated crit above -10 is never worn at -10 crit or below`() {
        // Le Jouik Krampe (−10 crit) and Amulette du Zinit (−5 crit) together put a level-153 sheet at 3 − 15 = −12.
        val jouik = catalog.getValue(4224)
        val zinit = catalog.getValue(11410)
        assertThat(jouik.equipCriterion!!.statGates).contains(gate(Characteristic.CRITICAL_HIT, CriterionComparison.GT, -10))
        assertThat(statGateViolations(build(jouik, zinit, level = 153), CharacterClass.CRA)).hasSize(2)
        assertThat(statGateViolations(build(jouik, level = 153), CharacterClass.CRA)).isEmpty()
        val pool =
            listOf(
                jouik,
                zinit,
                item(9130, ItemType.HELMET, mapOf(Characteristic.MASTERY_ELEMENTARY to 5)),
                item(9131, ItemType.AMULET, mapOf(Characteristic.MASTERY_ELEMENTARY to 5))
            )
        val built = solve(params(listOf(TargetStat(Characteristic.MASTERY_ELEMENTARY_FIRE, 1)), level = 153), pool)
        assertThat(violations(built)).isEmpty()
        assertThat(outOfCombatSheet(built, CharacterClass.CRA).getValue(Characteristic.CRITICAL_HIT)).isGreaterThan(-10)
        // Both are worn: the Luck skill points lift the sheet back above −10 — the model reads the skills like the game does.
        if (built.equipments.containsAll(listOf(jouik, zinit))) {
            assertThat(built.characterSkills.allCharacteristicValues.fixedValues[Characteristic.CRITICAL_HIT] ?: 0).isGreaterThanOrEqualTo(3)
        }
    }

    // ---- validity of everything else the engine returns ----

    @Test
    fun `the greedy warm start swaps a gated pick its build breaks for an ungated one`() {
        val gatedBelt = item(9140, ItemType.BELT, mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 1000), gate(Characteristic.ACTION_POINT, CriterionComparison.LE, 11, max = true))
        val plainBelt = item(9141, ItemType.BELT, mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 100))
        val apAmulet = item(9142, ItemType.AMULET, mapOf(Characteristic.ACTION_POINT to 6))
        val p = params(listOf(TargetStat(Characteristic.MASTERY_ELEMENTARY_FIRE, 1), TargetStat(Characteristic.ACTION_POINT, 12)), level = 1)
        val greedy = MostMasteriesWarmStart.greedyBuild(p, listOf(gatedBelt, plainBelt, apAmulet))
        assertThat(greedy).describedAs("the repair keeps the warm start alive (it used to cancel on an invalid build)").isNotNull
        assertThat(greedy!!.equipments).containsExactlyInAnyOrder(plainBelt, apAmulet)
        assertThat(greedy.isValid(CharacterClass.CRA)).isTrue()
        // When the gate holds, the gated belt stays.
        val noAp = MostMasteriesWarmStart.greedyBuild(p, listOf(gatedBelt, plainBelt))
        assertThat(noAp!!.equipments).containsExactly(gatedBelt)
    }

    @Test
    fun `domination - a gated item never evicts an ungated one, and an upper-gated stat is never raised by a swap`() {
        val p = params(listOf(TargetStat(Characteristic.MASTERY_ELEMENTARY_FIRE, 1)))
        val shape = requireNotNull(dominationShape(p, emptyList()))
        // Same slot: the gated helmet is better on every stat, yet its gate may fail where the plain one is free.
        val gatedHelmet = item(9150, ItemType.HELMET, mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 100), gate(Characteristic.RANGE, CriterionComparison.LE, 1))
        val plainHelmet = item(9151, ItemType.HELMET, mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 90))
        // Across slots: an amulet with more range would evict the other one — unless a worn item's gate caps the range.
        val rangeAmulet = item(9152, ItemType.AMULET, mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 50, Characteristic.RANGE to 1))
        val plainAmulet = item(9153, ItemType.AMULET, mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 40))
        val kept = filterDominatedPool(listOf(gatedHelmet, plainHelmet, rangeAmulet, plainAmulet).groupBy { it.itemType }, shape)
        assertThat(kept.getValue(ItemType.HELMET)).containsExactlyInAnyOrder(gatedHelmet, plainHelmet)
        assertThat(kept.getValue(ItemType.AMULET)).containsExactlyInAnyOrder(rangeAmulet, plainAmulet)
        // Without any gate the pool collapses to the best of each slot.
        val ungated = filterDominatedPool(stripped(listOf(gatedHelmet, plainHelmet, rangeAmulet, plainAmulet)).groupBy { it.itemType }, shape)
        assertThat(ungated.values.flatten().map { it.equipmentId }).containsExactlyInAnyOrder(9150, 9152)
        // A lower gate ("lock ≥ 10" on a helmet) keeps an amulet with more lock from being evicted by one with less.
        val lockHelmet = item(9154, ItemType.HELMET, mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 100), gate(Characteristic.LOCK, CriterionComparison.GE, 10))
        val lockAmulet = item(9155, ItemType.AMULET, mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 40, Characteristic.LOCK to 10))
        val mdShape = requireNotNull(dominationShape(params(emptyList(), mode = ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE), emptyList()))
        val mdKept =
            filterDominatedPool(
                listOf(lockHelmet, plainAmulet.copy(characteristics = mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 50)), lockAmulet).groupBy { it.itemType },
                mdShape
            )
        assertThat(mdKept.getValue(ItemType.AMULET).map { it.equipmentId }).contains(9155)
    }

    @Test
    fun `the E8 construct only crowns a build that keeps every gate`(): Unit =
        runBlocking {
            // A huge-fire helmet capped at max AP 7 and a +2 AP amulet: the certificate (which ignores the gates) bounds the pair at
            // 8 AP, a build the game refuses. Whatever the construct returns must keep the gate.
            val mdParams = params(emptyList(), level = 50, mode = ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE)
            val helmet =
                item(9160, ItemType.HELMET, mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 3000), gate(Characteristic.ACTION_POINT, CriterionComparison.LE, 7, max = true))
            val pool =
                listOf(
                    helmet,
                    item(9161, ItemType.HELMET, mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 1200)),
                    item(9162, ItemType.AMULET, mapOf(Characteristic.ACTION_POINT to 2)),
                    item(9163, ItemType.AMULET, mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 100))
                )
            val constructed = WakfuBuildSolver.dpConstructProvenOptimum(mdParams, pool.groupBy { it.itemType })
            if (constructed != null) {
                assertThat(constructed.individual.isValid(CharacterClass.CRA)).isTrue()
                assertThat(statGateViolations(constructed.individual, CharacterClass.CRA)).isEmpty()
            }
            // Stripped, the construct crowns exactly the pair the game refuses: the gate is what stops it.
            val free = WakfuBuildSolver.dpConstructProvenOptimum(mdParams, stripped(pool).groupBy { it.itemType })
            assertThat(free).isNotNull
            assertThat(free!!.individual.equipments.map { it.equipmentId }).containsExactlyInAnyOrder(9160, 9162)
        }

    // ---- request validation ----

    @Test
    fun `a forced gated item whose gate caps a target row is reported`() {
        val forced = params(listOf(TargetStat(Characteristic.RANGE, 4)), forced = listOf(cartesAnd.name.fr))
        val problems = WakfuBestBuildFinderAlgorithm.validateRequest(forced)
        assertThat(problems.filterIsInstance<RequestValidationProblem.ForcedItemStatGateContradictsTarget>())
            .singleElement()
            .satisfies({ assertThat(it.target).isEqualTo(4) })
        assertThat(problems.single { it is RequestValidationProblem.ForcedItemStatGateContradictsTarget }.describe())
            .contains("Art'And Cards", "RANGE <= 3", "4")
        // A target the gate allows, or a sublimation that adds range in combat (Abandon II), is no contradiction.
        assertThat(WakfuBestBuildFinderAlgorithm.validateRequest(forced.copy(targetStats = TargetStats(listOf(TargetStat(Characteristic.RANGE, 3))))))
            .noneMatch { it is RequestValidationProblem.ForcedItemStatGateContradictsTarget }
        assertThat(WakfuBestBuildFinderAlgorithm.validateRequest(forced.copy(useSublimations = true)))
            .noneMatch { it is RequestValidationProblem.ForcedItemStatGateContradictsTarget }
    }
}
