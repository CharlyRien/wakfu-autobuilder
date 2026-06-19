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
 * @property maxCastPerTarget max casts of this spell on **one target** per turn (`0` = no limit).
 *   Propagated to [Spell.maxCastPerTarget] and folded into [Spell.maxCastsThisTurn]: the rotation is
 *   single-target, so this is the cap that binds whenever it is tighter than [maxCastPerTurn].
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
