package me.chosante.autobuilder.genetic.wakfu

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
import me.chosante.common.ItemEquipCriterion
import me.chosante.common.ItemType
import me.chosante.common.Rarity
import me.chosante.common.skills.CharacterSkills
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

/**
 * The certificates and the item EQUIP conditions (CERTIFIER_VERSION 57). The certifiers ignore REQUIRES / FORBIDS — a
 * relaxation — except one sound tightening: an item that needs another (a nation sword needs its zero-stat EPIC ring) is
 * offered FUSED with it, so it takes the epic budget and never pairs with another epic item. Locks, on seeded pools where
 * the unconstrained optimum wears the sword beside an epic item:
 *  - SOUNDNESS (release-blocking): every AP-cell pass and the ledger upper-bound the CONSTRAINED CP-SAT optimum, and the
 *    most-masteries bound upper-bounds the constrained soft objective;
 *  - TIGHTENING: the fused bound never exceeds the bound of the same pool without the conditions, and drops below it
 *    whenever that relaxed optimum wore the sword with another epic item.
 */
class EquipConditionsCertificateTest {
    private fun item(
        id: Int,
        type: ItemType,
        name: String,
        rarity: Rarity,
        stats: Map<Characteristic, Int>,
        criterion: ItemEquipCriterion? = null,
        sockets: Int = 0,
    ) = Equipment(
        equipmentId = id,
        guiId = id,
        level = 1,
        name = I18nText(name, name, name, name),
        rarity = rarity,
        itemType = type,
        characteristics = stats,
        maxShardSlots = sockets,
        equipCriterion = criterion
    )

    private val maxDamageParams =
        WakfuBestBuildParams(
            character = Character(CharacterClass.CRA, 50, 0, CharacterSkills(50)),
            targetStats = TargetStats(emptyList()),
            searchDuration = 60.seconds,
            stopWhenBuildMatch = false,
            maxRarity = Rarity.EPIC,
            forcedItems = emptyList(),
            excludedItems = emptyList(),
            scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE,
            useRunes = false,
            useSublimations = false,
            damageScenario = DamageScenario(element = SpellElement.FIRE, rangeBand = RangeBand.DISTANCE, orientation = Orientation.FACE)
        )

    /**
     * Seeded pool #[seed]: a strong RELIC sword (AP +1..3) that needs a stat-less EPIC ring, a free weapon, 3–4 slots of
     * random fire items of which one is a strong EPIC (the item the unconstrained optimum pairs with the sword), plain
     * rings, and two rings that exclude each other.
     */
    private fun pool(
        seed: Int,
        mastery: Characteristic = Characteristic.MASTERY_ELEMENTARY_FIRE,
        conflicts: Boolean = true,
    ): Map<ItemType, List<Equipment>> {
        val rng = Random(0x5EED_E0C + seed)
        var id = 9100
        val items = mutableListOf<Equipment>()

        fun fire(
            lo: Int,
            hi: Int,
        ) = mastery to (lo + rng.nextInt(hi - lo))
        val key = id++
        val swordId = id++
        items +=
            item(
                swordId,
                ItemType.ONE_HANDED_WEAPONS,
                "sword",
                Rarity.RELIC,
                mapOf(fire(900, 2500), Characteristic.ACTION_POINT to 1 + rng.nextInt(3)),
                ItemEquipCriterion(swordId, "HasEquipmentId($key)", requiresItems = listOf(key))
            )
        items += item(key, ItemType.RING, "key", Rarity.EPIC, emptyMap(), sockets = listOf(0, 4)[rng.nextInt(2)])
        items += item(id++, ItemType.ONE_HANDED_WEAPONS, "weapon", Rarity.LEGENDARY, mapOf(fire(100, 1200)))
        val slots = listOf(ItemType.AMULET, ItemType.BELT, ItemType.CAPE, ItemType.BOOTS, ItemType.HELMET).shuffled(rng).take(3 + rng.nextInt(2))
        for ((i, slot) in slots.withIndex()) {
            repeat(2) { k ->
                val stats = mutableMapOf(fire(100, 1500))
                if (rng.nextInt(3) == 0) stats[Characteristic.CRITICAL_HIT] = rng.nextInt(10) - 2
                if (rng.nextInt(4) == 0) stats[Characteristic.ACTION_POINT] = rng.nextInt(3) - 1
                // Slot 0's first item is the strong epic the sword competes with; a few other epics / relics on the side.
                val rarity =
                    when {
                        i == 0 && k == 0 -> Rarity.EPIC.also { stats[mastery] = 1500 + rng.nextInt(1500) }
                        rng.nextInt(6) == 0 -> Rarity.EPIC
                        rng.nextInt(6) == 0 -> Rarity.RELIC
                        else -> Rarity.LEGENDARY
                    }
                items += item(id++, slot, "$slot$k", rarity, stats)
            }
        }
        val excluding = id++
        val excluded = id++
        items +=
            item(
                excluding,
                ItemType.RING,
                "excluding",
                Rarity.LEGENDARY,
                mapOf(fire(400, 900)),
                ItemEquipCriterion(excluding, "not HasEquipmentId($excluded)", forbidsItems = listOf(excluded)).takeIf { conflicts }
            )
        items += item(excluded, ItemType.RING, "excluded", Rarity.LEGENDARY, mapOf(fire(400, 900)))
        items += item(id++, ItemType.RING, "plain", Rarity.LEGENDARY, mapOf(fire(100, 500)))
        return items.groupBy { it.itemType }
    }

    private fun stripped(pool: Map<ItemType, List<Equipment>>) = pool.mapValues { (_, items) -> items.map { it.copy(equipCriterion = null) } }

