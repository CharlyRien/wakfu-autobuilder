package me.chosante.autobuilder.genetic.wakfu

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.Flow
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
        EmbeddedResources.decodeList<Sublimation>("sublimations.json") ?: emptyList()
    }

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
                MaxDamageSearch.run(params, equipmentsByItemType, runes, sublimations)
            } else {
                WakfuBuildSolver.optimize(params, equipmentsByItemType, runes, sublimations)
            }
        } catch (exception: Exception) {
            // Surface the failure to the caller instead of killing the JVM: the CLI's runBlocking
            // turns it into a visible crash, while the GUI can catch it and show an error rather than
            // having the whole desktop app terminated by exitProcess.
            logger.error(exception) { "Exception occurred during the process of finding the best equipments." }
            throw exception
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
     * together in one pop-up and the CLI can report them at once. Checks: contradictory level bounds; a forced
     * item the character can't equip (level outside [minLevel, level] — PETS/MOUNTS exempt — or a rarity above
     * [WakfuBestBuildParams.maxRarity] / in [WakfuBestBuildParams.excludedRarities]); and forcing more than one
     * epic or relic sublimation (a build hosts at most one of each). A forced item/sub name that matches nothing
     * is ignored (a typo can't be equipped). [allEquipments] / [allSublimations] are injectable for tests.
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

        for (forcedName in params.forcedItems.map { it.lowercase() }.toSet()) {
            val matches = allEquipments.filter { it.name.fr.lowercase() == forcedName }
            if (matches.isNotEmpty() && matches.none { it.isEquippableFor(params) }) {
                problems += RequestValidationProblem.ForcedItemNotEquippable(matches.first(), character.minLevel, character.level)
            }
        }

        if (params.forcedSublimations.isNotEmpty()) {
            val forcedSubNames = params.forcedSublimations.map { it.lowercase() }.toSet()
            val forcedSubs =
                allSublimations.filter { it.name.fr.lowercase() in forcedSubNames || it.name.en.lowercase() in forcedSubNames }
            for (rarity in listOf(SublimationRarity.EPIC, SublimationRarity.RELIC)) {
                val ofRarity = forcedSubs.filter { it.rarity == rarity }
                if (ofRarity.size > 1) {
                    problems += RequestValidationProblem.ForcedSublimationRarityExceeded(rarity, ofRarity.map { it.name })
                }
            }
        }

        return problems
    }

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

    /** More than one [rarity] (epic or relic) sublimation was forced; a build hosts at most one of each. */
    data class ForcedSublimationRarityExceeded(
        val rarity: SublimationRarity,
        val sublimations: List<I18nText>,
    ) : RequestValidationProblem
}

/**
 * Thrown by [WakfuBestBuildFinderAlgorithm.run] when a request has one or more [problems]. The GUI pre-validates
 * with [WakfuBestBuildFinderAlgorithm.validateRequest] and shows the problems in a pop-up, so it normally never
 * reaches this; the throw is the CLI / safety floor.
 */
class InvalidRequestException(
    val problems: List<RequestValidationProblem>,
) : IllegalArgumentException("Invalid search request with ${problems.size} problem(s).")

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
    // Sublimations the user requires the build to carry (matched on the sublimation's French name).
    // Combat-conditional subs are only usable this way. See createSublimationModel.
    val forcedSublimations: List<String> = emptyList(),
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
