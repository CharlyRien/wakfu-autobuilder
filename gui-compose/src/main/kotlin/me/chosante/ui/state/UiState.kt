package me.chosante.ui.state

import androidx.compose.ui.graphics.Color
import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.autobuilder.domain.DamageScenario
import me.chosante.autobuilder.domain.ScenarioDamage
import me.chosante.autobuilder.domain.SpellElement
import me.chosante.autobuilder.domain.SpellRotation
import me.chosante.autobuilder.genetic.wakfu.RequestValidationProblem
import me.chosante.autobuilder.genetic.wakfu.ScoreComputationMode
import me.chosante.autobuilder.genetic.wakfu.isMaximizableMastery
import me.chosante.autobuilder.genetic.wakfu.isRandomElementStat
import me.chosante.common.CharacterClass
import me.chosante.common.Characteristic
import me.chosante.common.Equipment
import me.chosante.common.Monster
import me.chosante.common.Rarity
import me.chosante.common.history.HistoryEntry
import me.chosante.ui.i18n.Lang
import me.chosante.ui.i18n.label
import me.chosante.ui.theme.WColor
import me.chosante.ui.theme.WRarityColor
import java.math.BigDecimal
import me.chosante.common.displayedMatchPercent as commonDisplayedMatchPercent
import me.chosante.common.meetsAllTargets as commonMeetsAllTargets

enum class Phase {
    Idle,
    Searching,
    Done,
}

enum class ZenithState {
    Idle,
    Loading,
    Ready,
    Error,
}

/** What the error banner's "Retry" repeats. */
enum class ErrorRetry {
    OPEN_ZENITH,
    COPY_ZENITH,
    SEARCH,
}

/**
 * The error banner of the results panel: the [message] shown to the player and, when repeating the failed action can help,
 * what its "Retry" does ([retry]). Technical detail (host names, timeouts, stack traces) never goes in [message]: it is logged.
 */
data class UiError(
    val message: String,
    val retry: ErrorRetry? = null,
)

/** The coarse phase the running optimality proof is in. See [ProofProgress]. */
enum class ProofPhase {
    /** The AP-cell certificate DP is computing the per-cell upper bounds (the long part). */
    CERTIFYING,

    /**
     * The certificate proved a better build exists and the E8 fast-path is constructing it. NOT emitted any more by the
     * max-damage flow: that verdict now shows its "proven within X %" badge at once and the construct runs behind it
     * ([ProofState.ProvenWithin.refining]). Kept only because the stats panel's progress label still renders it
     * (`Tr.PROOF_CONSTRUCTING`); nothing sets it.
     */
    CONSTRUCTING,
}

/**
 * Live progress of the running optimality proof, shown while [ProofState.Proving].
 *
 * v1 carries only what is observable today: the [phase] and [startedAtMs] (the UI derives the elapsed
 * time from it). It is deliberately shaped for a later per-cell hook from the certifier DP —
 * [cellsDone]/[cellsTotal] stay null until the engine reports them, at which point the UI can upgrade
 * the indeterminate spinner to a real fraction without another state change.
 */
data class ProofProgress(
    val phase: ProofPhase,
    val startedAtMs: Long,
    val cellsDone: Int? = null,
    val cellsTotal: Int? = null,
    // Engine stage key of the soft-leg proof (relaxedProbe, coarse, primaryRefinement, …) so the
    // UI can narrate WHAT the multi-minute proof is doing. Null until the engine reports one.
    val detailKey: String? = null,
)

/** Max-damage AP-cell certificate verdict for the finished build (P4.4). See [UiState.proofState]. */
sealed interface ProofState {
    /**
     * No verdict: not max-damage / most-masteries, before the async computation starts, or the check was stopped before
     * it knew anything ([BuildSearchModel.stopProof]) or switched off ([UiState.verifyOptimality]), or the request is one no
     * search can prove ([UiState.prefilteredRequest]: no check is started for it). The stats panel then shows the usual
     * "not proven" hint — or, for such a request, its explanation.
     */
    data object Idle : ProofState

    /** The certificate is being computed off-thread; [progress] says which phase and since when. */
    data class Proving(
        val progress: ProofProgress,
    ) : ProofState

    /** The build is the PROVEN optimum (CP-SAT closed the gap, or the certificate did). */
    data object ProvenOptimal : ProofState

