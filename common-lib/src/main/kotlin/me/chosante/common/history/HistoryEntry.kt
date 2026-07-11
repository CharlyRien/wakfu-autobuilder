package me.chosante.common.history

import kotlinx.serialization.Serializable
import me.chosante.common.Characteristic
import me.chosante.common.Equipment
import me.chosante.common.Passive
import me.chosante.common.Rarity
import me.chosante.common.RuneType
import me.chosante.common.Sublimation
import me.chosante.common.SublimationLegacyFieldsSerializer

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
    /** Free-form user tags (trimmed, case-insensitively unique). Empty for pre-feature saves. */
    val tags: List<String> = emptyList(),
    /** User folder name, or null = unfiled. Folders are implicit (they exist via membership). */
    val folder: String? = null,
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
    /**
     * Sublimation availability of the request: the solver-picks toggle and the forced / excluded lists
     * (French names, the engine's match key). Defaulted so saves written before these fields load cleanly.
     */
    val useSublimations: Boolean = true,
    val maxSublimationTier: Int? = null,
    val forcedSublimations: List<String> = emptyList(),
    val excludedSublimations: List<String> = emptyList(),
    /**
     * The remaining engine-affecting request state, persisted so a save reproduces the search EXACTLY
     * (a beta report missing any of these is impossible to replay — two saves with identical visible
     * requests can then legitimately reach different "proven optimal" scores). All defaulted so saves
     * written before these fields load cleanly.
     */
    val excludedRarities: Set<Rarity> = emptySet(),
    /** The selected passive loadout (French names — the engine's match key). */
    val forcedPassives: List<String> = emptyList(),
    /** Runes pinned onto a specific carrier item: item French name → rune-id multiset (forces the item). */
    val forcedRunesByItem: Map<String, List<Int>> = emptyMap(),
    /**
     * Attack scenario for the max-damage mode. Defaulted so saves written before this field load with
     * a neutral scenario (and ignored on load for non max-damage builds). Stored as primitives because
     * the live `DamageScenario` (and its `SpellElement`/`RangeBand`/`Orientation` enums) live in the
     * engine module — see the GUI mappers, mirroring how [mode] is stored.
     */
    val scenario: DamageScenarioSnapshot = DamageScenarioSnapshot(),
)

/**
 * Persisted, primitive-only mirror of the engine's `DamageScenario`. Enum fields are stored as plain
 * [String] names and resolved with a safe fallback on load (see the GUI mappers). Every field is
 * defaulted to the engine's default scenario so older saves missing any key load cleanly.
 */
@Serializable
data class DamageScenarioSnapshot(
    /** `SpellElement` name. */
    val element: String = "FIRE",
    /** `RangeBand` name. */
    val rangeBand: String = "DISTANCE",
    /** `Orientation` name. */
    val orientation: String = "BACK",
    val berserk: Boolean = false,
    val healing: Boolean = false,
    val critCapPercent: Int = 100,
    val targetResistancePercent: Int = 0,
    val baseDamage: Int = 100,
    /**
     * Boss-aware per-element resistance map (`SpellElement` name → resistance %), or null for single-element
     * mode. Without this, a saved boss-mode build round-tripped back to single-element FIRE on reload.
     */
    val elementResistances: Map<String, Int>? = null,
    /** Survivability soft-floor (Lot 5) — persisted so a tank/floor build doesn't reload as a glass cannon. */
    val survivabilityFloor: Boolean = false,
    val minEffectiveHp: Int = 0,
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
    /**
     * Socketed runes per equipped item, keyed by `equipmentId` (the per-item rune list, in socket
     * order). [RuneType] is `@Serializable`; the displayed enchantment level is derived from the
     * carrier item's level (see `RuneType.maxLevel`), so it round-trips for free with the equipment.
     * Empty for rune-less builds and pre-feature saves.
     */
    val runes: Map<Int, List<RuneType>> = emptyMap(),
    /**
     * Chosen/forced sublimations per carrier item, keyed by `equipmentId` (mirrors [runes]). [Sublimation]
     * is `@Serializable`, so the full effect set round-trips for display. Empty for sub-less / pre-feature saves.
     * A pre-B4 save stored the structured conversion / ramp / best-element bonuses as three top-level fields;
     * [SublimationLegacyFieldsSerializer] folds any such legacy field into [Sublimation.effects] on load so those
     * effects are not silently dropped under `ignoreUnknownKeys`.
     */
    val sublimations: Map<
        Int,
        List<
            @Serializable(with = SublimationLegacyFieldsSerializer::class)
            Sublimation
        >
    > = emptyMap(),
    /** The selected passive loadout ([Passive] is `@Serializable`). Empty for pre-feature saves. */
    val passives: List<Passive> = emptyList(),
)
