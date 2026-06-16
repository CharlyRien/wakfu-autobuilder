package me.chosante.common

import kotlinx.serialization.Serializable

/**
 * A Wakfu monster / boss, used by the autobuilder's **boss mode** to auto-fill an attack scenario
 * (target's elemental resistances) and to display "hits-to-kill".
 *
 * The dataset is produced by the `monsters-extractor` module and baked into the `autobuilder`
 * resources as `monsters-v<VERSION>.json`, exactly like `equipments`/`runes`.
 *
 * **Resistances are stored FLAT** (Ankama's raw per-element resistance, the value a debuff adds to /
 * removes from) — this is the source of truth. The damage engine converts flat → % at use time via
 * `Res% = floor((1 − 0.8^(flat/100)) × 100)`, capped at 90%. Flat resistances **may be negative**
 * (an elemental *weakness*) and that sign is preserved on purpose: picking the element a boss is weak
 * to is the whole point of boss mode.
 *
 * @property rank Ankama's monster rank (0 = normal; ≥ 1 = boss-tier: bosses, dimension golems,
 *   ultimate bosses, "Dominant" variants). [isBoss] is the convenience predicate.
 * @property source Data provenance, e.g. `"methodwakfu"` or `"methodwakfu+fandom"`, for traceability.
 */
@Serializable
data class Monster(
    val id: Int,
    val name: I18nText,
    val level: Int,
    val hp: Int,
    val family: I18nText? = null,
    val rank: Int = 0,
    val fireResistance: Int,
    val waterResistance: Int,
    val earthResistance: Int,
    val airResistance: Int,
    val source: String = "methodwakfu",
) {
    val isBoss: Boolean get() = rank >= 1
}