    /** Not proven optimal, but the certificate bounds the gap: the true optimum is at most [fraction] above.
     *  [refining] = work that may improve on this badge is still running behind it: the E8 construct of the proven
     *  optimum (which may swap the build in and flip to [ProvenOptimal]) and then, failing that, the per-carrier silent
     *  refinement (which may tighten the badge or close it to [ProvenOptimal]); the UI shows the badge plus a small
     *  progress indicator (with an info tooltip and a Stop link) while it is true. [BuildSearchModel.stopProof] clears
     *  it and keeps the badge. */
    data class ProvenWithin(
        val fraction: Double,
        val refining: Boolean = false,
    ) : ProofState

    /** No proof available (forced runes/sublimations, required-stat targets, or an un-proven boss). */
    data object Unavailable : ProofState
}

enum class PickerMode {
    Forced,
    Excluded,
}

/**
 * The three top-level surfaces. A deliberately tiny router (a `when` in `AppShell`) rather than a
 * navigation library — there are only three screens and state is a single [UiState].
 */
enum class Screen {
    Builder,
    Library,
    Compare,
}

/**
 * Tabs of the build-result region (everything right of the request inputs): the discovered build's
 * paperdoll + stats, or a reference view of the current class's damage spells & chosen passives.
 */
enum class BuilderTab {
    BUILD,
    SPELLS,
}

/** The compare view holds between two and four build columns. */
const val MIN_COMPARE_SLOTS = 2
const val MAX_COMPARE_SLOTS = 4

/** Ordering options for the build library. See [organizeLibrary]. */
enum class LibrarySort {
    NEWEST,
    OLDEST,
    NAME,
    LEVEL,
}

/** Folder-scoped view of the library: everything, only unfiled, or one named folder. */
sealed interface LibraryFolderFilter {
    data object All : LibraryFolderFilter

    data object Unfiled : LibraryFolderFilter

    data class Named(
        val name: String,
    ) : LibraryFolderFilter
}

sealed interface Modal {
    data object AddStat : Modal

    data class ItemPicker(
        val mode: PickerMode,
    ) : Modal

    /** Pick a sublimation by translated title + effect text — to FORCE it, or to EXCLUDE it ([exclude]). */
    data class SublimationPicker(
        val exclude: Boolean = false,
    ) : Modal

    /** Pick a passive to add to the loadout (filtered to the current class), by name / effect. */
    data object PassivePicker : Modal

    /** Pick a boss to target in max-damage mode, by translated name — auto-fills its per-element resistances. */
    data object BossPicker : Modal

    /** Choose the runes to pin onto a specific carrier item, identified by its French name. */
    data class ItemRunePicker(
        val itemName: String,
    ) : Modal

    /** Save-the-current-build dialog (name + optional note). */
    data object SaveBuild : Modal

    /**
     * Paste a build exported from this app (a [me.chosante.common.history.HistoryEntry] JSON, input +
     * result) to add it to the library and open it — so testers can share a build without a screenshot.
     */
    data object ImportBuild : Modal

    /** Edit a saved build's metadata (name, note, tags, folder). Resolves the entry at render time. */
    data class EditBuild(
        val id: String,
    ) : Modal

    /** Confirm deleting a saved build. */
    data class ConfirmDelete(
        val id: String,
        val name: String,
    ) : Modal

    /** Rename (or merge) a folder. */
    data class RenameFolder(
        val name: String,
    ) : Modal

    /** Confirm deleting a folder (members are kept, become unfiled). */
    data class ConfirmDeleteFolder(
        val name: String,
    ) : Modal

    /** Create a new, standalone tag (added to the registry, assignable later). */
    data object CreateTag : Modal

    /** Rename (or merge) a tag across every build that carries it. */
    data class RenameTag(
        val name: String,
    ) : Modal

    /** Confirm deleting a tag from every build (the builds are kept). */
    data class ConfirmDeleteTag(
        val name: String,
    ) : Modal

    /**
     * Confirm re-running the solver while a saved build is loaded — the guard behind the locked
     * search button, so the user knowingly re-optimizes the build they're editing.
     */
    data object ConfirmReSearch : Modal
}

