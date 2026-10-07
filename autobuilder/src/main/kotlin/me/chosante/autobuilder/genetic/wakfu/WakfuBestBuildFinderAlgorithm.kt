package me.chosante.autobuilder.genetic.wakfu

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import me.chosante.autobuilder.EmbeddedResources
import me.chosante.autobuilder.VERSION
import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.autobuilder.domain.DamageScenario
import me.chosante.autobuilder.domain.StatGateViolation
import me.chosante.autobuilder.domain.TargetStats
import me.chosante.autobuilder.domain.equipConflict
import me.chosante.autobuilder.domain.isWearableBy
import me.chosante.autobuilder.domain.requiredItemIds
import me.chosante.autobuilder.domain.sheetCharacteristic
import me.chosante.autobuilder.domain.statGates
import me.chosante.autobuilder.domain.withRequirementsMet
import me.chosante.autobuilder.genetic.SolverResult
import me.chosante.common.Character
import me.chosante.common.CharacterClass
import me.chosante.common.Characteristic
import me.chosante.common.CriterionComparison
import me.chosante.common.Equipment
import me.chosante.common.ExclusiveGroup
import me.chosante.common.I18nText
import me.chosante.common.ItemEquipCriterion
import me.chosante.common.ItemStatGate
import me.chosante.common.ItemType
import me.chosante.common.Monster
import me.chosante.common.Rarity
import me.chosante.common.RuneCatalogData
import me.chosante.common.RuneType
import me.chosante.common.Sublimation
import me.chosante.common.SublimationEffect
import me.chosante.common.SublimationKind
import me.chosante.common.SublimationRarity
import java.math.BigDecimal
import kotlin.time.Duration

object WakfuBestBuildFinderAlgorithm {
    private val logger = KotlinLogging.logger {}

    /**
     * The embedded Wakfu game-data version (e.g. `1.91.1.54`). Exposed publicly so callers outside
     * this module (the GUI's build-history persistence) can stamp saved builds with the exact data
     * set they were computed against — crucial for reproducibility across data bumps.
     */
    val dataVersion: String = VERSION

    // Both data sets are `lazy` on purpose: touching ANY member of this object (e.g. [dataVersion]
    // from the GUI view-model's constructor, which runs on the AWT event thread) used to trigger
    // these multi-MB JSON parses eagerly — blocking the UI thread before the first frame could even
    // paint. Lazy init moves the parse to the first real use (icon preloading / the first search),
    // which always happens on a background thread in the GUI and on the main thread in the CLI.
    //
    // The RAW catalog: an item's level-scaled lines ([Equipment.percentOfLevel]) are not in its characteristics yet.
    // Search with [poolFor] (or resolve with [Equipment.atLevel]) — a pool built straight from this list misses them.
    // Each item carries its EQUIP criterion ([Equipment.equipCriterion], joined by id from [itemCriteria]), so every
    // consumer of a pool reads the item's conditions from the item itself.
    val equipments: List<Equipment> by lazy {
        EmbeddedResources.decodeList<Equipment>("equipments.json")!!.map { equipment ->
            criteriaById[equipment.equipmentId]?.let { equipment.copy(equipCriterion = it) } ?: equipment
        }
    }

    private val criteriaById: Map<Int, ItemEquipCriterion> by lazy { itemCriteria.associateBy { it.itemId } }

    // The catalog's exclusivity-group exceptions by id ([Equipment.exclusiveGroupOverride]: the two COMMON items of the EPIC group).
    private val exclusiveGroupOverrideById: Map<Int, ExclusiveGroup> by lazy {
        equipments.mapNotNull { item -> item.exclusiveGroupOverride?.let { item.equipmentId to it } }.toMap()
    }

    /**
     * The first item EQUIP condition or "only one equipped at a time" rule [build] breaks for a [characterClass] (null when
     * the game lets it wear the build), each item's criterion and exclusivity group read from the catalog by id — an item
     * that carries none of its own included: a build read back from a save or an import has no criterion
     * ([Equipment.equipCriterion] is never saved), and one saved before the conditions were enforced may wear a nation
     * sword without its ring; one saved before the exclusivity groups were read follows its rarity, so it may wear 18691
     * (COMMON, EPIC group) beside an epic item, and one saved before the stat gates were enforced may wear Cartes And at 4 range.
     * See [me.chosante.autobuilder.domain.equipConditionViolation], [me.chosante.autobuilder.domain.exclusiveGroupViolation]
     * and [statGateViolations].
     */
    fun equipConditionViolation(
        build: BuildCombination,
        characterClass: CharacterClass,
    ): String? {
        val items = withCatalogConditions(build.equipments)
        return me.chosante.autobuilder.domain
            .equipConditionViolation(items, characterClass)
            ?: me.chosante.autobuilder.domain
                .exclusiveGroupViolation(items)
            ?: me.chosante.autobuilder.domain
                .statGateViolations(build.copy(equipments = items), characterClass)
                .firstOrNull()
                ?.describe()
    }

    /**
     * Every stat gate [build] breaks for a [characterClass] ([me.chosante.autobuilder.domain.statGateViolations]: an item
     * the game would show red — inactive — on the build's out-of-combat sheet), each item's criterion read from the catalog by
     * id, as [equipConditionViolation] does: a saved build carries none, and one saved before the gates were enforced may wear
     * Cartes And at 4 range. Empty when every item is active. The GUI's warning on a loaded or saved build.
     */
    fun statGateViolations(
        build: BuildCombination,
        characterClass: CharacterClass,
    ): List<StatGateViolation> =
        me.chosante.autobuilder.domain
            .statGateViolations(build.copy(equipments = withCatalogConditions(build.equipments)), characterClass)

    /** [items] with the catalog's EQUIP criterion and exclusivity-group override joined on, where they carry none of their own. */
    private fun withCatalogConditions(items: List<Equipment>): List<Equipment> =
        items.map { item ->
            val withCriterion = item.equipCriterion?.let { item } ?: criteriaById[item.equipmentId]?.let { item.copy(equipCriterion = it) } ?: item
            withCriterion.exclusiveGroupOverride?.let { withCriterion }
                ?: exclusiveGroupOverrideById[item.equipmentId]?.let { withCriterion.copy(exclusiveGroupOverride = it) }
                ?: withCriterion
        }

    /**
     * The EQUIP criteria of the catalog's items (`item-criteria.json`, decoded from the local client's Item table by
     * `bdata-extractor`): a nation sword needs its ring, class emblems / amulets are for their class, some rings
     * exclude each other — see [me.chosante.autobuilder.domain.isWearableBy] and the AGENTS.md §4 "Item equip
     * conditions". Required: a missing file would silently drop every condition.
     */
    val itemCriteria: List<ItemEquipCriterion> by lazy {
        EmbeddedResources.decodeList<ItemEquipCriterion>("item-criteria.json")!!
    }

    /**
     * The embedded runes ([RuneType]) and shared thresholds for the current data version. The OR-Tools solver socket-fills equipped items with these when [WakfuBestBuildParams.useRunes].
     */
    val runes: List<RuneType> by lazy {
        RuneCatalogData.embedded.runes
    }

    /**
     * The embedded monster/boss catalog ([Monster]) for the current data version, or empty if the
     * resource is absent. Used by boss mode to auto-fill the attack scenario's per-element resistances.
     * Sorted bosses-first / higher-level-first, as produced by the `bdata-extractor` (`buildMonsters`).
     */
    val monsters: List<Monster> by lazy {
        EmbeddedResources.decodeList<Monster>("monsters.json") ?: emptyList()
    }

