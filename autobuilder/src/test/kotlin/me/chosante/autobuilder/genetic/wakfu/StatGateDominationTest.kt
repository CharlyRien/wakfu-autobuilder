package me.chosante.autobuilder.genetic.wakfu

import me.chosante.autobuilder.domain.TargetStat
import me.chosante.autobuilder.domain.TargetStats
import me.chosante.autobuilder.domain.holdsOn
import me.chosante.autobuilder.domain.sheetCharacteristic
import me.chosante.autobuilder.domain.statGates
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
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

/**
 * The domination pre-filter and the item STAT GATES (CERTIFIER_VERSION 58): a gate that can never fail is no gate
 * ([DominationShape.gateCanFail], the model's own reach test), and only the gates of the items that survive constrain the others
 * (the fixpoint of [filterDominatedPool]). Plus a fast, CP-SAT-free exhaustive lock: on seeded random gated pools, the best
 * valid build of a monotone objective over the reduced pool equals the one over the full pool.
 */
class StatGateDominationTest {
    private val mostMasteries =
        WakfuBestBuildParams(
            character = Character(CharacterClass.CRA, 200, 0, CharacterSkills(200)),
            targetStats = TargetStats(listOf(TargetStat(Characteristic.MASTERY_ELEMENTARY_FIRE, 1))),
            searchDuration = 20.seconds,
            stopWhenBuildMatch = false,
            maxRarity = Rarity.EPIC,
            forcedItems = emptyList(),
            excludedItems = emptyList(),
            scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT,
            useRunes = false,
            useSublimations = false
        )
    private val shape = requireNotNull(dominationShape(mostMasteries, emptyList()))

    private val fire = Characteristic.MASTERY_ELEMENTARY_FIRE
    private val critAboveMinus10 = ItemStatGate(Characteristic.CRITICAL_HIT, false, CriterionComparison.GT, -10)
    private val rangeAtMost3 = ItemStatGate(Characteristic.RANGE, false, CriterionComparison.LE, 3)

    private fun item(
        id: Int,
        type: ItemType,
        stats: Map<Characteristic, Int>,
        vararg gates: ItemStatGate,
    ) = Equipment(
        equipmentId = id,
        guiId = id,
        level = 200,
        name = I18nText("e$id", "e$id", "e$id", "e$id"),
        rarity = Rarity.LEGENDARY,
        itemType = type,
        characteristics = stats.filterValues { it != 0 },
        maxShardSlots = 0,
        equipCriterion = if (gates.isEmpty()) null else ItemEquipCriterion(id, "gate", statGates = gates.toList())
    )

    private fun keptIds(vararg items: Equipment): Set<Int> = filterDominatedPool(items.groupBy { it.itemType }, shape).values.flatten().mapTo(HashSet()) { it.equipmentId }

    @Test
    fun `the critical hit above -10 gate can never fail and the model agrees`() {
        assertThat(shape.gateCanFail(critAboveMinus10)).isFalse
        assertThat(shape.gateCanFail(ItemStatGate(Characteristic.CRITICAL_HIT, false, CriterionComparison.GT, -9))).isTrue
        assertThat(shape.gateCanFail(rangeAtMost3)).isTrue
        // The domination reach is the model's (shared helper), on the pre-sub variable's whole domain.
        assertThat(outOfCombatSheetReach(Characteristic.CRITICAL_HIT, UNKNOWN_PRE_SUB_REACH, 0L..0L).first).isEqualTo(MIN_OUT_OF_COMBAT_CRIT)
        // An out-of-combat extra that can lower crit (a permanent negative-crit sub) makes it a gate that can fail.
        assertThat(shape.copy(outOfCombatExtras = mapOf(Characteristic.CRITICAL_HIT to -STAT_ABS_MAX..0L)).gateCanFail(critAboveMinus10)).isTrue
        // Unknown extras: every gate is treated as one that can fail.
        assertThat(DominationShape(emptySet()).gateCanFail(critAboveMinus10)).isTrue
    }

