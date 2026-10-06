package me.chosante.autobuilder.genetic.wakfu

import kotlinx.coroutines.runBlocking
import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.autobuilder.domain.DamageScenario
import me.chosante.autobuilder.domain.Orientation
import me.chosante.autobuilder.domain.RangeBand
import me.chosante.autobuilder.domain.SpellElement
import me.chosante.autobuilder.domain.TargetStat
import me.chosante.autobuilder.domain.TargetStats
import me.chosante.autobuilder.domain.exclusiveGroupViolation
import me.chosante.autobuilder.genetic.SolverResult
import me.chosante.common.Character
import me.chosante.common.CharacterClass
import me.chosante.common.Characteristic
import me.chosante.common.Equipment
import me.chosante.common.ExclusiveGroup
import me.chosante.common.I18nText
import me.chosante.common.ItemType
import me.chosante.common.Rarity
import me.chosante.common.Sublimation
import me.chosante.common.skills.CharacterSkills
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

/**
 * The certificates and the "only one equipped at a time" groups ([ExclusiveGroup]). Each certificate sets its epic / relic
 * bit from the item's GROUP: the budget is then exact — an EPIC-group COMMON item (18691, 18693) never pairs with an epic
 * item in a bound — while such an item also passes for an epic-SUB carrier there, a sound over-count (the game only lets an
 * EPIC-rarity item host one). Locks, on seeded pools carrying EPIC-group COMMON items beside strong epics (and, on half of
 * them, the epic sublimation Santé de fer):
 *  - SOUNDNESS (release-blocking): every AP-cell pass and the ledger stay ≥ the constrained pinned CP-SAT cell (whose builds
 *    are legal), the most-masteries bound ≥ the constrained soft objective, the soft max-damage bound ≥ the constrained soft
 *    optimum;
 *  - the group binds: on enough pools the optimum WITHOUT the group pairs a group COMMON item with an epic, and without
 *    sublimations the ledger never exceeds the group-free one — and falls strictly below it on every such pool, which locks
 *    the AP-cell certifier's epic bit on the group (read from the rarity, it leaves the two ledgers equal).
 */
class ExclusiveGroupCertificateTest {
    private val santeDeFer: Sublimation = WakfuBestBuildFinderAlgorithm.sublimations.single { it.name.fr == "Santé de fer" }

