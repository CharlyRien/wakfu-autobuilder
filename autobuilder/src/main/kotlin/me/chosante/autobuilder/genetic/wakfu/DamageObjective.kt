package me.chosante.autobuilder.genetic.wakfu

import com.google.ortools.sat.IntVar
import com.google.ortools.sat.LinearExpr
import me.chosante.autobuilder.domain.DamageScenario
import me.chosante.autobuilder.domain.SpellCatalog
import me.chosante.autobuilder.domain.SpellElement
import me.chosante.autobuilder.domain.SpellRotationOptimizer
import me.chosante.autobuilder.genetic.wakfu.WakfuBuildSolver.ceilDivPositive
import me.chosante.autobuilder.genetic.wakfu.WakfuBuildSolver.clampedProductQuotient
import me.chosante.common.Characteristic

// DamageObjective — the max-damage CP-SAT objective (perTurnDamageScore / perHitDamageScore) + its sound
// product/rotation upper bounds, extracted from StatBuilder as extension functions (B1 of
// docs/code-review-followups.md). Verbatim move; reads StatBuilder state by bare name via the receiver.

/**
 * Spell-aware / boss-aware per-turn damage objective for [scenario] (max-damage mode only).
 *
 * For each candidate element (one fixed element, or all four when boss-aware — see
 * [DamageScenario.candidateElements]) that [clazz] actually has spells in, the value is
 * `throughput_e × perHit_e × resFactor_e`:
 *  - `perHit_e` is the existing per-hit core `D · Graw` for that element ([perHitDamageScore]);
 *  - `throughput_e` is the build-independent best base-damage castable with the build's AP — a
 *    precomputed knapsack table ([SpellRotationOptimizer.baseThroughputTable]) looked up by the
 *    AP variable. Element gating is intrinsic: a class with no spells in `e` has an all-zero
 *    table and contributes nothing;
 *  - `resFactor_e = (100 − res_e)` folds in the boss's per-element resistance (a weakness
 *    `res_e < 0` amplifies it), so the `max` over elements picks the best **playable** element
 *    given both the boss profile and the class kit — the joint equipment + element + rotation
 *    optimum. The per-hit score is divided by [DMG_DOWNSCALE] to keep the product in Long range.
 */
