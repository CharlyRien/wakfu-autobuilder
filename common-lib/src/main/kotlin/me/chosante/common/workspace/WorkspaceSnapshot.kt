package me.chosante.common.workspace

import kotlinx.serialization.Serializable
import me.chosante.common.history.RequestSnapshot

/**
 * The persisted, on-disk shape of the REQUEST the user is editing, so the workspace survives a relaunch. It reuses the saved
 * builds' [RequestSnapshot] (the same stable identifiers, the same defaults for absent keys) and deliberately carries NO
 * result and no search state: a running search is never resumed and results live in "My Builds".
 *
 * Only the mode on screen is remembered, with its own target rows: the rows another mode parked during the session (switching
 * modes keeps each mode's rows) are not, so the other modes start from their first-visit rows after a relaunch.
 *
 * [formatVersion] guards the shape as a whole: a file written with any other version is ignored (the app starts from the
 * defaults) rather than migrated, since losing a draft request is cheap and a half-understood one is not.
 */
@Serializable
data class WorkspaceSnapshot(
    val formatVersion: Int = CURRENT_FORMAT_VERSION,
    /** The game-data version the request was written with (diagnostics; stale names are checked against the catalog anyway). */
    val dataVersion: String,
    val request: RequestSnapshot,
) {
    companion object {
        const val CURRENT_FORMAT_VERSION = 1
    }
}
