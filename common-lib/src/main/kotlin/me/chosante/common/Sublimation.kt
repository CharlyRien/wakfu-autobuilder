package me.chosante.common

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer

@Serializable
data class SublimationShard(
    val itemId: Int,
    val name: I18nText,
)

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
     * `MASTERY_CRITICAL`. (The 2-argument `GetCharac("CRITICAL_BONUS", "target")` form is instead one of the six
     * atoms of [SECONDARY_MASTERIES_AT_MOST]; the arity disambiguates them.)
     */
    CRITICAL_MASTERY_AT_MOST,
    BLOCK_AT_LEAST,
    RANGE_AT_MOST,
    RANGE_AT_LEAST,
    RANGE_EXACT,
    DODGE_LT_PCT_OF_LEVEL,

    /**
     * "Each secondary mastery ≤ N" (the Neutralité family: Neutrality, Ambition, Inflexibility, Pretension, …): holds
     * iff EVERY one of the six [SECONDARY_MASTERY_CHARACTERISTICS] is at most [SublimationCondition.value] on its own —
     * NOT their sum, so a positive mastery is never offset by a negative one. Decoded from an `and`-chain of exactly six
     * `GetCharac("<token>", "target") <= N` atoms with one threshold (melee `MELEE_DMG`, distance `RANGED_DMG`, berserk
     * `BERSERK_DMG`, critical `CRITICAL_BONUS`, rear `BACKSTAB_BONUS`, healing `HEAL_IN_PERCENT`); the extractor fails
     * on any other shape.
     */
    SECONDARY_MASTERIES_AT_MOST,

    /**
     * "Healing Mastery ≤ N" — Engagement's lone `GetCharac("HEAL_IN_PERCENT", "caster") <= N` criterion (healing mastery
     * ONLY, not the six [SECONDARY_MASTERIES_AT_MOST] read). Display / decode only: no solver-choosable sub carries it
     * (Engagement's "+30 % heals performed" is not a modelled stat), so the solver does not model it — like every type
     * outside `SUPPORTED_SUB_CONDITIONS`, a FORCED carrier's effects would apply unconditionally.
     */
    HEALING_MASTERY_AT_MOST,
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
 * condition (e.g. Neutrality / Ambition: "if each secondary mastery ≤ 0") holds iff **each** of these is at most
 * the threshold on its own — the game's criterion is an `and` of six per-stat comparisons, so (unlike a sum) a
 * positive mastery can NOT be offset by a negative one (+76 distance and −304 rear break it). Single source of
 * truth so the CP-SAT solver, the re-scorer and the extractor classifier stay in lockstep — omitting any of them
 * (the solver historically read only melee+distance) makes the condition spuriously hold for a rear/crit-stacking
 * build and hands it the bonus for free.
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
 * One effect of a sublimation, at its max level (best-achievable model). A sealed hierarchy so each way Ankama
 * can grant a bonus is its own shape (no nullable-field soup). The cross-cutting fields ([scenarioGate],
 * [appliesBeforeCombat]) live on the base interface; the **stat-granting** shapes additionally share
 * [StatEffect] ([characteristic] + [magnitudeAtLevel]) so the solver/re-scorer hot paths can iterate them
 * variant-agnostically, while the **structured** shapes ([Conversion] / [PerStatStep] / [BestElementConcentration])
 * are read through their dedicated [Sublimation] accessors ([Sublimation.conversion] / [Sublimation.perStatStep] /
 * [Sublimation.bestElementConcentration]). Serialized polymorphically (a `type` discriminator: `flat` /
 * `percentOfLevel` / `conversion` / `perStatStep` / `bestElementConcentration`).
 */
@Serializable
sealed interface SublimationEffect {
    val scenarioGate: ScenarioGate?

