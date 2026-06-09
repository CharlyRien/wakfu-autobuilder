package me.chosante.common.history

import kotlinx.serialization.Serializable
import me.chosante.common.Characteristic
import me.chosante.common.Equipment
import me.chosante.common.Rarity

/**
 * The persisted, on-disk shape of a saved build. **Deliberately decoupled from the live engine
 * object graph**: we never serialize `BuildCombination`, `Character` or the mutable, sealed
 * `CharacterSkills` hierarchy. Instead we store stable identifiers (enum names, item ids) plus
 * enough denormalized data to (a) re-display the build offline even if an item later leaves the
 * catalog, and (b) reload the original *request* to re-run or tweak it.
 *
 * This isolation is what lets the format survive an engine rewrite (GA ↔ OR-Tools) and Wakfu data
 * bumps. [schemaVersion] is carried so the format can be migrated later if needed.
 *
 * It lives in `common-lib` (rather than the GUI module) on purpose: that is where the working
 * `kotlin("plugin.serialization")` setup is, and where the [Equipment] / [Characteristic] / [Rarity]
 * types it references already have generated serializers. The GUI only needs the JSON *runtime* to
 * read/write these — no serialization compiler plugin (which currently clashes with the Compose
 * plugin in that module).
 *
 * Note on enum storage: [Characteristic], [Rarity] and [Equipment] are `@Serializable`, so they are
 * stored directly (as their names in JSON). The engine/class enums (`CharacterClass`,
 * `ScoreComputationMode`) are not serializable and live in other modules, so they are stored as plain
 * [String] names and resolved with a safe fallback on load — see the GUI's mappers. (Old saves may
 * still carry a `solver` key from the removed genetic-algorithm engine; it is ignored on load.)
 */
@Serializable
data class HistoryEntry(
    val id: String,
    val name: String,
    val createdAt: Long,
    val note: String? = null,
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    /** Embedded Wakfu game-data version this build was computed against (e.g. `1.91.1.54`). */
    val dataVersion: String,
    val request: RequestSnapshot,
    val result: ResultSnapshot,
    /** Cached Zenith share URL, if one was ever generated for this build. */
    val zenithUrl: String? = null,
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

/**
 * Everything needed to reload the *request* into the workspace and re-run or tweak it — the part
 * that actually delivers "don't redo the same search twice".
 */
@Serializable
data class RequestSnapshot(
    /** `CharacterClass` name. */
    val clazz: String,
    val level: Int,
    val minLevel: Int,
    /** `ScoreComputationMode` name. */
    val mode: String,
    val maxRarity: Rarity,
    val duration: String,
    val stopAtMatch: Boolean,
    val targets: List<TargetSnapshot>,
    val forcedItems: List<ItemRef>,
    val excludedItems: List<ItemRef>,
)

@Serializable
data class TargetSnapshot(
    val characteristic: Characteristic,
    val value: String,
    /** Per-stat priority (#123), 1..10. Defaulted so saves written before the feature load as neutral. */
    val weight: Int = 1,
)

/** A forced/excluded item, mirroring the GUI's `ItemChip`. */
@Serializable
data class ItemRef(
    val name: String,
    val rarity: Rarity,
    val matchName: String,
)

/**
 * Everything needed to *display* the discovered build without re-running the solver. Stores the
 * full [Equipment] list (it is `@Serializable` and carries id/guiId/name/rarity/level/stats — so
 * the paperdoll, tooltips and the compare view all work straight off it, even offline) and a flat
 * skill map (`skill name → points`) instead of the non-serializable `CharacterSkills`.
 */
@Serializable
data class ResultSnapshot(
    val equipments: List<Equipment>,
    /** Flat allocation: skill-characteristic name → points assigned. */
    val skills: Map<String, Int>,
    val achieved: Map<Characteristic, Int>,
    /** Match percentage (0..100). Stored as Double to preserve the engine's BigDecimal value. */
    val match: Double,
    val optimal: Boolean,
)