internal fun StatBuilder.perTurnDamageScore(
    scenario: DamageScenario,
    clazz: me.chosante.common.CharacterClass,
    objectiveCutoff: Long? = null,
): IntVar {
    val maxRotationAp = if (maxDamageExperiment.apCeiling) actualActionPointCeiling() else MAX_ROTATION_AP
    val apVar = tClamp(actualStat(Characteristic.ACTION_POINT), 0L, maxRotationAp, "rotationAp")
    val candidateElements = scenario.candidateElements()
    val directCutoff =
        objectiveCutoff
            ?.takeIf { it > 0L && candidateElements.size == 1 && !maxDamageExperiment.perHitOnlyObjective }
    val perElementDamage =
        candidateElements.mapNotNull { (element, resistance) ->
            val spells =
                SpellCatalog.damageSpells(clazz).filter {
                    it.element ==
                        me.chosante.common.SpellElement
                            .valueOf(element.name)
                }
            val table = SpellRotationOptimizer.baseThroughputTable(spells, maxRotationAp.toInt(), params.character.level)
            if (table.all { it == 0L }) return@mapNotNull null

            if (table.max() > PER_TURN_THROUGHPUT_MAX) {
                System.err.println(
                    "WARN: per-turn throughput for ${element.name} (${table.max()}) exceeds the " +
                        "CP-SAT cap PER_TURN_THROUGHPUT_MAX=$PER_TURN_THROUGHPUT_MAX; the cap is binding and " +
                        "distorts the max-damage objective — raise it for this dataset."
                )
            }
            val clampedTable = LongArray(table.size) { table[it].coerceAtMost(PER_TURN_THROUGHPUT_MAX) }
            // raw = throughput[AP] · (perHit ÷ PERHIT_DOWNSCALE). The AP lookup has only 21 possible
            // values, so encode it as a selector-gated product instead of a generic multiplication.
            // Fold in the boss's per-element resistance, then scale down once into the penalty's range.
            val resFactor = (100L - resistance).coerceIn(RES_FACTOR_MIN, RES_FACTOR_MAX)
            // dGrawCutoff: push the per-turn floor down to the per-hit product `score = D·Graw`, so its step
            // can reason per-D-value (see [perHitDamageScore]). Same exact chain as the raw/perHit floors
            // below: raw ≥ rawLower ⟹ perHitScaled ≥ ⌈rawLower/throughput⌉ ⟹ perHit ≥ that · DOWNSCALE.
            val dGrawCutoffLowerBound: Long? =
                if (maxDamageExperiment.dGrawCutoff && directCutoff != null) {
                    params.maxDamageApTarget
                        ?.takeIf { it in clampedTable.indices }
                        ?.let { apTarget ->
                            val throughput = clampedTable[apTarget]
                            if (throughput > 0L) {
                                val rawLower = ceilDivPositive(directCutoff * FINAL_DOWNSCALE, resFactor)
                                ceilDivPositive(rawLower, throughput) * PERHIT_DOWNSCALE
                            } else {
                                null
                            }
                        }
                } else {
                    null
                }
            val perHit =
                perHitDamageScore(
                    scenarioElementMasteryVar(element.masteryCharacteristic),
                    element.masteryCharacteristic.name,
                    scenario,
                    dGrawCutoffLowerBound,
                    critDiffJointEligible = candidateElements.size == 1
                )
            // Joint AM-GM product bound on perHit = D·Graw (sound for any build; tightens the McCormick
            // independent-max looseness). Single-element only, where the scenario mastery IS this element's.
            // Self-disabling: only add the cut when the AM-GM bound is STRICTLY tighter than perHit's already-
            // declared reachable max. On an easy solve the bound just equals the box product (no help), so a
            // redundant constraint would only perturb the search for nothing — skip it. On a hard sub-heavy
            // solve it beats the box and closes CP-SAT's dual gap (measured: the lvl-110 full-sub free solve
            // PROVES where it otherwise times out; lvl-245 reaches a strictly better incumbent in the same
            // budget). Sound either way — an under-estimate would cut the optimum and fail the joint-bound lock.
            if (maxDamageExperiment.dGrawJointBound && candidateElements.size == 1) {
                maxPerHitProductBound(scenario)?.let { bound ->
                    if (bound < tracker.of(perHit).last) model.addLessOrEqual(perHit, bound)
                }
            }
            if (certifyForTest && candidateElements.size == 1) {
                // Audit-iteration filter: restrict the all-cells sweep to a comma-separated cell list
                // (test seam only — the 3-D certifier costs ~1.5 min/cell at lvl-245).
                val auditCells =
                    System
                        .getenv("WAKFU_MAX_DAMAGE_CERT_CELLS")
                        ?.split(',')
                        ?.mapNotNull { it.trim().toIntOrNull() }
                        ?.toSet()
                for (apCell in clampedTable.indices) {
                    if (certifyFastOnly) break // P3.1 parallel-equality lock reads only the fast map
                    if (auditCells != null && apCell !in auditCells) continue
                    val maxPerHit = certifyMaxPerHitAtAp(scenario, apCell)
                    certifierObjectivesForTest[apCell] =
                        if (maxPerHit == Long.MAX_VALUE) {
                            -1L
                        } else {
                            clampedTable[apCell] * (maxPerHit / PERHIT_DOWNSCALE) * resFactor / FINAL_DOWNSCALE
                        }
                }
                // FAST tier-1 pass (P2): one shared call fills EVERY cell (cheap — always runs, even
                // under an exact-audit cell filter). Same objective scaling as the exact cells so they
                // are directly comparable; the timing line is the P2 acceptance measurement.
                val fastStart = System.currentTimeMillis()
                val fast = certifyAllCellsFast(scenario, clampedTable.size, certifyFastThreads)
                val fastMs = System.currentTimeMillis() - fastStart
                for (apCell in fast.indices) {
                    certifierFastObjectivesForTest[apCell] =
                        if (fast[apCell] == Long.MAX_VALUE) {
                            -1L
                        } else {
                            clampedTable[apCell] * (fast[apCell] / PERHIT_DOWNSCALE) * resFactor / FINAL_DOWNSCALE
                        }
                }
                System.err.println("CERT_FAST_AUDIT totalMs=$fastMs cells=${certifierFastObjectivesForTest.toSortedMap()}")
                // B7 tier-1.5 audit: the sharpened (step-1, cell-pinned) fast bound per cell, for the
                // `fast ≥ tier1.5 ≥ exact` soundness lock. Test-only (never on the fast-only parallel-equality
                // path, which reads only the tier-1 map).
                if (!certifyFastOnly) {
                    val t15 = certifyCellsTier15(scenario, clampedTable.indices.toList(), certifyFastThreads).values
                    for (apCell in clampedTable.indices) {
                        val v = t15[apCell] ?: Long.MAX_VALUE
                        certifierTier15ObjectivesForTest[apCell] =
                            if (v == Long.MAX_VALUE) {
                                -1L
                            } else {
                                clampedTable[apCell] * (v / PERHIT_DOWNSCALE) * resFactor / FINAL_DOWNSCALE
                            }
                    }
                }
            }
            if (certifyLedgerForTest && candidateElements.size == 1) {
                // P3.2 two-tier orchestrator: fast bound every cell, eliminate below the incumbent, and
                // confirm survivors exactly — all scaled with the ONE formula (clampedTable/resFactor in
                // scope here) so [CertLedger] values are directly comparable to CP-SAT objectives.
                certifierLedgerForTest =
                    certifyLedger(
                        scenario,
                        clampedTable.size,
                        clampedTable,
                        resFactor,
                        certifyLedgerIncumbent,
                        certifyLedgerForceTier2All,
                        certifyFastThreads,
                        certifyLedgerPrecomputedFast,
                        certifyLedgerPrecomputedBailed
                    )
            }
            if (certifyExplainCell != null && candidateElements.size == 1) {
                // E8 item A (perf): a cached (world, crit-step) replays a single explain pass; otherwise scan worlds.
                val explain =
                    certifyExplainProvenance
                        ?.let { certifyExplainAtApFromProvenance(scenario, certifyExplainCell, it) }
                        ?: certifyExplainAtAp(scenario, certifyExplainCell)
                certifierExplainForTest += explain.lines
                certifierExplainItemIds += explain.itemIds
            }
            // certifierCellCap (A/B experiment): feed the certifier's per-AP-cell upper bounds back into
            // the model. For each reachable AP cell `a` with a certified bound U(a) — a sound upper bound
            // on that cell's max per-hit, locked by the certifier soundness tests — add
            // `(rotationAp == a) ⟹ perHit ≤ U(a)`, so an AP branch whose certified ceiling is below the
            // incumbent dies by propagation instead of branching. Cells where the certifier bails
            // (Long.MAX_VALUE) stay uncapped. Only engaged when the rotationAp clamp can never bind
            // (tracked AP reach < maxRotationAp): a binding clamp would group AP > maxRotationAp builds
            // under the top cell, which that cell's bound does not cover.
            val apReach = tracker.of(apVar)
            // An AP-PINNED solve needs only ITS cell's certificate: the pin makes every other cell's
            // reified cap vacuous, and the certifier DP is the dominant cost of a certcap probe (÷17).
            val certCellRange =
                params.maxDamageApTarget
                    ?.toLong()
                    ?.takeIf { it in apReach.first.coerceAtLeast(0L)..apReach.last }
                    ?.let { it..it }
                    ?: (apReach.first.coerceAtLeast(0L)..apReach.last)
            val certCellCaps: List<Pair<Int, Long>>? =
                if (maxDamageExperiment.certifierCellCap && candidateElements.size == 1 && apReach.last < maxRotationAp) {
                    certCellRange.mapNotNull { apL ->
                        val cap = certifyMaxPerHitAtAp(scenario, apL.toInt())
                        if (cap == Long.MAX_VALUE) null else apL.toInt() to cap
                    }
                } else {
                    null
                }
            // Every cell in range certified ⟹ constant global caps are sound too (max over the cells;
            // under an AP pin the range is the single pinned cell, which the pin makes exhaustive).
            val certAllCovered =
                certCellCaps != null &&
                    certCellCaps.size.toLong() == certCellRange.last - certCellRange.first + 1
            if (certCellCaps != null && certCellCaps.isNotEmpty()) {
                for ((ap, cap) in certCellCaps) {
                    val cell = model.newBoolVar("certCellCap_${element.name}_$ap")
                    model.addEquality(apVar, ap.toLong()).onlyEnforceIf(cell)
                    model.addDifferent(apVar, model.newConstant(ap.toLong())).onlyEnforceIf(cell.not())
                    model.addLessOrEqual(perHit, cap).onlyEnforceIf(cell)
                }
                if (certAllCovered) model.addLessOrEqual(perHit, certCellCaps.maxOf { it.second })
            }
            if (maxDamageExperiment.perHitOnlyObjective) return@mapNotNull perHit
            val perHitScaled = tDiv("perHitScaled_${element.name}", perHit, PERHIT_DOWNSCALE, 0L, PERHIT_SCALED_MAX)
            val raw =
                tTableProduct(
                    "rotRaw_${element.name}",
                    apVar,
                    clampedTable,
                    perHitScaled,
                    0L..PERHIT_SCALED_MAX,
                    0L..ROTATION_RAW_MAX
                )
            if (maxDamageExperiment.perApRotRawCut) {
                maxRotRawForElement(scenario, clampedTable, PERHIT_SCALED_MAX)?.let { model.addLessOrEqual(raw, it) }
            }
            // certifierCellCap: the joint product ceiling. raw = throughput[a]·⌊perHit/DOWNSCALE⌋ and
            // per cell perHit ≤ U(a), so raw ≤ max over reachable cells of throughput[a]·⌊U(a)/DOWNSCALE⌋
            // — a CONSTANT bound that collapses the objective's root gap to the certifier's ceiling.
            // Cells where the certifier bailed fall back to the tracked perHit ceiling (their throughput
            // is typically low — e.g. the below-base-AP cells — so the max usually stays on a certified
            // high-AP cell and the cap still bites).
            if (certCellCaps != null && certCellCaps.isNotEmpty()) {
                val capByCell = certCellCaps.toMap()
                val perHitHi = tracker.of(perHit).last
                val rawCap =
                    certCellRange.maxOf { apL ->
                        val ap = apL.toInt()
                        clampedTable[ap] * ((capByCell[ap] ?: perHitHi) / PERHIT_DOWNSCALE)
                    }
                model.addLessOrEqual(raw, rawCap)
            }

            if (directCutoff != null) {
                val rawLower = ceilDivPositive(directCutoff * FINAL_DOWNSCALE, resFactor)
                model.addGreaterOrEqual(raw, rawLower)
                params.maxDamageApTarget?.takeIf { it in clampedTable.indices }?.let { apTarget ->
                    val throughput = clampedTable[apTarget]
                    if (throughput > 0L) {
                        val perHitScaledLower = ceilDivPositive(rawLower, throughput)
                        model.addGreaterOrEqual(perHitScaled, perHitScaledLower)
                        model.addGreaterOrEqual(perHit, perHitScaledLower * PERHIT_DOWNSCALE)
                    }
                }
            }
            val rawWithRes = tSumNaive("rotRawRes_${element.name}", listOf(Term(raw, resFactor)), 0L, 0L, ROTATION_RAW_RES_MAX)
            tDiv("rotDamage_${element.name}", rawWithRes, FINAL_DOWNSCALE, 0L, DAMAGE_PERTURN_ABS_MAX)
        }

    // The turn plays the single best PLAYABLE element against the boss — `max over elements`, NOT a sum
    // (an AP split across elements was measured UNPROVABLE at every arity — 2 terms still FEASIBLE at 240
    // det-time, 3 terms 311s FEASIBLE — for only a ~1.4% unproven gain; multi-element value is captured by
    // element SELECTION, this max). NOTE: a single candidate (size == 1) proves in seconds, but the
    // in-model `max` over several candidates does NOT prove on the full pool (562s FEASIBLE) — so the
    // production boss path (MaxDamageSearch) instead solves each candidate element SEPARATELY (single
    // candidate ⇒ provable) and takes the max externally. This in-model `max` is the correct but
    // slower-to-prove fallback for direct/small-pool callers. See docs/MAX_DAMAGE_PROVABLE_OPTIMUM.md.
    return when (perElementDamage.size) {
        0 -> model.newConstant(0L)
        1 -> perElementDamage.single()
        else -> {
            val reach = 0L..perElementDamage.maxOf { tracker.of(it).last }
            val (lo, hi) = tracker.decl(reach, 0L, DAMAGE_PERTURN_ABS_MAX)
            val best = model.newIntVar(lo, hi, "rotBestElement")
            model.addMaxEquality(best, perElementDamage.toTypedArray())
            tracker.record(best, reach, "rotBestElement")
        }
    }
}

