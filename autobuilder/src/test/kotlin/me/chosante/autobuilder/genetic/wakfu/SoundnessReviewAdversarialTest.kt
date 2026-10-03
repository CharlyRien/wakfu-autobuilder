package me.chosante.autobuilder.genetic.wakfu

import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.autobuilder.domain.SpellCatalog
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
import me.chosante.common.SublimationCondition
import me.chosante.common.SublimationConditionType
import me.chosante.common.SublimationEffect
import me.chosante.common.SublimationKind
import me.chosante.common.SublimationRarity
import me.chosante.common.skills.CharacterSkills
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Adversarial soundness review (2026-10-02) of the most-masteries certificate v41–v43 and the max-damage AP-cell
 * certifier v44. Deterministic counterexamples (CI-runnable) + env-gated fuzz harnesses. The B1 and A1 repros are CI
 * locks since their fixes (CERTIFIER_VERSION 49 and 50); the review follow-ups (provenance replay, latent shapes, the
 * MP clamp's later-debit headroom) since CERTIFIER_VERSION 51.
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

    /** A CRA max-damage request (fire, distance, face) — the A1 twin's shape. */
    private fun mdSoftParams(
        targets: List<TargetStat>,
        level: Int = 200,
    ) = WakfuBestBuildParams(
        character = Character(CharacterClass.CRA, level, 0, CharacterSkills(level)),
        targetStats = TargetStats(targets),
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

    /** The max-damage soft certificate at its production ("banded") settings — the A1 twin's second read. */
    private fun mdSoftBanded(
        params: WakfuBestBuildParams,
        pool: Map<ItemType, List<Equipment>>,
        subs: List<Sublimation>,
    ) = MaxDamageSoftCertificate.bound(
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

    /** A synthetic choosable sub (latent-shape fixtures: data the shipped catalog does not carry). */
    private fun synthSub(
        id: Int,
        rarity: SublimationRarity,
        condition: SublimationCondition?,
        effects: List<SublimationEffect>,
    ) = Sublimation(
        stateId = id,
        name = I18nText("synth$id", "synth$id", "", ""),
        rarity = rarity,
        maxStackLevel = 1,
        kind = if (condition == null) SublimationKind.FLAT else SublimationKind.STATIC_CONDITIONAL,
        solverChoosable = true,
        condition = condition,
        effects = effects
    )

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

    /**
     * A1 review follow-up (instrument only — production never sets `provenance`): both certificates logged a stage's
     * pre-stage map AFTER its LOW-offset shift and the backtrack took that key for the previous stage's output, so it
     * broke at the first stage whose offset grew — here the −10-crit AMULET, staged after the +12-crit ring (the A1
     * reviewer's repro). CERTIFIER_VERSION 51 records each stage's shift and undoes it: the Constance world's binding path
     * is complete in the most-masteries certificate and in its max-damage soft twin (on 9a698f2d both broke at the
     * weapons stage, right before the shifted AMULET one).
     */
    @Test
    fun `A1 follow-up - the provenance replay crosses a stage whose LOW offset grew`() {
        val constance = WakfuBestBuildFinderAlgorithm.sublimations.single { it.name.fr == "Constance" }

        fun pool(mastery: Characteristic) =
            listOf(
                item(1, ItemType.RING, stats = mapOf(mastery to 1000, Characteristic.CRITICAL_HIT to 12)),
                item(2, ItemType.AMULET, stats = mapOf(mastery to 1000, Characteristic.CRITICAL_HIT to -10)),
                item(3, ItemType.HELMET, Rarity.EPIC, mapOf(mastery to 500))
            ).groupBy { it.itemType }
        val mm =
            requireNotNull(
                MostMasteriesCertificate.bound(
                    mmParams(listOf(TargetStat(Characteristic.MASTERY_DISTANCE, 9999))),
                    pool(Characteristic.MASTERY_DISTANCE),
                    emptyList(),
                    listOf(constance),
                    provenance = true
                )
            )
        val md =
            requireNotNull(
                MaxDamageSoftCertificate.bound(
                    mdSoftParams(listOf(TargetStat(Characteristic.HP, 500))),
                    pool(Characteristic.MASTERY_ELEMENTARY_FIRE),
                    emptyList(),
                    listOf(constance),
                    blockGate = false,
                    provenance = true
                )
            )
        val problems = mutableListOf<String>()
        for ((label, binding, path) in listOf(Triple("MM", mm.bindingState, mm.bindingPath), Triple("MD-soft", md.bindingState, md.bindingPath))) {
            println("A1_PROVENANCE $label binding=$binding")
            path.forEach { println("A1_PROVENANCE $label   $it") }
            if ("assume=Constance" !in binding) problems += "$label: the Constance world does not bind ($binding)"
            if (path.isEmpty() || path.any { "no predecessor" in it }) problems += "$label: the backtrack breaks: $path"
            if (path.none { it.startsWith("AMULET: item2") }) problems += "$label: the path misses the shifted AMULET stage"
            if (path.none { it.startsWith("rings: item1") }) problems += "$label: the path never reaches the rings"
        }
        assertThat(problems).describedAs("the provenance replay must cross every LOW-offset shift").isEmpty()
    }

    /**
     * Latent (review follow-up, CERTIFIER_VERSION 51): the most-masteries certificate never stages the ASSUMED cap sub or
     * the world-B subs into an assume world's LOW dim (both are credited at the collapse only), yet the solver's
     * pre-combat read carries every permanent line of a STATIC sub, its own condition's read included
     * (`buildPermanentSubTerms`). A NEGATIVE capped-stat line on one of them reopens A1's over-rejection: (a) a
     * CRIT_AT_MOST-10 cap sub carrying its own −5 permanent crit, whose +12-crit ring carrier reads 3 + 12 − 5 = 10;
     * (b) Constance beside a world-B sub (SECONDARY_MASTERIES_AT_MOST, a budget that never binds) carrying the −5 that
     * brings the same ring under 10. No shipped sub has such a line: v51 bails both shapes (on 9a698f2d both bounds sat
     * under the pinned CP-SAT optimum). The max-damage soft twin STAGES its objective cappers (secZero / critZero /
     * capFree arms), so only its assumed cap sub needs the bail: (c) the cap-sub shape on fire items bails, and (d) the
     * world-B shape stays bounded, soundly.
     */
    @Test
    fun `latent - a negative capped-stat line on a sub no assume world stages bails`(): Unit =
        kotlinx.coroutines.runBlocking {
            val constance = WakfuBestBuildFinderAlgorithm.sublimations.single { it.name.fr == "Constance" }
            val capOwnNegCrit =
                synthSub(
                    99_101,
                    SublimationRarity.EPIC,
                    SublimationCondition(SublimationConditionType.CRIT_AT_MOST, 10),
                    listOf(
                        SublimationEffect.Flat(Characteristic.DAMAGE_INFLICTED, 20),
                        SublimationEffect.Flat(Characteristic.CRITICAL_HIT, -5, appliesBeforeCombat = true)
                    )
                )
            val worldBNegCrit =
                synthSub(
                    99_102,
                    SublimationRarity.NORMAL,
                    SublimationCondition(SublimationConditionType.SECONDARY_MASTERIES_AT_MOST, 100_000),
                    listOf(
                        SublimationEffect.Flat(Characteristic.DAMAGE_INFLICTED, 10),
                        SublimationEffect.Flat(Characteristic.CRITICAL_HIT, -5, appliesBeforeCombat = true)
                    )
                )

            fun pool(mastery: Characteristic) =
                listOf(
                    item(1, ItemType.RING, stats = mapOf(mastery to 1000, Characteristic.CRITICAL_HIT to 12)),
                    item(2, ItemType.HELMET, Rarity.EPIC, mapOf(mastery to 500))
                ).groupBy { it.itemType }
            val failures = mutableListOf<String>()
            val mmP = mmParams(listOf(TargetStat(Characteristic.MASTERY_DISTANCE, 9999)))
            val mmPool = pool(Characteristic.MASTERY_DISTANCE)
            for ((label, subs) in listOf("MM (a) cap sub's own line" to listOf(capOwnNegCrit), "MM (b) world-B line" to listOf(constance, worldBNegCrit))) {
                val optimum = requireNotNull(pinnedSoft(mmP, mmPool, subs))
                val incumbent = requireNotNull(optimum.mostMasteriesObjective)
                val carried =
                    optimum.individual.sublimations.values
                        .flatten()
                        .map { it.name.fr }
                val bound = MostMasteriesCertificate.bound(mmP, mmPool, emptyList(), subs)
                println("LATENT_NEG_LOW $label optimum=$incumbent optimal=${optimum.isOptimal} carried=$carried core=${bound?.coreBound} binding=${bound?.bindingState}")
                assertThat(optimum.isOptimal).isTrue()
                assertThat(carried).describedAs("$label: the optimum carries every fixture sub").containsExactlyInAnyOrderElementsOf(subs.map { it.name.fr })
                if (bound != null && bound.coreBound < incumbent) failures += "UNDER-COUNT $label: core ${bound.coreBound} < optimum $incumbent"
                if (bound != null) failures += "$label: no bail"
            }
            val mdP = mdSoftParams(listOf(TargetStat(Characteristic.HP, 500)))
            val mdPool = pool(Characteristic.MASTERY_ELEMENTARY_FIRE)
            for ((label, subs, bails) in listOf(
                Triple("MD-soft (c) cap sub's own line", listOf(capOwnNegCrit), true),
                Triple("MD-soft (d) world-B line, staged", listOf(constance, worldBNegCrit), false)
            )) {
                var last: SolverResult<BuildCombination>? = null
                WakfuBuildSolver.optimize(mdP, mdPool, emptyList(), subs, tuning).collect { last = it }
                val exact = requireNotNull(last)
                val optimum = requireNotNull(exact.maxDamageObjective)
                val carried =
                    exact.individual.sublimations.values
                        .flatten()
                        .map { it.name.fr }
                val reads = listOf("plain" to MaxDamageSoftCertificate.bound(mdP, mdPool, emptyList(), subs, blockGate = false), "banded" to mdSoftBanded(mdP, mdPool, subs))
                println("LATENT_NEG_LOW $label optimum=$optimum optimal=${exact.isOptimal} carried=$carried ${reads.joinToString { (s, r) -> "$s=${r?.foldedBound}" }}")
                assertThat(exact.isOptimal).isTrue()
                assertThat(carried).describedAs("$label: the optimum carries every fixture sub").containsExactlyInAnyOrderElementsOf(subs.map { it.name.fr })
                for ((setting, read) in reads) {
                    if (read != null && read.foldedBound < optimum) failures += "UNDER-COUNT $label $setting: ${read.foldedBound} < optimum $optimum"
                    if (bails && read != null) failures += "$label $setting: no bail"
                    if (!bails && read == null) failures += "$label $setting: an unexpected bail"
                }
            }
            assertThat(failures).describedAs("SOUNDNESS — a never-staged sub's negative capped-stat line").isEmpty()
        }

    /**
     * Latent (review follow-up, CERTIFIER_VERSION 51): the solver folds MAX_ACTION_POINT / MAX_MOVEMENT_POINT lines into AP
     * / MP (`valueFor`, `foldedToUsableStat`), but the most-masteries certificate reads the plain AP / MP lines only
     * (`statOf`, the sub staging, `reachableMax`) and its max-damage soft twin keeps at most a NEGATIVE rider. Every
     * MAX_* line in the data is −1 (an over-count there); a POSITIVE one is under-counted: an item's +2 MAX_AP meets an AP 9
     * target (6 base + 2 + the Major point) the certificate read at 7, an item's +2 MAX_MP an MP 6 target (3 + 2 + the
     * Major point) read at 4, a sub's +2 MAX_AP the AP 9 target again, and on the soft twin an item's +4 MAX_AP the spell
     * throughput's AP. v51 bails all four (on 9a698f2d every bound sat under its pinned CP-SAT optimum).
     */
    @Test
    fun `latent - a positive MAX_ACTION_POINT or MAX_MOVEMENT_POINT rider bails`(): Unit =
        kotlinx.coroutines.runBlocking {
            val dist = Characteristic.MASTERY_DISTANCE

            fun twoItems(
                first: ItemType,
                firstStats: Map<Characteristic, Int>,
                second: ItemType,
                mastery: Characteristic = dist,
            ) = listOf(item(1, first, stats = firstStats), item(2, second, stats = mapOf(mastery to 500))).groupBy { it.itemType }
            val apRiderSub =
                synthSub(99_301, SublimationRarity.NORMAL, null, listOf(SublimationEffect.Flat(Characteristic.MAX_ACTION_POINT, 2, appliesBeforeCombat = true)))
            val ap9 = mmParams(listOf(TargetStat(dist, 9999), TargetStat(Characteristic.ACTION_POINT, 9)))
            val mp6 = mmParams(listOf(TargetStat(dist, 9999), TargetStat(Characteristic.MOVEMENT_POINT, 6)))

            class Case(
                val label: String,
                val params: WakfuBestBuildParams,
                val pool: Map<ItemType, List<Equipment>>,
                val subs: List<Sublimation>,
            )
            val failures = mutableListOf<String>()
            for (c in listOf(
                Case("MM item +2 MAX_AP", ap9, twoItems(ItemType.HELMET, mapOf(dist to 1000, Characteristic.MAX_ACTION_POINT to 2), ItemType.BOOTS), emptyList()),
                Case("MM item +2 MAX_MP", mp6, twoItems(ItemType.BOOTS, mapOf(dist to 1000, Characteristic.MAX_MOVEMENT_POINT to 2), ItemType.HELMET), emptyList()),
                Case("MM sub +2 MAX_AP", ap9, twoItems(ItemType.HELMET, mapOf(dist to 1000), ItemType.BOOTS), listOf(apRiderSub))
            )) {
                val soft = requireNotNull(pinnedSoft(c.params, c.pool, c.subs))
                val hard = requireNotNull(pinnedSoft(c.params, c.pool, c.subs, hard = true))
                val bound = MostMasteriesCertificate.bound(c.params, c.pool, emptyList(), c.subs)
                // The target is reachable only through the rider (6 + 1 AP / 3 + 1 MP without it), so the proven hard leg
                // — the target enforced — carries it.
                val riderCarried =
                    hard.individual.equipments.any { e -> e.characteristics.keys.any { it == Characteristic.MAX_ACTION_POINT || it == Characteristic.MAX_MOVEMENT_POINT } } ||
                        hard.individual.sublimations.values
                            .flatten()
                            .any { it.stateId == apRiderSub.stateId }
                println(
                    "LATENT_MAX_RIDER ${c.label} soft=${soft.mostMasteriesObjective} hard=${hard.mostMasteriesObjective} riderCarried=$riderCarried " +
                        "boundSoft=${bound?.foldedBound} boundHard=${bound?.hardFoldedBound}"
                )
                assertThat(soft.isOptimal && hard.isOptimal && !hard.greedyWarmStartEmission).describedAs("${c.label}: both legs proven").isTrue()
                assertThat(riderCarried).describedAs("${c.label}: the target-meeting build carries the rider").isTrue()
                if (bound != null) {
                    val softUp = bound.comparableUpper(hardLeg = false, hasRequiredTargets = true)
                    val hardUp = bound.comparableUpper(hardLeg = true, hasRequiredTargets = true)
                    if (softUp < requireNotNull(soft.mostMasteriesObjective)) failures += "UNDER-COUNT ${c.label} soft: $softUp < ${soft.mostMasteriesObjective}"
                    if (hardUp < requireNotNull(hard.mostMasteriesObjective)) failures += "UNDER-COUNT ${c.label} targets-met: $hardUp < ${hard.mostMasteriesObjective}"
                    failures += "${c.label}: no bail"
                }
            }
            // The soft twin: a +4 MAX_AP item lifts the throughput AP from 7 (base 6 + the Major point) to 11.
            val mdP = mdSoftParams(listOf(TargetStat(Characteristic.HP, 500)))
            val fire = Characteristic.MASTERY_ELEMENTARY_FIRE
            val mdPool = twoItems(ItemType.HELMET, mapOf(fire to 1000, Characteristic.MAX_ACTION_POINT to 4), ItemType.BOOTS, mastery = fire)
            var last: SolverResult<BuildCombination>? = null
            WakfuBuildSolver.optimize(mdP, mdPool, emptyList(), emptyList(), tuning).collect { last = it }
            val exact = requireNotNull(last)
            val optimum = requireNotNull(exact.maxDamageObjective)
            val reads =
                listOf(
                    "plain" to MaxDamageSoftCertificate.bound(mdP, mdPool, emptyList(), emptyList(), blockGate = false),
                    "banded" to mdSoftBanded(mdP, mdPool, emptyList())
                )
            println("LATENT_MAX_RIDER MD-soft item +4 MAX_AP optimum=$optimum optimal=${exact.isOptimal} ${reads.joinToString { (s, r) -> "$s=${r?.foldedBound}" }}")
            assertThat(exact.isOptimal).isTrue()
            for ((setting, read) in reads) {
                if (read != null && read.foldedBound < optimum) failures += "UNDER-COUNT MD-soft $setting: ${read.foldedBound} < optimum $optimum"
                if (read != null) failures += "MD-soft $setting: no bail"
            }
            assertThat(failures).describedAs("SOUNDNESS — a positive MAX_* AP / MP rider").isEmpty()
        }

    /**
     * Latent (review follow-up, CERTIFIER_VERSION 51): the max-damage AP-cell certifier's secondary-capped world N prices a
     * whole FLAT sub as a READ source, its ramp included — but a ramp lands in the final sheet only, outside the
     * first-turn read Neutralité's `secondary masteries ≤ 0` checks. A synthetic ramp into distance mastery (+1 000 once
     * HP ≥ 1 000 — always, at level 200) beside Neutralité III was valued `pos(d) − d = 0` there: the free optimum
     * carrying both (24 DI AND the ramp's mastery, read 0 ≤ 0) sat above every world. No shipped ramp targets a mastery
     * (Poids Plume III: MP → DI): v51 bails world N on that shape, so the whole ledger bails (on 9a698f2d its max sat
     * under the free optimum).
     */
    @Test
    fun `latent - a FLAT ramp into a secondary mastery bails the secondary-capped world N`() {
        val neutralite = WakfuBestBuildFinderAlgorithm.sublimations.single { it.name.fr == "Neutralité III" }
        val ramp =
            synthSub(
                99_401,
                SublimationRarity.NORMAL,
                null,
                listOf(SublimationEffect.PerStatStep(Characteristic.HP, 0, 1, 1000, Characteristic.MASTERY_DISTANCE))
            )
        val fire = Characteristic.MASTERY_ELEMENTARY_FIRE
        // Two 3-socket carriers: one per normal sub.
        val pool =
            listOf(
                item(1, ItemType.HELMET, stats = mapOf(fire to 3000)),
                item(2, ItemType.BOOTS, stats = mapOf(fire to 3000))
            ).groupBy { it.itemType }
        val params = mdSoftParams(emptyList())
        val subs = listOf(neutralite, ramp)
        val truth =
            WakfuBuildSolver.timedMaxDamageProfileForTest(params, pool, emptyList(), subs, workers = 1, seconds = 30.0, applyDomination = false, deterministicLimit = 10.0)
        val ledger = WakfuBuildSolver.certifyLedgerForTest(params, pool, emptyList(), subs, applyDomination = false, forceTier2All = true)
        println(
            "LATENT_WORLD_N_RAMP free=${truth.rawObjective} status=${truth.status} carried=${truth.selectedSublimationStateIds} " +
                "ledgerMax=${ledger.maxCellObjective} bailedCells=${ledger.bailedCells.size}"
        )
        assertThat(truth.status).isEqualTo("OPTIMAL")
        assertThat(truth.selectedSublimationStateIds).describedAs("the free optimum carries Neutralité III AND the ramp").contains(neutralite.stateId, ramp.stateId)
        val failures = mutableListOf<String>()
        ledger.maxCellObjective?.let { max ->
            if (max < truth.rawObjective) failures += "UNDER-COUNT ledger max $max < free optimum ${truth.rawObjective}"
            failures += "world N did not bail (ledger max $max)"
        }
        assertThat(failures).describedAs("SOUNDNESS — a FLAT ramp into a secondary mastery in world N").isEmpty()
    }

    /**
     * The CERTIFIER_VERSION 51 / 53 latent-shape bails never fire on the shipped catalog — no badge is lost: no item carries a
     * positive MAX_ACTION_POINT / MAX_MOVEMENT_POINT line; no choosable sub a positive one, nor (a cap sub or an objective
     * capper) a negative crit / AP / MAX_ACTION_POINT line, nor (FLAT) a ramp into a secondary mastery, nor (RANGE_AT_LEAST)
     * a permanent +range line of its own; and the
     * most-masteries request gate ([MostMasteriesCertificate.supportsRequest], which runs the request-level bails) accepts
     * every requestable mastery at every level band beside the full catalog's cap subs. A data refresh that trips one is
     * named here — count that shape properly then.
     */
    @Test
    fun `latent-shape bails never fire on the shipped catalog`() {
        val riders = setOf(Characteristic.MAX_ACTION_POINT, Characteristic.MAX_MOVEMENT_POINT)
        val capTypes =
            setOf(
                SublimationConditionType.AP_AT_MOST,
                SublimationConditionType.AP_EXACT,
                SublimationConditionType.CRIT_AT_MOST,
                SublimationConditionType.SECONDARY_MASTERIES_AT_MOST,
                SublimationConditionType.CRITICAL_MASTERY_AT_MOST
            )
        val levels = listOf(20, 50, 110, 170, 200, 245)
        val findings = mutableListOf<String>()
        for (e in WakfuBestBuildFinderAlgorithm.equipments) {
            for (r in riders) if ((e.characteristics[r] ?: 0) > 0) findings += "item ${e.name.fr} (${e.equipmentId}): ${e.characteristics[r]} $r"
        }
        val choosable = WakfuBestBuildFinderAlgorithm.sublimations.filter { it.solverChoosable }
        for (sub in choosable) {
            for (eff in sub.effects) {
                when (eff) {
                    is SublimationEffect.StatEffect ->
                        for (level in levels) {
                            val v = eff.magnitudeAtLevel(level)
                            if (eff.characteristic in riders && v > 0) findings += "sub ${sub.name.fr}: +$v ${eff.characteristic} at level $level"
                            if (sub.condition?.type in capTypes &&
                                eff.characteristic.foldedToUsableStat() in setOf(Characteristic.CRITICAL_HIT, Characteristic.ACTION_POINT) &&
                                v < 0
                            ) {
                                findings += "capping sub ${sub.name.fr}: $v ${eff.characteristic} at level $level"
                            }
                        }
                    is SublimationEffect.PerStatStep ->
                        if (sub.kind == SublimationKind.FLAT && eff.target.foldedToUsableStat() in me.chosante.common.SECONDARY_MASTERY_CHARACTERISTICS) {
                            findings += "FLAT sub ${sub.name.fr}: a ramp into ${eff.target}"
                        }
                    else -> {}
                }
            }
        }
        // v53: a RANGE_AT_LEAST sub whose OWN +range line is permanent could feed its own condition (the target-aware RANGE
        // row bails on it). Any catalog sub — a forced one reaches the certificate too.
        for (sub in WakfuBestBuildFinderAlgorithm.sublimations) {
            if (sub.condition?.type != SublimationConditionType.RANGE_AT_LEAST) continue
            for (eff in sub.effects.filterIsInstance<SublimationEffect.StatEffect>()) {
                if (eff.appliesBeforeCombat && eff.characteristic.foldedToUsableStat() == Characteristic.RANGE && levels.any { eff.magnitudeAtLevel(it) > 0 }) {
                    findings += "RANGE_AT_LEAST sub ${sub.name.fr}: a permanent +range line"
                }
            }
        }
        for (level in levels) {
            for (mastery in listOf(
                Characteristic.MASTERY_DISTANCE,
                Characteristic.MASTERY_MELEE,
                Characteristic.MASTERY_CRITICAL,
                Characteristic.MASTERY_BACK,
                Characteristic.MASTERY_BERSERK,
                Characteristic.MASTERY_HEALING
            )) {
                val p = mmParams(listOf(TargetStat(mastery, 9999), TargetStat(Characteristic.ACTION_POINT, 11), TargetStat(Characteristic.CRITICAL_HIT, 30)), level = level)
                if (!MostMasteriesCertificate.supportsRequest(p, WakfuBestBuildFinderAlgorithm.sublimations)) findings += "MM request gate: level $level, $mastery"
            }
        }
        println(
            "LATENT_CATALOG items=${WakfuBestBuildFinderAlgorithm.equipments.size} choosableSubs=${choosable.size} " +
                "capOrCapperSubs=${choosable.count { it.condition?.type in capTypes }} findings=${findings.size}"
        )
        assertThat(findings).describedAs("a CERTIFIER_VERSION 51 / 53 latent-shape bail fires on the shipped catalog").isEmpty()
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
    // WAKFU_REVIEW_MD_ROWS=1 (CERTIFIER_VERSION 52) also draws required AP / MP / RANGE / CC rows and item range lines and
    // checks the TARGET-AWARE passes and ledgers against the pinned HARD-LEG optimum (never below it, never above the
    // target-blind value).
    //   WAKFU_REVIEW_MD_FUZZ=<cases> [WAKFU_REVIEW_MD_SEED0=<seed>] [WAKFU_REVIEW_MD_E8=1] [WAKFU_REVIEW_MD_ROWS=1]
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
        // v52: required AP / MP / RANGE / CC rows and item range lines, drawn from their OWN generator after every other
        // draw, so a seed's pool, rows and subs are otherwise unchanged (the CI locks' seeds included).
        requiredRows: Boolean = System.getenv("WAKFU_REVIEW_MD_ROWS") == "1",
    ): MdCase {
        val rng = java.util.Random(seed)
        val level = listOf(50, 110, 170, 230)[rng.nextInt(4)]
        // Among the class's PLAYABLE elements: CRA casts no WATER spell, so a WATER draw (a quarter of the seeds) made the
        // objective the constant 0 and the seed silently bailed. An unplayable draw is remapped onto a playable element —
        // same single RNG draw, so a seed that drew a playable element keeps its exact case (the CI locks' seeds included).
        val playable =
            SpellCatalog.playableElements(CharacterClass.CRA).map {
                me.chosante.autobuilder.domain.SpellElement
                    .valueOf(it.name)
            }
        val drawnElement = me.chosante.autobuilder.domain.SpellElement.entries[rng.nextInt(4)]
        val element = if (drawnElement in playable) drawnElement else playable[drawnElement.ordinal % playable.size]
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
        var poolItems: List<Equipment> = items
        if (requiredRows) {
            val rowRng = java.util.Random(seed * 7_919L + 13L)
            if (rowRng.nextInt(10) < 6) rows += TargetStat(Characteristic.ACTION_POINT, 7 + rowRng.nextInt(4))
            if (rowRng.nextInt(10) < 6) rows += TargetStat(Characteristic.MOVEMENT_POINT, 3 + rowRng.nextInt(3))
            if (rowRng.nextInt(10) < 6) rows += TargetStat(Characteristic.RANGE, 1 + rowRng.nextInt(5))
            if (rowRng.nextInt(10) < 5) rows += TargetStat(Characteristic.CRITICAL_HIT, 10 + rowRng.nextInt(31))
            poolItems =
                items.map { e ->
                    if (rowRng.nextInt(10) < 4) e.copy(characteristics = e.characteristics + (Characteristic.RANGE to rowRng.nextInt(4) - 1)) else e
                }
        }
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
        return MdCase("md-seed$seed", p, poolItems.groupBy { it.itemType }, subs)
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
            val withRows = System.getenv("WAKFU_REVIEW_MD_ROWS") == "1"
            var hardCellsCompared = 0
            var hardCellsTightened = 0
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
                if (withRows && targetAwareLedgerApplies(c.params.targetStats)) {
                    // v52: the target-aware passes bound the HARD leg (every required row met) — never below its pinned
                    // optimum, never above the target-blind value; the ledgers keep their max ≥ the true hard-leg optimum.
                    val (taExact, taFast, taT15) =
                        WakfuBuildSolver.certifierExactFastTier15CellObjectivesForTest(c.params, c.pool, runes, c.subs, applyDomination = false, targetAware = true)
                    val hardByAp = LinkedHashMap<Int, Long>()
                    for (ap in taExact.keys.sorted()) {
                        for ((label, value, blind) in listOf(
                            Triple("exact", taExact[ap], exact[ap]),
                            Triple("tier15", taT15[ap], tier15[ap]),
                            Triple("fast", taFast[ap], fast[ap])
                        )) {
                            if (value != null && blind != null && value >= 0 && blind >= 0 && value > blind) failures += "${c.label} AP=$ap TA-$label=$value > target-blind $blind"
                        }
                        val hard =
                            WakfuBuildSolver.timedMaxDamageProfileForTest(
                                c.params.copy(maxDamageApTarget = ap),
                                c.pool,
                                runes,
                                c.subs,
                                workers = 1,
                                seconds = 30.0,
                                applyDomination = false,
                                deterministicLimit = 10.0,
                                hardConstraints = true
                            )
                        if (!hard.hasSolution) continue
                        if (hard.status != "OPTIMAL") {
                            notOptimalCells++
                            continue
                        }
                        hardByAp[ap] = hard.rawObjective
                        if ((taExact[ap] ?: -1L) in 0 until (exact[ap] ?: -1L)) hardCellsTightened++
                        for ((label, value) in listOf("exact" to taExact[ap], "tier15" to taT15[ap], "fast" to taFast[ap])) {
                            if (value == null || value < 0) continue
                            hardCellsCompared++
                            if (value < hard.rawObjective) {
                                failures +=
                                    "${c.label} AP=$ap TA-$label=$value < hard-leg optimum ${hard.rawObjective} rows=${c.params.targetStats.map {
                                        "${it.characteristic}=${it.target}"
                                    }}"
                            }
                        }
                    }
                    val hardOptimum = hardByAp.values.maxOrNull()
                    if (hardOptimum != null && hardOptimum > 0) {
                        for (taLedger in listOf(
                            WakfuBuildSolver.certifyLedgerForTest(c.params, c.pool, runes, c.subs, applyDomination = false, forceTier2All = true, targetAware = true),
                            WakfuBuildSolver.certifyLedgerForTest(c.params, c.pool, runes, c.subs, applyDomination = false, incumbentObjective = hardOptimum, targetAware = true)
                        )) {
                            taLedger.maxCellObjective?.let { max -> if (max < hardOptimum) failures += "${c.label} TA LEDGER max=$max < hard-leg optimum $hardOptimum" }
                        }
                    }
                    println("MD_FUZZ_TA ${c.label} hardOpt=$hardOptimum cells=${hardByAp.size} rows=${c.params.targetStats.map { "${it.characteristic}=${it.target}" }}")
                }
            }
            println(
                "MD_FUZZ_SUMMARY cases=$cases cellsCompared=$cellsCompared ledgers=$ledgers bailedPools=$bailedPools notOptimalCells=$notOptimalCells " +
                    "e8Built=$e8Built droppedFamilyCarried=$droppedFamilyCarried hardCellsCompared=$hardCellsCompared " +
                    "hardCellsTightened=$hardCellsTightened failures=${failures.size}"
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
     * Third fixture (review follow-up, CERTIFIER_VERSION 51) — the clamp's LATER-DEBIT headroom: the best items alone
     * carry +9 MP, past the saturation (8) plus Armure lourde II's debit, so every cell's optimum takes Armure lourde II's
     * +10 DI with the ramp still saturated after its −1. A clamp without the debit term (`saturatedFrom − mpFreeMax`)
     * rewrote those points one MP too low and the debit then cost the ramp a step; the first two fixtures stayed green
     * under that mutation (their optima never pay the debit from saturated MP), this one does not. CP-SAT alone cannot
     * see the gap — it caps the pre-sub MP at 8 — so the clamp-off DP is the reference.
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
        val debitHeadroom =
            listOf(
                item(21, ItemType.HELMET, stats = mapOf(fire to 900, mp to 3)),
                item(22, ItemType.BOOTS, stats = mapOf(fire to 900, mp to 3)),
                item(23, ItemType.CAPE, stats = mapOf(fire to 900, mp to 3))
            ).groupBy { it.itemType }
        val debitSubs = listOf("Poids Plume III", "Armure lourde II").map { n -> catalog.single { it.name.fr == n } }
        val fixtures =
            listOf(
                Triple(microParams, micro, microSubs),
                Triple(seeded.params, seeded.pool, seeded.subs),
                Triple(microParams, debitHeadroom, debitSubs)
            )
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
                // The micro pools overshoot the saturation by design: there the clamp must rewrite frontiers (non-vacuous).
                if (i != 1) {
                    assertThat(CertifierTuning.mpClampRewritesForTest.get() - rewritesBefore)
                        .describedAs("fixture $i: the clamp rewrites frontiers on the micro pool (else this lock is vacuous)")
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
                    if (i == 2) {
                        assertThat(explain)
                            .describedAs("fixture 2: the best cell's optimum pays Armure lourde II's debit from saturated MP")
                            .anyMatch { "Heavy Armor II" in it }
                    }
                }
            }
        } finally {
            CertifierTuning.mpSaturationClampEnabled = true
        }
    }
}
