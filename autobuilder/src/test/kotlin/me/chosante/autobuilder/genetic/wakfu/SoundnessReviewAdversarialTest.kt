package me.chosante.autobuilder.genetic.wakfu

import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.autobuilder.domain.TargetStat
import me.chosante.autobuilder.domain.TargetStats
import me.chosante.autobuilder.genetic.SolverResult
import me.chosante.common.Character
import me.chosante.common.CharacterClass
import me.chosante.common.Characteristic
import me.chosante.common.Equipment
import me.chosante.common.I18nText
import me.chosante.common.ItemType
import me.chosante.common.Rarity
import me.chosante.common.Sublimation
import me.chosante.common.skills.CharacterSkills
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Adversarial soundness review (2026-10-02) of the most-masteries certificate v41–v43 and the max-damage AP-cell
 * certifier v44. Deterministic counterexamples (CI-runnable) + env-gated fuzz harnesses. The B1 and A1 repros are CI
 * locks since their fixes (CERTIFIER_VERSION 49 and 50).
 */
class SoundnessReviewAdversarialTest {
    private val tuning =
        WakfuBuildSolver.SolverTuning(numSearchWorkers = 1, randomSeed = 1, interleaveSearch = true, maxDeterministicTime = 60.0)

    private fun item(
        id: Int,
        type: ItemType,
        rarity: Rarity = Rarity.LEGENDARY,
        stats: Map<Characteristic, Int>,
        sockets: Int = 3,
        name: String = "item$id",
    ) = Equipment(
        equipmentId = id,
        guiId = id,
        level = 200,
        name = I18nText(name, name, "", ""),
        rarity = rarity,
        itemType = type,
        characteristics = stats,
        maxShardSlots = sockets
    )

    private fun mmParams(
        targets: List<TargetStat>,
        level: Int = 200,
        useRunes: Boolean = false,
        excludedSublimations: List<String> = emptyList(),
    ) = WakfuBestBuildParams(
        character = Character(CharacterClass.CRA, level, 0, CharacterSkills(level)),
        targetStats = TargetStats(targets),
        searchDuration = 60.seconds,
        stopWhenBuildMatch = false,
        maxRarity = Rarity.EPIC,
        forcedItems = emptyList(),
        excludedItems = emptyList(),
        scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT,
        useRunes = useRunes,
        useSublimations = true,
        excludedSublimations = excludedSublimations
    )

    private suspend fun pinnedSoft(
        p: WakfuBestBuildParams,
        pool: Map<ItemType, List<Equipment>>,
        subs: List<Sublimation>,
        hard: Boolean = false,
    ): SolverResult<BuildCombination>? {
        var last: SolverResult<BuildCombination>? = null
        WakfuBuildSolver.optimize(p, pool, emptyList(), subs, tuning, hardConstraints = hard).collect { last = it }
        return last
    }

    /**
     * FINDING A1 — the assume-CC world's LOW crit dim is clamped at 0 after every stage
     * (`MostMasteriesCertificate.applyOneRaw`: `(cc(k) + o.ccLowRaw).coerceIn(0, threshold + 1)`). Rings run FIRST, so
     * a −10-crit ring (real catalog: Tyra 'neau, Ann'Othan, Sortie d'Automne, L'un seul…) drives the real pre-combat
     * crit NEGATIVE (3 − 10 = −7; the solver only floors the PRE-SUB sheet at −9) while the dim stops at 0; a later +12
     * crit amulet then reads 12 > 10 in the dim although the real read is 5 ≤ 10. The CRIT_AT_MOST-10 carrier
     * (Constance, +20 DI) is REJECTED in its own world ⇒ the bound under-counts the optimum.
     * FIXED in CERTIFIER_VERSION 50 (the LOW dims are stored with an offset, never floored) — now a CI lock (3 102 → 3 666).
     */
    @Test
    fun `A1 low crit dim floored at zero rejects a real Constance carrier`(): Unit =
        kotlinx.coroutines.runBlocking {
            val constance = WakfuBestBuildFinderAlgorithm.sublimations.single { it.name.fr == "Constance" }
            val pool =
                listOf(
                    item(1, ItemType.RING, stats = mapOf(Characteristic.MASTERY_DISTANCE to 1000, Characteristic.CRITICAL_HIT to -10)),
                    item(2, ItemType.AMULET, stats = mapOf(Characteristic.MASTERY_DISTANCE to 1000, Characteristic.CRITICAL_HIT to 12)),
                    item(3, ItemType.HELMET, Rarity.EPIC, mapOf(Characteristic.MASTERY_DISTANCE to 500))
                ).groupBy { it.itemType }
            val p = mmParams(listOf(TargetStat(Characteristic.MASTERY_DISTANCE, 9999)))
            val subs = listOf(constance)
            val optimum = requireNotNull(pinnedSoft(p, pool, subs))
            val incumbent = requireNotNull(optimum.mostMasteriesObjective)
            val carried =
                optimum.individual.sublimations.values
                    .flatten()
                    .map { it.name.fr }
            val bound = requireNotNull(MostMasteriesCertificate.bound(p, pool, emptyList(), subs))
            println(
                "REVIEW_A1 optimum=$incumbent optimal=${optimum.isOptimal} carried=$carried items=${optimum.individual.equipments.map { it.name.fr }} " +
                    "coreBound=${bound.coreBound} folded=${bound.foldedBound} binding=${bound.bindingState}"
            )
            // A badge-level consequence: the best NON-Constance build (what a truncated search can return) is "proven".
            val noSub = requireNotNull(pinnedSoft(p, pool, emptyList()))
            val verdict = WakfuBestBuildFinderAlgorithm.compareMostMasteriesQuality(p, bound, noSub.copy(isOptimal = false))
            println("REVIEW_A1 noSubIncumbent=${noSub.mostMasteriesObjective} verdict=$verdict (true optimum $incumbent)")
            assertThat(optimum.isOptimal).isTrue()
            assertThat(carried).contains("Constance")
            assertThat(bound.coreBound)
                .describedAs("SOUNDNESS — coreBound must upper-bound the pinned CP-SAT optimum carrying Constance")
                .isGreaterThanOrEqualTo(incumbent)
        }

    /**
     * Same shape on the REAL sublimation catalog with Mesure III excluded (or capped out by maxSublimationTier ≤ 2):
     * Constance is then the only CRIT_AT_MOST +20 DI sub, so no other world covers the rejected carrier.
     * FIXED in CERTIFIER_VERSION 50 — CI lock.
     */
    @Test
    fun `A1 on the real catalog without Mesure III`(): Unit =
        kotlinx.coroutines.runBlocking {
            val pool =
                listOf(
                    item(1, ItemType.RING, stats = mapOf(Characteristic.MASTERY_DISTANCE to 1000, Characteristic.CRITICAL_HIT to -10)),
                    item(2, ItemType.AMULET, stats = mapOf(Characteristic.MASTERY_DISTANCE to 1000, Characteristic.CRITICAL_HIT to 12)),
                    item(3, ItemType.HELMET, Rarity.EPIC, mapOf(Characteristic.MASTERY_DISTANCE to 500, Characteristic.ACTION_POINT to 2)),
                    item(4, ItemType.BOOTS, stats = mapOf(Characteristic.MASTERY_DISTANCE to 300, Characteristic.ACTION_POINT to 2)),
                    item(5, ItemType.CAPE, stats = mapOf(Characteristic.MASTERY_DISTANCE to 300, Characteristic.ACTION_POINT to 1))
                ).groupBy { it.itemType }
            // AP 11 kills Inflexibilité (AP ≤ 10); Mesure III excluded by the user.
            val p =
                mmParams(
                    listOf(TargetStat(Characteristic.MASTERY_DISTANCE, 9999), TargetStat(Characteristic.ACTION_POINT, 11)),
                    excludedSublimations = listOf("Mesure III")
                )
            val subs = WakfuBestBuildFinderAlgorithm.activeSublimations(p)
            val optimum = requireNotNull(pinnedSoft(p, pool, subs))
            val incumbent = requireNotNull(optimum.mostMasteriesObjective)
            val carried =
                optimum.individual.sublimations.values
                    .flatten()
                    .map { it.name.fr }
            val bound = requireNotNull(MostMasteriesCertificate.bound(p, pool, emptyList(), subs))
            println(
                "REVIEW_A1_REAL optimum=$incumbent optimal=${optimum.isOptimal} carried=$carried items=${optimum.individual.equipments.map { it.name.fr }} " +
                    "folded=${bound.foldedBound} hard=${bound.hardFoldedBound} binding=${bound.bindingState}"
            )
            val hard = requireNotNull(pinnedSoft(p, pool, subs, hard = true))
            println("REVIEW_A1_REAL hardOptimum=${hard.mostMasteriesObjective} hardCarried=${hard.individual.sublimations.values.flatten().map { it.name.fr }}")
            assertThat(optimum.isOptimal).isTrue()
            assertThat(bound.foldedBound)
                .describedAs("SOUNDNESS — the soft read must upper-bound the pinned CP-SAT soft optimum")
                .isGreaterThanOrEqualTo(incumbent)
            assertThat(bound.hardFoldedBound)
                .describedAs("SOUNDNESS — the targets-met read must upper-bound the pinned CP-SAT hard-leg optimum")
                .isGreaterThanOrEqualTo(requireNotNull(hard.mostMasteriesObjective))
        }