/**
 * Build-dependent per-hit core `D · Graw` for an element whose folded elemental-mastery var is
 * [elementMasteryVar] (see [perTurnDamageScore]). Taking the mastery var as a parameter — rather than
 * computing it internally — lets the caller fold generic "+all elements" and random-element mastery onto
 * the element once. All masteries / DI / crit are clamped into the damage bounds so the two CP-SAT
 * multiplications stay on small, stable integer domains. Var names carry [suffix] so per-element cores
 * never collide.
 */
internal fun StatBuilder.perHitDamageScore(
    elementMasteryVar: IntVar,
    suffix: String,
    scenario: DamageScenario,
    dGrawCutoffLowerBound: Long? = null,
    // C7: whether the crit·diff AM-GM joint cut may be added (single-element callers only — the joint reach
    // is derived from the SCENARIO's mastery decomposition, which is this element's only when it is unique).
    critDiffJointEligible: Boolean = false,
): IntVar {
    val s = suffix
    val preM = damagePreMastery(s, elementMasteryVar, scenario)
    val m = tClamp(preM, 0L, DAMAGE_MASTERY_MAX, "dmgM_$s")
    val criticalMastery = tClamp(actualStat(Characteristic.MASTERY_CRITICAL), 0L, DAMAGE_MASTERY_MAX, "dmgCriticalMastery_$s")
    val critCap = scenario.critCapPercent.toLong().coerceIn(0L, 100L)
    val crit = tClamp(actualStat(Characteristic.CRITICAL_HIT), 0L, critCap, "dmgCrit_$s")
    val di = tClamp(actualStat(Characteristic.DAMAGE_INFLICTED), -DAMAGE_DI_FLOOR, DAMAGE_DI_MAX, "dmgDI_$s")
    val d = tSumNaive("dmgD_$s", listOf(Term(di, 1L)), 100L, 100L - DAMAGE_DI_FLOOR, 100L + DAMAGE_DI_MAX)

    // diff = M + 5·criticalMastery ; term = crit · diff ; Graw = 400·M + term.
    // Crit has a tiny non-negative integer domain (0..100), so an exact selector can turn the nested
    // crit product into a sum of boolean-gated tight diff copies.
    val diffReach = damageMasteryCriticalReach(scenario, masteryWeight = 1L, criticalMasteryWeight = 5L, guardHi = DAMAGE_MASTERY_MAX * 6)
    val diff = tSum("dmgDiff_$s", listOf(Term(m, 1L), Term(criticalMastery, 5L)), 0L, diffReach, 0L, DAMAGE_MASTERY_MAX * 6)
    val critTable = LongArray(critCap.toInt() + 1) { it.toLong() }
    val term =
        when (maxDamageExperiment.critProduct) {
            CritProductMode.TABLE ->
                tTableProduct("dmgCritTerm_$s", crit, critTable, diff, 0L..DAMAGE_MASTERY_MAX * 6, 0L..100L * DAMAGE_MASTERY_MAX * 6)

            CritProductMode.GENERIC ->
                tMul("dmgCritTerm_$s", crit, diff, 0L, 100L * DAMAGE_MASTERY_MAX * 6)

            // Binary-expand crit (0..critCap) into ~7 gated diff copies — the d-binary trick on the
            // crit one-hot. Distinct from GENERIC (a raw tMul, which was measured slower).
            CritProductMode.BINARY ->
                tBinaryOffsetProduct("dmgCritTerm_$s", crit, 0L..critCap, diff, 0L..DAMAGE_MASTERY_MAX * 6, 0L..100L * DAMAGE_MASTERY_MAX * 6)
        }
    model.addLessOrEqual(
        LinearExpr
            .newBuilder()
            .addTerm(term, 1L)
            .addTerm(diff, -critCap)
            .build(),
        0L
    )
    model.addLessOrEqual(
        LinearExpr
            .newBuilder()
            .addTerm(term, 1L)
            .addTerm(crit, -(DAMAGE_MASTERY_MAX * 6))
            .build(),
        0L
    )
    // E6: crit-band disjunction — a piecewise-McCormick tightening of `term = crit·diff`, the loosest objective
    // layer per the bound-layer audit (SOLVER_PERFORMANCE §7). Gated OFF by default; sound (redundant), so it
    // never changes the optimum — the exhaustive-optimum panel locks that.
    if (maxDamageExperiment.critBandDisjunction) addCritBandDisjunction(term, crit, diff, critCap, s)
    // C7: constant AM-GM joint bound on `term = crit·diff` (see [maxCritDiffProductBound]) — crit gear and
    // mastery gear COMPETE for the same slots, which the independent-max `critCap·diffHi` corner ignores.
    // Self-disabling: only added when strictly tighter than term's already-declared reach, so an easy solve
    // is not perturbed by a redundant constraint. Sound either way — an under-count would cap the objective
    // below the exhaustive optimum and fail the crit-diff firing fixture + panel locks.
    if (maxDamageExperiment.critDiffJointBound && critDiffJointEligible) {
        maxCritDiffProductBound(scenario)?.let { bound ->
            if (bound < tracker.of(term).last) {
                model.addLessOrEqual(term, bound)
                critDiffJointCutBoundForTest = bound
            }
        }
    }
    val grawReach =
        damageMasteryCriticalReach(
            scenario,
            masteryWeight = 400L + critCap,
            criticalMasteryWeight = 5L * critCap,
            guardHi = DAMAGE_GRAW_MAX
        )
    // Graw = 400·M + crit·(M + 5·criticalMastery) with M = 100 + ΣMastery. Because M folds in the base
    // 100, Graw already equals 40000·(the per-hit multiplier of FindMaxDamageScoring.expectedDamage): the
    // 400·M term carries the base hit (400·100 = 40000) AND the flat crit bonus (crit·100 from term), so
    // `D·Graw` is exactly proportional to the real per-hit. (An earlier `grawFull = Graw + 40000 + 100·crit`
    // double-counted both and distorted the ranking — reverted.)
    val graw = tSum("dmgGraw_$s", listOf(Term(m, 400L), Term(term, 1L)), 0L, grawReach, 0L, DAMAGE_GRAW_MAX)

    val dReach = tracker.of(d)
    val dOffset =
        tSumNaive(
            "dmgDOffset_$s",
            listOf(Term(d, 1L)),
            -dReach.first,
            0L,
            dReach.last - dReach.first
        )
    val dTable = LongArray((dReach.last - dReach.first + 1).toInt()) { dReach.first + it }
    val score =
        when (maxDamageExperiment.dProduct) {
            DProductMode.TABLE ->
                tTableProduct("dmgScore_$s", dOffset, dTable, graw, 0L..DAMAGE_GRAW_MAX, 0L..DAMAGE_SCORE_ABS_MAX)

            DProductMode.BINARY ->
                tBinaryOffsetProduct("dmgScore_$s", dOffset, dReach, graw, 0L..DAMAGE_GRAW_MAX, 0L..DAMAGE_SCORE_ABS_MAX)

            DProductMode.SOURCE_DI ->
                tSourceExpandedDamageInflictedProduct("dmgScore_$s", graw, 0L..DAMAGE_GRAW_MAX, 0L..DAMAGE_SCORE_ABS_MAX)
                    ?: tTableProduct("dmgScore_$s", dOffset, dTable, graw, 0L..DAMAGE_GRAW_MAX, 0L..DAMAGE_SCORE_ABS_MAX)
        }
    model.addLessOrEqual(
        LinearExpr
            .newBuilder()
            .addTerm(score, 1L)
            .addTerm(graw, -dReach.last)
            .build(),
        0L
    )
    val grawDomain = tracker.of(graw)
    model.addLessOrEqual(
        LinearExpr
            .newBuilder()
            .addTerm(score, 1L)
            .addTerm(graw, -dReach.first)
            .addTerm(d, -grawDomain.last)
            .build(),
        -dReach.first * grawDomain.last
    )

    // Cutoff (incumbent-optimality proof) ONLY: a sound lower bound `tScore` on the per-hit product
    // score = D·Graw. CP-SAT's product relaxation lets a fractional (D high)×(Graw high) meet tScore even
    // when no integer build does — high DI gear costs mastery, so it lowers Graw. Add the EXACT per-D-value
    // disjunction `(D==v) ⟹ Graw ≥ ⌈tScore/v⌉` (only over the band where it bites), plus the cheap box
    // projections `D ≥ ⌈tScore/Grawmax⌉` and `Graw ≥ ⌈tScore/Dmax⌉`. Every constraint is implied by
    // score ≥ tScore (so it never removes a feasible build), but they engage the integer item-coupling the
    // bilinear McCormick envelope misses. Fires only when dGrawCutoff is set AND a cutoff bound was derived,
    // so the normal (no-cutoff) objective — and its exactness tests — are untouched.
    if (dGrawCutoffLowerBound != null && maxDamageExperiment.dGrawCutoff) {
        val tScore = dGrawCutoffLowerBound
        val grawDom = tracker.of(graw)
        model.addGreaterOrEqual(score, tScore)
        if (grawDom.last > 0L) model.addGreaterOrEqual(d, ceilDivPositive(tScore, grawDom.last))
        if (dReach.last > 0L) model.addGreaterOrEqual(graw, ceilDivPositive(tScore, dReach.last))
        for (v in maxOf(dReach.first, 1L)..dReach.last) {
            val grawMin = ceilDivPositive(tScore, v)
            // Below the band the bound is free (already implied); above it `D==v` is impossible and is
            // already pruned by the `D ≥ ⌈tScore/Grawmax⌉` box cut, so only reify the middle.
            if (grawMin <= grawDom.first || grawMin > grawDom.last) continue
            val isV = model.newBoolVar("dGrawCut_${suffix}_$v")
            model.addEquality(d, v).onlyEnforceIf(isV)
            model.addDifferent(d, model.newConstant(v)).onlyEnforceIf(isV.not())
            model.addGreaterOrEqual(graw, grawMin).onlyEnforceIf(isV)
        }
    }
    return score
}

