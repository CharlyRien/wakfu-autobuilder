# S4 — Conditional sublimations block CP-SAT from proving the max-damage soft leg

> **STATUS 2026-07-16: RESOLVED — not by re-encoding, but by an external proof.** The CP-SAT
> reification wall described below still stands (do not retry the listed encodings); the badge is
> delivered instead by the **hybrid partition union** shipped in production
> (`MaxDamageSoftCertificate.hybridUnionUpper` + `MaxDamageSearch.proveSoftLegQuality`):
> no-conditional-sub builds are covered by the no-condition CP-SAT model's proven optimum, and
> conditional-sub builds by a dedicated DP certificate. The S4 fixture closes **exactly**
> (optimum 17 702 078 146 500 — ProvenOptimal end-to-end). Campaign log:
> `docs/MOST_MASTERIES_PERF_PLAN.md` §9.10-§9.20.

**Handoff document (2026-07-15).** Self-contained problem statement for whoever picks up the
condition-encoding work. Everything below is measured, not conjectured.

---

## 1. TL;DR

A max-damage search whose **required targets are unreachable** (e.g. AP16 / MP8 / CC100 / HP12000)
falls back to the **soft penalized objective** (`damage × power6(target-shortfall)`). On that leg,
**CP-SAT no longer reaches OPTIMAL**, so the "proven optimal" badge is withheld.

Isolation identifies **the reified conditions of the solver-choosable sublimations** as the
dominant proof wall on this fixture:

| configuration (lvl-245 CRA, real parallel portfolio, all cores) | result |
|---|---|
| soft leg, **no sublimations** | **OPTIMAL** in det 253 / ~3.5 min |
| soft leg, subs **without the 16 condition-bearing catalog entries** | **OPTIMAL** in **49 s**, obj = 17 702 078 146 500 |
| soft leg, **full subs** (with conditions), 15 min wall | **FEASIBLE**, dual 2.14×, never proves |
| soft leg, **full subs, 2 h wall** (real parallel) | **FEASIBLE**, dual **2.01×**, never proves |
| soft leg, full subs, deterministic 8-worker, det 6000 (2h27) | **FEASIBLE**, dual 3.17× — deterministic ~1-core ARTIFACT |

The best-known full-model incumbent is **`17 702 078 146 500`**. The no-condition subset proves that
same value in 49 s, and the full-subs 2 h search reached it at ~14 min and never improved it. This is
strong evidence, but **not a proof of the full optimum**: the subset's feasible set is smaller, and
the full run ended FEASIBLE with dual 35.55T. The current independent sound prototype certificate
closes the upper side to **17 762 813 128 500**, giving the interval
**[17 702 078 146 500, 17 762 813 128 500]**, only **0.343095%** wide. This latest bound is green
against three deterministic exact-pool CP-SAT locks; see §6 and
`MOST_MASTERIES_PERF_PLAN.md` §9.15.

Two consequences:
1. The bilinear objective, stacking and carrier matching are tractable on the restricted fixture —
   CP-SAT proves through them in under a minute. Adding the modeled condition layer destroys that
   proof behavior.
2. Equality of the restricted optimum and full incumbent does not establish that conditional subs
   cannot improve the build; only a full CP-SAT proof or a certificate meeting the incumbent can do
   that.

This is a **provability regression** introduced by the (semantically correct) condition-timing fix
`39532d15` (2026-07-14), which added `firstTurnStat` reified variables. Before it, sub-heavy soft
requests could prove; now the badge may be withheld. The fix must NOT be reverted (it corrects a
real Ravage×Neutralité bug — see `docs/` and the memory note); the goal is a **lighter condition
encoding** (or a search-side world split) that proves without changing the semantics.

---

## 2. Reproduce

The probe is env-gated in `MostMasteriesPerfExperimentTest.manual campaign-2 baseline matrix S1-S2-S3`.
Always `./gradlew --stop` first (the daemon freezes its env snapshot) and run with an 8 GB heap.

```sh
./gradlew --stop
# A — full subs, REAL parallel portfolio (production path, tuning=null), 15 min wall:
WAKFU_MM_C2_BASELINE=1 WAKFU_MM_C2_BASELINE_FIXTURES=S4 WAKFU_MM_C2_PROD=1 \
  WAKFU_MM_C2_WALL_SECONDS=900 WAKFU_TEST_MAX_HEAP=8g \
  ./gradlew :autobuilder:test --tests '*MostMasteriesPerfExperimentTest*' --rerun-tasks
#   → FEASIBLE, bestBound ≈ 37.9T (2.14×), optimal=false

# B — subs WITHOUT the conditional ones, same real-parallel path:
WAKFU_MM_C2_BASELINE=1 WAKFU_MM_C2_BASELINE_FIXTURES=S4 WAKFU_MM_C2_PROD=1 \
  WAKFU_MM_C2_NOCONDSUBS=1 WAKFU_MM_C2_WALL_SECONDS=900 WAKFU_TEST_MAX_HEAP=8g \
  ./gradlew :autobuilder:test --tests '*MostMasteriesPerfExperimentTest*' --rerun-tasks
#   → OPTIMAL in 49 s, rawObjective == bestBound == 17 702 078 146 500
```

Read the verdict from the `MM_PERF_AB SUMMARY shape=S4-...` line (`status`, `rawObjective`,
`bestBound`, `optimal`). The env toggles live in the test:
- `WAKFU_MM_C2_PROD=1` → `tuning = null` in `solve()` → the **production wall-clock parallel
  portfolio** (`if (tuning == null)` branch, `WakfuBuildSolver.kt` ~L2905). **Do NOT use the
  deterministic path for provability questions** — a non-null `SolverTuning` with
  `maxDeterministicTime` runs the workers *interleaved for reproducibility*, i.e. on ~1 physical
  core, so it dramatically under-represents what real parallelism proves. It is still useful for
  controlled A/B runs when paired with a fixed seed and `interleaveSearch=true`. Every earlier
  "multi-worker" S4 measurement in `docs/MOST_MASTERIES_PERF_PLAN.md` §9.0–9.5 was this deterministic
  artifact.
- `WAKFU_MM_C2_NOCONDSUBS=1` → filters `sub.condition != null` out of the sublimation list.
- `WAKFU_MM_C2_NOSUBS=1` / `WAKFU_MM_C2_NORUNES=1` — the other isolation toggles.

---

## 3. Where the conditions are encoded

The whole reification chain is in **`autobuilder/.../genetic/wakfu/SublimationTerms.kt`**:

- **`appliesVar(sub)`** (~L197): for a non-forced sub with a supported condition, it posts
  `subVar ≤ reifyCondition(cond)` — i.e. the sub can only be *chosen* when its condition holds.
- **`reifyCondition(cond)`** (~L257): dispatches on `subConditionSpec(cond, level)`:
  - `StatBound` → `reifyStatBound` (the common case — a stat sum vs a threshold),
  - `NoOffhandOrTwoHanded` → a pick-sum ≤ 0,
  - `AlwaysApplies` → constant 1 (unsupported types stay optimistically on).
- **`reifyStatBound(spec, tag)`** (~L271): reads `firstTurnStat` when `spec.firstTurn` (only
  `SECONDARY_MASTERIES_AT_MOST` today — the 07-14 fix), else `preCombatStat`, then:
  - `AT_MOST` → `reifyLe(value, n, tag)`
  - `AT_LEAST` → `reifyGe(value, n, tag)`
  - `EXACT` → `and(reifyLe, reifyGe)`
- **`reifyLe` / `reifyGe`** (~L218/L229): the actual hardness — a fresh bool `b` with the
  **indicator pair**
  ```
  value ≤ n      onlyEnforceIf(b)
  value ≥ n + 1  onlyEnforceIf(b.not())
  ```
  This is a full two-sided reification (b ⟺ value ≤ n). `value` is itself a build-dependent
  `IntVar` (a stat total that gathers items + runes + skills + permanent subs, and — under
  `firstTurn` — start-of-combat FLAT-sub terms via `StatBuilder.firstTurnStat` /
  `startOfCombatFlatSubTermsByStat`).

Semantics + the `firstTurn` flag live in **`SublimationSemantics.kt`**
(`SubConditionSpec.StatBound`, `ConditionComparison`, `SUPPORTED_SUB_CONDITIONS`). The scalar
re-scorer (`FindClosestBuildFromInputScoring.kt`, `subConditionHolds`) evaluates the SAME spec with
integer math — any encoding change must keep the two in lockstep (there is a differential lock:
`SublimationConditionTest`, `SublimationPreCombatConditionTest`).

### The condition-bearing catalog entries

There are 16 solver-choosable entries in the JSON, but only **15 enter CP-SAT with a modeled
condition**. `Force Herculéenne` uses `AP_ODD`, which is outside `SUPPORTED_SUB_CONDITIONS` and is
filtered by `isModelableSublimation`; it creates no reification.

```
 4  SECONDARY_MASTERIES_AT_MOST   (Neutralité/Prétention/Ambition III, Inflexibilité II — firstTurn=true)
 2  CRIT_AT_MOST                  (Constance, Mesure III)
 2  CRIT_AT_LEAST                 (Dénouement is CRIT_AT_LEAST 40 → a conversion sub)
 1  AP_AT_MOST                    (Inflexibilité)
 1  CRITICAL_MASTERY_AT_MOST      (Secret critique)
 1  BLOCK_AT_LEAST                (Mesure)
 1  RANGE_AT_LEAST / 1 RANGE_AT_MOST / 1 DODGE_LT_PCT_OF_LEVEL / 1 NO_OFFHAND_OR_TWO_HANDED
 1  AP_ODD catalog entry — unsupported/filter-out, not reified
```

---

## 4. Why CP-SAT can't prove through this

- Each `reifyLe`/`reifyGe` is an **indicator (big-M-style) constraint**. Its LP relaxation is weak:
  the fractional `b` lets `value` sit on both sides of the threshold at once, so the relaxation does
  not exclude the switching point. With **15 modeled conditions**, the search can be driven toward a
  power-set-sized condition split to certify the dual — the objective is nonlinear (`value` feeds mastery →
  `D·Graw` → penalty), so the disjunctions never collapse in presolve.
- The `firstTurn` reads (07-14 fix) make `value` a **larger sum** (pre-combat + start-of-combat flat
  sub terms, each subVar-gated), widening the reified expression's domain and adding gated products —
  strictly more indicator surface than the pre-fix `preCombatStat` read.
- Empirically the dual bound *does* descend under real parallelism (56→38T in 15 min) but does not
  reach the incumbent — consistent with "weak relaxation, huge branch tree," not "no bound exists."

---

## 5. Soundness / correctness constraints on any fix

1. **Do not change the game semantics.** `AT_MOST` conditions on secondary masteries / crit read the
   **first-turn** sheet (after start-of-combat FLAT sub effects); other conditions read pre-combat.
   This is the 07-14 fix and it is correct (`SublimationPreCombatConditionTest` locks it). A lighter
   encoding must reproduce the same feasible set.
2. **Scalar mirror parity.** `FindClosestBuildFromInputScoring.subConditionHolds` must agree with the
   model on every build (differential tests exist). If you split into worlds, the re-scorer still has
   to evaluate the actually-chosen world's build correctly.
