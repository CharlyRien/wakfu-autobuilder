package me.chosante.autobuilder.genetic.wakfu

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import me.chosante.autobuilder.EmbeddedResources
import me.chosante.autobuilder.VERSION
import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.autobuilder.domain.DamageScenario
import me.chosante.autobuilder.domain.TargetStats
import me.chosante.autobuilder.genetic.SolverResult
import me.chosante.common.Character
import me.chosante.common.Equipment
import me.chosante.common.I18nText
import me.chosante.common.ItemType
import me.chosante.common.Monster
import me.chosante.common.Rarity
import me.chosante.common.RuneType
import me.chosante.common.Sublimation
import me.chosante.common.SublimationRarity
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
    val equipments: List<Equipment> by lazy {
        EmbeddedResources.decodeList<Equipment>("equipments.json")!!
    }

    /**
     * The embedded runes ([RuneType]) for the current data version, or empty if the resource is
     * absent. The OR-Tools solver socket-fills equipped items with these when [WakfuBestBuildParams.useRunes].
     */
    val runes: List<RuneType> by lazy {
        EmbeddedResources.decodeList<RuneType>("runes.json") ?: emptyList()
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
        val equipmentsByItemType =
            groupAndFilterEquipments(
                excludedItems = params.excludedItems,
                forcedItems = params.forcedItems,
                maxRarity = params.maxRarity,
                excludedRarities = params.excludedRarities,
                character = params.character
            )

        return try {
            // Max-damage routes through the external loop (AP-breakpoint probes + debuff-aware
            // sequencing valuation). Every other mode is a single CP-SAT solve, unchanged.
            if (params.scoreComputationMode == ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE) {
                MaxDamageSearch.run(params, equipmentsByItemType, runes, activeSublimations(params))
            } else {
                WakfuBuildSolver.optimize(params, equipmentsByItemType, runes, activeSublimations(params))
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
        return MaxDamageSearch.proveOptimality(params, equipmentsByItemType, runes, activeSublimations(params), result, isCancelled = isCancelled)
    }

    /**
     * E8 fast-path (SOLVER_PERFORMANCE §7): when the finished search left a SUBOPTIMAL max-damage [result] — i.e.
     * [proveMaxDamageOptimality] returned [MaxDamageSearch.MaxDamageProof.ProvenWithin], so the certificate proves a
     * higher achievable damage than the incumbent reached — CONSTRUCT the proven-optimal build directly from the
     * certificate DP instead of running a fresh full solve. Rebuilds the SAME filtered pool the search used and
     * delegates to [WakfuBuildSolver.dpConstructProvenOptimum], which re-solves a tiny restricted pool and returns the
     * build ONLY when it provably reaches the DP bound (SOUND — else null ⇒ the caller keeps [result]). Meant to run
     * async right after a `ProvenWithin` verdict: it reuses that same cached ledger, so it adds ~one explain-pass DP.
     * Returns null for a non-max-damage / required-target request, or when construction can't reach the bound.
     */
    fun constructMaxDamageProvenOptimum(
        params: WakfuBestBuildParams,
        result: SolverResult<BuildCombination>,
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
            WakfuBuildSolver.dpConstructProvenOptimum(params, equipmentsByItemType, runes, activeSublimations(params), incumbentObjective = incumbent)
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

    private fun groupAndFilterEquipments(
        excludedItems: List<String>,
        forcedItems: List<String>,
        maxRarity: Rarity,
        excludedRarities: Set<Rarity>,
        character: Character,
    ): Map<ItemType, List<Equipment>> {
        val itemsExcluded = excludedItems.map { it.lowercase() }
        val itemsToForce = forcedItems.map { it.lowercase() }
        val eligibleEquipments =
            equipments
                .asSequence()
                .filter { equipment ->
                    equipment.rarity <= maxRarity && equipment.rarity !in excludedRarities
                }.filter { equipment ->
                    (equipment.level <= character.level && equipment.level >= character.minLevel) ||
                        (equipment.itemType == ItemType.PETS || equipment.itemType == ItemType.MOUNTS)
                }.filter { equipment -> equipment.name.fr.lowercase() !in itemsExcluded }
                .toList()
        val forcedWeaponTypes =
            eligibleEquipments
                .filter { it.name.fr.lowercase() in itemsToForce }
                .map { it.itemType }
                .toSet()
        val equipmentsByItemType =
            eligibleEquipments
                .groupBy { it.itemType }
                .mapValues { (_, value) ->
                    if (value.any { it.name.fr.lowercase() in itemsToForce }) {
                        value.filter { it.name.fr.lowercase() in itemsToForce || itemsToForce.isEmpty() }
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
        return equipmentsByItemType
    }

    /**
     * Validates a search request and returns ALL problems found (empty list = valid) so the GUI can show them
     * together in one pop-up and the CLI can report them at once. A request that fails here is invalid **by
     * construction** — no build can satisfy it — so no search is started. Checks: contradictory level bounds;
     * a forced item the character can't equip (level outside [minLevel, level] — PETS/MOUNTS exempt — or a
     * rarity above [WakfuBestBuildParams.maxRarity] / in [WakfuBestBuildParams.excludedRarities]); a forced
     * item that is also excluded; more distinct forced items than a slot can host (1 per slot, 2 rings); a
     * forced two-handed weapon combined with a forced one-handed / off-hand weapon (a 2H occupies both hands);
     * more than one forced epic / relic ITEM (a build equips at most one of each); more than one forced epic /
     * relic SUBLIMATION (same ≤1 rule); a forced epic/relic sublimation whose carrier-item rarity the search
     * excludes (it could never be socketed); and more forced sublimations than a build can host (10). A forced
     * item/sub name that matches nothing is ignored (a typo can't be equipped). [allEquipments] /
     * [allSublimations] are injectable for tests.
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

        for ((name, matches) in forcedItemMatches) {
            if (matches.none { it.isEquippableFor(params) }) {
                problems += RequestValidationProblem.ForcedItemNotEquippable(matches.first(), character.minLevel, character.level)
            }
            if (name in excludedNames) {
                problems += RequestValidationProblem.ForcedItemAlsoExcluded(matches.first())
            }
        }

        // Slot contention among DISTINCT forced names: every slot hosts one item, except rings (two slots —
        // and two forced rings are always distinct names here, so both can equip). Weapons are checked as a
        // cross-type conflict below (a two-handed weapon occupies both hands).
        val weaponTypes = setOf(ItemType.TWO_HANDED_WEAPONS, ItemType.ONE_HANDED_WEAPONS, ItemType.OFF_HAND_WEAPONS)
        val forcedByType = forcedItemMatches.values.map { it.first() }.groupBy { it.itemType }
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

        // Item rarity budget: a valid build equips at most one EPIC and one RELIC item. A name counts against
        // the budget only when EVERY item it resolves to has that rarity (an ambiguous multi-rarity name could
        // still be satisfied by another variant).
        for (rarity in listOf(Rarity.EPIC, Rarity.RELIC)) {
            val ofRarity =
                forcedItemMatches.values
                    .filter { matches -> matches.all { it.rarity == rarity } }
                    .map { it.first() }
            if (ofRarity.size > 1) {
                problems += RequestValidationProblem.ForcedItemRarityBudgetExceeded(rarity, ofRarity.map { it.name })
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
            itemType == ItemType.PETS ||
                itemType == ItemType.MOUNTS ||
                (level >= params.character.minLevel && level <= params.character.level)
        return rarityOk && levelOk
    }
}

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

    /** More than one forced item of [rarity] (epic or relic); a valid build equips at most one of each. */
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
        is RequestValidationProblem.ForcedItemsSlotConflict ->
            "the $itemType slot can host $capacity forced item(s), got: ${items.joinToString { it.en }}"
        is RequestValidationProblem.ForcedWeaponsConflict ->
            "a forced two-handed weapon can't be combined with a forced one-handed/off-hand weapon: ${items.joinToString { it.en }}"
        is RequestValidationProblem.ForcedItemRarityBudgetExceeded ->
            "a build equips at most one $rarity item, got: ${items.joinToString { it.en }}"
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
    // Overrides the production CP-SAT worker count (default = cores − 1). The max-damage loop sets this so
    // its **parallel** AP probes don't each spawn cores−1 native threads and oversubscribe the CPU. Null =
    // default. Ignored when a deterministic SolverTuning is supplied.
    val solverWorkers: Int? = null,
)