/**
 * E6: crit-band disjunction — a piecewise-McCormick tightening of `term = crit·diff` (the loosest objective layer,
 * per the bound-layer audit in SOLVER_PERFORMANCE §7). Partition crit's reachable range `[0, critHi]` into disjoint
 * integer bands; a per-band boolean reifies `crit ∈ [lo, hi]`, exactly one is true, and under band b the REDUNDANT
 * linear cut `term ≤ hi_b·diff` holds (`crit ≤ hi_b ⇒ term = crit·diff ≤ hi_b·diff`). Tighter than the single global
 * `term ≤ critCap·diff` for every band below the top, so the LP gets the per-band structure the exact product hides.
 * Sound (redundant over the exact encoding — no build is removed) — the exhaustive-optimum panel locks that.
 */
private fun StatBuilder.addCritBandDisjunction(
    term: IntVar,
    crit: IntVar,
    diff: IntVar,
    critCap: Long,
    suffix: String,
) {
    val critHi = tracker.of(crit).last.coerceAtMost(critCap)
    if (critHi <= 1L) return
    val bandCount = 4
    val edges = (0..bandCount).map { (critHi * it) / bandCount }.distinct()
    if (edges.size < 2) return
    val bandSum = LinearExpr.newBuilder()
    var any = false
    for (i in 0 until edges.size - 1) {
        val lo = if (i == 0) 0L else edges[i] + 1L
        val hi = edges[i + 1]
        if (lo > hi) continue
        val inBand = model.newBoolVar("critBand_${suffix}_$i")
        model.addGreaterOrEqual(crit, lo).onlyEnforceIf(inBand)
        model.addLessOrEqual(crit, hi).onlyEnforceIf(inBand)
        model
            .addLessOrEqual(
                LinearExpr
                    .newBuilder()
                    .addTerm(term, 1L)
                    .addTerm(diff, -hi)
                    .build(),
                0L
            ).onlyEnforceIf(inBand)
        bandSum.addTerm(inBand, 1L)
        any = true
    }
    if (any) model.addEquality(bandSum.build(), 1L)
}