data class UiState(
    val lang: Lang = Lang.EN,
    val clazz: CharacterClass = CharacterClass.CRA,
    val level: Int = 110,
    /** Lower item-level bound for the search; 0 = no minimum (consider every item up to [level]). */
    val minLevel: Int = 0,
    val mode: ScoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT,
    // Attack scenario for the max-damage mode (ignored by the other modes).
    val scenario: DamageScenario = DamageScenario(),
    /**
     * Boss targeted in max-damage mode (null = manual [scenario]). Picking one fills the scenario's
     * per-element resistances from the bestiary so the objective auto-picks the best playable element.
     */
    val selectedBoss: Monster? = null,
    /** Forced damage element vs the boss; null = auto (let the objective pick the best playable element). */
    val bossElement: SpellElement? = null,
    /** Dungeon HP multiplier for the turns-to-kill estimate (display only; never changes the build). */
    val bossDifficulty: String = "1",
    val targets: List<TargetRow> = defaultTargets(),
    /**
     * The OTHER search modes' work, parked while [mode] is on screen: each mode keeps its own target rows and the result found
     * under it, so a visit to another mode destroys neither (switching back restores both). The live mode is never in the map.
     * See [BuildSearchModel.setMode] and [ModeWorkspace].
     */
    val modeWorkspaces: Map<ScoreComputationMode, ModeWorkspace> = emptyMap(),
    val maxRarity: Rarity = Rarity.EPIC,
    /** Rarities the user toggled off; excluded from the search. At least one rarity always stays allowed. */
    val excludedRarities: Set<Rarity> = emptySet(),
    // 120s: long enough for max-damage (incl. runes+sublimations) to reach a PROVEN optimum at high level
    // (~80–110s after the rune fold); shorter modes still finish early and stream their result well before.
    val duration: String = "120",
    val stopAtMatch: Boolean = false,
    /**
     * "Check optimality after the search" (default ON, persisted via [LibraryPreferences]): whether the engine keeps
     * working once a search ends to prove how close the build is to the best possible one (the [proofState] pipeline,
     * in max-damage and most-masteries). An app option, not part of the request: it is not saved with a build. OFF starts
     * no proof work after the search — see [BuildSearchModel.setVerifyOptimality].
     */
    val verifyOptimality: Boolean = true,
    val forcedItems: List<ItemChip> = emptyList(),
    val excludedItems: List<ItemChip> = emptyList(),
    /** When true (default), the solver may pick statically-modelable sublimations. */
    val useSublimations: Boolean = true,
    /** Optional max item tier for solver-picked sublimations; forced sublimations can still override it. */
    val maxSublimationTier: Int? = null,
    /** Sublimations the user forces into the build (French names; incl. combat-conditional ones). */
    val forcedSublimations: List<String> = emptyList(),
    /** Sublimations the solver must never pick (French names). */
    val excludedSublimations: List<String> = emptyList(),
    /** The passive loadout the user selected (French names, capped to the level's slots). */
    val forcedPassives: List<String> = emptyList(),
    /**
     * Runes the user pins onto a specific carrier item, keyed by the item's **French** name →
     * the multiset of rune ids ([me.chosante.common.RuneType.id]) to socket on it. The engine forces
     * those runes onto that item (which also forces it to be equipped). See [Modal.ItemRunePicker].
     */
    val forcedRunesByItem: Map<String, List<Int>> = emptyMap(),
    val phase: Phase = Phase.Idle,
    val progress: Int = 0,
    val match: BigDecimal = BigDecimal.ZERO,
    val optimal: Boolean = false,
    /**
     * Max-damage mode only: true when a non-[optimal] result is *structurally* heuristic — a best-found max
     * over resistance-debuff sequencing / multi-element AP splits — rather than merely time-limited. Drives
     * which "not proven" hint the stats panel shows. See [SolverResult.maxDamageHeuristicPhases].
     */
    val maxDamageStructural: Boolean = false,
    /**
     * True when the request the shown result was computed for is one no search can prove: it wants more than one element of
     * mastery or resistance ([me.chosante.autobuilder.domain.TargetStats.needsItemPrefilter]), so the engine searches a
     * heuristic selection of the items and never awards an optimality badge, however long the search runs. The stats panel
     * then explains that instead of suggesting a longer search.
     *
     * It belongs to the RESULT, like [maxDamageStructural]: read from the request a search was started with
     * ([BuildSearchModel.search]) or a saved build was restored with ([BuildSearchModel.loadBuild]), never from the target rows
     * as they are now — editing the rows after a search must not change what that search's result says about itself.
     */
    val prefilteredRequest: Boolean = false,
    /**
     * Max-damage mode only: the AP-cell certificate's optimality verdict for the finished build (P4.4).
     * Computed asynchronously AFTER the search completes (a full exact solve can take minutes), so it starts
     * [ProofState.Idle], becomes [ProofState.Proving], then resolves. It can prove optimality CP-SAT left
     * un-closed ([optimal] false but [ProofState.ProvenOptimal]). See [ProofState].
     */
    val proofState: ProofState = ProofState.Idle,
    /**
     * True when the shown build is the best-so-far of a search the user stopped before it finished
     * ([BuildSearchModel.cancel]). That build is fully usable (save, export, Zenith) but it is a not-proven result, so the
     * stats panel says so instead of claiming a proof or blaming the time budget. Cleared by whatever replaces the build.
     */
    val searchStopped: Boolean = false,
    /**
     * The game-data version the shown build was computed with, when it is NOT the data this app ships — a saved (or imported)
     * build loaded after a game update ([BuildSearchModel.loadBuild]). The stats column then says so, quietly, until a new
     * search replaces the build; saving the build as it is keeps this stamp, since the numbers are still those of that
     * data. Null for a build found by this app's own data. Travels with the result ([ShownResult]).
     */
    val staleDataVersion: String? = null,
    /**
     * Set when the shown build was computed by an older engine than this app's ([me.chosante.autobuilder.domain.ENGINE_RESULTS_VERSION]):
     * a saved (or imported) build loaded after an engine fix that can improve results. Like [staleDataVersion], it travels with
     * the result, a new search clears it, and saving the build as it is keeps the original stamp. Null for a build found here.
     */
    val staleEngine: StaleEngine? = null,
    val build: BuildCombination? = null,
    val achieved: Map<Characteristic, Int> = emptyMap(),
    /** Best spells to cast for the build's AP, in max-damage mode only (else null). Computed off-thread. */
    val spellRotation: SpellRotation? = null,
    // Max-damage only: per-turn damage under each attack position (face/side/back/+berserk), for the result
    // breakdown. Empty in the other modes and until a max-damage build is found.
    val scenarioDamages: List<ScenarioDamage> = emptyList(),
    /** Active tab of the result region: the discovered build, or the class's spells & passives. */
    val builderTab: BuilderTab = BuilderTab.BUILD,
    val lastLandedEquipmentId: Int? = null,
    val zenith: ZenithState = ZenithState.Idle,
    val zenithUrl: String? = null,
    val toast: String? = null,
    val error: UiError? = null,
    /** Pre-search request problems shown together in the errors pop-up; non-empty blocks the search. */
    val requestErrors: List<RequestValidationProblem> = emptyList(),
    val modal: Modal? = null,
    // --- Build history / comparison ---
    val screen: Screen = Screen.Builder,
    /** Saved builds, newest first; loaded from disk at startup and kept in sync after each write. */
    val savedBuilds: List<HistoryEntry> = emptyList(),
    /** Id of the saved build currently loaded into the workspace, if any (drives the active-build chip). */
    val activeBuildId: String? = null,
    /** Display name of the loaded build, shown in the workspace; `null` ⇒ "unsaved build". */
    val activeBuildName: String? = null,
    /**
     * When a saved build is loaded, the search button is locked so re-optimizing it is a deliberate
     * act (it pops [Modal.ConfirmReSearch] first). Cleared once the user confirms or starts fresh.
     */
    val searchLocked: Boolean = false,
    /**
     * Builds pinned for the side-by-side compare view, in column order (entry ids; `null` = an empty
     * slot still showing its picker). Starts with two slots; the user can add up to four and remove
     * extras back down to two. See [me.chosante.ui.state.BuildSearchModel.setCompareSlot].
     */
    val compareSlots: List<String?> = listOf(null, null),
    /**
     * Id of the build just created by [me.chosante.ui.state.BuildSearchModel.duplicateBuild]. The
     * library briefly highlights that card (and scrolls it into view) so it's obvious where the copy
     * landed; cleared after a short delay.
     */
    val lastDuplicatedBuildId: String? = null,
    /** Library: free-text search (matches name, class display name, or any tag). In-memory only. */
    val librarySearch: String = "",
    /** Active library ordering; persisted across launches via [LibraryPreferences]. */
    val librarySort: LibrarySort = LibrarySort.NEWEST,
    /** Single-class filter, or null for all classes. In-memory only (reset each launch). */
    val libraryClassFilter: CharacterClass? = null,
    /** Active tag filter (lowercase keys); OR semantics. In-memory only (reset each launch). */
    val librarySelectedTags: Set<String> = emptySet(),
    /**
     * All known tags (the persisted registry ∪ tags currently on builds), display casing, A–Z. Tags
     * are first-class: a tag stays here even when no build uses it, until explicitly deleted.
     */
    val knownTags: List<String> = emptyList(),
    /** Active folder scope. In-memory only (reset each launch). */
    val libraryFolder: LibraryFolderFilter = LibraryFolderFilter.All,
    /** Whether the library groups its cards by class; persisted across launches. */
    val libraryGroupByClass: Boolean = false,
    /**
     * Saved builds re-scored under the CURRENT rules, by entry id, filled off the UI thread when the library or the compare view
     * opens ([BuildSearchModel] keeps the cache). The cards and the compare view show these numbers instead of the stored ones;
     * read through [shownEntry], which ignores a re-score made for another version of the entry. In-memory only.
     */
    val libraryRescores: Map<String, RescoredResult> = emptyMap(),
)