3. **A sub never feeds its own condition** — the reification is acyclic by construction
   (`preCombatStat`/`firstTurnStat` exclude the conditional sub's own start-of-combat effects). Keep
   that acyclicity.
4. **Certifier stays sound.** The certificate (`MostMasteriesCertificate` / `MaxDamageCertifier`) is a
   separate proof authority; it already handles conditions via world splits and is unaffected by a
   search-side change, but if you alter the choosable set or the semantics, re-run its locks and bump
   `CERTIFIER_VERSION`.

---

## 6. Current direction and tested alternatives

- **A. Continue the independent certificate (CURRENT).** The sound D·Graw prototype now bounds S4
  by **28.85412T = 1.6300×** the incumbent, improving the prior sound 30.85T/1.743× result. The latest
  tightening replaces an impossible negative-secondary stack (all weapon types and multiple rarity
  layouts) with a legal distinct-ring/weapon/EPIC/RELIC maximum: `armSecCap 3323 → 2587`. The binding
  path is still `secZero` and saturates AP16/MP8/CC100/HP12000. Independent exact HP/CC envelopes
  were also tested soundly, but other relaxed paths sharing the binding key reach HP12000/CC100, so
  the coarse bound stayed 31.85706T; the experiment was reverted. A sound support function now does
  preserve the missing tradeoff inside each key: `Hλ=max(W+λ·positiveCC)`, collapsed over CC bands
  using `W≤Hλ−λ·bandLow` and target credit at `bandHigh`. At `λ=5000` it lowers the coarse bound to
  **30.87351T (−3.09%)** without adding states; λ7000/10000 are worse. Three deterministic exact-pool
  CP-SAT locks with a real CC target remain green. The canonical fine grid confirms the gain:
  **28.04238T = 1.5841×**, down from 28.85412T/1.6300×, with the same 46,857,707 states and a measured
  wall of 1,979,857 ms (32m59.9s). A further sound item-layout coupling removes the independent
  combination of best `W0` gear with another layout's 2587 negative-secondary budget: each item is
  priced directly as `W0 + 500·Nitem`. The combined coarse bound drops sharply
  **30.87351T→24.13557T (−21.83%, 1.3634×)** with no new state, and all three exact-pool locks stay
  green. The fine grid confirms **21.92445T = 1.2385×** (down 21.81% from 28.04238T), with the same
  46,857,707 states and 1,708,112 ms DP wall. The certified interval is now
  **[17.7020781465T, 21.92445T]**. The existing production certifier only bounds raw damage and
  cannot directly represent S4's unmet-target soft penalty, so this remains a separate proof path,
  not yet a `CERTIFIER_VERSION` port. A stronger item identity
  `Pscenario <= t + Nitem - positiveNonScenarioSecondary(item)` then removes the remaining
  impossible Neutralité path: coarse **24.13557T→23.58279T**, with `plain` now winning. Finally,
  exact signed DI + exact CC inside the shared normal-sub knapsack pays Vélocité II's −10 DI
  rider and stops rounding each +3 CC copy independently; coarse falls another **10.43% to
  21.12267T = 1.1932×** and all three exact-pool locks remain green. Re-running those locks with
  the current λ=7000 configuration gives 3.3218×/1.5331×/1.5373×.
  An exact bit coupling `Expert des armes légères` to the weapon layout was measured but rejected:
  only −0.86% bound for +120% wall. Full log: `MOST_MASTERIES_PERF_PLAN.md` §9.
  The canonical fine grid confirms a **20.48772T = 1.1574×** sound bound (53,452,911 states,
  2,856,407 ms): a 6.55% improvement over 21.92445T, at +14.1% states / +67.2% wall. The certified
  interval initially became **[17.7020781465T, 20.48772T]**. Re-sweeping the support after the arm
  changed finds a new λ=7000 plateau (λ=10000 is identical in coarse grid). The canonical fine
  rerun certifies **20.40555T = 1.1527×** with the same 53,452,911 states and 2,821,827 ms wall.
  The current interval is **[17.7020781465T, 20.40555T]**, i.e. a proven maximum gap of **15.27%**.
  Its binding state is still `plain`, CC band 100, `Wupper=4,224,500`, d=94; the next certificate lever is a per-key multi-slope CC support envelope,
  not another independent CC maximum or a full exact-CC dimension.
  Iteration no longer needs that 47-minute gate: `scripts/s4-certificate-screen.sh` provides a
  stamped non-certifying `base/plain` screen plus `coarse`, DI-only, CC-only and HP-only profiles;
  `coarse cert` covers every world/arm and `fine cert` is reserved for promotion. It uses
  `cleanTest` so environment-only experiments reuse compiled classes, and optional timings expose
  every stage/world wall.
  Measured: `coarse plain` 9 s, DI-only plain 21 s, all-world coarse certificate 2m28s. A sound
  adaptive gate keeps coarse bounds for every world and DI-refines only contenders; here it refines
  base/plain once, proves the same **20.40555T** bound in **3m06s** (5.01M states), and stops because
  the next coarse world upper is 20.06691T. This replaces the 47-minute grid for normal promotion;
  the full fine run remains an occasional calibration/nightly check.
  **2026-07-16 update:** four further sound prototype tightenings changed the scale of the result:
  signed item `MAX_AP` in the AP axis; signed item/NORMAL-sub `MAX_MP` in the existing MP axis;
  corrected `secZero` algebra that permits only negative mastery *outside* the scenario to fund the
  condition; and a value-side union for `Expert des armes légères` (`noExpert` versus weapon-eligible
  layouts). The extra `mpCapMinus` bit was measured at **13m31s** and replaced: coarse worlds retain
  the loose MP read, while only contenders pay the signed refinement. A per-key multi-slope CC
  envelope was also measured with `{0,7000}` and `{7000,10000,20000,50000}`: **no gain**, about
  18 s → 74 s, fully reverted. Re-sweeping the surviving single support gives λ=6000. The sound
  adaptive all-world result is now **17.7628131285T** in **6m54s** / 9,949,088 states, versus the
  independently CP-SAT-proven no-condition optimum **17.7020781465T** (58.4 s). Thus the current
  certified interval is **[17.7020781465T, 17.7628131285T]**, only **0.343095%** wide. The exact
  deterministic seeded locks are green at ratios 3.3141× / 1.5351× / 1.5350× with all flags on.
  Full measurements and the incumbent provenance are in `MOST_MASTERIES_PERF_PLAN.md` §9.15.
  **Forensic follow-up:** reconstructing the certificate's own support provenance proves that it is
  not a better build missed by CP-SAT. Its concrete path has CC97 (not 100) and exact score
  22,251.2901; the sound support envelope combines its `Hλ` with the separate CC100 rectangle.
  CP-SAT proves the no-condition AP15/MP8/CC100/HP12000 cell exactly at raw **19,382,375** in
  14.76 s, identical to the incumbent's raw proxy. Removing all conditional subs leaves the
  certificate bound unchanged. A naive exact-CC DP tightens only to **17.760644022T (+0.330842%)**
  while costing 21.13M states / 36m57s; HP=100 is inert and costs 15m40s. Verdict: the remaining
  difference is certificate-envelope looseness, not a CP-SAT incumbent miss. See §9.16 for the
  exact items/runes/subs and the cutoff/cell controls.
- **A3. Complete semantic partition (PROTOTYPE PROOF, 0.0000%).** Split the feasible set into
  builds selecting no condition-bearing sub and builds selecting at least one. CP-SAT already
  proves the first partition exactly at **17.7020781465T**. A dormant certificate key marker tracks
  the second partition only from the sub stages onward; normal-sub packing, assumed cap worlds and
  objective-cap arms preserve the partition. An adaptive run keeps ordinary coarse bounds for all
  worlds already below the CP optimum and condition-refines only the contenders. It returns:
  conditional base/plain **17.0253169185T**, conditional critZero **16.9211998065T**, next coarse
  world **17.6934017205T**, hence final union upper **17.7020781465T** exactly. Wall is **10m48.8s**
  / 12.73M aggregate states. A new deterministic CP oracle constrained by
  `sum(condition-bearing sub vars) >= 1` proves seed 1 at 0.551021875745T; the conditional
  certificate covers it at 1.885243906T. This is the first complete S4 proof, but still test-side:
  run the other two partition locks, type/cache the no-condition proof input, reduce wall, then port
  and bump `CERTIFIER_VERSION`.
- **A4. λ is tuning, not semantics.** A targeted second support located the 97→111-positive-CC
  knee at μ=6100, but only reduced W by 300 and left the integer final bound unchanged while slowing
  the DI world ~92→169 s; it was reverted. Any λ≥0 remains sound and can only change tightness.
  λ=6000 is measured for S4, not universal. This prototype supports required AP/MP/CC/HP targets;
  unrelated shapes such as an impossible 10,000-resistance request must bail as unavailable rather
  than reuse the S4 tuning.
- **B. Direct selected-sub implication (MEASURED-NO; reverted).** The exact projection
  `subVar ⇒ condition` removes the otherwise-private `b ⇔ condition` variable for choosable subs;
  forced subs retain full reification. Semantic locks passed. At fixed seed, 1 worker,
  `interleaveSearch=true`, det 60, it improved the early incumbent 6.286T→10.833T but worsened the
  dual 71.380T→77.210T (+8.17%) and wall 144.6→184.7 s (+28%). A real-parallel 180 s sample was also
  worse (dual 35.921T→39.431T). Do not retry this change alone without a new propagation argument.
- **B2. Share identical predicates (MEASURED-NO; reverted).** Four subs have the same first-turn
  `secondary masteries ≤ 0` predicate. Memoizing by normalized condition reduces 15 modeled entries
  to 12 unique reifications and is exactly equivalent, but at fixed seed/1 worker/interleave/det 60
  it worsened the dual **46.790T→50.920T (+8.83%)** and the incumbent 7.526T→4.218T. Wall improved
  156.2→130.4 s, but proof quality is the gate. The duplicate predicates were useful propagation;
  this optimization was reverted.
- **B3. Direct structural conflicts (MEASURED-NO; reverted).** Replacing only the choosable
  `NO_OFFHAND_OR_TWO_HANDED` reification by exact pairwise `sub + offhand/2H ≤ 1` constraints improves
  incumbent 7.526T→7.773T and wall 156.1→132.0 s, but worsens the dual
  **46.790T→50.920T (+8.83%)**. Forced-sub inert semantics stayed fully reified. This lands on almost
  the same bad dual as B2: local removal/projection of reifications is now an exhausted family.
- **C. Search-side WORLD SPLIT (unimplemented).** Solve one CP-SAT model per *condition
  world* where each condition is a **constant** (assumed held / not held), so no indicator remains —
  each world proves in ~50 s like the no-cond case; the answer is the max over worlds. The naive
  power-set is 2^15 modeled conditions, so the leverage is in **soundly pruning worlds**. The
  certificate may supply bounds, but equality with the no-cond incumbent is not itself a pruning
  proof. Watch AT_MOST conditions that force an objective stat low.
- **D. Lazily add conditions.** Solve no-cond first (proven, 49 s). Then, per conditional sub, test
  whether adding just that sub (its condition reified) can raise the objective above the no-cond
  optimum; skip the ones that can't. Only the survivors enter a combined solve. This is B&B over
  conditions, but single-sub tests alone do not cover improvements requiring several coexisting
  normal subs; pruning must use a sound combined upper bound.
- **E. Presolve / linearization knobs.** The max-damage path already sets `linearizationLevel = 2` and
  `maxPresolveIterations = 3` (`WakfuBuildSolver.kt` ~L2904/L2918). Try higher presolve iterations or
  `numSearchWorkers` portfolios tuned for indicator-heavy models. Cheapest to try; least certain.

**Gate for a CP-SAT fix:** the full-subs S4 soft leg reaches `status=OPTIMAL`; do not require the
result to equal 17 702 078 146 500 until the full model proves it. Target wall is comparable to the
badge budget (~1 min real-parallel). **Gate for a certificate fix:** every exact-pool and banked
incumbent soundness canary remains `bound ≥ feasible objective`, and the S4 interval strictly
tightens. Keep every sublimation differential lock and certificate soundness lock green.

---

## 7. Pointers

- Full measurement log: `docs/MOST_MASTERIES_PERF_PLAN.md` §9.10–§9.14.
- Reification: `SublimationTerms.kt` (`appliesVar`, `reifyCondition`, `reifyStatBound`,
  `reifyLe`/`reifyGe`). Semantics: `SublimationSemantics.kt`. First-turn read:
  `StatBuilder.firstTurnStat` / `startOfCombatFlatSubTermsByStat`, and `SublimationTerms.
  buildStartOfCombatFlatSubTerms`.
- Production solve branch: `WakfuBuildSolver.optimize` → the `if (tuning == null)` portfolio path.
- Scalar mirror: `FindClosestBuildFromInputScoring.subConditionHolds`.
- Regression origin: commit `39532d15` (condition-timing fix — correct, do not revert).
- The test harness + all env toggles: `MostMasteriesPerfExperimentTest` (`mdFrontierShape` = the S4
  fixture; `solve()` = the `tuning==null` switch).

---

## Post-scriptum 2026-07-16 — a FAST testbed for encoding experiments exists now

Cost attribution at **cra-140** (plain full-model solve, `WAKFU_S4_CP_PLAIN=1` +
`WAKFU_S4_SHAPE=cra140-apmp`, 8 workers): no subs **0.38 s**; 204 unconditional subs **4.5 s**;
+28 conditional subs **104 s** (×23). The optimum is a no-conditional build proven in 4.5 s — the
remaining ~100 s exclusively pay the conditional-sub reifications. So the wall described in this
document exists at low level too, merely crossable — and cra-140 is therefore a **measurable-scale
testbed** for any condition-encoding experiment: a candidate encoding that cuts 104 s meaningfully
at 140 is worth re-testing at 245, without waiting hours for UNKNOWNs. (Incumbent-floor cutoffs are
measured HARMFUL in both directions — do not use them in these A/Bs.)

## Post-scriptum 2 (2026-07-17) — encoding campaign CLOSED: the wall resists all local surgery

A second full encoding campaign ran on the cra-140 testbed (fast, provable scale — see
`MOST_MASTERIES_PERF_PLAN.md` §9.22 for the complete matrix): redundant big-M rows + conflict
cliques (M1, adds-only), a conditional-first decision strategy ±`fixed` subsolver (M2/M2b), the
E-family knobs (lin0/lin1/presolve/detectLinearizedProduct), incumbent objective floors, and
hard-AP-cell decomposition. **Verdict: with B/B2/B3 above, twelve measured arms agree — no local
encoding or parameter surgery digests the conditional reifications.** Two positives shipped:
`linearizationLevel=1` (det −34% on the proof, the only winning knob) and per-shape λ min-sweep in
the DP union. The remaining structural route is a K-dimension (crit-mastery-aware) certificate
rework: the DP's main-world residual is W priced at critCap weights on low-cc binding states.

---

# HANDOFF 2 (2026-07-17) — the remaining problem: proofs for CONDITIONAL-CARRYING optima

Self-contained brief for the next agent. Everything below is measured (fast-locks-first protocol:
run the seeded locks before ANY full-pool measurement — see `fast-locks-first` in the project
memory and `seededPools()` in `MaxDamageSoftCertificateTest`).

## 1. State of the world (all shipped, all green)

The soft-leg (unreachable-targets) proof pipeline is `MaxDamageSearch.proveSoftLegQuality` →
`MaxDamageSoftCertificate.hybridUnionUpper`, three sound authorities in cascade:

1. **RELAXED probe** (step 0, 45 s budget, ungated): conditions STRIPPED (subs keep slots+credits,
   ZERO reifications) — sound upper on every build. `relaxedUpper ≤ incumbent` ⇒ ProvenOptimal
   immediately. **Soundness correction (CERTIFIER_VERSION 17):** the original implementation only
   cleared `condition`; that made `STATIC_CONDITIONAL` fail `isModelableSubShape` and silently
   disappear, so the claimed cra-140 exact/4.1 s result was not a valid upper bound. The corrected
   transform changes those entries to `FLAT`, preserves single-copy semantics, and only strips
   supported modeled conditions. Correct cra-140 read: **2.377161024480T in 2.87 s**, +13.49% over
   the 2.094581834070T incumbent — useful upper, but it does NOT close alone.
2. **Hybrid union**: no-condition CP-SAT oracle (proven optimum or dual) ∪ conditional-only DP
   (λ min-sweep {6000, 0} gated at 10%). Exact at S4-245 (~3-4 min).
3. **Plain full-model probe** (420 s, gated at 10% DP gap): proves outright on small pools
   (104 s cold at cra-140), stalls on large ones.

Verdict comparison is in PENALIZED units against `result.maxDamageObjective`; self-check
(incumbent > upper ⇒ error + Unavailable); badge floor at 25% gap.

## 2. THE remaining gap

A request whose optimum CARRIES ≥1 conditional sub gets NO fast path: relaxed can't conclude,
and the union/probe take 1-6 min. This is the target. Reference numbers (cra-140 testbed,
`WAKFU_S4_SHAPE=cra140-apmp`, seams in `MaxDamageSoftCertificateTest`):

| model | proof |
|---|---|
| no subs | 0.38 s |
| 204 unconditional subs | 4.5 s |
| + 28 conditional (15 modeled) | 104 s cold / >300 s hot |
| conditions stripped (corrected relaxation) | 2.87 s, 2.377T (+13.49%, does not close) |
| AP pinned (cell) | 83 s |
| (AP,MP) pinned — penalty CONSTANT, zero bilinear | STILL FEASIBLE at 120 s |

The wall is purely **reifications × damage-objective** (not the penalty product, not pool size,
not the encoding shape). CP-SAT profile: the LP never cuts fractional conditional subs; full
workers grind 10⁶-10⁷ branches / 10⁸ propagations refuting them via CDCL.

## 3. DO-NOT-RETRY (14 measured arms, two campaigns)

- B-family: remove/project reifications (B), share predicates (B2), structural conflicts (B3).
- M1: ADD redundant big-M rows + pairwise conflict cliques (feasible-set-neutral — still worse).
- M2/M2b: decision strategy conditional-bools-first, ± `extraSubsolvers("fixed")`.
- Knobs: lin0/presolve8 worse; detectLinearizedProduct and symmetry 3/4 inert; lin1 is
  seed-sensitive with two large dual regressions in five controlled pairs. None dominates.
- Objective-side cutoffs/floors in EITHER direction (incumbent floor = 2.4× worse; certcap dead).
- Cell decomposition (AP or AP+MP pins) as a <10 s route.
- Conditional-only + cutoff probes as an inference tool: that model is strictly HARDER than the
  plain solve (near-optimal-infeasibility proof) — never infer "won't prove" from it.

## 3bis. Resolved A/B: lin1 does not dominate

`linearizationLevel=1` tightened the first controlled cra-140 dual **3.697T → 2.872T (−22%)**,
but the completed five-pair campaign below found large seed-dependent reversals (up to +24.6%).
It is now a measured NO-GO; production remains lin2.

## 3ter. Controlled re-test queue — COMPLETE (2026-07-17)

All formerly confounded §9.22 candidates were re-run as 1-worker/fixed-seed/interleave/det-120
same-JVM pairs with alternating order. None dominates, so there is no winning arm to cross-combine.
NOT worth re-testing: the B-family (already measured under this protocol), the incumbent floor
(×145 branches — noise-insensitive), cell decompositions (structural economics dead at any clock).

### Controlled result: isolated conflict cliques are dead (2026-07-17)

The M1 cliques were isolated from its redundant big-M rows and re-tested as sound implied
`subA + subB ≤ 1` clauses only. Protocol: cra-140-apmp, 1 worker, interleave, det=120, two seeds,
same-JVM pairs with alternating order. At equal deterministic work the candidate's dual ratio
was **1.0049** and **1.0246** (higher is worse); incumbent deltas were −6.80G and +27.19G.
Seed 2 did cut branches to 0.369×, but still worsened the dual by 2.46%, so the extra conflicts
steered search without strengthening the proof. **NO-GO, implementation reverted.**

### Controlled result: lin1 is seed-sensitive, not a production win (2026-07-17)

The open lin1 result was settled across cra-140-apmp (three seeds) and iop200-frontier (two seeds),
again as alternating same-JVM 1-worker/interleave/det=120 pairs against lin2. Candidate/baseline
dual ratios were CRA **0.7770 / 1.2465 / 0.9050**, IOP **0.9850 / 1.1777**. Thus lin1 improved
the bound in three runs but regressed catastrophically in two; on IOP it also returned a worse
incumbent on both seeds. It redistributes work between LP/CDCL rather than dominating lin2.
**NO-GO for production; lin2 stays the default.**

### Controlled result: conditional-first `fixed` search is dead (2026-07-17)

Adding the `fixed` subsolver made CP-SAT honor the existing decision strategy (conditional sub
booleans first, zero first). On cra-140-apmp, two controlled pairs returned dual ratios **1.0409**
and **1.2473**, with branch ratios **6.04×** and **1.99×**. The proposed native world split
therefore drives search into a much worse tree. **NO-GO; portfolio unchanged.**

### Controlled result: symmetry levels 3/4 are inert (2026-07-17)

`symmetry_level=3` produced bit-for-bit identical search metrics to baseline on two cra-140 seeds:
same incumbent, dual, branches, conflicts and LP iterations. Level 4 was then screened on one seed
and was equally identical. The apparent repeated secondary-mastery predicates do not expose extra
symmetry to these settings after presolve. **No effect; production unchanged.**

### Controlled result: presolve8 loses; product detection is inert (2026-07-17)

Presolve8 returned dual ratios **1.0397 / 1.2531** on two cra-140 seeds. It improved the incumbent
and reduced branches, but weakened the proof bound — the wrong trade-off for this certificate.
`detectLinearizedProduct=true` was then bit-for-bit identical to baseline on both seeds. **Both
NO-GO; the controlled CP-SAT parameter/encoding queue is exhausted.**

The final normal test suite also caught a pre-existing seam debt: `maxDamageMpPin` had not been
listed in the certificate fingerprint tripwire. It is now normalized to `null` in the cache key,
exactly like the AP pin (both are probe-internal and the certifier bails while active), and locked
by an explicit fingerprint test. This prevents distinct in-memory keys sharing an accidental disk
fingerprint while preserving cache hits for the real unpinned request.

## 4. Open routes, in order of expected value

1. **K-dim DP rework (the structural fix).** The conditional-only DP's residual looseness at low
   level is diagnosed to the point: the binding path prices `W = (400+critCap)·M + 5·critCap·K`
   at critCap=100 on states whose actual crit is ~30 (`WAKFU_S4_PATH` read, §9.22). Tracking the
   crit-mastery component (K) — or Pareto (M, K) pairs — per state would let the collapse price
   the crit factor at the state's OWN cc. If the conditional DP falls UNDER the no-cond oracle at
   low level, the union closes exactly in oracle+DP ≈ 10-15 s with NO CP-SAT probe — including for
   conditional-carrying optima. Sound intermediate step measured available: collapse-time W
   downscale by `max((400+ccHigh)/(400+critCap), ccHigh/critCap)` (per-component ratio bound) —
   sound, unimplemented.
2. **Conditional-world B&B — SHIPPED in v20.** Flat per-carrier worlds were measured and rejected;
   the exhaustive recursive partition `{s=0 | s=1 + exact condition}` is the structural version
   that closes CRA-80/110. Production enables it only in the measured level≤110 regime, for at
   most 180 s. An unfinished tree returns `min(relaxedUpper, frontierUpper)` directly, avoiding
   serial accumulation with the legacy 240/420 s budgets. Higher levels keep the old DP/oracle
   route unchanged. Next optimization is sibling parallelism under a divided worker budget.
3. λ per-shape continuous calibration / per-band envelopes: measured marginal (§9.21), only worth
   revisiting after 1.

## 5. Gates that must stay green

`WAKFU_S4_UNION_LOCK=1` (seeded union soundness — run FIRST on any change);
`WAKFU_S4_PROD_PROOF=1` E2E at both `WAKFU_S4_SHAPE=cra140-apmp` (timing must be re-banked under v18)
and default S4-245 (ProvenOptimal); `WAKFU_S4_PROD_SCREEN=1` generality (sound everywhere, RANGE
bails); the 16 sublimation differential locks; full `:autobuilder:test`.

## 6. Code map (current)

- `MaxDamageSoftCertificate.kt`: the DP + `hybridUnionUpper` (relaxed probe STEP 0, oracle future,
  coarse+cascade `refinementPass(lambda)`, λ-min, plain probe, memo PROVEN-only).
- `MaxDamageSearch.kt`: `proveSoftLegQuality` (units, self-check, badge floor 25%),
  `SOFT_ORACLE_BUDGET_SECONDS`.
- `MaxDamageSoftCertificateTest.kt`: `shapePreset()` (`WAKFU_S4_SHAPE`), `seededPools()`, the
  union lock, E2E (prints `proofWallMs`), generality screen, `binding-arm` CP harness with env
  seams (`WAKFU_S4_CP_{PLAIN,RELAXCOND,NOSUBS,NOCOND,CELL,CELL_SOFT,APCELL,MPCELL,LIN,PRESOLVE,
  DETECT_PROD,SYM,EXTRA,CUTOFF,SECONDS,WORKERS,LOG,DET,INTERLEAVE}`).
- `WakfuBestBuildParams.maxDamageMpPin` + `WakfuBuildSolver` pin equalities: probe-internal seams.
- Measurement discipline: `./gradlew --stop` first; `WAKFU_TEST_MAX_HEAP=8g`; compare in `det`
  (wall is thermal: the same proof measured 104 s cold and >300 s after hours of benching);
  NEVER run concurrent gradle during timing arms; `caffeinate -i` on long runs.
- **Protocol note (user review):** the §9.22 arms ran on the 8-worker wall-clock portfolio —
  their BINARY reads (OPTIMAL vs FEASIBLE-at-cap) are protocol-robust, but fine det comparisons
  on the portfolio are not. For any encoding A/B (including resurrecting a do-not-retry with a
  new argument), use the CONTROLLED protocol: `WAKFU_S4_CP_WORKERS=1 WAKFU_S4_CP_INTERLEAVE=1
  WAKFU_S4_CP_DET=<budget>` at a fixed seed, same-JVM pairs — the B/B2/B3 standard. Provability
  conclusions ("does it prove at all") still need a real-parallel confirmation run (§9.11).

## HANDOFF 3 (2026-07-17) — corrected relaxation + iterative condition generation

The first naturally conditional-carrying, quickly provable fixture is CRA-80/free with the
conditional-only catalog (16 decoded entries, 15 solver-modeled):

| model | result |
|---|---|
| exact conditions | OPTIMAL **522720**, 11.81 s; selected `6821, 8492` |
| corrected condition-stripped upper | OPTIMAL **663120**, 1.04–1.27 s; selected `6821, 6931, 7115, 8492` |

This exposed the v16 under-count: the former `copy(condition=null)` relaxation returned 452160
because every `STATIC_CONDITIONAL` was filtered out. v17 centralizes the correct transform in
`Sublimation.withRelaxedBuildStaticCondition()` and bumps the certificate cache version.

### Structural arm measured: outer approximation / lazy condition generation

Prototype seam: `WAKFU_S4_COND_REFINE=1 WAKFU_S4_SHAPE=cra80-free`. It solves the relaxed upper,
pins every equipment/skill/rune/sub decision in the exact model (validation costs only 1–10 ms),
then restores condition gates and repeats. A pinned assignment that validates exact while its
upper solve is OPTIMAL proves the global exact optimum.

- **Coarse restore (all selected conditions):** closes exactly in 4 upper solves: 1.27 s → 17.58 s
  → 12.84 s → 51.66 s, **84.30 s total**. Sound, but 7× slower than the direct exact solve.
- **Selective restore (only individually violated selected conditions):** iteration 1 restores
  `6821, 6931, 7115`; iteration 2 finds the exact incumbent immediately but remains FEASIBLE at
  60 s with bound 577440. Leaving the satisfied `8492` condition relaxed weakens the global bound;
  fewer reifications are not monotonically better.

**Verdict: NO-GO for production in this form.** Keep the research seam and measurements; do not
retry full-assignment no-goods or simple lazy gate restoration without a stronger generalized
cut. The validation is free, but each partially reified upper model recreates the same proof wall.
The next genuinely different CP route would need an unsat-core-derived condition/stat cover cut
or per-carrier world bounds, not another local indicator encoding.

### Structural arm measured: per-carrier worlds, then external condition B&B

The naive sound union `{no conditional} ∪ {one forced exact carrier s, every other condition
relaxed}` is **too loose** on CRA-80 conditional-only: 16 worlds / 52.56 s, union upper **663120**
(identical to the root relaxation). In the `8492` world, the model still takes `6821, 6931, 7115`
for free. **NO-GO as a flat enumeration.**

The useful refinement is an **external B&B over sub selection**. At any node, solve a mixed upper
model. If its assignment abuses a relaxed selected sub `s`, partition the exact builds into:

- `s = 0` (exclude it), and
- `s = 1` with `condition(s)` restored exactly.

This partition is exhaustive because a solver-choosable selected sub in the exact model must meet
its condition. A node can be pruned whenever its CP-SAT dual is `≤ incumbent`, even if status is
only FEASIBLE. It is also sound to run the normal per-node domination: each mixed model's
`dominationShape` pins every stat read by the conditions that remain exact, so
`exactNodeOpt ≤ mixedNodeOpt = dominatedMixedOpt ≤ CP dual`.

Branching on the largest rough damage marginal (DI, then AP/MP/range/crit, then ordinary stats)
is important. CRA-80 conditional-only:

| protocol | result |
|---|---|
| ID-order, 15 s/node | 11 nodes, proven, 62.78 s |
| impact-order, 15 s/node | 7 nodes, proven, 26.76 s |
| impact-order, 5 s/node | 7 nodes, proven, 17.77 s |
| impact-order, 2 s/node, 8 workers | proven in 9.47 / 10.51 s twice, but one hot repeat stopped inconclusive |
| deterministic 1-worker/interleave | needs det=30/node; proven at **108.79 det**, worse than exact ~61.79 det |

Thus the tiny fixture win is portfolio-sensitive, not algorithmic domination. The full-catalog
result is stronger: the previously unproved CRA-80 incumbent **820040 is now PROVEN OPTIMAL** in
7 nodes, reproduced at **94.73 s** and **86.80 s**, while monolithic CP-SAT was still FEASIBLE at
120 s (bound 1,018,080). The tree is stable: exclude `6931 → 7115 → 6821`; the no-three branch
closes at 820040, and the three selected branches have duals below it.

The second full-catalog gate also closes: CRA-110 incumbent **1565500 is PROVEN OPTIMAL** in
9 nodes / **124.07 s**, whereas monolithic CP-SAT was still FEASIBLE after 300 s (1,565,500 /
1,885,670). Once the current assignment became exact-valid but its parent dual stayed open, the
B&B soundly branched on the highest-impact condition still relaxed (`7256`); its excluded child
closed at 1,547,320 and its forced child dual was only 762,035.

The engine is now production code (`conditionalWorldBranchAndBound`; the old `ForTest` wrapper
remains for the measurement harness) under **CERTIFIER_VERSION 20**. Its bounded result is typed:
`Proven(upper)`, `Inconclusive(frontierUpper)`, or `Counterexample(exactObjective)`. Production
spends at most 180 s total, 15 s per node, 64 nodes at level≤110. `Proven` returns immediately;
an inconclusive sound frontier is combined with the corrected relaxed probe and returned, while
a counterexample returns the relaxed global upper. The legacy DP/oracle path remains unchanged
above level 110.

Two independent locks guard it. The fast non-manual campaign uses 8 seeded three-condition pools;
for every seed it closes at the pinned exact optimum and, critically, rejects `optimum − 1` by
exhibiting a pinned exact counterexample. The mandatory v18 production-union campaign then passed
3/3 against independent one-worker CP-SAT optima in 9m19s: seed1 `568852079565`, seed2
`2276045688840`, seed3 `2113135111800`, all with `union == optimum` (ratio 1.0000).

An initial v18 CRA-80 E2E run exposed an operational bug: after the 150 s world budget missed on
a hot machine, it stacked the old oracle/DP budgets and was still running after ~7 minutes. v19
also treats an OPTIMAL node's rounded objective as its bound (rather than `ceil` of a floating
dual that could become optimum+1) and makes the low-level budget terminal.