    /**
     * Resolves a monster from a user-typed [query], matched against the **French** name (like
     * `--forced-items`): an exact name wins, otherwise the most prominent (boss-tier, then highest-level)
     * substring match across the French or English name. Returns null when nothing matches.
     */
    fun findMonster(query: String): Monster? {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return null
        return monsters.firstOrNull { it.name.fr.lowercase() == needle }
            ?: monsters
                .filter {
                    it.name.fr
                        .lowercase()
                        .contains(needle) ||
                        it.name.en
                            .lowercase()
                            .contains(needle)
                }.sortedWith(compareByDescending<Monster> { it.rank }.thenByDescending { it.level })
                .firstOrNull()
    }

    /**
     * Sublimation names written by 1.10.0 and earlier (saved builds, exports, CLI scripts) whose record was
     * renamed when its identity moved to the CREDITED tier ("Carnage II" → "Carnage III", 115 records,
     * July 2026). Lower-cased old FR/EN name → current French name; generated once from the two catalogs,
     * minus old names that are a CURRENT name of another record (an exact current name always wins).
     */
    private val legacySublimationNames: Map<String, String> by lazy {
        EmbeddedResources.decode<Map<String, String>>("sublimation-legacy-names.json").orEmpty()
    }

    /**
     * A user-supplied sublimation name (French or English, any case) resolved against the current catalog:
     * a current name is returned unchanged; a pre-rename one maps to its record's current French name, so
     * forced/excluded sublimations saved before the rename keep working instead of being silently ignored
     * (pre-release review 2026-10-01); anything else is returned as is (and reported by [validateRequest]
     * consumers exactly as before).
     */
    fun canonicalSublimationName(name: String): String {
        val key = name.trim().lowercase()
        if (sublimations.any { it.name.fr.lowercase() == key || it.name.en.lowercase() == key }) return name
        return legacySublimationNames[key] ?: name
    }

    /**
     * The embedded sublimations ([Sublimation]) for the current data version, or empty if the resource
     * is absent. The solver chooses among the [Sublimation.solverChoosable] subset and applies any the
     * user [WakfuBestBuildParams.forcedSublimations]; see AGENTS.md §5.
     */
    val sublimations: List<Sublimation> by lazy {
        val subs = EmbeddedResources.decodeList<Sublimation>("sublimations.json") ?: emptyList()
        // Join Wakfu's `is_cumulable` from the stacking artifact: sublimations.json carries the effect values +
        // max_level (the stack cap) but NOT cumulability, so we mark the cumulable stateIds here — that unlocks
        // self-stacking ([Sublimation.maxCopies]). Nothing loaded `sublimation-stacking.json` before this.
        val cumulableStates =
            EmbeddedResources
                .decodeList<SublimationStacking>("sublimation-stacking.json", EmbeddedResources.lenientJson)
                .orEmpty()
                .filter { it.cumulable }
                .mapTo(HashSet()) { it.stateId }
        subs.map { if (it.stateId in cumulableStates) it.copy(cumulable = true) else it }
    }

    /** Minimal view of `sublimation-stacking.json` (bdata `is_cumulable`), joined onto [sublimations] at load. */
    @Serializable
    private data class SublimationStacking(
        val stateId: Int,
        val cumulable: Boolean = false,
    )

    fun run(params: WakfuBestBuildParams): Flow<SolverResult<BuildCombination>> {
        // Reject an invalid request (contradictory level bounds, a non-equippable forced item, or >1 epic/relic
        // forced sublimation) BEFORE any work, reporting ALL problems at once. The GUI pre-validates with
        // [validateRequest] and shows them in a pop-up; this throw is the CLI / safety floor. (ENG-1 / ENG-2)
        validateRequest(params).let { if (it.isNotEmpty()) throw InvalidRequestException(it) }
        val equipmentsByItemType = poolFor(params)

        return try {
            // Max-damage routes through the external loop (AP-breakpoint probes + debuff-aware
            // sequencing valuation). Most-masteries runs the targets-HARD leg first with a soft
            // fallback (P2a — see [mostMasteriesHardThenSoft]), its quality bound computed in the
            // search's tail (E10-for-MM, [MostMasteriesBoundCache]). Precision stays a single soft
            // solve. Every new search supersedes the bounds still computing for earlier requests.
            when (params.scoreComputationMode) {
                ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE ->
                    MaxDamageSearch
                        .run(params, equipmentsByItemType, runes, activeSublimations(params))
                        .onStart { MostMasteriesBoundCache.supersedeAll() }
                ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT -> {
                    val sublimations = activeSublimations(params)
                    MostMasteriesBoundCache.withSearchTimeWarmup(
                        params,
                        equipmentsByItemType,
                        sublimations,
                        mostMasteriesHardThenSoft(params, equipmentsByItemType, runes, sublimations)
                    )
                }
                else ->
                    WakfuBuildSolver
                        .optimize(params, equipmentsByItemType, runes, activeSublimations(params))
                        .onStart { MostMasteriesBoundCache.supersedeAll() }
            }
        } catch (exception: Exception) {
            // Surface the failure to the caller instead of killing the JVM: the CLI's runBlocking
            // turns it into a visible crash, while the GUI can catch it and show an error rather than
            // having the whole desktop app terminated by exitProcess.
            logger.error(exception) { "Exception occurred during the process of finding the best equipments." }
            throw exception
        }
    }

    /**
     * [build] scored under the CURRENT rules: exactly the number [run] streams as [SolverResult.matchPercentage] for it — the
     * most-masteries / precision scorer's value or, in max-damage, the debuff-aware rotation damage over the shortfall penalty
     * ([MaxDamageSearch.sequencedScore], which the max-damage search ranks by). For a build the search did not just find, such as a
     * saved one whose stored number is the old rules': no search, no solver, one scorer call (milliseconds). It deliberately never
     * touches [WakfuBuildSolver], whose first use loads OR-Tools natively; the two scorers called here are those of its `scoreFor`.
     */
    fun rescore(
        params: WakfuBestBuildParams,
        build: BuildCombination,
    ): BigDecimal =
        when (params.scoreComputationMode) {
            ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT ->
                FindMostMasteriesFromInputScoring.computeScore(params.targetStats, build, params.character.baseCharacteristicValues)

            ScoreComputationMode.FIND_CLOSEST_BUILD_FROM_INPUT ->
                FindClosestBuildFromInputScoring.computeScore(params.targetStats, build, params.character.baseCharacteristicValues)

            ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE -> MaxDamageSearch.sequencedScore(params, build)
        }

