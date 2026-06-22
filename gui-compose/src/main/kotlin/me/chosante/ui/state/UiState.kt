package me.chosante.ui.state

import androidx.compose.ui.graphics.Color
import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.autobuilder.domain.DamageScenario
import me.chosante.autobuilder.domain.SpellElement
import me.chosante.autobuilder.domain.SpellRotation
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

/** The two sides of the compare view. */
enum class CompareSlot {
    A,
    B,
}

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

    /** Pick a sublimation to force, by translated title + effect text — a centered modal like the item picker. */
    data object SublimationPicker : Modal

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
    val maxRarity: Rarity = Rarity.EPIC,
    /** Rarities the user toggled off; excluded from the search. At least one rarity always stays allowed. */
    val excludedRarities: Set<Rarity> = emptySet(),
    val duration: String = "20",
    val stopAtMatch: Boolean = false,
    val forcedItems: List<ItemChip> = emptyList(),
    val excludedItems: List<ItemChip> = emptyList(),
    /** When true (default), the solver may pick statically-modelable sublimations. */
    val useSublimations: Boolean = true,
    /** Sublimations the user forces into the build (French names; incl. combat-conditional ones). */
    val forcedSublimations: List<String> = emptyList(),
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
     * which "not proven" hint the stats panel shows. See [GeneticAlgorithmResult.maxDamageHeuristicPhases].
     */
    val maxDamageStructural: Boolean = false,
    val build: BuildCombination? = null,
    val achieved: Map<Characteristic, Int> = emptyMap(),
    /** Best spells to cast for the build's AP, in max-damage mode only (else null). Computed off-thread. */
    val spellRotation: SpellRotation? = null,
    val lastLandedEquipmentId: Int? = null,
    val zenith: ZenithState = ZenithState.Idle,
    val zenithUrl: String? = null,
    val toast: String? = null,
    val error: String? = null,
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
    /** The two builds pinned for the side-by-side compare view (entry ids), A then B. */
    val compareA: String? = null,
    val compareB: String? = null,
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