/**
 * Sound upper bound on `raw = throughput[AP] · perHitScaled`, tightening the AP-vs-mastery slack: a
 * build with high AP (more throughput) spends item slots on AP gear, which lowers mastery (so perHit),
 * but the table-product McCormick lets `throughput[high AP]` pair with `perHit[high mastery]`. Since DI
 * and mastery do NOT compete (the per-hit `D·graw` is already tight), `perHit` at AP=a is bounded by
 * `D_max · grawLinMaxAtAp(a)` with `grawLin = 500·M + 500·K ≥ graw`. The per-AP mastery max comes from a
 * Lagrangian relaxation: for any μ, `max{grawLin : AP=a} ≤ max_x(grawLin(x) − μ·AP(x)) + μ·a`, and the
 * inner max is a SINGLE [reachableSumDomain] over the μ-weighted `(grawLin − μ·AP)` terms (so all the
 * ring/rarity/sublimation coupling comes for free). min over a μ grid ⇒ sound per-AP bound. The cut is
 * `raw ≤ max_a throughput[a] · floor(D_max·grawLinUB(a) / PERHIT_DOWNSCALE)`. Null ⇒ cannot tighten.
 */
internal fun StatBuilder.maxRotRawForElement(
    scenario: DamageScenario,
    clampedTable: LongArray,
    perHitScaledMax: Long,
): Long? {
    val mastery = damagePreMasteryTerms(scenario) ?: return null
    if (skillTerms.percent[Characteristic.MASTERY_CRITICAL].orEmpty().isNotEmpty()) return null
    if (skillTerms.percent[Characteristic.DAMAGE_INFLICTED].orEmpty().isNotEmpty()) return null
    if (skillTerms.percent[Characteristic.ACTION_POINT].orEmpty().isNotEmpty()) return null
    val (critTerms, critBase) = prePercentTermsFor(Characteristic.MASTERY_CRITICAL)
    val (diTerms, diBase) = prePercentTermsFor(Characteristic.DAMAGE_INFLICTED)
    val (apTerms, apBase) = prePercentTermsFor(Characteristic.ACTION_POINT)

    val grawLinTerms =
        mastery.terms.map { Term(it.variable, it.coefficient * 500L) } +
            critTerms.map { Term(it.variable, it.coefficient * 500L) }
    val grawLinConst = 500L * mastery.constant + 500L * critBase
    val dHi = reachableSumDomain(diTerms, 100L + diBase).last.coerceAtLeast(1L)

    val grawLinGlobalHi = reachableSumDomain(grawLinTerms, grawLinConst).last.coerceAtLeast(0L)
    if (grawLinGlobalHi <= 0L) return 0L
    val apSpan = (reachableSumDomain(apTerms, apBase).last - apBase).coerceAtLeast(1L)
    val muBase = (grawLinGlobalHi / apSpan).coerceAtLeast(1L) // ≈ grawLin gained per AP point
    val muGrid =
        listOf(0L, muBase / 4, muBase / 2, muBase, muBase * 2, muBase * 4, muBase * 8)
            .filter { it >= 0L }
            .distinct()
    val gByMu =
        muGrid.map { mu ->
            val combined = grawLinTerms + apTerms.map { Term(it.variable, -mu * it.coefficient) }
            mu to reachableSumDomain(combined, grawLinConst - mu * apBase).last
        }

    var maxRotRaw = 0L
    for (a in clampedTable.indices) {
        if (clampedTable[a] == 0L) continue
        val grawLinUBa = gByMu.minOf { (mu, g) -> g + mu * a }.coerceIn(0L, grawLinGlobalHi)
        val perHitScaledUBa = clampedProductQuotient(dHi, grawLinUBa, PERHIT_DOWNSCALE, perHitScaledMax)
        maxRotRaw = maxOf(maxRotRaw, clampedTable[a] * perHitScaledUBa)
    }
    return maxRotRaw
}