/** The build engine version a loaded build was computed with, when older than this app's; [savedVersion] null = not recorded. */
data class StaleEngine(
    val savedVersion: Int?,
)

/** A saved build's [stored] result and the same build re-scored under the current rules ([current]). */
data class RescoredResult(
    val stored: me.chosante.common.history.ResultSnapshot,
    val current: me.chosante.common.history.ResultSnapshot,
)

/**
 * [entry] as the library shows it: with the numbers of the current rules when its re-score is ready ([UiState.libraryRescores]),
 * else as stored. A re-score made for another version of the entry (overwritten since) is ignored.
 */
fun UiState.shownEntry(entry: HistoryEntry): HistoryEntry =
    libraryRescores[entry.id]
        ?.takeIf { it.stored == entry.result }
        ?.let { entry.copy(result = it.current) }
        ?: entry

/**
 * What one search mode keeps while another mode is on screen: its target [targets] and the [result] found under it. A result
 * belongs to the mode (and rows) that produced it — its headline number, its achieved-stat grid and its rotation are read by
 * that mode's rules, and Save / Export snapshot the mode and rows together with it — so it is parked here with them rather
 * than shown under another mode.
 */
data class ModeWorkspace(
    val targets: List<TargetRow>,
    val result: ShownResult,
)

/**
 * The part of [UiState] that describes the build the last search found — everything the paperdoll and the stats column read
 * for it — gathered so it can be parked and restored as one piece ([ModeWorkspace]).
 */
