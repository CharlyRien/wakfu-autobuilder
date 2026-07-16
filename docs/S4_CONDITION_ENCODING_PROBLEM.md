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