    /**
     * A1 on REAL level-245 items: the EPIC ring Tyra 'neau (−10 crit) is the only epic carrier for Constance, so every
     * Constance build stages its −10 in the FIRST (rings) stage. Real catalog sublimations, Mesure III excluded by the
     * user. Measured 2026-10-02: CP-SAT optimum 73 778 222 000 909 (both legs OPTIMAL, carries Constance + Tyra 'neau +
     * Le Yannarc / Sir Comte Flex / Emblème de l'horloger II: pre-combat crit 3 − 10 + 15 = 8) vs certificate 73 180 020
     * 209 999 on the soft AND the targets-met read (−0.81%). FIXED in CERTIFIER_VERSION 50 (now 76 809 111 129 999,
     * +4.1%) — CI lock.
     */
    @Test
    fun `A1 on real level-245 items with Tyra neau`(): Unit =
        kotlinx.coroutines.runBlocking {
            val ids =
                setOf(32087, 29529, 29083, 32565, 32084, 29176, 30180, 29064, 32510, 30319, 32177, 32504, 32545, 32092, 32485, 29635, 32614)
            val pool = WakfuBestBuildFinderAlgorithm.equipments.filter { it.equipmentId in ids }.groupBy { it.itemType }
            val withRunes = System.getenv("WAKFU_REVIEW_A1_RUNES") == "1"
            val underCounts = mutableListOf<String>()
            val p =
                mmParams(
                    listOf(TargetStat(Characteristic.MASTERY_DISTANCE, 9999), TargetStat(Characteristic.ACTION_POINT, 11)),
                    level = 245,
                    useRunes = withRunes,
                    excludedSublimations = listOf("Mesure III")
                )
            val runes = if (withRunes) WakfuBestBuildFinderAlgorithm.runes else emptyList()
            val subs = WakfuBestBuildFinderAlgorithm.activeSublimations(p)
            val t = WakfuBuildSolver.SolverTuning(numSearchWorkers = 1, randomSeed = 1, interleaveSearch = true, maxDeterministicTime = 400.0)
            for (hard in listOf(true, false)) {
                var last: SolverResult<BuildCombination>? = null
                var outcome: WakfuBuildSolver.SolveOutcome? = null
                WakfuBuildSolver.optimize(p, pool, runes, subs, t, hardConstraints = hard, onTermination = { outcome = it }).collect { last = it }
                val r = requireNotNull(last)
                val bound = requireNotNull(MostMasteriesCertificate.bound(p, pool, runes, subs))
                val upper = bound.comparableUpper(hardLeg = hard, hasRequiredTargets = true)
                println(
                    "REVIEW_A1_ITEMS hard=$hard status=${outcome?.status} optimum=${r.mostMasteriesObjective} upper=$upper " +
                        "items=${r.individual.equipments.map { it.name.fr }} subs=${r.individual.sublimations.values.flatten().map { it.name.fr }} " +
                        "binding=${if (hard) bound.hardBindingState else bound.bindingState}"
                )
                val optimum = r.mostMasteriesObjective
                if (outcome?.status == com.google.ortools.sat.CpSolverStatus.OPTIMAL && optimum != null && upper < optimum) {
                    underCounts += "hard=$hard upper=$upper < optimum=$optimum"
                }
            }
            assertThat(underCounts).describedAs("SOUNDNESS — real-catalog Constance carrier").isEmpty()
        }