    @Test
    fun `items gated by critical hit above -10 no longer block domination`() {
        // A crit-gated item evicts a weaker ungated one (its gate always holds: no gate)...
        val gatedStrong = item(1, ItemType.AMULET, mapOf(fire to 50), critAboveMinus10)
        val ungatedWeak = item(2, ItemType.AMULET, mapOf(fire to 10))
        assertThat(keptIds(gatedStrong, ungatedWeak)).containsExactly(1)
        // ...and its gate does not pin critical hit in the other slots: an amulet with more crit still evicts a weaker one.
        val helmet = item(3, ItemType.HELMET, mapOf(fire to 5), critAboveMinus10)
        val critAmulet = item(4, ItemType.AMULET, mapOf(fire to 30, Characteristic.CRITICAL_HIT to 5))
        val plainAmulet = item(5, ItemType.AMULET, mapOf(fire to 20))
        assertThat(keptIds(helmet, critAmulet, plainAmulet)).containsExactlyInAnyOrder(3, 4)
        // Under the conservative "unknown extras" shape the gate counts, so the gated item can't evict the ungated one (1.15).
        val unknown = shape.copy(outOfCombatExtras = null)
        assertThat(filterDominatedPool(listOf(gatedStrong, ungatedWeak).groupBy { it.itemType }, unknown).values.flatten().map { it.equipmentId })
            .containsExactlyInAnyOrder(1, 2)
    }

    @Test
    fun `the gates of a dominated item stop protecting the other slots`() {
        // The helmet's `range ≤ 3` gate keeps the low-range amulet (a swap to the ranged one could break it)...
        val gatedHelmet = item(1, ItemType.HELMET, mapOf(fire to 10), rangeAtMost3)
        val lowRange = item(2, ItemType.AMULET, mapOf(fire to 10))
        val ranged = item(3, ItemType.AMULET, mapOf(fire to 20, Characteristic.RANGE to 1))
        assertThat(keptIds(gatedHelmet, lowRange, ranged)).containsExactlyInAnyOrder(1, 2, 3)
        // ...but once an ungated helmet dominates it, no build wears that gate: the amulet goes too.
        val betterHelmet = item(4, ItemType.HELMET, mapOf(fire to 30))
        assertThat(keptIds(gatedHelmet, betterHelmet, lowRange, ranged)).containsExactlyInAnyOrder(4, 3)
    }

    @Test
    fun `two gated items do not keep each other alive when each is dominated`() {
        // Helmet A (range ≤ 3) and cape B (range ≤ 3) each pin range in the other's slot; both are dominated by ungated
        // items that carry range — evicting them needs the least fixpoint (no gate source first), not the pool's gates.
        val gatedHelmet = item(1, ItemType.HELMET, mapOf(fire to 10), rangeAtMost3)
        val gatedCape = item(2, ItemType.CAPE, mapOf(fire to 10), rangeAtMost3)
        val helmet = item(3, ItemType.HELMET, mapOf(fire to 20, Characteristic.RANGE to 1))
        val cape = item(4, ItemType.CAPE, mapOf(fire to 20, Characteristic.RANGE to 1))
        assertThat(keptIds(gatedHelmet, gatedCape, helmet, cape)).containsExactlyInAnyOrder(3, 4)
    }

    // --- exhaustive lock --------------------------------------------------------------------------------------------

    private val slots = listOf(ItemType.HELMET, ItemType.AMULET, ItemType.CAPE, ItemType.BOOTS, ItemType.RING)
    private val gateStats = listOf(Characteristic.RANGE, Characteristic.LOCK, Characteristic.CRITICAL_HIT, Characteristic.DODGE)

