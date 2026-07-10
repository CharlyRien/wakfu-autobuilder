package me.chosante.autobuilder.genetic.wakfu

/**
 * A/B experiment knobs for the max-damage CP-SAT objective. **Production always uses [DEFAULT]** —
 * `WakfuBuildSolver.optimize` passes it and never exposes these flags. The non-default variants exist only so
 * the max-damage tuning tests (`WakfuBuildSolverTest`) can measure alternative product encodings / cut
 * strategies against the shipped one. Kept out of `WakfuBuildSolver` so the engine file reflects the single
 * production encoding, not the research history behind it.
 *
 * NOTE: the shipped production config is [DEFAULT], which is NOT the bare `MaxDamageExperimentConfig()` — it
 * turns [dGrawJointBound] ON (see below). The bare constructor stays the tuning tests' neutral baseline, so
 * every existing `SolverTuning()`-driven test is unchanged; only the production `optimize` path gets the cut.
 */
internal data class MaxDamageExperimentConfig(
    // Tightens the rotation-AP ceiling (MAX_ROTATION_AP → actualActionPointCeiling()).
    // A/B 2026-07-08 (SOLVER_PERFORMANCE §7): NOT shipped — a search perturbation (−0.3% at det200 but +16.5% / +14.5%
    // LOOSER at det400 / det800), the same near-inert-constraint family as the removed E0/C6. Kept for the A/B registry.
    val apCeiling: Boolean = false,
    // BINARY is the production default: binary-expanding the D and crit one-hot product selectors (~180
    // booleans → ~14 bits) collapses the tree-exhaustion search ~5.5× (measured, sound). TABLE is the old
    // one-hot encoding, kept only for A/B. See solver-performance-audit.
    val critProduct: CritProductMode = CritProductMode.BINARY,
    val dProduct: DProductMode = DProductMode.BINARY,
    // SHIPPED ON in [DEFAULT]: the RING-pair reachable-domain bound excludes same-fr-name pairs (Wakfu forbids
    // wearing two rings of the same name — the model already enforces Σ same-name ≤ 1). Without it the declared
    // perHit box inflates by a phantom "best ring ×2" pair — the same inflation the certifier bans. It uses the
    // EXACT distinct-name-pair enumeration in `rarityAwareUpper.ringOptions` (NOT the take-2 heuristic, which
    // under-declared the domain and cut the optimum on same-name-ring pools). Sound + locked by the
    // same-name-ring-bound tuning test and the negative-AP same-name-ring certifier-equality test.
    val sameNameRingBound: Boolean = false,
    // SHIPPED ON in [DEFAULT] (plan §1.4 re-screen, 2026-07-08): the sound per-AP-cell rotation bound
    // (`maxRotRawForElement`, `raw ≤ max_a throughput[a]·D_max·grawLinUB(a)`). Under the canonical
    // 1-worker+interleave protocol it collapses the free-shape dual bound sharply (F1 lvl-110: −18.8 % at det400,
    // −9.7 % at det800 — the rotRaw layer was 3.2× loose in the bound-layer audit), marginally tightens constrained
    // shapes (fewer AP cells when AP is pinned), and regresses neither. Its old "perturbs without helping" verdict
    // was a pre-interleave-harness artifact. Sound (locked by the `per-ap-rotraw-cut` / `joint+per-ap` exhaustive
    // panel variants). See SOLVER_PERFORMANCE §7.
    val perApRotRawCut: Boolean = false,
    val perHitOnlyObjective: Boolean = false,
    // Cutoff-probe feasibility floor: pushes a per-hit LOWER bound derived from `objectiveCutoff` (see DamageObjective).
    // A/B 2026-07-08 (SOLVER_PERFORMANCE §7): INERT and production-UNREACHABLE — `objectiveCutoff` is supplied ONLY by
    // `timedMaxDamageProfileForTest` (test-only; no production caller), and the cut also needs the probe-only
    // `maxDamageApTarget` pin, so flipping it ON could not affect any production solve. Dormant research scaffolding.
    val dGrawCutoff: Boolean = false,
    // The always-sound AM-GM joint bound on the per-hit product `D·Graw` (see StatBuilder.maxPerHitProductBound).
    // SHIPPED ON in [DEFAULT]: it closes CP-SAT's McCormick dual gap on the hard sub-heavy free solve (measured —
    // the lvl-110 full-sub solve PROVES where it otherwise times out; lvl-245 reaches a strictly better incumbent),
    // is neutral on easy / required-target solves, and self-disables when it wouldn't tighten perHit's box (see the
    // call site in DamageObjective). Locked sound by the "joint-bound" tuning test.
    val dGrawJointBound: Boolean = false,
    // Feed the exact AP-cell certifier's per-cell upper bounds back into the model as constraints:
    // (rotationAp == a) ⟹ perHit ≤ certifiedMax(a), plus a constant cap on the throughput×perHit
    // product when every reachable cell certified. Sound by the certifier's upper-bound test locks.
    // A/B 2026-07-08 (SOLVER_PERFORMANCE §7): NOT inert — it PROVES the free lvl-110 optimum in-model at det≈175, where
    // baseline never proves. Kept OFF anyway: it runs the FULL certifier at model-build (cost hidden from det-time),
    // duplicating the E8 fast-path but EAGERLY (every solve) vs E8's lazy timed-out rescue; and a certifier under-count
    // would inject an invalid cap ⇒ false OPTIMAL (E8 fails safe instead). Its one merit: a parse-free alternative to
    // E8 item-A (CP-SAT finds its own optimal build — no explain-string provenance parse).
    val certifierCellCap: Boolean = false,
    // E6 (plan §4 Tier 2): crit-band disjunction on the inner crit·diff product `term`. The bound-layer audit
    // (SOLVER_PERFORMANCE §7) found `dmgCritTerm` the LOOSEST objective layer (4.1× box). Partition crit into
    // bands; under "crit ∈ band b" (crit ≤ b_hi) add the REDUNDANT linear cut `term ≤ b_hi·diff` — a piecewise
    // McCormick envelope tighter than the single global `term ≤ critCap·diff`. Sound (implied by term = crit·diff),
    // gated OFF by default, locked by the exhaustive-optimum panel (an under-count would cut the optimum — the C7
    // guard). A/B 2026-07-08 (SOLVER_PERFORMANCE §7): NOT shipped — oscillates around baseline (+2.0% / +20.7% / +0.3%
    // over the det sweep) and interferes with perApRotRaw; a big declared-box ratio is a candidate, not a guarantee.
    val critBandDisjunction: Boolean = false,
    // E2 (plan §4 Tier 1): conversion-conservation clamp slack. SHIPPED ON in [DEFAULT] (A/B 2026-07-08, see
    // SOLVER_PERFORMANCE §7). A CONVERSION sub's `moved` var enters the combined mastery/critical reach with OPPOSITE
    // signs (already netted EXACTLY by the C1 per-variable aggregation), but the separate per-stat `clampSlack` (how far
    // clamp(preStat,0,·) rises above a raw sum that went negative) still counts the `−moved` at full domain — crediting a
    // clamp-restoration for a source driven negative by `moved`, which `moved ∈ [0, percent%·preSubStat(from)]` makes
    // unreachable. Excluding `conversionMovedVars` from the clampSlack reaches drops that phantom while keeping the real
    // gear-driven slack — completing C1's other half. WIN: it UNSTICKS the lvl-245 flagship dual bound (25.98M frozen →
    // 23.29M, −10.4% @det800 — a frozen root perApRotRaw could not touch), −19%/−10% on the constrained shape, and is
    // net-positive on free lvl-110. Sound: the targeted fires+`withinBound` lock plus the objective-chain fuzz.
    val conversionConservationCut: Boolean = false,
    // C7 (perf-review-backlog §C7): constant AM-GM joint bound on the INNER crit·diff product `term` (see
    // DamageObjective.maxCritDiffProductBound) — the same joint-reach idea as [dGrawJointBound], one product
    // deeper. Both factors hide LOWER clamps (crit = clamp(rawCrit, 0, critCap); diff's m/K are clamped at 0),
    // so the joint C(μ) must carry all three clamp slacks — pricing them at zero was the reverted 2026-07-07
    // under-count the exhaustive panel caught. OFF pending the deterministic F2 harness A/B verdict; sound
    // (locked by the crit-diff firing fixture + the exhaustive-optimum panel).
    val critDiffJointBound: Boolean = false,
) {
    companion object {
        // The shipped production config — the AM-GM joint cut + the same-name-ring domain fix ON. Distinct from
        // `MaxDamageExperimentConfig()` (the tuning tests' neutral baseline) so existing test solves stay
        // byte-identical while production gains both tightenings.
        val DEFAULT =
            MaxDamageExperimentConfig(
                dGrawJointBound = true,
                sameNameRingBound = true,
                perApRotRawCut = true,
                conversionConservationCut = true
            )

        // The config the NON-max-damage modes (precision / most-masteries) declare their [StatBuilder] with.
        // Deliberately SEPARATE from [DEFAULT] so tuning a max-damage experiment can never silently perturb the
        // other modes' declared domains: those modes construct their StatBuilder without an explicit config, so
        // they used to inherit [DEFAULT] by default. Only [sameNameRingBound] reaches a non-max-damage model — the
        // sound RING-pair reachable-domain tightening precision (tight domains) benefits from; the
        // damage-objective-only flags ([dGrawJointBound], the product modes) are inert there. Keeping it `true`
        // preserves today's precision behaviour exactly while decoupling it from the max-damage default.
        val NON_MAX_DAMAGE = MaxDamageExperimentConfig(sameNameRingBound = true)
    }
}

internal enum class CritProductMode {
    TABLE,
    GENERIC,
    BINARY,
}

internal enum class DProductMode {
    TABLE,
    BINARY,
    SOURCE_DI,
}