data class ShownResult(
    val phase: Phase = Phase.Idle,
    val progress: Int = 0,
    val match: BigDecimal = BigDecimal.ZERO,
    val optimal: Boolean = false,
    val maxDamageStructural: Boolean = false,
    val prefilteredRequest: Boolean = false,
    val proofState: ProofState = ProofState.Idle,
    val searchStopped: Boolean = false,
    val staleDataVersion: String? = null,
    val staleEngine: StaleEngine? = null,
    val build: BuildCombination? = null,
    val achieved: Map<Characteristic, Int> = emptyMap(),
    val spellRotation: SpellRotation? = null,
    val scenarioDamages: List<ScenarioDamage> = emptyList(),
    val zenith: ZenithState = ZenithState.Idle,
    val zenithUrl: String? = null,
) {
    /**
     * This result as it can be shown again after being parked: nothing is running for it any more, so a running proof
     * ([ProofState.Proving]) is back to the "not proven" state and a "proven within X %" badge loses its "still refining" cue.
     */
    fun atRest(): ShownResult =
        copy(
            proofState =
                when (val proof = proofState) {
                    is ProofState.Proving -> ProofState.Idle
                    is ProofState.ProvenWithin -> if (proof.refining) proof.copy(refining = false) else proof
                    else -> proof
                }
        )
}

/** The result fields of this state, as one parkable piece. */
fun UiState.shownResult(): ShownResult =
    ShownResult(
        phase = phase,
        progress = progress,
        match = match,
        optimal = optimal,
        maxDamageStructural = maxDamageStructural,
        prefilteredRequest = prefilteredRequest,
        proofState = proofState,
        searchStopped = searchStopped,
        staleDataVersion = staleDataVersion,
        staleEngine = staleEngine,
        build = build,
        achieved = achieved,
        spellRotation = spellRotation,
        scenarioDamages = scenarioDamages,
        zenith = zenith,
        zenithUrl = zenithUrl
    )

