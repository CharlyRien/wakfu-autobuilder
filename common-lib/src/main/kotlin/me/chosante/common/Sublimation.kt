package me.chosante.common

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer

/** Where a sublimation goes: an epic/relic dedicated character slot, or a normal 3-colour socket set. */
@Serializable
enum class SublimationRarity { EPIC, RELIC, NORMAL }

/**
 * How a sublimation's effect can be modeled by the solver (see AGENTS.md §5):
 * - [FLAT]: unconditional stat contributions.
 * - [STATIC_CONDITIONAL]: applies only when a build-static start-of-combat [condition] holds (CP-SAT modelable).
 * - [CONVERSION]: moves a percentage of one stat into another (optionally under a static condition).
 * - [COMBAT_CONDITIONAL]: depends on in-combat events; **not** solver-modelable — forced-input only.
 */
@Serializable
enum class SublimationKind { FLAT, STATIC_CONDITIONAL, CONVERSION, COMBAT_CONDITIONAL }

/** The finite vocabulary of build-static start-of-combat conditions (see research §4a). */
@Serializable
enum class SublimationConditionType {
    AP_AT_MOST,
    AP_AT_LEAST,
    AP_EXACT,
    AP_ODD,
    CRIT_AT_MOST,
    CRIT_AT_LEAST,

    /**
     * "Critical **Mastery** ≤ N" (almost always ≤ 0) — distinct from [CRIT_AT_MOST], which caps the critical-hit
     * *rate*. The in-game *Critical Secret* sub grants +crit-rate only when the build has invested **no** critical
     * mastery; decoded from the 1-argument `GetCharac("CRITICAL_BONUS") <= N` criterion. Evaluated against
     * `MASTERY_CRITICAL`. (The 2-argument `GetCharac("CRITICAL_BONUS", "caster")` form is instead one of the six
     * tokens of [SECONDARY_MASTERIES_AT_MOST]; the arity disambiguates them.)
     */
    CRITICAL_MASTERY_AT_MOST,
    BLOCK_AT_LEAST,
    RANGE_AT_MOST,
    RANGE_AT_LEAST,
    RANGE_EXACT,
    DODGE_LT_PCT_OF_LEVEL,
    SECONDARY_MASTERIES_AT_MOST,
    WEAPON_TYPE_EQUIPPED,

    /**
     * "No shield, dagger or two-handed weapon equipped" (the in-game *light-weapons* condition, e.g. Light
     * Weapons Expert). Decoded from a `not HasWeaponType(...)` criterion whose forbidden types are exactly the
     * six two-handed subtypes plus dagger + shield — i.e. every off-hand and two-handed weapon — so it holds iff
     * the build leaves **both** the off-hand and two-handed slots empty (a single one-handed main weapon only).
     */
    NO_OFFHAND_OR_TWO_HANDED,
    HIGHEST_ELEM_MASTERY_GT_REAR,
    HIGHEST_ELEM_MASTERY_GT_HEALING,
    OTHER,
}

/**
 * The six **secondary** masteries (melee / distance / berserk / rear / critical / healing) — i.e. every
 * mastery that is *not* an elemental one. The [SublimationConditionType.SECONDARY_MASTERIES_AT_MOST]
 * condition (e.g. Neutrality / Ambition: "if secondary masteries ≤ 0") is evaluated against the **sum** of
 * these. Single source of truth so the CP-SAT solver, the re-scorer and the extractor classifier stay in
 * lockstep — omitting any of them (the solver historically summed only melee+distance) makes the condition
 * spuriously hold for a rear/crit-stacking build and hands it the bonus for free.
 */
val SECONDARY_MASTERY_CHARACTERISTICS: Set<Characteristic> =
    setOf(
        Characteristic.MASTERY_MELEE,
        Characteristic.MASTERY_DISTANCE,
        Characteristic.MASTERY_BERSERK,
        Characteristic.MASTERY_BACK,
        Characteristic.MASTERY_CRITICAL,
        Characteristic.MASTERY_HEALING
    )

