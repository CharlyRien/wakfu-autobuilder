package me.chosante.ui.history

import kotlinx.serialization.json.Json
import me.chosante.autobuilder.domain.BuildCombination
import me.chosante.autobuilder.domain.DamageScenario
import me.chosante.autobuilder.domain.Orientation
import me.chosante.autobuilder.domain.RangeBand
import me.chosante.autobuilder.domain.SpellElement
import me.chosante.autobuilder.genetic.wakfu.ScoreComputationMode
import me.chosante.autobuilder.genetic.wakfu.isMaximizableMastery
import me.chosante.common.CharacterClass
import me.chosante.common.history.DamageScenarioSnapshot
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
 * The single JSON codec for [HistoryEntry] — shared by the on-disk library
 * ([HistoryRepository]) and the clipboard export/import (see `BuildSearchModel.exportBuild` /
 * `importBuild`) so the two never drift. `prettyPrint` keeps an exported build readable when pasted
 * into a chat; `ignoreUnknownKeys` lets a build exported by a newer/older app version still load.
 */
internal val historyJson: Json =
    Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

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
                excludedItems = excludedItems.map { ItemRef(it.name, it.rarity, it.matchName) },
                scenario = scenario.toSnapshot()
            ),
        result =
            ResultSnapshot(
                equipments = build.equipments,
                skills = build.characterSkills.toFlatMap(),
                achieved = achieved,
                match = match.toDouble(),
                optimal = optimal,
                runes = build.runes.entries.associate { (equip, runes) -> equip.equipmentId to runes }
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

/** Reconstructs the discovered build (equipment + skills + socketed runes) for display when loading an entry. */
fun HistoryEntry.toBuildCombination(): BuildCombination {
    val equipmentById = result.equipments.associateBy { it.equipmentId }
    val runes =
        result.runes
            .mapNotNull { (equipmentId, runeList) -> equipmentById[equipmentId]?.let { it to runeList } }
            .toMap()
    return BuildCombination(
        equipments = result.equipments,
        characterSkills = reconstructSkills(request.level, result.skills),
        runes = runes
    )
}

/** Restores the saved target list into displayable [TargetRow]s (catalog-backed, like defaults). */
fun HistoryEntry.toTargetRows(): List<TargetRow> = request.targets.mapNotNull { statDefFor(it.characteristic)?.toRow(it.value)?.copy(weight = it.weight) }

fun HistoryEntry.toForcedChips(): List<ItemChip> = request.forcedItems.map { ItemChip(it.name, it.rarity, it.matchName) }

fun HistoryEntry.toExcludedChips(): List<ItemChip> = request.excludedItems.map { ItemChip(it.name, it.rarity, it.matchName) }

fun HistoryEntry.restoredClass(): CharacterClass = CharacterClass.fromValue(request.clazz)

fun HistoryEntry.restoredMode(): ScoreComputationMode =
    runCatching { ScoreComputationMode.valueOf(request.mode) }.getOrDefault(ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT)

/** Persists the live attack scenario as primitives for storage (enum → name). */
fun DamageScenario.toSnapshot(): DamageScenarioSnapshot =
    DamageScenarioSnapshot(
        element = element.name,
        rangeBand = rangeBand.name,
        orientation = orientation.name,
        berserk = berserk,
        healing = healing,
        critCapPercent = critCapPercent,
        targetResistancePercent = targetResistancePercent,
        baseDamage = baseDamage,
        elementResistances = elementResistances?.mapKeys { it.key.name }
    )

/**
 * Restores the saved attack scenario back into the live [DamageScenario]. Each enum is resolved with a
 * safe fallback to the engine default (mirroring [restoredMode]), so an unknown name from a future/older
 * save — or a pre-feature save defaulting every field — never throws.
 */
fun HistoryEntry.restoredScenario(): DamageScenario {
    val default = DamageScenario()
    val snapshot = request.scenario
    return DamageScenario(
        element = runCatching { SpellElement.valueOf(snapshot.element) }.getOrDefault(default.element),
        rangeBand = runCatching { RangeBand.valueOf(snapshot.rangeBand) }.getOrDefault(default.rangeBand),
        orientation = runCatching { Orientation.valueOf(snapshot.orientation) }.getOrDefault(default.orientation),
        berserk = snapshot.berserk,
        healing = snapshot.healing,
        critCapPercent = snapshot.critCapPercent,
        targetResistancePercent = snapshot.targetResistancePercent,
        baseDamage = snapshot.baseDamage,
        elementResistances =
            snapshot.elementResistances
                ?.mapNotNull { (name, res) -> runCatching { SpellElement.valueOf(name) }.getOrNull()?.let { it to res } }
                ?.toMap()
                ?.takeIf { it.isNotEmpty() }
    )
}

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
