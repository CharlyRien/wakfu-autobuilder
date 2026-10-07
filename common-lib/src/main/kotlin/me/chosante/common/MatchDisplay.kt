package me.chosante.common

import java.math.BigDecimal

private val FULL_MATCH = BigDecimal(100)

/**
 * A precision "% match" as the player reads it: a whole percent, capped at 100. Once a build meets every target the engine
 * keeps scoring how far it overshoots them (so the search still prefers the better of two builds that both meet them), and that
 * raw score can exceed 100. The raw value stays in the live match and in the saved
 * entry (it is what orders builds); only what is displayed is capped.
 */
fun BigDecimal.displayedMatchPercent(): Int = if (this >= FULL_MATCH) 100 else toInt().coerceAtLeast(0)

/** True once the match reaches 100: every requested target is met (the figure above 100 only ranks overshoot). */
fun BigDecimal.meetsAllTargets(): Boolean = this >= FULL_MATCH

/** [displayedMatchPercent] for a saved match. */
fun Double.displayedMatchPercent(): Int = if (this >= FULL_MATCH.toDouble()) 100 else toInt().coerceAtLeast(0)

/** [meetsAllTargets] for a saved match. */
fun Double.meetsAllTargets(): Boolean = this >= FULL_MATCH.toDouble()