Remaining performance work: re-measure the real production E2E shapes under the new 180 s policy,
then consider sibling-node parallelism with divided workers. The soundness and bounded-fallback
gates required for shipping are closed.

The first v19 bounded CRA-80 run terminated soundly in 194.6 s including the relaxed probe, but
remained at `937840` (+14.36% over 820040). Profiling the orchestration exposed hidden work not in
the per-node wall summary: one pinned solve per selected branch candidate, used only to classify
which condition was individually violated. v20 removes those probes and branches directly on the
largest-impact selected conditional. The binary partition stays identical and exhaustive; only
the performance heuristic changes.

The v20 production re-run closes exactly: `upper = incumbent = 820040` (`noCondProven=true`) in
**200.4 s E2E** on the hot machine, including ~20 s for the corrected relaxed probe and the full
world-tree orchestration. This is the honest production timing (not merely the sum of recorded
mixed-node solves): bounded to about 3m20 and exact on CRA-80, versus v19's similarly bounded but
only +14.36% result and monolithic CP-SAT still open at 120 s.

### Level-245 generalization screen — NO-GO (v21, 2026-07-17)

There is no soundness barrier to running the same tree at 245, but the measured dual economics are
bad. Full catalog, incumbent `17,702,078,146,500`, 8 workers, 15 s/node, 180 s total:

- exclusion-first v20: 11 FEASIBLE nodes, frontier `50.920T` (finite-time child duals could even
  exceed their parent before intersection);
- forced-first plus v21 parent-bound intersection: 16 nodes, several forced compounds prove
  INFEASIBLE in ~1 s, but every still-feasible forced node retains the root dual; frontier
  **36.515T**.

Both are much worse than the existing fine DP upper **20.406T** and incumbent 17.702T. The issue
is not DFS order: on the level-245 pool a 15 s CP-SAT node does not tighten its dual below the
ancestor, so partitioning merely creates more large proof obligations. Keep the production gate
at level≤110.

One free tightening survives the screen and ships as v21: every child upper is intersected with
its inherited parent upper. A child world is a subset of its parent, so `min(childDual,parentDual)`
is exact and prevents timeout noise from worsening a deeper frontier. `requiredFirst` remains a
manual research seam only; production keeps the proven low-level order.

### Universal-certificate restart: crit-aware scalar supports (research, OFF)

Following the product strategy review, further conditional-CP heuristics are deprioritized: the
target order is monolithic CP-SAT ≤2 min, then one fast certificate across all levels. The first
certificate experiment avoids a dense `(M,K)` key dimension. The DP still stores one scalar
`WA=(400+A)M+5AK`; at collapse, for CC-band high `c`, it soundly transports that support by
`max((400+c)/(400+A), c/A)`. With λ support active even when CC is not a requested target, this
adds no states and is independently sound for every anchor `A>0`.

The seeded 3-pool CP-SAT lock passed with the transform ON. CRA-80/full catalog/conditional
partition, all production tightenings, 834,540 states:

| support | bound | gap vs 820040 | DP wall |
|---|---:|---:|---:|
| old scalar, no crit-aware bands | 1,686,240 | +105.63% | 5.35 s |
| A=100, λ=6000 | 1,362,240 | +66.12% | 5.39 s |
| A=100, λ=2000 | 937,440 | +14.32% | 4.55 s |
| **A=100, λ=1500** | **928,800** | **+13.26%** | **4.93 s** |

Multiple scalar anchors compose soundly as `max_CCband min_anchor upper(anchor,band)`. Anchors
`{50,75,100}` improve to **918,000 (+11.95%)**; adding 25 changes nothing. The dominating band is
75..79, where A=100 is already best (`A25=3,140,640; A50=1,495,440; A75=954,720;
A100=918,000`). Thus the remaining 11.95% is NOT primarily the M/K projection: a dense K
dimension or more anchors is unlikely to close it. Next certificate work should inspect the
band-75 binding world's path merge / condition arm, not spend more state on crit weights.

