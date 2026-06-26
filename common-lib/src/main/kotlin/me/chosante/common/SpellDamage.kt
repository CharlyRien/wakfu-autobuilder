package me.chosante.common

import kotlin.math.max

/**
 * Reusable, self-contained computation of a [Spell]'s **expected in-game damage for a given build**.
 *
 * This is the bridge the comparison view needs: feed it a spell and the build's *resolved*
 * characteristic totals (the masteries / damage-inflicted / crit the build achieves) and it returns
 * the expected hit, using Wakfu's exact single-hit formula:
 *
 * ```
 * hit   = Base × (1 + ΣMastery/100) × (1 + ΣDamageInflicted/100) × (1 − Res%/100) × Orientation
 * crit  = Base_crit × (1 + (ΣMastery + criticalMastery)/100) × (1 + ΣDI/100) × (1 − Res%/100) × Orientation
 * E[hit] = (1 − p)·hit + p·crit                       // p = usable crit rate
 * ```
 *
 * `Base_crit` is the spell's critical-hit base, which ALREADY includes Wakfu's global +25% crit bonus
 * (the bdata crit scaling is the normal scaling × 1.25). It is NOT multiplied by 1.25 again. When a spell
 * exposes no separate crit base, `Base_crit` falls back to `Base × 1.25`.
 *
 * `ΣMastery` is the element's mastery (with the generic "+all elements" `MASTERY_ELEMENTARY` folded
 * in) plus the optional secondary masteries the caller says apply ([rangeBand], rear, berserk). It is
 * deliberately **decoupled** from the search engine so both the GUI and a future engine pass can call
 * it without pulling in OR-Tools — it mirrors `FindMaxDamageScoring.expectedDamage` on the
 * `feat/max-damage-mode` branch.
 *
 * Returns `null` when the spell has no readable base hit ([Spell.hasDamage] is false) — never a made-up
 * number.
 */
object SpellDamage {
    const val MAX_RESISTANCE_PERCENT = 90
    private const val DAMAGE_INFLICTED_FLOOR = 50
    private const val CRIT_MULTIPLIER = 1.25

    /** Which positional secondary mastery applies to the hit, if the caller wants to model one. */
    enum class RangeBand(
        val masteryCharacteristic: Characteristic,
    ) {
        MELEE(Characteristic.MASTERY_MELEE),
        DISTANCE(Characteristic.MASTERY_DISTANCE),
    }

    /** Expected / non-crit / crit components of a single hit. */
    data class Result(
        val expected: Double,
        val nonCrit: Double,
        val crit: Double,
    )

    /**
     * Expected damage of one cast of [spell] for a build whose resolved totals are [stats].
     *
     * @param stats the build's achieved characteristics (elemental masteries, [Characteristic.DAMAGE_INFLICTED],
     *   [Characteristic.MASTERY_CRITICAL], [Characteristic.CRITICAL_HIT], …). Missing keys read as 0.
     * @param rangeBand secondary mastery to fold in (melee/distance), or `null` to ignore it.
     * @param rearMastery include [Characteristic.MASTERY_BACK] (a back hit).
     * @param berserkMastery include [Characteristic.MASTERY_BERSERK] (caster at/below 50% HP).
     * @param orientationMultiplierPercent the positional damage multiplier as a percent (100 = face, 110 =
     *   side, 125 = back). In Wakfu a hit from behind deals ×1.25 *and* grants rear mastery, so a back-hit
     *   scenario passes `orientationMultiplierPercent = 125` together with `rearMastery = true`. Defaults to
     *   100 (face), leaving existing neutral calls unchanged.
     * @param targetResistancePercent target's effective elemental resistance % (signed; negative =
     *   weakness), clamped to ≤ [MAX_RESISTANCE_PERCENT]. A flat multiplier — does not change relative
     *   build ranking, only the displayed number.
     * @param critCapPercent upper bound on the usable crit rate (e.g. 100 to allow full crit).
     * @param characterLevel the caster's level — the spell's base hit is scaled to it via
     *   [Spell.baseDamageAt] (Wakfu damage scales with caster level). At max level this equals the old
     *   flat number, so existing max-level results are unchanged; lower levels are now correct.
     */
    fun expectedDamage(
        spell: Spell,
        stats: Map<Characteristic, Int>,
        characterLevel: Int,
        rangeBand: RangeBand? = null,
        rearMastery: Boolean = false,
        berserkMastery: Boolean = false,
        orientationMultiplierPercent: Int = 100,
        targetResistancePercent: Int = 0,
        critCapPercent: Int = 100,
    ): Result? {
        val element = spell.element ?: return null
        val base = spell.baseDamageAt(characterLevel) ?: return null
        // The crit-hit base. The bdata/encyclopedia critical value ALREADY includes the global +25% crit
        // bonus — its bdata scaling is the normal scaling × CRIT_MULTIPLIER (e.g. Blazing Arrow critBase 2.5 =
        // 2.0 × 1.25, critInc 0.30 = 0.24 × 1.25) — so it is used as-is. Only when a spell exposes NO separate
        // crit value do we apply the standard +25% to the normal base.
        val critHitBase = spell.critDamageAt(characterLevel)?.toDouble() ?: (base * CRIT_MULTIPLIER)

        fun v(c: Characteristic): Int = stats[c] ?: 0

        var mastery = v(element.masteryCharacteristic) + v(Characteristic.MASTERY_ELEMENTARY)
        rangeBand?.let { mastery += v(it.masteryCharacteristic) }
        if (rearMastery) mastery += v(Characteristic.MASTERY_BACK)
        if (berserkMastery) mastery += v(Characteristic.MASTERY_BERSERK)

        // Clamp critical mastery at 0 to match the CP-SAT objective (which models it as a >=0 variable),
        // so the engine's score and this rescoring agree for a build with negative critical mastery.
        val critMastery = max(v(Characteristic.MASTERY_CRITICAL), 0)
        val damageInflicted = max(v(Characteristic.DAMAGE_INFLICTED), -DAMAGE_INFLICTED_FLOOR)
        val critRate =
            v(Characteristic.CRITICAL_HIT).coerceIn(0, 100).coerceAtMost(critCapPercent) / 100.0

        // Resistance ∈ [−100, +90]%: the +90 cap and the −100 weakness floor (factor ≤ 2.0) match the
        // CP-SAT objective's bounds, so the engine score and this rescoring agree at the extremes too.
        val resistanceFactor =
            1.0 - targetResistancePercent.coerceIn(-100, MAX_RESISTANCE_PERCENT) / 100.0
        val diFactor = 1.0 + damageInflicted / 100.0
        // Positional multiplier (face 1.0 / side 1.10 / back 1.25), a flat factor on every hit — mirrors
        // FindMaxDamageScoring's orientation factor. Floored at 0 to never flip the sign of a hit.
        val orientationFactor = max(orientationMultiplierPercent, 0) / 100.0

        val nonCrit = base * (1.0 + mastery / 100.0) * diFactor * resistanceFactor * orientationFactor
        // crit hit = critHitBase (already +25%) × mastery factor — NOT × CRIT_MULTIPLIER again (that double-
        // counted the +25%, inflating every crit-build's displayed damage). Expected = blend by the crit rate.
        val crit = critHitBase * (1.0 + (mastery + critMastery) / 100.0) * diFactor * resistanceFactor * orientationFactor
        val expected = (1.0 - critRate) * nonCrit + critRate * crit
        return Result(expected = expected, nonCrit = nonCrit, crit = crit)
    }
}