    /**
     * P2a (docs/MOST_MASTERIES_PERF_PLAN.md): the production most-masteries orchestration — the
     * required targets are enforced as HARD `actual ≥ target` constraints under the PLAIN
     * (unpenalized) mastery×DI objective first; when that leg yields NO build the search falls back
     * to today's soft power-6-penalty model, which then trades shortfall for mastery exactly as
     * before. Two distinct no-build cases (the real solver status separates them — the flow alone
     * cannot): proven INFEASIBLE (targets genuinely unreachable, incl. the statically-detected
     * skip) and an UNKNOWN timeout without a solution (reachable targets, hard model) — both fall
     * back, but the second is logged as a warning so slow-hard-leg shapes stay visible.
     *
     * Two effects, both deliberate:
     *  - **Perf:** the penalty product — whose LP relaxation CP-SAT can only prove against by tree
     *    exhaustion (P0.5) — vanishes from the searched model on the common reachable-targets path:
     *    F5@245 proves the same 10705 optimum in ~78 s instead of ~147 s (E1).
     *  - **Semantics:** when the requested stats are achievable, the returned build now ALWAYS meets
     *    them (beta feedback: "conditions pas vraiment respectées"). The soft model could return a
     *    higher-mastery build missing a target by a hair; that trade now only happens when the
     *    targets are genuinely unreachable. `isOptimal` on the hard path means "proven optimal among
     *    builds meeting every required target".
     *
     * Correctness lock: `P2a hard leg equals the soft optimum on a reachable-targets pool`
     * (MostMasteriesBoundPrototypeTest) — when the soft optimum meets the targets the two legs
     * coincide (penalty == 1 there, so the objectives are identical on that set).
     */
    internal fun mostMasteriesHardThenSoft(
        params: WakfuBestBuildParams,
        equipmentsByItemType: Map<ItemType, List<Equipment>>,
        runes: List<RuneType>,
        sublimations: List<Sublimation>,
    ): Flow<SolverResult<BuildCombination>> =
        kotlinx.coroutines.flow.flow {
            // The greedy warm start streams an instant first build BEFORE the solve, so "the flow
            // emitted something" cannot mean "the hard model was feasible". The solver's guaranteed
            // FINAL send (progressPercentage == 100) fires iff the solve ended OPTIMAL/FEASIBLE —
            // that is the fallback criterion.
            var hardSolved = false
            var termination: WakfuBuildSolver.SolveOutcome? = null
            val startedAt = System.currentTimeMillis()
            WakfuBuildSolver
                .optimize(
                    params,
                    equipmentsByItemType,
                    runes,
                    sublimations,
                    tuning = null,
                    hardConstraints = true,
                    onTermination = { termination = it }
                ).collect { result ->
                    if (result.progressPercentage == 100) hardSolved = true
                    // Hard-leg provenance (plan §8.18, T3): the quality certificate compares such a build with its
                    // targets-met read. Never on the greedy warm start, which precedes the solve.
                    emit(if (result.greedyWarmStartEmission) result else result.copy(mostMasteriesHardConstraintsMet = true))
                }
            if (!hardSolved) {
                // The REAL solver status distinguishes the two no-build cases: proven INFEASIBLE = the
                // targets are genuinely unreachable (the semantics the fallback is FOR), while UNKNOWN =
                // the hard model timed out before finding any build (reachable targets, hard model) —
                // the soft fallback still runs (deliver something), but the distinction is logged so a
                // slow-hard-leg shape is visible instead of masquerading as "unreachable targets".
                when (termination?.status) {
                    com.google.ortools.sat.CpSolverStatus.INFEASIBLE ->
                        logger.info { "Most-masteries hard leg INFEASIBLE (targets unreachable) — soft fallback." }
                    else ->
                        logger.warn {
                            "Most-masteries hard leg ended ${termination?.status} without a build " +
                                "(targets may still be reachable) — soft fallback on the remaining budget."
                        }
                }
                // The fallback runs on the REMAINING user budget (floored so a hard leg that burned the
                // whole duration proving infeasibility still yields a usable soft answer, not nothing).
                val elapsed = System.currentTimeMillis() - startedAt
                val remaining =
                    with(kotlin.time.Duration) {
                        (params.searchDuration.inWholeMilliseconds - elapsed).coerceAtLeast(5_000L).milliseconds
                    }
                WakfuBuildSolver
                    .optimize(params.copy(searchDuration = remaining), equipmentsByItemType, runes, sublimations)
                    .collect { emit(it) }
            }
        }

    /**
     * The most-masteries QUALITY certificate (backup certifier, docs/MOST_MASTERIES_PERF_PLAN.md
     * §8.9bis): a sound upper bound on the SOFT folded objective — "your build is provably within X%
     * of the optimum". Meant for searches whose CP-SAT leg ended WITHOUT a proof (low-core machines /
     * short budgets: the 1-worker proof takes 15-20 min where this DP answers in seconds).
     *
     * The convenience entry (GUI, CLI, tests): [mostMasteriesQualityBound] — the full-tier bound,
     * memoized single-flight and normally already computed in the search's tail (E10-for-MM, §8.19:
     * instant at search end; else the in-flight compute is awaited, or computed here after a budget
     * too short for a warm-up) — then [compareMostMasteriesQuality] against the result's raw objective
     * ([SolverResult.mostMasteriesObjective] — stamped only when the searched objective is
     * certificate-comparable). [shouldContinue] cancels the wait (and a compute this call started),
     * polled every ~100 ms and once per DP stage. One that is ALREADY false makes the call a PEEK: a
     * memoized bound still answers (the memo is read before the first poll) but nothing is started or
     * joined, so a bound that is not ready yet gives [MostMasteriesProof.Unavailable] at once — how the
     * GUI shows the badge the search's tail already paid for when the post-search check is switched off.
     *
     * SOUNDNESS: the bound never under-counts (locked by the tightness/fuzz harnesses), so
     * [MostMasteriesProof.ProvenWithin.percent] is a GUARANTEE, not an estimate; every unsupported
     * shape (elemental request, forced items/runes/subs, unsupported target, already-proven result,
     * missing comparable objective) returns [MostMasteriesProof.Unavailable] — a bail hides the
     * badge, it never fakes one.
     */
    fun proveMostMasteriesQuality(
        params: WakfuBestBuildParams,
        result: SolverResult<BuildCombination>,
        shouldContinue: () -> Boolean = { true },
    ): MostMasteriesProof {
        if (params.scoreComputationMode != ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT) return MostMasteriesProof.Unavailable
        // Mirror max-damage: reject heuristic requests BEFORE trusting a result's OPTIMAL stamp or a bound.
        if (WakfuBuildSolver.needsItemPrefilter(params.targetStats)) return MostMasteriesProof.Unavailable
        // Result-level verdicts first: no bound is computed for a result that cannot use one.
        if (result.isOptimal) return MostMasteriesProof.ProvenOptimal // CP-SAT already certified it exactly.
        val incumbent = result.mostMasteriesObjective ?: return MostMasteriesProof.Unavailable
        if (incumbent <= 0) return MostMasteriesProof.Unavailable
        val bound = mostMasteriesQualityBound(params, shouldContinue) ?: return MostMasteriesProof.Unavailable
        return compareMostMasteriesQuality(params, bound, result)
    }

    /**
     * COMPUTE half of [proveMostMasteriesQuality]: the incumbent-free full-tier bound for [params],
     * over the full eligible + dominated pool (no heuristic top-8 prefilter). Memoized per request and
     * single-flight ([MostMasteriesBoundCache]): the search's tail warm-up usually has it ready (or in
     * flight — then this waits for it); otherwise it is computed here, on every stage worker. Null =
     * the certificate bails on this shape, or [shouldContinue] turned false first.
     */
    internal fun mostMasteriesQualityBound(
        params: WakfuBestBuildParams,
        shouldContinue: () -> Boolean = { true },
    ): MostMasteriesCertificate.Result? {
        if (params.scoreComputationMode != ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT) return null
        return MostMasteriesBoundCache.bound(params, shouldContinue = shouldContinue)
    }