    /**
     * True iff this is a **permanent**, out-of-combat effect — it shows on the character sheet, so it is present
     * BEFORE combat starts. Only permanent effects feed build-static start-of-combat conditions: a permanent
     * +crit (Influence II) counts toward another sub's [SublimationConditionType.CRIT_AT_MOST], but a
     * start-of-combat / conditional +crit (Secondary Devastation II, Ambition) does **not** — it is applied
     * *at* combat start, after the condition is read, and a conditional sub must never feed its own condition.
     * Decoded from the StaticEffect timing (`duration_base == -1`, not `ends_at_end_of_turn`, no condition on the
     * sub, and no `triggers_*` on the effect or any ancestor group). Default `false` = treated as start-of-combat
     * (never fed to a condition), so an unclassified effect can never spuriously satisfy one.
     */
    val appliesBeforeCombat: Boolean

    /**
     * A stat-granting effect: contributes a flat magnitude of a single [characteristic]. Its two shapes are a
     * plain [Flat] amount or a level-scaled [PercentOfLevel]; consumers that only need the magnitude call
     * [magnitudeAtLevel] (so the solver/re-scorer hot paths stay variant-agnostic) while display code `when`s
     * over the concrete type. The build's *structured* effects (conversion / ramp / best-element) are NOT
     * [StatEffect]s — iterating for stat contributions must therefore filter to this type.
     */
    @Serializable
    sealed interface StatEffect : SublimationEffect {
        val characteristic: Characteristic

        /** Raw flat amounts or percentages, indexed by family level minus one; empty in legacy saves. */
        val valuesByLevel: List<Int>

        /** The modeled flat magnitude for a character of [level]: [Flat.value], or `floor(percent · level / 100)`. */
        fun magnitudeAtLevel(level: Int): Int
    }

    /** A plain flat stat contribution (the common case). */
    @Serializable
    @SerialName("flat")
    data class Flat(
        override val characteristic: Characteristic,
        val value: Int,
        override val scenarioGate: ScenarioGate? = null,
        override val appliesBeforeCombat: Boolean = false,
        override val valuesByLevel: List<Int> = emptyList(),
    ) : StatEffect {
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
        override val valuesByLevel: List<Int> = emptyList(),
    ) : StatEffect {
        override fun magnitudeAtLevel(level: Int): Int = percentOfLevelMagnitude(percentOfLevel, level)
    }

    /**
     * A [SublimationKind.CONVERSION] payload: move [percent]% of [from] into [to] at start of combat. Read via
     * [Sublimation.conversion]; the solver models it as a reified `moved` term, so it never goes through
     * [StatEffect.magnitudeAtLevel] (it is not a [StatEffect]).
     */
    @Serializable
    @SerialName("conversion")
    data class Conversion(
        val from: Characteristic,
        val to: Characteristic,
        val percent: Int,
        override val scenarioGate: ScenarioGate? = null,
        override val appliesBeforeCombat: Boolean = false,
    ) : SublimationEffect

    /**
     * A build-static ramp bonus: at start of combat grant [target] equal to
     * `clamp(perStep · max(0, source − threshold), 0, cap)`, where [source] is one of the build's OWN stats
     * (Featherweight: `source=MOVEMENT_POINT, threshold=4, perStep=6, cap=24, target=DAMAGE_INFLICTED` →
     * "+6% Damage Inflicted per MP above 4, max +24"). Decoded first-party from Ankama's action-913 linear-ramp
     * formula — the [source] id read from the client's characteristic enum, slope/intercept from the effect
     * params, the [cap] from the child action-126. Its magnitude depends on a build variable, so the solver
     * models it as a reified clamped term and the re-scorer applies [contribution] AFTER the build's stats (incl.
     * [source]) are totalled — it never goes through the level-only [StatEffect.magnitudeAtLevel]. Read via
     * [Sublimation.perStatStep].
     */
    @Serializable
    @SerialName("perStatStep")
    data class PerStatStep(
        val source: Characteristic,
        val threshold: Int,
        val perStep: Int,
        val cap: Int,
        val target: Characteristic,
        override val scenarioGate: ScenarioGate? = null,
        override val appliesBeforeCombat: Boolean = false,
    ) : SublimationEffect {
        /** The [target] magnitude for a build whose [source] stat totals [sourceValue]. */
        fun contribution(sourceValue: Int): Int = (perStep * maxOf(0, sourceValue - threshold)).coerceIn(0, cap)
    }

