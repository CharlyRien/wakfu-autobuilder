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
 * hit   = Base × (1 + ΣMastery/100) × (1 + ΣDamageInflicted/100) × (1 − Res%/100)
 * crit  = Base_crit × 1.25 × (1 + (ΣMastery + criticalMastery)/100) × (1 + ΣDI/100) × (1 − Res%/100)
 * E[hit] = (1 − p)·hit + p·crit                       // p = usable crit rate
 * ```
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
     * @param targetResistancePercent target's effective elemental resistance % (signed; negative =
     *   weakness), clamped to ≤ [MAX_RESISTANCE_PERCENT]. A flat multiplier — does not change relative
     *   build ranking, only the displayed number.
     * @param critCapPercent upper bound on the usable crit rate (e.g. 100 to allow full crit).
     */
    fun expectedDamage(
        spell: Spell,
        stats: Map<Characteristic, Int>,
        rangeBand: RangeBand? = null,
        rearMastery: Boolean = false,
        berserkMastery: Boolean = false,
        targetResistancePercent: Int = 0,
        critCapPercent: Int = 100,
    ): Result? {
        val element = spell.element ?: return null
        val base = spell.baseDamage ?: return null
        // Crit base falls back to the normal base when the page didn't expose a separate crit hit.
        val baseCrit = spell.critDamage ?: base

        fun v(c: Characteristic): Int = stats[c] ?: 0

        var mastery = v(element.masteryCharacteristic) + v(Characteristic.MASTERY_ELEMENTARY)
        rangeBand?.let { mastery += v(it.masteryCharacteristic) }
        if (rearMastery) mastery += v(Characteristic.MASTERY_BACK)
        if (berserkMastery) mastery += v(Characteristic.MASTERY_BERSERK)

        val critMastery = v(Characteristic.MASTERY_CRITICAL)
        val damageInflicted = max(v(Characteristic.DAMAGE_INFLICTED), -DAMAGE_INFLICTED_FLOOR)
        val critRate =
            v(Characteristic.CRITICAL_HIT).coerceIn(0, 100).coerceAtMost(critCapPercent) / 100.0

        val resistanceFactor =
            1.0 - targetResistancePercent.coerceAtMost(MAX_RESISTANCE_PERCENT) / 100.0
        val diFactor = 1.0 + damageInflicted / 100.0

        val nonCrit = base * (1.0 + mastery / 100.0) * diFactor * resistanceFactor
        val crit = baseCrit * CRIT_MULTIPLIER * (1.0 + (mastery + critMastery) / 100.0) * diFactor * resistanceFactor
        val expected = (1.0 - critRate) * nonCrit + critRate * crit
        return Result(expected = expected, nonCrit = nonCrit, crit = crit)
    }
}