@Serializable
data class SublimationCondition(
    val type: SublimationConditionType,
    /** Numeric threshold for the comparison conditions (e.g. 10 for AP_AT_MOST 10). */
    val value: Int? = null,
    /** Free text payload for non-numeric conditions (e.g. "dagger"/"shield" for WEAPON_TYPE_EQUIPPED). */
    val text: String? = null,
)

/**
 * Restricts an effect to a specific attack [me.chosante.autobuilder.domain.DamageScenario]. All fields
 * are compile-time constants for a given scenario, so a non-matching gated effect is simply dropped by
 * the solver (no variable needed). Strings mirror the scenario enums (`MELEE`/`DISTANCE`, `BACK`/`FRONT`/`SIDE`).
 */
@Serializable
data class ScenarioGate(
    val rangeBand: String? = null,
    val orientation: String? = null,
    val berserk: Boolean? = null,
    val ranged: Boolean? = null,
    val area: Boolean? = null,
    val minCharacterLevel: Int? = null,
    /**
     * Restricts the effect to a single damage **element** (`FIRE`/`WATER`/`EARTH`/`AIR`, matching
     * `SpellElement`). The per-element-damage sublimations (Brûlure/Gel/Tellurisme/Ventilation — "+X% Fire/
     * Water/Earth/Air damage") carry this: in the game data their bonus is implemented as element-triggered
     * branches, but the player-facing effect is simply "+X% <element> damage". Like the other gates it is
     * honoured only in max-damage mode, where the solver optimizes one element at a time, so the bonus is a
     * compile-time constant per solve (credited iff the scenario's element matches).
     */
    val element: String? = null,
)

/**
 * One stat contribution of a sublimation, at its max level (best-achievable model). A sealed hierarchy so each
 * way Ankama can grant a stat is its own shape (no nullable-field soup): a plain [Flat] amount, or a
 * level-scaled [PercentOfLevel]. The cross-cutting fields ([characteristic], [scenarioGate],
 * [appliesBeforeCombat]) live on the interface; consumers that only need the magnitude call
 * [magnitudeAtLevel] (so the solver/re-scorer hot paths stay variant-agnostic) while display/decoding `when`
 * over the concrete type. Serialized polymorphically (a `type` discriminator: `flat` / `percentOfLevel`).
 */
@Serializable
sealed interface SublimationEffect {
    val characteristic: Characteristic
    val scenarioGate: ScenarioGate?

    /**
     * True iff this is a **permanent**, out-of-combat stat — it shows on the character sheet, so it is present
     * BEFORE combat starts. Only permanent effects feed build-static start-of-combat conditions: a permanent
     * +crit (Influence II) counts toward another sub's [SublimationConditionType.CRIT_AT_MOST], but a
     * start-of-combat / conditional +crit (Secondary Devastation II, Ambition) does **not** — it is applied
     * *at* combat start, after the condition is read, and a conditional sub must never feed its own condition.
     * Decoded from the StaticEffect timing (`duration_base == -1`, not `ends_at_end_of_turn`, no condition on the
     * sub, and no `triggers_*` on the effect or any ancestor group). Default `false` = treated as start-of-combat
     * (never fed to a condition), so an unclassified effect can never spuriously satisfy one.
     */
    val appliesBeforeCombat: Boolean

    /** The modeled flat magnitude for a character of [level]: [Flat.value], or `floor(percent · level / 100)`. */
    fun magnitudeAtLevel(level: Int): Int

    /** A plain flat stat contribution (the common case). */
    @Serializable
    @SerialName("flat")
    data class Flat(
        override val characteristic: Characteristic,
        val value: Int,
        override val scenarioGate: ScenarioGate? = null,
        override val appliesBeforeCombat: Boolean = false,
    ) : SublimationEffect {
        override fun magnitudeAtLevel(level: Int): Int = value
    }

