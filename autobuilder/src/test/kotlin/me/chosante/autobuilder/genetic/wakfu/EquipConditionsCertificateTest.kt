package me.chosante.autobuilder.genetic.wakfu

import kotlinx.coroutines.runBlocking
import me.chosante.autobuilder.domain.DamageScenario
import me.chosante.autobuilder.domain.Orientation
import me.chosante.autobuilder.domain.RangeBand
import me.chosante.autobuilder.domain.SpellElement
import me.chosante.autobuilder.domain.TargetStat
import me.chosante.autobuilder.domain.TargetStats
import me.chosante.autobuilder.domain.equipConditionViolation
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
 * The certificates and the item EQUIP conditions (CERTIFIER_VERSION 57, 58). REQUIRES: an item that needs another (a nation
 * sword needs its zero-stat EPIC ring) is offered FUSED with it, so it takes the epic budget and never pairs with another epic
 * item, and the AP-cell certifier splits its worlds on it (lazily, v58). FORBIDS: rings that exclude each other share a
 * pairing key when they form a clique ([me.chosante.autobuilder.domain.ringPairingKeys], v58), so no bound pairs them. Locks,
 * on seeded pools where the unconstrained optimum wears the sword beside an epic item:
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
            if (!conflicts) conflictFree++
            if (bound == trueOptimum) tight++
        }
        assertThat(compared).isGreaterThan(40)
        assertThat(tightened).describedAs("the split must tighten the pools whose relaxed optimum wears the sword beside the epic").isGreaterThanOrEqualTo(6)
        // The bundle world counts the sword's ring slot exactly, and (CERTIFIER_VERSION 58) the excluding rings are priced
        // exactly ([ringPairingKeys]): the ledger lands ON the constrained optimum, FORBIDS pair or not.
        assertThat(conflictFree).isEqualTo(8)
        assertThat(tight).describedAs("the ledger must be TIGHT (== the constrained optimum) on every pool").isEqualTo(16)
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

    /**
     * Adversarial pool #[seed] (ported from the review of #246): ONE or TWO RELIC swords, each needing its own EPIC key ring
     * whose stats vary — none / mastery / AP +1 / MP +1 (the exact pass's MP-ring path) / crit + crit mastery / AP −1 +
     * mastery — with 0 or 4 sockets; free one-handers (some EPIC / RELIC), an optional two-hander, off-hands, other slots
     * with EPIC / RELIC items, a Lieute-style excluding triple (its third ring sometimes lists nothing) and plain rings
     * with AP / MP / crit.
     */
    private fun adversarialPool(seed: Int): Map<ItemType, List<Equipment>> {
        val rng = Random(0x246_000 + seed)
        var id = 700_000
        val items = mutableListOf<Equipment>()
        val m = Characteristic.MASTERY_ELEMENTARY_FIRE

        fun r(
            lo: Int,
            hi: Int,
        ) = lo + rng.nextInt(hi - lo + 1)

        fun extras(
            stats: MutableMap<Characteristic, Int>,
            di: Boolean = true,
        ) {
            if (rng.nextInt(3) == 0) stats[Characteristic.CRITICAL_HIT] = r(-3, 10)
            if (rng.nextInt(4) == 0) stats[Characteristic.ACTION_POINT] = r(-1, 2)
            if (rng.nextInt(6) == 0) stats[Characteristic.MOVEMENT_POINT] = 1
            if (rng.nextInt(3) == 0) stats[Characteristic.MASTERY_DISTANCE] = r(50, 400)
            if (rng.nextInt(4) == 0) stats[Characteristic.MASTERY_CRITICAL] = r(50, 300)
            // The certifier bails on a ring carrying Damage Inflicted (sound, but it would hide the pool): rings get none.
            if (rng.nextInt(5) == 0 && di) stats[Characteristic.DAMAGE_INFLICTED] = r(5, 15)
        }
        repeat(1 + rng.nextInt(2)) { s ->
            val key = id++
            val sword = id++
            val swordStats = mutableMapOf(m to r(600, 2500), Characteristic.ACTION_POINT to r(1, 3))
            if (rng.nextBoolean()) swordStats[Characteristic.CRITICAL_HIT] = r(0, 10)
            items +=
                item(
                    sword,
                    ItemType.ONE_HANDED_WEAPONS,
                    "sword$s",
                    Rarity.RELIC,
                    swordStats,
                    ItemEquipCriterion(sword, "HasEquipmentId($key)", requiresItems = listOf(key)),
                    sockets = listOf(0, 4)[rng.nextInt(2)]
                )
            val keyStats: Map<Characteristic, Int> =
                when (rng.nextInt(6)) {
                    0 -> emptyMap()
                    1 -> mapOf(m to r(100, 600))
                    2 -> mapOf(Characteristic.ACTION_POINT to 1)
                    3 -> mapOf(Characteristic.MOVEMENT_POINT to 1, m to r(0, 200))
                    4 -> mapOf(Characteristic.CRITICAL_HIT to r(1, 8), Characteristic.MASTERY_CRITICAL to r(0, 200))
                    else -> mapOf(Characteristic.ACTION_POINT to -1, m to r(300, 900))
                }
            items += item(key, ItemType.RING, "key$s", Rarity.EPIC, keyStats, sockets = listOf(0, 4)[rng.nextInt(2)])
        }
        repeat(1 + rng.nextInt(2)) { k ->
            val stats = mutableMapOf(m to r(100, 1800))
            extras(stats)
            items += item(id++, ItemType.ONE_HANDED_WEAPONS, "w1h$k", listOf(Rarity.LEGENDARY, Rarity.EPIC, Rarity.RELIC)[rng.nextInt(3)], stats, sockets = 4)
        }
        if (rng.nextBoolean()) {
            val stats = mutableMapOf(m to r(800, 3500), Characteristic.ACTION_POINT to r(0, 2))
            extras(stats)
            items += item(id++, ItemType.TWO_HANDED_WEAPONS, "w2h", listOf(Rarity.LEGENDARY, Rarity.EPIC, Rarity.RELIC)[rng.nextInt(3)], stats, sockets = 4)
        }
        repeat(rng.nextInt(3)) { k ->
            val stats = mutableMapOf(m to r(50, 900))
            extras(stats)
            items += item(id++, ItemType.OFF_HAND_WEAPONS, "off$k", listOf(Rarity.LEGENDARY, Rarity.EPIC, Rarity.MYTHIC)[rng.nextInt(3)], stats, sockets = 2)
        }
        val slots = listOf(ItemType.AMULET, ItemType.BELT, ItemType.CAPE, ItemType.BOOTS, ItemType.HELMET).shuffled(rng).take(2 + rng.nextInt(3))
        for ((i, slot) in slots.withIndex()) {
            repeat(2) { k ->
                val stats = mutableMapOf(m to r(100, 1500))
                extras(stats)
                val rarity =
                    when {
                        i == 0 && k == 0 -> Rarity.EPIC.also { stats[m] = r(1500, 3000) }
                        rng.nextInt(5) == 0 -> Rarity.EPIC
                        rng.nextInt(5) == 0 -> Rarity.RELIC
                        else -> Rarity.LEGENDARY
                    }
                items += item(id++, slot, "$slot$k", rarity, stats, sockets = listOf(0, 3, 4)[rng.nextInt(3)])
            }
        }
        if (rng.nextInt(3) != 0) {
            val triple = listOf(id++, id++, id++)
            for ((n, ringId) in triple.withIndex()) {
                val stats = mutableMapOf(m to r(300, 1100))
                extras(stats, di = false)
                val criterion = if (n == 2 && rng.nextBoolean()) null else ItemEquipCriterion(ringId, "x", forbidsItems = triple - ringId)
                items += item(ringId, ItemType.RING, "tri$n", Rarity.LEGENDARY, stats, criterion, sockets = 4)
            }
        }
        repeat(2 + rng.nextInt(3)) { k ->
            val stats = mutableMapOf(m to r(50, 700))
            extras(stats, di = false)
            items += item(id++, ItemType.RING, "ring$k", listOf(Rarity.LEGENDARY, Rarity.MYTHIC, Rarity.EPIC)[rng.nextInt(3)], stats, sockets = 4)
        }
        return items.groupBy { it.itemType }
    }

    /**
     * Seeded soundness lock (review of #246): on adversarial pools — two swords, key rings carrying AP / MP / crit, an excluding
     * triple, two-handers, off-hands — every AP-cell pass (exact, fast, tier-1.5) and the ledger stay ≥ the CONSTRAINED pinned
     * CP-SAT cell, with and without the domination pre-filter, and every CP-SAT build is legal. Sized for CI (~25 s on 4 cores).
     */
    @Test
    fun `max-damage certificate - adversarial seeded pools stay sound against the constrained optimum`() {
        var compared = 0
        var swordOptima = 0
        var twoSwordPools = 0
        var certified = 0
        var tight = 0
        for (seed in 0 until 10) {
            val pool = adversarialPool(seed)
            val domination = seed % 2 == 1
            if (pool.values.flatten().count { it.equipCriterion?.requiresItems?.isNotEmpty() == true } == 2) twoSwordPools++
            val (exact, fast, tier15) = WakfuBuildSolver.certifierExactFastTier15CellObjectivesForTest(maxDamageParams, pool, applyDomination = domination)
            var best: Pair<Long, Set<Int>>? = null
            for (ap in (exact.keys + fast.keys).sorted()) {
                val profile =
                    WakfuBuildSolver.timedMaxDamageProfileForTest(
                        maxDamageParams.copy(maxDamageApTarget = ap),
                        pool,
                        emptyList(),
                        emptyList(),
                        workers = 1,
                        seconds = 10.0,
                        applyDomination = domination,
                        deterministicLimit = 6.0
                    )
                if (!profile.hasSolution) continue
                val worn = pool.values.flatten().filter { it.equipmentId in profile.selectedEquipmentIds }
                assertThat(equipConditionViolation(worn)).describedAs("seed %d AP=%d: the CP-SAT build is legal", seed, ap).isNull()
                for ((pass, value) in listOf("exact" to exact[ap], "fast" to fast[ap], "tier-1.5" to tier15[ap])) {
                    if (value == null || value < 0) continue
                    assertThat(value).describedAs("seed %d AP=%d: %s must upper-bound the constrained CP-SAT cell", seed, ap, pass).isGreaterThanOrEqualTo(profile.objective)
                    compared++
                }
                if (best == null || profile.objective > best.first) best = profile.objective to profile.selectedEquipmentIds
            }
            val (optimum, ids) = best ?: continue
            if (pool.values.flatten().any { it.equipmentId in ids && it.equipCriterion?.requiresItems?.isNotEmpty() == true }) swordOptima++
            val ledger = WakfuBuildSolver.certifyLedgerForTest(maxDamageParams, pool, applyDomination = domination, forceTier2All = true).maxCellObjective ?: continue
            assertThat(ledger).describedAs("seed %d: the ledger (%d) must upper-bound the constrained optimum (%d)", seed, ledger, optimum).isGreaterThanOrEqualTo(optimum)
            println("EQ_ADV seed=$seed domination=$domination optimum=$optimum ledger=$ledger")
            certified++
            if (ledger == optimum) tight++
        }
        assertThat(compared).isGreaterThan(50)
        assertThat(twoSwordPools).describedAs("pools with two swords").isGreaterThanOrEqualTo(2)
        assertThat(swordOptima).describedAs("pools whose optimum wears a sword with its ring").isGreaterThanOrEqualTo(2)
        // CERTIFIER_VERSION 58: the excluding triple is a clique of conflicting rings, priced exactly ([ringPairingKeys]) — with
        // the REQUIRES split exact too, the ledger lands ON the constrained optimum (the review of #246 measured up to +16 %
        // on these pools while FORBIDS were a relaxation).
        assertThat(tight).describedAs("the ledger is the constrained optimum on every certified pool (%d)", certified).isEqualTo(certified)
        assertThat(certified).isGreaterThanOrEqualTo(8)
    }

    /**
     * The E8 construct (review of #246) on a pool whose optimum wears the sword: the certifier's argmax is the bundle world's
     * sword + ring, the restricted re-solve gets the ring through [me.chosante.autobuilder.domain.requirementClosure], and the
     * constructed build — the sword, its ring AND the best other ring — is the pinned CP-SAT optimum, proven and legal.
     */
    @Test
    fun `the E8 construct crowns a legal sword build when the optimum wears the sword`(): Unit =
        runBlocking {
            val sword =
                item(
                    9301,
                    ItemType.ONE_HANDED_WEAPONS,
                    "sword",
                    Rarity.RELIC,
                    mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 2500, Characteristic.ACTION_POINT to 2),
                    ItemEquipCriterion(9301, "HasEquipmentId(9302)", requiresItems = listOf(9302))
                )
            val pool =
                listOf(
                    sword,
                    item(9302, ItemType.RING, "key", Rarity.EPIC, emptyMap(), sockets = 4),
                    item(9303, ItemType.ONE_HANDED_WEAPONS, "weapon", Rarity.LEGENDARY, mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 600)),
                    item(9304, ItemType.RING, "ring1", Rarity.LEGENDARY, mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 700)),
                    item(9305, ItemType.RING, "ring2", Rarity.LEGENDARY, mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 500)),
                    item(9306, ItemType.AMULET, "epicAmulet", Rarity.EPIC, mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 900)),
                    item(9307, ItemType.AMULET, "amulet", Rarity.LEGENDARY, mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 800)),
                    item(9308, ItemType.HELMET, "helmet", Rarity.LEGENDARY, mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 1000, Characteristic.ACTION_POINT to 1))
                ).groupBy { it.itemType }
            val constructed = WakfuBuildSolver.dpConstructProvenOptimum(maxDamageParams, pool)
            assertThat(constructed).describedAs("the construct reaches the bound on the tiny pool").isNotNull
            constructed!!
            assertThat(constructed.isOptimal).isTrue()
            assertThat(constructed.individual.isValid(CharacterClass.CRA)).isTrue()
            val ids = constructed.individual.equipments.map { it.equipmentId }
            assertThat(ids).describedAs("the sword, its ring and the best free ring").contains(9301, 9302, 9304).doesNotContain(9306)
            // The construct's proxy is the pinned CP-SAT optimum over the AP cells.
            val (exact, _, _) = WakfuBuildSolver.certifierExactFastTier15CellObjectivesForTest(maxDamageParams, pool)
            val optimum = exact.keys.mapNotNull { cpsatCell(pool, it) }.max()
            assertThat(constructed.maxDamageRawProxy ?: constructed.maxDamageObjective).isEqualTo(optimum)
        }
}
