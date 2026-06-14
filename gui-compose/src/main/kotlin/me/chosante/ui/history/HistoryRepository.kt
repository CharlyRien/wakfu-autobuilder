package me.chosante.ui.history

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.chosante.common.history.HistoryEntry
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText

/**
 * Resolves the per-user, per-OS application data directory. We never write next to the jar — under
 * a Conveyor install that location is unpredictable and may be read-only. The conventional spots:
 *  - macOS:   `~/Library/Application Support/WakfuAutobuilder`
 *  - Windows: `%APPDATA%\WakfuAutobuilder`
 *  - Linux:   `${XDG_DATA_HOME:-~/.local/share}/wakfu-autobuilder`
 *
 * Hand-rolled (no dependency) — the JDK has no "app data dir" API.
 */
internal fun appDataDir(
    osName: String = System.getProperty("os.name").orEmpty(),
    env: (String) -> String? = System::getenv,
    userHome: String = System.getProperty("user.home").orEmpty(),
): Path {
    val os = osName.lowercase(Locale.ROOT)
    val home = Path.of(userHome)
    return when {
        os.contains("mac") || os.contains("darwin") ->
            home.resolve("Library").resolve("Application Support").resolve("WakfuAutobuilder")

        os.contains("win") ->
            (env("APPDATA")?.let(Path::of) ?: home.resolve("AppData").resolve("Roaming"))
                .resolve("WakfuAutobuilder")

        else ->
            (env("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }?.let(Path::of) ?: home.resolve(".local").resolve("share"))
                .resolve("wakfu-autobuilder")
    }
}

/**
 * Stores saved builds as **one JSON file per build** under `<appData>/history/`. One-file-per-build
 * (rather than a single big file) means a torn write can lose at most one build, and individual
 * export/share stays trivial. Writes are atomic (temp file + atomic move). All blocking IO runs on
 * [ioDispatcher] — never the Compose UI (Swing/EDT) thread, the rule the rest of the app follows.
 */
class HistoryRepository(
    baseDir: Path = appDataDir(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val historyDir: Path = baseDir.resolve("history")

    // Reuses the shared [historyJson] codec (see HistoryMapping) so the on-disk format and the
    // clipboard export/import stay byte-for-byte compatible.
    private val json = historyJson

    /**
     * Loads every saved build, newest first. Corrupt or unreadable files are skipped (never throw)
     * so one bad file can't break the whole library — the rest still load.
     */
    suspend fun loadAll(): List<HistoryEntry> =
        withContext(ioDispatcher) {
            if (!Files.isDirectory(historyDir)) return@withContext emptyList()
            historyDir
                .listDirectoryEntries()
                .filter { it.isRegularFile() && it.extension == "json" }
                .mapNotNull { path -> runCatching { json.decodeFromString(HistoryEntry.serializer(), path.readText()) }.getOrNull() }
                .sortedByDescending { it.createdAt }
        }

    /** Persists (or overwrites) a build. Atomic: writes to a temp file, then moves it into place. */
    suspend fun save(entry: HistoryEntry) {
        withContext(ioDispatcher) {
            historyDir.createDirectories()
            val target = historyDir.resolve(fileName(entry.id))
            val temp = Files.createTempFile(historyDir, "build-", ".json.tmp")
            try {
                Files.writeString(temp, json.encodeToString(HistoryEntry.serializer(), entry))
                runCatching {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                }.getOrElse {
                    // Some filesystems reject ATOMIC_MOVE across the temp/target pair; fall back to a
                    // plain replace, which is still far safer than writing the target in place.
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                temp.deleteIfExists()
            }
        }
    }

    /** Deletes the build with [id] if present. */
    suspend fun delete(id: String) {
        withContext(ioDispatcher) {
            historyDir.resolve(fileName(id)).deleteIfExists()
        }
    }

    private fun fileName(id: String): String = "${sanitize(id)}.json"

    /** Keep ids filesystem-safe; our ids are UUIDs, but be defensive about stray characters. */
    private fun sanitize(id: String): String = id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }

    /** Exposed for diagnostics/tests. */
    fun directory(): Path = historyDir
}