    /**
     * COMPARE half of [proveMostMasteriesQuality]: the verdict for [result] against a [bound] computed
     * for the same [params] — pure arithmetic, instant. Picks the bound's read in the result's units
     * (folded with required targets, the bare core without), self-checks soundness, and returns
     * ProvenOptimal (incumbent reaches the bound), ProvenWithin(bound / incumbent − 1) or Unavailable.
     */
    internal fun compareMostMasteriesQuality(
        params: WakfuBestBuildParams,
        bound: MostMasteriesCertificate.Result,
        result: SolverResult<BuildCombination>,
    ): MostMasteriesProof {
        if (WakfuBuildSolver.needsItemPrefilter(params.targetStats)) return MostMasteriesProof.Unavailable
        if (result.isOptimal) return MostMasteriesProof.ProvenOptimal
        val incumbent = result.mostMasteriesObjective ?: return MostMasteriesProof.Unavailable
        if (incumbent <= 0) return MostMasteriesProof.Unavailable
        // The model's exact fold predicate (no `target > 0` filter — a 0-valued required target still folds).
        val hasRequiredTargets = params.targetStats.any { it.characteristic.isRequiredMostMasteriesTarget() }
        // T3 (plan §8.18): a HARD-leg result is optimal among the targets-met builds — it is compared with the
        // certificate's targets-met read; the soft read also bounds target-missing builds the hard leg never returns.
        val upper = bound.comparableUpper(result.mostMasteriesHardConstraintsMet, hasRequiredTargets)
        // Self-check (mandatory, mirrors the max-damage siblings): the certificate is a sound
        // upper bound on a FEASIBLE incumbent, so a strictly greater incumbent can only mean
        // the certifier under-counted on live data — suppress the badge and log loudly.
        // Equality alone is the proven-optimal case.
        if (incumbent > upper) {
            logger.error {
                "MM certificate self-check FAILED (badge suppressed): upper=$upper < incumbent=$incumbent " +
                    "— the certifier under-counted on live data. Solve is unaffected."
            }
            return MostMasteriesProof.Unavailable
        }
        return if (incumbent == upper) {
            MostMasteriesProof.ProvenOptimal
        } else {
            MostMasteriesProof.ProvenWithin(upper.toDouble() / incumbent - 1)
        }
    }

    /** Verdict of [proveMostMasteriesQuality] — mirrors [MaxDamageSearch.MaxDamageProof]. */
    sealed interface MostMasteriesProof {
        /** The result provably IS the optimum (CP-SAT proof, or the incumbent reached the bound). */
        data object ProvenOptimal : MostMasteriesProof

        /** The result is provably within [percent] (e.g. 0.112 = 11.2%) of the optimum. */
        data class ProvenWithin(
            val percent: Double,
        ) : MostMasteriesProof

        /** Unsupported shape or missing comparable objective — no badge, never a fake one. */
        data object Unavailable : MostMasteriesProof
    }

    /**
     * Post-search optimality proof (P4) for a finished max-damage [result] of [params]. Rebuilds the SAME
     * filtered pool / runes / sublimations the search used and delegates to [MaxDamageSearch.proveOptimality].
     * The single production entry both the CLI and the GUI call — **meant to run async after the search**
     * (a full exact tier-2 solve can take minutes). Returns [MaxDamageSearch.MaxDamageProof.Unavailable] for a
     * non-max-damage request.
     */

    fun proveMaxDamageOptimality(
        params: WakfuBestBuildParams,
        result: SolverResult<BuildCombination>,
        // B8: polled once per certifier DP stage so a cancelled proof (search restarted / window closed) stops
        // the ~minutes-per-cell exact pass promptly instead of running it to completion off-screen.
        isCancelled: () -> Boolean = { false },
        // User-facing progress: soft-leg stage keys, forwarded to the GUI proof narrator.
        onPhase: (String) -> Unit = {},
    ): MaxDamageSearch.MaxDamageProof {
        if (params.scoreComputationMode != ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE) {
            return MaxDamageSearch.MaxDamageProof.Unavailable
        }
        val equipmentsByItemType =
            groupAndFilterEquipments(
                excludedItems = params.excludedItems,
                forcedItems = params.forcedItems,
                maxRarity = params.maxRarity,
                excludedRarities = params.excludedRarities,
                character = params.character
            )
        // Pass the rune / sublimation catalogs exactly as [run] does (exclusions applied) — the model honours
        // useRunes / useSublimations internally, so the certificate sees the same availability the search did.
        return MaxDamageSearch.proveOptimality(params, equipmentsByItemType, runes, activeSublimations(params), result, isCancelled = isCancelled, onPhase = onPhase)
    }

    /**
     * Stops the optimality work the engine itself started beside the latest search and left running once it ended (E10: a
     * normally completed search keeps its max-damage certificate warm-up and its most-masteries quality-bound warm-up so
     * the post-search proof can join them instead of recomputing). For a front-end whose user stopped the proof or
     * declined it: both computes bail within a stage and cache nothing, and a later proof simply recomputes. Idempotent;
     * a no-op when nothing runs. Call it only while NO search is running — it would otherwise cancel that search's own
     * warm-ups (the max-damage one is what lets the search stop early once its certificate lands). Cancel the proof you
     * launched first: a proof still waiting on a warm-up that is cancelled under it starts its own compute.
     */
    fun cancelBackgroundProofs() {
        MaxDamageSearch.cancelCertificateWarmup()
        MostMasteriesBoundCache.supersedeAll()
    }

    /**
     * SILENT-REFINEMENT entry (journal 2026-07-21): after [proveMaxDamageOptimality] returned a soft-leg
     * `ProvenWithin`, re-bound the conditional partition with the per-carrier exact closure and return the
     * improved verdict — [MaxDamageSearch.MaxDamageProof.ProvenOptimal] when the refined union meets the
     * incumbent. Null = refinement not applicable / cancelled / no improvement (keep the shown badge).
     * Wall: minutes; meant to run async while the GUI shows the first badge plus a refining indicator.
     */
    fun refineMaxDamageOptimality(
        params: WakfuBestBuildParams,
        result: SolverResult<BuildCombination>,
        isCancelled: () -> Boolean = { false },
        onPhase: (String) -> Unit = {},
    ): MaxDamageSearch.MaxDamageProof? {
        if (params.scoreComputationMode != ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE) return null
        val equipmentsByItemType =
            groupAndFilterEquipments(
                excludedItems = params.excludedItems,
                forcedItems = params.forcedItems,
                maxRarity = params.maxRarity,
                excludedRarities = params.excludedRarities,
                character = params.character
            )
        return MaxDamageSearch.refineSoftLegProof(
            params,
            equipmentsByItemType,
            runes,
            activeSublimations(params),
            result,
            isCancelled = isCancelled,
            onPhase = onPhase
        )
    }

    /**
     * E8 fast-path (SOLVER_PERFORMANCE §7): when the finished search left a SUBOPTIMAL max-damage [result] — i.e.
     * [proveMaxDamageOptimality] returned [MaxDamageSearch.MaxDamageProof.ProvenWithin], so the certificate proves a
     * higher achievable damage than the incumbent reached — CONSTRUCT the proven-optimal build directly from the
     * certificate DP instead of running a fresh full solve. Rebuilds the SAME filtered pool the search used and
     * delegates to [WakfuBuildSolver.dpConstructProvenOptimum], which re-solves a tiny restricted pool and returns the
     * build ONLY when it provably reaches the DP bound (SOUND — else null ⇒ the caller keeps [result]). Meant to run
     * async right after a `ProvenWithin` verdict: it reuses that same cached ledger, so it adds ~one explain-pass DP.
     * Returns null for a non-max-damage request, one carrying a required (non-maximized) target — a MAXIMIZED-mastery
     * row such as the GUI's default "distance mastery 1" does not count, max-damage ignores it, nor does a floor (a row of
     * target 0, which the construct enforces) — or when construction can't reach the bound. Bounded: its open-ended full-pool fallback gives up after
     * [WakfuBuildSolver.E8_FALLBACK_WALL_CAP_SECONDS]; [isCancelled] (polled during the whole rescue — the GUI passes its
     * proof-cancel flag) abandons it early when the proof is superseded.
     */
    fun constructMaxDamageProvenOptimum(
        params: WakfuBestBuildParams,
        result: SolverResult<BuildCombination>,
        isCancelled: () -> Boolean = { false },
    ): SolverResult<BuildCombination>? {
        if (params.scoreComputationMode != ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE) return null
        val incumbent = result.maxDamageRawProxy ?: result.maxDamageObjective ?: return null
        val equipmentsByItemType =
            groupAndFilterEquipments(
                excludedItems = params.excludedItems,
                forcedItems = params.forcedItems,
                maxRarity = params.maxRarity,
                excludedRarities = params.excludedRarities,
                character = params.character
            )
        return runBlocking {
            WakfuBuildSolver.dpConstructProvenOptimum(
                params,
                equipmentsByItemType,
                runes,
                activeSublimations(params),
                incumbentObjective = incumbent,
                isCancelled = isCancelled
            )
        }
    }