/** This state with [result] on screen in place of whatever result it shows now. */
fun UiState.withResult(result: ShownResult): UiState =
    copy(
        phase = result.phase,
        progress = result.progress,
        match = result.match,
        optimal = result.optimal,
        maxDamageStructural = result.maxDamageStructural,
        prefilteredRequest = result.prefilteredRequest,
        proofState = result.proofState,
        searchStopped = result.searchStopped,
        staleDataVersion = result.staleDataVersion,
        staleEngine = result.staleEngine,
        build = result.build,
        achieved = result.achieved,
        spellRotation = result.spellRotation,
        scenarioDamages = result.scenarioDamages,
        zenith = result.zenith,
        zenithUrl = result.zenithUrl,
        lastLandedEquipmentId = null
    )

/**
 * Stats the engine treats as internal encodings rather than final values a player reads:
 *  - `MAX_ACTION/MOVEMENT/WAKFU` — the game data stores AP/MP/WP gear modifiers here, and the engine
 *    folds them into AP/MP/WP (so a "-1 Max MP" is *already* deducted from MP — showing it again
 *    reads as a phantom second penalty);
 *  - random-element masteries/resistances — item rolls distributed onto concrete elements at scoring
 *    time, so they're already reflected in the per-element values.
 * These are hidden from the build sheet and the compare table.
 */
fun Characteristic.isEngineInternalStat(): Boolean =
    this == Characteristic.MAX_ACTION_POINT ||
        this == Characteristic.MAX_MOVEMENT_POINT ||
        this == Characteristic.MAX_WAKFU_POINTS ||
        isRandomElementStat()

/** The four elementary mastery stats. */
private val ELEMENTARY_MASTERIES =
    setOf(
        Characteristic.MASTERY_ELEMENTARY_WATER,
        Characteristic.MASTERY_ELEMENTARY_FIRE,
        Characteristic.MASTERY_ELEMENTARY_EARTH,
        Characteristic.MASTERY_ELEMENTARY_WIND
    )

/** Specialized (non-elementary) maximizable masteries — these are summed. */
private val SPECIALIZED_MASTERIES =
    setOf(
        Characteristic.MASTERY_DISTANCE,
        Characteristic.MASTERY_CRITICAL,
        Characteristic.MASTERY_BACK,
        Characteristic.MASTERY_MELEE,
        Characteristic.MASTERY_BERSERK,
        Characteristic.MASTERY_HEALING
    )

/**
 * The mastery value the **engine actually optimizes**, mirroring `FindMostMasteriesFromInputScoring`:
 * the sum of the requested *specialized* masteries **plus the MINIMUM of the requested *elementary*
 * masteries** — your weakest requested element gates hybrid damage, so the elements are never summed.
 *
 * Showing this (instead of a naive sum of all requested masteries) keeps "what you see" == "what the
 * solver maximized": a build with balanced-high fire+water no longer looks better than one the engine
 * actually ranks higher. [requestedMasteries] is the set of maximizable-mastery characteristics that
 * were requested; [achieved] is the build's resulting stats.
 */
fun engineMasteryScore(
    achieved: Map<Characteristic, Int>,
    requestedMasteries: Set<Characteristic>,
): Int {
    val specialized = requestedMasteries.filter { it in SPECIALIZED_MASTERIES }.sumOf { achieved[it] ?: 0 }
    // Specific elements win over a co-requested "all elements" — mirrors the engine's
    // TargetStats.masteryElementsToMinimize so the headline equals what the solver maximised.
    val specificElements = requestedMasteries.filter { it in ELEMENTARY_MASTERIES }
    val wantedElements =
        when {
            specificElements.isNotEmpty() -> specificElements
            Characteristic.MASTERY_ELEMENTARY in requestedMasteries -> ELEMENTARY_MASTERIES.toList()
            else -> emptyList()
        }
    val elemental = wantedElements.minOfOrNull { achieved[it] ?: 0 } ?: 0
    return specialized + elemental
}

/**
 * Cumulated mastery the build reached, as the **engine scores it** — the meaningful headline in
 * most-masteries mode (unlike precision mode, "% match" says nothing about how much mastery the build
 * reached). See [engineMasteryScore]: requested specialized masteries are summed, requested elemental
 * masteries count by their minimum (not their sum).
 */
