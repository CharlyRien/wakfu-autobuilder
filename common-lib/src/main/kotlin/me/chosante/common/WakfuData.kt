package me.chosante.common

/**
 * The **single source of truth** for the embedded Wakfu game-data version (e.g. `1.91.1.54`).
 *
 * Everything that needs the version references [VERSION]: the apps stamp it as their `dataVersion`, the
 * (private) extractors fetch CDN assets for it, and history entries record it. The resource files themselves are
 * **not** version-stamped in their names (they are fixed: `equipments.json`, `spells.json`, …), so a
 * version bump touches only this constant.
 *
 * A new Wakfu release is brought in by the maintainer's private data tooling, which auto-detects the latest
 * version from Ankama's CDN, rewrites this constant, and regenerates every data artifact in this repository.
 */
object WakfuData {
    const val VERSION: String = "1.93.1.62"
}