    /**
     * The "best element" concentration bonus (Elemental Concentration): at start of combat, grant
     * +[damageInflictedBonus]% Damage Inflicted **and** −[masteryPenaltyPercent]% Elemental Mastery on every
     * element except your strongest one ("the 3 weakest elements"). Read via [Sublimation.bestElementConcentration].
     *
     * **Sound damage-mode model:** credit only the +[damageInflictedBonus]% DI, gated so the scenario / requested
     * damage element is the build's **strongest** element. This is not just optimistic — it is *exact*: when the
     * damage element is NOT the strongest, the −[masteryPenaltyPercent]% penalty lands on it, and (with a 20/30
     * split) `0.70 · 1.20 = 0.84 < 1` makes the sublimation strictly worse than not taking it, so such a build is
     * never the optimum. The strongest-element guard therefore excludes only dominated builds while never
     * over-crediting the DI (the un-modeled penalty can never apply to the element whose damage we score). Only
     * meaningful where there is a single damage element to protect (max-damage; mono-element most-masteries) —
     * elsewhere it stays forced-input-only.
     */
    @Serializable
    @SerialName("bestElementConcentration")
    data class BestElementConcentration(
        val damageInflictedBonus: Int,
        val masteryPenaltyPercent: Int,
        override val scenarioGate: ScenarioGate? = null,
        override val appliesBeforeCombat: Boolean = false,
    ) : SublimationEffect
}

