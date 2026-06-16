package me.chosante.autobuilder.domain

import me.chosante.common.Monster
import me.chosante.common.Resistances
import java.math.BigDecimal
import java.math.RoundingMode

// Boss-mode glue: turns a Monster's flat per-element resistances into a DamageScenario for the
// max-damage engine. A boss carries a four-element resistance profile, so [againstAllElements] fills
// [DamageScenario.elementResistances] and the fused boss-aware objective ([DamageScenario.candidateElements]
// + max over elements) auto-picks the best playable element jointly with the gear — no per-element
// orchestration needed (the old BossSearch wrapper is gone).

/**
 * Converts Ankama's **flat** elemental resistance to the **percentage** the damage formula uses
 * (`Res% = 100·(1 − 0.8^(flat/100))`, capped at +90%). A negative flat resistance is an elemental
 * **weakness** and yields a negative percentage that legitimately increases damage — kept signed so
 * element auto-selection favours the boss's weakness. Delegates to [Resistances.flatToPercent], the
 * single source of truth shared with the rotation/debuff sequencing, so the resistance shown for a boss
 * and the one its rotation is actually scored at agree exactly (including on weaknesses).
 */
fun flatResistanceToPercent(flat: Int): Int = Resistances.flatToPercent(flat)

/** The monster's flat resistance against [element] (signed; negative = weakness). */
fun Monster.flatResistance(element: SpellElement): Int =
    when (element) {
        SpellElement.FIRE -> fireResistance
        SpellElement.WATER -> waterResistance
        SpellElement.EARTH -> earthResistance
        SpellElement.AIR -> airResistance
    }

/** The monster's effective resistance percentage against [element] (signed). */
fun Monster.resistancePercent(element: SpellElement): Int = flatResistanceToPercent(flatResistance(element))

/**
 * This scenario re-aimed at [monster] with [element]: keeps the orientation / range / shape / berserk /
 * heal / crit-cap / base-damage the user chose, and fills [DamageScenario.element] and
 * [DamageScenario.targetResistancePercent] from the monster's profile.
 */
fun DamageScenario.against(
    monster: Monster,
    element: SpellElement,
): DamageScenario =
    copy(
        element = element,
        targetResistancePercent = monster.resistancePercent(element)
    )

/**
 * This scenario aimed at [monster] across **all four elements**: fills [DamageScenario.elementResistances]
 * from the monster's profile so the boss-aware objective ([DamageScenario.candidateElements]) optimizes
 * over every element and auto-picks the best one the class can actually play (jointly with the gear). Use
 * this for "auto" element mode; use [against] to force a single element.
 */
fun DamageScenario.againstAllElements(monster: Monster): DamageScenario = copy(elementResistances = SpellElement.entries.associateWith { monster.resistancePercent(it) })

/**
 * Display helpers for boss mode. **Not used by the optimizer**: the optimal build is independent of boss
 * HP and difficulty (only resistance is a build-dependent term in the damage formula), so these only
 * shape the informational "turns to kill" figure — see docs/BOSS_MODE_RESEARCH.md.
 */
object BossDisplay {
    /** The boss's HP scaled by the difficulty [hpMultiplier] (clamped non-negative). */
    fun effectiveHp(
        monster: Monster,
        hpMultiplier: Double,
    ): Long = (monster.hp.toDouble() * hpMultiplier.coerceAtLeast(0.0)).toLong()

    /**
     * Number of **turns** of [expectedDamagePerTurn] needed to deplete the boss's HP (≥ 1). The max-damage
     * engine now produces a full per-turn rotation, so this is "turns to kill" (per-turn damage), not the
     * old single-hit "hits to kill". [hpMultiplier] models dungeon difficulty (HP scaling only).
     */
    fun turnsToKill(
        monster: Monster,
        expectedDamagePerTurn: BigDecimal,
        hpMultiplier: Double = 1.0,
    ): Int {
        if (expectedDamagePerTurn <= BigDecimal.ZERO) return 0
        return effectiveHp(monster, hpMultiplier)
            .toBigDecimal()
            .divide(expectedDamagePerTurn, 0, RoundingMode.CEILING)
            .toInt()
            .coerceAtLeast(1)
    }
}
