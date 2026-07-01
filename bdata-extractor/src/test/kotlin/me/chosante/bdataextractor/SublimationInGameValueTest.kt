package me.chosante.bdataextractor

import kotlinx.serialization.builtins.ListSerializer
import me.chosante.common.Sublimation
import me.chosante.common.SublimationEffect
import me.chosante.common.findRepositoryRoot
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.math.abs

/**
 * Validates the committed `sublimations.json` values against **in-game tooltips** — the authoritative oracle.
 *
 * This replaces the earlier WakForge cross-check: WakForge's `state_data.json` per-level tables show a CONSTANT
 * (level-1) value for low-`max_level` subs (e.g. Heavy Armor reads `5` at every level), which is a WakForge
 * display bug, not the real scaling. Trusting it made us clamp the value formula and ship half-values (Heavy
 * Armor `5` instead of the in-game `10`). The real per-level value is the community formula `floor(base +
 * inc·level)` at `level = maxTier`; this test pins a small snapshot of maintainer-verified in-game tooltip
 * values so that regression can't recur. Reads the committed artifact (no local install) so it runs in CI.
 *
 * Each entry is `stateId -> (name, in-game magnitudes we model)`. We assert every listed magnitude appears among
 * the sub's decoded effect magnitudes (set membership) — catching wrong/halved values and dropped effects,
 * while tolerating effects we additionally carry (e.g. a companion stat). Stats Wakfu shows that our model has
 * no characteristic for (e.g. Inflexibility II's "% heals performed") are simply omitted from the expected set.
 */
class SublimationInGameValueTest {
    private val inGameValues: Map<Int, Pair<String, List<Int>>> =
        mapOf(
            // --- maintainer screenshots (2026-06-29), the low-`maxLevel` subs that pin the formula ---
            7077 to ("Heavy Armor" to listOf(10, 1)), // Lvl 2: 10% damage inflicted, -1 max MP
            8520 to ("Earthbound" to listOf(12)), // Lvl 3: 12% damage inflicted (Earth)
            6009 to ("Berserk Critical" to listOf(15)), // Lvl 3: 15% Critical Hit (HP < 50%)
            7862 to ("Vital Influence" to listOf(12)), // Lvl 3: 12% Critical Hit (HP > 90%)
            7861 to ("Vital Strength" to listOf(1)), // Lvl 2: 1 AP (HP > 90%)
            7860 to ("Vital Agility" to listOf(1)), // Lvl 2: 1 MP (HP > 90%)
            6042 to ("Stupefaction" to listOf(15)), // Lvl 3: 15% Critical Hit + 15% of level as Critical Mastery
            7256 to ("Inflexibility II" to listOf(20)), // 20% damage inflicted if secondary masteries <= 0 (+20% heals: stat not modeled)
            5983 to ("Carnage II" to listOf(45)), // 45% of level as elemental mastery when HP > 90% (optimistic: full HP at start)
            6009 to ("Berserk Critical II" to listOf(15)), // 15% Critical Hit when HP < 50% (berserk gate)
            7862 to ("Vital Influence I" to listOf(12)), // 12% Critical Hit when HP > 90% (optimistic)
            // Per-element damage subs: "+12% <element> damage", modeled as an element-gated DAMAGE_INFLICTED.
            8518 to ("Brûlure I" to listOf(12)), // +12% Fire damage
            8519 to ("Gel I" to listOf(12)), // +12% Water damage
            8520 to ("Tellurisme I" to listOf(12)), // +12% Earth damage (Earthbound)
            8521 to ("Ventilation I" to listOf(12)), // +12% Air damage
            9074 to ("Critical Secret" to listOf(30)), // +30% Critical Hit when Critical Mastery <= 0 (new CRITICAL_MASTERY_AT_MOST condition)
            // --- maintainer-confirmed value (no screenshot) ---
            6005 to ("Swiftness II" to listOf(10)), // -10% damage inflicted
            // --- high-`maxLevel` anchors (the original value-bug fix; WakForge is reliable when it scales) ---
            6026 to ("Influence II" to listOf(9)), // 3 per level, tier 3 -> 9 Critical Hit
            6821 to ("Light Weapons Expert" to listOf(75)) // 25% of level per tier, tier 3 -> 75%
        )

    private fun magnitudes(sub: Sublimation): Set<Int> =
        sub.effects
            .map { e ->
                when (e) {
                    is SublimationEffect.Flat -> abs(e.value)
                    is SublimationEffect.PercentOfLevel -> abs(e.percentOfLevel)
                }
            }.toSet()

    @Test
    fun `committed sublimation values match in-game tooltips`() {
        val byId =
            LENIENT_JSON
                .decodeFromString(
                    ListSerializer(Sublimation.serializer()),
                    File(findRepositoryRoot(), "autobuilder/src/main/resources/sublimations.json").readText()
                ).associateBy { it.stateId }

        val failures = ArrayList<String>()
        for ((stateId, expected) in inGameValues) {
            val (name, values) = expected
            val sub = byId[stateId]
            if (sub == null) {
                failures.add("$stateId $name: absent from sublimations.json")
                continue
            }
            val decoded = magnitudes(sub)
            val missing = values.filter { it !in decoded }
            if (missing.isNotEmpty()) {
                failures.add("$stateId $name: in-game $values not all present — missing $missing, decoded=$decoded")
            }
        }

        assertTrue(
            failures.isEmpty(),
            "decoded sublimation values diverge from in-game tooltips:\n" + failures.joinToString("\n")
        )
    }
}