### Universal-certificate restart: signed MP ramp + support calibration (research, OFF)

Band/world provenance localized the CRA-80 envelope winner to
`A=100 / base / plain / expertEligible`. Its reconstructed path contained `Poids Plume III`, but
the DP had deliberately dropped the MP state when MP was not requested and priced the MP→DI ramp
at the globally reachable MP. That mixed the best mastery path with a 24-DI ramp it did not own.

The research seam `stateDependentMpRamp` now keeps the unique MP→DI ramp inside the exact
10-normal-sublimation knapsack, tracks signed MP, and evaluates the ramp only from the collapsed
path's MP. If a future catalogue has more than one such ramp, the certifier bails. A fast
non-manual semantic lock uses incompatible MP/mastery item choices and an exact CP-SAT oracle; the
new bound remains above the optimum and is strictly below the legacy ramp relaxation. It passes in
~0.35 s. A second non-manual campaign covers eight deterministic random pools (three item slots,
positive/negative MP, elemental/distance/critical mastery, DI and crit, with free/MP/crit targets):
every coupled bound covers its exact CP-SAT optimum and is never above the legacy bound. The whole
targeted lock takes 39 s. The older all-catalogue seeded campaign was stopped after 40 minutes;
this isolated replacement gives useful coverage without turning normal CI into an overnight job.

Measured impact with all other research seams enabled:

| shape | incumbent/oracle | signed-ramp upper | gap | DP wall |
|---|---:|---:|---:|---:|
| CRA-80 free, `{25,50,75,100}` band envelope | 820,040 | **825,840** | **+0.707%** | ~75 s |
| CRA-110 free, A=100 | 1,565,500 | **1,595,805** | **+1.94%** | 21.1 s |
| CRA-140 AP14/MP7, A=100 | 2,094,581,834,070 | **2,270,344,148,380** | **+8.39%** | 33.5 s |

For CRA-80, the former binding path's fictitious ramp disappears (`ramp=0`). A one-anchor adaptive
all-world pass reaches **836,640 (+2.02%) in 11.2 s**; the multi-anchor/finer envelope buys the
last 10,800 points shown above.

The crit-support dual price λ is not universal. On IOP-200's dominant `secZero` world, otherwise
identical DI=1 reads were 9.630T at λ=0, 9.330T at λ=1500, and **9.168T at λ=6000**. Conversely,
forcing λ=6000 on CRA-80 `secZero` made that arm dominate at **1,195,200 (+45.75%)**, while λ=1500
keeps every arm in the 0.79–0.84M range. The harness now has an OFF research policy: λ=1500 below
level 175; at high level, only `secZero` uses 6000. This is sound because every non-negative λ is
an independent upper bound; the policy only selects which sound projection to compute.

A second proposed dual support `W + λ·CC + μ·HP` was measured and rejected. Scaling all HP by the
maximum attainable HP% aptitude made μ=10 worsen IOP-200 from 9.168T to 9.420T and μ=100 explode
to 14.026T. The seam was removed completely. Do not retry an HP scalar support unless HP% is first
conditioned exactly (for example by an outer aptitude world); otherwise the percentage relaxation
dominates the intended HP/path correlation gain.

### IOP-200: the adaptive certificate now fits under two minutes (research, OFF)

The correctly partitioned hybrid (`no-condition oracle ∪ conditional-only DP`) first reproduced
the expected per-world-λ upper **9,167,663,226,930** against the conservative 8.9T oracle floor,
but took **321.9 s / 12,266,945 states**. Three sound orchestration changes retain that exact bound:

1. A best-first grid queue refines only the world with the largest *current* upper, one DI tier at
   a time. Once a DI1 world is the queue maximum, every other coarse/refined upper is below it.
   This removes useless DI4/DI1 passes on the initial coarse winner (`plain`).
2. `secZero`, `critZero`, and ASSUME worlds already imply a conditional carrier. Dropping their
   redundant selected-conditional bit is bit-identical by construction and by a dedicated
   equivalence lock; `secZero` DI1 falls from 3.96M to 2.64M states.
3. HP=2000 is bit-identical to HP=1000 on every final IOP authority (`plain`, `critZero`, and
   `secZero` DI1). Keeping that grid in refinements cuts the remaining state sets by more than half.

An intermediate result (best-first + marker elision, HP=1000) was **144.2 s / 6.06M states**. The
final research profile uses a HP=4000 coarse sweep, HP=2000 refinements, and skips the ineffective
`secZero` DI4 tier after its DI10 scout. It returns the same **9.167663T (+3.01%)** in
**73.6 s / 2,369,591 states** (82 s including Gradle startup): plain DI10 12.0 s, secZero DI10 6.1 s,
critZero DI10 8.9 s, secZero DI1 17.0 s. A DI20 scout was measured and rejected: it stayed above
the winner on plain/critZero and merely added 25 s.

### Production v22: bounded DP+DP fallback under two minutes

The same profile generalizes strongly to S4-245. With the known no-condition floor, the
conditional-only DP needs only plain/critZero DI10 and returns the exact **17,702,078,146,500** in
**103.5 s**. Plain is 17.238T, critZero 17.136T, and every remaining coarse world is ≤17.693T.

The production E2E exposed that relaunching a second CP-SAT oracle is incompatible with the time
target. Eight oracle workers competing with the DP stretch the proof to 178.4 s; 2 workers time
out at 240 s (44.63T dual), and 4 workers also time out / finish at 275.6 s (18.696T). A global DP
without the conditional partition is sound but lands at the historical **17.762813T (+0.343%)**
and takes 200 s, so it is not the answer either.

The useful decomposition is DP on both sides. Filtering the catalogue to `condition == null`
makes the no-condition DP tiny: HP=4000 is bit-identical and completes in about **12 s including
Gradle**, with upper 17.762813T. Production v22 uses that authority above level 175 and then the
best-first conditional DP; it no longer relaunches CP-SAT after the main solve has stalled. The
real production path returns the global **17.762813T (+0.343%)** certificate in **103.5 s runtime**
(the no-condition bound becomes the union winner). This deliberately changes the S4 badge from
`ProvenOptimal` to `ProvenWithin(0.343%)` while meeting the two-minute contract. Closing that small
no-condition residual is now the precise route back to exactness.

IOP-200 production E2E also stays comfortably inside the contract: **62.9 s runtime**, global
upper **9,267,128,776,880**, i.e. +4.13% against the conservative 8.9T incumbent floor. The
conditional authority alone is tighter (9.167663T, +3.01%), but the fast no-condition DP carries
the global union. This is the honest product number; the earlier 73.6 s/+3.01% was a
conditional-partition benchmark with the no-condition floor injected.

This changes production bounds/orchestration and cache contents, so `CERTIFIER_VERSION` is **22**.
Normal `:autobuilder:test` passes in 5m43 and `:autobuilder:ktlintCheck` passes. The mandatory
seeded production-union campaign also passes 3/3 in 4m59: exact CP-SAT optima
568,852,079,565 / 2,276,045,688,840 / 2,113,135,111,800 are covered by v22 uppers
1,155,552,532,365 / 3,826,178,473,040 / 3,684,355,936,940. These artificial level-200 pools are
deliberately loose (+103%/+68%/+74%), so they award no useful badge; their role is the independent
soundness gate for the new composition. The primary promotion gates are green.

## Handoff — v23 frontier cells + secondary Lagrangian support (2026-07-17)

This is the exact continuation point for the next agent. The worktree is intentionally dirty and
contains the whole v17→v23 campaign; do not discard unrelated edits. `CERTIFIER_VERSION` is already
**23**. The invariant remains: every composed number is an upper bound; on an unknown native bound,
overflow or unsupported shape, return `Long.MAX_VALUE`/bail rather than under-count.

### What is implemented in production now

The soft DP now exports, without another state dimension:

- `targetCellBounds[(AP,MP,CC,HP)]`: folded upper per target-credit rectangle;
- `targetCellCoreBounds`: corresponding pre-penalty damage core;
- `PenaltyProfile`: exact production penalty bucket/multiplier reconstruction;
- `belowTargetBounds`: diagnostic complement reads.

`frontierRegionUpper` soundly replaces at most four offending **no-condition** rectangles by:

1. all other DP rectangles;
2. the same rectangle below a computed HP floor, using its DP core and a capped penalty;
3. an exact CP-SAT raw-damage region above that HP floor, multiplied by the rectangle's worst
   penalty multiplier.

The partition is exhaustive. AP/MP/CC lower/upper bounds are posted on resolved sheet-stat vars;
the HP floor handles the rounded top HP cell explicitly. A timed-out CP solve uses its dual. A
negative/missing native sentinel is treated as infinity (never folded to zero).

Measured real-pool results:

| shape | old no-condition DP | v23 region result | details |
|---|---:|---:|---|
| CRA-245 S4 | 17.762813T | **17.702078146500T exact** | one offender `(15,8,100,12000)`, HP floor 11974, exact raw 19,382,375 |
| IOP-200 | 9.267129T | **8.909052797060T exact** | two offenders: `(13,8,100,10000)` floor 9741 and `(13,8,99,10000)` floor 9841 |

For S4, the complete production union is now `ProvenOptimal`, but the honest sequential wall is
**144.4 s** (about 103 s conditional DP + regional CP). Running the region concurrently was worse:
8 regional workers in parallel gave 163.9 s; a targeted 4-worker portfolio gave 167.9 s. Keep the
current sequential order. This is exact but misses the desired two-minute target by ~24 s; optimize
the conditional DP, not the ~15–25 s regional solve.

The generic region lock passes on all seeded pools:

```shell
WAKFU_S4_FRONTIER_LOCK=1 WAKFU_TEST_MAX_HEAP=8g ./gradlew \
  :autobuilder:cleanTest :autobuilder:test \
  --tests '*MaxDamageSoftCertificateTest*manual frontier region partition covers seeded no-condition optima*' \
  --no-daemon
```

It passed in 2m27. The complete production-union soundness lock also passed 3/3 in 8m06:

```text
seed1 exact=568852079565  union=1155552532365
seed2 exact=2276045688840 union=3826178473040
seed3 exact=2113135111800 union=3684355936940
```

Those synthetic requests time out in the optional region refinement and safely keep the old DP
bound (`noCondProven=false`). This exposes an operational follow-up: do not blindly spend 120 s on
every arbitrary request; add a useful activation/budget policy after the real-shape promotion.

### Conditional IOP residual: provenance and rejected ideas

With the no-condition side closed exactly, IOP-200's remaining production winner is the
conditional `secZero/noExpert` DP. Changing the mid-level crit support from λ=6000 to **λ=4000**
improves it to **9.143179399250T** (about +2.63% over 8.909052797060T). λ sweeps around it were
worse. CC support band 5→1 removes the old `95..99`/path-at-104 mismatch but leaves the bound
bit-identical at 9.143179T: do not pursue finer CC bands.

The reconstructed coarse path selects the following discrete build:

```text
items: 26897 27416 26497 26287 26996 25078 27557 26952
       26323 21760 21859 14527 14422 27923
normal subs: Vélocité II, Vivacité II, Destruction III×2, Neutralité III,
             Poids Plume III, Ambition III, Influence vitale III×2, Brûlure III
epic sub: Anatomie
```

A new research-only pin seam on `timedMaxDamageProfileForTest` pins all equipment and exact
sublimation copy counts while leaving skills/runes free. Exact validation proves that path feasible
but worth only **7.822582943760T**, raw **9,636,120**, with
`AP=13, MP=8, CC=100, HP=17856`. The DP path has the same penalty cell and DI=98; the gap is therefore
entirely in the mastery core, not the request penalty, and it is **not** a better incumbent missed by
CP-SAT. The opt-in test is:

```shell
WAKFU_IOP_DP_WITNESS=1 WAKFU_S4_SHAPE=iop200-frontier ./gradlew \
  :autobuilder:cleanTest :autobuilder:test \
  --tests '*MaxDamageSoftCertificateTest*IOP DP witness*' --no-daemon
```

Two independent endpoint uppers were measured and rejected alone:

- price only actually available positive scenario-secondary mastery (`μ=0`): **10.208226T**;
- use the global exact secondary-budget knapsack without item coupling: **12.098071T**.

Both are sound but looser than the item-coupled budget projection.

### Promising unfinished work: secondary-condition Lagrangian envelope

For a `SECONDARY_MASTERIES_AT_MOST(0)` carrier, write scenario secondary mastery as `S` and all
other signed secondary mastery as `O`; the condition is `S + O ≤ 0`. For every
`0 ≤ μ ≤ wMastery`, the objective term has the sound upper

```text
wMastery*S ≤ (wMastery - μ)*S - μ*O.
```

The DP can price this without a new key:

- positive scenario-secondary lines receive coefficient `wMastery - μ`;
- other negative/positive secondary lines receive `+μ/-μ` on their own item option;
- external negative budget and threshold constants are multiplied by `μ`;
- negative terms may still be dropped, which only raises the upper.

The research parameter `secondarySupportPrice` is already added to `bound`; `μ=wMastery=500`
reproduces the historical `secZero` arm. Direct IOP-200 DI1 reads (λ=4000, CC band 1) are:

| μ | conditional upper |
|---:|---:|
| 500 | 9.143179399250T |
| 400 | 9.071258155440T |
| 300 | 8.997806672400T |
| **250** | **8.971792605490T** |
| 255 | 8.973322844720T |
| 200 | 9.054425523910T |

So μ=250 closes the conditional gap to about **+0.704%**, with no extra DP state. This is the best
current lead.

**Original handoff state:** integration stopped halfway at user request; the parameter initially
worked only in the direct fixed `worldArm=secZero, lightWeaponArm=noExpert` research run.

#### Progress journal after handoff

- **μ propagation + adaptive queue implemented:** `secondarySupportPrice` now follows both world
  replay and light-weapon recursion. Production retains the mutable best-first candidates from the
  μ=500 pass, recomputes only still-relevant `secZero` worlds at DI1/μ=250, intersects each world
  with `min`, then resumes the same queue so any newly exposed plain/critZero world pays only its
  missing DI tiers. S4 should skip the pass because its first conditional union is already below
  the no-condition authority.
- **Targeted μ=250 soundness lock passed** in 1m42 with 8-worker exact capper worlds. Exact/upper:
  seed1 `297707299560 / 786608298630`, seed2 `325614023000 / 2745267700560`, seed3
  `354366378345 / 2962599906750`. `INFEASIBLE` forced-capper worlds are correctly absent from the
  exact union. The first deterministic version of this lock was stopped/replaced because it spent
  ~9 minutes and failed only by treating an exact-INFEASIBLE world as an error. Ktlint and both
  main/test compilation pass after the integration.
- **IOP-200 production E2E:** no-condition is proven exactly at `8.909052797060T`; the complete
  union falls to **`8.971792605490T` (+0.704%)**, proving that no plain/critZero world re-emerges
  above μ=250. Runtime is **158.9 s**, however, versus the ≤120 s target. Next optimization:
  preserve the split light-arm bounds in each best-first candidate and recompute μ=250 only for
  arms whose current upper can still beat the no-condition authority; the binding arm is
  `noExpert`, so the current unconditional two-arm replay likely pays avoidable wall.

Remaining production checklist:

1. add a seeded equivalence/soundness lock for μ in `{0,250,500}` against exact CP-SAT, then rerun
   `WAKFU_S4_UNION_LOCK=1` and the real IOP production path;
2. re-run S4 production to confirm identical exact result and ~144 s wall.

The prior `secSupply` pseudo-arm was fully reverted after its 10.208T NO-GO. Do not reintroduce it;
the general μ support subsumes it. The μ support itself changes certificate arithmetic, so v23 is
already the intended cache version; if another certifier change is made after this handoff, bump
the version again.

