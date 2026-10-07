package me.chosante.autobuilder.genetic.wakfu

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.autobuilder.domain.TargetStat
import me.chosante.autobuilder.domain.TargetStats
import me.chosante.autobuilder.domain.exclusiveGroupViolation
import me.chosante.common.Character
import me.chosante.common.CharacterClass
import me.chosante.common.Characteristic
import me.chosante.common.Equipment
import me.chosante.common.ExclusiveGroup
import me.chosante.common.I18nText
import me.chosante.common.ItemType
import me.chosante.common.Rarity
import me.chosante.common.SublimationRarity
import me.chosante.common.skills.CharacterSkills
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/**
 * The game's "only one equipped at a time" item groups ([ExclusiveGroup], the CDN item properties 12 and 8): the EPIC group
 * is every EPIC item PLUS two COMMON ones (18691 Piquants du Guerrier Trool anciens, AP +1; 18693 Sain Turastil ancienne,
 * MP +1), so the game refuses either beside an epic item or beside each other. The budget follows the group in the CP-SAT
 * model, `isValid`, the greedy warm start, the request validation, the domination pre-filter and the reloaded-save check;
 * the epic SUBLIMATION carrier stays the rarity. Synthetic pools (ids ≥ 9500) for the rules, the real catalog for the data.
 */
class ExclusiveGroupTest {
    private val tuning =
        WakfuBuildSolver.SolverTuning(numSearchWorkers = 1, randomSeed = 1, interleaveSearch = true, maxDeterministicTime = 30.0)
    private val tunedWithDomination = tuning.copy(applyDominationOverride = true)