    private fun item(
        id: Int,
        type: ItemType,
        name: String,
        rarity: Rarity,
        stats: Map<Characteristic, Int>,
        group: ExclusiveGroup? = null,
    ) = Equipment(
        equipmentId = id,
        guiId = id,
        level = 1,
        name = I18nText(name, name, name, name),
        rarity = rarity,
        itemType = type,
        characteristics = stats,
        exclusiveGroupOverride = group
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
     * Seeded pool #[seed]: one or two COMMON items of the EPIC group (the 18691 shape — a shoulder pad with AP +1 and crit — and
     * the 18693 one — a belt with MP +1), each with a plain alternative and sometimes an epic one; 3–4 other slots whose first
     * item is a strong EPIC (what the group-free optimum pairs the commons with), more EPIC / RELIC items on the side,
     * weapons and rings.
     */
    private fun pool(seed: Int): Map<ItemType, List<Equipment>> {
        val rng = Random(0xE9_1C00 + seed)
        var id = 800_000
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
        val groupSlots = listOf(ItemType.SHOULDER_PADS, ItemType.BELT).shuffled(rng).take(1 + rng.nextInt(2))
        for (slot in groupSlots) {
            val stats = mutableMapOf(m to r(300, 1500), Characteristic.CRITICAL_HIT to 2)
            stats[if (slot == ItemType.SHOULDER_PADS) Characteristic.ACTION_POINT else Characteristic.MOVEMENT_POINT] = 1
            if (rng.nextBoolean()) stats[Characteristic.MASTERY_DISTANCE] = r(50, 300)
            items += item(id++, slot, "common$slot", Rarity.COMMON, stats, ExclusiveGroup.EPIC)
            val plain = mutableMapOf(m to r(100, 900))
            extras(plain)
            items += item(id++, slot, "plain$slot", Rarity.LEGENDARY, plain)
            if (rng.nextInt(3) == 0) {
                val epic = mutableMapOf(m to r(200, 1200))
                extras(epic)
                items += item(id++, slot, "epic$slot", Rarity.EPIC, epic)
            }
        }
        val slots = listOf(ItemType.AMULET, ItemType.CAPE, ItemType.BOOTS, ItemType.HELMET, ItemType.CHEST_PLATE).shuffled(rng).take(3 + rng.nextInt(2))
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
                items += item(id++, slot, "$slot$k", rarity, stats)
            }
        }
        repeat(1 + rng.nextInt(2)) { k ->
            val stats = mutableMapOf(m to r(100, 1800))
            extras(stats)
            items += item(id++, ItemType.ONE_HANDED_WEAPONS, "w1h$k", listOf(Rarity.LEGENDARY, Rarity.EPIC, Rarity.RELIC)[rng.nextInt(3)], stats)
        }
        if (rng.nextBoolean()) {
            val stats = mutableMapOf(m to r(50, 900))
            extras(stats)
            items += item(id++, ItemType.OFF_HAND_WEAPONS, "off", listOf(Rarity.LEGENDARY, Rarity.EPIC)[rng.nextInt(2)], stats)
        }
        repeat(2 + rng.nextInt(2)) { k ->
            val stats = mutableMapOf(m to r(50, 700))
            extras(stats, di = false)
            items += item(id++, ItemType.RING, "ring$k", listOf(Rarity.LEGENDARY, Rarity.MYTHIC, Rarity.EPIC)[rng.nextInt(3)], stats)
        }
        return items.groupBy { it.itemType }
    }

    /** [pool] with the group taken off the COMMON items (the engine before the groups were read: rarity only). */
    private fun groupFree(pool: Map<ItemType, List<Equipment>>) = pool.mapValues { (_, items) -> items.map { it.copy(exclusiveGroupOverride = null) } }

    private fun Map<ItemType, List<Equipment>>.pairsGroupCommonWithEpic(ids: Set<Int>): Boolean {
        val worn = values.flatten().filter { it.equipmentId in ids }
        return worn.any { it.exclusiveGroupOverride == ExclusiveGroup.EPIC } && worn.any { it.rarity == Rarity.EPIC }
    }

    @Test
    fun `max-damage certificate - sound against the constrained optimum with EPIC-group common items`() {
        var compared = 0
        var binding = 0
        var withSub = 0
        var subFreeBinding = 0
        var strictlyTighter = 0
        for (seed in 0 until 10) {
            val pool = pool(seed)
            val domination = seed % 2 == 1
            val subs = if (seed % 4 < 2) listOf(santeDeFer) else emptyList()
            if (subs.isNotEmpty()) withSub++
            val params = maxDamageParams.copy(useSublimations = subs.isNotEmpty())
            val (exact, fast, tier15) = WakfuBuildSolver.certifierExactFastTier15CellObjectivesForTest(params, pool, sublimations = subs, applyDomination = domination)

            fun pinned(
                p: Map<ItemType, List<Equipment>>,
                ap: Int,
            ) = WakfuBuildSolver.timedMaxDamageProfileForTest(
                params.copy(maxDamageApTarget = ap),
                p,
                emptyList(),
                subs,
                workers = 1,
                seconds = 10.0,
                applyDomination = domination,
                deterministicLimit = 6.0
            )
            var best: Long? = null
            var groupFreeBest: Pair<Long, Set<Int>>? = null
            for (ap in (exact.keys + fast.keys).sorted()) {
                val profile = pinned(pool, ap)
                if (profile.hasSolution) {
                    val worn = pool.values.flatten().filter { it.equipmentId in profile.selectedEquipmentIds }
                    assertThat(exclusiveGroupViolation(worn)).describedAs("seed %d AP=%d: the CP-SAT build keeps one item per group", seed, ap).isNull()
                    for ((pass, value) in listOf("exact" to exact[ap], "fast" to fast[ap], "tier-1.5" to tier15[ap])) {
                        if (value == null || value < 0) continue
                        assertThat(value).describedAs("seed %d AP=%d: %s must upper-bound the constrained CP-SAT cell", seed, ap, pass).isGreaterThanOrEqualTo(profile.objective)
                        compared++
                    }
                    if (best == null || profile.objective > best) best = profile.objective
                }
                val free = pinned(groupFree(pool), ap)
                if (free.hasSolution && (groupFreeBest == null || free.objective > groupFreeBest.first)) groupFreeBest = free.objective to free.selectedEquipmentIds
            }
            val optimum = best ?: continue
            val binds = groupFreeBest != null && pool.pairsGroupCommonWithEpic(groupFreeBest.second)
            if (binds) binding++
            val ledger =
                WakfuBuildSolver.certifyLedgerForTest(params, pool, sublimations = subs, applyDomination = domination, forceTier2All = true).maxCellObjective ?: continue
            assertThat(ledger).describedAs("seed %d: the ledger (%d) must upper-bound the constrained optimum (%d)", seed, ledger, optimum).isGreaterThanOrEqualTo(optimum)
            var relaxed: Long? = null
            if (subs.isEmpty()) {
                relaxed = WakfuBuildSolver.certifyLedgerForTest(params, groupFree(pool), applyDomination = domination, forceTier2All = true).maxCellObjective ?: continue
                assertThat(ledger).describedAs("seed %d: the group never loosens a sub-free ledger (%d vs %d)", seed, ledger, relaxed).isLessThanOrEqualTo(relaxed)
                if (binds) subFreeBinding++
                if (binds && ledger < relaxed) strictlyTighter++
            }
            println(
                "EXCL_CERT seed=$seed subs=${subs.size} domination=$domination binds=$binds optimum=$optimum ledger=$ledger groupFreeLedger=$relaxed groupFree=${groupFreeBest?.first}"
            )
        }
        assertThat(compared).isGreaterThan(50)
        assertThat(withSub).isGreaterThanOrEqualTo(4)
        assertThat(binding).describedAs("pools whose group-free optimum pairs a group common item with an epic").isGreaterThanOrEqualTo(3)
        // The AP-cell certifier reads the GROUP (review of #253, finding 2): where the group binds, a sub-free ledger falls
        // strictly below the group-free one — an epic bit read from the rarity would leave the two equal.
        assertThat(subFreeBinding).describedAs("sub-free pools where the group binds").isGreaterThanOrEqualTo(2)
        assertThat(strictlyTighter).describedAs("sub-free binding pools whose ledger the group lowers strictly").isEqualTo(subFreeBinding)
    }

    @Test
    fun `most-masteries and soft max-damage certificates - sound against the constrained soft objective`(): Unit =
        runBlocking {
            val tuning = WakfuBuildSolver.SolverTuning(numSearchWorkers = 1, randomSeed = 1, interleaveSearch = true, maxDeterministicTime = 60.0)
            val mostMasteries =
                maxDamageParams.copy(
                    // Distance mastery: on an elemental-mastery request the bound bails on these pools (sound, but it checks nothing).
                    targetStats = TargetStats(listOf(TargetStat(Characteristic.MASTERY_DISTANCE, 9999), TargetStat(Characteristic.ACTION_POINT, 8))),
                    scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT
                )
            val soft = maxDamageParams.copy(targetStats = TargetStats(listOf(TargetStat(Characteristic.ACTION_POINT, 10))))
            var checked = 0
            for (seed in 0 until 6) {
                val pool = pool(seed)
                val subs = if (seed % 2 == 0) listOf(santeDeFer) else emptyList()

                suspend fun solve(
                    p: WakfuBestBuildParams,
                    hard: Boolean,
                ): SolverResult<BuildCombination> {
                    var last: SolverResult<BuildCombination>? = null
                    WakfuBuildSolver.optimize(p.copy(useSublimations = subs.isNotEmpty()), pool, emptyList(), subs, tuning, hardConstraints = hard).collect { last = it }
                    return requireNotNull(last)
                }
                val mm = solve(mostMasteries, hard = false)
                assertThat(mm.individual.isValid(CharacterClass.CRA)).isTrue()
                val mmBound = MostMasteriesCertificate.bound(mostMasteries.copy(useSublimations = subs.isNotEmpty()), pool, emptyList(), subs)?.foldedBound
                if (mmBound != null) {
                    assertThat(mmBound)
                        .describedAs("seed %d: the most-masteries bound (%d) must upper-bound the constrained soft objective (%d)", seed, mmBound, mm.mostMasteriesObjective)
                        .isGreaterThanOrEqualTo(requireNotNull(mm.mostMasteriesObjective))
                    checked++
                }
                val md = solve(soft, hard = false)
                assertThat(md.isOptimal).describedAs("seed %d: the soft max-damage oracle proves OPTIMAL on the tiny pool", seed).isTrue()
                assertThat(md.individual.isValid(CharacterClass.CRA)).isTrue()
                val softBound = MaxDamageSoftCertificate.bound(soft.copy(useSublimations = subs.isNotEmpty()), pool, emptyList(), subs)?.foldedBound
                if (softBound != null) {
                    assertThat(softBound)
                        .describedAs("seed %d: the soft bound (%d) must upper-bound the constrained soft optimum (%d)", seed, softBound, md.maxDamageObjective)
                        .isGreaterThanOrEqualTo(requireNotNull(md.maxDamageObjective))
                    checked++
                }
                println("EXCL_SOFT seed=$seed subs=${subs.size} mm=${mm.mostMasteriesObjective} mmBound=$mmBound md=${md.maxDamageObjective} softBound=$softBound")
            }
            assertThat(checked).describedAs("bounds compared (a bail is sound but checks nothing)").isGreaterThanOrEqualTo(8)
        }
}
