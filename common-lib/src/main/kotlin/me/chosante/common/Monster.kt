package me.chosante.common

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable

/**
 * A Wakfu monster / boss, used by the autobuilder's **boss mode** to auto-fill an attack scenario
 * (target's elemental resistances) and to display "hits-to-kill".
 *
 * The dataset is produced by the `bdata-extractor` — decoded straight from the local game client's
 * Monster table (TYPE_ID 42) plus localized names from the i18n bundle — and baked into the
 * `autobuilder` resources as `monsters.json`, exactly like `equipments`/`spells`.
 *
 * **Resistances are stored FLAT** (Ankama's raw per-element resistance, the value a debuff adds to /
 * removes from) — this is the source of truth. The damage engine converts flat → % at use time via
 * `Res% = floor((1 − 0.8^(flat/100)) × 100)`, capped at 90%. Flat resistances **may be negative**
 * (an elemental *weakness*) and that sign is preserved on purpose: picking the element a boss is weak
 * to is the whole point of boss mode.
 *
 * @property rank Ankama's monster rank (0 = normal; ≥ 1 = boss-tier: bosses, dimension golems,
 *   ultimate bosses, "Dominant" variants). [isBoss] is the convenience predicate, and the GUI boss
 *   picker shows only `isBoss` monsters. Boss-tier is an editorial taxonomy absent from every client
 *   table, so it is carried by the committed `monster-overlay.json` overlay; monsters with no entry
 *   default to 0 (regular, hidden from the picker).
 * @property gfx Ankama sprite/graphics id, used by the GUI to resolve the monster's icon PNG
 *   (`assets/monsters/<gfx>.png`, sourced from the community `Vertylo/wakassets` set — the same repo as
 *   item icons). Decoded straight from the Monster record (the bdata schema is auto-derived from the client
 *   bytecode, so the trailing GFX block is reached even across version drift). Nullable on purpose:
 *   `<= 0` (no sprite) becomes null and consumers degrade to no icon rather than crash.
 * @property source Data provenance, e.g. `"bdata"` (decoded from the local client), for traceability.
 */
@Serializable
data class Monster(
    val id: Int,
    val name: I18nText,
    val level: Int,
    val hp: Int,
    val family: I18nText? = null,
    val rank: Int = 0,
    val gfx: Int? = null,
    val fireResistance: Int,
    val waterResistance: Int,
    val earthResistance: Int,
    val airResistance: Int,
    // Always serialized even though it equals its default: buildMonsters sets "bdata", so without this the
    // value would silently vanish from monsters.json on the next regeneration (encodeDefaults is off).
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val source: String = "bdata",
) {
    val isBoss: Boolean get() = rank >= 1
}