fun UiState.requestedMasteryTotal(): Int =
    engineMasteryScore(
        achieved = achieved,
        requestedMasteries = targets.filter { it.characteristic.isMaximizableMastery() }.map { it.characteristic }.toSet()
    )

/** Compact integer formatting: a thousands separator past 1000, plain otherwise. */
fun Int.formatCompact(): String = toLong().formatCompact()

/**
 * [Long] overload so large expected-damage totals (which can exceed Int.MAX ≈ 2.1e9) format correctly
 * instead of silently wrapping to a negative number when narrowed to Int.
 */
fun Long.formatCompact(): String =
    if (this >= 1000) {
        java.text.NumberFormat
            .getIntegerInstance(java.util.Locale.US)
            .format(this)
    } else {
        toString()
    }

/**
 * A precision "% match" as the player reads it: a whole percent, capped at 100. Once a build meets every target the engine
 * keeps scoring how far it overshoots them (so the search still prefers the better of two builds that both meet them), and that
 * raw score reaches 248 or 20 330 — not a percentage anyone can read. The raw value stays in [UiState.match] and in the saved
 * entry (it is what orders builds); only what is displayed is capped.
 */
fun BigDecimal.displayedMatchPercent(): Int = commonDisplayedMatchPercent()

/** True once the match reaches 100: every requested target is met (the figure above 100 only ranks overshoot). */
fun BigDecimal.meetsAllTargets(): Boolean = commonMeetsAllTargets()

/** [displayedMatchPercent] for a saved match. */
fun Double.displayedMatchPercent(): Int = commonDisplayedMatchPercent()

/** [meetsAllTargets] for a saved match. */
fun Double.meetsAllTargets(): Boolean = commonMeetsAllTargets()

data class TargetRow(
    val id: String,
    val characteristic: Characteristic,
    val label: String,
    val glyph: String,
    val color: Color,
    val value: String,
    /**
     * Per-stat priority (#123), 1..5, for the *constraints* (the segmented bar). Flows into
     * [me.chosante.autobuilder.domain.TargetStat.userDefinedWeight], which weights the soft, penalized
     * constraint targets so the solver favours the higher-priority ones when they can't all be met.
     * Default 1 = neutral. (Priority on the maximized masteries was reverted; their weight stays 1.)
     */
    val weight: Int = 1,
)

data class ItemChip(
    val name: String,
    val rarity: Rarity,
    val matchName: String = name,
)

fun TargetRow.isExact(mode: ScoreComputationMode): Boolean = !isMaximized(mode)

fun TargetRow.isMaximized(mode: ScoreComputationMode): Boolean =
    mode == ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT &&
        characteristic.isMaximizableMastery()

fun String.onlyDigits(): String = filter { it.isDigit() }

fun Rarity.color(): Color =
    when (this) {
        Rarity.COMMON -> WRarityColor.common
        Rarity.UNCOMMON -> WRarityColor.uncommon
        Rarity.RARE -> WRarityColor.rare
        Rarity.MYTHIC -> WRarityColor.mythic
        Rarity.LEGENDARY -> WRarityColor.legendary
        Rarity.RELIC -> WRarityColor.relic
        Rarity.SOUVENIR -> WRarityColor.souvenir
        Rarity.EPIC -> WRarityColor.epic
    }

/**
 * A selectable characteristic with its UI metadata (glyph + accent). The display name itself comes
 * from the single, exhaustive [me.chosante.ui.i18n.label] source so labels never drift.
 */
data class StatDef(
    val characteristic: Characteristic,
    val glyph: String,
    val color: Color,
) {
    fun label(lang: Lang): String = characteristic.label(lang)
}

fun StatDef.toRow(value: String): TargetRow =
    TargetRow(
        id = characteristic.name,
        characteristic = characteristic,
        label = characteristic.label(Lang.EN),
        glyph = glyph,
        color = color,
        value = value
    )

fun statDefFor(characteristic: Characteristic): StatDef? = statCatalog.firstOrNull { it.characteristic == characteristic }

fun Equipment.toChip(): ItemChip =
    ItemChip(
        name = name.en.ifBlank { name.fr },
        rarity = rarity,
        matchName = name.fr
    )

