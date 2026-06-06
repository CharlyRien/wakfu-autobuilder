package me.chosante.ui.state

import androidx.compose.ui.graphics.Color
import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.autobuilder.genetic.wakfu.ScoreComputationMode
import me.chosante.autobuilder.genetic.wakfu.WakfuSolver
import me.chosante.autobuilder.genetic.wakfu.isMaximizableMastery
import me.chosante.common.CharacterClass
import me.chosante.common.Characteristic
import me.chosante.common.Equipment
import me.chosante.common.Rarity
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

sealed interface Modal {
    data object AddStat : Modal

    data class ItemPicker(
        val mode: PickerMode,
    ) : Modal
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
)

/**
 * Cumulated value of the masteries the user asked to maximize (most-masteries mode). This is the
 * meaningful headline number there — unlike precision mode, "% match" says nothing about how much
 * mastery the build actually reached. Sums the achieved value of every maximizable-mastery target;
 * the "all elements" target reads through the aggregate [Characteristic.MASTERY_ELEMENTARY] key
 * (set to the weakest element by the scorer), so it counts once, not four times.
 */
fun UiState.requestedMasteryTotal(): Int =
    targets
        .filter { it.characteristic.isMaximizableMastery() }
        .sumOf { achieved[it.characteristic] ?: 0 }

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