    private fun cpsatCell(
        pool: Map<ItemType, List<Equipment>>,
        ap: Int,
    ): Long? {
        val profile =
            WakfuBuildSolver.timedMaxDamageProfileForTest(
                maxDamageParams.copy(maxDamageApTarget = ap),
                pool,
                emptyList(),
                emptyList(),
                workers = 1,
                seconds = 10.0,
                applyDomination = false,
                deterministicLimit = 6.0
            )
        if (!profile.hasSolution) return null
        check(profile.status == "OPTIMAL") { "AP=$ap: expected OPTIMAL on the tiny synthetic pool, got ${profile.status}" }
        return profile.objective
    }

    /**
     * The AP-cell certifier splits its worlds on the REQUIRES condition ([CertWorld.bundle]): the bundle world offers the
     * sword + ring as ONE ring-stage entry, so it counts the ring slot the ring takes — on pools without a FORBIDS pair the
     * ledger lands exactly on the constrained optimum. FORBIDS stay a relaxation (the certifier may pair two rings that
     * exclude each other — sound, looser), so pools carrying one are only checked for soundness and tightening.
     */
    @Test
    fun `max-damage certificate - sound against the constrained optimum, tighter than without the conditions`() {
        var compared = 0
        var tightened = 0
        var tight = 0
        var conflictFree = 0
        repeat(16) { run ->
            val seed = run / 2
            val conflicts = run % 2 == 0
            val pool = pool(seed, conflicts = conflicts)
            val (exact, fast, tier15) = WakfuBuildSolver.certifierExactFastTier15CellObjectivesForTest(maxDamageParams, pool)
            val cpsat = exact.keys.sorted().associateWith { cpsatCell(pool, it) }
            for ((ap, truth) in cpsat) {
                if (truth == null) continue
                for ((pass, value) in listOf("exact" to exact[ap], "fast" to fast[ap], "tier-1.5" to tier15[ap])) {
                    if (value == null || value < 0) continue
                    assertThat(
                        value
                    ).describedAs("seed %d AP=%d: %s (%d) must upper-bound the constrained CP-SAT cell (%d)", seed, ap, pass, value, truth).isGreaterThanOrEqualTo(truth)
                    compared++
                }
            }
            val trueOptimum = cpsat.values.filterNotNull().maxOrNull() ?: return@repeat
            val bound = WakfuBuildSolver.certifyLedgerForTest(maxDamageParams, pool, forceTier2All = true).maxCellObjective ?: return@repeat
            assertThat(bound).describedAs("seed %d: the ledger (%d) must upper-bound the constrained optimum (%d)", seed, bound, trueOptimum).isGreaterThanOrEqualTo(trueOptimum)
            val relaxed = WakfuBuildSolver.certifyLedgerForTest(maxDamageParams, stripped(pool), forceTier2All = true).maxCellObjective ?: return@repeat
            assertThat(bound).describedAs("seed %d: the split bound (%d) never exceeds the relaxed one (%d)", seed, bound, relaxed).isLessThanOrEqualTo(relaxed)
            println("EQ_CERT seed=$seed conflicts=$conflicts optimum=$trueOptimum bound=$bound relaxed=$relaxed")
            if (bound < relaxed) tightened++
            if (!conflicts) {
                conflictFree++
                if (bound == trueOptimum) tight++
            }
        }
        assertThat(compared).isGreaterThan(40)
        assertThat(tightened).describedAs("the split must tighten the pools whose relaxed optimum wears the sword beside the epic").isGreaterThanOrEqualTo(6)
        // The bundle world counts the sword's ring slot exactly: without a FORBIDS pair the ledger lands ON the optimum.
        assertThat(tight).describedAs("the ledger must be TIGHT (== the constrained optimum) on most conflict-free pools (%d)", conflictFree).isGreaterThanOrEqualTo(
            conflictFree - 1
        )
    }

    @Test
    fun `most-masteries certificate - sound against the constrained soft objective, tighter than without the conditions`(): Unit =
        runBlocking {
            val params =
                maxDamageParams.copy(
                    targetStats = TargetStats(listOf(TargetStat(Characteristic.MASTERY_DISTANCE, 9999), TargetStat(Characteristic.ACTION_POINT, 7))),
                    scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT
                )
            val tuning = WakfuBuildSolver.SolverTuning(numSearchWorkers = 1, randomSeed = 1, interleaveSearch = true, maxDeterministicTime = 60.0)
            var tightened = 0
            repeat(6) { seed ->
                val pool = pool(seed, Characteristic.MASTERY_DISTANCE)
                var last: me.chosante.autobuilder.genetic.SolverResult<me.chosante.autobuilder.domain.BuildCombination>? = null
                WakfuBuildSolver.optimize(params, pool, emptyList(), emptyList(), tuning).collect { last = it }
                val final = requireNotNull(last)
                assertThat(final.individual.isValid(CharacterClass.CRA)).isTrue()
                val incumbent = requireNotNull(final.mostMasteriesObjective)
                val bound = requireNotNull(MostMasteriesCertificate.bound(params, pool, emptyList(), emptyList())).foldedBound
                assertThat(
                    bound
                ).describedAs("seed %d: the bound (%d) must upper-bound the constrained soft objective (%d)", seed, bound, incumbent).isGreaterThanOrEqualTo(incumbent)
                val relaxed = requireNotNull(MostMasteriesCertificate.bound(params, stripped(pool), emptyList(), emptyList())).foldedBound
                assertThat(bound).isLessThanOrEqualTo(relaxed)
                if (bound < relaxed) tightened++
            }
            assertThat(tightened).isGreaterThanOrEqualTo(2)
        }
}
