package me.chosante.ui.history

import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.autobuilder.genetic.wakfu.ScoreComputationMode
import me.chosante.autobuilder.genetic.wakfu.isMaximizableMastery
import me.chosante.common.CharacterClass
import me.chosante.common.history.HistoryEntry
import me.chosante.common.history.ItemRef
import me.chosante.common.history.RequestSnapshot
import me.chosante.common.history.ResultSnapshot
import me.chosante.common.history.TargetSnapshot
import me.chosante.common.skills.CharacterSkills
import me.chosante.ui.i18n.Lang
import me.chosante.ui.i18n.label
import me.chosante.ui.state.ItemChip
import me.chosante.ui.state.TargetRow
import me.chosante.ui.state.UiState
import me.chosante.ui.state.engineMasteryScore
import me.chosante.ui.state.statDefFor
import me.chosante.ui.state.toRow

// Mappers between the live UiState / engine objects and the persisted HistoryEntry DTO. Kept in one
// place so the (de)serialization rules — and the deliberate decoupling from the engine graph — live
// next to the format they serve.

/**
 * Snapshots the current workspace into a savable [HistoryEntry]. Returns `null` when there is no
 * discovered build to save (a saved build without a result would be meaningless).
 */
fun UiState.toHistoryEntry(
    id: String,
    name: String,
    note: String?,
    createdAt: Long,
    dataVersion: String,
    tags: List<String> = emptyList(),
    folder: String? = null,
): HistoryEntry? {
    val build = this.build ?: return null
    return HistoryEntry(
        id = id,
        name = name,
        createdAt = createdAt,
        note = note?.takeIf { it.isNotBlank() },
        dataVersion = dataVersion,
        request =
            RequestSnapshot(
                clazz = clazz.name,
                level = level,
                minLevel = minLevel,
                mode = mode.name,
                maxRarity = maxRarity,
                duration = duration,
                stopAtMatch = stopAtMatch,
                targets = targets.map { TargetSnapshot(it.characteristic, it.value, it.weight) },
                forcedItems = forcedItems.map { ItemRef(it.name, it.rarity, it.matchName) },
                excludedItems = excludedItems.map { ItemRef(it.name, it.rarity, it.matchName) }
            ),
        result =
            ResultSnapshot(
                equipments = build.equipments,
                skills = build.characterSkills.toFlatMap(),
                achieved = achieved,
                match = match.toDouble(),
                optimal = optimal
            ),
        zenithUrl = zenithUrl,
        tags = tags,
        folder = folder
    )
}

/** Trim, drop blanks, dedupe case-insensitively keeping the first-seen casing for display. */
fun normalizeTags(raw: List<String>): List<String> {
    val seen = HashSet<String>()
    return raw
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .filter { seen.add(it.lowercase()) }
}

/** Flattens a skill allocation to `skill name → points` for storage. */
fun CharacterSkills.toFlatMap(): Map<String, Int> = allCharacteristic.associate { it.name to it.pointsAssigned }

/**
 * Rebuilds a [CharacterSkills] for [level] from a stored flat allocation. Best-effort: a stored name
 * that no longer matches any skill (e.g. after a data change) is simply ignored, and out-of-range
 * point values are skipped rather than throwing. `PairedCharacteristic.setPointAssigned` cascades to
 * its two children, so paired skills round-trip from their single top-level name.
 */
fun reconstructSkills(
    level: Int,
    flat: Map<String, Int>,
): CharacterSkills {
    val skills = CharacterSkills(level)
    val branches = listOf(skills.intelligence, skills.strength, skills.agility, skills.luck, skills.major)
    branches.forEach { branch ->
        branch.getCharacteristics().forEach { characteristic ->
            val points = flat[characteristic.name] ?: return@forEach
            runCatching { characteristic.setPointAssigned(points) }
        }
    }
    return skills
}

/** Reconstructs the discovered build (equipment + skills) for display when loading an entry. */
fun HistoryEntry.toBuildCombination(): BuildCombination =
    BuildCombination(
        equipments = result.equipments,
        characterSkills = reconstructSkills(request.level, result.skills)
    )

/** Restores the saved target list into displayable [TargetRow]s (catalog-backed, like defaults). */
fun HistoryEntry.toTargetRows(): List<TargetRow> = request.targets.mapNotNull { statDefFor(it.characteristic)?.toRow(it.value)?.copy(weight = it.weight) }

fun HistoryEntry.toForcedChips(): List<ItemChip> = request.forcedItems.map { ItemChip(it.name, it.rarity, it.matchName) }

fun HistoryEntry.toExcludedChips(): List<ItemChip> = request.excludedItems.map { ItemChip(it.name, it.rarity, it.matchName) }

fun HistoryEntry.restoredClass(): CharacterClass = CharacterClass.fromValue(request.clazz)

fun HistoryEntry.restoredMode(): ScoreComputationMode =
    runCatching { ScoreComputationMode.valueOf(request.mode) }.getOrDefault(ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT)

/** True if the build was searched in most-masteries mode (where "% match" is not the headline number). */
fun HistoryEntry.isMasteryMode(): Boolean = restoredMode() == ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT

/**
 * Mastery the build reached, **as the engine scores it** (see [engineMasteryScore]) — the headline for
 * most-masteries builds. Requested specialized masteries are summed; requested elemental masteries
 * count by their minimum, not their sum, so the number matches what the solver optimized.
 */
fun HistoryEntry.requestedMasteryTotal(): Int =
    engineMasteryScore(
        achieved = result.achieved,
        requestedMasteries =
            request.targets
                .filter { it.characteristic.isMaximizableMastery() }
                .map { it.characteristic }
                .toSet()
    )

/** Class display name, e.g. `Cra`. */
fun HistoryEntry.classDisplayName(): String = request.clazz.lowercase().replaceFirstChar { it.titlecase() }

/**
 * A sensible pre-filled name for the save dialog, e.g. `Cra 110 · Distance` — class, level, and the
 * build's focus (first maximized mastery, else first mastery target). The user can edit it.
 */
fun UiState.suggestedBuildName(): String {
    val cls = clazz.name.lowercase().replaceFirstChar { it.titlecase() }
    val focus =
        targets.firstOrNull { it.characteristic.isMaximizableMastery() }?.characteristic
            ?: targets.firstOrNull { it.characteristic.name.startsWith("MASTERY") }?.characteristic
    val focusLabel = focus?.label(Lang.EN)?.removeSuffix(" Mastery")
    return buildString {
        append(cls)
        append(' ')
        append(level)
        if (focusLabel != null) {
            append(" · ")
            append(focusLabel)
        }
    }
}