/** User-facing characteristics in catalog order; excludes engine-internal random/harvest stats. */
val statCatalog: List<StatDef> =
    listOf(
        StatDef(Characteristic.ACTION_POINT, "AP", WColor.accent),
        StatDef(Characteristic.MOVEMENT_POINT, "MP", WColor.accent2),
        StatDef(Characteristic.RANGE, "◎", WColor.accent2),
        StatDef(Characteristic.WAKFU_POINT, "WP", WColor.accent2),
        StatDef(Characteristic.CRITICAL_HIT, "%", WRarityColor.legendary),
        StatDef(Characteristic.HP, "♥", WColor.danger),
        StatDef(Characteristic.MASTERY_ELEMENTARY, "El", WColor.accent),
        StatDef(Characteristic.MASTERY_ELEMENTARY_WATER, "Wa", WColor.water),
        StatDef(Characteristic.MASTERY_ELEMENTARY_FIRE, "Fi", WColor.fire),
        StatDef(Characteristic.MASTERY_ELEMENTARY_EARTH, "Ea", WColor.earth),
        StatDef(Characteristic.MASTERY_ELEMENTARY_WIND, "Ai", WColor.air),
        StatDef(Characteristic.MASTERY_DISTANCE, "◆", WColor.earth),
        StatDef(Characteristic.MASTERY_MELEE, "Me", WColor.fire),
        StatDef(Characteristic.MASTERY_CRITICAL, "✷", WRarityColor.legendary),
        StatDef(Characteristic.MASTERY_BACK, "Re", WColor.accent),
        StatDef(Characteristic.MASTERY_BERSERK, "Be", WColor.danger),
        StatDef(Characteristic.MASTERY_HEALING, "He", WColor.success),
        StatDef(Characteristic.RESISTANCE_ELEMENTARY, "rE", WColor.accent2),
        StatDef(Characteristic.RESISTANCE_ELEMENTARY_WATER, "rW", WColor.water),
        StatDef(Characteristic.RESISTANCE_ELEMENTARY_FIRE, "rF", WColor.fire),
        StatDef(Characteristic.RESISTANCE_ELEMENTARY_EARTH, "rT", WColor.earth),
        StatDef(Characteristic.RESISTANCE_ELEMENTARY_WIND, "❖", WColor.air),
        StatDef(Characteristic.RESISTANCE_CRITICAL, "rC", WRarityColor.legendary),
        StatDef(Characteristic.RESISTANCE_BACK, "rB", WColor.accent2),
        StatDef(Characteristic.CONTROL, "Co", WColor.muted),
        StatDef(Characteristic.WISDOM, "Ws", WColor.success),
        StatDef(Characteristic.PROSPECTION, "Pp", WColor.warning),
        StatDef(Characteristic.INITIATIVE, "In", WColor.muted),
        StatDef(Characteristic.DODGE, "➶", WColor.muted),
        StatDef(Characteristic.LOCK, "Lk", WColor.muted),
        StatDef(Characteristic.WILLPOWER, "Wl", WColor.accent),
        StatDef(Characteristic.BLOCK_PERCENTAGE, "Bl", WColor.muted)
    )

/** Accent color for a stat's element dot — reuses the catalog color, else derives one by family. */
fun Characteristic.statColor(): Color =
    statDefFor(this)?.color ?: when {
        name.contains("WATER") -> WColor.water
        name.contains("FIRE") -> WColor.fire
        name.contains("EARTH") -> WColor.earth
        name.contains("WIND") -> WColor.air
        name.startsWith("MASTERY") -> WColor.accent
        name.startsWith("RESISTANCE") -> WColor.accent2
        name.startsWith("MAX_ACTION") -> WColor.accent
        name.startsWith("MAX_MOVEMENT") -> WColor.accent2
        name.startsWith("MAX_WAKFU") -> WColor.accent2
        else -> WColor.muted
    }

private val defaultTargetValues =
    listOf(
        Characteristic.ACTION_POINT to "11",
        Characteristic.MOVEMENT_POINT to "4",
        Characteristic.RANGE to "4",
        Characteristic.CRITICAL_HIT to "25",
        Characteristic.MASTERY_DISTANCE to "1",
        Characteristic.HP to "2000",
        Characteristic.RESISTANCE_ELEMENTARY_WIND to "0",
        Characteristic.DODGE to "0"
    )

fun defaultTargets(): List<TargetRow> = defaultTargetValues.mapNotNull { (characteristic, value) -> statDefFor(characteristic)?.toRow(value) }