### Remaining promotion gates

The frontier lock and production-union lock passed before the latest diagnostic fields/μ seam.
Compilation and targeted tests pass, but the following have **not** yet been rerun after the final
research edits:

```shell
./gradlew :autobuilder:ktlintCheck
WAKFU_TEST_MAX_HEAP=8g ./gradlew :autobuilder:test --no-daemon
WAKFU_S4_UNION_LOCK=1 WAKFU_TEST_MAX_HEAP=8g ./gradlew \
  :autobuilder:cleanTest :autobuilder:test \
  --tests '*MaxDamageSoftCertificateTest*union soundness*' --no-daemon
```

Also update this section with the final IOP E2E upper/wall and the S4 non-regression before calling
v23 promoted.

## Handoff update — v24 (2026-07-18)

v23's remaining checklist is DONE: μ lock extended to μ∈{0,250,500} (green, 3 seeds × 3 μ),
production-union lock re-passed (×3, bit-identical), and the shapes re-measured. v24 ships the
guarded acceptance floor (stop refining once every world is within 1.5% of the incumbent; guarded
to `tier >= 0` so S4-245 keeps ProvenOptimal), the DI10→DI1 jump for all ≥175 worlds, and 4×
coarser assume-worlds. Stage/per-DP-pass instrumentation is now permanent
(`soft-leg proof stage=` / `refine world=` INFO + test-only `log4j2-test.xml`). The E2E contract
is shape-aware (S4 exact, others ≤2%). Full numbers: perf plan §9.23.

Open walls: S4-245 153 s hot ProvenOptimal; IOP-200 157-160 s hot at 1.08%; cra-80 ~200 s (B&B);
cra-140 probe-bound (the open research gap — 462 s on a hot machine, the 420 s plain probe did not
prove; it proves ~104 s cold). Cooldown measurements pending — every number above is from a
saturated machine. Do-not-retry additions: merging μ=250 into the primary refinement (loses arm
selectivity, cost-neutral at best); grid coarsening beyond 4× has sub-linear payoff (the DP cost
floor is per-option scanning, not states).

## ProvenOptimal-everywhere measurement campaign (2026-07-18, post-v25)

The user raised the bar from "a sound verdict under two minutes" to "ProvenOptimal under two
minutes, every request". Five decisive measurements (all firsts):

| shape | route | result |
|---|---|---|
| IOP-200 | monolithic full model, 8w, 600 s | FEASIBLE, dual 21.02T = **+136%** — hopeless; best primal 8.759T does not even reach the 8.909T incumbent |
| IOP-200 | conditional-world B&B, 20 s/node, 600 s | does NOT close: 30 nodes, EVERY mixed node dual ~28.3T (+218%) — the reification wall hits mixed nodes at this scale too |
| cra80-ap10 | monolithic full model, 8w, 300 s | FEASIBLE, **+20.9%** — the partition (3.34%) is 6× tighter than the monolith |
| cra80-ap10 | B&B UNBOUNDED (600 s, 256 nodes) | **CLOSES: Proven(820040000000) in 43 nodes / 456.8 s** → ProvenOptimal is reachable, needs ~4× wall engineering |
| cra-140 | B&B (300 s, 15 s/node) | does not close: 33 nodes, frontier +2.60% — the monolith (~104 s cold) stays its best exact route |

Resulting map to "ProvenOptimal < 2 min everywhere":

- **S4-245** ✅ already (96.3 s, v25).
- **cra80-ap10**: pure engineering — the closed tree needs ~4×: wide (k-ary) branching over the
  whole selected-conditional support to feed 4 node-workers (the DFS chain leaves the frontier
  1-2 nodes wide, so the shipped 2-worker parallelism only bought 1.2×), plus adaptive per-node
  budgets (most FEASIBLE nodes ride their inherited parent dual anyway).
- **cra-140**: route the plain full-model probe FIRST/concurrently for this class (level <175,
  mid pools) — ProvenOptimal ~104-120 s on a cold machine; thermally fragile (>300 s hot).
- **IOP-200**: ❌ NO measured route proves it at ANY duration (monolith +136%, mixed B&B +218%,
  DP +0.704%). The only paths are research: close the conditional DP's last 0.704% (the
  cross-path merge looseness in the secZero core — K-dim / carrier-exact modeling), or discover
  a better-than-8.909T conditional incumbent (none found by any search so far).

Decision point for the user: launch the IOP-200 research campaign (multi-session), or accept
ProvenWithin(≤1.1%) as that shape's terminal badge.

### P3 entry point — the IOP-200 phantom is LOCATED (2026-07-18, DI1 provenance)