    /**
     * The sublimation catalog minus [WakfuBestBuildParams.excludedSublimations] (French/English name match,
     * case-insensitive — same convention as forced subs). The SINGLE filter every production entry uses
     * ([run], [proveMaxDamageOptimality], [constructMaxDamageProvenOptimum]) so the search, the certificate
     * (whose cache fingerprint hashes the sub identity list) and the E8 construction all see the same set.
     */
    internal fun activeSublimations(
        params: WakfuBestBuildParams,
        allSublimations: List<Sublimation> = sublimations,
    ): List<Sublimation> {
        val excluded = params.excludedSublimations.map { it.lowercase() }.toSet()
        val forced = params.forcedSublimations.map { it.lowercase() }.toSet()
        return allSublimations
            .filterNot { it.name.fr.lowercase() in excluded || it.name.en.lowercase() in excluded }
            .filter { sub ->
                val isForced = sub.name.fr.lowercase() in forced || sub.name.en.lowercase() in forced
                // Cap on the GENERATION tier (the name's I/II/III), not the shard upgrade level [maxTier] —
                // "≤ 2" then excludes Mesure III (tier 3) as a user expects, since every epic's maxTier is 1.
                isForced || params.maxSublimationTier?.let { sub.nameTier <= it } != false
            }
    }

    /**
     * The filtered, slot-grouped pool a production search of [params] runs on (before domination), every item resolved at
     * the character's level ([Equipment.atLevel]).
     */
    internal fun poolFor(params: WakfuBestBuildParams): Map<ItemType, List<Equipment>> =
        groupAndFilterEquipments(
            excludedItems = params.excludedItems,
            forcedItems = params.forcedItems,
            maxRarity = params.maxRarity,
            excludedRarities = params.excludedRarities,
            character = params.character
        )

    private fun groupAndFilterEquipments(
        excludedItems: List<String>,
        forcedItems: List<String>,
        maxRarity: Rarity,
        excludedRarities: Set<Rarity>,
        character: Character,
    ): Map<ItemType, List<Equipment>> {
        val itemsExcluded = excludedItems.map { it.lowercase() }
        val eligibleEquipments =
            equipments
                .asSequence()
                .filter { equipment ->
                    equipment.rarity <= maxRarity && equipment.rarity !in excludedRarities
                }.filter { equipment ->
                    equipment.isLevelExemptCompanion ||
                        (equipment.level <= character.level && equipment.level >= character.minLevel)
                }.filter { equipment -> equipment.name.fr.lowercase() !in itemsExcluded }
                // The static EQUIP conditions: an item no character can wear, and another class's emblem / amulet, go.
                .filter { equipment -> equipment.isWearableBy(character.clazz) }
                // Level-scaled lines (the Dofus Pourpre's "100% of level as Elemental Mastery") resolved at the
                // character's level HERE, once per request: every consumer of the pool (domination, prefilter, CP-SAT
                // model, scorers, both certificates, the build handed to the GUI / CLI / Zenith) then reads plain stats.
                .map { equipment -> equipment.atLevel(character.level) }
                .toList()
        // Only the USER's forced names narrow a slot (the model's `Σ same-name ≥ 1` then equips each of them); what a
        // forced item requires (a forced nation sword's ring) is kept beside them in that slot, never narrows one on its
        // own — the model's `sword ≤ ring` equips it. The RING slot is never narrowed: a build wears TWO rings, so
        // forcing one (or a sword, whose key ring takes one) leaves the second free — narrowing it to the forced rings
        // took that second ring away, and CP-SAT then proved OPTIMAL a build worse than the true forced-item optimum.
        val userForced = forcedItems.mapTo(HashSet()) { it.lowercase() }
        val itemsToKeep = forcedNamesWithRequirements(forcedItems, eligibleEquipments)
        val forcedWeaponTypes =
            eligibleEquipments
                .filter { it.name.fr.lowercase() in userForced }
                .map { it.itemType }
                .toSet()
        val equipmentsByItemType =
            eligibleEquipments
                .groupBy { it.itemType }
                .mapValues { (type, value) ->
                    if (type != ItemType.RING && value.any { it.name.fr.lowercase() in userForced }) {
                        value.filter { it.name.fr.lowercase() in itemsToKeep }
                    } else {
                        value
                    }
                }.toMutableMap()
        if (ItemType.TWO_HANDED_WEAPONS in forcedWeaponTypes) {
            equipmentsByItemType.remove(ItemType.ONE_HANDED_WEAPONS)
            equipmentsByItemType.remove(ItemType.OFF_HAND_WEAPONS)
        } else if (ItemType.ONE_HANDED_WEAPONS in forcedWeaponTypes || ItemType.OFF_HAND_WEAPONS in forcedWeaponTypes) {
            equipmentsByItemType.remove(ItemType.TWO_HANDED_WEAPONS)
        }
        // An item whose required item did not make it into the pool (above the rarity cap, out of the level band,
        // excluded by name, crowded out of a forced slot) can't be worn in this request: it goes too.
        return withRequirementsMet(equipmentsByItemType)
    }

    /**
     * The lower-cased French [forcedItems] names plus the names of the items they require (a nation sword's ring), to a
     * fixpoint, resolved among [catalog] — what "forcing" an item means for the pool: its slot keeps it AND whatever it
     * can't be worn without.
     */
    internal fun forcedNamesWithRequirements(
        forcedItems: List<String>,
        catalog: List<Equipment>,
    ): Set<String> {
        val byId = catalog.associateBy { it.equipmentId }
        val names = forcedItems.mapTo(LinkedHashSet()) { it.lowercase() }
        var frontier: Set<String> = names.toSet()
        while (frontier.isNotEmpty()) {
            frontier =
                catalog
                    .filter { it.name.fr.lowercase() in frontier }
                    .flatMap { it.requiredItemIds }
                    .mapNotNull { byId[it]?.name?.fr?.lowercase() }
                    .filterTo(LinkedHashSet()) { names.add(it) }
        }
        return names
    }

