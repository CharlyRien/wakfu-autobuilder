package me.chosante.autobuilder.genetic.wakfu

import com.google.ortools.Loader
import io.github.oshai.kotlinlogging.KotlinLogging
import java.net.JarURLConnection
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.jar.JarFile

/**
 * Loads the OR-Tools native libraries, replacing [Loader.loadNativeLibraries] on macOS with an
 * extract-once cache.
 *
 * The stock loader unpacks the ~100 bundled dylibs (~50 MB) into a **fresh temp directory on every
 * launch**, and macOS validates the code signature of freshly written dylibs on their first
 * `dlopen` — measured at 10–25 s on an M-series Mac, during which the OS dynamic loader stalls the
 * whole UI (AWT event thread / AppKit), freezing the app at every startup. That validation is
 * cached per on-disk file, so loading the *same* files again costs ~50 ms (measured: 25.4 s fresh
 * vs 0.055 s cached). Extracting once into a stable per-version directory and reusing it makes
 * every launch after the first fast and freeze-free; only the very first launch (or the first one
 * after an OR-Tools upgrade) still pays the validation.
 *
 * Any unexpected condition (non-jar classpath, unwritable cache, partial extraction, exotic OS…)
 * falls back to the stock loader — slow but battle-tested. Non-mac OSes always use the stock
 * loader: they don't re-validate fresh files the way macOS does, so the cache would only save a
 * little extraction IO.
 */
object OrToolsNativeLoader {
    private val logger = KotlinLogging.logger {}
    private var loaded = false

    private const val JNI_LIB = "libjniortools.dylib"

    /**
     * Idempotent. Like the stock loader (`synchronized` there too), concurrent callers block until
     * the first one finishes, so no caller can ever observe a half-loaded state; a failed attempt
     * (stock-loader throw) stays un-latched and is retried by the next call.
     */
    @Synchronized
    fun load() {
        if (loaded) return
        val isMac =
            System
                .getProperty("os.name")
                .orEmpty()
                .lowercase()
                .contains("mac")
        val cached =
            isMac &&
                runCatching { loadFromStableCache() }
                    .onFailure { logger.warn(it) { "OR-Tools stable-cache load failed; falling back to the stock loader." } }
                    .getOrDefault(false)
        if (!cached) {
            Loader.loadNativeLibraries()
        }
        loaded = true
    }

    /** @return true when the natives were loaded from (or freshly installed into) the stable cache. */
    private fun loadFromStableCache(): Boolean {
        val arch = System.getProperty("os.arch").orEmpty().lowercase()
        val resourceDir = "ortools-darwin-" + if (arch == "aarch64" || arch == "arm64") "aarch64" else "x86-64"

        val jniResource =
            Loader::class.java.classLoader.getResource("$resourceDir/$JNI_LIB")
                ?: return false
        val connection = jniResource.openConnection() as? JarURLConnection ?: return false
        connection.useCaches = false
        val jarPath = Path.of(connection.jarFileURL.toURI())

        // One cache directory per *content fingerprint* of the bundled natives, so any OR-Tools
        // change lands in a new directory and re-validates exactly once. The jar's file name is NOT
        // a safe key: the natives can be resolved from the CLI fat jar, whose name never changes
        // across upgrades. CRCs come from the zip central directory, so fingerprinting reads no
        // entry content — and stays identical across fat-jar rebuilds that keep the same natives.
        val cacheRoot =
            Path
                .of(System.getProperty("user.home"))
                .resolve("Library")
                .resolve("Caches")
                .resolve("WakfuAutobuilder")
                .resolve("ortools-native")
        val cacheDir = cacheRoot.resolve(nativesFingerprint(jarPath, resourceDir))
        val jniPath = cacheDir.resolve(JNI_LIB)

        if (!Files.isRegularFile(jniPath)) {
            extractInto(jarPath, resourceDir, cacheDir)
        }
        try {
            // The JNI dylib finds its ~100 dependencies next to itself (@loader_path), exactly like
            // the stock loader's temp directory.
            System.load(jniPath.toAbsolutePath().toString())
        } catch (firstAttempt: Throwable) {
            // Self-heal: ~/Library/Caches is purgeable, so macOS (or a cleaner) can delete *some* of
            // the ~100 sibling dylibs while the JNI gate file survives — without this retry such a
            // half-purged directory would be trusted (and fail) on every launch forever, silently
            // reinstating the per-launch slow path this cache exists to eliminate.
            logger.warn(firstAttempt) { "Cached OR-Tools natives failed to load; re-extracting once: $cacheDir" }
            cacheDir.toFile().deleteRecursively()
            extractInto(jarPath, resourceDir, cacheDir)
            System.load(jniPath.toAbsolutePath().toString())
        }
        // Recency marker for cleanStaleVersions: a directory's own mtime only changes when entries
        // are added/removed, never on load, so an actively-used cache would otherwise look stale.
        runCatching {
            Files.setLastModifiedTime(
                cacheDir,
                java.nio.file.attribute.FileTime
                    .fromMillis(System.currentTimeMillis())
            )
        }
        cleanStaleVersions(cacheRoot, keep = cacheDir)
        logger.debug { "OR-Tools natives loaded from stable cache: $cacheDir" }
        return true
    }