    /**
     * A1's twin in the MAX-DAMAGE SOFT certificate (a clone of the MM stage DP: `MaxDamageSoftCertificate.applyOne`
     * clamps the assume-CC LOW dim with the same `coerceIn(0, threshold + 1)`). Same pool shape, fire damage, a
     * trivially-met HP target so the soft fold is the full-targets multiplier. Checks the plain and the production
     * ("banded") settings against the pinned soft CP-SAT optimum. FIXED in CERTIFIER_VERSION 50 — CI lock.
     */
    @Test
    fun `A1 twin in the max-damage soft certificate`(): Unit =
        kotlinx.coroutines.runBlocking {
            val constance = WakfuBestBuildFinderAlgorithm.sublimations.single { it.name.fr == "Constance" }
            val pool =
                listOf(
                    item(1, ItemType.RING, stats = mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 1000, Characteristic.CRITICAL_HIT to -10)),
                    item(2, ItemType.AMULET, stats = mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 1000, Characteristic.CRITICAL_HIT to 12)),
                    item(3, ItemType.HELMET, Rarity.EPIC, mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 500))
                ).groupBy { it.itemType }
            val params =
                WakfuBestBuildParams(
                    character = Character(CharacterClass.CRA, 200, 0, CharacterSkills(200)),
                    targetStats = TargetStats(listOf(TargetStat(Characteristic.HP, 500))),
                    searchDuration = 60.seconds,
                    stopWhenBuildMatch = false,
                    maxRarity = Rarity.EPIC,
                    forcedItems = emptyList(),
                    excludedItems = emptyList(),
                    scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE,
                    useRunes = false,
                    useSublimations = true,
                    damageScenario =
                        me.chosante.autobuilder.domain.DamageScenario(
                            element = me.chosante.autobuilder.domain.SpellElement.FIRE,
                            rangeBand = me.chosante.autobuilder.domain.RangeBand.DISTANCE,
                            orientation = me.chosante.autobuilder.domain.Orientation.FACE
                        )
                )
            val subs = listOf(constance)
            var last: SolverResult<BuildCombination>? = null
            WakfuBuildSolver.optimize(params, pool, emptyList(), subs, tuning, hardConstraints = false).collect { last = it }
            val exact = requireNotNull(last)
            val optimum = requireNotNull(exact.maxDamageObjective)
            val plain = MaxDamageSoftCertificate.bound(params, pool, emptyList(), subs, blockGate = false)
            val banded =
                MaxDamageSoftCertificate.bound(
                    params,
                    pool,
                    emptyList(),
                    subs,
                    blockGate = false,
                    ccSupportLambda = 1500L,
                    ccSupportBand = 5,
                    coupleSecondaryItemNegative = true,
                    netSecondaryItemBudget = true,
                    exactNormalSubPacking = true,
                    foldNegativeItemAp = true,
                    foldNegativeMaxMp = true,
                    critAwareCollapse = true,
                    critWeightAnchorPercent = 100,
                    anchorConstTransport = true,
                    stateDependentMpRamp = true
                )
            println(
                "REVIEW_A1_MDSOFT optimum=$optimum optimal=${exact.isOptimal} subs=${exact.individual.sublimations.values.flatten().map { it.name.fr }} " +
                    "items=${exact.individual.equipments.map { it.name.fr }} plain=${plain?.foldedBound} banded=${banded?.foldedBound} binding=${plain?.bindingState}"
            )
            // Control: the same items with the crit signs swapped between the ring and the amulet (real pre-combat crit
            // unchanged at 5): the running LOW sum never dips below 0, so the Constance world keeps the carrier.
            val swapped =
                listOf(
                    item(1, ItemType.RING, stats = mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 1000, Characteristic.CRITICAL_HIT to 12)),
                    item(2, ItemType.AMULET, stats = mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 1000, Characteristic.CRITICAL_HIT to -10)),
                    item(3, ItemType.HELMET, Rarity.EPIC, mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 500))
                ).groupBy { it.itemType }
            var lastSwapped: SolverResult<BuildCombination>? = null
            WakfuBuildSolver.optimize(params, swapped, emptyList(), subs, tuning, hardConstraints = false).collect { lastSwapped = it }
            val bandedSwapped =
                MaxDamageSoftCertificate.bound(
                    params,
                    swapped,
                    emptyList(),
                    subs,
                    blockGate = false,
                    ccSupportLambda = 1500L,
                    ccSupportBand = 5,
                    coupleSecondaryItemNegative = true,
                    netSecondaryItemBudget = true,
                    exactNormalSubPacking = true,
                    foldNegativeItemAp = true,
                    foldNegativeMaxMp = true,
                    critAwareCollapse = true,
                    critWeightAnchorPercent = 100,
                    anchorConstTransport = true,
                    stateDependentMpRamp = true
                )
            println("REVIEW_A1_MDSOFT_CONTROL optimum=${lastSwapped?.maxDamageObjective} banded=${bandedSwapped?.foldedBound}")
            assertThat(exact.isOptimal).isTrue()
            assertThat(requireNotNull(plain).foldedBound).describedAs("plain soft DP ≥ soft optimum").isGreaterThanOrEqualTo(optimum)
            assertThat(requireNotNull(banded).foldedBound).describedAs("banded soft DP ≥ soft optimum").isGreaterThanOrEqualTo(optimum)
        }

    /**
     * FINDING B-1 (pre-existing since the v40 tree, FIXED in CERTIFIER_VERSION 49 — now a CI lock): when an MP→DI ramp
     * sub is in the model (Poids Plume III — always, on the default catalog), `mpRampEnabled` gives every MP skill var
     * its MP coefficient, and the skill-branch cells (`branchCells` / `branchCellsF` / the explain `skills:` options in
     * MaxDamageCertifier) only kept PURE-MP vars on the mp axis (`it.m == 0L`) and only `mp == 0L` vars in the graw
     * fill. The Major "Movement Point and damage" point is PAIRED (+1 MP AND +20 elemental mastery) ⇒ it landed in
     * NEITHER list and was silently dropped: its +20 mastery and its +1 MP (the ramp's source) were lost. Level 175
     * (4 Major points), items + base give MP 7 ⇒ the Major point lifts the ramp from 18 to 24 DI. Before v49: AP-6
     * exact 900 990 / fast 903 120 vs CP-SAT 951 400, AP-7 exact 1 008 855 vs 1 047 015, ledger max 1 008 855 and a
     * ProvenOptimal verdict on a 3.8 % sub-optimal build. Since v49 the paired var has its own split (`mpGrawSplits`)
     * in every pass: both cells are tight (exact == CP-SAT) and that build reads ProvenWithin 3.78 %.
     */
    @Test
    fun `B1 paired Major MP point dropped from the certifier skill cells when an MP ramp sub is modeled`() {
        val poidsPlume = WakfuBestBuildFinderAlgorithm.sublimations.single { it.name.fr == "Poids Plume III" }
        val pool =
            listOf(
                item(1, ItemType.HELMET, stats = mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 1000), sockets = 0),
                item(2, ItemType.BOOTS, stats = mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 500, Characteristic.MOVEMENT_POINT to 2), sockets = 3),
                item(3, ItemType.CAPE, stats = mapOf(Characteristic.MASTERY_ELEMENTARY_FIRE to 300, Characteristic.MOVEMENT_POINT to 2), sockets = 0)
            ).groupBy { it.itemType }
        val params =
            WakfuBestBuildParams(
                character = Character(CharacterClass.CRA, 175, 0, CharacterSkills(175)),
                targetStats = TargetStats(emptyList()),
                searchDuration = 60.seconds,
                stopWhenBuildMatch = false,
                maxRarity = Rarity.EPIC,
                forcedItems = emptyList(),
                excludedItems = emptyList(),
                scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE,
                useRunes = false,
                useSublimations = true,
                damageScenario =
                    me.chosante.autobuilder.domain.DamageScenario(
                        element = me.chosante.autobuilder.domain.SpellElement.FIRE,
                        rangeBand = me.chosante.autobuilder.domain.RangeBand.DISTANCE,
                        orientation = me.chosante.autobuilder.domain.Orientation.FACE
                    )
            )
        val underCounts = mutableListOf<String>()
        for ((label, subs) in listOf("withPoidsPlume" to listOf(poidsPlume), "control-noRampSub" to emptyList())) {
            val (exact, fast, tier15) = WakfuBuildSolver.certifierExactFastTier15CellObjectivesForTest(params, pool, emptyList(), subs, applyDomination = false)
            for (ap in exact.keys.sorted()) {
                val profile =
                    WakfuBuildSolver.timedMaxDamageProfileForTest(
                        params.copy(maxDamageApTarget = ap),
                        pool,
                        emptyList(),
                        subs,
                        workers = 1,
                        seconds = 30.0,
                        applyDomination = false,
                        deterministicLimit = 10.0
                    )
                if (!profile.hasSolution || profile.status != "OPTIMAL") continue
                println(
                    "REVIEW_B1 $label AP=$ap raw=${profile.rawObjective} exact=${exact[ap]} t15=${tier15[ap]} fast=${fast[ap]} " +
                        "MP=${profile.actualStats[Characteristic.MOVEMENT_POINT]} subs=${profile.selectedSublimationStateIds}"
                )
                for ((pass, v) in listOf("exact" to exact[ap], "tier15" to tier15[ap], "fast" to fast[ap])) {
                    if (v != null && v >= 0 && v < profile.rawObjective) underCounts += "$label AP=$ap $pass=$v < CP-SAT ${profile.rawObjective}"
                }
            }
            val ledger = WakfuBuildSolver.certifyLedgerForTest(params, pool, emptyList(), subs, applyDomination = false, forceTier2All = true)
            val truth =
                WakfuBuildSolver.timedMaxDamageProfileForTest(
                    params,
                    pool,
                    emptyList(),
                    subs,
                    workers = 1,
                    seconds = 30.0,
                    applyDomination = false,
                    deterministicLimit = 10.0
                )
            println("REVIEW_B1 $label ledgerMax=${ledger.maxCellObjective} freeOptimum=${truth.rawObjective} status=${truth.status}")
            if (truth.status == "OPTIMAL" && (ledger.maxCellObjective ?: Long.MAX_VALUE) < truth.rawObjective) {
                underCounts += "$label LEDGER max=${ledger.maxCellObjective} < free optimum ${truth.rawObjective}"
            }
        }
        // The explain path (the E8 construct's provenance replay) must mirror the exact pass's skill cells: the winning
        // AP-7 state spends the paired point, so its backtrack has to find that option instead of breaking.
        val explain = WakfuBuildSolver.certifierExplainForTest(params, pool, emptyList(), listOf(poidsPlume), applyDomination = false, cell = 7)
        println("REVIEW_B1_EXPLAIN ${explain.joinToString(" | ")}")
        if (explain.any { "???" in it }) underCounts += "provenance backtrack broken: ${explain.filter { "???" in it }}"
        if (explain.none { "mpGrawPts=1" in it }) underCounts += "provenance does not credit the paired Major MP point: $explain"
        // Badge-level consequence: the E8 construct accepts any build whose proxy reaches the (under-counted) ledger max
        // and flags it isOptimal — with a weak incumbent it may "prove" a build below the true optimum.
        val e8 =
            kotlinx.coroutines.runBlocking {
                WakfuBuildSolver.dpConstructProvenOptimum(params, pool, emptyList(), listOf(poidsPlume), incumbentObjective = 800_000L, fallbackWallCapSeconds = 30.0)
            }
        val trueOptimum =
            WakfuBuildSolver
                .timedMaxDamageProfileForTest(params, pool, emptyList(), listOf(poidsPlume), workers = 1, seconds = 30.0, applyDomination = false, deterministicLimit = 10.0)
                .rawObjective
        println(
            "REVIEW_B1_E8 constructed=${e8 != null} isOptimal=${e8?.isOptimal} proxy=${e8?.maxDamageRawProxy ?: e8?.maxDamageObjective} trueOptimum=$trueOptimum " +
                "items=${e8?.individual?.equipments?.map { it.name.fr }}"
        )
        if (e8 != null) {
            val proxy = e8.maxDamageRawProxy ?: e8.maxDamageObjective ?: Long.MIN_VALUE
            if (proxy < trueOptimum) underCounts += "E8 'proven optimal' build proxy=$proxy < true optimum $trueOptimum"
        }
        // The production verdict on a REAL, feasible but sub-optimal incumbent: the best AP-7 build that does NOT spend the
        // Major MP point (MP pinned at 7 — exactly what the certifier can see) is compared with the ledger.
        val weak =
            kotlinx.coroutines.runBlocking {
                var last: SolverResult<BuildCombination>? = null
                WakfuBuildSolver
                    .optimize(params.copy(maxDamageApTarget = 7, maxDamageMpPin = 7), pool, emptyList(), listOf(poidsPlume), tuning)
                    .collect { last = it }
                last
            }
        if (weak != null) {
            val verdict = MaxDamageSearch.proveOptimality(params, pool, emptyList(), listOf(poidsPlume), weak.copy(isOptimal = false), threads = 1)
            println("REVIEW_B1_VERDICT weakProxy=${weak.maxDamageRawProxy ?: weak.maxDamageObjective} verdict=$verdict trueOptimum=$trueOptimum")
            if (verdict == MaxDamageSearch.MaxDamageProof.ProvenOptimal && (weak.maxDamageRawProxy ?: weak.maxDamageObjective ?: 0L) < trueOptimum) {
                underCounts += "WRONG BADGE: ProvenOptimal on proxy=${weak.maxDamageRawProxy ?: weak.maxDamageObjective} < true optimum $trueOptimum"
            }
        }
        assertThat(underCounts).describedAs("SOUNDNESS — the AP-cell certifier must never under-count a pinned CP-SAT cell").isEmpty()
    }

    /**
     * CONTROL for A1: the SAME items with the signs swapped between slots (the +12 crit on the ring, the −10 on the
     * amulet) — the real pre-combat crit is still 5, but the running LOW sum never dips below 0, so the Constance
     * world accepts the carrier and the bound is sound. Isolates the root cause to the stage-order × 0-floor clamp.
     */
    @Test
    fun `A1 control - same build with the negative crit staged after the positive one is covered`(): Unit =
        kotlinx.coroutines.runBlocking {
            val constance = WakfuBestBuildFinderAlgorithm.sublimations.single { it.name.fr == "Constance" }
            val pool =
                listOf(
                    item(1, ItemType.RING, stats = mapOf(Characteristic.MASTERY_DISTANCE to 1000, Characteristic.CRITICAL_HIT to 12)),
                    item(2, ItemType.AMULET, stats = mapOf(Characteristic.MASTERY_DISTANCE to 1000, Characteristic.CRITICAL_HIT to -10)),
                    item(3, ItemType.HELMET, Rarity.EPIC, mapOf(Characteristic.MASTERY_DISTANCE to 500))
                ).groupBy { it.itemType }
            val p = mmParams(listOf(TargetStat(Characteristic.MASTERY_DISTANCE, 9999)))
            val optimum = requireNotNull(pinnedSoft(p, pool, listOf(constance)))
            val bound = requireNotNull(MostMasteriesCertificate.bound(p, pool, emptyList(), listOf(constance)))
            println("REVIEW_A1_CONTROL optimum=${optimum.mostMasteriesObjective} coreBound=${bound.coreBound}")
            assertThat(bound.coreBound).isGreaterThanOrEqualTo(requireNotNull(optimum.mostMasteriesObjective))
        }

    // ------------------------------------------------------------------------------------------------------------
    // MM FUZZ (manual): seeded random pools × real choosable subs × random targets; pinned CP-SAT soft + hard legs
    //   WAKFU_REVIEW_MM_FUZZ=<cases> [WAKFU_REVIEW_MM_SEED0=<seed>] [WAKFU_REVIEW_MM_NO_NEG_CC=1]
    // ------------------------------------------------------------------------------------------------------------

    private val mmRequestable =
        listOf(
            Characteristic.MASTERY_DISTANCE,
            Characteristic.MASTERY_DISTANCE,
            Characteristic.MASTERY_MELEE,
            Characteristic.MASTERY_CRITICAL,
            Characteristic.MASTERY_BACK,
            Characteristic.MASTERY_BERSERK,
            Characteristic.MASTERY_HEALING
        )

    private class MmCase(
        val label: String,
        val params: WakfuBestBuildParams,
        val pool: Map<ItemType, List<Equipment>>,
        val subs: List<Sublimation>,
        val negCcWithCritCap: Boolean,
    )

    private fun mmFuzzCase(
        seed: Long,
        noNegCc: Boolean,
    ): MmCase {
        val rng = java.util.Random(seed)
        val level = listOf(110, 200, 200, 245)[rng.nextInt(4)]
        val requested = (0 until 1 + rng.nextInt(2)).map { mmRequestable[rng.nextInt(mmRequestable.size)] }.distinct()
        val targets = mutableListOf<TargetStat>()
        requested.forEach { targets += TargetStat(it, 9999) }
        if (rng.nextInt(2) == 0) targets += TargetStat(Characteristic.ACTION_POINT, listOf(0, 7, 8, 9, 10, 11, 12, 17)[rng.nextInt(8)])
        if (rng.nextInt(5) < 2) targets += TargetStat(Characteristic.MOVEMENT_POINT, listOf(0, 4, 5, 6, 9)[rng.nextInt(5)])
        if (rng.nextInt(5) < 2) targets += TargetStat(Characteristic.CRITICAL_HIT, listOf(0, 10, 20, 35, 50)[rng.nextInt(5)])
        if (rng.nextInt(5) < 2) targets += TargetStat(Characteristic.HP, listOf(0, 1500, 3000, 4500)[rng.nextInt(4)])
        val secondaries =
            me.chosante.common.SECONDARY_MASTERY_CHARACTERISTICS
                .toList()
        var id = (seed % 100_000).toInt() * 100
        val items = mutableListOf<Equipment>()

        fun randomItem(
            type: ItemType,
            name: String,
        ): Equipment {
            val stats = mutableMapOf<Characteristic, Int>()
            for (m in requested) {
                if (rng.nextInt(10) < 8) stats[m] = 100 + rng.nextInt(500)
                if (rng.nextInt(10) == 0) stats[m] = -(20 + rng.nextInt(120))
            }
            if (rng.nextInt(10) < 3) {
                val o = secondaries[rng.nextInt(secondaries.size)]
                if (o !in requested) stats[o] = rng.nextInt(400) - 200
            }
            if (rng.nextInt(10) < 4) stats[Characteristic.CRITICAL_HIT] = if (noNegCc) 1 + rng.nextInt(12) else rng.nextInt(23) - 10
            if (rng.nextInt(10) < 3) stats[Characteristic.ACTION_POINT] = rng.nextInt(4) - 1
            if (rng.nextInt(20) == 0) stats[Characteristic.MAX_ACTION_POINT] = -1
            if (rng.nextInt(4) == 0) stats[Characteristic.MOVEMENT_POINT] = rng.nextInt(3)
            if (rng.nextInt(10) < 4) stats[Characteristic.HP] = 50 + rng.nextInt(650)
            if (rng.nextInt(5) == 0) stats[Characteristic.DAMAGE_INFLICTED] = 1 + rng.nextInt(12)
            if (rng.nextInt(7) == 0) stats[Characteristic.BLOCK_PERCENTAGE] = 3 + rng.nextInt(23)
            val rarity =
                when (rng.nextInt(12)) {
                    0, 1 -> Rarity.EPIC
                    2 -> Rarity.RELIC
                    else -> Rarity.LEGENDARY
                }
            id++
            return Equipment(
                equipmentId = id,
                guiId = id,
                level = minOf(level, 200),
                name = I18nText("$name$id", "$name$id", "", ""),
                rarity = rarity,
                itemType = type,
                characteristics = stats.filterValues { it != 0 },
                maxShardSlots = listOf(0, 2, 3, 4)[rng.nextInt(4)]
            )
        }
        val singles = listOf(ItemType.HELMET, ItemType.CAPE, ItemType.BELT, ItemType.BOOTS, ItemType.AMULET, ItemType.CHEST_PLATE, ItemType.SHOULDER_PADS, ItemType.EMBLEM)
        for (slot in singles.shuffled(rng).take(3 + rng.nextInt(4))) repeat(1 + rng.nextInt(3)) { items += randomItem(slot, "it") }
        if (rng.nextBoolean()) repeat(2 + rng.nextInt(2)) { items += randomItem(ItemType.RING, "ring") }
        when (rng.nextInt(3)) {
            0 -> items += randomItem(ItemType.TWO_HANDED_WEAPONS, "w2")
            1 -> {
                items += randomItem(ItemType.ONE_HANDED_WEAPONS, "w1")
                items += randomItem(ItemType.OFF_HAND_WEAPONS, "off")
            }
            else -> {}
        }
        val catalog = WakfuBestBuildFinderAlgorithm.sublimations.filter { it.solverChoosable && it.bestElementConcentration == null }
        val capSubs =
            catalog.filter {
                it.condition?.type in
                    setOf(
                        me.chosante.common.SublimationConditionType.CRIT_AT_MOST,
                        me.chosante.common.SublimationConditionType.AP_AT_MOST
                    )
            }
        var subs = (catalog.shuffled(rng).take(4 + rng.nextInt(9)) + (if (rng.nextInt(10) < 7) listOf(capSubs[rng.nextInt(capSubs.size)]) else emptyList())).distinct()
        // FOCUS mode (T1 / T5 / AP-MP saturation shapes): a CC target above the crit-cap thresholds, a crit-cap sub, the
        // start-of-combat / permanent crit subs, the AP / MP riders and an above-cap AP / MP target. Second RNG, so the
        // base case of a seed is unchanged when focus is off.
        if (System.getenv("WAKFU_REVIEW_MM_FOCUS") == "1") {
            val f = java.util.Random(seed * 31 + 7)
            targets.removeAll { it.characteristic == Characteristic.CRITICAL_HIT }
            targets += TargetStat(Characteristic.CRITICAL_HIT, listOf(20, 35, 50, 70, 90)[f.nextInt(5)])
            if (f.nextBoolean()) {
                targets.removeAll { it.characteristic == Characteristic.ACTION_POINT }
                targets += TargetStat(Characteristic.ACTION_POINT, listOf(11, 16, 17)[f.nextInt(3)])
            }
            if (f.nextInt(5) < 2) {
                targets.removeAll { it.characteristic == Characteristic.MOVEMENT_POINT }
                targets += TargetStat(Characteristic.MOVEMENT_POINT, listOf(6, 8, 9)[f.nextInt(3)])
            }
            val named =
                setOf("Influence vitale III", "Ambition III", "Influence III", "Ravage secondaire II", "Vivacité II", "Vélocité II", "Armure lourde II", "Secret critique")
            val extra = catalog.filter { it.name.fr in named && f.nextInt(3) != 0 }
            subs = (subs + extra + capSubs.filter { it.condition?.type == me.chosante.common.SublimationConditionType.CRIT_AT_MOST }[f.nextInt(2)]).distinct()
        }
        val p =
            mmParams(targets, level = level, useRunes = rng.nextInt(3) == 0)
        val negCc = items.any { (it.characteristics[Characteristic.CRITICAL_HIT] ?: 0) < 0 }
        val critCap = subs.any { it.condition?.type == me.chosante.common.SublimationConditionType.CRIT_AT_MOST }
        return MmCase("mm-seed$seed", p, items.groupBy { it.itemType }, subs, negCc && critCap)
    }

    @Test
    fun `manual MM certificate fuzz - soft and targets-met reads vs pinned CP-SAT`(): Unit =
        kotlinx.coroutines.runBlocking {
            val cases = System.getenv("WAKFU_REVIEW_MM_FUZZ")?.toIntOrNull() ?: return@runBlocking
            val seed0 = System.getenv("WAKFU_REVIEW_MM_SEED0")?.toLongOrNull() ?: 7_000L
            val noNegCc = System.getenv("WAKFU_REVIEW_MM_NO_NEG_CC") == "1"
            val det = System.getenv("WAKFU_REVIEW_MM_DET")?.toDoubleOrNull() ?: 30.0
            val t = WakfuBuildSolver.SolverTuning(numSearchWorkers = 1, randomSeed = 1, interleaveSearch = true, maxDeterministicTime = det)
            val failures = mutableListOf<String>()
            var softCompared = 0
            var hardCompared = 0
            var bails = 0
            var notOptimal = 0
            for (i in 0 until cases) {
                val c = mmFuzzCase(seed0 + i, noNegCc)
                val runes = if (c.params.useRunes) WakfuBestBuildFinderAlgorithm.runes else emptyList()
                val bound = MostMasteriesCertificate.bound(c.params, c.pool, runes, c.subs)
                if (bound == null) {
                    bails++
                    println("MM_FUZZ ${c.label} BAIL")
                    continue
                }
                val hasReq = c.params.targetStats.any { it.characteristic.isRequiredMostMasteriesTarget() }
                // Soft leg.
                var softLast: SolverResult<BuildCombination>? = null
                var softOutcome: WakfuBuildSolver.SolveOutcome? = null
                WakfuBuildSolver.optimize(c.params, c.pool, runes, c.subs, t, hardConstraints = false, onTermination = { softOutcome = it }).collect { softLast = it }
                val softInc = softLast?.mostMasteriesObjective
                val softUpper = bound.comparableUpper(hardLeg = false, hasRequiredTargets = hasReq)
                if (softOutcome?.status == com.google.ortools.sat.CpSolverStatus.OPTIMAL && softInc != null) {
                    softCompared++
                    if (softUpper < softInc) {
                        failures +=
                            "${c.label} SOFT bound=$softUpper < optimum=$softInc (${"%.2f".format((softInc.toDouble() / softUpper - 1) * 100)}%) " +
                            "negCcCritCap=${c.negCcWithCritCap} targets=${c.params.targetStats.map { "${it.characteristic}=${it.target}" }} " +
                            "subs=${softLast?.individual?.sublimations?.values?.flatten()?.map { it.name.fr }} binding=${bound.bindingState}"
                    }
                } else {
                    notOptimal++
                }
                // Hard leg (targets enforced): compared with the targets-met read when it proves OPTIMAL.
                if (hasReq) {
                    var hardLast: SolverResult<BuildCombination>? = null
                    var hardOutcome: WakfuBuildSolver.SolveOutcome? = null
                    WakfuBuildSolver.optimize(c.params, c.pool, runes, c.subs, t, hardConstraints = true, onTermination = { hardOutcome = it }).collect { hardLast = it }
                    val hardInc = hardLast?.takeIf { !it.greedyWarmStartEmission }?.mostMasteriesObjective
                    if (hardOutcome?.status == com.google.ortools.sat.CpSolverStatus.OPTIMAL && hardInc != null) {
                        hardCompared++
                        val hardUpper = bound.comparableUpper(hardLeg = true, hasRequiredTargets = true)
                        if (hardUpper < hardInc) {
                            failures +=
                                "${c.label} HARD bound=$hardUpper < optimum=$hardInc negCcCritCap=${c.negCcWithCritCap} " +
                                "targets=${c.params.targetStats.map { "${it.characteristic}=${it.target}" }} " +
                                "subs=${hardLast?.individual?.sublimations?.values?.flatten()?.map { it.name.fr }} hardBinding=${bound.hardBindingState}"
                        }
                    }
                }
                if (bound.hardFoldedBound > bound.foldedBound) failures += "${c.label} INVARIANT hard ${bound.hardFoldedBound} > soft ${bound.foldedBound}"
                println("MM_FUZZ ${c.label} softUpper=$softUpper softInc=$softInc status=${softOutcome?.status} hard=${bound.hardFoldedBound} negCcCritCap=${c.negCcWithCritCap}")
            }
            println("MM_FUZZ_SUMMARY cases=$cases softCompared=$softCompared hardCompared=$hardCompared bails=$bails notOptimal=$notOptimal failures=${failures.size}")
            failures.forEach { println("MM_FUZZ_FAIL $it") }
            assertThat(failures).describedAs("SOUNDNESS — MM certificate under-counts").isEmpty()
        }

    /**
     * Replays MM fuzz cases (comma-separated seeds) with diagnostics: the pinned optimum's items / subs / crit lines,
     * the certificate reads, and the SAME case with every negative item crit line removed (`noNegCc`) — a failure that
     * disappears there is the A1 LOW-dim floor.  WAKFU_REVIEW_MM_CASES=7089[,…]
     */
    @Test
    fun `manual MM fuzz case replay`(): Unit =
        kotlinx.coroutines.runBlocking {
            val seeds = System.getenv("WAKFU_REVIEW_MM_CASES")?.split(',')?.mapNotNull { it.trim().toLongOrNull() } ?: return@runBlocking
            val t = WakfuBuildSolver.SolverTuning(numSearchWorkers = 1, randomSeed = 1, interleaveSearch = true, maxDeterministicTime = 30.0)
            for (seed in seeds) {
                val c = mmFuzzCase(seed, noNegCc = false)
                val runes = if (c.params.useRunes) WakfuBestBuildFinderAlgorithm.runes else emptyList()
                for (variant in listOf("asIs", "noNegItemCc")) {
                    val pool =
                        if (variant == "asIs") {
                            c.pool
                        } else {
                            c.pool.mapValues { (_, es) ->
                                es.map { e ->
                                    e.copy(characteristics = e.characteristics.filterNot { (k, v) -> k == Characteristic.CRITICAL_HIT && v < 0 })
                                }
                            }
                        }
                    val bound = MostMasteriesCertificate.bound(c.params, pool, runes, c.subs)
                    val hasReq = c.params.targetStats.any { it.characteristic.isRequiredMostMasteriesTarget() }
                    for (hard in listOf(false, true)) {
                        if (hard && !hasReq) continue
                        var last: SolverResult<BuildCombination>? = null
                        var outcome: WakfuBuildSolver.SolveOutcome? = null
                        WakfuBuildSolver.optimize(c.params, pool, runes, c.subs, t, hardConstraints = hard, onTermination = { outcome = it }).collect { last = it }
                        val r = last
                        val upper = bound?.comparableUpper(hard, hasReq)
                        val optimum = r?.mostMasteriesObjective
                        println(
                            "MM_REPLAY seed=$seed $variant hard=$hard status=${outcome?.status} optimum=$optimum upper=$upper " +
                                "undercount=${upper != null && optimum != null && upper < optimum} " +
                                "targets=${c.params.targetStats.map { "${it.characteristic}=${it.target}" }} level=${c.params.character.level} runes=${c.params.useRunes}"
                        )
                        println(
                            "MM_REPLAY   items=${r?.individual?.equipments?.map {
                                "${it.itemType}:${it.name.fr}:${it.rarity}:CC${it.characteristics[Characteristic.CRITICAL_HIT] ?: 0}"
                            }} " +
                                "subs=${r?.individual?.sublimations?.values?.flatten()?.map { it.name.fr }}"
                        )
                        println("MM_REPLAY   binding=${if (hard) bound?.hardBindingState else bound?.bindingState}")
                    }
                }
                println("MM_REPLAY   catalogSubs=${c.subs.map { it.name.fr }} poolOrder=${c.pool.keys}")
            }
        }

    // ------------------------------------------------------------------------------------------------------------
    // MAX-DAMAGE FUZZ (manual): seeded random pools × REAL choosable subs (Neutralité family, Mesure, Ravage…) × rune
    // rows (general fold) × scenarios. Per AP cell: exact / tier-1.5 / fast ≥ pinned CP-SAT raw optimum; ledger max ≥
    // true optimum (forceTier2All AND the incumbent path); E8 construct never returns a sub-optimal "proven" build.
    //   WAKFU_REVIEW_MD_FUZZ=<cases> [WAKFU_REVIEW_MD_SEED0=<seed>] [WAKFU_REVIEW_MD_E8=1]
    // ------------------------------------------------------------------------------------------------------------

    private class MdCase(
        val label: String,
        val params: WakfuBestBuildParams,
        val pool: Map<ItemType, List<Equipment>>,
        val subs: List<Sublimation>,
    )

    // [forcePoidsPlume]: append Poids Plume III when the seed did not draw it (the B1 lock below) — after every RNG draw,
    // so the pool, rows and other subs of a seed are unchanged.
    private fun mdFuzzCase(
        seed: Long,
        forcePoidsPlume: Boolean = System.getenv("WAKFU_REVIEW_MD_FORCE_PP") == "1",
    ): MdCase {
        val rng = java.util.Random(seed)
        val level = listOf(50, 110, 170, 230)[rng.nextInt(4)]
        val element = me.chosante.autobuilder.domain.SpellElement.entries[rng.nextInt(4)]
        val range = if (rng.nextBoolean()) me.chosante.autobuilder.domain.RangeBand.DISTANCE else me.chosante.autobuilder.domain.RangeBand.MELEE
        val orientation = if (rng.nextInt(3) == 0) me.chosante.autobuilder.domain.Orientation.BACK else me.chosante.autobuilder.domain.Orientation.FACE
        val berserk = rng.nextInt(4) == 0
        val scenario =
            me.chosante.autobuilder.domain
                .DamageScenario(element = element, rangeBand = range, orientation = orientation, berserk = berserk)
        val scenarioSecondaries = scenarioMasteryStats(scenario).filter { it in me.chosante.common.SECONDARY_MASTERY_CHARACTERISTICS }
        val others =
            me.chosante.common.SECONDARY_MASTERY_CHARACTERISTICS
                .filter { it !in scenarioSecondaries && it != Characteristic.MASTERY_CRITICAL }
        val rows = mutableListOf<TargetStat>()
        val useRunes = rng.nextInt(3) != 0
        if (rng.nextBoolean()) rows += TargetStat(Characteristic.HP, if (rng.nextInt(3) == 0) 1200 + rng.nextInt(1500) else 0)
        if (rng.nextInt(3) == 0) rows += TargetStat(Characteristic.DODGE, 0)
        if (rng.nextInt(3) == 0) rows += TargetStat(Characteristic.RESISTANCE_ELEMENTARY_WIND, 0)
        if (rng.nextInt(3) == 0) rows += TargetStat(others[rng.nextInt(others.size)], rng.nextInt(2)) // off-scenario maximized row
        if (rng.nextInt(4) == 0) rows += TargetStat(Characteristic.ACTION_POINT, 7 + rng.nextInt(3))
        var id = (seed % 100_000).toInt() * 100

        fun randomItem(
            type: ItemType,
            name: String,
            rarity: Rarity? = null,
        ): Equipment {
            val stats = mutableMapOf<Characteristic, Int>()
            stats[if (rng.nextInt(3) == 0) Characteristic.MASTERY_ELEMENTARY else element.masteryCharacteristic] = 100 + rng.nextInt(800)
            if (rng.nextInt(10) < 4) stats[range.masteryCharacteristic] = rng.nextInt(700) - 300
            if (orientation.grantsRearMastery && rng.nextInt(4) == 0) stats[Characteristic.MASTERY_BACK] = rng.nextInt(400) - 100
            if (berserk && rng.nextInt(4) == 0) stats[Characteristic.MASTERY_BERSERK] = rng.nextInt(400) - 100
            if (rng.nextInt(10) < 3) stats[others[rng.nextInt(others.size)]] = rng.nextInt(450) - 200
            if (rng.nextInt(10) < 3) stats[Characteristic.MASTERY_CRITICAL] = rng.nextInt(350) - 50
            if (rng.nextInt(10) < 4) stats[Characteristic.CRITICAL_HIT] = rng.nextInt(19) - 8
            if (rng.nextInt(10) < 3) stats[Characteristic.ACTION_POINT] = rng.nextInt(4) - 1
            if (rng.nextInt(20) == 0) stats[Characteristic.MAX_ACTION_POINT] = -1
            if (rng.nextInt(5) == 0) stats[Characteristic.MOVEMENT_POINT] = rng.nextInt(3)
            if (type != ItemType.RING && rng.nextInt(5) == 0) stats[Characteristic.DAMAGE_INFLICTED] = 5 + rng.nextInt(26)
            if (rng.nextInt(3) == 0) stats[Characteristic.HP] = rng.nextInt(500) - 50
            if (rng.nextInt(4) == 0) stats[Characteristic.DODGE] = rng.nextInt(150) - 50
            if (rng.nextInt(4) == 0) stats[Characteristic.BLOCK_PERCENTAGE] = 5 + rng.nextInt(30)
            if (rng.nextInt(4) == 0) stats[Characteristic.RESISTANCE_ELEMENTARY_WIND] = rng.nextInt(60) - 20
            id++
            return Equipment(
                equipmentId = id,
                guiId = id,
                level = 100 + rng.nextInt(101),
                name = I18nText("$name$id", "$name$id", "", ""),
                rarity = rarity ?: if (rng.nextInt(9) == 0) Rarity.RELIC else Rarity.COMMON,
                itemType = type,
                characteristics = stats.filterValues { it != 0 },
                maxShardSlots = listOf(0, 3, 3, 4)[rng.nextInt(4)]
            )
        }
        val items = mutableListOf<Equipment>()
        val singles = listOf(ItemType.AMULET, ItemType.BELT, ItemType.CAPE, ItemType.BOOTS, ItemType.HELMET, ItemType.CHEST_PLATE, ItemType.SHOULDER_PADS)
        val slots = singles.shuffled(rng).take(3 + rng.nextInt(3))
        for (slot in slots) repeat(2 + rng.nextInt(2)) { items += randomItem(slot, "md") }
        if (rng.nextBoolean()) {
            items += randomItem(ItemType.RING, "ringA")
            items += randomItem(ItemType.RING, "ringB")
        }
        if (rng.nextInt(3) == 0) items += randomItem(ItemType.TWO_HANDED_WEAPONS, "w2")
        if (rng.nextInt(3) == 0) items += randomItem(ItemType.ONE_HANDED_WEAPONS, "w1")
        if (rng.nextInt(4) == 0) items += randomItem(ItemType.OFF_HAND_WEAPONS, "off")
        if (rng.nextInt(3) != 0) items += randomItem(slots.first(), "epic", rarity = Rarity.EPIC)
        val catalog = WakfuBestBuildFinderAlgorithm.sublimations.filter { it.solverChoosable }
        val focus =
            catalog.filter {
                it.name.fr in
                    setOf(
                        "Neutralité III",
                        "Ambition III",
                        "Prétention III",
                        "Inflexibilité II",
                        "Mesure",
                        "Ravage III",
                        "Ravage secondaire II",
                        "Dénouement",
                        "Secret critique",
                        "Influence III",
                        "Influence vitale III",
                        "Expert des armes légères III",
                        "Carnage III"
                    )
            }
        val noPp = System.getenv("WAKFU_REVIEW_MD_NO_PP") == "1"
        val drawn =
            (focus.shuffled(rng).take(3 + rng.nextInt(5)) + catalog.shuffled(rng).take(rng.nextInt(6)))
                .distinct()
                .filterNot { noPp && it.perStatStep?.source == Characteristic.MOVEMENT_POINT }
        val subs = if (forcePoidsPlume) (drawn + catalog.single { it.name.fr == "Poids Plume III" }).distinct() else drawn
        val p =
            WakfuBestBuildParams(
                character = Character(CharacterClass.CRA, level, 0, CharacterSkills(level)),
                targetStats = TargetStats(rows),
                searchDuration = 60.seconds,
                stopWhenBuildMatch = false,
                maxRarity = Rarity.EPIC,
                forcedItems = emptyList(),
                excludedItems = emptyList(),
                scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE,
                useRunes = useRunes,
                useSublimations = true,
                damageScenario = scenario
            )
        return MdCase("md-seed$seed", p, items.groupBy { it.itemType }, subs)
    }

    /**
     * Replays max-damage fuzz cases with ablations to localize an under-count:
     *   WAKFU_REVIEW_MD_CASES=9072[,…] [WAKFU_REVIEW_MD_CELLS=7,9]
     */
    @Test
    fun `manual max-damage fuzz case replay`(): Unit =
        kotlinx.coroutines.runBlocking {
            val seeds = System.getenv("WAKFU_REVIEW_MD_CASES")?.split(',')?.mapNotNull { it.trim().toLongOrNull() } ?: return@runBlocking
            val onlyCells =
                System
                    .getenv("WAKFU_REVIEW_MD_CELLS")
                    ?.split(',')
                    ?.mapNotNull { it.trim().toIntOrNull() }
                    ?.toSet()
            for (seed in seeds) {
                val c = mdFuzzCase(seed)
                val runes = if (c.params.useRunes) WakfuBestBuildFinderAlgorithm.runes else emptyList()
                println(
                    "MD_REPLAY seed=$seed level=${c.params.character.level} scenario=${c.params.damageScenario} rows=${c.params.targetStats.map {
                        "${it.characteristic}=${it.target}"
                    }} runes=${c.params.useRunes}"
                )
                c.pool.values.flatten().forEach { e ->
                    println("MD_REPLAY   item ${e.itemType} ${e.name.fr} ${e.rarity} lvl=${e.level} sockets=${e.maxShardSlots} ${e.characteristics}")
                }
                println("MD_REPLAY   subs=${c.subs.map { it.name.fr }}")
                val variants =
                    linkedMapOf<String, Pair<WakfuBestBuildParams, List<Sublimation>>>(
                        "asIs" to (c.params to c.subs),
                        "noPoidsPlume" to (c.params to c.subs.filterNot { it.name.fr == "Poids Plume III" }),
                        "noCarnage" to (c.params to c.subs.filterNot { it.name.fr == "Carnage III" }),
                        "noBerserk" to (c.params.copy(damageScenario = c.params.damageScenario.copy(berserk = false)) to c.subs),
                        "noRows" to (c.params.copy(targetStats = TargetStats(emptyList())) to c.subs),
                        "onlyPoidsPlume" to (c.params to c.subs.filter { it.name.fr == "Poids Plume III" })
                    )
                for ((label, v) in variants) {
                    val (p, subs) = v
                    val truthParams = p.copy(targetStats = TargetStats(p.targetStats.map { TargetStat(it.characteristic, it.target, userDefinedWeight = 0) }))
                    val (exact, fast, tier15) = WakfuBuildSolver.certifierExactFastTier15CellObjectivesForTest(p, c.pool, runes, subs, applyDomination = false)
                    for (ap in exact.keys.sorted()) {
                        if (onlyCells != null && ap !in onlyCells) continue
                        val profile =
                            WakfuBuildSolver.timedMaxDamageProfileForTest(
                                truthParams.copy(maxDamageApTarget = ap),
                                c.pool,
                                runes,
                                subs,
                                workers = 1,
                                seconds = 30.0,
                                applyDomination = false,
                                deterministicLimit = 10.0
                            )
                        if (!profile.hasSolution) continue
                        val worst = listOfNotNull(exact[ap], tier15[ap], fast[ap]).filter { it >= 0 }.minOrNull()
                        println(
                            "MD_REPLAY $label AP=$ap status=${profile.status} raw=${profile.rawObjective} exact=${exact[ap]} t15=${tier15[ap]} fast=${fast[ap]} " +
                                "under=${worst != null && worst < profile.rawObjective} subs=${subs.filter {
                                    it.stateId in profile.selectedSublimationStateIds
                                }.map { "${it.name.fr}x${profile.selectedSublimationCopies[it.stateId] ?: 1}" }} " +
                                "items=${c.pool.values.flatten().filter { it.equipmentId in profile.selectedEquipmentIds }.map { it.name.fr }} " +
                                "stats=${profile.actualStats.filterKeys {
                                    it in
                                        setOf(
                                            Characteristic.ACTION_POINT,
                                            Characteristic.MOVEMENT_POINT,
                                            Characteristic.CRITICAL_HIT,
                                            Characteristic.DAMAGE_INFLICTED,
                                            Characteristic.MASTERY_ELEMENTARY_FIRE,
                                            Characteristic.MASTERY_BERSERK,
                                            Characteristic.MASTERY_BACK,
                                            Characteristic.MASTERY_CRITICAL
                                        )
                                }}"
                        )
                    }
                }
            }
        }

    @Test
    fun `manual max-damage certifier fuzz - real sub catalog, rune rows, aux worlds`(): Unit =
        kotlinx.coroutines.runBlocking {
            val cases = System.getenv("WAKFU_REVIEW_MD_FUZZ")?.toIntOrNull() ?: return@runBlocking
            val seed0 = System.getenv("WAKFU_REVIEW_MD_SEED0")?.toLongOrNull() ?: 9_000L
            val withE8 = System.getenv("WAKFU_REVIEW_MD_E8") == "1"
            val failures = mutableListOf<String>()
            var cellsCompared = 0
            var ledgers = 0
            var bailedPools = 0
            var notOptimalCells = 0
            var e8Built = 0
            var droppedFamilyCarried = 0
            for (i in 0 until cases) {
                val c = mdFuzzCase(seed0 + i)
                val runes = if (c.params.useRunes) WakfuBestBuildFinderAlgorithm.runes else emptyList()
                val truthParams =
                    c.params.copy(targetStats = TargetStats(c.params.targetStats.map { TargetStat(it.characteristic, it.target, userDefinedWeight = 0) }))
                val (exact, fast, tier15) =
                    WakfuBuildSolver.certifierExactFastTier15CellObjectivesForTest(c.params, c.pool, runes, c.subs, applyDomination = false)
                if (exact.values.none { it >= 0 } && fast.values.none { it >= 0 }) {
                    bailedPools++
                    println("MD_FUZZ ${c.label} BAIL rows=${c.params.targetStats.map { "${it.characteristic}=${it.target}" }} subs=${c.subs.map { it.name.fr }}")
                    continue
                }
                val truthByAp = LinkedHashMap<Int, Long>()
                for (ap in exact.keys.sorted()) {
                    val profile =
                        WakfuBuildSolver.timedMaxDamageProfileForTest(
                            truthParams.copy(maxDamageApTarget = ap),
                            c.pool,
                            runes,
                            c.subs,
                            workers = 1,
                            seconds = 30.0,
                            applyDomination = false,
                            deterministicLimit = 10.0
                        )
                    if (!profile.hasSolution) continue
                    if (profile.status != "OPTIMAL") {
                        notOptimalCells++
                        continue
                    }
                    truthByAp[ap] = profile.rawObjective
                    val carriedNames = c.subs.filter { it.stateId in profile.selectedSublimationStateIds }.map { it.name.fr }
                    if (carriedNames.any { it in setOf("Neutralité III", "Ambition III", "Prétention III", "Inflexibilité II", "Mesure") }) droppedFamilyCarried++
                    for ((label, value) in listOf("exact" to exact[ap], "tier15" to tier15[ap], "fast" to fast[ap])) {
                        if (value == null || value < 0) continue
                        cellsCompared++
                        if (value < profile.rawObjective) {
                            failures +=
                                "${c.label} AP=$ap $label=$value < raw optimum ${profile.rawObjective} " +
                                "(${"%.2f".format((profile.rawObjective.toDouble() / value - 1) * 100)}%) carried=$carriedNames " +
                                "rows=${c.params.targetStats.map { "${it.characteristic}=${it.target}" }} runes=${c.params.useRunes} scenario=${c.params.damageScenario}"
                        }
                    }
                }
                val trueOptimum = truthByAp.values.maxOrNull() ?: continue
                val ledger = WakfuBuildSolver.certifyLedgerForTest(c.params, c.pool, runes, c.subs, applyDomination = false, forceTier2All = true)
                ledger.maxCellObjective?.let { max ->
                    ledgers++
                    if (max < trueOptimum) failures += "${c.label} LEDGER(forceTier2All) max=$max < true optimum $trueOptimum"
                }
                for (frac in listOf(1.0, 0.9)) {
                    val inc = (trueOptimum * frac).toLong()
                    val l2 = WakfuBuildSolver.certifyLedgerForTest(c.params, c.pool, runes, c.subs, applyDomination = false, incumbentObjective = inc)
                    l2.maxCellObjective?.let { max ->
                        if (max < trueOptimum) failures += "${c.label} LEDGER(incumbent=$inc) max=$max < true optimum $trueOptimum"
                    }
                }
                if (withE8 && isFreeMaxDamageShape(c.params.targetStats)) {
                    val built =
                        WakfuBuildSolver.dpConstructProvenOptimum(
                            c.params,
                            c.pool,
                            runes,
                            c.subs,
                            incumbentObjective = (trueOptimum * 0.9).toLong(),
                            fallbackWallCapSeconds = 20.0
                        )
                    if (built != null) {
                        e8Built++
                        val proxy = built.maxDamageRawProxy ?: built.maxDamageObjective ?: Long.MIN_VALUE
                        if (proxy < trueOptimum) failures += "${c.label} E8 'proven optimal' proxy=$proxy < true optimum $trueOptimum"
                    }
                }
                println("MD_FUZZ ${c.label} trueOpt=$trueOptimum ledgerMax=${ledger.maxCellObjective} cells=${truthByAp.size}")
            }
            println(
                "MD_FUZZ_SUMMARY cases=$cases cellsCompared=$cellsCompared ledgers=$ledgers bailedPools=$bailedPools notOptimalCells=$notOptimalCells " +
                    "e8Built=$e8Built droppedFamilyCarried=$droppedFamilyCarried failures=${failures.size}"
            )
            failures.forEach { println("MD_FUZZ_FAIL $it") }
            assertThat(failures).describedAs("SOUNDNESS — max-damage certifier under-counts").isEmpty()
        }

    /**
     * B1 CI LOCK (CERTIFIER_VERSION 49): the seeded max-damage fuzz above on a few fixed seeds WITH Poids Plume III forced
     * into the sub set, so the MP→DI ramp is modeled and the paired Major "Movement Point and damage" point has a priced
     * MP axis in every pass. Per AP cell: the exact / tier-1.5 / fast bounds upper-bound the pinned CP-SAT raw optimum
     * (1 worker, fixed seed, interleaved search: deterministic) and stay ordered fast ≥ tier-1.5 ≥ exact; the
     * production ledger at incumbent = the true optimum keeps its max ≥ it. Every seed under-counted on the v48 tree:
     * md-seed9009 (level 230; AP-6–8, ledger max −1.8 %), md-seed9016 (230, the review's: AP-4 fast, AP-6 every pass,
     * −2.2 %), md-seed12961 (110; AP-5–8, ledger −1.5 %) and md-seed1994 (170; AP-6–7, ledger −0.9 %). The first draw of
     * consecutive seeds barely moves (`java.util.Random`), so 12961 / 1994 were picked for their level. Surveyed with
     * v49: 60 consecutive seeds from 9000 (12 red on v48) plus 20 level-spread ones (3 red) all green; the review's
     * other B1 pool, md-seed9072 (~35 s), stays on the manual fuzz. `WAKFU_REVIEW_MD_PP_SEEDS=a,b,…` replays any seeds.
     */
    @Test
    fun `B1 lock - max-damage certifier fuzz with Poids Plume never under-counts a cell`() {
        val seeds =
            System
                .getenv("WAKFU_REVIEW_MD_PP_SEEDS")
                ?.split(',')
                ?.mapNotNull { it.trim().toLongOrNull() }
                ?: listOf(9009L, 9016L, 12961L, 1994L)
        val failures = mutableListOf<String>()
        var compared = 0
        var rampCarried = 0
        var notOptimal = 0
        for (seed in seeds) {
            val started = System.nanoTime()
            val c = mdFuzzCase(seed, forcePoidsPlume = true)
            val runes = if (c.params.useRunes) WakfuBestBuildFinderAlgorithm.runes else emptyList()
            val poidsPlume = c.subs.single { it.name.fr == "Poids Plume III" }
            // The certificate bounds raw damage over EVERY build of the same model: same rows (same rune fold), weight 0.
            val truthParams =
                c.params.copy(targetStats = TargetStats(c.params.targetStats.map { TargetStat(it.characteristic, it.target, userDefinedWeight = 0) }))
            val (exact, fast, tier15) = WakfuBuildSolver.certifierExactFastTier15CellObjectivesForTest(c.params, c.pool, runes, c.subs, applyDomination = false)
            val truthByAp = LinkedHashMap<Int, Long>()
            for (ap in exact.keys.sorted()) {
                val profile =
                    WakfuBuildSolver.timedMaxDamageProfileForTest(
                        truthParams.copy(maxDamageApTarget = ap),
                        c.pool,
                        runes,
                        c.subs,
                        workers = 1,
                        seconds = 120.0,
                        applyDomination = false,
                        randomSeed = 1,
                        interleave = true,
                        deterministicLimit = 20.0
                    )
                if (!profile.hasSolution) continue
                if (profile.status != "OPTIMAL") {
                    notOptimal++
                    println("B1_LOCK ${c.label} AP=$ap not proven (${profile.status}) — skipped")
                    continue
                }
                truthByAp[ap] = profile.rawObjective
                if (poidsPlume.stateId in profile.selectedSublimationStateIds) rampCarried++
                val exactObj = exact[ap] ?: -1L
                val tier15Obj = tier15[ap] ?: -1L
                val fastObj = fast[ap] ?: -1L
                for ((label, value) in listOf("exact" to exactObj, "tier-1.5" to tier15Obj, "fast" to fastObj)) {
                    if (value < 0) continue
                    compared++
                    if (value < profile.rawObjective) failures += "${c.label} AP=$ap $label=$value < pinned raw optimum ${profile.rawObjective}"
                }
                if (exactObj >= 0 && tier15Obj >= 0 && tier15Obj < exactObj) failures += "${c.label} AP=$ap tier-1.5=$tier15Obj < exact=$exactObj"
                if (tier15Obj >= 0 && fastObj >= 0 && fastObj < tier15Obj) failures += "${c.label} AP=$ap fast=$fastObj < tier-1.5=$tier15Obj"
            }
            val trueOptimum = truthByAp.values.maxOrNull()
            if (trueOptimum != null) {
                // The production read: eliminate on the fast tier, refine the survivors — what proveOptimality compares.
                val ledger = WakfuBuildSolver.certifyLedgerForTest(c.params, c.pool, runes, c.subs, applyDomination = false, incumbentObjective = trueOptimum)
                val max = ledger.maxCellObjective
                if (max != null && max < trueOptimum) failures += "${c.label} LEDGER max=$max < true optimum $trueOptimum"
            }
            println(
                "B1_LOCK ${c.label} level=${c.params.character.level} cells=${truthByAp.size} trueOpt=$trueOptimum " +
                    "ms=${(System.nanoTime() - started) / 1_000_000}"
            )
        }
        failures.forEach { println("B1_LOCK_FAIL $it") }
        assertThat(failures).describedAs("SOUNDNESS — the certifier must never under-count a cell with an MP ramp modeled").isEmpty()
        assertThat(notOptimal).describedAs("every reachable cell is proven OPTIMAL by the pinned solve (else it compares nothing)").isZero()
        assertThat(compared).describedAs("certified cells compared against pinned CP-SAT").isGreaterThanOrEqualTo(10 * seeds.size)
        assertThat(rampCarried).describedAs("pinned optima carrying Poids Plume III (the ramp the paired point feeds)").isGreaterThanOrEqualTo(2 * seeds.size)
    }

    /**
     * v49 MP SATURATION CLAMP lock: after the skill stages the certifier rewrites every frontier point's MP as
     * `min(mp, clamp)`, the clamp sitting where Poids Plume III is saturated whatever MP the staged transitions take
     * later (`mpSaturationClamp` in MaxDamageCertifier). That must be value-EXACT: the fast / tier-1.5 / exact maps and
     * both ledgers (forceTier2All, and the rescue shape at incumbent = max − 1 that runs tier-1.5 and the pruned exact
     * tier) are IDENTICAL with the clamp on and off — bounds, tiers and winning provenance — the explain backtrack
     * crosses the clamp stage, and the clamp actually rewrites frontiers (else this lock is vacuous). Fixtures: a micro
     * pool whose MP gear overshoots the saturation (items up to +9 MP, Major point +1) with Vélocité II (+1 MP) and
     * Armure lourde II (−1 max MP) moving the axis after the clamp, and the B1 fuzz seed 9009 with Poids Plume forced.
     */
    @Test
    fun `B1 lock - the MP saturation clamp is value- and provenance-identical to the unclamped DP`() {
        val catalog = WakfuBestBuildFinderAlgorithm.sublimations
        val fire = Characteristic.MASTERY_ELEMENTARY_FIRE
        val mp = Characteristic.MOVEMENT_POINT
        val micro =
            listOf(
                item(11, ItemType.HELMET, stats = mapOf(fire to 900)),
                item(12, ItemType.HELMET, stats = mapOf(fire to 500, mp to 2)),
                item(13, ItemType.BOOTS, stats = mapOf(fire to 600, mp to 1)),
                item(14, ItemType.BOOTS, stats = mapOf(fire to 300, mp to 3)),
                item(15, ItemType.CAPE, stats = mapOf(fire to 700)),
                item(16, ItemType.CAPE, stats = mapOf(fire to 350, mp to 2)),
                item(17, ItemType.AMULET, stats = mapOf(fire to 800, Characteristic.ACTION_POINT to 1)),
                item(18, ItemType.AMULET, stats = mapOf(fire to 400, mp to 2))
            ).groupBy { it.itemType }
        val microParams =
            WakfuBestBuildParams(
                character = Character(CharacterClass.CRA, 175, 0, CharacterSkills(175)),
                targetStats = TargetStats(emptyList()),
                searchDuration = 60.seconds,
                stopWhenBuildMatch = false,
                maxRarity = Rarity.EPIC,
                forcedItems = emptyList(),
                excludedItems = emptyList(),
                scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE,
                useRunes = false,
                useSublimations = true,
                damageScenario =
                    me.chosante.autobuilder.domain.DamageScenario(
                        element = me.chosante.autobuilder.domain.SpellElement.FIRE,
                        rangeBand = me.chosante.autobuilder.domain.RangeBand.DISTANCE,
                        orientation = me.chosante.autobuilder.domain.Orientation.FACE
                    )
            )
        val microSubs = listOf("Poids Plume III", "Vélocité II", "Armure lourde II").map { n -> catalog.single { it.name.fr == n } }
        val seeded = mdFuzzCase(9009L, forcePoidsPlume = true)
        val fixtures = listOf(Triple(microParams, micro, microSubs), Triple(seeded.params, seeded.pool, seeded.subs))
        try {
            for ((i, fixture) in fixtures.withIndex()) {
                val (params, pool, subs) = fixture
                val runes = if (params.useRunes) WakfuBestBuildFinderAlgorithm.runes else emptyList()

                fun certify(clamp: Boolean): Triple<Triple<Map<Int, Long>, Map<Int, Long>, Map<Int, Long>>, CertLedger, CertLedger?> {
                    CertifierTuning.mpSaturationClampEnabled = clamp
                    val maps = WakfuBuildSolver.certifierExactFastTier15CellObjectivesForTest(params, pool, runes, subs, applyDomination = false)
                    val forced = WakfuBuildSolver.certifyLedgerForTest(params, pool, runes, subs, applyDomination = false, forceTier2All = true)
                    val rescue =
                        forced.maxCellObjective?.takeIf { it > 1L }?.let { max ->
                            WakfuBuildSolver.certifyLedgerForTest(params, pool, runes, subs, applyDomination = false, incumbentObjective = max - 1)
                        }
                    return Triple(maps, forced, rescue)
                }
                val off = certify(clamp = false)
                val rewritesBefore = CertifierTuning.mpClampRewritesForTest.get()
                val on = certify(clamp = true)
                // The micro pool overshoots the saturation by design: there the clamp must rewrite frontiers (non-vacuous).
                if (i == 0) {
                    assertThat(CertifierTuning.mpClampRewritesForTest.get() - rewritesBefore)
                        .describedAs("the clamp rewrites frontiers on the micro pool (else this lock is vacuous)")
                        .isGreaterThan(0L)
                }
                assertThat(on.first).describedAs("fixture $i: (exact, fast, tier-1.5) maps identical with the clamp").isEqualTo(off.first)
                assertThat(on.second).describedAs("fixture $i: forceTier2All ledger identical with the clamp").isEqualTo(off.second)
                assertThat(on.third).describedAs("fixture $i: rescue-shape ledger identical with the clamp").isEqualTo(off.third)
                val argmax =
                    on.second.cellObjectives.entries
                        .filter { it.value >= 0 }
                        .maxByOrNull { it.value }
                        ?.key
                if (argmax != null) {
                    val explain = WakfuBuildSolver.certifierExplainForTest(params, pool, runes, subs, applyDomination = false, cell = argmax)
                    println("B1_CLAMP fixture=$i argmax=$argmax ${explain.joinToString(" | ")}")
                    assertThat(explain).describedAs("fixture $i: the provenance backtrack crosses the clamp").noneMatch { "???" in it }
                }
            }
        } finally {
            CertifierTuning.mpSaturationClampEnabled = true
        }
    }
}