    /**
     * Validates a search request and returns ALL problems found (empty list = valid) so the GUI can show them
     * together in one pop-up and the CLI can report them at once. A request that fails here is invalid **by
     * construction** — no build can satisfy it — so no search is started. Checks: contradictory level bounds;
     * a forced item the character can't equip (level outside [minLevel, level] — PETS/MOUNTS exempt — or a
     * rarity above [WakfuBestBuildParams.maxRarity] / in [WakfuBestBuildParams.excludedRarities]); a forced
     * item that is also excluded; more distinct forced items than a slot can host (1 per slot, 2 rings); a
     * forced two-handed weapon combined with a forced one-handed / off-hand weapon (a 2H occupies both hands);
     * more than one forced item of the epic / relic exclusivity group (a build equips at most one of each; the epic group
     * also holds two COMMON items — [me.chosante.common.ExclusiveGroup]); more than one forced epic /
     * relic SUBLIMATION (same ≤1 rule); a forced epic/relic sublimation whose carrier-item rarity the search
     * excludes (it could never be socketed); and more forced sublimations than a build can host (10). The item EQUIP
     * conditions add: a forced item the game never lets anyone wear, another class's item, an item whose required item
     * the search can't equip (a nation sword whose ring is excluded or above the rarity cap), and two forced items that
     * exclude each other, a forced item whose stat gate caps a stat below a target row ([RequestValidationProblem.ForcedItemStatGateContradictsTarget])
     * — and a forced item's required items count as forced in the slot and rarity budgets (the
     * sword's EPIC ring takes a ring slot and the epic budget). A forced item/sub name that matches nothing is ignored
     * (a typo can't be equipped). [allEquipments] / [allSublimations] are injectable for tests.
     */
    fun validateRequest(
        params: WakfuBestBuildParams,
        allEquipments: List<Equipment> = equipments,
        allSublimations: List<Sublimation> = sublimations,
    ): List<RequestValidationProblem> {
        val problems = mutableListOf<RequestValidationProblem>()
        val character = params.character

        if (character.minLevel > character.level) {
            problems += RequestValidationProblem.LevelRangeInvalid(character.minLevel, character.level)
        }

        // Resolve each forced-item name once (French-name match, like the engine's own filtering). A name
        // with no match is a typo no-op everywhere below.
        val excludedNames = params.excludedItems.map { it.lowercase() }.toSet()
        val forcedItemMatches: Map<String, List<Equipment>> =
            params.forcedItems
                .map { it.lowercase() }
                .toSet()
                .associateWith { name -> allEquipments.filter { it.name.fr.lowercase() == name } }
                .filterValues { it.isNotEmpty() }

        val catalogById = allEquipments.associateBy { it.equipmentId }

        // Whether the search could equip [item] at all: level / rarity band, not excluded by name, its class's.
        fun searchCanEquip(item: Equipment) = item.isEquippableFor(params) && item.name.fr.lowercase() !in excludedNames && item.isWearableBy(character.clazz)
        for ((name, matches) in forcedItemMatches) {
            if (matches.none { it.isEquippableFor(params) }) {
                problems += RequestValidationProblem.ForcedItemNotEquippable(matches.first(), character.minLevel, character.level)
            }
            if (name in excludedNames) {
                problems += RequestValidationProblem.ForcedItemAlsoExcluded(matches.first())
            }
            // The item EQUIP conditions the game checks before anything else (AGENTS.md §4 "Item equip conditions").
            if (matches.all { it.equipCriterion?.never == true }) {
                problems += RequestValidationProblem.ForcedItemNeverEquippable(matches.first())
            } else if (matches.none { it.isWearableBy(character.clazz) }) {
                val classes = matches.flatMap { it.equipCriterion?.classes.orEmpty() }.distinct()
                problems += RequestValidationProblem.ForcedItemWrongClass(matches.first(), classes, character.clazz)
            }
            // A forced item needs its required items equippable too (a nation sword needs its EPIC ring: a rarity cap
            // below epic, or excluding the ring, makes the sword impossible). Reported when EVERY otherwise
            // equippable match of the name misses one of its requirements.
            val wearable = matches.filter(::searchCanEquip)
            val missing = wearable.map { item -> item.requiredItemIds.firstOrNull { id -> catalogById[id]?.let(::searchCanEquip) != true } }
            if (wearable.isNotEmpty() && missing.all { it != null }) {
                val requiredId = missing.first()!!
                val requiredName = catalogById[requiredId]?.name ?: I18nText("#$requiredId", "#$requiredId", "#$requiredId", "#$requiredId")
                problems += RequestValidationProblem.ForcedItemRequirementUnavailable(wearable.first(), requiredName)
            }
        }

        // A forced item whose stat gate caps a stat BELOW what a required target row asks for (forced Cartes And — range ≤ 3 out
        // of combat — with a range target of 4): the game would show the item inactive on any build meeting the target. Only an
        // upper gate is read, and only when the target exceeds it by more than every in-combat-only bonus the search could add
        // (start-of-combat / conditional sublimation effects on that stat, summed over every sub in play — a sound over-estimate;
        // a conversion INTO the stat makes it give up). Reported when EVERY match of the name carries such a gate.
        val subNamesForced = params.forcedSublimations.map { it.lowercase() }.toSet()
        val subsInPlay =
            allSublimations.filter { sub ->
                (sub.solverChoosable && params.useSublimations) || sub.name.fr.lowercase() in subNamesForced || sub.name.en.lowercase() in subNamesForced
            }

        fun inCombatHeadroom(characteristic: Characteristic): Int? {
            if (subsInPlay.any { it.conversion?.to?.foldedToUsableStat() == characteristic }) return null
            return subsInPlay
                .filter { it.kind != SublimationKind.COMBAT_CONDITIONAL }
                .flatMap { it.effects.filterIsInstance<SublimationEffect.StatEffect>() }
                .filter { !it.appliesBeforeCombat && it.characteristic.foldedToUsableStat() == characteristic }
                .sumOf { it.magnitudeAtLevel(character.level).coerceAtLeast(0) }
        }
        for ((_, matches) in forcedItemMatches) {
            val contradictions =
                matches.map { item ->
                    item.statGates.firstNotNullOfOrNull { gate ->
                        if (gate.comparison != CriterionComparison.LE && gate.comparison != CriterionComparison.LT) return@firstNotNullOfOrNull null
                        val cap = if (gate.comparison == CriterionComparison.LT) gate.value - 1 else gate.value
                        val row =
                            params.targetStats.firstOrNull { it.characteristic.foldedToUsableStat() == gate.sheetCharacteristic && it.target > 0 }
                                ?: return@firstNotNullOfOrNull null
                        val headroom = inCombatHeadroom(gate.sheetCharacteristic) ?: return@firstNotNullOfOrNull null
                        if (row.target > cap + headroom) RequestValidationProblem.ForcedItemStatGateContradictsTarget(item, gate, row.target) else null
                    }
                }
            if (contradictions.all { it != null }) problems += contradictions.first()!!
        }

        // Forcing an item forces what it needs (a nation sword brings its ring — the pool does the same), so the slot
        // and rarity budgets below count those required items as forced too.
        val forcedWithRequirements: Map<String, List<Equipment>> =
            forcedNamesWithRequirements(forcedItemMatches.keys.toList(), allEquipments)
                .associateWith { name -> allEquipments.filter { it.name.fr.lowercase() == name } }
                .filterValues { it.isNotEmpty() }

        // Two forced items the game refuses together (an equip condition forbids one with the other; either way round).
        val forcedNames = forcedWithRequirements.keys.toList()
        for (i in forcedNames.indices) {
            for (j in i + 1 until forcedNames.size) {
                val a = forcedWithRequirements.getValue(forcedNames[i])
                val b = forcedWithRequirements.getValue(forcedNames[j])
                if (a.all { x -> b.all { y -> equipConflict(x, y) } }) {
                    problems += RequestValidationProblem.ForcedItemsMutuallyExclusive(listOf(a.first().name, b.first().name))
                }
            }
        }

        // Slot contention among DISTINCT forced names: every slot hosts one item, except rings (two slots —
        // and two forced rings are always distinct names here, so both can equip). Weapons are checked as a
        // cross-type conflict below (a two-handed weapon occupies both hands).
        val weaponTypes = setOf(ItemType.TWO_HANDED_WEAPONS, ItemType.ONE_HANDED_WEAPONS, ItemType.OFF_HAND_WEAPONS)
        val forcedByType = forcedWithRequirements.values.map { it.first() }.groupBy { it.itemType }
        for ((type, items) in forcedByType) {
            val capacity = if (type == ItemType.RING) 2 else 1
            if (items.size > capacity) {
                problems += RequestValidationProblem.ForcedItemsSlotConflict(type, capacity, items.map { it.name })
            }
        }
        val forcedTwoHanded = forcedByType[ItemType.TWO_HANDED_WEAPONS].orEmpty()
        val forcedOtherHands = weaponTypes.minus(ItemType.TWO_HANDED_WEAPONS).flatMap { forcedByType[it].orEmpty() }
        if (forcedTwoHanded.isNotEmpty() && forcedOtherHands.isNotEmpty()) {
            problems += RequestValidationProblem.ForcedWeaponsConflict((forcedTwoHanded + forcedOtherHands).map { it.name })
        }

        // Exclusivity budget: a valid build equips at most one item of the EPIC group (every EPIC item and two COMMON ones,
        // [Equipment.exclusiveGroup]) and one of the RELIC group. A name counts against a budget only when EVERY item it
        // resolves to is in that group (an ambiguous multi-rarity name could still be satisfied by another variant).
        for ((group, rarity) in listOf(ExclusiveGroup.EPIC to Rarity.EPIC, ExclusiveGroup.RELIC to Rarity.RELIC)) {
            val inGroup =
                forcedWithRequirements.values
                    .filter { matches -> matches.all { it.exclusiveGroup == group } }
                    .map { it.first() }
            if (inGroup.size > 1) {
                problems += RequestValidationProblem.ForcedItemRarityBudgetExceeded(rarity, inGroup.map { it.name })
            }
        }

        if (params.forcedSublimations.isNotEmpty()) {
            val forcedSubNames = params.forcedSublimations.map { it.lowercase() }.toSet()
            val forcedSubs =
                allSublimations.filter { it.name.fr.lowercase() in forcedSubNames || it.name.en.lowercase() in forcedSubNames }
            // A sublimation both forced and excluded contradicts itself (like ForcedItemAlsoExcluded).
            val excludedSubNames = params.excludedSublimations.map { it.lowercase() }.toSet()
            forcedSubs
                .filter { it.name.fr.lowercase() in excludedSubNames || it.name.en.lowercase() in excludedSubNames }
                .forEach { problems += RequestValidationProblem.SublimationForcedAndExcluded(it.name) }
            for (rarity in listOf(SublimationRarity.EPIC, SublimationRarity.RELIC)) {
                val ofRarity = forcedSubs.filter { it.rarity == rarity }
                if (ofRarity.size > 1) {
                    problems += RequestValidationProblem.ForcedSublimationRarityExceeded(rarity, ofRarity.map { it.name })
                }
                // An epic/relic sublimation is socketed ON an equipped item of the same rarity — if the search
                // excludes that item rarity, the sub can never be hosted.
                val carrierRarity = if (rarity == SublimationRarity.EPIC) Rarity.EPIC else Rarity.RELIC
                if (carrierRarity > params.maxRarity || carrierRarity in params.excludedRarities) {
                    ofRarity.forEach { problems += RequestValidationProblem.ForcedSublimationNoCarrier(it.name, rarity) }
                }
            }
            // Epic/relic forced subs are bounded ≤1 each above; the remaining budget is the 10 NORMAL slots.
            val forcedNormalCount = forcedSubs.count { it.rarity == SublimationRarity.NORMAL }
            if (forcedNormalCount > MAX_NORMAL_SUBLIMATIONS) {
                problems += RequestValidationProblem.ForcedSublimationsExceedCapacity(forcedNormalCount, MAX_NORMAL_SUBLIMATIONS)
            }
        }

        return problems
    }