/**
 * Sound CONSTANT upper bound on the per-hit product `score = D · Graw` (D = 100+DI, Graw ≤ grawLin =
 * 500·M + 500·K). The McCormick relaxation of the product bounds it by the INDEPENDENT maxes `Dmax ·
 * grawLinMax` — loose because high DI and high mastery COMPETE for the same slots (the best-DI item in a
 * slot is rarely the best-mastery one). For any weight μ>0 the joint reachable bound `μ·D + grawLin ≤
 * C(μ)` is a SINGLE [reachableSumDomain] over the μ-weighted `(μ·DI ∪ grawLin)` terms (so all the per-slot
 * / ring / rarity / sublimation competition is captured exactly), and by AM-GM `D·grawLin ≤ C(μ)²/(4μ)`.
 * The min over a μ grid is a sound upper bound on `score` for EVERY build — valid for the normal objective,
 * not only the cutoff proof. Null ⇒ a %-skill term makes the slot decomposition unsound (cannot tighten).
 */
internal fun StatBuilder.maxPerHitProductBound(scenario: DamageScenario): Long? {
    val mastery = damagePreMasteryTerms(scenario) ?: return null
    if (skillTerms.percent[Characteristic.MASTERY_CRITICAL].orEmpty().isNotEmpty()) return null
    if (skillTerms.percent[Characteristic.DAMAGE_INFLICTED].orEmpty().isNotEmpty()) return null
    val (critTerms, critBase) = prePercentTermsFor(Characteristic.MASTERY_CRITICAL)
    val (diTerms, diBase) = prePercentTermsFor(Characteristic.DAMAGE_INFLICTED)

    // graw = 400·M + crit·(M + 5·critM) with crit ≤ critCap, so the tightest crit-free upper bound is
    // (400+critCap)·M + 5·critCap·critM (NOT 500·M + 500·critM, which assumed crit ≤ 100 — far too loose
    // when the scenario caps crit well below 100).
    val critCap = scenario.critCapPercent.toLong().coerceIn(0L, 100L)
    val masteryCoef = 400L + critCap
    val critCoef = 5L * critCap
    val grawLinTerms =
        mastery.terms.map { Term(it.variable, it.coefficient * masteryCoef) } +
            critTerms.map { Term(it.variable, it.coefficient * critCoef) }
    val grawLinConst = masteryCoef * mastery.constant + critCoef * critBase
    val dBase = 100L + diBase
    val dHi = reachableSumDomain(diTerms, dBase).last.coerceAtLeast(1L)
    val grawLinHi = reachableSumDomain(grawLinTerms, grawLinConst).last.coerceAtLeast(1L)

    // The AM-GM bound is tightest when μ·D ≈ grawLin, i.e. μ ≈ grawLin / D; grid around it.
    val muBase = (grawLinHi / dHi).coerceAtLeast(1L)
    val muGrid = listOf(muBase / 4, muBase / 2, muBase, muBase * 2, muBase * 4).filter { it > 0L }.distinct()
    val independent = dHi * grawLinHi
    var best = independent // independent-max fallback (always valid; the cut only helps if a μ beats it)
    for (mu in muGrid) {
        val combined = diTerms.map { Term(it.variable, mu * it.coefficient) } + grawLinTerms
        val cMu = reachableSumDomain(combined, mu * dBase + grawLinConst).last
        // Clamp the AM-GM bound `cMu² / (4μ)` to the running [best] (its only role is to lower the min),
        // computed EXACTLY: a Double `cMu * cMu` loses precision past 2^53 and could round this hard
        // bound below the true per-hit max, cutting the optimum.
        best = clampedProductQuotient(cMu, cMu, 4L * mu, best)
    }
    if (System.getenv("WAKFU_MAX_DAMAGE_DEBUG_JOINT") == "1") {
        System.err.println(
            "JOINT_BOUND_DEBUG critCap=$critCap dHi=$dHi grawLinHi=$grawLinHi independent=$independent " +
                "jointU=$best ratio=${"%.4f".format(best.toDouble() / independent)}"
        )
    }
    return best
}

