package me.chosante.ui.history

import me.chosante.autobuilder.domain.ENGINE_RESULTS_VERSION
import me.chosante.autobuilder.genetic.wakfu.WakfuBestBuildFinderAlgorithm
import me.chosante.common.history.HistoryEntry
import me.chosante.ui.i18n.Lang
import me.chosante.ui.i18n.Tr
import me.chosante.ui.state.UiState

/**
 * Why a SAVED build may no longer be the best a search would find: (A) the game data was updated since it was saved
 * ([savedDataVersion] → [currentDataVersion]: new items, sublimations or runes), and/or (B) the engine got a fix that can
 * improve results ([engineImproved]: its stored `ENGINE_RESULTS_VERSION` is lower than the app's, or was not recorded). At
 * least one of the two holds — a current build has no [Obsolescence] at all (see [obsolescenceOf]).
 */
data class Obsolescence(
    /** The game-data version the build was saved with, when it is not the current one (reason A); null otherwise. */
    val savedDataVersion: String?,
    val currentDataVersion: String,
    /** Reason B: the build was computed by an older engine than this app's. */
    val engineImproved: Boolean,
) {
    val dataUpdated: Boolean get() = savedDataVersion != null

    /** One plain-words sentence per reason, (A) first, each ending with the suggestion to re-run the search. */
    fun reasons(lang: Lang): List<String> =
        buildList {
            savedDataVersion?.let { add(Tr.OBSOLETE_DATA_REASON.value(lang).format(versionChange(it, currentDataVersion))) }
            if (engineImproved) add(Tr.OBSOLETE_ENGINE_REASON.value(lang))
        }
}

/**
 * The obsolescence of a build saved with [savedDataVersion] and [savedEngineVersion] (null = saved before the engine version
 * was recorded, which counts as older), seen from an app shipping [currentDataVersion] and [currentEngineVersion]; null when
 * neither reason applies.
 */
fun obsolescenceOf(
    savedDataVersion: String,
    savedEngineVersion: Int?,
    currentDataVersion: String = WakfuBestBuildFinderAlgorithm.dataVersion,
    currentEngineVersion: Int = ENGINE_RESULTS_VERSION,
): Obsolescence? {
    val dataUpdated = savedDataVersion != currentDataVersion
    val engineImproved = savedEngineVersion == null || savedEngineVersion < currentEngineVersion
    if (!dataUpdated && !engineImproved) return null
    return Obsolescence(
        savedDataVersion = savedDataVersion.takeIf { dataUpdated },
        currentDataVersion = currentDataVersion,
        engineImproved = engineImproved
    )
}

/** Whether this saved build is obsolete, and why. See [obsolescenceOf]. */
fun HistoryEntry.obsolescence(
    currentDataVersion: String = WakfuBestBuildFinderAlgorithm.dataVersion,
    currentEngineVersion: Int = ENGINE_RESULTS_VERSION,
): Obsolescence? = obsolescenceOf(dataVersion, engineResultsVersion, currentDataVersion, currentEngineVersion)

/**
 * Whether the build on screen is an obsolete saved build, and why — read from the stamps a loaded build carries
 * ([UiState.staleDataVersion], [UiState.staleEngine]); null for a build found by this app (or no build).
 */
fun UiState.obsolescence(currentDataVersion: String = WakfuBestBuildFinderAlgorithm.dataVersion): Obsolescence? {
    if (build == null) return null
    val stale = staleEngine
    if (staleDataVersion == null && stale == null) return null
    return Obsolescence(savedDataVersion = staleDataVersion, currentDataVersion = currentDataVersion, engineImproved = stale != null)
}

/**
 * "1.92 → 1.93": the two versions cut to their major.minor when that is enough to tell them apart (the game's own way of
 * naming an update), in full otherwise ("1.92.1.58 → 1.92.2.60").
 */
internal fun versionChange(
    saved: String,
    current: String,
): String {
    fun short(version: String) = version.split('.').take(2).joinToString(".")
    val (from, to) = if (short(saved) != short(current)) short(saved) to short(current) else saved to current
    return "$from → $to"
}
