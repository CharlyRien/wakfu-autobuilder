package me.chosante.autobuilder.genetic.wakfu

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.autobuilder.domain.TargetStat
import me.chosante.autobuilder.domain.TargetStats
import me.chosante.autobuilder.domain.ringPairingKeys
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
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/**
 * The item EQUIP conditions the engine enforces (AGENTS.md §4 "Item equip conditions"): a nation sword is only worn with
 * its ring, mutually exclusive rings are never paired, class items go to their class only, never-equippable items are
 * gone — in the CP-SAT model, the pool filters, the domination pre-filter, the greedy warm start, `isValid` and the
 * request validation. Synthetic pools (ids ≥ 9000, conditions set explicitly) for the solver, the real catalog for the
 * data-driven filters.
 */
class EquipConditionsTest {
    private val tuning =
        WakfuBuildSolver.SolverTuning(numSearchWorkers = 1, randomSeed = 1, interleaveSearch = true, maxDeterministicTime = 30.0)
    private val tunedWithDomination = tuning.copy(applyDominationOverride = true)

    private fun item(
        id: Int,
        type: ItemType,
        fire: Int,
        rarity: Rarity = Rarity.LEGENDARY,
        name: String = "item$id",
        criterion: ItemEquipCriterion? = null,
    ) = Equipment(
        equipmentId = id,
        guiId = id,
        level = 1,
        name = I18nText(name, name, name, name),
        rarity = rarity,
        itemType = type,
        characteristics = if (fire == 0) emptyMap() else mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to fire),
        equipCriterion = criterion
    )

    private fun requires(
        id: Int,
        key: Int,
    ) = ItemEquipCriterion(id, "HasEquipmentId($key)", requiresItems = listOf(key))

    private fun forbids(
        id: Int,
        vararg others: Int,
    ) = ItemEquipCriterion(id, others.joinToString(" and ") { "not HasEquipmentId($it)" }, forbidsItems = others.toList())

    private fun params(
        clazz: CharacterClass = CharacterClass.IOP,
        level: Int = 1,
        forced: List<String> = emptyList(),
        excluded: List<String> = emptyList(),
        maxRarity: Rarity = Rarity.EPIC,
    ) = WakfuBestBuildParams(
        character = Character(clazz, level, 0, CharacterSkills(level)),
        targetStats = TargetStats(listOf(TargetStat(Characteristic.MASTERY_ELEMENTARY_FIRE, 1))),
        searchDuration = 10.seconds,
        stopWhenBuildMatch = false,
        maxRarity = maxRarity,
        forcedItems = forced,
        excludedItems = excluded,
        scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT,
        useRunes = false,
        useSublimations = false
    )

    // The nation-sword shape: S (RELIC 1H, the best weapon) needs K (EPIC ring, no stat); E is a strong EPIC amulet.
    private val sword = item(9001, ItemType.ONE_HANDED_WEAPONS, 1000, Rarity.RELIC, "sword", requires(9001, 9002))
    private val key = item(9002, ItemType.RING, 0, Rarity.EPIC, "key")
    private val weapon = item(9003, ItemType.ONE_HANDED_WEAPONS, 300)
    private val ring1 = item(9004, ItemType.RING, 200, name = "ring1")
    private val ring2 = item(9005, ItemType.RING, 150, name = "ring2")
    private val epicAmulet = item(9006, ItemType.AMULET, 900, Rarity.EPIC, "epicAmulet")
    private val amulet = item(9007, ItemType.AMULET, 100, name = "amulet")

    private fun pool(vararg items: Equipment) = items.groupBy { it.itemType }

    private fun bestBuild(
        p: WakfuBestBuildParams,
        pool: Map<ItemType, List<Equipment>>,
        t: WakfuBuildSolver.SolverTuning = tuning,
    ): BuildCombination =
        runBlocking {
            WakfuBuildSolver
                .optimize(p, pool, t)
                .toList()
                .last()
                .individual
        }

    private fun BuildCombination.ids() = equipments.map { it.equipmentId }.toSet()

    @Test
    fun `the solver never wears the sword without its ring - and pays the epic slot for it`() {
        for (t in listOf(tuning, tunedWithDomination)) {
            // Unconstrained, sword + epic amulet + two rings (2 250) wins; the sword needs its zero-stat EPIC ring, which takes
            // the epic budget and a ring slot, so the weaker free weapon + epic amulet + two rings (1 550) is the optimum.
            val withEpic = bestBuild(params(), pool(sword, key, weapon, ring1, ring2, epicAmulet, amulet), t)
            assertThat(withEpic.ids()).containsExactlyInAnyOrder(9003, 9006, 9004, 9005)
            // Without the epic amulet the sword + its ring (1 300) beats the free weapon (750): both are worn — even with the
            // domination pre-filter on, where the two plain rings used to evict the stat-less key.
            val withoutEpic = bestBuild(params(), pool(sword, key, weapon, ring1, ring2, amulet), t)
            assertThat(withoutEpic.ids()).containsExactlyInAnyOrder(9001, 9002, 9004, 9007)
            assertThat(withEpic.isValid(CharacterClass.IOP) && withoutEpic.isValid(CharacterClass.IOP)).isTrue()
        }
    }

    @Test
    fun `forcing the sword forces its ring, excluding the ring removes the sword`() {
        val p = params(forced = listOf("sword"))
        // The sword, its ring — and the best other ring in the second ring slot (the key ring takes only one).
        assertThat(bestBuild(p, pool(sword, key, weapon, ring1, ring2, epicAmulet, amulet)).ids()).contains(9001, 9002, 9004)
        assertThat(WakfuBestBuildFinderAlgorithm.validateRequest(p, allEquipments = listOf(sword, key, weapon))).isEmpty()

        val excluded = params(forced = listOf("sword"), excluded = listOf("key"))
        assertThat(WakfuBestBuildFinderAlgorithm.validateRequest(excluded, allEquipments = listOf(sword, key, weapon)))
            .containsExactly(RequestValidationProblem.ForcedItemRequirementUnavailable(sword, key.name))
        // A rarity cap below EPIC keeps the RELIC sword in band but not its ring.
        assertThat(WakfuBestBuildFinderAlgorithm.validateRequest(params(forced = listOf("sword"), maxRarity = Rarity.RELIC), listOf(sword, key)))
            .containsExactly(RequestValidationProblem.ForcedItemRequirementUnavailable(sword, key.name))
        // The forced sword brings its EPIC ring: with another forced epic item the epic budget overflows.
        assertThat(WakfuBestBuildFinderAlgorithm.validateRequest(params(forced = listOf("sword", "epicAmulet")), listOf(sword, key, epicAmulet)))
            .containsExactly(RequestValidationProblem.ForcedItemRarityBudgetExceeded(Rarity.EPIC, listOf(epicAmulet.name, key.name)))
        // ... and with two forced rings, the ring slots do.
        assertThat(WakfuBestBuildFinderAlgorithm.validateRequest(params(forced = listOf("sword", "ring1", "ring2")), listOf(sword, key, ring1, ring2)))
            .containsExactly(RequestValidationProblem.ForcedItemsSlotConflict(ItemType.RING, 2, listOf(ring1.name, ring2.name, key.name)))
    }

    @Test
    fun `rings that exclude each other are never paired - either way round`() {
        // t1 forbids t2 (t2 says nothing); t3 is weaker but free.
        val t1 = item(9011, ItemType.RING, 500, name = "t1", criterion = forbids(9011, 9012))
        val t2 = item(9012, ItemType.RING, 450, name = "t2")
        val t3 = item(9013, ItemType.RING, 100, name = "t3")
        for (t in listOf(tuning, tunedWithDomination)) {
            assertThat(bestBuild(params(), pool(t1, t2, t3), t).ids()).containsExactlyInAnyOrder(9011, 9013)
        }
        assertThat(WakfuBestBuildFinderAlgorithm.validateRequest(params(forced = listOf("t1", "t2")), listOf(t1, t2, t3)))
            .containsExactly(RequestValidationProblem.ForcedItemsMutuallyExclusive(listOf(t1.name, t2.name)))
        // Domination: t2 (≥ t3 on every stat) can't evict t3 — t2's conflict partners are no subset of t3's (none).
        val shape = DominationShape(pinned = emptySet())
        assertThat(filterDominatedPool(pool(t1, t2, t3), shape).getValue(ItemType.RING)).contains(t3)
    }

    @Test
    fun `isValid rejects every violation`() {
        val skills = CharacterSkills(1)

        fun valid(
            vararg items: Equipment,
            clazz: CharacterClass? = null,
        ) = BuildCombination(items.toList(), skills).isValid(clazz)
        assertThat(valid(sword)).isFalse()
        assertThat(valid(sword, key)).isTrue()
        val t1 = item(9011, ItemType.RING, 500, name = "t1", criterion = forbids(9011, 9012))
        val t2 = item(9012, ItemType.RING, 450, name = "t2")
        assertThat(valid(t1, t2)).isFalse()
        assertThat(valid(t2, t1)).isFalse()
        val never = item(9020, ItemType.HELMET, 10, criterion = ItemEquipCriterion(9020, "False", never = true))
        assertThat(valid(never)).isFalse()
        val iopEmblem = item(9021, ItemType.EMBLEM, 10, criterion = ItemEquipCriterion(9021, "IsBreed(\"IOP\")", classes = listOf(CharacterClass.IOP)))
        assertThat(valid(iopEmblem, clazz = CharacterClass.IOP)).isTrue()
        assertThat(valid(iopEmblem, clazz = CharacterClass.CRA)).isFalse()
        assertThat(valid(iopEmblem)).describedAs("no class given: the class is not checked").isTrue()
    }

    @Test
    fun `the greedy warm start wears a bundle whole or not at all, and never pairs excluding rings`() {
        val t1 = item(9011, ItemType.RING, 500, name = "t1", criterion = forbids(9011, 9012))
        val t2 = item(9012, ItemType.RING, 450, name = "t2")
        val t3 = item(9013, ItemType.RING, 100, name = "t3")
        // Without the sword: free weapon, t1 + t3 (t2 is excluded by t1), amulet.
        val free = MostMasteriesWarmStart.greedyBuild(params(), listOf(weapon, t1, t2, t3, amulet))!!
        assertThat(free.ids()).containsExactlyInAnyOrder(9003, 9011, 9013, 9007)
        // With it: the sword + its ring bundle (1 600) beats the free weapon (1 000) — the ring takes a ring slot.
        val bundled = MostMasteriesWarmStart.greedyBuild(params(), listOf(sword, key, weapon, t1, t2, t3, amulet))!!
        assertThat(bundled.ids()).containsExactlyInAnyOrder(9001, 9002, 9011, 9007)
        // ... but not when its ring costs a stronger epic item.
        val withEpic = MostMasteriesWarmStart.greedyBuild(params(), listOf(sword, key, weapon, t1, t2, t3, epicAmulet, amulet))!!
        assertThat(withEpic.ids()).containsExactlyInAnyOrder(9003, 9011, 9013, 9006)
        assertThat(listOf(free, bundled, withEpic)).allMatch { it.isValid(CharacterClass.IOP) }
    }

    @Test
    fun `real catalog - class items go to their class, never-equippable items are gone, the sword follows its ring`() {
        fun poolIds(p: WakfuBestBuildParams) =
            WakfuBestBuildFinderAlgorithm
                .poolFor(p)
                .values
                .flatten()
                .map { it.equipmentId }
                .toSet()
        val iop = poolIds(params(CharacterClass.IOP, 200))
        assertThat(iop).contains(15257, 20061).doesNotContain(15258, 20068, 15260, 26643) // Iop's emblem + amulet only
        assertThat(poolIds(params(CharacterClass.SACRIEUR, 200))).contains(15260, 20063).doesNotContain(15257)
        assertThat(poolIds(params(CharacterClass.XELOR, 200))).contains(26643)
        assertThat(iop).doesNotContainAnyElementsOf(listOf(32988, 33375, 33376, 33377))
        // The nation swords and their rings are in a level-200 pool; excluding a ring removes its sword, a rarity cap
        // below EPIC removes every sword.
        assertThat(iop).contains(26494, 26495, 26496, 26497, 26575, 26576, 26577, 26578)
        assertThat(poolIds(params(CharacterClass.IOP, 200, excluded = listOf("Anneau de Brâkmar")))).doesNotContain(26497, 26578).contains(26496)
        assertThat(poolIds(params(CharacterClass.IOP, 200, maxRarity = Rarity.RELIC))).doesNotContainAnyElementsOf(listOf(26494, 26495, 26496, 26497))
        // Forcing a sword and a ring keeps the sword's ring in the ring slot — beside every other ring (see below).
        val forced = WakfuBestBuildFinderAlgorithm.poolFor(params(CharacterClass.IOP, 200, forced = listOf("Epée de Brâkmar", "La Promesse")))
        assertThat(forced.getValue(ItemType.RING).map { it.name.fr }).contains("Anneau de Brâkmar", "La Promesse")
        assertThat(forced.getValue(ItemType.ONE_HANDED_WEAPONS).map { it.equipmentId }).containsExactly(26497)
    }

    /**
     * A build wears TWO rings: forcing one ring — or a nation sword, whose key ring takes one — never narrows the ring slot
     * (review of #246: the slot narrowed to the forced names, so a forced Brâkmar sword lost its second ring and CP-SAT
     * proved OPTIMAL a build 7.4 % below the true forced-sword optimum). Only the USER's forced names narrow a slot; the
     * model's `Σ same-name ≥ 1` equips each forced ring and `sword ≤ ring` the sword's ring.
     */
    @Test
    fun `real catalog - forcing a sword or a single ring leaves the second ring slot free`() {
        val unforced = WakfuBestBuildFinderAlgorithm.poolFor(params(CharacterClass.IOP, 200)).getValue(ItemType.RING).map { it.equipmentId }
        for (forced in listOf(listOf("Epée de Brâkmar"), listOf("La Promesse"), listOf("Epée de Brâkmar", "La Promesse"))) {
            val pool = WakfuBestBuildFinderAlgorithm.poolFor(params(CharacterClass.IOP, 200, forced = forced))
            assertThat(pool.getValue(ItemType.RING).map { it.equipmentId })
                .describedAs("forcing %s keeps every ring of the unforced pool", forced)
                .containsExactlyInAnyOrderElementsOf(unforced)
        }
        // The weapon slots still narrow to the forced sword (and its two-handed alternative goes).
        val sword = WakfuBestBuildFinderAlgorithm.poolFor(params(CharacterClass.IOP, 200, forced = listOf("Epée de Brâkmar")))
        assertThat(sword.getValue(ItemType.ONE_HANDED_WEAPONS).map { it.equipmentId }).containsExactly(26497)
        assertThat(sword).doesNotContainKey(ItemType.TWO_HANDED_WEAPONS)
    }

    @Test
    fun `a single forced ring is worn beside the best free ring`() {
        // ring2 is forced although ring1 is better: the build wears both, not ring2 alone.
        for (t in listOf(tuning, tunedWithDomination)) {
            val build = bestBuild(params(forced = listOf("ring2")), pool(weapon, ring1, ring2, amulet), t)
            assertThat(build.ids()).contains(9004, 9005)
        }
    }

    /**
     * The production repro of the review of #246: a level-200 Crâ most-masteries request (distance mastery, AP 12, MP 5) with the
     * Brâkmar sword forced returned 2 985 marked proven optimal, wearing only the sword's ring; the forced-sword optimum, proven on
     * an un-narrowed pool, is 3 205 with a second ring. Slow: a real search on the full level-200 catalog.
     */
    @Test
    @Tag("slow")
    fun `production - a forced nation sword still wears a second ring and reaches the true forced optimum`(): Unit =
        runBlocking {
            val p =
                params(CharacterClass.CRA, 200, forced = listOf("Epée de Brâkmar")).copy(
                    targetStats =
                        TargetStats(
                            listOf(
                                TargetStat(Characteristic.MASTERY_DISTANCE, 9999),
                                TargetStat(Characteristic.ACTION_POINT, 12),
                                TargetStat(Characteristic.MOVEMENT_POINT, 5)
                            )
                        ),
                    searchDuration = 120.seconds
                )
            val last = WakfuBestBuildFinderAlgorithm.run(p).toList().last()
            val rings =
                last.individual.equipments
                    .filter { it.itemType == ItemType.RING }
                    .map { it.name.fr }
            println("FORCED_SWORD score=${last.matchPercentage} optimal=${last.isOptimal} rings=$rings")
            assertThat(rings).contains("Anneau de Brâkmar").hasSize(2)
            assertThat(last.individual.isValid(CharacterClass.CRA)).isTrue()
            assertThat(last.matchPercentage.toDouble()).isGreaterThanOrEqualTo(3205.0)
        }

    @Test
    fun `real catalog - domination keeps the nation rings and no sword evicts a free weapon`() {
        val p = params(CharacterClass.IOP, 200)
        val shape = dominationShape(p, emptyList())!!
        val kept = filterDominatedPool(WakfuBestBuildFinderAlgorithm.poolFor(p), shape).values.flatten().map { it.equipmentId }
        // The four stat-identical zero-stat EPIC rings used to evict each other (lower id kept) and fall to any 4-socket ring.
        assertThat(kept).contains(26575, 26576, 26577, 26578)
        // Epée Eternelle (RELIC, AP 3) was dominated by the Brâkmar sword, which needs a ring it doesn't.
        assertThat(kept).contains(26593)
    }

    @Test
    fun `real catalog - request validation rejects wrong-class, never and unwearable forced items`() {
        fun catalog(id: Int) = WakfuBestBuildFinderAlgorithm.equipments.first { it.equipmentId == id }

        fun problems(p: WakfuBestBuildParams) = WakfuBestBuildFinderAlgorithm.validateRequest(p)
        val craEmblem = catalog(15258)
        assertThat(problems(params(CharacterClass.IOP, 200, forced = listOf(craEmblem.name.fr))))
            .containsExactly(RequestValidationProblem.ForcedItemWrongClass(craEmblem, listOf(CharacterClass.CRA), CharacterClass.IOP))
        assertThat(problems(params(CharacterClass.CRA, 200, forced = listOf(craEmblem.name.fr)))).isEmpty()
        val never = catalog(32988)
        assertThat(problems(params(CharacterClass.IOP, 200, forced = listOf(never.name.fr))))
            .containsExactly(RequestValidationProblem.ForcedItemNeverEquippable(never))
        // The Brâkmar sword is RELIC, its ring EPIC: a SOUVENIR cap keeps the sword in band, not the ring.
        assertThat(problems(params(CharacterClass.IOP, 200, forced = listOf("Epée de Brâkmar"), maxRarity = Rarity.SOUVENIR)))
            .containsExactly(RequestValidationProblem.ForcedItemRequirementUnavailable(catalog(26497), catalog(26578).name))
        assertThat(problems(params(CharacterClass.IOP, 200, forced = listOf("Epée de Brâkmar")))).isEmpty()
        assertThat(problems(params(CharacterClass.IOP, 200, forced = listOf("Le Tig", "Le Lieute"))))
            .containsExactly(RequestValidationProblem.ForcedItemsMutuallyExclusive(listOf(catalog(24488).name, catalog(24505).name)))
    }

    /**
     * The certificates' ring pairing key (CERTIFIER_VERSION 57): one key per clique of conflicting rings — same name, or a
     * FORBIDS either way — so a bound refuses exactly the pairs the game refuses; a component that is not a clique keeps the
     * name keys (the old relaxation: sound, looser), so no key ever separates a pair the game allows.
     */
    @Test
    fun `ring pairing keys - one key per clique of conflicting rings, names elsewhere`() {
        // A triangle by the symmetric closure: t3 lists nothing, yet t1 and t2 both forbid it.
        val t1 = item(9031, ItemType.RING, 1, name = "t1", criterion = forbids(9031, 9032, 9033))
        val t2 = item(9032, ItemType.RING, 1, name = "t2", criterion = forbids(9032, 9033))
        val t3 = item(9033, ItemType.RING, 1, name = "t3")
        // A path p1 – p2 – p3 (p1 and p3 may pair): no clique.
        val p1 = item(9041, ItemType.RING, 1, name = "p1", criterion = forbids(9041, 9042))
        val p2 = item(9042, ItemType.RING, 1, name = "p2", criterion = forbids(9042, 9043))
        val p3 = item(9043, ItemType.RING, 1, name = "p3")
        // a forbids b, but a has a same-name sibling a' that b does not exclude: {a, a', b} is no clique.
        val a = item(9051, ItemType.RING, 1, Rarity.MYTHIC, name = "a", criterion = forbids(9051, 9053))
        val aSibling = item(9052, ItemType.RING, 1, Rarity.LEGENDARY, name = "A")
        val b = item(9053, ItemType.RING, 1, name = "b")
        // A plain name class, and a ring whose FORBIDS partner is not a ring of the pool.
        val n1 = item(9061, ItemType.RING, 1, Rarity.MYTHIC, name = "n")
        val n2 = item(9062, ItemType.RING, 1, Rarity.LEGENDARY, name = "N")
        val lone = item(9071, ItemType.RING, 1, name = "lone", criterion = forbids(9071, 9999))
        val keys = ringPairingKeys(listOf(t1, t2, t3, p1, p2, p3, a, aSibling, b, n1, n2, lone))
        assertThat(setOf(keys[9031], keys[9032], keys[9033])).containsExactly("forbids#9031")
        assertThat(listOf(keys[9041], keys[9042], keys[9043])).containsExactly("p1", "p2", "p3")
        assertThat(listOf(keys[9051], keys[9052], keys[9053])).containsExactly("a", "a", "b")
        assertThat(listOf(keys[9061], keys[9062], keys[9071])).containsExactly("n", "n", "lone")
    }

    @Test
    fun `real catalog - the ring pairing keys join the five excluding triples, every other ring keeps its name`() {
        val rings = WakfuBestBuildFinderAlgorithm.equipments.filter { it.itemType == ItemType.RING }
        val keys = ringPairingKeys(rings)
        val joined = rings.filter { keys.getValue(it.equipmentId).startsWith("forbids#") }.groupBy({ keys.getValue(it.equipmentId) }, { it.equipmentId })
        assertThat(joined.values.map { it.toSet() }).containsExactlyInAnyOrder(
            setOf(24392, 24408, 24426),
            setOf(24440, 24456, 24473),
            setOf(24488, 24505, 24521),
            setOf(24537, 24552, 24567),
            setOf(24585, 24601, 24615)
        )
        // Issé Sceau's three rarities exclude each other AND share a name: their name key already says so.
        assertThat(listOf(19698, 22226, 22227).map { keys.getValue(it) }.toSet()).containsExactly("issé sceau")
        assertThat(rings.filter { keys.getValue(it.equipmentId) != it.name.fr.lowercase() }).hasSize(15)
    }
}