    /** Stable hex digest of the `resourceDir/` entries' names + CRC32s (zip central directory only). */
    private fun nativesFingerprint(
        jarPath: Path,
        resourceDir: String,
    ): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        JarFile(jarPath.toFile()).use { jar ->
            jar
                .entries()
                .asSequence()
                .filter { !it.isDirectory && it.name.startsWith("$resourceDir/") }
                .sortedBy { it.name }
                .forEach { entry ->
                    digest.update(entry.name.toByteArray())
                    digest.update(entry.crc.toString().toByteArray())
                }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }.take(16)
    }

    /**
     * Extracts every `resourceDir/` entry of [jarPath] into [target]. Writes to a temp sibling and
     * atomically renames so [target] is only ever absent or complete — a crash mid-extraction can
     * never leave a half-populated directory that later loads would trust.
     */
    private fun extractInto(
        jarPath: Path,
        resourceDir: String,
        target: Path,
    ) {
        Files.createDirectories(target.parent)
        val tmp = Files.createTempDirectory(target.parent, ".extract-")
        try {
            JarFile(jarPath.toFile()).use { jar ->
                for (entry in jar.entries()) {
                    if (entry.isDirectory || !entry.name.startsWith("$resourceDir/")) continue
                    val out = tmp.resolve(entry.name.substringAfterLast('/'))
                    jar.getInputStream(entry).use { Files.copy(it, out, StandardCopyOption.REPLACE_EXISTING) }
                }
            }
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE)
            } catch (moveFailed: Exception) {
                if (Files.isRegularFile(target.resolve(JNI_LIB))) {
                    // Another process finished first; its copy is equivalent.
                    tmp.toFile().deleteRecursively()
                } else {
                    // A half-purged leftover directory (e.g. macOS reaped the JNI dylib but not its
                    // siblings) blocks the rename; replace it wholesale or fail for real.
                    target.toFile().deleteRecursively()
                    Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE)
                }
            }
        } catch (failure: Throwable) {
            tmp.toFile().deleteRecursively()
            throw failure
        }
    }

    /**
     * Best-effort removal of cache entries (each ~50 MB) unused for 30+ days — covering both old
     * OR-Tools versions and `.extract-` directories orphaned by a crash mid-extraction. Age-based
     * on purpose: a delete-all-siblings policy would make two coexisting versions (an installed
     * release alongside a dev tree after a dependency bump) evict each other on every alternating
     * launch, re-paying the validation the cache exists to avoid. Recency comes from the marker
     * touch in [loadFromStableCache].
     */
    private fun cleanStaleVersions(
        root: Path,
        keep: Path,
    ) {
        val cutoffMs = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
        runCatching {
            Files.list(root).use { siblings ->
                siblings
                    .filter { it != keep }
                    .filter { runCatching { Files.getLastModifiedTime(it).toMillis() < cutoffMs }.getOrDefault(false) }
                    .forEach { stale -> runCatching { stale.toFile().deleteRecursively() } }
            }
        }
    }
}