/**
 * C7: sound CONSTANT upper bound on the inner per-hit product `term = crit · diff`, where
 * `crit = clamp(rawCrit, 0, critCap)` and `diff = m + 5·K` (both factors non-negative by construction).
 * The linear cuts bound `term` by the INDEPENDENT corner `critCap · diffHi` — loose because crit gear and
 * mastery gear COMPETE for the same slots (the best-crit amulet is rarely the best-mastery one). For any
 * μ > 0 and any build, `μ·crit + diff ≤ C(μ)` with
 * `C(μ) = min( critDiffJointReachHi(μ), μ·critCap + diffHi )`:
 * the joint reach prices the slot competition (one [StatBuilder.reachableSumDomain] over the μ-weighted
 * union — plus the three LOWER-clamp slacks; see [StatBuilder.critDiffJointReachHi]), the trivial
 * component caps it by the clamps; the min of two sound bounds is sound. By AM-GM
 * `crit·diff ≤ C(μ)²/(4μ)` (exact integers via [clampedProductQuotient] — a Double `C·C` loses precision
 * past 2^53 and could round this hard bound below the true max, cutting the optimum). Min over a WIDE
 * geometric μ grid around `diffHi / critCap` (the C6 lesson: a single centre whiffs) ⇒ a sound upper
 * bound on `term` for EVERY build. Null ⇒ cannot tighten soundly (a %-skill term breaks the slot
 * decomposition, or critCap = 0 where `term ≤ critCap·diff` already pins it to 0).
 */