    private fun item(
        id: Int,
        type: ItemType,
        fire: Int,
        rarity: Rarity = Rarity.LEGENDARY,
        name: String = "item$id",
        group: ExclusiveGroup? = null,
        extra: Map<Characteristic, Int> = emptyMap(),
    ) = Equipment(
        equipmentId = id,
        guiId = id,
        level = 1,
        name = I18nText(name, name, name, name),
        rarity = rarity,
        itemType = type,
        characteristics = (if (fire == 0) emptyMap() else mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to fire)) + extra,
        exclusiveGroupOverride = group
    )

    private fun params(
        level: Int = 1,
        forced: List<String> = emptyList(),
        forcedSublimations: List<String> = emptyList(),
        clazz: CharacterClass = CharacterClass.IOP,
    ) = WakfuBestBuildParams(
        character = Character(clazz, level, 0, CharacterSkills(level)),
        targetStats = TargetStats(listOf(TargetStat(Characteristic.MASTERY_ELEMENTARY_FIRE, 1))),
        searchDuration = 10.seconds,
        stopWhenBuildMatch = false,
        maxRarity = Rarity.EPIC,
        forcedItems = forced,
        excludedItems = emptyList(),
        scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT,
        useRunes = false,
        useSublimations = forcedSublimations.isNotEmpty(),
        forcedSublimations = forcedSublimations
    )

    // The 18691 shape: a COMMON shoulder pad of the EPIC group, the best of its slot; a strong EPIC amulet; plain alternatives.
    private val trool = item(9501, ItemType.SHOULDER_PADS, 500, Rarity.COMMON, "trool", ExclusiveGroup.EPIC)
    private val shoulder = item(9502, ItemType.SHOULDER_PADS, 300, name = "shoulder")
    private val epicAmulet = item(9503, ItemType.AMULET, 900, Rarity.EPIC, "epicAmulet")
    private val amulet = item(9504, ItemType.AMULET, 100, name = "amulet")

    // The 18693 shape: a COMMON belt of the EPIC group.
    private val sain = item(9505, ItemType.BELT, 400, Rarity.COMMON, "sain", ExclusiveGroup.EPIC)
    private val belt = item(9506, ItemType.BELT, 350, name = "belt")
    private val relicCape = item(9507, ItemType.CAPE, 600, Rarity.RELIC, "relicCape")

    private fun pool(vararg items: Equipment) = items.groupBy { it.itemType }

    private fun bestBuild(
        p: WakfuBestBuildParams,
        pool: Map<ItemType, List<Equipment>>,
        t: WakfuBuildSolver.SolverTuning = tuning,
    ): BuildCombination =
        runBlocking {
            WakfuBuildSolver
                .optimize(p, pool, emptyList(), WakfuBestBuildFinderAlgorithm.sublimations, t)
                .toList()
                .last()
                .individual
        }

    private fun BuildCombination.ids() = equipments.map { it.equipmentId }.toSet()

    @Test
    fun `an item's group follows its rarity unless the data says otherwise`() {
        assertThat(item(1, ItemType.AMULET, 1, Rarity.EPIC).exclusiveGroup).isEqualTo(ExclusiveGroup.EPIC)
        assertThat(item(1, ItemType.AMULET, 1, Rarity.RELIC).exclusiveGroup).isEqualTo(ExclusiveGroup.RELIC)
        for (rarity in Rarity.entries - Rarity.EPIC - Rarity.RELIC) {
            assertThat(item(1, ItemType.AMULET, 1, rarity).exclusiveGroup).isEqualTo(ExclusiveGroup.NONE)
        }
        assertThat(trool.exclusiveGroup).isEqualTo(ExclusiveGroup.EPIC)
        // A copy under another rarity follows the new rarity; an explicit group survives the copy.
        assertThat(item(1, ItemType.AMULET, 1).copy(rarity = Rarity.EPIC).exclusiveGroup).isEqualTo(ExclusiveGroup.EPIC)
        assertThat(trool.copy(rarity = Rarity.RARE).exclusiveGroup).isEqualTo(ExclusiveGroup.EPIC)
        assertThat(item(1, ItemType.AMULET, 1, Rarity.EPIC, group = ExclusiveGroup.NONE).exclusiveGroup).isEqualTo(ExclusiveGroup.NONE)
    }

    @Test
    fun `real catalog - the EPIC group is the 115 epics plus 18691 and 18693, the RELIC group the 99 relics`() {
        val catalog = WakfuBestBuildFinderAlgorithm.equipments
        val epicGroup = catalog.filter { it.exclusiveGroup == ExclusiveGroup.EPIC }
        assertThat(catalog.count { it.rarity == Rarity.EPIC }).isEqualTo(115)
        assertThat(epicGroup.filter { it.rarity == Rarity.EPIC }).hasSize(115)
        assertThat(epicGroup.filter { it.rarity != Rarity.EPIC }.map { it.equipmentId to it.rarity })
            .containsExactlyInAnyOrder(18691 to Rarity.COMMON, 18693 to Rarity.COMMON)
        val relicGroup = catalog.filter { it.exclusiveGroup == ExclusiveGroup.RELIC }
        assertThat(relicGroup).hasSize(99)
        assertThat(relicGroup.map { it.equipmentId }).containsExactlyInAnyOrderElementsOf(catalog.filter { it.rarity == Rarity.RELIC }.map { it.equipmentId })
        // Only the two exceptions carry the field in equipments.json; every other item follows its rarity.
        assertThat(catalog.filter { it.exclusiveGroupOverride != null }.map { it.equipmentId }).containsExactlyInAnyOrder(18691, 18693)
        // 18691 is the only AP shoulder pad outside the epic / relic budgets before this rule — why the engine liked it.
        val trool = catalog.single { it.equipmentId == 18691 }
        assertThat(trool.name.fr).isEqualTo("Piquants du Guerrier Trool anciens")
        assertThat(trool.characteristics[Characteristic.ACTION_POINT]).isEqualTo(1)
        assertThat(catalog.single { it.equipmentId == 18693 }.name.fr).isEqualTo("Sain Turastil ancienne")
    }

    @Test
    fun `the solver never wears an EPIC-group common item beside an epic item, or beside the other one`() {
        for (t in listOf(tuning, tunedWithDomination)) {
            // Unconstrained, trool + epic amulet (1 400) wins; trool takes the epic budget, so shoulder + epic amulet (1 200).
            assertThat(bestBuild(params(), pool(trool, shoulder, epicAmulet, amulet), t).ids()).containsExactlyInAnyOrder(9502, 9503)
            // Without an epic around, trool is worn (500 > 300) — beside the RELIC cape: a different group.
            assertThat(bestBuild(params(), pool(trool, shoulder, amulet, relicCape), t).ids()).containsExactlyInAnyOrder(9501, 9504, 9507)
            // The two EPIC-group commons never together: trool + belt (850) beats shoulder + sain (700).
            assertThat(bestBuild(params(), pool(trool, shoulder, sain, belt), t).ids()).containsExactlyInAnyOrder(9501, 9506)
        }
    }

    @Test
    fun `an EPIC-group common item hosts no epic sublimation - the carrier is the rarity`() {
        val epicSub = WakfuBestBuildFinderAlgorithm.sublimations.single { it.name.fr == "Santé de fer" }
        assertThat(epicSub.rarity).isEqualTo(SublimationRarity.EPIC)
        // Free, trool + amulet (600) beats shoulder + epic amulet (500 — the amulet is weak here). Forcing the epic sub needs an
        // EPIC-RARITY carrier: the epic amulet is worn, so trool — in the epic group — can't be.
        val weakEpicAmulet = item(9508, ItemType.AMULET, 200, Rarity.EPIC, "weakEpicAmulet")
        val p = pool(trool, shoulder, weakEpicAmulet, amulet)
        assertThat(bestBuild(params(), p).ids()).containsExactlyInAnyOrder(9501, 9504)
        val forced = bestBuild(params(forcedSublimations = listOf(epicSub.name.fr)), p)
        assertThat(forced.ids()).containsExactlyInAnyOrder(9502, 9508)
        assertThat(forced.sublimations[weakEpicAmulet].orEmpty()).contains(epicSub)
        assertThat(forced.isValid(CharacterClass.IOP)).isTrue()
        // isValid refuses the sub on the COMMON item.
        val onTrool = BuildCombination(listOf(trool, amulet), CharacterSkills(1), sublimations = mapOf(trool to listOf(epicSub)))
        assertThat(onTrool.isValid()).isFalse()
    }

    @Test
    fun `isValid counts the groups, not the rarities`() {
        val skills = CharacterSkills(1)

        fun valid(vararg items: Equipment) = BuildCombination(items.toList(), skills).isValid(CharacterClass.IOP)
        assertThat(valid(trool, amulet, relicCape)).isTrue()
        assertThat(valid(trool, epicAmulet)).isFalse()
        assertThat(valid(trool, sain)).isFalse()
        assertThat(valid(sain, epicAmulet)).isFalse()
        assertThat(valid(shoulder, epicAmulet, relicCape)).isTrue()
        assertThat(exclusiveGroupViolation(listOf(trool, epicAmulet))).contains("trool", "epicAmulet", "EPIC")
        // An EPIC item the data puts in no group pairs freely (none in today's data — the rule follows the data, not the rarity).
        val freeEpic = item(9509, ItemType.CAPE, 10, Rarity.EPIC, "freeEpic", ExclusiveGroup.NONE)
        assertThat(valid(freeEpic, epicAmulet)).isTrue()
    }

    @Test
    fun `the greedy warm start repairs an over-budget EPIC group`() {
        // Greedy picks trool and the epic amulet (each its slot's best); the repair drops the cheaper loss: trool → shoulder.
        val build = MostMasteriesWarmStart.greedyBuild(params(), listOf(trool, shoulder, epicAmulet, amulet))!!
        assertThat(build.ids()).containsExactlyInAnyOrder(9502, 9503)
        // Both commons of the group: trool (500 vs 300) stays, sain goes to the plain belt.
        val commons = MostMasteriesWarmStart.greedyBuild(params(), listOf(trool, shoulder, sain, belt))!!
        assertThat(commons.ids()).containsExactlyInAnyOrder(9501, 9506)
        assertThat(listOf(build, commons)).allMatch { it.isValid(CharacterClass.IOP) }
    }

    @Test
    fun `request validation counts a forced EPIC-group common item in the epic budget`() {
        val all = listOf(trool, shoulder, epicAmulet, amulet, sain, belt, relicCape)
        assertThat(WakfuBestBuildFinderAlgorithm.validateRequest(params(forced = listOf("trool", "epicAmulet")), all))
            .containsExactly(RequestValidationProblem.ForcedItemRarityBudgetExceeded(Rarity.EPIC, listOf(trool.name, epicAmulet.name)))
        assertThat(WakfuBestBuildFinderAlgorithm.validateRequest(params(forced = listOf("trool", "sain")), all))
            .containsExactly(RequestValidationProblem.ForcedItemRarityBudgetExceeded(Rarity.EPIC, listOf(trool.name, sain.name)))
        assertThat(WakfuBestBuildFinderAlgorithm.validateRequest(params(forced = listOf("trool", "relicCape")), all)).isEmpty()
        // Real catalog: 18691 + an epic amulet at level 200.
        val p = params(level = 200, forced = listOf("Piquants du Guerrier Trool anciens", "Amulette Excrue"))
        assertThat(WakfuBestBuildFinderAlgorithm.validateRequest(p).filterIsInstance<RequestValidationProblem.ForcedItemRarityBudgetExceeded>())
            .singleElement()
            .matches { it.rarity == Rarity.EPIC && it.items.map { n -> n.fr }.toSet() == setOf("Piquants du Guerrier Trool anciens", "Amulette Excrue") }
    }

    @Test
    fun `domination - an item that takes the epic budget never evicts one that does not`() {
        // trool ≥ shoulder on every stat, but it takes the epic budget shoulder leaves free: shoulder must stay.
        val shape = DominationShape(pinned = emptySet())
        assertThat(filterDominatedPool(pool(trool, shoulder, epicAmulet, amulet), shape).getValue(ItemType.SHOULDER_PADS)).contains(shoulder)
        // A plain item ≥ an EPIC-group common evicts it (no sub carrier modelled): the swap frees the budget, never spends it.
        val weakTrool = item(9510, ItemType.SHOULDER_PADS, 200, Rarity.COMMON, "weakTrool", ExclusiveGroup.EPIC)
        assertThat(filterDominatedPool(pool(weakTrool, shoulder), shape).getValue(ItemType.SHOULDER_PADS)).containsExactly(shoulder)
        // Within the group, the stronger member evicts the weaker one.
        val epicShoulder = item(9511, ItemType.SHOULDER_PADS, 100, Rarity.EPIC, "epicShoulder")
        assertThat(filterDominatedPool(pool(trool, epicShoulder), shape).getValue(ItemType.SHOULDER_PADS)).containsExactly(trool)
        // ... unless an epic sub is modelled: the EPIC item is a sub carrier the COMMON one is not.
        assertThat(filterDominatedPool(pool(trool, epicShoulder), shape.copy(epicCarriers = true)).getValue(ItemType.SHOULDER_PADS))
            .containsExactlyInAnyOrder(trool, epicShoulder)
    }

    @Test
    fun `a reloaded save - which carries no group - is checked against the catalog's`() {
        val catalog = WakfuBestBuildFinderAlgorithm.equipments
        val trool = catalog.single { it.equipmentId == 18691 }
        val epic = catalog.first { it.rarity == Rarity.EPIC && it.itemType == ItemType.AMULET }
        // A build saved before the groups were read: the item JSON has no `exclusiveGroup`, so it follows its rarity.
        val saved = trool.copy(exclusiveGroupOverride = null)
        assertThat(saved.exclusiveGroup).isEqualTo(ExclusiveGroup.NONE)
        val build = BuildCombination(listOf(saved, epic), CharacterSkills(200))
        assertThat(build.isValid(CharacterClass.IOP)).describedAs("the save alone can't tell").isTrue()
        assertThat(WakfuBestBuildFinderAlgorithm.equipConditionViolation(build, CharacterClass.IOP)).isNotNull().contains("Piquants du Guerrier Trool anciens")
        assertThat(WakfuBestBuildFinderAlgorithm.equipConditionViolation(BuildCombination(listOf(saved), CharacterSkills(200)), CharacterClass.IOP)).isNull()
    }

    /**
     * Real data, CP-SAT: the level-95 AP items of three slots (shoulder pads, amulets, capes) under a precision request for
     * more AP than any build reaches — the solver maximizes AP. 18691 is the only AP shoulder pad outside the epic and relic
     * budgets on the CDN rarity, so without its group the best build wears it beside an AP +2 epic and an AP +2 relic; with
     * the group it costs the epic slot, and the best legal build has one AP less and never pairs it with an epic.
     */
    @Test
    fun `real catalog - the AP-maximizing build never pairs 18691 with an epic item`() {
        val p =
            params(level = 95).copy(
                targetStats = TargetStats(listOf(TargetStat(Characteristic.ACTION_POINT, 30))),
                scoreComputationMode = ScoreComputationMode.FIND_CLOSEST_BUILD_FROM_INPUT
            )
        // 18691's own stat gate (max AP ≤ 11, StatGatesTest) would keep it out of any AP-maximizing build: lifted here, so the
        // test isolates the exclusivity-group rule.
        val apPool =
            WakfuBestBuildFinderAlgorithm
                .poolFor(p)
                .filterKeys { it in setOf(ItemType.SHOULDER_PADS, ItemType.AMULET, ItemType.CAPE) }
                .mapValues { (_, items) ->
                    items
                        .filter { (it.characteristics[Characteristic.ACTION_POINT] ?: 0) > 0 }
                        .map { item -> item.copy(equipCriterion = item.equipCriterion?.copy(statGates = emptyList())) }
                }
        assertThat(apPool.getValue(ItemType.SHOULDER_PADS).map { it.equipmentId }).contains(18691)
        val stripped = apPool.mapValues { (_, items) -> items.map { if (it.equipmentId == 18691) it.copy(exclusiveGroupOverride = ExclusiveGroup.NONE) else it } }

        fun ap(build: BuildCombination) = build.equipments.sumOf { it.characteristics[Characteristic.ACTION_POINT] ?: 0 }
        val free = bestBuild(p, stripped)
        assertThat(free.ids()).describedAs("without the group, 18691 rides beside an epic").contains(18691)
        assertThat(free.equipments.map { it.rarity }).contains(Rarity.EPIC, Rarity.RELIC)
        val legal = bestBuild(p, apPool)
        assertThat(legal.isValid(CharacterClass.IOP)).isTrue()
        assertThat(legal.equipments.count { it.exclusiveGroup == ExclusiveGroup.EPIC }).isLessThanOrEqualTo(1)
        assertThat(ap(legal)).isEqualTo(ap(free) - 1)
    }
}
