# Investigation brief — max-damage + sublimations: optimum found fast, never *proven*

> Hand this to an agent. It is self-contained. Goal = **root cause**, not a workaround.

## The problem (one paragraph)

In this repo (Kotlin + Google OR-Tools CP-SAT build solver, package `me.chosante.autobuilder`), a
**max-damage** search **with sublimations enabled** finds its optimum in ~20 s but then **fails to prove
optimality** for the whole remaining time budget, printing *"Optimality not proven (no certificate for this
request)"*. The **same search with sublimations OFF proves in ~3 s**; runes are innocent (also ~3 s). Adding
~36 modelable sublimations (a few dozen booleans) should not turn a 3 s proof into an "impossible in 120 s"
proof — so we suspect a **modeling inefficiency**, not a fundamental limit. Find it.

There are **two independent failures** to explain:
- **(A)** CP-SAT can't **close its optimality gap** with subs in the model (it finds the incumbent fast but
  can't prove no-better-exists, so it burns the entire `--duration`).
- **(B)** The custom certificate (`MaxDamageCertifier`) **bails** ("no certificate for this request").

Do **not** "fix" this with early-stop, convergence heuristics, or a shorter default duration. Identify the
specific model construct / sublimation shape responsible for each, with evidence.

## Reproduce (baseline; 10-core Mac ⇒ cores-1 = 9 workers)

```sh
# PROVES in ~3s:
./gradlew :autobuilder:run -q --args="--class cra --max-level 110 --pa 11 --pm 4 --po 4 \
  --computation-mode max-damage --scenario-element fire --duration 120 --no-sublimations"

# NOT proven; burns the full 120s. Incumbent ~8456 is reached by <=20s and never improves:
./gradlew :autobuilder:run -q --args="--class cra --max-level 110 --pa 11 --pm 4 --po 4 \
  --computation-mode max-damage --scenario-element fire --duration 120"
```

Measured: lean (no subs/no runes) **3.1 s proven**; runes-only **3.2 s proven**; full **123 s NOT proven**;
incumbent ~8456 by 20 s, unchanged at 60 s / 120 s. (Single element `fire` — this is NOT a multi-element /
worker-splitting artifact; it reproduces on one element with all 9 workers.)

## Ground facts (don't re-derive)

- Modes live in `ScoreComputationMode`: `most-masteries` (default), `precision`, `max-damage`.
- Production solve sets `numSearchWorkers = params.solverWorkers ?: (availableProcessors()-1)` and
  `maxTimeInSeconds = --duration`. **Max-damage uses the whole budget; it exits early only if it PROVES**
  (unlike most-masteries, which exits the instant CP-SAT closes its gap).
- **Two-part proof.** CP-SAT optimizes a **proxy** objective (`perTurnDamageScore`) that is not exactly the
  displayed damage, so a **separate certificate** (`MaxDamageCertifier`) is what stamps "proven". Consequences:
  CP-SAT's own OPTIMAL status is not used as the proof, and if CP-SAT never reaches OPTIMAL on the proxy it also
  never early-stops. Both (A) and (B) must be understood.
- ~36 / 232 subs are `solverChoosable`. Each chosen sub folds its effects into stat terms. **%DI subs** fold
  into `DAMAGE_INFLICTED`; per-hit damage is a **bilinear product** `mastery × (1 + DI/100)`.