internal fun StatBuilder.maxCritDiffProductBound(scenario: DamageScenario): Long? {
    val critCap = scenario.critCapPercent.toLong().coerceIn(0L, 100L)
    if (critCap == 0L) return null
    val diffHi =
        damageMasteryCriticalReach(
            scenario,
            masteryWeight = 1L,
            criticalMasteryWeight = 5L,
            guardHi = DAMAGE_MASTERY_MAX * 6
        ).last
    val independent = critCap * diffHi

    // AM-GM is tightest when μ·crit ≈ diff at the optimum, i.e. μ ≈ diffHi / critCap; wide grid around it.
    val muBase = (diffHi / critCap).coerceAtLeast(1L)
    val muGrid =
        listOf(muBase / 8, muBase / 4, muBase / 2, muBase, muBase * 2, muBase * 4, muBase * 8)
            .filter { it > 0L }
            .distinct()
    var best = independent // independent-max fallback (always valid; the cut only helps if a μ beats it)
    for (mu in muGrid) {
        val jointHi = critDiffJointReachHi(scenario, mu) ?: return null
        val cMu = minOf(jointHi.coerceAtLeast(0L), mu * critCap + diffHi)
        best = clampedProductQuotient(cMu, cMu, 4L * mu, best)
    }
    if (System.getenv("WAKFU_MAX_DAMAGE_DEBUG_CRITDIFF") == "1") {
        System.err.println(
            "CRITDIFF_BOUND_DEBUG critCap=$critCap diffHi=$diffHi independent=$independent " +
                "jointU=$best ratio=${"%.4f".format(best.toDouble() / independent.coerceAtLeast(1L))}"
        )
    }
    return best
}
