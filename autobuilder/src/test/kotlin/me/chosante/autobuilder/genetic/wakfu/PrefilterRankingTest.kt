package me.chosante.autobuilder.genetic.wakfu

import me.chosante.autobuilder.domain.TargetStat
import me.chosante.autobuilder.domain.TargetStats
import me.chosante.common.Character
import me.chosante.common.CharacterClass
import me.chosante.common.Characteristic
import me.chosante.common.Equipment
import me.chosante.common.I18nText
import me.chosante.common.ItemType
import me.chosante.common.Rarity
import me.chosante.common.skills.CharacterSkills
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Which items the multi-element item prefilter keeps (no solve — pool membership only). It keeps, per slot, the top 8 items
 * of every requested characteristic plus the forced items, and since the combined-mastery ranking it breaks ties on a
 * characteristic's value by an item's mastery across ALL the wanted elements, and also keeps the top 8 by that score: the
 * well-rounded item (395 random-element + 395 distance mastery) that is the best on no single stat is what a multi-element
 * optimum wears. That a prefiltered request never earns an optimality badge is locked in [PrefilterOptimalityTest].
 */
class PrefilterRankingTest {
    private val fire = Characteristic.MASTERY_ELEMENTARY_FIRE
    private val water = Characteristic.MASTERY_ELEMENTARY_WATER

    private fun params(
        rows: List<TargetStat>,
        character: Character = Character(CharacterClass.CRA, 1, 1, CharacterSkills(1)),
        forcedItems: List<String> = emptyList(),
    ) = WakfuBestBuildParams(
        character = character,
        targetStats = TargetStats(rows),
        searchDuration = 5.seconds,
        stopWhenBuildMatch = false,
        maxRarity = Rarity.EPIC,
        forcedItems = forcedItems,
        excludedItems = emptyList(),
        scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT,
        useRunes = false,
        useSublimations = false
    )

    private fun equipment(
        id: Int,
        slot: ItemType,
        characteristics: Map<Characteristic, Int>,
        name: String = "item$id",
    ) = Equipment(
        equipmentId = id,
        guiId = id,
        level = 1,
        name = I18nText(name, name, "", ""),
        rarity = Rarity.LEGENDARY,
        itemType = slot,
        characteristics = characteristics,
        maxShardSlots = 0
    )

    private fun keptIds(
        p: WakfuBestBuildParams,
        pool: List<Equipment>,
        slot: ItemType,
    ): List<Int> {
        assertThat(WakfuBuildSolver.needsItemPrefilter(p.targetStats)).describedAs("the request trips the prefilter").isTrue()
        return WakfuBuildSolver
            .prefilteredPoolForTest(p, pool.groupBy { it.itemType })
            .getValue(slot)
            .map { it.equipmentId }
    }

    private val fireAndWater = listOf(TargetStat(fire, 1800), TargetStat(water, 1800))

    // Eight fire-only and eight water-only items (1 001..1 008 each): every one beats a balanced 900 / 900 item on its own
    // stat, but none reaches its 1 800 in total.
    private fun specialists(
        slot: ItemType,
        firstId: Int,
    ) = (1..8).map { equipment(firstId + it, slot, mapOf(fire to 1000 + it)) } +
        (1..8).map { equipment(firstId + 10 + it, slot, mapOf(water to 1000 + it)) }

    @Test
    fun `a well-rounded item ranked ninth on every stat is kept for its combined mastery`() {
        // The balanced ring is 9th on fire (900 < 1 001..1 008) and 9th on water, yet it holds 1 800 of the mastery the
        // optimum is judged on (the minimum over the two elements), more than any specialist's ~1 000: the combined
        // ranking puts it first. Ranked on single stats alone it was lost, and with it the better build.
        val balanced = equipment(99, ItemType.RING, mapOf(fire to 900, water to 900), name = "balanced")
        val pool = specialists(ItemType.RING, 100) + balanced

        val kept = keptIds(params(fireAndWater), pool, ItemType.RING)

        assertThat(kept).contains(99)
        // The rest of the pool is what the per-stat tops keep: eight fire items and eight water items.
        assertThat(kept).hasSize(17)
    }

    @Test
    fun `a tie on a stat is cut by combined mastery, not by pool order`() {
        // Ten rings carry the same +1 AP, so the AP cut at 8 falls inside a tie group. Nine plain rings come first in
        // the pool and the balanced one (+300 fire, +300 water) last; it is 9th on fire and water behind the specialists
        // and out of the combined top 8 too (600 against their 1 001..1 008), so only the AP tie decides. By value alone
        // the first eight plain rings won and it was lost; now it leads the tie and pool order only decides the plain rings.
        val ap = Characteristic.ACTION_POINT
        val plain = (1..9).map { equipment(it, ItemType.RING, mapOf(ap to 1)) }
        val balanced = equipment(10, ItemType.RING, mapOf(ap to 1, fire to 300, water to 300), name = "balanced")
        val pool = plain + specialists(ItemType.RING, 100) + balanced

        val kept = keptIds(params(fireAndWater + TargetStat(ap, 6)), pool, ItemType.RING)

        assertThat(kept).contains(10)
        assertThat(kept).describedAs("the stable cut keeps pool order among the plain rings").contains(1, 2, 3, 4, 5, 6, 7).doesNotContain(8, 9)
    }

