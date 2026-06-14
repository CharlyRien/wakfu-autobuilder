package me.chosante.common

import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Conversions between Wakfu's two resistance representations:
 *  - **flat** resistance — the raw stat a monster carries and that a debuff adds to / removes from
 *    (e.g. "-100 Elemental Resistance"); the source of truth in the bestiary and on spell pages;
 *  - **percent** resistance — the damage-reduction factor the formula uses: `dmg ×= (1 − res%/100)`.
 *
 * The mapping is non-linear (`res% = 100·(1 − 0.8^(flat/100))`, capped at +90%), which is exactly why
 * resistance **debuffs must be applied in flat**: removing 100 flat from a lightly-resistant target is a
 * small % swing, but from a heavily-resistant target it's large. Working in % would mis-value them.
 */
object Resistances {
    const val MAX_PERCENT = 90

    /** Flat → effective percent (signed; negative flat = a weakness = negative %). Capped at +90%. */
    fun flatToPercent(flat: Int): Int =
        ((1.0 - 0.8.pow(flat / 100.0)) * 100.0)
            .let { if (it < 0) kotlin.math.ceil(it) else kotlin.math.floor(it) }
            .toInt()
            .coerceAtMost(MAX_PERCENT)

    /**
     * Percent → flat (the inverse of [flatToPercent]). Used to recover the boss's flat resistance from a
     * percent-typed scenario so flat debuffs can be applied to it. Percent is clamped below 100 (a 100%
     * reduction has no finite flat).
     */
    fun percentToFlat(percent: Int): Int {
        val p = percent.coerceAtMost(99)
        return (100.0 * ln(1.0 - p / 100.0) / ln(0.8)).roundToInt()
    }
}