    /**
     * **[percentOfLevel]% of the character's level** of [characteristic] — Ankama's "X% of level as …"
     * sublimations (Light Weapons Expert's elemental mastery, the lock/dodge "Brawling"/"Evasion" family, …).
     * Resolved at solve time because the data is level-independent; magnitude = `floor(percentOfLevel · level / 100)`.
     */
    @Serializable
    @SerialName("percentOfLevel")
    data class PercentOfLevel(
        override val characteristic: Characteristic,
        val percentOfLevel: Int,
        override val scenarioGate: ScenarioGate? = null,
        override val appliesBeforeCombat: Boolean = false,
    ) : SublimationEffect {
        override fun magnitudeAtLevel(level: Int): Int = (percentOfLevel * level) / 100
    }
}

/**
 * Back-compat serializer for [SublimationEffect]. It encodes with the normal polymorphic `type` discriminator
 * (`flat` / `percentOfLevel`) — so `sublimations.json` and freshly-saved builds are byte-identical to before —
 * but on **decode defaults a discriminator-less object to [SublimationEffect.Flat]**. Builds saved before
 * [SublimationEffect] became a sealed hierarchy stored each effect as a plain `{ "characteristic": …,
 * "value": … }` with no `type`; without this those objects fail to decode, and the build-history / clipboard
 * loader silently drops the whole saved build. Injecting the missing discriminator lets them load as the flat
 * effects they always were.
 */
object SublimationEffectLenientSerializer :
    JsonTransformingSerializer<SublimationEffect>(SublimationEffect.serializer()) {
    override fun transformDeserialize(element: JsonElement): JsonElement =
        if (element is JsonObject && "type" !in element) {
            JsonObject(element + ("type" to JsonPrimitive("flat")))
        } else {
            element
        }
}

/** A [SublimationKind.CONVERSION] payload: move [percent]% of [from] into [to] at start of combat. */
@Serializable
data class SublimationConversion(
    val from: Characteristic,
    val to: Characteristic,
    val percent: Int,
)

/**
 * A build-static ramp bonus: at start of combat grant [target] equal to
 * `clamp(perStep · max(0, source − threshold), 0, cap)`, where [source] is one of the build's OWN stats
 * (Featherweight: `source=MOVEMENT_POINT, threshold=4, perStep=6, cap=24, target=DAMAGE_INFLICTED` →
 * "+6% Damage Inflicted per MP above 4, max +24"). Decoded first-party from Ankama's action-913 linear-ramp
 * formula — the [source] id read from the client's characteristic enum, slope/intercept from the effect params,
 * the [cap] from the child action-126. Its magnitude depends on a build variable, so the solver models it as a
 * reified clamped term and the re-scorer applies [contribution] AFTER the build's stats (incl. [source]) are
 * totalled — it never goes through the level-only [SublimationEffect.magnitudeAtLevel].
 */
@Serializable
data class PerStatStepSpec(
    val source: Characteristic,
    val threshold: Int,
    val perStep: Int,
    val cap: Int,
    val target: Characteristic,
) {
    /** The [target] magnitude for a build whose [source] stat totals [sourceValue]. */
    fun contribution(sourceValue: Int): Int = (perStep * maxOf(0, sourceValue - threshold)).coerceIn(0, cap)
}

/**
 * The "best element" concentration bonus (Elemental Concentration): at start of combat, grant
 * +[damageInflictedBonus]% Damage Inflicted **and** −[masteryPenaltyPercent]% Elemental Mastery on every element
 * except your strongest one ("the 3 weakest elements").
 *
 * **Sound damage-mode model:** credit only the +[damageInflictedBonus]% DI, gated so the scenario / requested
 * damage element is the build's **strongest** element. This is not just optimistic — it is *exact*: when the damage
 * element is NOT the strongest, the −[masteryPenaltyPercent]% penalty lands on it, and (with a 20/30 split)
 * `0.70 · 1.20 = 0.84 < 1` makes the sublimation strictly worse than not taking it, so such a build is never the
 * optimum. The strongest-element guard therefore excludes only dominated builds while never over-crediting the DI
 * (the un-modeled penalty can never apply to the element whose damage we score). Only meaningful where there is a
 * single damage element to protect (max-damage; mono-element most-masteries) — elsewhere it stays forced-input-only.
 */