Binding path of the secZero/noExpert μ=250 DI1 world (bound 8.9718T, the union owner):
`support=3361500 Wupper=2961500 core=11051755 d=74+ramp24 cc=100` — items+skills+runes sum to
exactly Wupper, so the **+400,000 of W (~13.5%) is pure μ-support credit**. The path carries
**843 positive scenario-secondary mastery** (Bague Fléopard 108 secN, Coiffeuse Mortelle 430,
L'Ami Léhunui 305) priced at `(wMastery − μ) = 250`/unit by the Lagrangian projection, while the
exact build must actually offset them under `S + O ≤ 0`. Witness cross-check: DP core 11,051,755
vs exact pinned 9,636,120 (+14.7%) — the entire residual.

Concrete research routes, in order:
1. **State-conditional μ pricing**: the projection charges μ per positive scenario-secondary unit
   assuming an external negative budget; make the credited budget the negative secondary
   ACTUALLY reachable by the remaining slots of that state (a per-state cap on the μ term, like
   the state-dependent MP ramp fix that closed CRA-80's invented credit §9.22sexdecies).
2. Net-secondary state dimension (K-dim): track the path's signed scenario-secondary sum coarsely
   (e.g. 3-5 buckets) and zero the support credit for states whose net cannot reach ≤ 0.
3. μ per world re-calibration is DEAD (sweeps measured, 250 is the min).

Reproduce the diagnostic:
```shell
WAKFU_S4_PROTO=1 WAKFU_S4_PATH_ONLY=1 WAKFU_S4_PATH=1 WAKFU_S4_SHAPE=iop200-frontier \
WAKFU_S4_PATH_ARM=secZero WAKFU_S4_PATH_LIGHT_ARM=noExpert WAKFU_S4_PATH_DI=1 \
WAKFU_S4_PATH_HP=2000 WAKFU_S4_PATH_CC=10 WAKFU_S4_CC_LAMBDA=4000 WAKFU_S4_CC_BAND=5 \
WAKFU_S4_SECONDARY_PRICE=250 WAKFU_S4_COUPLE_NEG_ITEMS=1 WAKFU_S4_NET_NEG_ITEMS=1 \
WAKFU_S4_EXACT_NORMAL_SUBS=1 WAKFU_S4_FOLD_ITEM_MAX_AP=1 WAKFU_S4_FOLD_MAX_MP=1 \
WAKFU_S4_SPLIT_LIGHT_WEAPON=1 WAKFU_S4_REQUIRE_CONDITIONAL=1 WAKFU_S4_CRIT_AWARE_COLLAPSE=1 \
WAKFU_S4_CRIT_WEIGHT_ANCHOR=100 WAKFU_S4_STATE_MP_RAMP=1 WAKFU_S4_ELIDE_IMPLIED_COND=1 \
WAKFU_TEST_MAX_HEAP=8g ./gradlew :autobuilder:cleanTest :autobuilder:test \
  --tests '*MaxDamageSoftCertificateTest*prototype tightness*' --no-daemon
```

### P3 addendum — the joint (λ, μ) grid is DEAD; the 400k is the λ-crit term (2026-07-18)

Correction to the entry point above: the +400,000 support-vs-W delta on the binding state is the
**λ-crit support term** (λ=4000 × CC=100), not the μ-secondary projection (the μ pricing is baked
per-option). A λ sweep at μ=250 (each value independently sound, min sound):

| λ | secZero/noExpert DI1 bound |
|---:|---:|
| 2500 | 9.0789T |
| 3000 | 9.0345T |
| 3500 | 8.9917T |
| **4000** | **8.9718T** (production) |
| 5000 | 8.9810T |

λ=4000 sits at the curve's minimum at μ=250 too — the joint grid buys ~0.1%, not the needed 0.7%.
DO-NOT-RETRY scalar (λ, μ) tuning. The residual lives in the collapse arithmetic itself
(W×(400+CC) transport across crit bands + the secondary budget interplay on a path that REALLY
sits at CC=100 with 843 positive scenario-secondary): closing it requires the state-conditional
routes (per-state reachable negative-secondary cap, or coarse net-secondary K buckets) — the P3
research proper, with the witness (7.8226T exact vs 11,051,755 core) as the oracle to close against.

### P3 addendum 2 — the conditional-region-CP route caps at +0.55% (analysis, 2026-07-18)

Transplanting the v23 frontier pattern to the conditional side — per sec-capper c, one exact CP
with c FORCED, c's condition posted LINEARLY (no reification: the carrier is fixed), every other
conditional condition-stripped (sound relaxation) — would likely close the secZero and critZero
worlds (1-2 reifications ≈ the digestible regime; the μ-lock solves of exactly this shape prove
OPTIMAL in ~60 s on seeded pools). BUT the **plain world binds at 8.958T (+0.55%) on its own**:
its builds carry non-capper conditionals (Neutralité-class) whose conditions can only enter CP
reified — the measured wall. So this route's ceiling is ProvenWithin(0.55%), not ProvenOptimal.

Conclusion unchanged and now fully mapped: IOP-200 ProvenOptimal requires the state-conditional
DP surgery (per-state reachable-negative-secondary cap or net-secondary K buckets) applied to BOTH
the secZero and plain worlds, validated against the banked witness (7.8226T exact) and the μ/union
locks. Optional intermediate ship if wanted: capper-region CP for a 1.08% → ~0.55% badge.

### P3 addendum 3 — IOP-200 is down to ONE world (2026-07-18)

Three decisive reads:

| world | production λ | bound | λ=4000 read | closes vs 8.909T? |
|---|---:|---:|---:|---|
| plain | 1500 | 8.958T | **8.638T** | ✅ |
| critZero | 1500 | 8.955T | **8.575T** | ✅ |
| secZero (μ250) | 4000 | 8.9718T | (λ-swept, 4000 is the min) | ❌ +0.704% |

The production λ_LOW=1500 was simply miscalibrated for plain/critZero at IOP — a per-world λ
multi-read {1500, 4000} min-intersection closes BOTH (each λ independently sound). The capper-CP
route is DEAD: all four sec-cappers keep +24%..+153% duals at 120 s with the other conditionals
relaxed (Inflexibilité II best at 11.04T), and Secret critique proves OPTIMAL at 9.114T > incumbent
(a world bound under relaxation, not a counterexample — but it does not close either).

**The entire remaining IOP-200 gap is the secZero world.** The P3 surgery is now scoped to ONE
place: add a bucketized net-scenario-secondary dimension to the secZero DP reads (state key), and
at collapse EXCLUDE states whose minimal reachable net is provably > 0 (their carrier would be
inert — such builds are covered by the plain/no-cond side). Validation: the banked witness
(7.8226T exact), the μ{0,250,500} lock, the union lock. Note: do NOT ship the per-world λ
multi-read alone — it does not move the badge while secZero holds the max (pure wall cost).

### P3 implementation spec — the secZero net-secondary dimension (written 2026-07-18)

Goal: in `MaxDamageSoftCertificate.bound()` secZero reads ONLY, exclude at collapse the states
whose FINAL signed secondary (scenario S + other O, items only — skills/runes only ADD secondary,
so their minimal contribution is 0) is provably above the most permissive sec-capper threshold
`t = secTMax`. The 8.9718T phantom path carries +843; killing it must land the world ≤ 8.909T.

**Lower-bound semantics (the soundness core).** Store per state a value `v` with the invariant
`v ≤ v_exact = floor((netSec + OFFSET) / SEC_STEP)` where `OFFSET = Σ_slots max(0, −minSecNet(slot))`
(a precomputed constant making the tracked quantity non-negative; never clamp UP). Transitions:
`v' = min(v + floor((option.secNet + slack) / SEC_STEP), cap)` — per-option FLOOR rounding and
the `min` saturation both only lower `v`, preserving the invariant. Exclusion test (sound):
`v × SEC_STEP − OFFSET > t ⇒ netSec > t` (since `netSec + OFFSET ≥ v_exact × SEC_STEP ≥ v × SEC_STEP`).
`cap = ceil((t + OFFSET) / SEC_STEP) + 1` — every provably-over state merges into one bucket.
SEC_STEP ≈ 250, OFFSET ≈ 2000 ⇒ cap ≈ 9-10 ⇒ 4 bits.

**Insertion points:**
1. `Opt` gains `secNet: Int` (signed scenario+other secondary of the option) — populate where the
   coupled μ pricing already reads per-item secondary lines (items, weapons pairs, rings pairs;
   subs' secondary lines too — Influence vitale etc. carry secondary? include them, signed).
2. `Geometry`: params `secBucketCap: Int = 0` (0 = off) + `secOffset/secStep`; packed field 4b @50
   (`fitsPackedKey` unchanged — bits 50-53 free; extend the check `secBucketCap <= 0xF`).
   `applyOne`: `newSec = min(sec(k) + floorDiv(o.secNet + ?, step), secBucketCap)` — careful:
   floor of a NEGATIVE delta rounds DOWN (more negative) ✓ sound.
3. Collapse (the `for (k in states)` loop, near the `assumeApThreshold` skip at ~2154): when
   `armZeroSecondary && secBucketCap > 0 && geo.sec(k) * SEC_STEP − OFFSET > secTMax` ⇒ `continue`.
4. Orchestrator: enable via a new `bound()` param `secondaryNetDimension: Boolean` passed by
   `refineWorld` when `world.arm == "secZero"` (and by the μ-pass call), OFF everywhere else.
5. `CERTIFIER_VERSION` bump.

**Validation sequence (fast-locks-first):** (a) μ{0,250,500} lock (seeded capper optima MUST stay
covered — the dimension must never exclude a state hosting a feasible capper build; if the lock
reddens, the exclusion formula is wrong, likely OFFSET/sign); (b) the IOP witness read: the
secZero DI1 μ250 bound must DROP from 8.9718T (target ≤ 8.909T) while the pinned exact 7.8226T
build stays covered; (c) union lock; (d) E2E iop (expect ProvenOptimal if ≤ incumbent) + s4
(must stay ProvenOptimal — S4's secZero world must not lose coverage: its capper builds have
net ≤ t by construction, so exclusion never fires on covered builds); (e) full suite.

**Known risk:** the μ pricing already charges ±μ per secondary unit inside W — the new dimension
EXCLUDES states rather than re-pricing, so there is no double-count interaction; but a state's
final skills/runes may RAISE net above t while items alone are ≤ t — that build's carrier is inert
⇒ covered by plain/no-cond ⇒ excluding on ITEMS-ONLY net is NOT sound the other way: items-net ≤ t
but true net > t means we KEEP the state — keeping is always sound (only exclusion needs proof). ✓

### P3 spec v2 — CREDIT-CAP design (supersedes the exclusion design above, 2026-07-18)

Code reading corrects the diagnosis: the provenance `secN` fields are NEGATIVE secondary — the
phantom path stacks negative-secondary items because the coupled pricing credits `μ·negSec(item)`
into W per item (itemOpts, `+ secSupportPrice * negativeSecondary`), i.e. **budget credited with
nothing to fund** (the path carries no positive scenario-secondary S).

Exact identity: for a build satisfying `S + O ≤ t`, `wM·S = (wM−μ)·S + μ·S` and `S ≤ t + negB`,
so the exact credit is `μ·min(S, t + negB)`. The sound one-dimension fix:

1. Track per state `S⁺` = accumulated POSITIVE scenario-secondary (characteristics in
   SECONDARY ∩ (masteryStats ∪ randomStats), items AND subs), rounded **UP** per option
   (`ceilDiv(o.secPos, SEC_STEP)`, saturating at a small cap) — stored ≥ true, which is the sound
   direction for capping a CREDIT.
2. REMOVE the per-item `μ·negSec` W credit in this mode (and the μ parts of `armConstantW`).
3. At collapse add `μ·min(S⁺_stored × SEC_STEP, NEG_BUDGET_MAX + secTMax + secOwnMax)` where
   `NEG_BUDGET_MAX` = Σ_slots max-item negative secondary (the `futureItemMpDebit` pattern) plus
   the external budget already computed. Sound: true ≤ (wM−μ)S + μ·min(S_up, cap_const).
4. Positive scenario lines keep their `(wM−μ)` per-line pricing (unchanged).
5. Bucket cap small: resolution only needed up to `NEG_BUDGET_MAX + t` ≈ ≤1250; SEC_STEP=250 →
   cap 5 (3 bits @50 in the packed key; extend `fitsPackedKey`). Most options have secPos=0 →
   state blowup ~1.5-2× on secZero reads only.
6. The phantom path has S⁺ = 0 → collapse credit 0 → its ~μ×843 = 210k phantom W dies; expected
   secZero drop 8.9718T → target ≤ 8.909T (witness 7.8226T stays covered: its credit is exact-ish).

Everything else from spec v1 (validation sequence, orchestrator gating via a `bound()` param on
secZero reads, CERTIFIER_VERSION bump) unchanged.

**Spec v2 soundness addendum — the skills/runes complement.** `S_total` includes positive
scenario-secondary allocated by SKILLS and RUNES (both priced per-line at `(wM−μ)` but chosen at
collapse, outside the tracked dimension). The collapse credit must therefore be
`μ·min(S⁺_items+subs × SEC_STEP + SKILL_RUNE_SEC_MAX, capBudget)` with `SKILL_RUNE_SEC_MAX` a
TIGHT sound constant (max positive scenario-secondary reachable via the skill allocation + the
rune shard combos actually priced by `runeAxesW` when the best weighted char is a secondary
mastery). If that constant is loose (≥ the ~843 phantom scale) the min never bites and the fix is
inert — compute it from the actual skill branch values and rune tables, and print it in the
binding state for verification. If skills/runes CANNOT produce scenario-secondary on the request's
mastery set, the constant is 0 and the pure item/sub dimension suffices.

**Spec v2 implementation state (2026-07-18, end of session):**

DONE (compiled, DORMANT — behavior bit-identical, `secBucketCap` defaults to 0 everywhere):
- `Opt.secPos: Int` (positive scenario-secondary of the option);
- `Geometry.secBucketCap` param + `sec(k)` accessor + 3-bit packed field @50 + `fitsPackedKey`
  extension + `withMp` preservation + `applyOne` UP-rounded saturating accumulation
  (`SEC_DIM_STEP = 250`).

REMAINING (the activation, in order):
1. Populate `secPos`: item opts (Σ max(v,0) over SECONDARY ∩ (masteryStats ∪ randomStats)),
   sub opts (same over their StatEffects), and SKILL opts — skills are DP STAGES (provenance
   shows `skills-Strength/Luck/Major` as path steps), so populating their options suffices; no
   skill constant needed.
2. Runes: the item-option W axis takes max(wOf) over runes; a real build's secondary rune can
   out-value the credited axis by at most `RUNE_SEC_SHORTFALL = Σ_slots max(0, wM·v_secmax −
   wM·v_elemmax)` — compute it; with uniform shard values per slot it is ZERO. Add it (in W
   units, divided by μ → sec units) to the min's left side.
3. In dim mode: drop the per-item `μ·negSec` W credit (itemOpts line ~1067, guard with
   `&& !secondaryNetDimension`) and the μ part of `armConstantW`; at collapse (the `support =`
   sum in `foldWith`, ~line 2198) add
   `secSupportPrice * min(geo.sec(k)·SEC_DIM_STEP + runeShortfallSec, armSecCapRaw + ITEM_NEG_BUDGET_MAX)`
   where `ITEM_NEG_BUDGET_MAX` = Σ_slots max-item negative secondary (futureItemMpDebit pattern;
   ring second-best + weapon-combo handling). NOTE: the budget side becomes a global constant
   (looser than today's per-item credits) while the S side becomes state-exact (much tighter on
   S=0 phantom paths). Net effect at IOP must be measured; the phantom dies by construction.
4. New `bound()` param `secondaryNetDimension: Boolean = false`; geometry gets
   `secBucketCap = 5` when active && armZeroSecondary; `refineWorld` and the μ-pass pass it for
   `world.arm == "secZero"`; coarse pass stays OFF (bounds must not regress there before the
   queue refines).
5. CERTIFIER_VERSION 26 → 27; validation sequence per spec v1 (μ lock FIRST — it is the direct
   soundness oracle for exactly this pricing).

### P3 spec v2 MEASURED: 1-dim credit-cap is a NO-GO (2026-07-18)

The full activation was implemented and measured (secPos populated on items/subs/skills + rune
shortfall, per-item μ·negSec credit dropped, collapse credit μ·min(S⁺, globalBudgetCap)):

- μ soundness lock: GREEN with the dimension ON (3 seeds × 3 μ — the arm is sound);
- IOP secZero DI1 μ250: **9.933T vs 8.9718T — WORSE by +10.7%**.

Root cause: dropping the COUPLED per-item credit for a GLOBAL budget cap makes positive
scenario-secondary effectively full-price ((wM−μ) per line + μ via a min that never bites at the
global cap ≈ armSecCapRaw + ΣslotMaxNeg), so a NEW S⁺-rich binding path emerges above the old
one. The coupled per-item pricing is tighter than the naive credit-cap.

The exact form is `μ·min(S⁺_state, t + negB_state)` — BOTH quantities per state (~25× states,
out of budget). DO-NOT-RETRY the 1-dim global-cap variant. Remaining honest routes for the last
+0.704%: (a) 2-dim S⁺×negB with aggressive elision (SAFE sentinels both sides — research), or
(b) accept ProvenWithin(0.704%) as IOP-200's terminal badge. The plumbing (Opt.secPos, Geometry
sec field, `secondaryNetDimension` seam incl. WAKFU_S4_SEC_DIM on the μ lock and the path
harness) stays in the tree, DORMANT and union-lock-proven inert, for route (a).

### P3 route (a) MEASURED: the 2-dim S⁺×negB family is a NO-GO — IOP-200 terminal = ProvenWithin(0.704%) (2026-07-19)

Both 2-dim forms were implemented (negB 3b @53, DOWN/UP-rounded per direction, saturation-safe
both sides, dominance clauses extended, μ+union locks GREEN for each) and measured on the IOP-200
secZero owner read:

1. **Credit form** — μ·min(S⁺_up, armSecCapRaw + negB_up) replacing the per-item credits:
   DI10 9.9190T vs 9.2442T baseline (**+7.3% LOOSER**). Same failure mode as the 1-dim: at IOP
   scales (budgets 843–3000 raw) both cap-5×250 buckets saturate instantly → global-cap fallback
   ≫ per-path credit. Finer buckets can't fix it: the per-item UP-rounding slack alone
   (≥100·nItems raw) exceeds the 0.704% target.
2. **Correction form** (safe sentinels) — keep the exact baseline W and only SUBTRACT the provable
   over-credit −μ·max(0, negB_down + armSecCapRaw − S⁺_up). No regression possible by
   construction (DI10 bit-identical to baseline confirms), the phantom (S⁺=0) dies up to
   DOWN-rounding… but the S⁺×negB state product makes the DI1 read (the ONLY tier where the
   phantom binds) computationally infeasible: fine grid >88 min, coarse cc20/hp1000 grid
   >10 min (killed), vs ~20 s baseline. Outside any production proof budget.

With the CP monolith (+136%), the joint (λ,μ) grids (inert), the 1-dim credit-cap (+10.7%), the
DD exclude-chain family (ensemble of near-equivalent phantom paths) and now both 2-dim forms all
measured dead, **route (b) is adopted: ProvenWithin(0.704%) is IOP-200's terminal badge** (and
the AP/MP-only badge class shares the same structural residual). The correction seam stays in the
tree (WAKFU_S4_SEC_DIM2, dormant, lock-proven inert when off) with two soundness hardenings that
survive it: the secPos S⁺ tracking now clamps negative sub/skill lines to 0 (an S⁺ under-count
was unsound in every mode) and `dominates` carries the secPos/secNeg monotonicity clauses.

### Review-fix wave 2026-07-20 — three nested under-counts fixed; badges re-based to honest values

The high-effort branch review (8 finder angles × adversarial verify) surfaced two CONFIRMED
soundness holes; fixing them — and hardening the MP-ramp locks to production W pricing — exposed
two more in the same crit-aware pricing family. All four are fixed; the certificate is sound and
the E2E contracts are re-based to the HONEST residuals:

1. **MP-headroom collapse × ramp** (executed −17.9% repro): the collapse merged high-MP states
   down to the MP target while the Poids Plume ramp DI was read from the stored MP at collapse.
   Fix: the MP dimension stays un-collapsed whenever a ramp exists.
2. **Frontier-region coverage** (CONFIRMED structural): arm 3 pinned REAL sheet stats at the
   CREDITED cell coordinates. Fix: `creditedStatLowerBounds` in the CP oracle (real + elided
   per-item/per-sub debits — the exact crediting identity, hp% over-scaled from the skills API)
   + δ-relaxed REAL targets for the hardConstraints door; pins stay cell-tight.
3. **Crit reach under-modeling**: without a crit target, skill crit points and rune crit shards
   were not modeled, so the transport's ccHigh did not upper-bound reachable crit. Fix: crit is
   skill/rune-relevant whenever the collapse is crit-aware.
4. **Crit-transport DOWN-scaling** (ratio < 1 for c < anchor): only sound if EVERY W term is
   anchor-conforming — empirically refuted (a crit-3 build's real score exceeded the down-scaled
   W by 1.4% on the hardened lock's toy shape). Fix: the ratio is floored at 1; production runs
   at c = anchor (ratio 1) and never used the branch legitimately.

**Honest badges after the wave** (CERTIFIER_VERSION 31): S4-245 ProvenWithin 0.343% (the
pre-wave ProvenOptimal was partly the ramp under-count), IOP-200 4.02% (was 0.704%), enutrof125
8.5% (was 2.02% — it benefited from the removed down-scaling). Full suite + hardened locks
green. The generality matrix must be re-swept to re-base the remaining ProvenOptimal shapes.

### HANDOFF — ramp/crit tightening campaign (written 2026-07-20, post-review-wave)

**State.** The 2026-07-20 soundness wave (commit `686f60df` + the saturation-collapse follow-up)
fixed four nested under-counts (MP-ramp collapse, frontier-region coverage, crit-reach modeling,
crit-transport down-scaling). The certificate is SOUND (hardened locks + full suite green) but the
honest badges regressed: S4-245 0.34%, osa225 1.6%, IOP-200 4.0%, iop215 5.1%, eca195/cra185/
enutrof ~8%, iop110/panda170/xelor155 ~12-13%, feca65/sacrieur230/steamer240 ~15-16%
(matrix table in the section above; cra50 + the B&B-closed cra80/cra-140 keep ProvenOptimal).
E2E contracts re-based (s4 0.005, iop200 0.045, else 0.25). CERTIFIER_VERSION 31.

**Dominant residual = the Poids Plume ramp credit.** `foldWith` reads
`rampDi = contribution(geo.mp(k))` where `geo.mp(k)` is the CREDITED MP (positive parts only) —
sound, but phantom high-credited-MP paths collect up to +24% DI they cannot really fund. The two
tightening leads, in recommended order:

1. **Ramp world-split (the cap-sub "world B" pattern, most promising).** Split the union:
   main worlds with the ramp sub EXCLUDED (rampDi = 0 — removes the credit from every
   non-ramp path), plus ONE dedicated world with the ramp sub REQUIRED where the MP dimension
   is tracked exactly enough to price `contribution()` honestly (fold item/sub MP debits into
   the dim — the `foldNegativeMaxMp` signed machinery already exists for MAX_MP riders; extend
   it to plain −MP lines inside that world only). Cost: +1 world per ramp sub (there is one).
   This mirrors the objective-capper world split that took the S2 MM bound from +21% to +9.87%.
2. **Anchor-conformity partition of W (recovers the down-scaling legitimately).** The transport
   floor-at-1 gave up all c<anchor tightening because W mixes anchor-scaled terms ((400+A)·M,
   5A·K) with non-conforming ones (λ·cc support, μ credits, armConstantW). Split the state's W
   into `wAnchor` + `wConst` (either a second accumulator per state — packing risk — or a sound
   per-world constant bound on wConst), transport only wAnchor:
   `bound = r·wAnchor + wConst` is sound for r < 1 once the classification is exact. This is
   what the removed branch needed all along; it mainly helps low-crit-target shapes
   (enutrof/xelor/sacrieur class).

**Guard rails.** The hardened MP-ramp locks (`state dependent MP ramp is sound *`, now at
production W pricing: critAwareCollapse=true, anchor 100) are the direct oracles — they caught
all four under-counts; keep them green at every step. Fast protocol: 30-60s single-shape probes
(WAKFU_S4_PROTO/PATH harness, prod-parity defaults) BEFORE any matrix sweep; the matrix
(WAKFU_S4_PROOF_MATRIX=1) only at milestones. Bump CERTIFIER_VERSION on any arithmetic change.
Do-not-retry: the P3 2-dim S⁺×negB family, the (λ,μ) grids, solver knobs, DD exactness — see the
sections above.

### Progress journal — v32 ramp world-split (2026-07-20)

- Implemented the exhaustive partition requested by the handoff: every ordinary objective/cap
  world now EXCLUDES the unique MP→DI ramp carrier, and one additional `rampCover` world REQUIRES
  it while relaxing every other cap-sublimation condition. This is one logical ramp world (the
  existing light-weapon structural split may still price its two exhaustive weapon arms).
- The required-ramp world forces exact NORMAL-sub packing and signed MP folding even when called
  from the coarse production sweep. Its MP coordinate now includes both negative `MAX_MP` lines
  and plain negative `MOVEMENT_POINT` lines, on equipment and sublimations; this covers
  `Armure lourde II`'s `-1 MP`, the omission named in the handoff. Ordinary no-ramp worlds retain
  the cheaper historical MP model.
- `WorldRead` carries the ramp partition identity so every best-first refinement re-prices the
  SAME set. `CERTIFIER_VERSION` is bumped 31→32. Compile + test compile + ktlint are green.
- Direct soundness milestone GREEN: both hardened production-priced tests matching
  `state dependent MP ramp is sound *` pass, including the executed incompatible-choice repro
  and 8 seeded pools containing negative item MP. Next: 30–60 s mono-shape probes, then the union
  and μ locks only if the tightening is useful; no proof matrix yet.
- First S4-245 DI10/HP1000/CC20 probes (`noExpert`, λ=6000): the single fully relaxed
  `rampCover` is **20.567467833000T** (~33 s), therefore unusably loose; forcing the same ramp
  under the `plain` objective arm gives **17.979723778500T** (~22 s). Diagnosis: signed MP itself
  tightens, but merging all objective/stat cappers into one cap-free cover destroys it. Next probe
  separates the cap-stat relaxation from the objective arm before choosing the sound cover shape.
- Fine probes: S4 `ramp/plain/noExpert` at DI1 is **17.762813128500T**, exactly the current honest
  +0.343% residual (a real MP=8/ramp=24 family survives). IOP-200 is more promising:
  `ramp/plain/noExpert` = **9.059016241600T** at DI1 (+1.68%), `ramp/secZero/noExpert`
  at μ=250 = **8.971792605490T** (+0.704%), while excluding the ramp from secZero collapses it
  to **8.004681412130T**. Thus the post-wave +4% IOP regression really contains unfunded ramp
  borrowing on no-ramp paths, even though genuine ramp paths set a new ~1.7% floor.
- Added a lazy sound re-split of `rampCover` into the existing `{plain,secZero,critZero}` objective
  arms plus every stat-cap assume-world, all with the ramp required. The DI10 full partition is
  **9.244175188430T** on IOP and costs 69 s; it is secZero-owned at DI10 but plain-owned at DI1.
  The production queue therefore expands the scout into child candidates after DI10 and refines
  only the current owner instead of replaying the whole partition at DI1.
- Post-integration gates so far: compile/test-compile/ktlint GREEN; both hardened ramp locks GREEN;
  μ∈{0,250,500} lock GREEN (3 seeds, exact always covered). The μ lock took 4m32 on this run,
  materially slower than the historical 1m42, so wall measurements should be treated as a loaded/
  thermally noisy machine until a clean E2E. Union lock remains the next soundness gate.
- First production IOP E2E exposed the real authority: the post-wave no-condition DP+region stayed
  at +4.019% and burned 131.6 s; the new conditional partition was already below it. Tightened the
  high-level no-condition DP to HP1000 and λ=4000 for levels 175–224, and gated region CP to DP
  gaps ≤1% (the +4%/+1.68% IOP reads cannot close and must not burn the 120 s region budget).
  The second E2E reached **+1.683%** but still took 160.5 s.
- Exact wall fix: in a ramp-REQUIRED world, Poids Plume is now removed from the normal-sub
  knapsack and its one slot is reserved explicitly (`9` remaining normal subs). The ramp is known
  present at collapse, so its state bit was redundant. This leaves every measured bound
  bit-identical while shrinking the ramp DP: IOP full rampPartition DI10 69 s→42 s in the harness;
  hardened ramp locks remain GREEN.
- **IOP-200 production milestone GREEN:** incumbent `8.909052797060T`, final upper
  **`9.059016241600T` = ProvenWithin 1.683%**, proof wall **99.887 s**. Stage walls:
  no-condition 21.7 s, coarse 15.3 s, rampPartition DI10 30.7 s, ramp/secZero DI1 18.1 s,
  ramp/plain DI1 10.2 s, no-ramp/secZero DI10 3.9 s. This improves the honest post-wave badge
  4.019%→1.683% and returns below the two-minute product goal on this run.
- S4 scale routing: HP1000 + region kept the correct +0.343% badge but cost 290.8 s. At level
  ≥225 the no-condition leg now retains HP4000, and region CP only activates below 0.1% (at
  S4 +0.343%, the conditional ramp/plain world owns the same upper, so refining no-condition
  cannot alter the union). S4 then returned the same **17.762813128500T (+0.343095%)** in
  120.940 s.
- Final scout optimization: use HP4000 only for `rampPartition@DI10`, restoring HP2000 for child
  DI1 reads. Dedicated probes are bit-identical on both S4 (`17.979723778500T`) and IOP
  (`9.244175188430T`); harness walls fell 60→44 s and 42→29 s respectively. Final S4 E2E:
  **ProvenWithin 0.343095% in 106.933 s** (no-condition 13.2 s, coarse 23.3 s, rampPartition
  46.3 s, ramp/plain DI1 24.1 s). This is now honestly below two minutes.
- Final repeat after promotion: IOP remains **+1.683% in 110.478 s** (loaded run) and S4 remains
  **+0.343095% in 106.933 s**. Final union lock GREEN in 5m03; its seed uppers actually tighten
  from `1.1555/3.8262/3.6844T` to `1.0348/3.4623/3.3449T`, all still above exact optima.
  Full `:autobuilder:test` + `:autobuilder:ktlintCheck` GREEN in 5m17. The v32 ramp-split jalon is
  therefore promoted locally; next step is the generality matrix, then the independent
  `wAnchor/wConst` transport campaign for low-crit shapes.

**⚠️ Matrix numbers above are LOAD-CONTAMINATED.** The two post-wave matrix sweeps (2026-07-20,
after hours of continuous compile/test load) disagree by up to 13 points on the same shapes with
BIT-EQUIVALENT arithmetic (osa225 1.6% vs 14.6%, three shapes flipping to Unavailable = deadline
bails under thermal throttle): the deadline-clipped refinement pipeline makes badges strongly
wall-speed-dependent. FIRST task of the tightening campaign: re-base the honest badge table with
ONE cold matrix run (idle machine), before touching anything — per the fast-locks-first
directive, never conclude tightness from a loaded-machine sweep.

- **v32 generality milestone (single uninterrupted run, 46m45):** all 12 shapes return an honest
  badge (none unavailable), but only 3 certificate walls are below two minutes. Percentages/walls:

  | shape | honest badge | certificate wall |
  |---|---:|---:|
  | cra50-apmp | +15.060% | 110.1 s |
  | iop110-full | +11.897% | 287.4 s |
  | xelor155-apmp | +13.190% | 175.7 s |
  | cra185-full | +5.257% | 93.9 s |
  | iop215-full | +1.588% | 105.1 s |
  | sacrieur230-apmp | +16.122% | 229.5 s |
  | feca65-full | +14.958% | 187.6 s |
  | enutrof125-cchp | +11.329% | 353.8 s |
  | panda170-apmp | +13.333% | 215.7 s |
  | eca195-full | +4.879% | 126.5 s |
  | osa225-full | +1.562% | 122.7 s |
  | steamer240-apmp | +16.054% | 245.4 s |

  Relative to the post-wave reference, the ramp partition materially helps high-level FULL
  requests (cra185 ~8%→5.26%, iop215 5.1%→1.59%, eca195 ~8%→4.88%) and preserves osa225,
  but AP/MP-only + low-level shapes stay near 12–16%. Enutrof's +11.33%/354 s is worse than its
  ~8.5% reference because the deadline-clipped route reaches its useful passes very late; its
  cap-free re-split alone costs ~150 s. This matrix therefore closes the v32 ramp milestone and
  makes the next campaign unambiguous: recover safe c<100 transport via an anchor/constant split,
  starting with a bounded Enutrof mono-shape probe. Do not launch another matrix before that probe
  wins and the ramp/union/mu locks are green.

### Progress journal — v33 anchor/constant crit transport (2026-07-20)

- Implemented the handoff's second lead without a second per-state accumulator. At `c<A`, a
  state's scalar support `H` is transported as
  `ceil((r*H + (1-r)*Cmax))`, where `r=max((400+c)/(400+A), c/A)` and `Cmax` independently
  bounds every non-anchor term. `Cmax` contains lambda's positive-CC support, every positive mu
  budget credit (arm + one legal item layout), and a conservative fixed `(400+A)*100` seed guard.
  At `c>=A` the historical whole-support up-scaling remains unchanged.
- Lambda cannot be bounded from the old CC key once that key saturates at the target/100. With
  v33 active, the existing seven-bit CC field retains headroom up to 240 raw CC (or bucket 127,
  whichever is reached first); below the sentinel the state's UP-rounded CC is the tight lambda
  ceiling, while a saturated/ASSUME state falls back to the global sum of one maximum per real DP
  stage. No new packed-key field exists.
- The first propagated implementation (lambda+mu only) was correctly REJECTED by the hardened
  crit-3 ramp lock: `95200 < exact 96520`. Keeping the fixed +100 mastery/base-hit seed outside
  the transport restores a sound conservative cushion; both hardened `state dependent MP ramp is
  sound *` locks are GREEN after the guard. This is deliberately slightly looser than the pure
  algebraic partition rather than risking another rounding/coupling under-count.
- Enutrof-125 base/plain mono-world (`DI10/HP1000/CC20`, lambda=1500) improves from
  `2.595912712500T` (+13.45%) to `2.466304012500T` (+7.79%) in ~10 s. This is the first direct
  proof that the recovered c<100 transport is materially useful. The fixed guard costs little
  against its ~1.2M W support.
- Safety gates: mu lock GREEN for all 3 seeds x `{0,250,500}` (5m20, all nine values cover exact;
  numerically identical to v32 because those fixtures target crit=100). Production enables the
  new transport only when the explicit crit target is absent or below 100; target=100 keeps the
  exact v32 state space and arithmetic. The final production union lock is GREEN (7m52): seed
  uppers remain bit-identical `1.034786823125/3.462292318100/3.344928176660T`, above exact
  `0.568852079565/2.276045688840/2.113135111800T`. The lock's longer wall is load/thermal noise,
  not a v33 state-space cost on its target=100 fixtures.
- First production Enutrof E2E: same incumbent `2.288092050000T`, badge improves from the v32
  matrix's +11.329%/353.8 s to **+7.189542%/146.5 s**. The final owner is
  `ramp/plain DI1 = 2.452595400000T`; the old 150 s cap-free re-split disappears.
- Removed 27 s of proven-inert post-owner work: a secZero mu read runs only if it can beat the
  best current non-secZero candidate, and fine alternate-lambda calibration runs only if its DI10
  scout actually tightened that candidate. Skipping an intersection only retains a looser sound
  upper. On Enutrof both stages fall from 18+9 s to ~1 ms, badge bit-identical.
- The remaining wall variance was the <=140 conditional-world CP tree: Enutrof consumed its full
  180 s (10 nodes, inconclusive) before the ~80 s DP. Levels 111..139 now cap that optional tree at
  20 s (initially 30 s, but loaded runs returned `nodes=0` at that ceiling); its unfinished
  frontier dual is itself sound and still intersects the DP. Historical
  <=110 closures and the measured cra140 endpoint retain 180 s. Enutrof's first 30 s-gated run was
  **ProvenWithin 7.189542% in 93.458 s**; the final 20 s version is bit-identical in **110.664 s**
  on a loaded run (world tree 20.137 s, coarse elapsed 27.992 s). Compile, ktlint, hardened ramp
  locks, mu lock and final union lock GREEN.
- `CERTIFIER_VERSION` is 33. Next: full standard suite, then targeted xelor155/sacrieur230 probes
  before any new generality matrix; do not infer matrix-wide wall gains from the Enutrof route.
- Promotion gate complete: full `:autobuilder:test` + `:autobuilder:ktlintCheck` GREEN in 5m17,
  exactly the v32 reference wall. v33 is therefore the current sound production milestone; the
  next reads remain mono-shape probes, not a matrix.
- Cap-free routing is now hierarchical only on the low-crit v33 path. The merged cap-free cover
  stops at DI10, is split into its three objective arms, and only a surviving owner descends to
  DI1; alternate-lambda and mu reads are likewise skipped unless their coarse upper can still win.
  These are sound queue eliminations: a skipped child remains covered by its looser parent. The
  target=100 path deliberately retains the complete v32 refinement order.
- **Xelor-155 targeted E2E:** incumbent `3.067514241300T`; plain mono-world moves from
  `3.540285253400T` to `3.270915723250T`. Production improves from v32
  **+13.190% / 175.7 s** to **+6.344086% / 43.122 s**. A first overly lazy cap-free attempt
  returned the weaker +9.606% and was rejected; the final hierarchical cover restores the exact
  +6.344086% bound while avoiding all non-owner DI1 work.
- **Sacrieur-230 targeted E2E:** incumbent `18.586084644855T`; plain mono-world moves from
  `19.524457183140T` to `18.838420117335T`. Production improves from v32
  **+16.122% / 229.5 s** to **+1.923349% / 142.959 s**. This is a large badge gain, although the
  current loaded wall remains about 23 s above the two-minute product target.
- The three intended low-crit probes all win materially without a soundness-lock regression:
  Enutrof +11.329%→+7.190%, Xelor +13.190%→+6.344%, Sacrieur +16.122%→+1.923%. The next honest
  milestone is one final standard-suite run after the routing constants, followed by a cold
  generality matrix; avoid tuning conclusions from another thermally loaded matrix.
- Final post-routing promotion gate: full `:autobuilder:test` + `:autobuilder:ktlintCheck` GREEN
  in **5m25** (17 tasks, no failure/OOM). This supersedes the earlier pre-final-routing 5m17 run.

#### v33 generality matrix milestone (cold isolated run, 40m44)

The requested 12-shape production matrix completed GREEN with no unavailable badge. Compared with
v32, ten badges tighten and the two explicit crit=100 shapes remain intentionally bit-identical.
The whole Gradle run falls from 46m45 to 40m44, though individual wall times remain strongly
shape/order/thermal dependent:

| shape | v32 badge / wall | v33 badge / wall | badge delta |
|---|---:|---:|---:|
| cra50-apmp | +15.060% / 110.1 s | **+10.395% / 129.0 s** | -4.665 pt |
| iop110-full | +11.897% / 287.4 s | **+4.957% / 226.6 s** | -6.940 pt |
| xelor155-apmp | +13.190% / 175.7 s | **+6.344% / 60.1 s** | -6.846 pt |
| cra185-full | +5.257% / 93.9 s | **+3.779% / 180.7 s** | -1.478 pt |
| iop215-full | +1.588% / 105.1 s | **+1.588% / 118.0 s** | unchanged (crit=100) |
| sacrieur230-apmp | +16.122% / 229.5 s | **+1.923% / 149.7 s** | -14.199 pt |
| feca65-full | +14.958% / 187.6 s | **+13.993% / 162.2 s** | -0.965 pt |
| enutrof125-cchp | +11.329% / 353.8 s | **+7.190% / 143.9 s** | -4.139 pt |
| panda170-apmp | +13.333% / 215.7 s | **+8.159% / 66.3 s** | -5.174 pt |
| eca195-full | +4.879% / 126.5 s | **+4.135% / 262.3 s** | -0.744 pt |
| osa225-full | +1.562% / 122.7 s | **+1.562% / 166.1 s** | unchanged (crit=100) |
| steamer240-apmp | +16.054% / 245.4 s | **+1.915% / 103.9 s** | -14.139 pt |

Four certificate walls are now below two minutes (xelor155, iop215, panda170, steamer240), versus
three in v32. Cra50 at 129 s is the nearest additional wall closure. Badge-wise the next dominant
residuals are feca65 (+13.99%), cra50 (+10.40%), panda170 (+8.16%), enutrof125 (+7.19%), then
xelor155 (+6.34%). Wall-wise eca195 (262 s), iop110 (227 s), cra185 (181 s), osa225 (166 s),
feca65 (162 s) and sacrieur230 (150 s) remain above the product target. Because the two crit=100
badges are unchanged while their walls moved, do not attribute wall regressions there to v33
arithmetic; refinement deadlines and accumulated thermal state still dominate timing variance.

### Progress journal — v34 strict optional-pass deadline (2026-07-20)

- The v33 matrix exposed a routing bug in the nominal 110 s proof deadline. Once the deadline was
  exhausted, `deadlineSecondsRemaining()` still forced a minimum 20 s budget, so late relaxed and
  full-model CP probes could push an already-complete sound DP result far beyond the product wall.
  Lambda-zero refinement was likewise allowed to start after the deadline.
- Added a raw remaining-budget guard: relaxed CP, lambda-zero and plain full-model probes start only
  when at least their 20 s minimum remains. Skipping them is sound because the already-computed DP,
  oracle and world-tree uppers remain in the final union; it can only retain a looser badge.
  `CERTIFIER_VERSION` is bumped 33→34 because this changes production orchestration/cache results.
- Instrumented provenance on the late full-model probe and final union. Feca65 reproduced the v33
  path at **193.948 s**: DP `551.160996750G`, oracle/incumbent `483.505255350G`, world-tree
  `555.826909950G`; the 20 s plain probe returned the much looser `869.990663530G` and changed
  nothing. The late relaxed and lambda-zero passes changed no owner either.
- v34 Feca validations retain the exact **+13.992762%** badge and finish at **121.736 s** and
  **119.853 s**, versus v33's 162.2 s matrix / 193.9 s loaded diagnostic. The latter is the first
  Feca run inside the strict two-minute target. Remaining variance is upstream: its parallel
  conditional-world B&B alternates between a 45 s one-node root bail and a 50–73 s two-node bail.
- Cra50 independently demonstrated that the low-level world tree is still worth retaining: with
  identical v33/v34 arithmetic it sometimes closes globally in **18.868 s / 5 nodes**
  (ProvenOptimal), whereas the isolated matrix run bailed after 2 nodes and returned +10.395% in
  129 s. The documented canonical 1-worker/interleave mode was already measured much slower
  (108.79 deterministic units) and is not retried; the multi-worker portfolio remains inherently
  variable. A tentative 130→120 two-node prognosis threshold was tested but did not participate in
  the winning Feca run, so it was reverted rather than promoted without evidence.

### Progress journal — v35 COLD re-base + B&B-variance lead REFUTED (2026-07-21)

Single-shape cold probes (idle machine, fresh daemon per shape, 60 s cooldowns) re-based the whole
matrix at CERTIFIER_VERSION 35 (commit `81d8e43f`). The five shapes missing a `shapePreset` entry
(cra185/iop215/eca195/osa225/steamer240) were added, mirrored bit-for-bit from the matrix runner's
`frontier()` rows, so every matrix shape now runs as a single cold probe.

| shape | cold v35 badge / wall | v33 matrix |
|---|---:|---:|
| cra50-apmp | **ProvenOptimal / 84.5 s** | +10.395% / 129.0 s |
| iop110-full | +3.371% / 171.4 s (repro 291 s) | +4.957% / 226.6 s |
| xelor155-apmp | +7.419% / 35.8 s | +6.344% / 60.1 s |
| cra185-full | +3.313% / 129.1 s | +3.779% / 180.7 s |
| iop215-full | +1.588% / 113.4 s | +1.588% / 118.0 s |
| sacrieur230-apmp | +1.881% / 61.0 s | +1.923% / 149.7 s |
| feca65-full | +6.996% / 91.2 s (×3 identical) | +13.993% / 162.2 s |
| enutrof125-cchp | +6.209% / 76.1 s | +7.190% / 143.9 s |
| panda170-apmp | +8.159% / 38.1 s | +8.159% / 66.3 s |
| eca195-full | +3.929% / 220.5 s (repro 240 s) | +4.135% / 262.3 s |
| osa225-full | +1.562% / 139.6 s | +1.562% / 166.1 s |
| steamer240-apmp | +1.887% / 67.1 s | +1.915% / 103.9 s |

- **The B&B-variance lead is REFUTED — do not retry a progress-conditioned two-node prognosis.**
  Cold cra50 closes ProvenOptimal in 84.5 s *through the DP*, with the world tree bailing at two
  nodes exactly as in the "bad" matrix run (root OPTIMAL det 179, +15.1% dual; child no-progress) —
  the bail was the *correct* route and the matrix's +10.395% was load-starved DP refinement, not a
  tree race. Cold feca65 is a *stable* +6.996% (three identical runs): its root is FEASIBLE at
  **+70%** over the incumbent (reification wall), so the root bail is right and no tree can own its
  badge — the journal's 7↔14% alternation was load, and the v33/v34 +13.99% readings were
  load-starved too. The mechanical suspect (second-node det clip `max(131−rootDet, 10)` making the
  bail quasi-automatic after a non-closing root) is real but *harmless*: on both shapes the DP is
  the authority.
- Honest wall picture: only eca195 (220–240 s) and iop110 (171–291 s) remain above two minutes
  cold. Eca195's cost is DP fold volume at level 195 (no-condition DP 51 s + primaryRefinement
  144 s, single passes 8–55 s; the secZero owner DI1 runs twice — `lightArm=both` 55 s then the
  μ-support `noExpert` re-read 29 s for an identical bound). Iop110's extra wall is
  capFreeResplit (+104 s) and oracleJoin (+63 s) on the slow repro.
- **Campaign pivot (user, 2026-07-21): badge quality over wall time — win back ProvenOptimal.**
  Post-soundness-wave badges are honest residuals (1.5–8.2%); the open question per shape is
  whether the gap is *bound looseness* or *incumbent weakness*. Note the harness incumbent is the
  NO-CONDITION oracle (`condition == null` subs only) while the union prices conditional subs —
  part of a harness gap can be genuinely achievable damage (production incumbents include
  choosable conditional subs, so production badges are at least as tight). The decisive
  diagnostic — running now — is the unrestricted full-model CP-SAT solve (`WAKFU_S4_CP_PLAIN=1`,
  every condition exactly modeled, 600 s) per near-closure shape (osa225, iop215, sacrieur230,
  steamer240, panda170), classifying each gap so tightening effort lands where it can actually
  close a badge.

#### Truth table: the residual gaps are BOUND looseness, not incumbent weakness (2026-07-21)

`WAKFU_S4_CP_PLAIN=1`, 600 s, 8 workers, full 232-sub catalog, cold sequential runs:

| shape | no-condition incumbent | plain-CP primal @600 s | plain-CP dual @600 s |
|---|---:|---:|---:|
| sacrieur230-apmp | 18 586 084 644 855 | **identical to the incumbent** | +32.0% |
| steamer240-apmp | 15 734 807 693 505 | **identical to the incumbent** | +33.3% |
| panda170-apmp | 3 617 348 292 675 | **identical to the incumbent** | +20.8% |
| osa225-full | 10 281 602 977 425 | 9 320 263 854 330 (not even recovered) | +150% |
| iop215-full | 10 572 303 956 565 | 10 407 703 241 065 (not even recovered) | +135% |

- No shape produced a conditional build above the no-condition optimum in 600 s: on the three
  shapes where CP recovered the incumbent it landed on it EXACTLY. The residual badge gaps are
  therefore (with high confidence, pending exact proof) **pure certificate looseness**.
- The monolithic dual is confirmed hopeless at levels 170–240 (+20.8% at best after 600 s, vs the
  DP union's 1.5–8.2%) — no direct-optimization route to ProvenOptimal above the ≤140 world-tree
  band. Do not re-run plain full-model CP as a *proof* instrument at these levels.
- Next measurement: the partition-exactness probe (`WAKFU_S4_CP_FULLCOND=1`,
  `WAKFU_S4_CP_CUTOFF=<incumbent>`) — prove merely that no conditional-carrying build EXCEEDS the
  incumbent (INFEASIBLE / bound ≤ cutoff), which composes with the no-condition oracle's exact
  authority into full ProvenOptimal. Decision-problem duals routinely close where optimization
  duals stall; measuring on the same five shapes.

#### The FULLCOND decision probe fails too — but CARRIER-FORCED exact CP breaks the wall (2026-07-21)

- `WAKFU_S4_CP_FULLCOND` (require ≥1 conditional + cutoff=incumbent, 600 s): all five shapes
  UNKNOWN, bounds still +14–150% above the cutoff, millions of branches. Even the pure decision
  problem is reification-walled monolithically. Do not retry monolithic conditional proofs (any
  cutoff/decision framing) at levels 170–240.
- **Per-carrier CP (`WAKFU_S4_CAPPER_CP`, carrier forced via `requiredSublimationStateId`) is the
  instrument that works.** Sacrieur230, 120 s each, others relaxed: Inflexibilité II OPTIMAL
  9 462G, Prétention III OPTIMAL 9 228G @600 s (its 120 s dual 18 911G was just slow), Neutralité
  ≤14 402G, Ambition ≤16 154G — all ≤ incumbent 18 586G. Secret critique's relaxed world peaked at
  18 820G (+1.26% ABOVE the incumbent) but its witness was NOT exactly feasible (it leaned on
  other relaxed conditionals 6821/6931/7115); pairwise splits (±Prétention) didn't move it.
- **Decisive run: the exact SC world — carrier forced, ALL conditions exact, nothing relaxed —
  proves OPTIMAL 16 312 436 898 030 within 600 s and closes.** Same value as the
  others-excluded strict run: alongside Secret critique the other conditionals contribute nothing
  exact. Carrier concentration collapses the reification wall that defeats the monolith.
- Composition theorem for the ProvenOptimal route above the ≤140 world-tree band: every exact
  build either carries no conditional sub (the no-condition oracle's EXACT authority) or carries
  some conditional carrier c (bounded by c's carrier-forced exact world). If every carrier world
  proves ≤ incumbent, the certificate closes exactly at the incumbent; a carrier world proving
  ABOVE it yields an exact witness (counterexample flow → raise the incumbent). Harness now
  supports `WAKFU_S4_CAPPER_ALL=1` (sweep every solver-choosable supported-condition carrier),
  `WAKFU_S4_CAPPER_KEEP`/`DROP`/`STRICT`/`ONLY`. Full-carrier sweep on sacrieur230 running.

#### SACRIEUR230 TRUE OPTIMUM PROVEN = 18 586 084 644 855 — the composed per-carrier certificate (2026-07-21)

The first proven optimum above the ≤140 world-tree band, assembled from measured pieces (all runs
cold, `WAKFU_S4_CAPPER_ALL=1 WAKFU_S4_CAPPER_KEEP=` i.e. every condition exact):

- **No-conditional builds:** the no-condition oracle proves OPTIMAL 18 586 084 644 855.
- **13 of 15 carrier worlds** (carrier forced, every other conditional present and exact) close at
  ≤300 s: 11 OPTIMAL (Inflexibilité-AP 5 166G, Constance 15 847G, Mesure 16 922G, Furie 16 260G,
  Furie II 16 147G, Inflexibilité II 9 350G, Volonté de fer 16 449G, Mesure II 16 449G, Mesure III
  17 569G, Secret critique 16 312G, Prétention III 11 170G-dual) + Neutralité III ≤15 850G and
  Ambition III ≤14 723G as sound duals — all ≤ the incumbent.
- **The two blockers close by set-cover:** Dénouement (CRIT_AT_LEAST) plateaus at +20% and Expert
  des armes légères III (NO_OFFHAND_OR_TWO_HANDED) at +4.5% even at 900 s *with the other
  reifications in the model* — but any build containing one of the other 13 carriers is already
  covered by that carrier's closed world, so only conditional-sets ⊆ {D, E} remain:
  `{D alone}` OPTIMAL 16 449G (STRICT ≤300 s), `{E alone}` OPTIMAL 17 713G (STRICT ≤300 s),
  `{D ∧ E}` OPTIMAL 16 449G (STRICT+KEEP=Expert, the optimum does not even take Expert). All ≤
  the incumbent. ⇒ every exact build is ≤ 18 586 084 644 855, and the oracle attains it. ∎
- Lessons for the production wiring: (a) carrier-forced concentration beats the reification wall
  *only when the carrier's condition prunes the space* (AT_MOST-style caps); AT_LEAST /
  weapon-shape carriers stall and need the STRICT + pairwise set-cover instead; (b) the cover
  argument needs no first-carrier ordering — a build is covered by ANY of its carriers' closed
  worlds, so blockers only ever need their {alone} and {pairwise-with-other-blockers} strict
  worlds; with k blockers that is 2^k − 1 strict solves, and k was 2 here; (c) budgets: closed
  carriers ≤300 s each, sequential sweep ≈ 25 min — a production stage must gate on the DP's
  per-world looseness (run carrier CP only for carriers whose credit can still own the badge) and
  cache by CERTIFIER_VERSION.

#### v36 — the per-carrier closure SHIPPED as silent refinement (2026-07-21)

Production wiring (CERTIFIER_VERSION 35→36), per the user's integration decision: the fast DP
badge shows first, then the per-carrier closure refines it in the background with a visible
"Affinage de la preuve…" spinner on the badge.

- **Engine**: `MaxDamageSoftCertificate.perCarrierClosureUpper` — full-exact solve per
  solver-choosable conditional carrier (120 s each; a carrier the solver does not model = an
  EMPTY world, skipped); unclosed carriers become *blockers*, closed by enumerating the non-empty
  blocker SUBSETS (≤4 blockers ⇒ ≤15 runs; each subset FORCED via the new
  `requiredSublimationStateIds` model seam, other blockers excluded — the fully concentrated form
  that measures OPTIMAL where partial-keep runs stall: D-forced+E-kept sat at +11.4% while
  {D}/{E}/{D∧E} all close). `noConditionOptimum` pays a 900 s oracle anchor when the fast pass's
  240 s oracle only returned a dual (sacrieur's case). `MaxDamageSearch.refineSoftLegProof` +
  `WakfuBestBuildFinderAlgorithm.refineMaxDamageOptimality` compose
  `min(firstPassUpper, max(oracleOptimum, conditionalUpper))` with the same self-check discipline.
  All memoized (keyed incl. CERTIFIER_VERSION).
- **GUI**: `ProofState.ProvenWithin(refining=true)` renders the badge plus a spinner; the refined
  verdict swaps in when the closure lands. New Tr keys (carrier-closure stage, refining label);
  the previously-unmapped `capFreeResplit`/`oracleJoin` stage keys now narrate as "finalizing".
- **Gate (cold)**: sacrieur230 E2E — fast badge +1.881% in 62.7 s, then
  **`S4_PROD_REFINE verdict=ProvenOptimal` in 19.6 min**: refined upper = incumbent =
  18 586 084 644 855 exactly (strict subsets: {D} 16 449G, {E} 17 713G, {D∧E} closed; conditional
  side 18 386G < oracle). First production ProvenOptimal above the ≤140 band. Two earlier gate
  iterations fixed: unmodeled-carrier worlds must be skipped-as-empty (not counted unbounded), and
  the blocker cover must force full subsets (a keep-others run is not in the provable class).
- E2E lock: `WAKFU_S4_REFINE=1` runs the refinement after the fast badge, asserts it never
  loosens, and pins sacrieur230 to exactly ProvenOptimal.

#### v36 refine sweep + prompt cancellation (2026-07-21)

Refine sweep over the residual shapes (cold, sequential):

| shape | fast badge | refined | refine wall |
|---|---:|---:|---:|
| sacrieur230 | +1.881% | **ProvenOptimal** | 19.6 min |
| steamer240 | +1.887% | **ProvenOptimal** | 26.3 min |
| panda170 | +8.159% | **ProvenOptimal** | 14.9 min |
| feca65 | +6.996% | **ProvenOptimal** | 18.2 min |
| osa225 | +1.562% | unchanged | 34.9 min |
| iop215 | +1.588% | unchanged | 34.5 min |

- **4/6 residual shapes close to ProvenOptimal** — including panda170 (the worst badge of the
  whole matrix) and feca65. The two crit=100 shapes (osa225, iop215) resist: exactly the shapes
  whose plain-CP could not even recover the incumbent in the truth table — their carrier worlds
  need a next-level split (diagnose which carrier/blocker stays open before designing it).
- feca65's first sweep entry died BETWEEN the closure logs and the verdict println with no
  assertion in the XML — transient JVM death under ~2 h of accumulated sweep load (the known
  EOF pattern); the clean re-run closed ProvenOptimal. Not a mechanism defect.
- **Prompt cancellation shipped** (user: a search must never starve another): the profile solve
  now takes `shouldContinue`, watched by a 500 ms daemon thread that calls `CpSolver.stopSearch()`
  — a cancelled multi-minute proof/refine solve releases its workers within ~1 s instead of
  running out its budget (up to 15 min before). Wired through the carrier worlds, the refinement
  oracle anchor and the first-pass union oracle. Inert without cancellation (results bit-identical
  — no CERTIFIER_VERSION bump); a cancellation-degraded read is never memoized (guards on both
  memos). Locked by an always-on test: full sacrieur pool, 60 s budget, cancel at 2 s → returns
  in 2.5 s.