    private fun randomPool(rnd: Random): List<Equipment> {
        var id = 0
        val items = mutableListOf<Equipment>()
        for (slot in slots) {
            repeat(rnd.nextInt(2, if (slot == ItemType.RING) 6 else 5)) {
                id++
                val stats = HashMap<Characteristic, Int>()
                stats[fire] = rnd.nextInt(0, 6) * 10
                for (c in gateStats) if (rnd.nextInt(3) == 0) stats[c] = rnd.nextInt(-3, 4)
                val gates =
                    when (rnd.nextInt(6)) {
                        0 -> listOf(critAboveMinus10)
                        1, 2 -> {
                            val c = gateStats.random(rnd)
                            listOf(ItemStatGate(c, false, CriterionComparison.entries.random(rnd), rnd.nextInt(-2, 4)))
                        }
                        else -> emptyList()
                    }
                val e = item(id, slot, stats, *gates.toTypedArray())
                items += e
                // A dominated gated copy: weaker on fire, same other stats, with a gate that can fail.
                if (gates.isEmpty() && rnd.nextInt(3) == 0) {
                    id++
                    val c = gateStats.random(rnd)
                    items += item(id, slot, stats + (fire to (stats.getValue(fire) - 5)), ItemStatGate(c, false, CriterionComparison.entries.random(rnd), rnd.nextInt(-2, 4)))
                }
            }
        }
        return items
    }

    /** The best (objective, ids) over every valid build of [pool]: one item or none per slot, two rings of different names. */
    private fun bestValid(
        pool: List<Equipment>,
        weights: Map<Characteristic, Int>,
    ): Long {
        val bySlot = slots.associateWith { s -> pool.filter { it.itemType == s } }
        val choices =
            slots.map { s ->
                val single = listOf(emptyList<Equipment>()) + bySlot.getValue(s).map { listOf(it) }
                if (s != ItemType.RING) {
                    single
                } else {
                    val rings = bySlot.getValue(s)
                    single + rings.indices.flatMap { i -> (i + 1 until rings.size).map { j -> listOf(rings[i], rings[j]) } }.filter { it[0].name.fr != it[1].name.fr }
                }
            }
        var best = Long.MIN_VALUE

        fun rec(
            i: Int,
            worn: List<Equipment>,
        ) {
            if (i == choices.size) {
                val total = HashMap<Characteristic, Int>()
                for (e in worn) for ((c, v) in e.characteristics) total.merge(c, v, Int::plus)
                // Base crit 3 and the model's ≥ −9 out-of-combat crit cap.
                val crit = 3 + (total[Characteristic.CRITICAL_HIT] ?: 0)
                if (crit < MIN_OUT_OF_COMBAT_CRIT) return
                val sheet = { c: Characteristic -> if (c == Characteristic.CRITICAL_HIT) crit else total[c] ?: 0 }
                if (worn.any { e -> e.statGates.any { g -> !g.holdsOn(sheet(g.sheetCharacteristic)) } }) return
                val score = weights.entries.sumOf { (c, w) -> w.toLong() * sheet(c) }
                if (score > best) best = score
                return
            }
            for (c in choices[i]) rec(i + 1, worn + c)
        }
        rec(0, emptyList())
        return best
    }

    @Test
    fun `seeded lock - domination keeps the best valid build of random gated pools`() {
        // Most-masteries compares every stat (≥) — any non-negative weighting is a monotone objective it must preserve.
        var shrank = 0
        for (seed in 1..60) {
            val rnd = Random(seed)
            val pool = randomPool(rnd)
            val reduced = filterDominatedPool(pool.groupBy { it.itemType }, shape).values.flatten()
            if (reduced.size < pool.size) shrank++
            repeat(3) {
                val weights = (listOf(fire) + gateStats).associateWith { rnd.nextInt(0, 4) }
                assertThat(bestValid(reduced, weights))
                    .describedAs("seed $seed weights $weights: the reduced pool loses the optimum")
                    .isEqualTo(bestValid(pool, weights))
            }
        }
        assertThat(shrank).describedAs("the lock must exercise real evictions").isGreaterThan(40)
    }
}
