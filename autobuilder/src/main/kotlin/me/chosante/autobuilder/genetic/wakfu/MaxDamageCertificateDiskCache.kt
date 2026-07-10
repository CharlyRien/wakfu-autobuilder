package me.chosante.autobuilder.genetic.wakfu

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

private val logger = KotlinLogging.logger {}

/**
 * Disk persistence for [MaxDamageCertificateCache]'s incumbent-independent raw bounds (B5). Lets the common
 * GUI flow — the same default request across app restarts — get its "proven optimal" badge in seconds
 * instead of re-running the multi-minute certifier from cold.
 *
 * ### Soundness (the ONE thing that matters here)
 * A stale disk bound served for a DIFFERENT request would award a WRONG badge — the single forbidden failure
 * mode. Two independent guards make that impossible:
 *
 * 1. **Complete, injective [fingerprint].** The file name is `sha256(fingerprint)`, and the FULL fingerprint
 *    string is stored *inside* the file. On load the record is rejected unless its stored fingerprint equals
 *    the live request's fingerprint **byte-for-byte** — so a SHA-256 collision (astronomically unlikely) still
 *    cannot serve a wrong bound. The fingerprint canonically encodes every ledger-affecting field of the
 *    normalized [WakfuBestBuildParams] plus [me.chosante.common.WakfuData.VERSION] and
 *    [WakfuBuildSolver.CERTIFIER_VERSION]; each is length-prefixed so the encoding is unambiguous (prefix-free
 *    ⇒ injective). Bumping either version changes the fingerprint (hence the file name), so old files are
 *    simply never read. A `WakfuBuildSolverTest` field-completeness tripwire fails the build if a field is
 *    ever added to the fingerprinted graph without updating the fingerprint.
 * 2. **Payload checksum.** The file is `sha256(body)\n<body>`; a truncated or bit-flipped file fails the
 *    checksum (or JSON parse) and is dropped. On ANY anomaly — missing dir, unreadable file, bad checksum,
 *    parse failure, fingerprint mismatch — [load] returns null and the caller recomputes. A recompute is
 *    always sound; the disk layer can only ever *save time*, never change a verdict.
 *
 * Bailed shapes (no sound global bound) are never persisted. Writes are atomic (temp file + rename).
 *
 * Disabled by default ([directory] == null) so tests never touch the real user cache. Production enables it
 * once at startup via [enableDefault]; tests that exercise it point [directory] at a temporary directory.
 */
internal object MaxDamageCertificateDiskCache {
    /** null ⇒ disabled (no disk I/O). Set by [enableDefault] in production, or to a temp dir in tests. */
    @Volatile
    var directory: Path? = null

    /** Keep at most this many bound files; the oldest are pruned best-effort on write. */
    private const val MAX_FILES = 128

    private val json = Json

    /** Enable the disk cache at the OS-conventional cache location, unless already configured. */
    fun enableDefault() {
        if (directory == null) directory = defaultOsCacheDir()
    }

    /** The record for [fingerprint], or null on a miss / any anomaly (⇒ the caller recomputes; always sound). */
    fun load(fingerprint: String): DiskRecord? {
        val file = fileFor(fingerprint) ?: return null
        if (!Files.isRegularFile(file)) return null
        return runCatching {
            val content = Files.readString(file)
            val split = content.indexOf('\n')
            if (split <= 0) return null
            val storedChecksum = content.substring(0, split)
            val body = content.substring(split + 1)
            if (sha256Hex(body) != storedChecksum) {
                logger.warn { "Certificate cache file failed its checksum; ignoring: $file" }
                return null
            }
            val record = json.decodeFromString<DiskRecord>(body)
            // Defence-in-depth against a hash collision: only trust a byte-for-byte fingerprint match.
            if (record.fingerprint != fingerprint) {
                logger.warn { "Certificate cache fingerprint mismatch (hash collision?); ignoring: $file" }
                return null
            }
            record
        }.getOrElse { failure ->
            logger.warn(failure) { "Certificate cache file unreadable; ignoring: $file" }
            null
        }
    }

    /** Persist [record] for [fingerprint] atomically. Best-effort: any I/O failure is swallowed (soundness-neutral). */
    fun store(
        fingerprint: String,
        record: DiskRecord,
    ) {
        val dir = directory ?: return
        require(record.fingerprint == fingerprint) { "record fingerprint must match the store key" }
        runCatching {
            Files.createDirectories(dir)
            val body = json.encodeToString(DiskRecord.serializer(), record)
            val content = sha256Hex(body) + "\n" + body
            val target = fileFor(fingerprint)!!
            val tmp = Files.createTempFile(dir, "cert", ".tmp")
            try {
                Files.writeString(tmp, content)
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (moveFailure: Throwable) {
                runCatching { Files.deleteIfExists(tmp) }
                throw moveFailure
            }
            pruneOldest(dir)
        }.onFailure { failure ->
            logger.warn(failure) { "Could not persist certificate cache entry (non-fatal): $fingerprint" }
        }
    }

    private fun fileFor(fingerprint: String): Path? = directory?.resolve(sha256Hex(fingerprint) + ".json")

    /** Cap the cache directory; delete the oldest files past [MAX_FILES]. Best-effort. */
    private fun pruneOldest(dir: Path) {
        runCatching {
            Files.list(dir).use { stream ->
                val files =
                    stream
                        .filter { it.fileName.toString().endsWith(".json") }
                        .toList()
                if (files.size <= MAX_FILES) return
                files
                    .sortedBy { runCatching { Files.getLastModifiedTime(it).toMillis() }.getOrDefault(0L) }
                    .take(files.size - MAX_FILES)
                    .forEach { runCatching { Files.deleteIfExists(it) } }
            }
        }
    }

    private fun defaultOsCacheDir(): Path {
        val home = Path.of(System.getProperty("user.home"))
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val root =
            when {
                os.contains("mac") || os.contains("darwin") ->
                    home.resolve("Library").resolve("Caches").resolve("WakfuAutobuilder")

                os.contains("win") ->
                    System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }?.let { Path.of(it).resolve("WakfuAutobuilder") }
                        ?: home.resolve("WakfuAutobuilder")

                else ->
                    System
                        .getenv("XDG_CACHE_HOME")
                        ?.takeIf { it.isNotBlank() }
                        ?.let { Path.of(it) }
                        ?.resolve("WakfuAutobuilder")
                        ?: home.resolve(".cache").resolve("WakfuAutobuilder")
            }
        return root.resolve("cert-cache")
    }

    private fun sha256Hex(text: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

/**
 * The on-disk form of a [MaxDamageCertificateCache] `RawEntry`: the incumbent-independent raw bounds plus the
 * [fingerprint] that identifies exactly which request they belong to (verified byte-for-byte on load). Every
 * field is a primitive collection (or a [CellProvenance] pair), so serialization is total and lossless.
 */
@Serializable
internal data class DiskRecord(
    val fingerprint: String,
    val fastObjectives: Map<Int, Long>,
    val bailed: Set<Int>,
    val exactByCell: Map<Int, Long>,
    val exactBailed: Set<Int>,
    val tier15ByCell: Map<Int, Long>,
    // E8 item A (perf): the winning (world, crit-step) per exactly-confirmed cell. OPTIONAL (defaulted empty) so a
    // record written before this field existed still decodes cleanly — the E8 fast-path then just falls back to the
    // full provenance scan for that shape, which is sound. Not part of the fingerprint (it is payload, not identity).
    val provByCell: Map<Int, CellProvenance> = emptyMap(),
)