    /** Wakfu: a build hosts at most 10 NORMAL sublimations (epic/relic are each bounded ≤1 separately). */
    private const val MAX_NORMAL_SUBLIMATIONS = 10

    private fun Equipment.isEquippableFor(params: WakfuBestBuildParams): Boolean {
        val rarityOk = rarity <= params.maxRarity && rarity !in params.excludedRarities
        val levelOk =
            isLevelExemptCompanion ||
                (level >= params.character.minLevel && level <= params.character.level)
        return rarityOk && levelOk
    }
}

/**
 * A companion-slot item with no character-level requirement: equippable at any character level, ignoring the
 * requested `[minLevel, level]` band. True for familiers and mounts (level-less in game, force-leveled only for
 * display). FALSE for Lucky Charms — they share the PETS slot but carry a real level requirement
 * ([Equipment.levelRestricted]), so they are level-filtered like ordinary gear.
 */
private val Equipment.isLevelExemptCompanion: Boolean
    get() = (itemType == ItemType.PETS || itemType == ItemType.MOUNTS) && !levelRestricted

/**
 * A single problem with a search request, found by [WakfuBestBuildFinderAlgorithm.validateRequest]. Structured
 * (not a pre-formatted string) so the GUI localizes each one and the CLI formats them in one go.
 */
sealed interface RequestValidationProblem {
    /** The character's minimum level is above its level — no normal item fits the range. */
    data class LevelRangeInvalid(
        val minLevel: Int,
        val characterLevel: Int,
    ) : RequestValidationProblem

    /** A forced [item] can't be equipped: level outside [minLevel, characterLevel] or a disallowed rarity. */
    data class ForcedItemNotEquippable(
        val item: Equipment,
        val minLevel: Int,
        val characterLevel: Int,
    ) : RequestValidationProblem

    /** The same [item] is both forced and excluded — the two lists contradict each other. */
    data class ForcedItemAlsoExcluded(
        val item: Equipment,
    ) : RequestValidationProblem

    /** A forced [item] is reserved to other [classes] by its equip condition; a [characterClass] can't wear it. */
    data class ForcedItemWrongClass(
        val item: Equipment,
        val classes: List<CharacterClass>,
        val characterClass: CharacterClass,
    ) : RequestValidationProblem

    /** A forced [item] can never be equipped in the game (its equip condition is `False`). */
    data class ForcedItemNeverEquippable(
        val item: Equipment,
    ) : RequestValidationProblem

    /**
     * A forced [item] can only be worn together with [required] (its equip condition), which this search can't equip
     * (its level or rarity is outside the search, it is excluded, or it is another class's item).
     */
    data class ForcedItemRequirementUnavailable(
        val item: Equipment,
        val required: I18nText,
    ) : RequestValidationProblem

    /**
     * A forced [item]'s stat [gate] caps a stat below the [target] a request row asks for (forced Cartes And — range ≤ 3 out of
     * combat — with a range target of 4), beyond what any in-combat-only bonus could add: the item would be inactive in game.
     */
    data class ForcedItemStatGateContradictsTarget(
        val item: Equipment,
        val gate: ItemStatGate,
        val target: Int,
    ) : RequestValidationProblem

    /** Two forced [items] the game refuses together (an equip condition of one forbids the other). */
    data class ForcedItemsMutuallyExclusive(
        val items: List<I18nText>,
    ) : RequestValidationProblem

    /** More distinct forced [items] than the [itemType] slot can host ([capacity]: 1, rings 2). */
    data class ForcedItemsSlotConflict(
        val itemType: ItemType,
        val capacity: Int,
        val items: List<I18nText>,
    ) : RequestValidationProblem

    /** A forced two-handed weapon occupies both hands — it can't coexist with a forced 1H / off-hand [items]. */
    data class ForcedWeaponsConflict(
        val items: List<I18nText>,
    ) : RequestValidationProblem

    /**
     * More than one forced item of the [rarity] exclusivity group (epic or relic — [me.chosante.common.ExclusiveGroup]: the
     * EPIC group also holds two COMMON items); a valid build equips at most one of each.
     */
    data class ForcedItemRarityBudgetExceeded(
        val rarity: Rarity,
        val items: List<I18nText>,
    ) : RequestValidationProblem

    /** More than one [rarity] (epic or relic) sublimation was forced; a build hosts at most one of each. */
    data class ForcedSublimationRarityExceeded(
        val rarity: SublimationRarity,
        val sublimations: List<I18nText>,
    ) : RequestValidationProblem