@Serializable
data class BestElementConcentration(
    val damageInflictedBonus: Int,
    val masteryPenaltyPercent: Int,
)

/**
 * A Wakfu sublimation, keyed by its in-game `stateId` (the stable join key — 467 item rows collapse to
 * 232 distinct effects). Effect data is decoded **first-party** from the local client's State (67) →
 * StaticEffect (68) tables by `bdata-extractor` (`SublimationBuilder`); identity/name/rarity/slot colours
 * come from the CDN `items.json` (itemTypeId 812). No third-party (WakForge) dump is used.
 *
 * Best-achievable model: a chosen sublimation contributes its **max-level** [effects].
 */
@Serializable
data class Sublimation(
    val stateId: Int,
    /** The sublimation's in-game item id — used as Zenith's `/shard/add` `id_shard` (a sub socketes like a rune). */
    val zenithId: Int = 0,
    val name: I18nText,
    val rarity: SublimationRarity,
    /** Normal subs: 3 socket colours (`1`=red, `2`=green, `3`=blue, matching [RuneColor]). Empty for epic/relic. */
    val slotColorPattern: List<Int> = emptyList(),
    val maxLevel: Int = 1,
    val kind: SublimationKind,
    /** True only when the solver can correctly model every effect of this sub (FLAT/STATIC/CONVERSION). */
    val solverChoosable: Boolean = false,
    val condition: SublimationCondition? = null,
    val effects: List<
        @Serializable(with = SublimationEffectLenientSerializer::class)
        SublimationEffect
    > = emptyList(),
    val conversion: SublimationConversion? = null,
    /**
     * A build-static "per point of a source stat above a threshold" bonus (Featherweight: +6% Damage Inflicted
     * per MP above 4, max +24). Applied specially by the solver/scorer because its value depends on the build's
     * own [PerStatStepSpec.source] stat — not on level, so it cannot go through [SublimationEffect.magnitudeAtLevel].
     */
    val perStatStep: PerStatStepSpec? = null,
    /**
     * The "best element" concentration bonus (Elemental Concentration: +20% Damage Inflicted, −30% mastery on the
     * three weakest elements). Modeled soundly in the damage modes as a scenario-element-strongest-gated DI bonus —
     * see [BestElementConcentration]. Applied specially by the solver/scorer (not a flat [effects] entry).
     */
    val bestElementConcentration: BestElementConcentration? = null,
    /**
     * Chaos: at start of combat, **all elemental masteries are set to 0** (in exchange for its unconditional +20%
     * Damage Inflicted, an ordinary [effects] entry). **Decoded first-party but NOT solver-modeled** — the sub is
     * deliberately [solverChoosable] = false. A sound max-damage model exists (gate/subtract the scenario element's
     * mastery when the sub is picked), but it makes the solver explore both an elemental-on and an elemental-zeroed
     * damage regime for every build — ~5× the max-damage proof time, globally (the domain widening is inherent to the
     * semantics). It only wins for a deliberately secondary-mastery-stacked, elemental-light build
     * (`secondary × 1.2 > elemental`), not worth that cost on every request. Kept as data for display + a possible
     * future fast model; users can still force the sub. See `SublimationBuilder` for the rationale.
     */
    val zeroesElementalMastery: Boolean = false,
    /** Human-readable effect text (English), for tooltips and CLI/UI display. */
    val rawText: String? = null,
) {
    /** The required socket colours for a normal sublimation (empty for epic/relic). */
    val colors: List<RuneColor>
        get() = slotColorPattern.mapNotNull { code -> runCatching { RuneColor.fromCode(code) }.getOrNull() }
}
