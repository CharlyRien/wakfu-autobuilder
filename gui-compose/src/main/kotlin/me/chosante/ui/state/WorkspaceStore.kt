package me.chosante.ui.state

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import me.chosante.common.workspace.WorkspaceSnapshot
import me.chosante.ui.history.appDataDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/**
 * Remembers the request the user is editing between launches, as one `workspace.json` in the app data directory — next to the
 * saved builds' `history/` (see [appDataDir]). A file rather than a [LibraryPreferences] key: a request with a boss and a few
 * dozen chips can outgrow a Preferences value (8 KB max), and a file can be replaced atomically.
 *
 * Every failure is silent: a missing, unreadable, corrupt or other-format file reads as `null` (the app starts from the
 * defaults), and a failed write is dropped (the next change writes again). Writes go to a temp file that is then moved into
 * place, so a crash mid-write leaves the previous file whole. Blocking IO runs on [ioDispatcher]; [saveBlocking] is the one
 * exception, for the window's close request, where there is no later moment to write in.
 */
open class WorkspaceStore(
    baseDir: Path = appDataDir(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val file: Path = baseDir.resolve(FILE_NAME)

    open suspend fun load(): WorkspaceSnapshot? = withContext(ioDispatcher) { read() }

    suspend fun save(snapshot: WorkspaceSnapshot) {
        withContext(ioDispatcher) { saveBlocking(snapshot) }
    }

    /** Reads the remembered workspace, or `null` when there is none this app can use. Never throws. */
    fun read(): WorkspaceSnapshot? =
        runCatching {
            if (!file.isRegularFile()) return null
            json
                .decodeFromString(WorkspaceSnapshot.serializer(), file.readText())
                .takeIf { it.formatVersion == WorkspaceSnapshot.CURRENT_FORMAT_VERSION }
        }.getOrNull()

    /** Writes [snapshot] atomically on the calling thread. Never throws. */
    @Synchronized
    open fun saveBlocking(snapshot: WorkspaceSnapshot) {
        runCatching {
            file.parent.createDirectories()
            // One temp name per process (writes within it are serialized): a crash mid-write leaves at most this one file, replaced
            // by the next write, and two app instances never move each other's half-written file into place.
            val temp = file.resolveSibling("$FILE_NAME.${ProcessHandle.current().pid()}.tmp")
            try {
                Files.writeString(temp, encode(snapshot))
                runCatching {
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                }.getOrElse {
                    // Some filesystems reject ATOMIC_MOVE; a plain replace is still far safer than writing the target in place.
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                temp.deleteIfExists()
            }
        }
    }

    /** Exposed for tests. */
    fun file(): Path = file

    companion object {
        private const val FILE_NAME = "workspace.json"

        // Same rules as the saved builds' codec: absent keys take their defaults, unknown keys are ignored.
        private val json =
            Json {
                prettyPrint = true
                ignoreUnknownKeys = true
                encodeDefaults = true
            }

        /** The file's content for [snapshot]. */
        internal fun encode(snapshot: WorkspaceSnapshot): String = json.encodeToString(WorkspaceSnapshot.serializer(), snapshot)
    }
}