    /**
     * A forced epic/relic [sublimation] must be socketed on an equipped item of the same [rarity], but the
     * search excludes that item rarity ([WakfuBestBuildParams.maxRarity] / [WakfuBestBuildParams.excludedRarities]).
     */
    data class ForcedSublimationNoCarrier(
        val sublimation: I18nText,
        val rarity: SublimationRarity,
    ) : RequestValidationProblem

    /** More forced sublimations ([count]) than a build can socket ([max] = 10). */
    data class ForcedSublimationsExceedCapacity(
        val count: Int,
        val max: Int,
    ) : RequestValidationProblem

    /** The same [sublimation] is both forced and excluded — the two lists contradict each other. */
    data class SublimationForcedAndExcluded(
        val sublimation: I18nText,
    ) : RequestValidationProblem
}

/** English one-liner for a [RequestValidationProblem] — the CLI / exception-message rendering (the GUI localizes). */
fun RequestValidationProblem.describe(): String =
    when (this) {
        is RequestValidationProblem.LevelRangeInvalid ->
            "min level $minLevel is above the character level $characterLevel"
        is RequestValidationProblem.ForcedItemNotEquippable ->
            "forced item '${item.name.en}' can't be equipped (level/rarity outside the search)"
        is RequestValidationProblem.ForcedItemAlsoExcluded ->
            "'${item.name.en}' is both forced and excluded"
        is RequestValidationProblem.ForcedItemWrongClass ->
            "forced item '${item.name.en}' is for ${classes.joinToString()} only, not $characterClass"
        is RequestValidationProblem.ForcedItemNeverEquippable ->
            "forced item '${item.name.en}' can never be equipped in the game"
        is RequestValidationProblem.ForcedItemRequirementUnavailable ->
            "forced item '${item.name.en}' can only be worn with '${required.en}', which this search can't equip"
        is RequestValidationProblem.ForcedItemStatGateContradictsTarget ->
            "forced item '${item.name.en}' is only active with ${gate.characteristic} ${gate.comparison.symbol} ${gate.value} out of combat, " +
                "but the request asks for $target"
        is RequestValidationProblem.ForcedItemsMutuallyExclusive ->
            "these forced items can't be worn together: ${items.joinToString { it.en }}"
        is RequestValidationProblem.ForcedItemsSlotConflict ->
            "the $itemType slot can host $capacity forced item(s), got: ${items.joinToString { it.en }}"
        is RequestValidationProblem.ForcedWeaponsConflict ->
            "a forced two-handed weapon can't be combined with a forced one-handed/off-hand weapon: ${items.joinToString { it.en }}"
        is RequestValidationProblem.ForcedItemRarityBudgetExceeded ->
            "a build equips at most one item of the $rarity exclusivity group, got: ${items.joinToString { it.en }}"
        is RequestValidationProblem.ForcedSublimationRarityExceeded ->
            "a build hosts at most one $rarity sublimation, got: ${sublimations.joinToString { it.en }}"
        is RequestValidationProblem.ForcedSublimationNoCarrier ->
            "forced sublimation '${sublimation.en}' needs an equipped $rarity item, but that rarity is excluded from the search"
        is RequestValidationProblem.ForcedSublimationsExceedCapacity ->
            "$count sublimations forced, a build can socket at most $max"
        is RequestValidationProblem.SublimationForcedAndExcluded ->
            "sublimation '${sublimation.en}' is both forced and excluded"
    }

/**
 * Thrown by [WakfuBestBuildFinderAlgorithm.run] when a request has one or more [problems]. The GUI pre-validates
 * with [WakfuBestBuildFinderAlgorithm.validateRequest] and shows the problems in a pop-up, so it normally never
 * reaches this; the throw is the CLI / safety floor.
 */
class InvalidRequestException(
    val problems: List<RequestValidationProblem>,
) : IllegalArgumentException(
        "Invalid search request:\n" + problems.joinToString("\n") { "  - ${it.describe()}" }
    )

data class WakfuBestBuildParams(
    val character: Character,
    val targetStats: TargetStats,
    val searchDuration: Duration,
    val stopWhenBuildMatch: Boolean,
    val maxRarity: Rarity,
    /** Rarities the build may not use at all (independent of [maxRarity]); empty allows every rarity. */
    val excludedRarities: Set<Rarity> = emptySet(),
    val forcedItems: List<String>,
    val excludedItems: List<String>,
    val scoreComputationMode: ScoreComputationMode,
    // When true (default), the OR-Tools solver socket-fills equipped items with the best runes for the
    // requested stats (best-achievable model). See RuneType / WakfuBuildSolver.createRuneModel.
    val useRunes: Boolean = true,
    // Runes the user requires the build to socket at least once (matched on the rune's French name,
    // like forcedItems). Their stat is added to the modelable rune set. See createRuneModel.
    // Used by the CLI's --forced-runes; the GUI uses the per-item [forcedRunesByItem] instead.
    val forcedRunes: List<String> = emptyList(),
    // Runes the user pins onto a SPECIFIC carrier item, keyed by the item's **French** name (like
    // forcedItems) → the multiset of rune ids ([RuneType.id]) to socket on that item. The solver forces
    // those runes into that item's sockets (which also forces the item to be equipped). See
    // createRuneModel. Repetition in the list means "N runes of that type on the item".
    val forcedRunesByItem: Map<String, List<Int>> = emptyMap(),
    // When true (default), the solver may choose statically-modelable sublimations (epic/relic/normal)
    // and applies any forcedSublimations. See WakfuBuildSolver.createSublimationModel.
    val useSublimations: Boolean = true,
    // Optional cap for solver-picked sublimations by real item tier (I/II/III). Effects are already decoded at
    // each sub's top tier, so this filters the auto-choosable catalog; forced sublimations are kept.
    val maxSublimationTier: Int? = null,
    // Sublimations the user requires the build to carry (matched on the sublimation's French name).
    // Combat-conditional subs are only usable this way. See createSublimationModel.
    val forcedSublimations: List<String> = emptyList(),
    // Sublimations the solver must NOT use (matched on the French or English name, like forcedSublimations).
    // Removed from the catalog at the production entry points ([WakfuBestBuildFinderAlgorithm.run] and the
    // proof/construct paths, so the certificate sees the same availability the search did). Forcing and
    // excluding the same sublimation is a validation error ([RequestValidationProblem.SublimationForcedAndExcluded]).
    val excludedSublimations: List<String> = emptyList(),
    // The player's selected passive loadout (matched on the passive's French name, capped to the level's
    // passive slots). Their fully-declarative flat stats fold into the solve; all selected passives ride
    // on the resulting build for display. See PassiveCatalog / WakfuBuildSolver.resolvedPassives.
    val forcedPassives: List<String> = emptyList(),
    // The attack scenario optimized by ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE (ignored by the
    // other modes).
    val damageScenario: DamageScenario = DamageScenario(),
    // Max-damage external loop only: when set, the solver is hard-constrained to **exactly** this many
    // AP, so the loop can probe each AP breakpoint (the CP-SAT objective alone can't see a breakpoint
    // that only pays off once resistance debuffs are sequenced). Ignored by the other modes.
    val maxDamageApTarget: Int? = null,
    /** §9.22 (AP,MP)-cell probes: pin actual MP to this exact value (hard equality). Probe-internal,
     *  like [maxDamageApTarget] — the soft certificate bails on pinned shapes. */
    val maxDamageMpPin: Int? = null,
    // Overrides the production CP-SAT worker count (default = cores − 1). The max-damage loop sets this so
    // its **parallel** AP probes don't each spawn cores−1 native threads and oversubscribe the CPU. Null =
    // default. Ignored when a deterministic SolverTuning is supplied.
    val solverWorkers: Int? = null,
)