/**
 * Back-compat serializer for [SublimationEffect]. It encodes with the normal polymorphic `type` discriminator —
 * so `sublimations.json` and freshly-saved builds keep their discriminators — but on **decode defaults a
 * discriminator-less object to [SublimationEffect.Flat]**. Builds saved before [SublimationEffect] became a sealed
 * hierarchy stored each effect as a plain `{ "characteristic": …, "value": … }` with no `type`; without this those
 * objects fail to decode, and the build-history / clipboard loader silently drops the whole saved build. Injecting
 * the missing discriminator lets them load as the flat effects they always were.
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

/**
 * A Wakfu sublimation, keyed by its in-game `stateId` (the stable join key — 467 item rows collapse to
 * 232 distinct effects). Effect data is decoded **first-party** from the local client's State (67) →
 * StaticEffect (68) tables by `bdata-extractor` (`SublimationBuilder`); identity/name/rarity/slot colours
 * come from the CDN `items.json` (itemTypeId 812). No third-party (WakForge) dump is used.
 *
 * Best-achievable model: a chosen sublimation contributes its top item-tier [effects] — one ordered list holding
 * every effect shape (flat / %-of-level stat contributions plus the structured conversion / ramp / best-element
 * bonuses). The real item tier is [maxTier] (the I/II/III grant carried by the sublimation item); [maxStackLevel]
 * is the bdata State table's stacking level cap (drives [maxCopies]). The three
 * structured shapes are surfaced by the [conversion] / [perStatStep] / [bestElementConcentration] accessors so
 * the solver and re-scorer can special-case them.
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
    /**
     * The sublimation family's **stack cap** — the max total socketed level (State table `max_level`, usually
     * 6, sometimes 4/2). Each socketed shard adds its [maxTier] levels; `floor(maxStackLevel / maxTier)` full
     * copies fit (see [maxCopies]). NOT the item tier a user buys/sockets. Serialized as `maxLevel` for
     * back-compat with the baked `sublimations.json` + saved builds.
     */
    @SerialName("maxLevel")
    val maxStackLevel: Int = 1,
    /** The real best-achievable item tier (I/II/III), sourced from the sublimation item apply-state level grant. */
    val maxTier: Int = 1,
    /** Exact CDN shard identities for each socketable tier. */
    val shardsByTier: Map<Int, SublimationShard> = emptyMap(),
    /** Structured effects deliberately stay single-shard at maxTier. */
    val stackModelled: Boolean = true,
    /** Selected family level on a socketed result; null in catalogs and legacy saves. */
    val stackLevel: Int? = null,
    /** The actual shard tier socketed on this carrier. */
    val socketTier: Int? = null,
    /**
     * Whether socketing this sublimation multiple times ACCUMULATES (Wakfu's `is_cumulable`). A cumulable normal
     * sub can be stacked up to [maxCopies] copies, each on its own ≥3-socket carrier, its effects scaling k×.
     * Decoded by `bdata-extractor` into `sublimation-stacking.json` and joined onto the runtime sub at load
     * (see `WakfuBestBuildFinderAlgorithm.sublimations`). Default false ⇒ an un-joined sub is single-copy.
     */
    val cumulable: Boolean = false,
    val kind: SublimationKind,
    /** True only when the solver can correctly model every effect of this sub (FLAT/STATIC/CONVERSION). */
    val solverChoosable: Boolean = false,
    val condition: SublimationCondition? = null,
    val effects: List<
        @Serializable(with = SublimationEffectLenientSerializer::class)
        SublimationEffect
    > = emptyList(),
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

    /** Shards needed to reach the family cap, including a partial final shard. Structured effects stay single-copy. */
    val maxCopies: Int
        get() = if (stacksByLevel) (maxStackLevel + maxTier.coerceAtLeast(1) - 1) / maxTier.coerceAtLeast(1) else 1

    val stacksByLevel: Boolean
        get() =
            rarity == SublimationRarity.NORMAL &&
                cumulable &&
                stackModelled &&
                conversion == null &&
                perStatStep == null &&
                bestElementConcentration == null &&
                !zeroesElementalMastery

    /** Cap normal shard tiers by actual availability; epic/relic generation filters remain name-based. */
    fun atTierLimit(limit: Int?): Sublimation? {
        if (limit == null || rarity != SublimationRarity.NORMAL || !stackModelled || shardsByTier.isEmpty()) return this
        val tier = availableTiers(limit).maxOrNull() ?: return null
        return if (tier == maxTier) this else copy(maxTier = tier)
    }

    /** Numeric family-level value. Legacy/synthetic effects retain their full-shard proportional model. */
    fun magnitudeAtStackLevel(
        effect: SublimationEffect.StatEffect,
        familyLevel: Int,
        characterLevel: Int,
    ): Int {
        if (familyLevel == 0) return 0
        if (effect.valuesByLevel.isEmpty()) return effect.magnitudeAtLevel(characterLevel) * familyLevel / maxTier.coerceAtLeast(1)
        val raw = effect.valuesByLevel[familyLevel.coerceAtMost(effect.valuesByLevel.size) - 1]
        return when (effect) {
            is SublimationEffect.Flat -> raw
            is SublimationEffect.PercentOfLevel -> percentOfLevelMagnitude(raw, characterLevel)
        }
    }

    fun marginalMagnitude(
        effect: SublimationEffect.StatEffect,
        shard: Int,
        characterLevel: Int,
        chosenLevel: Int? = null,
    ): Int {
        if (!stacksByLevel && chosenLevel == null) return effect.magnitudeAtLevel(characterLevel)
        val cap = chosenLevel ?: maxStackLevel
        val from = ((shard - 1) * maxTier).coerceAtMost(cap)
        val to = (shard * maxTier).coerceAtMost(cap)
        return magnitudeAtStackLevel(effect, to, characterLevel) - magnitudeAtStackLevel(effect, from, characterLevel)
    }

    /** Certificate units may be taken out of order: this relaxes the prefix and credits at least every real k-shard build. */
    fun marginalUnit(
        shard: Int,
        characterLevel: Int,
    ): Sublimation =
        copy(
            cumulable = false,
            stackModelled = false,
            stackLevel = (shard * maxTier).coerceAtMost(maxStackLevel),
            effects =
                effects.map { effect ->
                    if (effect is SublimationEffect.StatEffect) {
                        SublimationEffect.Flat(
                            effect.characteristic,
                            marginalMagnitude(effect, shard, characterLevel),
                            effect.scenarioGate,
                            effect.appliesBeforeCombat
                        )
                    } else {
                        effect
                    }
                }
        )

    fun certificateUnits(characterLevel: Int): List<Sublimation> = if (maxCopies == 1) listOf(this) else (1..maxCopies).map { marginalUnit(it, characterLevel) }

    /** The actual CDN tiers allowed by the request; synthetic fixtures fall back to 1..maxTier. */
    fun availableTiers(tierLimit: Int? = null): List<Int> =
        (if (shardsByTier.isEmpty()) (1..maxTier).toList() else shardsByTier.keys.toList())
            .filter {
                it <=
                    (tierLimit ?: maxTier)
            }.sortedDescending()

    /** Sums of available shards, with over-cap sums represented by the cap. */
    fun reachableLevels(tierLimit: Int? = null): List<Int> {
        if (!stacksByLevel) return availableTiers(tierLimit).map { it.coerceAtMost(maxStackLevel) }.distinct().sorted()
        val tiers = availableTiers(tierLimit)
        val reachable = BooleanArray(maxStackLevel + 1)
        reachable[0] = true
        for (level in 0 until maxStackLevel) if (reachable[level]) for (tier in tiers) reachable[(level + tier).coerceAtMost(maxStackLevel)] = true
        return (1..maxStackLevel).filter { reachable[it] }
    }

    /** Fewest actual shards for an exact level (the cap also permits overshoot), stable highest-tier-first ties. */
    fun shardPlan(
        level: Int,
        tierLimit: Int? = null,
    ): List<Int> {
        val tiers = availableTiers(tierLimit)
        require(level in reachableLevels(tierLimit)) { "${name.fr}: level $level is unavailable; choose ${reachableLevels(tierLimit).joinToString()}" }
        if (!stacksByLevel) return listOf(tiers.last { it.coerceAtMost(maxStackLevel) == level })
        val plans = arrayOfNulls<List<Int>>(maxStackLevel + 1)
        plans[0] = emptyList()
        for (at in 0 until maxStackLevel) {
            val plan = plans[at] ?: continue
            for (tier in tiers) {
                val next = (at + tier).coerceAtMost(maxStackLevel)
                val candidate = (plan + tier).sortedDescending()
                if (plans[next] == null ||
                    (candidate.size < plans[next]!!.size || candidate.size == plans[next]!!.size && candidate.sum() < plans[next]!!.sum())
                ) {
                    plans[next] = candidate
                }
            }
        }
        return requireNotNull(plans[level])
    }

    /** Socketable identities plus resolved marginal values; saved per carrier so scorers and Zenith read the same stack. */
    fun socketedShards(
        copies: Int,
        characterLevel: Int,
        chosenLevel: Int? = null,
    ): List<Sublimation> {
        if (copies == 0) return emptyList()
        if (!stacksByLevel) return List(copies) { this }
        // Keep legacy full-shard fixtures stable, but resolve partial and per-level synthetic families too.
        if (shardsByTier.isEmpty() &&
            copies * maxTier <= maxStackLevel &&
            effects.filterIsInstance<SublimationEffect.StatEffect>().all { it.valuesByLevel.isEmpty() }
        ) {
            return List(copies) { this }
        }
        val level = chosenLevel ?: (copies * maxTier).coerceAtMost(maxStackLevel)
        val tiers =
            if (chosenLevel != null) {
                shardPlan(level, maxTier)
            } else {
                val remainder = level - (copies - 1) * maxTier
                List(copies - 1) { maxTier } + (availableTiers().firstOrNull { it == remainder } ?: maxTier)
            }
        var from = 0
        return tiers.map { tier ->
            val to = (from + tier).coerceAtMost(level)
            val shard = if (shardsByTier.isEmpty()) SublimationShard(zenithId, name) else requireNotNull(shardsByTier[tier]) { "Missing CDN shard id: ${name.fr} tier $tier" }
            val resolved =
                effects.map { effect ->
                    if (effect is SublimationEffect.StatEffect) {
                        SublimationEffect.Flat(
                            effect.characteristic,
                            magnitudeAtStackLevel(effect, to, characterLevel) - magnitudeAtStackLevel(effect, from, characterLevel),
                            effect.scenarioGate,
                            effect.appliesBeforeCombat
                        )
                    } else {
                        effect
                    }
                }
            from = to
            copy(name = shard.name, zenithId = shard.itemId, socketTier = tier, stackLevel = level, effects = resolved)
        }
    }

    /**
     * The sublimation's **generation tier** (1/2/3), read from the trailing roman numeral of its name —
     * the number Ankama shows players and what a user means by "Mesure III is tier 3". A base name with no
     * numeral (e.g. `Mesure`, `Furie`) or an explicit ` I` is tier 1; ` II` → 2; ` III` → 3. This is the
     * ONLY place the generation lives in the game data — the CDN item carries no numeric field for it
     * (item `level` and `rarity` are constant across a family; the I/II/III entries are distinct items with
     * distinct state ids). Distinct from [maxTier], which is the shard's upgrade level driving the effect
     * VALUE (`floor(base + inc·maxTier)`) — every epic is [maxTier] 1 regardless of its generation.
     */
    val nameTier: Int
        get() =
            when (name.fr.trim().substringAfterLast(' ')) {
                "III" -> 3
                "II" -> 2
                else -> 1 // "I" or no trailing numeral
            }

    /**
     * The [SublimationEffect.Conversion] this sub carries (Unraveling), or null — the crit-mastery → elemental
     * conversion the solver models via a reified `moved` term. At most one is decoded per sub.
     */
    val conversion: SublimationEffect.Conversion?
        get() = effects.firstNotNullOfOrNull { it as? SublimationEffect.Conversion }

    /**
     * The build-static per-stat-step ramp this sub carries (Featherweight: +6% Damage Inflicted per MP above 4,
     * max +24), or null. Applied specially by the solver/scorer because its value depends on the build's own
     * [SublimationEffect.PerStatStep.source] stat — not on level, so it cannot go through
     * [SublimationEffect.StatEffect.magnitudeAtLevel].
     */
    val perStatStep: SublimationEffect.PerStatStep?
        get() = effects.firstNotNullOfOrNull { it as? SublimationEffect.PerStatStep }

    /**
     * The "best element" concentration bonus this sub carries (Elemental Concentration: +20% Damage Inflicted,
     * −30% mastery on the three weakest elements), or null. Modeled soundly in the damage modes as a
     * scenario-element-strongest-gated DI bonus — see [SublimationEffect.BestElementConcentration].
     */
    val bestElementConcentration: SublimationEffect.BestElementConcentration?
        get() = effects.firstNotNullOfOrNull { it as? SublimationEffect.BestElementConcentration }
}