- The unproven build uses these subs (useful for Part B mapping): **Anatomy** (EPIC, −20% / +40% DI *rear*),
  **Vital Influence** (+12% crit *rate*), **Devastate**/**Carnage** (+%-of-level mastery), **Vivacity** (+1 AP),
  **Visibility** (+1 range), **Influence** (+9% crit rate), **Neutrality** (sec-mastery ≤ 0 → +24% DI),
  **Destruction** (−6% / +9% DI *rear*), **Heavy Armor** (+10% DI, −1 MP), **Burn** (+12% fire DI).

---

## Part A — why can't CP-SAT close the gap?

**A1 — model-size delta.** Print CP-SAT variable/constraint counts with vs without subs (from the assembled
`CpModel` proto). The model is built in `WakfuBuildSolver.buildModel(...)` → `StatBuilder`. Decide: size
explosion, or a *bound-quality* problem?

**A2 — CP-SAT search log (the decisive experiment).** Temporarily set
`solver.parameters.logSearchProgress = true` in the **production** max-damage solve (`WakfuBuildSolver.kt`, the
`optimize(...)` solve path where `numSearchWorkers = params.solverWorkers ?: (availableProcessors()-1)` — around
line ~1660; **not** the `warmUp()` solve ~line 232 nor the tuning solve ~line 639 unless that is the one this run
hits). Run the full config and read the log: watch **`best_objective_bound` vs the incumbent** over time.
Expected to confirm: incumbent found fast, **bound stuck far above it** ⇒ weak LP relaxation ⇒ this is a
*bounding* problem, not an *incumbent* problem. (The bounded objective is the proxy `perTurnDamageScore`.)

**A3 — sublimation-family bisection.** Add a temporary env-gated filter in the choosable-sub selection
(`SublimationModelBuilder.isModelableSublimation` / `createSublimationModel`) admitting ONE family at a time,
re-running each: (a) plain FLAT stat subs; (b) %-of-level mastery subs (Devastate/Carnage/Light Weapons Expert);
(c) **%DI subs** (Neutrality/Measure/Heavy/Anatomy/Destruction); (d) conditional subs (those with a reified
`condition`); (e) per-element DI (Burn/Freeze). Find which family keeps the gap open (hypothesis: (c) and/or (d)).

**A4 — confirm the bilinear-bound hypothesis.** The per-hit upper bound is
`StatBuilder.maxPerHitProductBound(scenario)` (`DamageObjective.kt:465`) feeding `perHitDamageScore`
(`DamageObjective.kt:268`). Inspect the declared domain of the DI variable (the `clampVar(actualStat(
DAMAGE_INFLICTED), …)`) and the product's declared max with vs without subs. Hypothesis: %DI subs raise DI's max
(and its *reachable* range), so the product's LP upper bound balloons and branch-and-bound can't prune. If
confirmed, the fix direction is a **tighter, sub-aware reachable bound** on DI / the product — the same
"per-slot reachable domain" trick already used for items (see `docs/MAX_DAMAGE_PROVABLE_OPTIMUM.md`).

---

## Part B — why does the certifier bail?

**B1 — locate & log every bail.** `MaxDamageCertifier` gives up by returning `Long.MAX_VALUE` / `null`. The gate
is `certifierWorlds(scenario)` (`MaxDamageCertifier.kt`: `?: return Long.MAX_VALUE` ~line 124; the `return null`
coverage bails cluster ~lines 178, 188, 213, 216, 219, 222, 223, 228, 229). Add a log at each bail with the
reason + the offending sub, run the full config, and record **which bail fires**.

**B2 — map the build's subs to certifier coverage.** For each sub in the build (list above), check whether
`certifierWorlds` / `certifyMaxPerHitAtAp` models it or bails. The certifier is documented to charge
AP / crit-mastery / secondary-mastery conditions exactly; its coverage of **orientation-gated DI** (Anatomy /
Destruction "rear") and **crit-rate** subs (Vital Influence / Influence) is the prime suspect — verify which is
first-unsupported.

**B3 — confirm by subtraction.** There is no per-sub exclude flag (only `--no-sublimations` = all off, and
`--forced-sublimations`). Reuse the **same temporary env-gated filter** as A3 (in `SublimationModelBuilder`) to
drop the suspected shape(s) from the choosable set — e.g. exclude the two rear-DI subs (Anatomy/Destruction),
then the crit-rate subs — and re-run; see whether the certificate returns. That pins the exact unsupported shape
and tells us whether B is one gap or several. (Note: the certifier reads the *forced/choosable* sublimation set
the model was built with, so filtering at the model-build source is what changes what it must certify.)

---

## Files map (one-liners)

| File | Role |
|---|---|
| `WakfuBuildSolver.kt` | orchestrator: `optimize()` (production solve; worker/time/log config ~L1660), `buildModel()`, `warmUp()` (~L232, ignore), tuning solve (~L639) |
| `StatBuilder.kt` | CP-SAT stat variables/terms; the DI clamp; `diAdjustedPerElementMasteryScore` (most-masteries only) |
| `DamageObjective.kt` | max-damage objective: `perTurnDamageScore` (L33), `perHitDamageScore` (L268), `maxPerHitProductBound` (L465) ← **the bilinear bound** |
| `SublimationTerms.kt` | `buildSublimationTerms`, `reifyCondition`, `appliesVar`, `scenarioElementStrongestVar`, `perStatStepGatedVar` |
| `SublimationModelBuilder.kt` | `isModelableSublimation`, `createSublimationModel` (choosable selection + carrier gating) ← **A3 bisection hook** |
| `SublimationSemantics.kt` | `subConditionSpec`, `SUPPORTED_SUB_CONDITIONS`, `scenarioGateMatchesCore` |
| `MaxDamageCertifier.kt` | certificate DP; `certifierWorlds` (**coverage gate / bails**), `certifyMaxPerHitAtAp`, `certifyLedger` |
| `MaxDamageSearch.kt` | per-element enumeration; `proven = allElementSolvesProved && !debuff` |

Related docs: `docs/MAX_DAMAGE_PROVABLE_OPTIMUM.md`, `docs/SOLVER_PERFORMANCE.md`.

## Discipline (non-negotiable)

This is **provably-optimal solver code**: a wrong bound silently breaks the "proven optimum" guarantee without
crashing. Any model change must (1) keep the optimum **identical** — verify with the `==CP-SAT` soundness tests in
`WakfuBuildSolverTest` + full `./gradlew :autobuilder:test`; (2) be re-verified on a **cool** machine
(wall-clock is thermal-noisy — prefer OR-Tools *deterministic time*, not numBranches/wall-clock, per
`docs/SOLVER_PERFORMANCE.md`). Revert all instrumentation (logs, counters, env-gated filters) before finishing.

## Deliverable

A short written report:
- **(A)** the exact construct that keeps the CP-SAT bound loose — with the var-count (A1), search-log (A2),
  family-bisection (A3) and product-bound (A4) evidence — and an optimum-preserving fix direction.
- **(B)** the exact certifier bail + sublimation shape that triggers it (B1/B2/B3 evidence) — and whether
  extending coverage is tractable or whether the shape is inherently uncertifiable.
- A one-line verdict: is the slowness a **fixable modeling inefficiency** (tighten a bound / extend coverage) or
  an **inherent** cost of the sub layer?

---
---

# FINDINGS (investigation executed 2026-07-04)

> Method: all instrumentation was env-gated (search log, model stats, domain dumps, a sub-family filter in
> `createSublimationModel`, bail logs in `certifierWorlds`/`certifyMaxPerHitAtApPass`/`proveOptimality`) and has
> been **fully reverted** — the working tree is back to its pre-investigation state. Runs used the fat CLI jar
> directly (9 CP-SAT workers, 10-core M-series Mac). Objective values below are CP-SAT objective units (with
> required targets they include the ×penalty-multiplier scale, so they are only comparable within a row group).

## One-line verdict

**Both failures are real but neither is what the brief suspected: (A) is a fixable *structural* interaction —
the required-target penalty product `objective = damage × multiplier`, not the %DI bilinear — and (B) is not a
certifier bail at all: the certificate is skipped *by design* for any request with required AP/MP/range targets,
and when actually exercised it covers all 39 choosable subs with zero bails.**

## The decisive 2×2 (Cra 110, fire, 120 s budget)

| | no sublimations | 39 choosable sublimations |
|---|---|---|
| **no required targets** | proves instantly | **OPTIMAL in 78.8 s** (`nt-full`) |
| **`--pa 11 --pm 4 --po 4`** | **OPTIMAL in 0.97 s** | FEASIBLE at 120 s; still FEASIBLE at **600 s** (bound +17.2 %, det-time 3006) |

Neither layer alone blocks the proof. The blocker is their **product**: `applyConstraintPenalty` multiplies the
damage score by a power-6 shortfall multiplier (`WakfuBuildSolver.kt` ~L1515, `addMultiplicationEquality`), and
with the sub layer present CP-SAT cannot close the dual gap on that product. Any *single* target suffices:
`--pa 11` alone, `--pm 4` alone and `--po 4` alone each leave the full-sub model FEASIBLE at 60 s (gaps
+21/+24/+27 %).

## Part A — why the bound stays loose

- **A1 (model size): not a size problem.** 2,440 vars / 782 constraints with subs vs 646 vars without —
  both tiny.
- **A2 (search log): a pure bounding problem.** Incumbent `1.3378e12` (the true optimum — no later run ever
  beat it) is found at **~15 s**; the dual bound falls fast to `1.87e12` (~8 s) then decays asymptotically:
  `1.60e12` @120 s → `1.567e12` @600 s. It would not close with any realistic budget.
- **A3 (family bisection, 60 s each, targets on): every "suspect" family proves.**
  flat(16) 12.5 s · uncond-DI(3) 43.4 s · gated-DI (Anatomy/Destruction/Burn) 9.8 s · conditional(14) 7.4 s ·
  Elemental Concentration 2.4 s · Featherweight 15.6 s · di+gatedi (the *widest* DI span) 6.8 s ·
  flat+cond (30 subs) 29.4 s — **all OPTIMAL**. The only stuck family set was `conv+bec+ramp`, and round 2
  isolated it: **Unraveling alone (Dénouement, stateId 5077 — "if Crit ≥ 40 %: convert 100 % of critical
  mastery to elemental mastery") is sufficient to kill the proof** (FEASIBLE at 120 s, bound +21.7 %, for an
  optimum only +1.1 % over no-subs). It is *not necessary*, though: all-38-subs-minus-Unraveling is also stuck
  at 120 s (bound +9.8 %) — the sub layer's phantom relaxation headroom is cumulative, Unraveling is just the
  single worst contributor.
- **A4 (product bound): the D×Graw "DI balloon" hypothesis is refuted.** The DI clamp does widen with subs
  (`D ∈ 55..229` vs `100..110`), but the widest-D family combination proves in 6.8 s. Conversely Unraveling
  widens **D not at all** (100..110) yet blocks: it raises the *mastery* reach by exactly the critM budget
  (2242 → 2795) and Graw's ceiling by +41 % (1.335e6 → 1.888e6). Mechanism: the `moved` conversion var is
  linearly coupled (`+moved` into elemental mastery, `−moved` into critM), but the damage chain reads both
  sums through `tClamp` (a `max`/`min`-equality pair) whose *upper* side is only combinatorially enforced —
  in the LP relaxation the clamped mastery can float to its (conversion-widened) declared ceiling while critM
  stays at *its* max, i.e. the relaxation banks the moved critical mastery **twice**. A required target then
  amplifies the gap from the other side: a real build pays gear/skill slots to reach AP/MP/range (lowering the
  true optimum) while the fractional relaxation meets the target almost for free — evidence: without targets
  the same full-sub model proves (78.8 s), with any one target it never does.
  - The already-implemented AM-GM joint cut (`maxPerHitProductBound`, `dGrawJointBound=true`) was tested
    (env-gated) and is **not** the fix: bounds unchanged on both the conv-only and full configs (the cut is
    ~20 % tighter than the box product on paper, but the binding looseness sits in the penalty/clamp layers,
    not the per-hit product cap).

### Optimum-preserving fix direction (A)

Don't ask CP-SAT to close the gap on the penalized product. When the returned incumbent **meets every required
target** (the overwhelmingly common outcome — the power-6 penalty makes target-missing builds nearly always
dominated):

1. **Prove-phase re-solve with hard targets.** Re-run the solve with the required targets as *hard linear
   constraints* (≥ target) and the plain damage objective — the penalty product disappears and the model is
   exactly the shape that proved in 78.8 s. Its proven optimum `D*` equals the incumbent's damage iff the
   incumbent is optimal among target-meeting builds.
2. **Close the target-violating corner** with the penalty's own granularity: any violating build has
   `multiplier ≤ table[maxIndex−1]`, so its objective is bounded by `(global damage bound) × that multiplier`,
   where the global damage bound comes from the (target-free) certificate ledger or the `nt`-style solve. With
   the power-6 table one missed point costs tens of percent, so this bound almost always clears the incumbent.
   Both halves are sound and optimum-preserving; neither touches the production search model.

Alternatively (cheaper, weaker): extend `proveOptimality` to required-target requests by comparing in damage
units (see B below) — sound, but conservative whenever the targets actually bind (i.e. cost damage), which is
exactly when users set them.

## Part B — the certificate

- **B1: no bail fires.** For the repro, `proveOptimality` returns `Unavailable` at the
  **required-stat-target gate** (`MaxDamageSearch.kt` ~L302: `isRequiredMostMasteriesTarget` — here RANGE,
  ACTION_POINT, MOVEMENT_POINT) **before any certifier code runs**. "No certificate for this request" is the
  documented policy (the penalty multiplier puts the CP-SAT objective in different units than the damage-only
  ledger), not a coverage failure. The no-subs run only says "Proven optimal (certificate)" because CP-SAT
  itself reached OPTIMAL (`result.isOptimal` short-circuit) — that CLI wording is misleading on that path.
- **B2/B3: coverage of the sub layer is complete on this pool.** Exercised for real (no targets, 30 s budget so
  CP-SAT stays FEASIBLE): the ledger comes back with **`bailedCells=[]`** — none of the 39 choosable subs
  (incl. the suspected rear-DI Anatomy/Destruction and crit-rate Influence/Vital Influence, and even the
  Unraveling conversion via its two-world split) triggers a world or cell bail. Verdict printed:
  **"Proven within 0.1 % of optimal"** (`maxCell 1,454,400` vs incumbent `1,452,380`, +0.14 % — the certifier's
  documented legitimate looseness on crit bands/couplings, not a gap in coverage). Same for the conv-only
  config (+0.11 %) — **the certificate succeeds precisely where CP-SAT's own gap is hopeless (+25 %)**.

### Fix direction (B)

Tractable, two independent pieces:
1. Reword the CLI/GUI copy: for required-target requests say the certificate is *not applicable to
   constrained requests* (policy), and don't print "(certificate)" when the proof came from CP-SAT's own
   OPTIMAL status.
2. If the badge should *work* for required-target requests, implement the damage-units comparison from the
   A-fix: when the incumbent meets all targets, `incumbentDamage ≥ maxCellObjective` (target-free ledger)
   soundly implies request-level optimality; expect it to be conservative when targets bind. The full fix is
   the hard-target re-solve above.
3. (Optional polish) the 0.1 % residual: on sub-heavy shapes the exact tier stays ~0.1 % above the true
   optimum, so even eligible requests get "proven within 0.1 %" rather than the full badge — tightening the
   crit-band/coupling folds is a separate, known work item.

## Measured matrix (all runs, for reference)

| Config (Cra 110 fire) | Status | Objective | Final bound | Wall |
|---|---|---|---|---|
| targets, no subs | OPTIMAL | 778,848,773,570 | = | 0.97 s |
| targets, full 39 subs | FEASIBLE | 1,337,753,700,325 | 1,567,218,925,790 @600 s | never |
| no targets, full subs | OPTIMAL | 1,452,380 (raw) | = | 78.8 s |
| no targets, full subs, 30 s | FEASIBLE | 1,452,380 | 1,825,030 | cert: within 0.1 % |
| targets, conv only | FEASIBLE | 787,418,014,355 | 957,850,692,190 @120 s | never |
| no targets, conv only | FEASIBLE | 853,770 | 1,067,690 @60 s | cert: within 0.1 % |
| targets, all minus conv | FEASIBLE | 1,337,753,700,325 | 1,469,148,725,695 @120 s | never |
| targets, flat(16) | OPTIMAL | 961,659,243,650 | = | 12.5 s |
| targets, di(3) | OPTIMAL | 788,459,882,490 | = | 43.4 s |
| targets, gatedi(3) | OPTIMAL | 1,069,250,822,395 | = | 9.8 s |
| targets, cond(14) | OPTIMAL | 937,855,797,025 | = | 7.4 s |
| targets, bec | OPTIMAL | 920,717,315,455 | = | 2.4 s |
| targets, ramp | OPTIMAL | 778,848,773,570 | = | 15.6 s |
| targets, di+gatedi | OPTIMAL | 1,081,628,614,640 | = | 6.8 s |
| targets, flat+cond | OPTIMAL | 1,151,977,146,320 | = | 29.4 s |
| `--pa 11` only, full subs | FEASIBLE | 1,448,027,217,140 | 1,750,119,126,140 @60 s | never |
| `--pm 4` only, full subs | FEASIBLE | ~1.3924e12 | ~1.7230e12 @60 s | never |
| `--po 4` only, full subs | FEASIBLE | ~1.3989e12 | ~1.7773e12 @60 s | never |
| joint-cut (`dGrawJointBound`) probes | — | unchanged incumbents | unchanged bounds | not the fix |

Family key — flat: unconditional non-DI stat subs; di: unconditional global DI (Swiftness/Heavy Armor/
Abnegation); gatedi: scenario-gated DI (Anatomy/Destruction/Burn); cond: supported-condition subs (Measure/
Neutrality/Outrage/…); conv: Unraveling; bec: Elemental Concentration; ramp: Featherweight.

---
---

# IMPLEMENTATION (2026-07-05)

Two follow-ups from the finding: **(1)** make the certificate the proof authority for required-target
requests (the A/B gate lift); **(2)** reduce the wasted CP-SAT proof time.

## Piece 1 — the certificate now proves required-target requests ✅ (shipped)

Previously `MaxDamageSearch.proveOptimality` returned `Unavailable` for ANY request with a required AP/MP/
range target, because it compared the **penalized** CP-SAT objective (`damage × shortfall-multiplier`) against
the damage-only certificate ledger — different units. The fix carries the build's **unpenalized** damage proxy
out of the solve (`SolverResult.maxDamageRawProxy`, read from the pre-penalty `perTurnDamageScore` var) and
compares THAT against `maxCell`. Sound because: when the incumbent **fully meets every required target** its
shortfall multiplier is the flat maximum, so `proxy ≥ maxCell ⇒ penalized-optimal` (proof in the code comment).
A target-missing incumbent, a survivability floor, forced runes or a multi-element shape stay `Unavailable`.

- Files: `SolverResult.kt` (+`maxDamageRawProxy`), `WakfuBuildSolver.kt` (expose the pre-penalty score var,
  thread it through `executeSolverAndEmitResults`), `MaxDamageSearch.kt` (`proveOptimality` rewrite +
  `fullyMeetsRequiredTargets`, which reuses the scorer's own `requiredConstraintPenaltyFactor` so it can't
  drift). CLI message reworded (`Main.kt`) — the old "(no certificate for this request)" is no longer true.
- No `CERTIFIER_VERSION` bump: the certifier itself is untouched; only its **consumer** changed.
- Verified: the repro (`--pa 11 --pm 4 --po 4`, full subs) now prints **"Proven within 8.4 % of optimal"**
  instead of "Optimality not proven". The 8.4 % is the honest, sound cost of the **binding** MP/range targets —
  the certificate bounds damage over ALL builds, so binding non-damage targets legitimately loosen the ratio.
  A non-binding target proves tight. Locked by a new soundness test in `WakfuBuildSolverTest`.

## Piece 2 — reduce the wasted proof time: `certifierCellCap` REJECTED (measured, counterproductive)

The 72 s the no-target case spends is CP-SAT closing its bilinear dual gap (§Part A). The candidate lever was
`certifierCellCap` — inject the certifier's per-AP-cell upper bounds into the search model as
`perHit ≤ certifierCeiling` constraints, "collapsing the root gap to the certifier's ceiling." Measured with a
clean search-only gate (level-110, full 39 subs, search log on):

| run | certifier cost (model build) | CP-SAT root bound | status | verdict |
|---|---|---|---|---|
| baseline (no cap), no target | — | (native, loose) → 0 % in **78.8 s** | **OPTIMAL** | proven optimal (exact) |
| certcap **fast**, no target | **~25 s** | **1,480,660 instantly** = incumbent **+1.9 %** | **FEASIBLE, stuck** | within 0.1 % (async cert) |
| certcap **exact**, no target | **~774 s (13 min!)** | — | (build never finishes usefully) | — |
| certcap **fast**, `--pa 11 --pm 4 --po 4` | ~25 s | — | FEASIBLE | within 8.4 % (== baseline; no help) |

**The finding is decisive and it kills the idea.** certcap injects the certifier's ceiling as a HARD cap, so
CP-SAT's dual bound can never fall below it — and on any sub-heavy pool the certifier has residual looseness
(≈1.9 % fast, ≈0.1 % exact, from crit-band / coupling folds). So the capped bound floors **above** the incumbent
and CP-SAT is **guaranteed to stay FEASIBLE — it can never prove OPTIMAL**. certcap doesn't accelerate the proof;
it *prevents* the exact proof that CP-SAT's slower native bound (78 s) actually reaches. Three more nails: the
**fast** cap costs ~25 s of model build (front-loaded **before the first build appears** — a UX regression); the
**exact** cap costs **~13 min** at level 110 (and far worse at 245) — categorically infeasible to inject; the
target-request run still ended "within 8.4 %" (identical to baseline — certcap gave the penalty-product objective
no help either); and the badge already waits for the whole `--duration` regardless, because the GUI/CLI launch the
certificate only **after** the search flow completes (`BuildSearchModel.launchOptimalityProof`, `Main.kt`). Not
shipped; the experiment flag stays dormant (its soundness lock is retained for the record).

**Why no cheaper Piece-2 win exists.** Certificate-driven early-stop (stop the search when the certificate
proves the incumbent optimal) would be sound but yields ≈0 practical gain: it fires only when the certifier is
**exactly** tight, which is precisely the crit-free / few-sub shapes where **CP-SAT already proves in seconds**
and the search stops on its own. The pools where CP-SAT is slow (sub-heavy) are exactly the pools where the
certifier is loose, so the cert never reaches OPTIMAL there either. The no-target 78 s is CP-SAT achieving an
**exact** proof that is strictly tighter than the certificate's 0.1 %; it is the best available result, not
waste. The only remaining wait — the required-target search burning its full `--duration` before the (loose,
async) badge — has no sound solver-side early-stop signal and would need a product decision (shorter default
duration, or surfacing the faster 0.1 %-loose certificate result in place of the exact CP-SAT proof), not an
engine change.

**Net deliverable:** Piece 1 makes the certificate the proof authority for **every** single-element max-damage
request (target or not) — the substantive fix the user asked for. Piece 2's premise (the certifier can
*accelerate* CP-SAT's proof) is falsified: the certifier is a bound-*prover*, not a bound-*tightener* for
CP-SAT, and forcing it into that role only caps the proof short.

## Lever 2 — hard-target re-solve for a TIGHT binding-target proof: also REJECTED (measured, stuck)

Follow-up idea for the "within 8.4 %" case: since the certificate is loose only because it bounds *unconstrained*
damage, re-solve with the required targets as **hard constraints** + the **plain** (un-penalized) damage
objective — CP-SAT would then prove the *constrained* optimum, and the repro would read "proven optimal". The
insight was right (removing the penalty product removes the Part-A pathology), but the constrained model has the
**same bilinear McCormick difficulty — worse, in fact**, because the AP/MP/range hard constraints pin the build
into a corner the relaxation certifies poorly. Measured (level-110, full subs, repro targets, plain objective):

| budget | incumbent (constrained opt) | dual bound | gap | status |
|---|---|---|---|---|
| 120 s | 1,341,775 | 1,595,800 | **18.9 %** | FEASIBLE |
| 300 s | 1,341,775 | 1,537,215 | **14.6 %** | FEASIBLE |

4.3 points closed in the extra 180 s and **decelerating** — it will not prove in any practical budget. So the
binding-target optimum is provable neither by the certificate (which structurally can't see MP/range
constraints, so its bound is the unconstrained max = incumbent + 8.4 %) **nor** by a direct constrained solve.

## The honest bottom line on "can the proof be made fast + exact?"

No — and now we know *exactly* where every wall is, with evidence:
- **No-target**, sub-heavy: CP-SAT proves the **exact** optimum in **78 s** (best case). The certificate gives a
  0.1 %-loose answer faster (~25 s). Both already exist; the search early-stops on CP-SAT's OPTIMAL.
- **Binding-target** (the repro): **no tractable exact proof exists.** The certificate bounds unconstrained
  damage (→ within 8.4 %, sound); the constrained solve is stuck at 14.6 % after 300 s. "Within 8.4 %" is the
  honest best.
- **certcap** can't accelerate CP-SAT (it caps the dual bound *above* the incumbent). Dead.
- The **only** avenue with any payoff is **Lever 1 — tightening the certifier's ≈0.1 % crit-band/coupling
  residual to exact.** It would (a) turn the no-target badge from "within 0.1 %" into "proven optimal" and
  (b) make certcap viable. But it is narrow (it does **nothing** for binding-target requests — the certifier
  fundamentally can't model MP/range constraints) and uncertain (tighter DP = slower; needs a provenance
  diagnostic to even scope whether the 0.1 % is one fixable coupling or diffuse). Not attempted here; flagged as
  the sole remaining, speculative lever.

The max-damage optimality proof sits at the tractability frontier of a bilinear integer program. Piece 1 is the
real, shipped improvement; the rest is inherent cost, now fully characterised rather than assumed.

---
---

# FOLLOW-UP (2026-07-05) — the certificate does NOT generalise; the fix is the SOLVER

The sections above treat the **certificate** as the path to proving constrained requests. A later question —
*"what happens if I constrain elemental **resistance**?"* — showed that path has a low ceiling, and pointed at a
better one.

## Why the certificate can't carry constrained requests

The certificate is a **damage** bound: a per-AP-cell DP over a `(DI, graw, mp)` frontier. It can model a
constraint **only on a stat that lives on that frontier** — AP (the cell), MP (added this session), and range
(a would-be 4th axis). **Resistance is a defensive stat that does not change outgoing damage**, so it is
invisible to the certificate; constrain it and the certificate returns the *unconstrained* damage ceiling — a
large, misleading "within X%", never "proven". The same is true for HP, lock, dodge, wisdom, prospection,
block, … (~20 stats). Baking each in is an axis-per-stat with combinatorial blow-up. **So the certificate can
only ever prove the narrow AP/MP/range slice.**

Two certifier extensions were built this session before the pivot (MP-target frontier modelling +
signed-MP, both sound and test-locked) and then **REVERTED** once hard-constraints-first landed. Why reverted,
not kept: the hard-constraints solve *proves* the MP case directly (bypassing the certificate), and the
extensions measured **loose at scale** — on the real level-110 pool the constrained ledger's `maxCellObjective`
stayed at the unconstrained ~9168 even though the true MP≥4 optimum is 8775, so they never tightened the repro —
while adding a ~5-min per-request path and a `CERTIFIER_VERSION` bump. Sound but dead weight, so they were
removed; the certifier is back to its committed (Piece-1) state.

## Why the SOLVER is the real lever (and generalises)

Required targets are a **soft power-6 penalty product** (`applyConstraintPenalty`): `objective = damage ×
penaltyMultiplier`. That product is **not something CP-SAT can prove** — its McCormick-loose relaxation leaves a
dual gap that never closes — and the damage certificate is loose/blind on constrained requests. So even when the
soft solve *finds* the true constrained optimum, it can only report "within X%". Measured (Cra 110 fire): free
optimum **9148** (proven, 0.1%); the **`--pa 11 --pm 4` optimum is 8775** — MP≥4 genuinely binds (the 9148 build
carries MP < 4, so it is not a valid MP≥4 build; meeting MP≥4 costs the AP-14→13 shift, −4.5%). The soft penalty
finds 8775 but cannot prove it ("within 4.5%"). *(This corrects an earlier mid-investigation guess that 8775 was
sub-optimal and 9148 was achievable under the targets — it is not.)*

## Shipped — hard-constraints-first max-damage solver

Solve the required targets as **HARD `actual ≥ target` constraints** under a **plain** damage objective first;
if **INFEASIBLE** (unreachable targets) fall back to the **soft** penalty (unchanged closest-build behaviour).
The plain objective is the shape CP-SAT proves, so:
- A **binding** target CP-SAT can close: the constrained optimum is **proven**. **Repro `--pa 11 --pm 4`: 8775
  flips from "within 4.5%" to "Proven optimal"** (CP-SAT closed it in ~90 s and stopped early). Same build — but
  now proven, where the soft penalty product never could.
- A **vacuous** target (met by the unconstrained optimum) reduces to the unconstrained problem ⇒ proven outright.
- A **binding** target CP-SAT still can't close stays "within X%": the full `--pa 11 --pm 4 --po 4` repro finds
  the true optimum (8457) but range≥4 keeps the bilinear dual gap open, so it does not prove — that lone case
  would still need the (deprioritized) range certifier or a smarter solve.
- **Generalises to every constraint** — resistance, HP, lock, … — because it is the solver, not a damage bound,
  that enforces them. This is the piece the certificate could never be.
- Files: `StatBuilder.addRequiredTargetHardConstraints` (uses `requiredActualStat`, so the aggregate
  `RESISTANCE_ELEMENTARY` becomes min-of-four ⇒ one constraint forces all four elements),
  `buildMaxDamageObjective(hardConstraints)` / `buildModel` / `optimize` thread the flag (default false ⇒ every
  existing caller byte-identical), `MaxDamageSearch.optimizeHardThenSoft` orchestrates hard→soft and replaces the
  three `optimize` call sites. Max-damage only; most-masteries/precision keep the soft penalty. No
  `CERTIFIER_VERSION` bump (solver-only). Locked by two tests (vacuous ⇒ proven-directly; unreachable ⇒
  soft-fallback); full `:autobuilder:test` + `:gui-compose:test` green. Caveat: an infeasible request pays up to
  2× budget (hard fast-INFEASIBLE, then soft) — rare, acceptable.

**Revised bottom line.** "Prove every constrained request" is unreachable through the certificate (defensive
constraints are invisible to it). The durable win is the **solver** finding the true optimum for *any*
constraint set — which hard-constraints-first delivers — with "proven" as an honest bonus on the requests CP-SAT
can close (vacuous targets outright; the damage-frontier slice via the certificate).
