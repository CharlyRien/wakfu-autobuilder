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
 * The certificates and the item EQUIP conditions (CERTIFIER_VERSION 57). REQUIRES: an item that needs another (a nation
 * sword needs its zero-stat EPIC ring) is offered FUSED with it, so it takes the epic budget and never pairs with another epic
 * item, and the AP-cell certifier splits its worlds on it ([CertWorld.bundle]). FORBIDS: rings that exclude each other share a
 * pairing key when they form a clique ([me.chosante.autobuilder.domain.ringPairingKeys]), so no bound pairs them. Locks,
 * on seeded pools where the unconstrained optimum wears the sword beside an epic item:
 *  - SOUNDNESS (release-blocking): every AP-cell pass and the ledger upper-bound the CONSTRAINED CP-SAT optimum, and the
 *    most-masteries bound upper-bounds the constrained soft objective;
 *  - TIGHTENING: the fused bound never exceeds the bound of the same pool without the conditions, and drops below it
 *    whenever that relaxed optimum wore the sword with another epic item.
 * And on ring conflicts that are NOT cliques (a path, a 4-cycle, a triple with a same-name sibling, a ring forbidding an
 * amulet), whose best legal ring pair one key per component would refuse: every certificate stays sound — the lock on
 * [me.chosante.autobuilder.domain.ringPairingKeys]' clique guard.
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
     * sword + ring as ONE ring-stage entry, so it counts the ring slot the ring takes, and the excluding pair shares one
     * pairing key ([me.chosante.autobuilder.domain.ringPairingKeys]): the ledger lands exactly on the constrained optimum,
     * with or without the FORBIDS pair.
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
        // The bundle world counts the sword's ring slot exactly, and the excluding rings are priced
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
        // The excluding triple is a clique of conflicting rings, priced exactly ([ringPairingKeys]) — with
        // the REQUIRES split exact too, the ledger lands ON the constrained optimum (the review of #246 measured up to +16 %
        // on these pools while FORBIDS were a relaxation).
        assertThat(tight).describedAs("the ledger is the constrained optimum on every certified pool (%d)", certified).isEqualTo(certified)
        assertThat(certified).isGreaterThanOrEqualTo(8)
    }

    /** Ring conflict components that are NOT cliques: [ringPairingKeys] must keep their name keys (review of #253). */
    private enum class RingTopology { PATH, CYCLE4, TRIPLE_WITH_SIBLING, FOREIGN_PARTNER }

    /** A [ringTopologyPool] and its best legal ring pair that one key for the whole conflict component would refuse. */
    private data class TopologyPool(
        val pool: Map<ItemType, List<Equipment>>,
        val refusedByComponentKey: Set<Int>,
    )

    /**
     * Seeded pool #[seed] around ONE ring conflict component that is not a clique, its two strongest rings a LEGAL pair that a
     * key for the whole component would refuse: the ends a + c of the path a–b–c, the diagonal a + c of the 4-cycle
     * a–b–c–d–a, and the ring y of an excluding triple x–y–z with x', a same-name sibling of x (it conflicts with x alone).
     * FOREIGN_PARTNER is a strong ring that forbids the strong amulet: the certificates ignore a partner outside the rings
     * (a relaxation), so it only checks soundness. Every item carries fire AND distance mastery (the max-damage scenario
     * reads both, the most-masteries request the distance one); a weapon, two or three other slots with an epic item and AP
     * lines, and two weak plain rings complete it.
     */
    private fun ringTopologyPool(
        seed: Int,
        topology: RingTopology,
    ): TopologyPool {
        val rng = Random(0x253_000 + 31 * seed + topology.ordinal)
        var id = 600_000
        val items = mutableListOf<Equipment>()

        fun r(
            lo: Int,
            hi: Int,
        ) = lo + rng.nextInt(hi - lo + 1)

        fun stats(
            strength: Int,
            ap: Int = 0,
        ): Map<Characteristic, Int> =
            buildMap {
                put(Characteristic.MASTERY_ELEMENTARY_FIRE, strength)
                put(Characteristic.MASTERY_DISTANCE, strength / 2)
                if (ap != 0) put(Characteristic.ACTION_POINT, ap)
            }

        fun ring(
            ringId: Int,
            name: String,
            strength: Int,
            forbids: List<Int> = emptyList(),
            rarity: Rarity = Rarity.LEGENDARY,
        ) = item(ringId, ItemType.RING, name, rarity, stats(strength), ItemEquipCriterion(ringId, "x", forbidsItems = forbids).takeIf { forbids.isNotEmpty() })

        fun strong() = r(1600, 2000)

        fun medium() = r(1100, 1400)
        val refused: Set<Int> =
            when (topology) {
                RingTopology.PATH -> {
                    val (a, b, c) = listOf(id++, id++, id++)
                    items += ring(a, "pathA", strong(), forbids = listOf(b))
                    items += ring(b, "pathB", medium())
                    items += ring(c, "pathC", strong(), forbids = listOf(b))
                    setOf(a, c)
                }
                RingTopology.CYCLE4 -> {
                    val (a, b, c, d) = listOf(id++, id++, id++, id++)
                    items += ring(a, "cycleA", strong(), forbids = listOf(b, d))
                    items += ring(b, "cycleB", medium())
                    items += ring(c, "cycleC", strong(), forbids = listOf(b, d))
                    items += ring(d, "cycleD", medium())
                    setOf(a, c)
                }
                RingTopology.TRIPLE_WITH_SIBLING -> {
                    val (x, y, z, sibling) = listOf(id++, id++, id++, id++)
                    items += ring(x, "triX", medium(), forbids = listOf(y, z))
                    items += ring(y, "triY", strong(), forbids = listOf(z))
                    items += ring(z, "triZ", medium())
                    // Same name as x: the game refuses x + x', nothing else — x' + y is legal.
                    items += ring(sibling, "triX", strong(), rarity = Rarity.MYTHIC)
                    setOf(sibling, y)
                }
                RingTopology.FOREIGN_PARTNER -> {
                    val (f, amulet) = listOf(id++, id++)
                    items += ring(f, "foreign", strong(), forbids = listOf(amulet))
                    items += ring(id++, "other", strong())
                    items += item(amulet, ItemType.AMULET, "forbiddenAmulet", Rarity.LEGENDARY, stats(r(1500, 2200)))
                    items += item(id++, ItemType.AMULET, "amulet", Rarity.LEGENDARY, stats(r(600, 1200), ap = r(0, 1)))
                    emptySet()
                }
            }
        repeat(2) { k -> items += ring(id++, "plain$k", r(200, 500)) }
        items += item(id++, ItemType.ONE_HANDED_WEAPONS, "weapon", Rarity.LEGENDARY, stats(r(600, 1500), ap = r(0, 2)))
        val slots = listOf(ItemType.BELT, ItemType.CAPE, ItemType.BOOTS, ItemType.HELMET).shuffled(rng).take(2 + rng.nextInt(2))
        for ((i, slot) in slots.withIndex()) {
            repeat(2) { k ->
                val rarity = if (i == 0 && k == 0) Rarity.EPIC else listOf(Rarity.LEGENDARY, Rarity.LEGENDARY, Rarity.RELIC)[rng.nextInt(3)]
                items += item(id++, slot, "$slot$k", rarity, stats(r(300, 1500), ap = if (rng.nextInt(3) == 0) r(-1, 2) else 0))
            }
        }
        return TopologyPool(items.groupBy { it.itemType }, refused)
    }

    /**
     * Seeded soundness lock on ring conflicts that are NOT cliques (review of #253): every AP-cell pass (exact, fast, tier-1.5)
     * and the ledger stay ≥ the CONSTRAINED pinned CP-SAT cell, with and without the domination pre-filter. On the path, the
     * 4-cycle and the triple with a sibling the optimum wears the pair a component-wide key would refuse, so a regression of
     * [ringPairingKeys]' clique guard under-counts here.
     */
    @Test
    fun `max-damage certificate - sound on ring conflicts that are not cliques`() {
        var compared = 0
        var refusedPairOptima = 0
        for (topology in RingTopology.entries) {
            for (seed in 0 until 3) {
                val (pool, refused) = ringTopologyPool(seed, topology)
                val domination = seed % 2 == 1
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
                    assertThat(equipConditionViolation(worn)).describedAs("%s seed %d AP=%d: the CP-SAT build is legal", topology, seed, ap).isNull()
                    for ((pass, value) in listOf("exact" to exact[ap], "fast" to fast[ap], "tier-1.5" to tier15[ap])) {
                        if (value == null || value < 0) continue
                        assertThat(value)
                            .describedAs("%s seed %d AP=%d: %s (%d) must upper-bound the constrained CP-SAT cell (%d)", topology, seed, ap, pass, value, profile.objective)
                            .isGreaterThanOrEqualTo(profile.objective)
                        compared++
                    }
                    if (best == null || profile.objective > best.first) best = profile.objective to profile.selectedEquipmentIds
                }
                val (optimum, ids) = requireNotNull(best) { "$topology seed $seed: no build" }
                if (refused.isNotEmpty() && ids.containsAll(refused)) refusedPairOptima++
                val ledger = WakfuBuildSolver.certifyLedgerForTest(maxDamageParams, pool, applyDomination = domination, forceTier2All = true).maxCellObjective
                if (ledger != null) {
                    assertThat(
                        ledger
                    ).describedAs("%s seed %d: the ledger (%d) must upper-bound the constrained optimum (%d)", topology, seed, ledger, optimum).isGreaterThanOrEqualTo(optimum)
                }
                println(
                    "EQ_TOPO topology=$topology seed=$seed domination=$domination optimum=$optimum ledger=$ledger refusedPairWorn=${refused.isNotEmpty() &&
                        ids.containsAll(
                            refused
                        )}"
                )
            }
        }
        assertThat(compared).isGreaterThan(40)
        assertThat(refusedPairOptima).describedAs("optima wearing the pair a component-wide key would refuse").isGreaterThanOrEqualTo(6)
    }

    /** The most-masteries and soft max-damage bounds on the same non-clique ring conflicts, against the proven soft optima. */
    @Test
    fun `most-masteries and soft max-damage certificates - sound on ring conflicts that are not cliques`(): Unit =
        runBlocking {
            val tuning = WakfuBuildSolver.SolverTuning(numSearchWorkers = 1, randomSeed = 1, interleaveSearch = true, maxDeterministicTime = 60.0)
            val mostMasteries =
                maxDamageParams.copy(
                    targetStats = TargetStats(listOf(TargetStat(Characteristic.MASTERY_DISTANCE, 9999), TargetStat(Characteristic.ACTION_POINT, 7))),
                    scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT
                )
            val soft = maxDamageParams.copy(targetStats = TargetStats(listOf(TargetStat(Characteristic.ACTION_POINT, 10))))
            var checked = 0
            for (topology in RingTopology.entries) {
                for (seed in 0 until 2) {
                    val pool = ringTopologyPool(seed, topology).pool

                    suspend fun solve(p: WakfuBestBuildParams): me.chosante.autobuilder.genetic.SolverResult<me.chosante.autobuilder.domain.BuildCombination> {
                        var last: me.chosante.autobuilder.genetic.SolverResult<me.chosante.autobuilder.domain.BuildCombination>? = null
                        WakfuBuildSolver.optimize(p, pool, emptyList(), emptyList(), tuning).collect { last = it }
                        return requireNotNull(last)
                    }
                    val mm = solve(mostMasteries)
                    assertThat(mm.isOptimal).describedAs("%s seed %d: the most-masteries oracle proves OPTIMAL on the tiny pool", topology, seed).isTrue()
                    assertThat(mm.individual.isValid(CharacterClass.CRA)).isTrue()
                    val mmBound = MostMasteriesCertificate.bound(mostMasteries, pool, emptyList(), emptyList())?.foldedBound
                    if (mmBound != null) {
                        assertThat(mmBound)
                            .describedAs("%s seed %d: the most-masteries bound (%d) must upper-bound the soft optimum (%d)", topology, seed, mmBound, mm.mostMasteriesObjective)
                            .isGreaterThanOrEqualTo(requireNotNull(mm.mostMasteriesObjective))
                        checked++
                    }
                    val md = solve(soft)
                    assertThat(md.isOptimal).describedAs("%s seed %d: the soft max-damage oracle proves OPTIMAL on the tiny pool", topology, seed).isTrue()
                    assertThat(md.individual.isValid(CharacterClass.CRA)).isTrue()
                    val softBound = MaxDamageSoftCertificate.bound(soft, pool, emptyList(), emptyList())?.foldedBound
                    if (softBound != null) {
                        assertThat(softBound)
                            .describedAs("%s seed %d: the soft bound (%d) must upper-bound the soft optimum (%d)", topology, seed, softBound, md.maxDamageObjective)
                            .isGreaterThanOrEqualTo(requireNotNull(md.maxDamageObjective))
                        checked++
                    }
                    println("EQ_TOPO_SOFT topology=$topology seed=$seed mm=${mm.mostMasteriesObjective} mmBound=$mmBound md=${md.maxDamageObjective} softBound=$softBound")
                }
            }
            assertThat(checked).describedAs("bounds compared (a bail is sound but checks nothing)").isGreaterThanOrEqualTo(12)
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