/**
 * Back-compat serializer for a [Sublimation] embedded in an **older saved build** (build-history / clipboard).
 * Before the structured effects were folded into [Sublimation.effects] (B4), a saved sub carried them as three
 * top-level fields (`conversion` / `perStatStep` / `bestElementConcentration`). This folds any such legacy field
 * into [Sublimation.effects] with the matching `type` discriminator on decode, so a pre-B4 saved build keeps its
 * conversion / ramp / best-element effect instead of silently dropping it under `ignoreUnknownKeys`. New data
 * already stores them inside `effects`, so this is a no-op for it. Encodes unchanged (delegates straight through).
 */
object SublimationLegacyFieldsSerializer :
    JsonTransformingSerializer<Sublimation>(Sublimation.serializer()) {
    private val LEGACY_FIELD_TO_TYPE =
        mapOf(
            "conversion" to "conversion",
            "perStatStep" to "perStatStep",
            "bestElementConcentration" to "bestElementConcentration"
        )

    override fun transformDeserialize(element: JsonElement): JsonElement {
        if (element !is JsonObject) return element
        val legacy = LEGACY_FIELD_TO_TYPE.keys.filter { it in element }
        if (legacy.isEmpty()) return element
        val existing = (element["effects"] as? JsonArray)?.toList() ?: emptyList()
        val folded =
            legacy.mapNotNull { field ->
                (element[field] as? JsonObject)?.let { payload ->
                    JsonObject(payload + ("type" to JsonPrimitive(LEGACY_FIELD_TO_TYPE.getValue(field))))
                }
            }
        return JsonObject(
            (element - LEGACY_FIELD_TO_TYPE.keys) + ("effects" to JsonArray(existing + folded))
        )
    }
}
