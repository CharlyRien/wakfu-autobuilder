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

    /**
     * The save comes from NEWER game data than this app ships (imported from a newer app, or saved before a downgrade): nothing
     * says a search here would do better, so reason (A) then only states the fact.
     */
    val savedWithNewerData: Boolean get() = savedDataVersion?.let { compareVersions(it, currentDataVersion) > 0 } == true

    /** One plain-words sentence per reason, (A) first, each ending with the suggestion to re-run the search. */
    fun reasons(lang: Lang): List<String> =
        buildList {
            savedDataVersion?.let { saved ->
                if (savedWithNewerData) {
                    add(Tr.OBSOLETE_DATA_OTHER.value(lang).format(versionOf(saved, currentDataVersion)))
                } else {
                    add(Tr.OBSOLETE_DATA_REASON.value(lang).format(versionChange(saved, currentDataVersion)))
                }
            }
            if (engineImproved) add(Tr.OBSOLETE_ENGINE_REASON.value(lang))
        }
}

/** Orders two dotted game-data versions numerically ("1.100" is after "1.93"); a non-numeric part counts as 0. */
internal fun compareVersions(
    a: String,
    b: String,
): Int {
    val left = a.split('.').map { it.toIntOrNull() ?: 0 }
    val right = b.split('.').map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(left.size, right.size)) {
        val diff = left.getOrElse(i) { 0 }.compareTo(right.getOrElse(i) { 0 })
        if (diff != 0) return diff
    }
    return 0
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
 * True when this save's stored proof ("optimal proven") was made by an older engine than this app's (reason B): the engine got a
 * fix since, so the proof holds for that version's rules only. The cards keep showing it, dimmed, with a tooltip saying so.
 */
fun HistoryEntry.provenByOlderEngine(currentEngineVersion: Int = ENGINE_RESULTS_VERSION): Boolean = engineResultsVersion.let { it == null || it < currentEngineVersion }

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
): String = "${versionOf(saved, current)} → ${versionOf(current, saved)}"

/** [version] cut to its major.minor when that is enough to tell it from [other], in full otherwise. */
internal fun versionOf(
    version: String,
    other: String,
): String {
    fun short(v: String) = v.split('.').take(2).joinToString(".")
    return if (short(version) != short(other)) short(version) else version
}
