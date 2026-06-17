package me.chosante.common

import kotlinx.serialization.Serializable

/**
 * Per-spell cast limits decoded from the local game client by the `bdata-extractor` module and baked
 * into `autobuilder/src/main/resources/spell-cast-limits-v<VERSION>.json` (see
 * `docs/SPELL_CAST_LIMITS_EXTRACTION.md`). Joined onto [Spell] by [spellId] at catalog load
 * (`SpellCatalog`) so the spell-rotation optimizer can bound how often a spell is cast per turn.
 *
 * Convention from the extraction: values are the **raw decoded integers** — **`0` means "no limit" /
 * "no cooldown"** (unlimited), *not* a finite cap of zero. [Spell.maxCastsThisTurn] applies that
 * convention. Every field is nullable so a partial/absent record degrades to "unknown" (= no cap)
 * rather than inventing a value.
 *
 * @property maxCastPerTarget single-target caps are out of scope for the single-target damage path;
 *   carried for completeness / the future multi-target lot but not propagated to [Spell].
 */
@Serializable
data class SpellCastLimit(
    val spellId: Int,
    val name: String? = null,
    val breedId: Int? = null,
    val maxCastPerTurn: Int? = null,
    val maxCastPerTurnIncr: Int? = null,
    val maxCastPerTarget: Int? = null,
    val cooldown: Int? = null,
    /** WP (Wakfu Point) base cost (`0` = free). Joined onto [Spell.wpCost]. */
    val wpCost: Int? = null,
)
