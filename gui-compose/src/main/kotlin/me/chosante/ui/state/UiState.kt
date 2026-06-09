package me.chosante.ui.state

import androidx.compose.ui.graphics.Color
import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.autobuilder.genetic.wakfu.ScoreComputationMode
import me.chosante.autobuilder.genetic.wakfu.WakfuSolver
import me.chosante.autobuilder.genetic.wakfu.isMaximizableMastery
import me.chosante.autobuilder.genetic.wakfu.isRandomElementStat
import me.chosante.common.CharacterClass
import me.chosante.common.Characteristic
import me.chosante.common.Equipment
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

sealed interface Modal {
    data object AddStat : Modal

    data class ItemPicker(
        val mode: PickerMode,
    ) : Modal

    /** Save-the-current-build dialog (name + optional note). */
    data object SaveBuild : Modal

    /** Rename an existing saved build. */
    data class RenameBuild(
        val id: String,
        val currentName: String,
    ) : Modal

    /** Confirm deleting a saved build. */
    data class ConfirmDelete(
        val id: String,
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
    val minLevel: Int = 80,
    val mode: ScoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT,
    val solver: WakfuSolver = WakfuSolver.OR_TOOLS,
    val targets: List<TargetRow> = defaultTargets(),
    val maxRarity: Rarity = Rarity.EPIC,
    /** Rarities the user toggled off; excluded from the search. At least one rarity always stays allowed. */
    val excludedRarities: Set<Rarity> = emptySet(),
    val duration: String = "20",
    val stopAtMatch: Boolean = false,
    val forcedItems: List<ItemChip> = emptyList(),
    val excludedItems: List<ItemChip> = emptyList(),
    val phase: Phase = Phase.Idle,
    val progress: Int = 0,
    val match: BigDecimal = BigDecimal.ZERO,
    val optimal: Boolean = false,
    val build: BuildCombination? = null,
    val achieved: Map<Characteristic, Int> = emptyMap(),
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
fun Int.formatCompact(): String =
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
        StatDef(Characteristic.MASTERY_CRITICAL, "✷", WRarityColor.legendary),
        StatDef(Characteristic.MASTERY_MELEE, "Me", WColor.fire),
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