    @Test
    fun `an item stays out when lopsided items out-sum it on every ranking`() {
        // The prefilter stays a heuristic: with eight fire-only items worth more than the balanced item's total, it is
        // 9th on fire, on water and on combined mastery alike, and is dropped (the global optimum may be lost, which is
        // why a prefiltered request never earns an optimality badge).
        val balanced = equipment(99, ItemType.RING, mapOf(fire to 900, water to 900), name = "balanced")
        val lopsided = (1..8).map { equipment(100 + it, ItemType.RING, mapOf(fire to 2000 + it)) }
        val waterOnly = (1..8).map { equipment(110 + it, ItemType.RING, mapOf(water to 1000 + it)) }

        val kept = keptIds(params(fireAndWater), lopsided + waterOnly + balanced, ItemType.RING)

        assertThat(kept).doesNotContain(99)
        assertThat(kept).hasSize(16)
    }

    @Test
    fun `without an elemental mastery row ties still fall back to pool order`() {
        // Resistance rows only: no wanted element, so the combined score is zero for every item and neither the tie-break
        // nor the extra ranking applies. Ten rings tie on fire resistance; the first eight in pool order stay, the last
        // (which carries a mastery nobody asked for) does not.
        val fireResistance = Characteristic.RESISTANCE_ELEMENTARY_FIRE
        val tied = (1..9).map { equipment(it, ItemType.RING, mapOf(fireResistance to 50)) }
        val masteryBearing = equipment(10, ItemType.RING, mapOf(fireResistance to 50, fire to 900, water to 900))
        val rows = listOf(TargetStat(fireResistance, 10), TargetStat(Characteristic.RESISTANCE_ELEMENTARY_WATER, 10))

        val kept = keptIds(params(rows), tied + masteryBearing, ItemType.RING)

        assertThat(kept).containsExactlyInAnyOrder(1, 2, 3, 4, 5, 6, 7, 8)
    }

    @Test
    fun `forced items stay whatever their stats, and a slot with nothing relevant is kept whole`() {
        val forcedRing = equipment(1, ItemType.RING, emptyMap(), name = "Forced Ring")
        val fillers = (2..20).map { equipment(it, ItemType.RING, mapOf(fire to 10 * it)) }
        val p = params(fireAndWater, forcedItems = listOf("forced ring"))
        assertThat(keptIds(p, listOf(forcedRing) + fillers, ItemType.RING)).contains(1)

        // Belts that carry no requested stat at all: nothing ranks, so the slot is returned as it came.
        val belts = (1..5).map { equipment(it, ItemType.BELT, mapOf(Characteristic.HP to 100 * it)) }
        assertThat(keptIds(params(fireAndWater), belts, ItemType.BELT)).containsExactly(1, 2, 3, 4, 5)
    }

    /**
     * Real data lock: a level-245 Zobal asking for wind + fire + distance mastery, 12 AP and 4 MP lost 3.7 % of its score to
     * the prefilter (the full-pool optimum, proven, 15 028 against 14 490): 'Couronne du roi seuleil' (395 random-element + 395
     * distance mastery) ranked 10th on random-element mastery and 12th on distance, the epic ring 'Tyra 'neau' 10th of 12
     * on the +1 AP tie, and the optimum wears both. All level-245 requests with a distance-mastery row of the 26 measured
     * lost 2–5 % that way. Items are named by their embedded-data ids (game data 1.93).
     */
    @Test
    fun `the prefilter keeps the well-rounded level-245 items the Zobal optimum wears`() {
        val p =
            params(
                listOf(
                    TargetStat(Characteristic.MASTERY_ELEMENTARY_WIND, 1),
                    TargetStat(fire, 1),
                    TargetStat(Characteristic.ACTION_POINT, 12),
                    TargetStat(Characteristic.MOVEMENT_POINT, 4),
                    TargetStat(Characteristic.MASTERY_DISTANCE, 1)
                ),
                character = Character(CharacterClass.ZOBAL, 245, 170, CharacterSkills(245))
            )
        assertThat(WakfuBuildSolver.needsItemPrefilter(p.targetStats)).isTrue()

        val kept = WakfuBuildSolver.prefilteredPoolForTest(p, WakfuBestBuildFinderAlgorithm.poolFor(p))

        fun idsIn(slot: ItemType) = kept.getValue(slot).map { it.equipmentId }
        assertThat(idsIn(ItemType.HELMET)).describedAs("Couronne du roi seuleil (helmet)").contains(31939)
        assertThat(idsIn(ItemType.RING)).describedAs("Tyra 'neau (epic ring)").contains(32087)
        assertThat(idsIn(ItemType.CHEST_PLATE)).describedAs("Plastronled (chest plate)").contains(31966)
        assertThat(idsIn(ItemType.BELT)).describedAs("Tour Hyste (belt)").contains(31952)
        assertThat(idsIn(ItemType.SHOULDER_PADS)).describedAs("Epaulettes à boutons (shoulder pads)").contains(32177)
    }
}
