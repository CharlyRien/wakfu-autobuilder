package me.chosante.ui.state

import me.chosante.autobuilder.domain.PassiveCatalog
import me.chosante.autobuilder.genetic.wakfu.WakfuBestBuildFinderAlgorithm
import me.chosante.common.CharacterClass
import me.chosante.common.Rarity
import me.chosante.common.history.RequestSnapshot
import me.chosante.common.workspace.WorkspaceSnapshot
import me.chosante.ui.history.restoredBossDifficulty
import me.chosante.ui.history.restoredBossElement
import me.chosante.ui.history.restoredClass
import me.chosante.ui.history.restoredMode
import me.chosante.ui.history.restoredScenario
import me.chosante.ui.history.toExcludedChips
import me.chosante.ui.history.toForcedChips
import me.chosante.ui.history.toRequestSnapshot
import me.chosante.ui.history.toTargetRows

// The remembered workspace ([WorkspaceStore]): what of the UiState survives a relaunch, and how it comes back. Only the
// request is remembered — never a result, a running search or the screen the user was on.

/** The request on screen, as the remembered workspace stores it (a selected boss is kept in any mode). */
fun UiState.toWorkspaceSnapshot(dataVersion: String): WorkspaceSnapshot = WorkspaceSnapshot(dataVersion = dataVersion, request = toRequestSnapshot(keepBossInAnyMode = true))

/**
 * This state with the remembered [request] in place of its own request fields — the inverse of [toRequestSnapshot], with
 * the same safe fallbacks as a saved build's reload (unknown enum names fall back to the defaults). Nothing else is touched:
 * the result, the library and the app options stay as they are. Out-of-range numbers (a hand-edited file) are clamped the
 * way the request panel's setters clamp them, and an "every rarity excluded" set — which no search accepts — is dropped.
 */
fun UiState.withRememberedRequest(request: RequestSnapshot): UiState {
    val level = request.level.coerceIn(1, 245)
    return copy(
        clazz = request.restoredClass(),
        level = level,
        minLevel = request.minLevel.coerceIn(0, 245),
        mode = request.restoredMode(),
        modeWorkspaces = emptyMap(),
        scenario = request.restoredScenario(),
        selectedBoss = request.boss?.monster,
        bossElement = request.restoredBossElement(),
        bossDifficulty = request.restoredBossDifficulty(),
        targets = request.toTargetRows(),
        maxRarity = request.maxRarity,
        excludedRarities = request.excludedRarities.takeIf { it.size < Rarity.entries.size }.orEmpty(),
        duration = request.duration.filter(Char::isDigit).take(3),
        stopAtMatch = request.stopAtMatch,
        forcedItems = request.toForcedChips(),
        excludedItems = request.toExcludedChips(),
        useSublimations = request.useSublimations,
        maxSublimationTier = request.maxSublimationTier,
        forcedSublimations = request.forcedSublimations,
        forcedSublimationLevels = request.forcedSublimationLevels,
        excludedSublimations = request.excludedSublimations,
        forcedPassives = request.forcedPassives,
        forcedRunesByItem = request.forcedRunesByItem
    )
}

/**
 * What the game data this app ships knows, for checking a remembered request against it ([withoutUnknownEntries]). Built
 * from the embedded catalogs by [fromGameData]; tests pass their own.
 */
class WorkspaceCatalog(
    /** French item names (the engine's match key for forced / excluded items and pinned runes). */
    private val itemNames: Set<String>,
    /** Sublimation names, lowercase, in French and English. */
    private val sublimationNames: Set<String>,
    private val runeIds: Set<Int>,
    private val passiveExists: (CharacterClass, String) -> Boolean,
    /** Maps a sublimation name renamed by a game update to its current name (see [WakfuBestBuildFinderAlgorithm.canonicalSublimationName]). */
    private val canonicalSublimation: (String) -> String = { it },
) {
    fun hasItem(frenchName: String): Boolean = frenchName in itemNames

    fun canonicalSublimationName(name: String): String = canonicalSublimation(name)

    fun hasSublimation(name: String): Boolean = name.trim().lowercase() in sublimationNames

    fun hasRune(id: Int): Boolean = id in runeIds

    fun hasPassive(
        clazz: CharacterClass,
        name: String,
    ): Boolean = passiveExists(clazz, name)

    companion object {
        /** The embedded game data. Parses the equipment catalog on first use: call it off the UI thread. */
        fun fromGameData(): WorkspaceCatalog =
            WorkspaceCatalog(
                itemNames = WakfuBestBuildFinderAlgorithm.equipments.mapTo(HashSet()) { it.name.fr },
                sublimationNames =
                    WakfuBestBuildFinderAlgorithm.sublimations
                        .flatMap { listOf(it.name.fr, it.name.en) }
                        .mapTo(HashSet()) { it.trim().lowercase() },
                runeIds = WakfuBestBuildFinderAlgorithm.runes.mapTo(HashSet()) { it.id },
                passiveExists = { clazz, name -> PassiveCatalog.findByName(clazz, name) != null },
                canonicalSublimation = WakfuBestBuildFinderAlgorithm::canonicalSublimationName
            )
    }
}

/**
 * This state without the request entries the [catalog] no longer knows — forced / excluded items, forced / excluded
 * sublimations, forced passives, pinned runes (and the pins of an item that is gone) — paired with how many were dropped. A
 * remembered request can outlive its game data: an update can remove or rename them, and the engine would silently ignore
 * a name it cannot match. Renamed sublimations are carried to their current name rather than dropped. A filter, so it is
 * safe to apply to a request the user has edited since it was restored: nothing the user just picked can be unknown.
 */
fun UiState.withoutUnknownEntries(catalog: WorkspaceCatalog): Pair<UiState, Int> {
    var dropped = 0

    fun <T> List<T>.known(keep: (T) -> Boolean): List<T> = filter(keep).also { dropped += size - it.size }

    fun List<String>.knownSublimations(): List<String> =
        map(catalog::canonicalSublimationName)
            .distinct()
            .known(catalog::hasSublimation)

    val runes =
        forcedRunesByItem
            .filterKeys { item -> catalog.hasItem(item).also { if (!it) dropped++ } }
            .mapValues { (_, ids) -> ids.known(catalog::hasRune) }
            .filterValues { it.isNotEmpty() }
    val cleaned =
        copy(
            forcedItems = forcedItems.known { catalog.hasItem(it.matchName) },
            excludedItems = excludedItems.known { catalog.hasItem(it.matchName) },
            forcedSublimations = forcedSublimations.knownSublimations(),
            forcedSublimationLevels = forcedSublimationLevels.mapKeys { catalog.canonicalSublimationName(it.key) }.filterKeys { catalog.hasSublimation(it) },
            excludedSublimations = excludedSublimations.knownSublimations(),
            forcedPassives = forcedPassives.known { catalog.hasPassive(clazz, it) },
            forcedRunesByItem = runes
        )
    return cleaned to dropped
}
