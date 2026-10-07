package me.chosante.common

import kotlinx.serialization.Serializable

/**
 * An item's EQUIP criterion: the condition the game checks before a character may wear the item — and re-checks on
 * the whole equipped set, so it is a rule on the FINAL build ("the sword is worn ⇒ its ring is worn").
 *
 * Decoded from the local game client's Item table (35) by `bdata-extractor` into `item-criteria.json`, for the items
 * of `equipments.json` only. [raw] is the client's expression verbatim (Ankama's `Critere` language: `and` / `or` /
 * `not`, comparisons, function calls); every other field is one typed part of it, and the criterion holds iff ALL of
 * them hold — the extractor fails on any shape it cannot type that way (an `or`, a negated conjunction, an unknown
 * function).
 *
 * What the engine does with each part:
 *  - [requiresItems], [forbidsItems], [classes] and [never] are ENFORCED (the CP-SAT model, the pool filters,
 *    `BuildCombination.isValid`);
 *  - [uniqueEquipped] is already enforced by the "never two rings of the same name" rule (rings are the only items
 *    carrying it, and two copies of one item share their name);
 *  - [statGates] are ENFORCED on the build's OUT-OF-COMBAT sheet (`outOfCombatSheet` in the engine): base + every item, the
 *    gated item's own bonus included, + skills + runes + permanent sublimation effects + passives — never an in-combat bonus.
 *    In game an item whose gate fails is inactive (shown red), so such a build is not a valid build;
 *  - [playerState] is NOT enforced: the player's state (company rank, achievements, gauges, crime score) is outside the
 *    build, so it is assumed satisfied and kept for display.
 */
@Serializable
data class ItemEquipCriterion(
    /** The item's id ([Equipment.equipmentId]). */
    val itemId: Int,
    /** The client's EQUIP expression, verbatim (line breaks included). */
    val raw: String,
    /** `HasEquipmentId(x)`: the item can only be worn together with EACH of these items. */
    val requiresItems: List<Int> = emptyList(),
    /**
     * `not HasEquipmentId(x)`: the item can't be worn together with any of these items. The relation is symmetric in
     * the game (the criterion is re-checked on the whole set), so the engine reads its symmetric closure: two items
     * conflict when EITHER one forbids the other.
     */
    val forbidsItems: List<Int> = emptyList(),
    /** `IsBreed("…")`: only these classes may wear the item. Empty = every class. */
    val classes: List<CharacterClass> = emptyList(),
    /** A literal `False` atom: no character can ever wear the item. */
    val never: Boolean = false,
    /** `not HasAnotherSameEquipment()`: never two copies of the item at once (rings). */
    val uniqueEquipped: Boolean = false,
    /** `GetCharac` / `GetCharacMax` comparisons on the wearer's out-of-combat characteristics (enforced, see above). */
    val statGates: List<ItemStatGate> = emptyList(),
    /** Comparisons on the player's state outside the build (not enforced: assumed satisfied, see above). */
    val playerState: List<ItemPlayerStateAtom> = emptyList(),
) {
    /** True when the criterion restricts which builds may contain the item (the parts the engine enforces). */
    val constrainsBuild: Boolean
        get() = requiresItems.isNotEmpty() || forbidsItems.isNotEmpty() || classes.isNotEmpty() || never
}

/** A comparison operator of an item criterion (`GetCharac("AP") <= 11` → [LE]). */
@Serializable
enum class CriterionComparison(
    val symbol: String,
) {
    LT("<"),
    LE("<="),
    GT(">"),
    GE(">="),
    EQ("=="),
    NE("!="),
    ;

    /** Whether `actual <op> value` holds. */
    fun holds(
        actual: Int,
        value: Int,
    ): Boolean =
        when (this) {
            LT -> actual < value
            LE -> actual <= value
            GT -> actual > value
            GE -> actual >= value
            EQ -> actual == value
            NE -> actual != value
        }

    /** The operator of `not (a <op> b)`. */
    fun negated(): CriterionComparison =
        when (this) {
            LT -> GE
            LE -> GT
            GT -> LE
            GE -> LT
            EQ -> NE
            NE -> EQ
        }

    companion object {
        /** The operator written [symbol] in the client's language (`<>` is a synonym of `!=`), or null. */
        fun ofSymbol(symbol: String): CriterionComparison? = if (symbol == "<>") NE else entries.firstOrNull { it.symbol == symbol }
    }
}

/**
 * `GetCharac("<stat>") <op> <value>` — or `GetCharacMax`, which reads the characteristic's MAXIMUM (the AP / MP / WP
 * pool) rather than its current value ([max]). Out of combat a pool is full (current = max), so the engine reads both on
 * the same out-of-combat total (MAX_* lines included); [max] only changes the label ("Max AP ≤ 11"). On the 1.93 data every
 * `GetCharacMax` gate is on AP / MP / WP. Negations are folded into [comparison].
 */
@Serializable
data class ItemStatGate(
    val characteristic: Characteristic,
    val max: Boolean = false,
    val comparison: CriterionComparison,
    val value: Int,
)

/**
 * A condition on the player outside the build (`GetCompanyRank() >= 4`, `IsAchievementComplete(1509)`,
 * `GetStasisGauge() > 30`…): [function] with its literal [args]; for a numeric function, the [comparison] with
 * [value] (a negation folded in); for a boolean one, [negated] when it is preceded by `not`.
 */
@Serializable
data class ItemPlayerStateAtom(
    val function: String,
    val args: List<String> = emptyList(),
    val comparison: CriterionComparison? = null,
    val value: Int? = null,
    val negated: Boolean = false,
)
