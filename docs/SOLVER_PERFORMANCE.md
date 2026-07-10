# Solver performance — optimality-preserving speedups (audit, 2026-06-24)

A backlog of ways to make the CP-SAT solver **faster in every mode** without ever compromising
**output quality** (the exact same optimal build is found) or **provability** (the solver still
reaches and reports `OPTIMAL` / a proven optimum, and any outer enumeration stays exhaustive).

> **For any agent picking this up.** This is a worklist, not a record of work done. Each item below
> carries the mechanism, a code anchor, expected impact, effort, an explicit *why-it-preserves-the-optimum*
> argument, and the test/guard it must ship with. The **Rejected** section (bottom) lists ideas that
> *look* like speedups but provably drop or change the optimum — **do not re-attempt them.**

## ⚠️ Measured outcome (2026-06-24) — most of §1 is NOT a real speedup

The top-pick model-level items were benchmarked on a real before/after, and the **deterministic** metric
(CP-SAT `numBranches`/`numConflicts` — fixed seed/workers/det-time, so reproducible and thermal-immune)
**contradicted the wall-clock numbers**. Wall-clock on this machine was useless: identical code ran
56 s → 236 s → 877 s on the same solve as the CPU **thermally throttled** (4× swings). Use `numBranches`,
not wall-clock, for any future A/B here.

> **⚠️ CORRECTION (2026-06-25) — even `deterministic_time` is unreliable here; treat every `×` below as
> soft.** A reproducibility test ran the *identical* full model back-to-back with 8 workers / seed 1:
> det-time came out **49.1 / 111.7 / 98.3** at lvl-110 and **358 / 478 / 439** at lvl-245 — a **2.3× swing on
> the same model in the same JVM**. CP-SAT's parallel-portfolio det-time accounting is perturbed by real-clock
> worker scheduling, which the thermal throttling jerks around (single-worker is reproducible but times out, so
> it's not a usable control). `numBranches` is reproducible but *non-monotone* (the domination filter posted 3.2×
> *more* branches yet "less" det-time — a smaller model can be slower). **Net: on this machine we cannot reliably
> attribute a proof-time speedup.** What IS solid and reproducible: (1) **soundness** — every A/B scored
> identically, so the proven optimum is always preserved; (2) **model-size reduction** — var/pool counts are
> deterministic. So trust the *reductions* and the *soundness*, and read the `×` figures below as "probably
> faster, magnitude unknown — the large lvl-110 subs-off effect likely exceeds the noise; the smaller/high-level
> ones are within it." A trustworthy magnitude would need a non-throttling host and many averaged runs.

Head-to-head (full EPIC pool, `solveForBenchmark`, 8 workers / seed 1 / det-time 600; identical objective
values on both sides ⇒ output preserved):

| scenario | baseline branches | with §1.3 slice | result |
|---|---|---|---|
| mostmast-110 (control, untouched) | 7583 | 7595 | ~0% ⇒ metric is deterministic ✓ |
| maxdmg-110 | 2692 | **2692** | **identical — no effect** |
| maxdmg-245 | 1690 | **1792** | **+6% — slightly worse** |
| precision-110 (§1.2 tight) | 3074 | 2678 | −13%, but on a 0.4 s solve ⇒ irrelevant |

**Conclusion: the solver is already presolve-optimized; the model-level "speedups" don't move the proof.**
- **§1.3 (throughput AP-slice) — DROPPED.** CP-SAT presolve already derives the element-constraint domain
  (`target = table[index]`, `index ∈ [lo,hi]` ⇒ slice), so the manual slice is redundant: byte-identical
  branch count at 110, +6% at 245. No benefit.
- **§1.6 (throughput/damageSpells memo) — DROPPED.** Build-time only (microseconds); invisible next to a
  multi-second-to-minutes solve. Not measurable.
- **§1.2 (precision `tight=true`) — KEPT as harmless insurance.** −13% branches, but precision already
  proves in <0.5 s so the real-world effect is nil. Worth keeping only for the **precision soundness +
  tight==loose equality tests** it ships with (they lock the precision domains).
- **§1.1 (callback rescore throttle) — KEPT, but as a UX fix not a speedup.** Cuts heavy per-solution
  rescores 12 → 3; each is ~ms so the *solve* gain is negligible, but it reduces solve-thread / render-thread
  contention (less GUI jank during a search).

**Shipped on `perf/solver-speedups`: §1.1 + §1.2 (+ tests) only.** Everything in §1 below is the original
audit's *prediction*; treat §1.3/§1.6 as **measured no-ops** and the rest as **unverified** until a
deterministic (`numBranches`) A/B shows otherwise — wall-clock will lie. The §3 cache idea is untested.

### ✅ Validated win — per-slot DOMINATION pre-filter (measure with deterministic_time, not numBranches)

The one idea that **does** work (and the audit had *rejected* a sloppy version of it — see §4). Within a
slot, item A **soundly dominates** B (B can never beat A in any build, so B is removable) when **all** hold:
- A.characteristics ≥ B on **every** characteristic (monotone objective terms, stat sums, ≥-type sub
  conditions are all then ≥; absent key = 0);
- A introduces no rarity-cap cost B lacked: `(A epic ⇒ B epic) ∧ (A relic ⇒ B relic)`;
- `A.maxShardSlots ≥ B.maxShardSlots` — and the rune/sub model is **colour-agnostic** (sockets are a pure
  count, golden runes form sub patterns; `WakfuBuildSolver.kt` ~962), so count is sufficient for both rune
  capacity and sub-carrier eligibility — **no socket-colour multisets needed**;
- B removable iff it has **≥ k** such dominators (k=2 for RING — two co-equippable distinct — else 1), ties
  broken by id.

**Measured (max-damage fire, full EPIC pool, no runes/subs, deterministic):**

| | lvl-110 | lvl-245 |
|---|---|---|
| pool / vars FULL→FILTERED | 2762→1323 / 4316→2194 | 7884→3116 / 13204→5346 |
| **deterministic_time FULL→FILTERED** | **315.5 → 71.4 (−77%, 4.4×)** | **453.6 → 288.6 (−36%, 1.6×)** |
| proven objective | identical ✓ | identical ✓ |

~50–60% of items are dominated (e.g. 97% of mounts — all carry only `MASTERY_ELEMENTARY`, totally ordered).
**Use `deterministic_time`, NOT `numBranches`:** filtered lvl-245 took 3.2× *more* branches but they're far
cheaper (half the vars ⇒ smaller LP/node), so total work still dropped 36%. numBranches lied here.

### Conditional sublimations — pin, don't gate (so it works with subs ON, the default)

A *conditional* sub makes some stats non-monotone: a build may keep a weaker (e.g. low-secondary) item
*specifically* to satisfy a cap like `SECONDARY_MASTERIES_AT_MOST` (the choosable ones are value 0 — pure
elemental), which domination would remove. The blunt fix is to gate domination off whenever a conditional
choosable sub is in play — but the default has `useSublimations = true` with **12/27** choosable subs
conditional, so that turns it off for the case users actually run.

**The smart fix: pin every stat a *dangerous* (≤ / exact / parity) condition reads to equality** in the
relation (`A == B` on those, `A ≥ B` on the rest). The swap then can't move that stat's build sum, so no sub
can flip — while domination still fires across every stat no condition touches (the bulk of pure-**elemental**
gear). `≥`-type conditions stay satisfied under a `≥` swap on a beneficial choosable sub ⇒ no pin. For the
default sub set the pinned stats are **AP, crit, dodge, range, and the 6 secondary masteries**.

**Measured (max-damage fire, full EPIC pool, subs ON, pinned, deterministic):** ~13–22 % of items still
dominated, and the win *scales with difficulty* —

| | lvl-110 | lvl-245 |
|---|---|---|
| vars FULL→FILTERED | 42360→36143 | 133358→120474 |
| **deterministic_time** | 79.5 → 85.8 (neutral; already fast) | **716.3 → 258.3 (−64 %, 2.8×)** |
| proven objective | identical ✓ | identical ✓ |

So the default (subs ON) gets a **2.8× faster proof at lvl-245** — the slow high-level case that's actually
painful — for free, and an immaterial wobble at the already-fast lvl-110.

**IMPLEMENTED — for ALL THREE modes** (`WakfuBuildSolver.dominationPinnedStats` → `filterDominatedPool`),
applied in `buildModel` only on the production path (`optimize` passes `applyDomination = tuning == null`, so
deterministic tests keep the full pool and are untouched). The original max-damage-only gate was over-conservative:
my claim that most-masteries / precision "penalize overshoot" was **wrong** — both **clamp** at the target
(precision `min(actual, target)`; most-masteries `maxVar(actual, 1, target)` + an overshoot-rewarding tie-break),
so all three objectives are **monotone non-decreasing in every characteristic**. The one subtlety —
most-masteries' objective is the *product* `masteryScore × penaltyMultiplier` — is fine because the optimum
always has `masteryScore ≥ 0` (the empty build already scores objective ≥ 0), and for `masteryScore ≥ 0` the
swap `(m+Δm)(μ+Δμ) ≥ mμ` holds. **most-masteries is the *slowest* mode (~72 s full-pool lvl-245), so this is
where it most helps.** `dominationPinnedStats` returns the pin set, or `null` to skip entirely:
- **null (skip) when**: any forced item / forced rune-by-item; any *forced* conditional sub (unknown effect
  direction); or a condition that compares two build stats / is categorical (`HIGHEST_ELEM_MASTERY_GT_*`,
  `WEAPON_TYPE_EQUIPPED`, `OTHER`). (No longer gated by mode — all three are monotone.)
- **else** the union of stats read by in-play dangerous choosable-sub conditions (empty ⇒ full domination).
- **Soundness locked by 7 tests** (`WakfuBuildSolverTest`): the pure-relation edges (rarity-budget / socket /
  ring-multiplicity), the pinning mechanism, the epic-budget counterexample, and four `@slow` full-pool lvl-110
  guards (filtered==full *proven* optimum) — max-damage subs-off, max-damage **subs ON**, **most-masteries**
  (exercises the bilinear `masteryScore × multiplier` sign), and **precision**. Mirrors the rune-fold lock.
- **⚠️ The `compared` set must include every FEEDER of a constrained stat, not just the stat itself** (A1, 2026-07-06).
  A required RESISTANCE target is enforced on `requiredActualStat`, which folds *specific + generic
  `RESISTANCE_ELEMENTARY` + random-element* resistance lines into the constrained actual. Comparing only the target
  characteristic (e.g. `RESISTANCE_ELEMENTARY_FIRE`) left an item carrying its resistance on the generic/random line
  reading 0-vs-0, so a higher-mastery item dominated it away — pruning the only item that could meet the target
  (wrong "proven optimal" badge, or a false-INFEASIBLE hard leg). Fix: `dominationShape` adds the resistance feeder
  block to `compared` whenever a resistance target exists. **Any future domination edit that touches a stat with
  generic/random feeders (masteries too) must re-check this.** (HP is exempt — one characteristic, no feeder fold.)

### Explored further and NOT pursued — per-probe read-set domination (a sound but not-worth-it refinement)

A 9-agent workflow designed + adversarially verified a more aggressive variant: since `MaxDamageSearch` solves
one element at a time, a *fire* probe's objective never reads water/earth/air masteries (or resistances, floor
off), so domination can ignore those axes (restrict the `>=` test to the per-probe read-set `R(E,S,F,T)` + the
existing pins + the `AT_LEAST` condition stats). It is **sound** (proven; a solve A/B scored identically) and
removes more (subs-on potential 13–22% → **21–32%**). But (a) the verification **caught that an earlier
"directional secondary" idea was UNSOUND** — the cap is *sum ≤ 0*, so 43 real items with *negative* secondary
mastery (e.g. L'Ami Léhunui BERSERK −305/BACK +244) can be kept to hold the cap; and (b) a solve A/B **regressed
at lvl-245** (324 → 523 det-time — smaller model, slower proof) while helping lvl-110, i.e. the gain is within
the measurement noise documented above. Verdict: **not worth the soundness-critical read-set audit** for an
unmeasurable/possibly-negative gain. The runner-up (a 2-regime Neutrality-on/off split) is parked for the same
reason. Reproducing the read-set relation: `geStats` (elemental + DI + MP + BLOCK + AT_LEAST stats) use `>=`,
the conditioned pins use `==`, and sibling-element + resistance/HP (floor off) axes are *ignored*.

## The one hard rule

A speedup is admissible **only** if it provably keeps BOTH:
1. **Output quality** — the same optimal build is still found; no build can be lost or changed.
2. **Provability** — the solve still reaches/report `OPTIMAL` (gap 0), and any outer enumeration
   (`MaxDamageSearch`) stays exhaustive over its candidate space.

**Admissible levers** (all used or proposed below): tighter variable *domains* over the same feasible
region; redundant/valid cuts, symmetry-breaking, or implied bounds that remove *no* optimum; a
smaller-but-equivalent model; better presolve/linearization/parameter config that does **not** cap the
proof; CP-SAT solution hints / warm starts (cannot change the optimum); right-sized parallelism;
eliminating redundant re-solves; caching build-invariant precomputation; reducing per-probe /
per-callback overhead; provably-sound *dominance* pruning in the outer enumeration.

**Never admissible:** lowering the time / deterministic-time budget; feasible-only or early-stop
shortcuts; heuristic pruning that *could* skip the optimum; widening the choosable sublimation set or
any data change that "invents" damage; anything that changes the reported build.

> **Anchor caveat.** Line numbers below are as of `main` @ **1.8.0** (`WakfuBuildSolver.kt` ≈ 3182
> lines). They drift across releases — prefer the **symbol name** when locating code. The data version
> at audit time was `WakfuData.VERSION = "1.92.1.58"` (`common-lib/.../WakfuData.kt:16`).

---

## 0. Where time goes today (baseline per mode)

| Mode | Current cost | Bound by |
|---|---|---|
| **most-masteries** (default) | prefiltered proves OPTIMAL ~3.8 s; full late-game pool ~72 s (lvl-245). Single-element default runs the **full, unprefiltered** pool and often misses the "proven" badge inside the GUI's ~20 s default. | the bilinear objective product `masteryScore × penaltyMultiplier` + power-6 penalty bucketing — *not* the linear stat chain. |
| **precision** | full cap/min/average/overflow objective runs over **loose 1e7–1e13 declared domains**; the reachable domains are already computed at every leaf but **discarded** (`tight=false`). The random-element redesign already took precision 204 s FEASIBLE → 1.6 s OPTIMAL once the model tightened *structurally*. | loose variable domains starving presolve + the LP relaxation. |
| **max-damage single** | provable today (lvl-110 ~50 s, lvl-245 ~3.5 min) via per-pool reachable domains + the boolean rune fold. | closing the **dual bound**, not finding an incumbent. The central bilinear term `throughput × perHitScaled` dominates. |
| **max-damage bi** | provable per-element **enumeration** (in-model bilinear split is unprovable); ≤4 element solves run in parallel via `runProbeBatch`; each full solve is the dominant cost. | the per-element solves; throughput-table recompute repeats once per candidate element. |
| **max-damage boss** | confirmed-debuff classes (Sram/Sadida) run `activePhases=2` with a static 50/50 split → phase 1 (the *provable* enumeration) gets only half the wall-clock. At the 20 s GUI default, phase 1 gets 10 s. High-level boss/multi-element optima are structurally unprovable in minutes (inherent scale). | wall-clock split + scale. |
| **shared** | first search per process pays JIT of model-construction/scorer paths (native dylib load is already covered by the trivial warm-up); the CLI under-uses one core; intermediate solution callbacks steal native-solve-thread cycles in **every** mode. | first-search JIT + callback overhead. |

---

## 1. Top picks (ranked by impact ÷ effort)

### 1.1 — Throttle the per-solution rescore in the solver callback · **all modes** · medium impact · **small effort**
Every improving solution currently runs a full `solutionToBuild` + `scoreFor` (a knapsack-rotation DP
in max-damage) **on the native solve thread**, then `trySend`s a progress snapshot. Coalesce these
behind a single wall-clock timestamp check (~150–250 ms) inside `onSolutionCallback` so the solver
spends its cycles on search/proof instead of progress snapshots.
- **Code:** `WakfuBuildSolver.kt` — `onSolutionCallback` (≈1413), intermediate `trySend` (≈1426),
  unconditional final emit `scope.send` (≈1442).
- **Why optimum-safe:** the callback feeds neither branch-and-bound nor the optimality proof — it only
  reads var values, checks cancellation, and *best-effort* `trySend`s a snapshot. The reported build is
  the **final** one, recomputed unconditionally after `solve()` returns and delivered via a guaranteed
  *suspending* `send`, so it is always the last flow element; throttling intermediates cannot drop or
  reorder it. CLI (`.buffer(CONFLATED).lastOrNull`) and GUI (`.conflate().collect`) already keep only
  the last element; tests assert `.toList().maxBy { score }`, not emission counts.
- **Guards:** keep the `!scope.isActive → stopSearch()` cancellation check **before** the throttle so
  cancel latency is unchanged. Do **not** substitute `solver.objectiveValue()` for the displayed
  intermediate (it is the proxy objective, not the lockstep score).

### 1.2 — Precision: build the `StatBuilder` with `tight = true` · **precision** · medium impact · **small effort**
`buildPrecisionObjective` constructs its `StatBuilder` with the default `tight = false`
(`WakfuBuildSolver.kt:1297`), so every stat var is declared on the 10 M guard domain even though the
reachable range is already computed at every leaf. Pass `tight = true` exactly as max-damage does
(`:459`). Per-element stat domains collapse from 10,000,000 to a few thousand across the
cap/min/average/overflow chain — the **same lever** that flipped max-damage from FEASIBLE to OPTIMAL.
- **Code:** `WakfuBuildSolver.kt:1289` (`buildPrecisionObjective`), `:1297` (the `StatBuilder` ctor call);
  enabled by `DomainTracker.decl` (declares `reach.coerceIn(guard)`); `StatBuilder` `tight` default at `:1618`.
- **Why optimum-safe:** `decl` declares `reach.coerceIn(guard)`, so the tight domain is always a
  **subset** of today's domain, never wider; every `reach` is sound interval arithmetic
  (`reachableSumDomain` bounds each mutually-exclusive `ItemType` group by best/worst coeff — it even
  permits 2H+1H+offhand together, a *superset* of the validity region) so it contains every attainable
  value. Domains are not constraints, so a feasible-superset domain can never conflict. Solver params
  are unchanged (`maxPresolveIterations` stays 1), so the proof can only tighten, never cap.
- **Guards (REQUIRED):** add a **precision-mode reachable-bound soundness lock** mirroring
  `maxDamageVarBoundsForTest` (today it covers only the max-damage chain) **plus** a `tight==loose`
  optimum-equality test. Benchmark full-pool OPTIMAL-time before/after — the impact estimate is
  extrapolated from max-damage, and precision does **not** get `presolve=3` / `linearizationLevel=2`.

### 1.3 — Max-damage: slice the throughput `tElement` domain to the build's reachable AP window · **single / bi / boss** · medium impact · **small effort**
In `StatBuilder.tElement` the result var is declared on the full `table.min()..table.max()`. Instead
declare it on the reachable **slice** `table[loI..hiI]`, where `loI/hiI` come from `tracker.of(apVar)`
(the build's real achievable AP, clamped to `[0, MAX_ROTATION_AP=20]`). At lvl-110 the true AP ceiling
is ~12–16, so the throughput operand's declared `hi` drops ~19–29 %; since throughput feeds
`raw = tMul(throughput, perHitScaled)`, the product envelope (`mulRange`) shrinks proportionally,
tightening the LP relaxation of the central bilinear term **and the dual bound**.
- **Code:** `WakfuBuildSolver.kt:1767` (`tElement`); AP source `apVar = tClamp(actualStat(AP), 0, 20)` (≈2236);
  `MAX_ROTATION_AP` at `:81`.
- **Why optimum-safe:** the recorded reach `table[loI..hiI]` is the *exact* value-set of `table[index]`
  over `apVar`'s tracked feasible window; `apVar` is functionally pinned by equality to the real
  achievable clamped AP, so the solver cannot push the index past the gear-bounded ceiling even in loose
  mode. No feasible `(index, target)` pair is dropped → no build removed. When AP reaches 20 the slice
  equals the full table (exact no-op).
- **Guards:** clamp slice indices with `coerceIn(0, table.size-1)`; the existing 10-shape
  `maxDamageVarBoundsForTest` lock + the lvl-110/245 free-solve proofs already cover this var.

### 1.4 — Boss-debuff: give phase 1 the full budget; run the heuristic AP-window phase on leftover only · **boss (Sram/Sadida)** · low/medium impact · **small effort**
For confirmed-debuff classes, the wall-clock is split `searchDuration / activePhases` so phase 1 (the
**provable** per-element enumeration) gets only 50 %. Restructure so phase 1 runs against the *full*
`searchDuration` and proves if it can, then phase 2 runs only with `remaining = searchDuration − elapsed`
(floored to the 1 s minimum). At the 20 s GUI default this doubles the provable phase's budget
(10 s → 20 s) — meaningful in the borderline band where the proof completes within full duration but
not within half.
- **Code:** `MaxDamageSearch.kt:118-119` (`activePhases` / `phaseBudget`), the sequential phase producer
  (≈151-214), `proven` at `:230`.
- **Why optimum-safe:** phase 1 *is* the provable enumeration; raising its budget is the admissible
  inverse of lowering it — feasible region, objective and candidate-element enumeration untouched, so
  `phase1Optimal` can only flip true on more runs. Phase 2 only calls `consider()` and upgrades on a
  **strict** improvement, reporting any such build as `improvedByDebuff ⇒ isOptimal=false /
  maxDamageHeuristicPhases=true`; dropping it at high level always swaps **toward** the proven build and
  makes `proven` *more* likely.
- **Caveat (honest behavioral change):** a non-proven, honestly-labeled heuristic damage nudge may
  disappear for some high-level debuff-class cases — flag this to the user, or keep a small phase-2 floor.
  Tests use deterministic-time (they ignore the split), so they are unaffected. Scope: Sram/Sadida only,
  production wall-clock path only.

### 1.5 — Most-masteries: warm-start with a greedy admissible incumbent (`addHint`) · **most-masteries** · medium impact · medium effort
Before solving, `addHint` the equipment booleans of a greedy incumbent that respects rarity/weapon/ring
caps **and** trades off the soft AP/MP/range penalties. A strong early incumbent tightens the
incumbent-vs-bound gap so the proof can close sooner on the ~72 s full-pool single-element default.
- **Code:** `WakfuBuildSolver.kt` — after `model.maximize(objective)` (≈460) or in the solver setup
  (`executeSolverAndEmitResults`, ≈1359); `equipVars` from `createEquipmentVariables` (`:656`).
- **Why optimum-safe:** `addHint` is a pure **primal warm start** — by CP-SAT semantics it adds/removes/
  reweights no feasible solution; partial hints are completed and infeasible ones silently repaired;
  model + objective bytes are unchanged; `isOptimal` is keyed off the real status.
- **Guards:** the only risk is **determinism** (it may change *which* tied-optimum build returns, or
  shift proof completion under fixed det-time) → gate the hint **OFF** in the `SolverTuning`/test path,
  and OFF when forced/excluded items or forced runes already shrink the pool. A max-mastery-only greedy
  is likely too weak — it **must** weigh the soft penalties. **Measure** time-to-OPTIMAL before trusting
  the medium-impact estimate (it must beat CP-SAT's own incumbents to be worth it).

### 1.6 — Memoize `damageSpells(clazz)` and the build-independent throughput table per `(clazz, element)` · **single / bi / boss** · low impact · **small effort**
`perTurnDamageScore` recomputes `SpellCatalog.damageSpells(clazz).filter { element }` +
`baseThroughputTable(spells, MAX_ROTATION_AP)` on every model build. Both are pure functions of
`(clazz, element)`. Memoize them — small for single-element, but removes the recompute across the
**boss/bi-element probe batches** that rebuild the model N times in parallel.
- **Code:** `SpellCatalog.kt:76` (`damageSpells`), `SpellRotation.kt:458` (`baseThroughputTable`);
  consumer `WakfuBuildSolver.kt` `perTurnDamageScore` (≈2232, calls at ≈2240/2245).
- **Why optimum-safe:** inputs are immutable `by lazy` globals; the cached array equals the recompute
  element-for-element, so the model built from it is byte-identical (same feasible region, objective,
  `OPTIMAL` status, enumeration exhaustiveness). The single consumer allocates a fresh `clampedTable` and
  never mutates the cached array.
- **Guards:** the **table** memo MUST include `maxAp` in its key (`baseThroughputTable` is also called
  from `SpellRotation.kt` with other budgets) or be scoped to the fixed `MAX_ROTATION_AP` call site; the
  `damageSpells` memo is `maxAp`-independent. Make both **thread-safe** (`ConcurrentHashMap` /
  `computeIfAbsent`) — probes run in parallel.

---

## 2. Secondary / cleanup items

- **Most-masteries: declare the objective product on its reachable bound** (`shared` lever, low/medium).
  Replace the loose `±MASTERY_SCORE_ABS_MAX` guard on `masteryScore` and the `safeMultiply(...)≈1e14`
  bound on the objective product with reachable bounds from `StatBuilder.tracker`. Only the single genuine
  product `objective = coreScore × multiplier` benefits (the linear `masteryScore`'s own box is
  LP-redundant), tightening that one McCormick envelope. **Same soundness** as 1.2/1.3 (a sound
  over-estimate of the reachable range removes no feasible assignment). **Requires** a most-masteries
  reachable-bound soundness test. ⚠️ The naïve `aLo*weight..aHi*weight` form is **UNSOUND** —
  `scaledWeight` can be negative (no non-negativity clamp on CLI target weights), which inverts the
  domain; any implementation must sort the two products via `min/max` and clamp the capped `lo` to
  `min(., expected)`, exactly as the existing sign-agnostic `[-span,span]` / `[-span,expected]` code does.
- **Make `warmUp()` structurally representative** (`shared`, low). `warmUp()` solves a throwaway 2-bool
  model — it pays native load/JNI/worker spin-up but never JITs `buildModel` (StatBuilder, rune/sub
  models, McCormick/linearization) or the scorers. Replace with a tiny but *structurally real* solve (a
  few synthetic equipments across a couple `ItemType`s through `buildModel`, ideally one max-damage
  objective pass), bounded by a short `maxTimeInSeconds` + the existing 2-worker cap, behind the loading
  screen. **Optimum-safe:** `WakfuBuildSolver` is an `object` singleton but holds **no** solve-written
  mutable state (all caches are per-call `StatBuilder` fields bound to one `CpModel`), so a synthetic
  warm-up cannot pollute a later real search. **Guards:** use synthetic params, keep it bounded so the
  loading screen doesn't lengthen, and add a test that the warm-up solve **actually completes** (a
  swallowed failure yields zero benefit). Code: `WakfuBuildSolver.kt:164`.
- **Let the CLI use all cores** (`shared`, low — marginal, sometimes negative under contention).
  `numSearchWorkers` is `availableProcessors() − 1` to keep the GUI render thread responsive; the CLI has
  no UI thread. The `WakfuBestBuildParams.solverWorkers` field already exists — thread the full count from
  the CLI while the GUI keeps `cores−1`. **Optimum-safe:** worker count only sizes CP-SAT's cooperative
  portfolio under the wall-clock bound; it removes no feasible solution and caps no dual bound.
  ⚠️ **HAZARD:** `solverWorkers` is *also* live oversubscription control — `MaxDamageSearch.runProbeBatch`
  (≈278, override at ≈302) re-derives per-probe workers so the test invariant
  `concurrency × workersPerProbe ≤ host` holds. Set the field only at CLI param construction and do
  **not** remove the per-probe override, or the boss/bi-element batch re-oversubscribes the CPU.
  Code: `WakfuBuildSolver.kt:1397`; plumb from `autobuilder/Main.kt`.
- **Collapse the ~16 per-`ItemType` `allEquips.filter` passes** in `addBuildValidityConstraints` into one
  order-preserving `groupBy { itemType }` + a rarity `partition` (`shared`, microsecond-scale — do only as
  a free cleanup when already touching this code). **Optimum-safe:** same item membership per group in
  source order ⇒ byte-identical `addLessOrEqual` constraints. Keep `groupBy`/`partition` (not
  `associateBy`/set regrouping) so equivalence is trivially reviewable. Code: `WakfuBuildSolver.kt:998`.

---

## 3. Cross-query result reuse ("a cache")

The solver is **deterministic**: for a fixed model, a **proven-OPTIMAL** result is fully determined by
its inputs. That makes caching *trivially* optimum-safe for exact matches — a cache hit returns the
**same proven build** the solver would recompute, at zero solve cost. Three sound mechanisms, in
descending hit-rate value:

### 3.1 — Exact-match result cache (in-process, optionally on-disk)
A `Map<QueryKey, SolverResult>` consulted before solving; on a miss, solve and store **only if
`isOptimal == true`**. Helps repeated identical searches in a session (re-runs, the default request) and,
if persisted, across launches.
- **Cache only proven results.** A FEASIBLE (timed-out, non-proven) result is run-dependent and a longer
  search would beat it — caching it would freeze in a sub-optimal build. Store iff the final emit is
  `isOptimal`.
- **The `QueryKey` must canonically cover every input that determines the optimum** (and *exclude* the
  ones that affect only time/feasibility). From `WakfuBestBuildParams`:
  - **Include:** `character` (clazz, level, minLevel, skills config), normalized `targetStats`,
    `scoreComputationMode`, `maxRarity`, `excludedRarities`, sorted `forcedItems` / `excludedItems`,
    `useRunes` / `forcedRunes` / `forcedRunesByItem`, `useSublimations` / `forcedSublimations`,
    `forcedPassives`, and (max-damage) the full `damageScenario` (element, boss resistances, role,
    crit-cap, range band, …).
  - **Exclude:** `searchDuration`, `stopWhenBuildMatch`, `solverWorkers`, `maxDamageApTarget` (an internal
    probe param below the user-query cache boundary). These change time/parallelism/feasibility, not the
    proven optimum.
  - **Plus two version stamps:** `WakfuData.VERSION` (data bump ⇒ different items/values) **and a separate
    `ENGINE_MODEL_VERSION`** you bump whenever the CP-SAT model, any objective, or any scorer changes.
    Without the engine stamp, a stale cache would serve a build that is no longer optimal under the new
    model — the single most important correctness guard for a persisted cache.
  - **Canonicalize:** sort list/map inputs, use `TargetStats`' existing normalization (weights + the
    `MASTERY_ELEMENTARY`/`RESISTANCE_ELEMENTARY` expansion) so equivalent queries hash equal.
- **Tie note:** when several builds tie at the optimum, CP-SAT may return *a* different (equally optimal)
  one across runs. A cache hit returning the stored optimum is still optimal — correctness holds. If
  bit-for-bit reproducibility matters, tie-break deterministically before storing.
- **Scope:** in-process is zero-risk and cheap (do first). On-disk (e.g. under
  `~/Library/Caches/WakfuAutobuilder/`, keyed by the two version stamps) survives restarts.

### 3.2 — Ship precomputed optima for common queries
Precompute the proven-optimal builds for the **most-requested** queries (the GUI default request,
popular class/level/element combos) at release time and bake them in as a resource keyed by the same
`QueryKey`. Those queries then return **instantly and proven** on first launch — directly addresses the
GUI's "misses the proven badge inside 20 s" case for the common path. Regenerate when either version
stamp changes (wire it into `scripts/update-game-data.sh`). This is the most user-visible form of the
"prefilled results everyone reuses" idea.

### 3.3 — Neighbor builds as warm-start hints (the *only* sound "fuzzy" reuse)
The query space is effectively continuous (arbitrary weights, forced/excluded lists), so exact-match
hit-rate is mostly the default + repeats. You **cannot** return a *similar* query's build as this query's
optimum — a neighbor's optimum is not this query's optimum. But a neighbor's solved build is an excellent
**`addHint` seed**: look up the nearest cached build, hint it, and **still solve & prove fresh**. This
turns "partial reuse" into a pure speedup that preserves the proof — and it is the same mechanism as
§1.5. Determinism guards from §1.5 apply (gate off in the test/`SolverTuning` path).

> **Unsound caching traps — do NOT do these:** returning a similar query's build as if it were this
> query's optimum; caching FEASIBLE/timed-out results; omitting the `ENGINE_MODEL_VERSION` stamp.

---

## 4. Rejected as unsound — do **not** re-attempt

Each *looked* like a speedup but provably drops or changes the optimal build:

- **Prune objective-irrelevant equipment booleans** (per-slot single representative): UNSOUND — the model
  reads more characteristics than the prefilter set (negative back/crit/berserk masteries in
  `finalMasteryScore`; precision `negativeTargetPenalty`; and **decisively** sublimation
  `reifyCondition`/conversion read whole-build DODGE/BLOCK/RANGE/AP/CRIT/secondary masteries from **any**
  equipped item, including zero-socket ones), so pruning can flip an active sublimation and delete the
  proven optimum non-monotonically.
- **Symmetry-break duplicate rings via a lexicographic ordering cut:** UNSOUND — two different-named rings
  carry different stat vectors and can be equipped together (RING cap = 2), so forcing `ring_a < ring_b`
  forbids the optimal unordered pairing. (The "verify symmetry is on" variant is a no-op — `symmetry_level`
  is on by default and never disabled.)
- **Dominance-prune duplicate-stat items within a slot:** UNSOUND — ignores RING cap = 2 (different rings
  co-equippable), the global ≤1 EPIC / ≤1 RELIC cross-slot resource caps, rune socket count, and
  sub-carrier eligibility; `valueFor`-only dominance drops feasible optima. Also aimed at the
  already-fast (≤3.5 s) single-element path.
- **Implied upper-bound cuts on AP/MP/range from out-of-combat caps:** UNSOUND — caps are on the *pre-sub*
  var; start-of-combat sublimations push combat AP above 16, and most-masteries' overshoot tie-breaker
  *rewards* exceeding the target, so `min(16, …)` deletes feasible builds. There is also **no range cap**
  in the model, so any range cut is invented. (The sound residue is just the existing reachable-domain
  lever = §1.2/§2.)
- **Warm-start each per-element/AP probe from the previous proven probe's solution:** UNSOUND in the
  targeted-timeout regime (a wall-clock-bounded solve returns the best **feasible** incumbent, which a hint
  changes) **and** mechanically wrong — `runProbeBatch` runs all probes via `awaitAll` (no wave boundary to
  harvest from); serializing to seed would surrender the parallelism that is the current speedup.
- **Build the element-independent model once and clone per probe (swap only the objective):** optimum-safe
  but **not a bottleneck** — `buildModel` runs *inside* each probe's own coroutine fanned across cores, so
  it removes ~one model-build (ms-to-low-seconds), dwarfed by the per-element solve; and replaying the
  `DomainTracker` reachable-range state + cross-builder index sharing is a fragile provability footgun.
- **Skip a candidate-element probe whose ceiling is "provably dominated":** UNSOUND — the upper bound is
  built on the CP-SAT *proxy* objective, but the winner is re-scored by `sequencedScore` (real per-turn
  damage / build-dependent penalty, maxed over all playable elements), a **different scale**; the dominance
  test cannot bound the ranking the incumbent is actually selected by, so it can drop the element
  containing the reported optimum.
- **Skip the overshoot tie-breaker when `sum(weights)==0`:** non-bottleneck (fires only when a required
  stat is requested with target/weight 0 — rare-to-never); and the guard would have to be `all { weight==0 }`,
  not `sum==0`.
- **Reuse the cached `actualStat` var in `negativeTargetPenalty`:** no-op — the dedup already exists (both
  the cap and penalty paths share `actualCache.getOrPut(char)`).

---

## 5. Suggested order + testing requirements

1. **§1.1 callback throttle** + **§1.3 AP-slice** + **§1.6 memo** — the optimum-safe-by-construction set;
   land together behind one before/after benchmark (lvl-110 + lvl-245, single + boss).
2. **§1.2 precision `tight=true`** — second change, ships with its **precision soundness-lock test** +
   `tight==loose` equality test + a full-pool OPTIMAL-time benchmark.
3. **§1.4 phase-1-full-budget** — boss-debuff classes; ships with the user-facing note about the heuristic
   nudge possibly disappearing at high level.
4. **§1.5 most-masteries warm-start** — only if the greedy (penalty-aware) provably beats CP-SAT's own
   incumbents in a measured A/B; gate off in tests.
5. **§3.1 in-process exact-match cache** → **§3.2 shipped common-query optima** → **§3.3 neighbor hints**.
6. Cleanup items in §2 opportunistically.

**Every domain-tightening change (§1.2, §1.3, §2 product bound) MUST ship with a reachable-bound
soundness lock** for its mode — today's `maxDamageVarBoundsForTest` covers **only** the max-damage chain.
**Every engine-affecting change MUST bump `ENGINE_MODEL_VERSION`** once a cache (§3) exists. Engine tests
must keep using `SolverTuning` (fixed workers/seed/deterministic-time) or they flake on CI.

---

## 6. C4 solver-parameter A/B — harness (2026-07-07)

The `@Tag("manual")` `manual max-damage solver-parameter matrix` test (`WakfuBuildSolverTest`) is the C4
harness. It builds the **identical production model** and varies ONLY CP-SAT solver params (soundness-safe by
construction — CP-SAT proves the same optimum regardless), reporting `deterministicTime`-to-OPTIMAL. As of
2026-07-07 it also screens **`sharedTree4`** (`sharedTreeNumWorkers=4`) and can screen the **constrained
hard-leg shape** (`--pa 11 --pm 4 --po 4`, `hardConstraints=true` — the bilinear-dual-gap shape C6 targets)
via `WAKFU_MAX_DAMAGE_CONSTRAINED=1`. `useObjectiveShavingSearch`, `symmetryLevel`, `cpModelProbingLevel`,
`searchBranching`, and the extra/ignore-subsolver lists are all wired on `timedMaxDamageProfileForTest`.

Run (on a **thermally-stable** machine — this dev host's `deterministic_time` swings ~2.3× back-to-back
under throttling, §0, so treat sub-2× deltas as noise and average many `WAKFU_MAX_DAMAGE_REPEATS`):

```sh
# Free solve, level 110, several repeats:
WAKFU_MAX_DAMAGE_SOLVER_PARAMS=1 WAKFU_MAX_DAMAGE_EXPERIMENT_LEVEL=110 WAKFU_MAX_DAMAGE_REPEATS=5 \
  ./gradlew :autobuilder:test --tests '*WakfuBuildSolverTest' -Dgroups=manual
# Constrained hard-leg shape (the C6-gating screen — the verdict here is BINARY: does any knob flip its
# status to OPTIMAL within budget? status is noise-free even when timing isn't):
WAKFU_MAX_DAMAGE_SOLVER_PARAMS=1 WAKFU_MAX_DAMAGE_CONSTRAINED=1 WAKFU_MAX_DAMAGE_EXPERIMENT_LEVEL=110 \
  WAKFU_MAX_DAMAGE_DETTIME=600 ./gradlew :autobuilder:test --tests '*WakfuBuildSolverTest' -Dgroups=manual
```

**Decision rule:** ship a knob only if it wins on ≥ 2 fixtures and regresses none, gated to the max-damage
production path (`deterministicMaxDamageSolver`). If a knob flips the constrained shape to OPTIMAL,
C6 (the Lagrangian joint bound) becomes unnecessary.

### First run — constrained shape `--pa11 --pm4 --po4`, level 110, 8 workers (2026-07-07, dev host)

Wall cap 200 s; `objShaving` was cut short by an over-tight det limit (250) so its result is **inconclusive, not
a loss** — the det limit stopped it at 66 s while the others ran the full 200 s wall. The **STATUS is noise-free**
even though timing on this host isn't. The shape's optimum is **1221445** (every variant that reached it agrees).

| Variant | Status | Incumbent | bestBound | Note |
|---|---|---|---|---|
| baseline | FEASIBLE | 1221445 | 1514630 | dual gap open (the shape C6 targets) |
| **`probing3` (cpModelProbingLevel=3)** | **OPTIMAL** ✅ | 1221445 | 1221445 | **PROVED — closed the gap** (~194 s) |
| linear0 / linear1 | FEASIBLE | 1221445 | ~1.43–1.46M | — |
| presolve6 | FEASIBLE | 1221445 | 1478340 | — |
| symmetry3 | FEASIBLE | 1221445 | 1449690 | — |
| portfolio / portfolioQuickRestart | FEASIBLE | 1221445 | ~1.40M | — |
| sharedTree4 | FEASIBLE | 1217625 | 1524180 | 1.36M branches, LP-heavy; no prove |
| objShaving | FEASIBLE | 1217625 | 2053330 | **det-limited to 66 s — INCONCLUSIVE, re-run with a wall-only budget** |

> ⚠️ **This 8-worker run's headline was WRONG — see the 1-worker sweep below, which SUPERSEDES it.** The
> `probing3=OPTIMAL` here was **worker-race variance**, not a real effect. It is kept only as the cautionary
> example of why an 8-worker single run cannot be trusted (invariant 5 exists for exactly this reason).

### 1-worker det-time sweep — the reproducible result (2026-07-07) — SUPERSEDES the 8-worker run above

Same shape, **1 worker + fixed seed** (so the solve is fully deterministic and `bound(det)` is an exact,
reproducible curve — invariant 5's gold standard), **det-time is the only budget** (wall cap 4000 s, never binds),
swept over `det ∈ {50,100,200,400,800}`. The metric is the **dual bound** (an upper bound on the max objective;
lower = tighter, converging toward the true optimum **1221445**).

| det | obj (all 3) | dual bound — baseline | probing3 | objShaving |
|---|---|---|---|---|
| 50  | 1198525 | 1972010 | 1972010 | 1972010 |
| 100 | 1217625 | 1713970 | 1713970 | 1713970 |
| 200 | 1217625 | 1583390 | 1583390 | 1583390 |
| 400 | **1221445** | 1524180 | 1524180 | 1524180 |
| 800 | 1221445 | 1486935 | 1486935 | 1486935 |

**`baseline` = `probing3` = `objShaving`, byte-for-byte, at EVERY checkpoint** — at 1 worker the knobs have zero
effect (they don't touch the single default subsolver). But 1 worker is NOT production; the next run tests the
8-worker portfolio the knobs actually change.

### 8-worker × 3-repeat proof-rate — the production configuration (2026-07-07) — the DECISIVE run

Same shape, **8 workers** (production), **det budget 1600** (wall cap 5000, never binds), **3 repeats** per
variant (multi-worker det-time is genuinely noisy run-to-run, so we need the distribution, not one sample):

| variant | rep0 | rep1 | rep2 | proof rate | det-to-prove (mean) |
|---|---|---|---|---|---|
| **`probing3`** | OPT @635 | OPT @794 | OPT @1188 | **3/3** | 635–1188 (**~872**) |
| `baseline` | OPT @1406 | OPT @2382 | OPT @2566 | 3/3 | 1406–2566 (~2118) |
| `objShaving` | FEAS (2.03M) | FEAS (2.03M) | FEAS (2.03M) | **0/3** | never — regresses |

**`probing3`'s det band (max 1188) never overlaps `baseline`'s (min 1406): a consistent ~2.4× proof speedup at 8
workers, 3/3.** So `probing3` IS a real win — but it is a **portfolio effect** (it changes what the 8 racing
subsolvers do), which is exactly why it was invisible at 1 worker. `objShaving` consistently regresses (0/3, bound
stuck ~2.03M). Note the absolute det-times are noisy (baseline spans 1.8×) and the 1600 det budget is **soft** at 8
workers (baseline overshoots to 2566), so only the *ordering* is trustworthy — but the ordering is rock-stable.

### 8-worker free-solve no-regression check (2026-07-07) — the plot twist

Same 8-worker/det-1600/3-repeat protocol on the **free** lvl-110 solve (no AP/MP/range targets), baseline vs
probing3, to check the decision rule's "regresses none":

| variant | rep0 | rep1 | rep2 | mean | range |
|---|---|---|---|---|---|
| `probing3` | 210.9 | 602.8 | **2043.6** | ~952 | 211–2044 (**~10× spread**) |
| `baseline` | 1419.1 | 821.7 | 626.8 | ~956 | 627–1419 (~2.3×) |

On the free solve `probing3` and `baseline` have **essentially identical means (~952 vs ~956)** — `probing3` just
has **much higher variance** (rep0 looked like a 6.7× win; rep2 is a *worse-than-any-baseline* 2043 spike). So on
the free solve: **no benefit, and a worse tail.**

### 8-worker INTERLEAVE run — the closer (2026-07-07): a fifth regime settles it

To escape the multi-worker variance, run 5 turned on CP-SAT's **deterministic interleaved parallel search**
(`interleaveSearch=true`) — meant to make an 8-worker solve reproducible. It did NOT fully (the det-to-prove is
quantized to a few discrete values but *which* one a run lands on still varies), but the A/B result is decisive:

| variant (8w, interleave, det) | rep0 | rep1 | rep2 | min | verdict |
|---|---|---|---|---|---|
| `baseline` | **741.86** | 1257.08 | 1257.08 | **741.86** | hits the fast value |
| `probing3` | 1257.08 | 1257.08 | 1456.28 | 1257.08 | never fast; hit the *worst* value |

In interleave mode `probing3` is **same-or-WORSE** than baseline — the exact opposite of the async run. Both draw
from the same discrete outcome set {741.86, 1257.08, …}; `baseline` is the one that caught the fast 741.86.

### FINAL VERDICT (five runs) — settled, do not revisit without new evidence

**`probing3` / `objShaving` provide NO robust, reproducible benefit — do NOT ship any solver-parameter knob for
max-damage.** The evidence, ranked by trustworthiness:
1. **The only FULLY reproducible measurement — 1 worker — is byte-identical `baseline` = `probing3`.** Zero
   algorithmic effect. This is ground truth.
2. **The multi-worker "wins" are mutually contradictory across parallel modes:** `probing3` looked ~2.4× *faster*
   in async, is *identical* at 1 worker, and is *same-or-worse* under interleave. A real improvement helps
   consistently; a sign-flipping effect is a **worker-race artifact**, not a property of the change.
3. **The variance is irreducible (~10× run-to-run) and even interleave doesn't remove it**, so no practical number
   of multi-worker repeats yields a trustworthy proof-time A/B on this problem.
4. `objShaving` regresses in every regime; `baseline` proves every fixture fine; **C6 is not needed** (the shape is
   backstopped by the AP-cell certificate + "within X%" badge).

**Corollary for future work:** on this codebase, a solver-knob **proof-time** A/B is only reliable at **1 worker**
(fixed seed, det budget). If a knob shows nothing there, multi-worker samples cannot rescue it — they only measure
noise. Don't chase multi-worker proof-time knobs; put effort into **bounds/algorithm** changes (which move the
1-worker curve) instead.

**Methodology lessons (this whole exercise is the case study — four runs, three wrong intermediate conclusions):**
- Run 1 (8-worker, **wall** cap, 1 sample): "probing3 proves, C6 obviated" — WRONG (wall noise + read speed as
  prove-vs-not).
- Run 2 (1-worker det-sweep): "no knob does anything" — right for the single subsolver, but I **over-generalized**.
- Run 3 (8-worker det, 3 repeats, constrained): "probing3 is a real 2.4× portfolio win, ship it" — over-claimed
  from 3 samples.
- Run 4 (8-worker det, 3 repeats, **free**): the ~10× variance surfaced → **the constrained win is 3-sample-thin
  and probing3 worsens the free-solve tail → don't ship.**
- Run 5 (8-worker **interleave**, 3 repeats): tried to buy determinism; it didn't (still quantized-noisy), but
  `probing3` came back **same-or-worse** than baseline → the async "win" was a worker-race artifact. **Settled.**
- The durable takeaways: (a) test at BOTH worker counts (1-worker null ≠ multi-worker null); (b) multi-worker
  CP-SAT proof-time is **variance-dominated and mode-dependent** — small-N A/B is unreliable and even
  `interleaveSearch` doesn't make it reproducible, so a knob that's null at 1 worker can't be rescued; (c) use a
  det budget (not wall), but the det limit is *soft* at >1 worker (overshoots); (d) chase bound/algorithm changes
  (which move the reproducible 1-worker curve), not multi-worker proof-time knobs.

---

## 7. Deterministic-interleave protocol + experiment log (2026-07-07)

Refines §6's "even `interleaveSearch` doesn't make it reproducible" — that finding was about **8-worker**
interleave. The stable protocol is **1 worker + `interleaveSearch = true`**:

- **1-worker + interleave is byte-identical reproducible** (verified: 3 repeats, exact `det` checkpoints —
  `100.0`, not `100.001`). One thread has no cross-worker race on the `maxDeterministicTime` check, so no
  stop-jitter. And unlike **bare** 1-worker (which runs only the lone default subsolver), interleave runs the
  **full subsolver portfolio** round-robin on the single thread — deterministic AND representative of the real
  algorithm mix.
- **8-worker interleave still jitters ~1–2 %** at a fixed det budget: workers race the limit check, so the solve
  stops at a slightly different `det` per run (`200.333` vs `203.399`) and the bound differs. Some
  (variant, checkpoint) pairs reproduce exactly, others don't — treat as noisy.
- **Canonical A/B (the only accepted evidence going forward):** 1 worker, `interleaveSearch=true`, `randomSeed=1`,
  a det sweep; compare **trajectory-vs-trajectory** (bound at each checkpoint); confirm determinism once (3 repeats
  identical). Harness: `WAKFU_MAX_DAMAGE_INTERLEAVE=1 WAKFU_MAX_DAMAGE_EXPERIMENT_WORKERS=1` + the det sweep /
  variant screen. This is the premise of `docs/SOLVER_BOTTLENECK_PLAN.md`.
- **Corrects the C4 takeaways:** a solver change IS reliably A/B-able — at 1-worker+interleave. (C4's knobs stay
  rejected: they were null at 1 worker, which this protocol only reconfirms.)

### E0 — C6 required-target Lagrangian: MEASURED → REMOVED

Ran under the canonical protocol on **F4** (`--pa 11 --pm 4 --po 4`, real runes+subs catalog, lvl 110). Verdict:
**remove** (the plan's E0 "fires + barely-tighter box + trajectory-noise" branch). Evidence:

- **Box-delta probe (the decisive readout):** on the real catalog the Lagrangian slice *fires* but tightens the
  perHit product bound only **235,200,000 → 234,400,680 = 0.34 %**. The perHit ceiling is **not the binding face**.
- **Trajectory (1w+interleave, deterministic):** c6on-vs-c6off bound oscillates — det50 −4.8 %, det100 −3.0 %,
  det200 −1.3 %, det400 **+2.3 % (c6off ahead)**, det800 −6.2 %. The swings are **~18× the 0.34 % the cut actually
  moves**, so they are search-perturbation noise from adding a near-inert constraint, **not** causal.
- **Determinism:** 3 repeats byte-identical (c6on 2,038,180×3; c6off 2,100,800×3).
- **F4 primal/dual split (§2.1):** optimum incumbent (1,221,445) found by det≈100; bound still 31 % loose at
  det800 → **bounding-limited**. C6 was the right *category* (a dual-side cut) but the wrong *lever*.
- **Consequence:** the "tighten perHit further" channel is **spent** for constrained shapes — this also **demotes a
  C7 retry** there (same channel). The ~31 % dual gap lives in the sub / product-relaxation layers (→ §2.2
  bound-layer audit; expected catalog picks E2 / E6 / E9).
- Code reverted: the C6 branch `perf/c6-lagrangian-hardleg-bound` (commit `b84a2363`) is left dangling + unmerged.

### §2.2 bound-layer audit — F1 (lvl 110, free), 2026-07-08

New reusable seam: `manual bound-layer audit` (`WAKFU_BOUND_AUDIT=1`) solves the production model and prints, per
tracked objective-chain var, `declaredHi / valueAtIncumbent` (looseness). Identical tight-vs-loose domains ⇒
level-robust. F1 objective-chain looseness (biggest = where the LP can float most):

| layer | ratio | note |
|---|---|---|
| `dmgCritTerm` = crit·(M+5K) | **4.10×** | inner **crit×diff product** — loosest (→ E6 crit-band) |
| `dmgCriticalMastery` (K) | 3.67× | feeds crit×diff + diff |
| `rotRaw`/`rotDamage` = throughput·perHit | **3.24×** | outer **rotation product** (→ `perApRotRawCut` re-screen) |
| `dmgDiff` = M+5K | 2.95× | |
| `dmgScore` = perHit = D·graw | **2.29×** | the perHit box — **C6's target, NOT the loosest** (confirms E0) |
| `dmgGraw`, `dmgM` (mastery) | 1.74×, 1.62× | tightest |

**Reading:** the perHit box C6 attacked (2.29×) is not the binding face — the **crit×diff (4.10×)** and
**rotation (3.24×)** products are. Two candidate cuts fall out: `perApRotRawCut` (`maxRotRawForElement`, an existing
OFF-by-default flag → cheap re-screen) for rotRaw, and **E6 crit-band disjunction** for crit×diff. A big box ratio is
only a *candidate* — the A/B (does tightening it move the dual bound?) is the real test.

**F2 (lvl-245, tight domains) CONFIRMS + AMPLIFIES F1** (slow — ~30 min, full pool, no domination):

| layer | F1 (110) | F2 (245) |
|---|---|---|
| crit×diff `dmgCritTerm` | 4.10× | **4.50×** (loosest) |
| crit mastery `K` | 3.67× | 4.30× |
| `dmgDiff` = M+5K | 2.95× | 4.23× |
| rotRaw (throughput·perHit) | 3.24× | 2.99× |
| `dmgScore` = perHit (C6's target) | 2.29× | 2.41× |

The **crit cluster (crit×diff / crit-mastery / diff) is the loosest at both levels, looser at 245** — E6's target,
mandate confirmed. perHit (C6) is again not the binding face. (Note: the lvl-245 audit runs but is slow with the
full pool + no domination; a build-only no-solve variant via `maxDamageReachableRangesForTest` would be faster if
re-run often.)

### Cut re-screens under the canonical protocol — `perApRotRawCut` SHIPPED, E6 crit-band NOT (2026-07-08)

The experiment-A/B harness (`manual max-damage experiment ab`, 1-worker + interleave, det sweep) tested cuts on the
two audit-flagged loose layers. Bound = CP-SAT dual bound (lower = tighter); F1 = free lvl-110 (optimum 1,310,980),
F4 = constrained `--pa11pm4po4` (optimum 1,221,445).

**`perApRotRawCut` (rotRaw layer, 3.2× box) — WIN, shipped in DEFAULT.** This re-screens the stale "perturbs without
helping" verdict (pre-interleave harness) to a clear win:

| det | F1 baseline | F1 perApRotRaw | F4 baseline | F4 perApRotRaw |
|---|---|---|---|---|
| 200 | 2,101,955 | 2,103,865 | 2,064,440 | 2,058,980 |
| 400 | 2,028,420 | **1,647,375 (−18.8%)** | 1,989,505 | **1,851,360 (−6.9%)** |
| 600 | 1,822,650 | **1,631,150 (−10.5%)** | 1,825,260 | 1,814,970 (−0.6%) |
| 800 | 1,797,310 | **1,623,070 (−9.7%)** | — | — |

Big, consistent, deterministic dual-bound tightening on **free lvl-110** (the default lower-level request — free AP ⇒
many rotation cells ⇒ the per-AP bound bites hardest); marginal-but-not-regressive on **constrained lvl-110** (AP
pinned ⇒ fewer cells; a 1-worker incumbent lag at det200–400 that resolves by det600, likely a single-worker-search
artifact since production runs 8–9 workers).

**Level caveat — inert at lvl-245.** F2 free lvl-245: baseline == perApRotRaw *byte-identical* (25,978,170 at both
det200 and det400), and the bound is **stuck at the root** for both (the known lvl-245 "doesn't prove in-model"
pathology — the certificate carries that shape). So the mechanism is: perApRotRaw **accelerates a descending dual
bound (lvl-110)** but **cannot unstick a stuck root (lvl-245)** — it does NOT crack the lvl-245 flagship dual gap
(still open for E9 / E2 / E8). Shipping is still net-positive: a free win where the bound descends, self-disabling
(zero cost) where it doesn't, no regression anywhere.

Sound (the `per-ap-rotraw-cut` / `joint+per-ap` exhaustive-panel variants lock it), no `CERTIFIER_VERSION` bump
(model-only cut). Flipped `DEFAULT.perApRotRawCut = true`.

**E6 crit-band disjunction (crit×diff layer, 4.5× box — the LOOSEST) — NOISY, NOT shipped, kept flag-off.** Despite
targeting the loosest box, it does NOT deliver a reliable dual-bound win: on F1 it is looser at det100–200, marginal
at det400 (−2.6%), **worse than baseline at det600 (+5.2%)**, then catches up at det800 (−8.3%) — an oscillation, not
a trend — and it *interferes* with perApRotRaw when combined (det400: 1.988M vs perApRotRaw-alone 1.647M). Kept behind
`critBandDisjunction = false` (sound + exhaustive-locked, re-testable).

**Lesson (the key §2.2 caveat, now empirical): a big declared-box ratio is a CANDIDATE, not a guarantee.** The A/B is
the real test. The rotRaw layer (3.2× box) delivered the win; the crit×diff layer (4.5× box, the loosest) did not —
because "declared box loose" ≠ "the LP relaxation actually floats there." Audit to shortlist; A/B to decide.

### E8 measurement — DP-solvable fraction: **GO** (2026-07-08)

The plan's highest-upside item (certificate-DP as the primary *prover* ⇒ DP-seconds instead of CP-SAT-minutes)
hinges on whether the DP's argmax cell is **constructible**. Measured test-only (`manual E8 DP-solvable measurement`,
no structured-provenance seam yet): run the ledger, backtrack the argmax cell's provenance item names, restrict the
pool to those items, re-solve pinned to the cell AP (CP-SAT re-derives runes/subs/skills freely), compare the
re-solved objective to the cell bound (identical scale).

| fixture | argmax cell | cell bound | re-solved opt | ratio | DP-solvable |
|---|---|---|---|---|---|
| free lvl-110 | 14 | 1,310,980 | 1,310,980 | 1.0000 | **YES** |
| free lvl-245 | 16 | **16,909,590** (= campaign optimum) | 16,909,590 | 1.0000 | **YES** |

**Both free shapes are DP-CONSTRUCTIVE** — the DP's argmax item set re-solves to *exactly* the cell bound (the
proven optimum). Decisive at **lvl-245**, the shape where in-model CP-SAT is stuck at ~26M and never proves: the DP
both proves the optimum (16.9M) *and* names the items that achieve it. **Verdict: GO.** The default free search can
skip CP-SAT — run the DP, backtrack the argmax, construct the build, done.

**Fast-path IMPLEMENTED + FIRES (2026-07-08):** `WakfuBuildSolver.dpConstructProvenOptimum` — run the ledger,
backtrack the argmax cell's provenance items, restrict the pool, re-solve that tiny pool (CP-SAT re-derives
runes/subs/skills), and **accept ONLY when the re-solved proxy reaches the DP bound AND the build is `isValid()`**.
Since the bound is a sound upper bound on the global optimum, `proxy ≥ cellBound` certifies the build IS the global
optimum; otherwise it returns null and the caller falls back — so best-effort provenance parsing is safe (a miss only
costs the fast path, never correctness). Fires end-to-end at lvl-110 (constructs the `1,310,980` optimum, valid +
`isOptimal`).

The blocker the first cut flagged (the constructed build tripping `isValid()`) was a **stale validity check**, not a
seam bug: `hasLegalSublimations()` still enforced `runes + 3·subs ≤ sockets`, but commit `54761dc6` had removed that
socket-budget sharing from the model — golden runes form a normal sub's colour pattern AND carry their stat, so the
carrier keeps its full rune set. The solver model, the sub-model builder, and the AP-cell certifier all credit every
socket as a rune regardless of subs; only `isValid()` was left on the retired "reserve 3 sockets" rule. Fixed in
`6eefe734` (line 81 keeps just `runes ≤ sockets`), aligning it with the certifier — **no certificate soundness change**
(the certifier already credited all sockets, so the fuzz/oracle guards are unaffected).

**Production wiring — B/C/D SHIPPED (2026-07-08); A remains (perf).**
- ✅ **(B) cache-coherent seam** (`0526ea1f`): `dpConstructProvenOptimum` reuses `MaxDamageCertificateCache.certificate`
  (same key as `proveOptimality`, `incumbentObjective = incumbentProxy`) when an incumbent is present, so the ledger is
  a cache HIT — no extra ledger DP in production. The standalone/no-incumbent path keeps `certifyLedgerForTest` +
  `forceTier2All` for the manual proof test.
- ✅ **(C) production entry point** (`0526ea1f`): `WakfuBestBuildFinderAlgorithm.constructMaxDamageProvenOptimum(params,
  result)` — rebuilds the search's filtered pool and delegates to the seam with the incumbent's raw proxy. Returns the
  constructed proven optimum or null (sound — caller keeps the search's build). Rather than change `proveOptimality`'s
  return type (a widely-consumed contract), it is a SEPARATE additive call the consumers make on a `ProvenWithin`.
- ✅ **(D) CLI + GUI surfacing** (`0526ea1f` CLI, `43a10f4b` GUI): CLI prints the upgraded build's full sheet + rotation,
  flips the verdict to "Proven optimal", and routes the Zenith link to it. GUI's async prover swaps `ui`'s build +
  recomputes the whole sheet (achieved / rotation / scenario / match) exactly as the search-completion path does, then
  flips the badge to `ProvenOptimal`. `:autobuilder:test` + `:gui-compose:test` green.
- ⏳ **(A) STRUCTURED provenance folded into the cached ledger pass** — the ONE remaining piece, a **perf/robustness**
  optimization (the feature works without it). Today the seam still runs `certifierExplainForTest` (one explain-mode DP)
  for provenance and parses its debug strings. Fold the argmax cell's chosen `Equipment`-per-slot picks onto the
  `CertLedger` the cached pass already computes, so construction reads typed picks with **zero extra DP** and no string
  parse. Touches the certifier ⇒ **CERTIFIER_VERSION bump + re-run the fuzz + `@Tag("slow")` oracle guards**. Safe
  failure mode: a wrong pick → the re-solve misses the bound → sound fallback (never a wrong badge); a debug-string
  parse miss (an item name containing `" + "` / `"(di+"`) likewise just falls back, so the current cut is sound, only
  occasionally ineffective.

This rescues a **timed-out** incumbent on the DP-provable shape; in the common case the search already finds the
optimum and the certificate proves it, so E8 is a tail-case win. Caveats: `forceTier2All` at lvl-245 OOMs at 8 threads
(~1 GB/DP) — use `threads=1` + an incumbent prune. Constrained requests keep the hard leg (the DP can't model
targets — settled).

### Untested-flag inertness sweep — the remaining OFF experiment knobs (2026-07-08)

Four `MaxDamageExperimentConfig` cuts were still OFF and never re-screened under the canonical protocol:
`certifierCellCap`, `dGrawCutoff`, `apCeiling`, and (E6) `critBandDisjunction`. Re-ran all four through
`manual max-damage experiment ab` (1-worker + interleave, free lvl-110, optimum 1,310,980) to confirm each OFF default
is correct. Bound = CP-SAT dual bound (lower = tighter); `baseline` = shipped `DEFAULT` (perApRotRaw on), whose bound
descends to ~1.62M — a ~24 % gap still open above the 1.31M optimum (it never proves in-model on this shape).

| det | baseline | certCellCap | dGrawCutoff | apCeiling | critBand |
|---|---|---|---|---|---|
| 200 | 2,103,865 | **1,310,980 · OPTIMAL @det175** | 2,103,865 (=) | 2,096,760 (−0.3%) | 2,145,885 (+2.0%) |
| 400 | 1,647,375 | **1,310,980 · OPTIMAL @det175** | 1,647,375 (=) | 1,918,595 (+16.5%) | 1,987,950 (+20.7%) |
| 800 | 1,623,070 | **1,310,980 · OPTIMAL @det175** | 1,623,070 (=) | 1,858,430 (+14.5%) | 1,628,275 (+0.3%) |

The verdict is **heterogeneous** — "inert" holds for only ONE of the four; the other three are *live* cuts that would
change production behaviour if flipped on (for the worse, or redundantly). All four stay OFF, now evidence-justified.

**`dGrawCutoff` — INERT, production-unreachable (keep off; harmless dormant scaffolding).** Byte-identical to baseline
at all three checkpoints because the cut never fires: it is gated on `directCutoff != null`, sourced from the
`objectiveCutoff` parameter of `perTurnDamageScore` — and the ONLY non-null `objectiveCutoff` in the whole `main` tree
is set inside `timedMaxDamageProfileForTest` (the test harness; `WakfuBuildSolver.kt:861/915`). No production path
(`optimize`, the AP-probe batch, the certifier) ever supplies one, and the cut *also* needs the dedicated
`maxDamageApTarget` pin (a probe-only field the plain-maximize/target shapes never set). So even flipped ON in `DEFAULT`
it could not affect any production solve — inertness *by construction*, stronger than the empirical byte-identity. (To
exercise it at all one must pin `maxDamageApTarget` + pass `objectiveCutoff` — a cutoff-probe regime with no production
caller today.)

**`certCellCap` — NOT inert; it PROVES the optimum in-model — but keep off (certifier-cost-dominated + redundant with
E8).** The surprise of the sweep: it collapses the dual bound straight to the optimum 1,310,980 and returns **OPTIMAL**
at det≈175 — deterministically identical across all three det caps — where baseline never proves. Sound: it injects
`perHit ≤ U(a)` from the certifier's per-cell upper bound `U(a)`, and it proved the *correct* value (not a cut-down
one). Two reasons it still stays off:
- **Cost is hidden from det-time.** It runs the **full AP-cell certifier at model-build** (every reachable cell on the
  free shape) to get those caps — the same dominant cost as computing the certificate — and deterministic-time counts
  only *solver* work, so "proves at det175" conceals minutes of certifier wall-time. It duplicates the shipped **E8
  fast-path**: both spend the certifier DP to reach a proven optimum, but E8 is *lazy* (only on a timed-out incumbent)
  and *cheaper* (skips the CP-SAT solve), while `certCellCap` is *eager* — every max-damage solve pays the full
  certifier upfront, even ones aborted early or not needing a proof. E8's lazy rescue dominates it.
- **Scarier failure mode than E8.** Its soundness rests *entirely* on the certifier never under-counting: an
  under-count would inject an invalid `perHit ≤ U(a)` and CP-SAT would report a **false OPTIMAL** (vs E8, whose wrong
  pick merely misses the bound → sound null fallback). Same guards, higher stakes.

  **One genuine merit worth recording:** `certCellCap` proves via CP-SAT finding its *own* optimal build, so it is a
  **parse-free alternative to E8's deferred item-A** (structured provenance). If folding typed provenance onto the
  ledger ever proves too thorny, "inject certifier caps → let CP-SAT prove" sidesteps the explain-string parse E8
  relies on today.

**`apCeiling` — NOT inert; search perturbation, net-negative (keep off).** Tightens the rotation-AP ceiling
(`MAX_ROTATION_AP` → `actualActionPointCeiling()`). Marginally tighter at det200 (−0.3 %) but materially *looser* at
det400 (+16.5 %) and det800 (+14.5 %) — the "near-inert constraint perturbs the search" signature (same family as the
removed E0/C6). No reliable gain, a real mid-run regression.

**`critBand` (E6) — NOT inert; oscillates, interferes with perApRotRaw (keep off).** Re-confirms the earlier E6 verdict
against the now-perApRotRaw baseline: +2.0 % / +20.7 % / +0.3 % across the sweep — an oscillation, not a trend, whose
det400 regression reproduces the documented perApRaw-interference (1.988M vs 1.647M).

**Search-parameter Knobs (`probing3`, `objShaving`) were NOT re-run here** — they are OR-Tools solver parameters
(`cpModelProbingLevel`, `useObjectiveShavingSearch`), not `MaxDamageExperimentConfig` model cuts, and were already
settled under interleave in the C4 campaign (§6, RUN5: `probing3` same-or-worse deterministically; multi-worker "wins"
were worker-race noise — do not revisit).

The harness registry gained an `apCeiling` entry so this stays a one-command re-screen
(`WAKFU_EXP_AB=1 WAKFU_EXP_AB_VARIANTS=baseline,certCellCap,dGrawCutoff,critBand,apCeiling`).

### E2 conversion-conservation cut — SHIPPED: it UNSTICKS the lvl-245 flagship dual bound (2026-07-08)

The plan's first Tier-1 cut, implemented and shipped. A CONVERSION sublimation (Unraveling: crit-mastery → elemental
mastery under a crit floor) puts a shared `moved` var as `+1` in the mastery reach and `−1` in the critical-mastery
reach. C1 (always-on) nets it EXACTLY in the combined `damageMasteryCriticalReach`, but the per-stat **`clampSlack`** —
the sound over-estimate of how far `clamp(preStat, 0, ·)` rises above a raw sum that went negative — still counted the
`−moved` at full domain, crediting a clamp-restoration for a source driven negative by `moved`. Since
`moved ∈ [0, percent%·preSubStat(from)]`, moving it out can never push the source below its no-conversion min, so that
restoration is unreachable — a phantom that inflated the `diff`/`graw` reach. `conversionConservationCut` excludes
`conversionMovedVars` from the clampSlack reaches: **C1's other half.**

**Soundness (locked; no `CERTIFIER_VERSION` bump — CP-SAT model only, the certifier already handles conversions
analytically):** the targeted test `E2 conversion-conservation cut tightens the mastery-crit reach and stays sound`
asserts BOTH halves of the C7 lesson — the cut FIRES (`dmgDiff_`/`dmgGraw_` reach strictly shrinks on a conversion +
negative-crit pool) AND stays SOUND (`maxDamageVarBoundsForTest` with loose guard domains: every freely-solved
objective-chain value still fits the tightened box; an under-count would be a silently cut optimum). The freely-solved
`obj` reaches the true optimum on every A/B fixture, corroborating soundness.

**A/B (1-worker + interleave, det sweep; bound = CP-SAT dual bound, lower = tighter):**

| det | F1 free-110 base | F1 conv | F2 free-245 base | F2 conv | F4 pa11pm4po4 base | F4 conv |
|---|---|---|---|---|---|---|
| 200 | 2,103,865 | 1,842,240 (−12.4%) | 25,978,170 | 25,953,270 (−0.1%) | 2,058,980 | **1,665,045 (−19.1%)** |
| 400 | 1,647,375 | 1,833,150 (+11.3%) | 25,978,170 | 25,866,120 (−0.4%) | 1,851,360 | 1,663,470 (−10.2%) |
| 800 | 1,623,070 | 1,576,610 (−2.9%) | 25,978,170 (**stuck**) | **23,286,875 (−10.4%)** | 1,571,560 | 1,593,780 (+1.4%) |

**The headline: F2 unsticks the lvl-245 flagship root.** Baseline is FROZEN at 25,978,170 across all dets (the known
"doesn't prove in-model" pathology); convConservation DESCENDS — 25.95M → 25.87M → 23.29M — the effect COMPOUNDS with
det (so a det200 read alone reads "inert": premature). By det800 the bound is −10.4% and still dropping, and the
incumbent climbs to 16,807,500 (vs baseline's stuck 16,376,730), within 0.6% of the 16,909,590 optimum. **This is a
dual-bound descent `perApRotRaw` could NOT produce** — it was inert at 245 (a different loose layer). The two lvl-110
shapes are a horizon **wash**: convConservation takes a large early lead (−12%/−19% at det200) that baseline's descent
catches by det800 (F1 ends −2.9% ahead, F4 ends +1.4% behind); both reach the true optimum incumbent. So the shape of
the win is "big early tightening + a decisive unsticking of the hard, previously-frozen case."

Flipped `DEFAULT.conversionConservationCut = true`. This is the **disciplined exception** to the "no mid-trajectory
regression" bar that rightly kept E6 off: E6 oscillated with no endpoint win AND was inert on the flagship; E2 is sound,
completes an already-shipped cut (C1), and delivers a large reproducible win on the hardest case (the frozen 245 root)
plus big early leads everywhere, with only a lvl-110 horizon wash. **It also SHARPENS E9:** E2 unstuck the 245 root but
left a ~38% residual gap (23.3M vs 16.9M) — that remainder is the crit×diff LP relaxation (the audited 4.5× loosest
layer), confirming E9 (outer crit/DI-band enumeration) as the next lever.

### E9 crit-band enumeration — POC → NO-GO (dominated by the certificate) (2026-07-08)

E9 (plan §4 Tier 3): outer-enumerate the loosest product axis so each inner solve is tight, max over a partition = the
proven optimum (the per-element pattern applied to crit). The §2.2 audit named `crit×diff` (4.5× @245) loosest, so the
natural axis is crit. **POC-first** (a test-only `maxDamageCritBand` pin constraining the clamped crit to a band; baseline
= shipped DEFAULT with E2 ON, so this asks whether crit-banding closes what E2 left): does pinning crit make the stuck
lvl-245 solve prove?

| lvl-245 solve | obj | bound | vs baseline |
|---|---|---|---|
| baseline (E2, det1000) | 16,817,460 | 22,985,250 | — |
| band[0-33] (det1000) | 14,509,230 | 18,707,875 | tighter (low crit) |
| band[34-67] (det1000) | 15,945,960 | 24,776,000 | **looser** |
| band[68-100] holds optimum crit≈85 (det1000) | 16,543,560 | 23,251,250 | **≈ baseline — no help** |
| band[80-89] narrow (det**2500**) | 16,780,110 | 22,747,750 | **−1%, still 35% gap, 47 min for ONE band** |

**NO-GO — two independent reasons:**

1. **Crit is the SMALL factor of `crit×diff`.** At the optimum, `declaredHi/value = (critCap·diffMax)/(crit_opt·diff_opt)
   = (100/85)·(diffMax/diff_opt) ≈ 1.18 × 3.8`: the 4.5× is dominated by the **diff factor (M+5K ≈ crit-mastery K; audit
   K 4.30×, diff 4.23×)**, not crit chance (1.18×). Banding crit only tightens `term ≤ b_hi·diff` by lowering `b_hi`, but
   the optimum has HIGH crit (≈85) so `b_hi` must stay ≈ the baseline cap 100 — the bound is unmoved where the optimum
   lives (the POC's optimum-holding bands barely tighten; only the LOW band, where the optimum isn't, tightens). Coarse
   bands don't prove and some LOOSEN (search perturbation).
2. **Dominated by the certificate.** The certificate ALREADY outer-enumerates crit (its per-c grid) AND optimizes K/diff
   EXACTLY per crit value (the mastery/crit-mastery frontier DP). CP-SAT crit-band enumeration leaves K/diff to the loose
   LP relaxation — strictly weaker than the certificate, which was built precisely because in-model CP-SAT can't prove the
   crit-coupled optimum. Re-enumerating crit inside CP-SAT is a step backward; the 47-min single-band solve seals it (N
   bands = hours vs the certificate's ~5.7 min).

The residual 245 crit-cluster gap is the **certificate's** job — it proves the free optimum, E8 constructs it, and
constrained requests get the CP-SAT incumbent + the certificate bound. The cheap crit-band POC saved a multi-hour build
of an ineffective, redundant enumeration (scaffolding reverted). If any axis were worth enumerating it is diff/K — but
that IS the certificate's frontier DP, so the lever is to sharpen the certificate, not to reproduce it in CP-SAT.

### E8 item A — structured provenance: Phase 1 (robustness) + Phase 2 (perf) both SHIPPED (2026-07-08)

Item A hardens the shipped E8 fast-path (`dpConstructProvenOptimum`). It splits into two halves.

**A1 (robustness) ✅ SHIPPED (`d9db1a47`).** The seam recovered the argmax cell's items by regex-parsing the certifier's
`slot:` explain STRINGS — fragile: an optimal item whose name contains `" + "` (the split delimiter) or `"(di+"` (the
regex boundary) broke the parse, so E8 silently fell back to no-badge even when the optimum was provable. Now the winning
composition's **equipmentIds** are threaded through the explain backtrack (`Opt.ids` per item stage → collected at the
winning-line → `CertExplain.itemIds` → `StatBuilder`/`BuiltModel.certifierExplainItemIds` → `certifierExplainItemIdsForTest`
seam), and the seam restricts the re-solve pool by id. Explain-output only — **no bound math change, no `CERTIFIER_VERSION`
bump**, fail-safe (missing/wrong ids → re-solve misses the DP bound → sound null fallback). E8 construct fires via the ids
(lvl-110); full `:autobuilder:test` + ktlint green.

**A2 (perf) ✅ SHIPPED.** Before Phase 2, E8 re-ran the explain DP (`certifierExplainItemIdsForTest` = `certifyExplainAtAp`
≈ N-worlds passes + 1 explain pass ≈ **2+ full exact-cell passes ≈ ~9 min @lvl-245**) on EVERY rescued search — and 245
rescues are common (the in-model bound stays stuck even with E2, so the search times out → E8 rescues). Phase 2 captures the
argmax cell's provenance during the badge's **existing** exact pass, so the rescue replays a single crit-step instead of
re-scanning every world.

The shipped design is a **refinement** of the memory's original "snapshot every c" sketch — strictly cheaper and simpler.
Rather than snapshot full frontiers (overhead on the common badge path, the wrong tradeoff since the badge runs for *every*
search but E8 only for timed-out ones), the exact pass records just a lightweight **`CellProvenance(worldIndex, c)`** pointer
— the argmax world + winning crit-step — which is nearly free (`certifyMaxPerHitAtAp` / `exactForCells` already track the
max; they now surface *which* world/c achieved it). It rides on `CertLedger.cellProvenance` (a `@Serializable` value type),
accumulates through the in-mem `RawEntry` + `DiskRecord` as an OPTIONAL defaulted field, and survives `reconstruct`. The E8
seam replays only that one (world, c) as a single explain pass (`certifyExplainAtApFromProvenance` → new
`certifierExplainItemIdsFromProvenanceForTest` seam), **with graceful fallback to the A1 full scan** when the pointer is
absent (an old cache entry, or a tier-1.5-cleared argmax that never ran exactly). So E8's provenance cost drops from ~9 min
to ~seconds, the badge pays only a pointer write, and — because the field is additive/optional and changes **no bound** —
there is **no `CERTIFIER_VERSION` bump and no oracle re-run**. (The memory's "option B" — run the full explain once during
the badge — only RELOCATES the cost onto the common path, so it was correctly rejected.)

Soundness locks (CI-runnable, `WakfuBuildSolverTest`): *provenance replays the same backtrack as the full world scan* (the
cheap replay returns byte-identical items to the full N-worlds scan for every exactly-confirmed cell — the one invariant),
*disk record round-trips provenance*, and *a record predating the field decodes with an empty `provByCell`* (the
backward-compat / no-bump lock). Full `:autobuilder:test` + ktlint green.

### Certificate two-tier speed — exact-pass c-loop pruning via tier-1.5's free per-c rows (2026-07-08, `CERTIFIER_VERSION` 5→6)

The badge's dominant cost is the exact tier: one confirmed cell = `max over c of dp(c)` — ~`cEnumMax` (~110)
nearly-identical full DPs, of which only a handful can win (~4.5–5 min/cell at lvl 245). Tier-1.5's step-1 fast pass
runs per (survivor, world) right before the exact tier and its per-crit-step graw fold is EXACT — its harvest loop
already visits every (cell, c); it just maxed the per-c values away. Now it keeps them (`Tier15Result.perCByCell`,
harvested via the pass's new `fastPerCOutHolder`), and `certifyLedger` threads each row into the SAME (cell, world)
exact pass (`cPruneUbIn`), which **B&B-prunes its c-loop**: run the argmax-bound crit step first to seed `best`, scan
the rest ascending, and skip any step whose bound is strictly below `best` (`dp(c) ≤ ub(c) < best` can never raise the
max; a tie `ub == best` is never skipped, and a tie-aware update lands `bestC` on the smallest max-achieving c) —
**value and provenance byte-identical, only the number of full DPs changes**.

**The measured dead-end that shaped the design:** the first cut had each exact pass compute its own bound via a
per-(cell, world) step-1 self-call — lvl-110 `forceTier2All` went 835 s → 1,560 s (a **1.87× regression**: the step-1
pass costs about as much as the exact loop it prunes). So the bound is consumed ONLY where it already exists — the
production badge path (tier-1.5 just ran). The oracle / `forceTier2All` / no-incumbent paths stay unpruned and
unchanged. Never re-introduce a bound recompute on the exact path.

**Production rescue-shape A/B** (incumbent just below the optimum ⇒ the argmax survivor is exactly confirmed —
the ProvenWithin/E8-rescue badge flow), serial, identical ledgers on every run:
- lvl 110 (incumbent 1,300,000): **243 s → 189 s** (−22 % end-to-end; the exact cell's loop ~55 s → ~1 s — the run
  now sits at the fast+tier-1.5 floor).
- lvl 245 (incumbent 16,900,000): **620 s → 385 s** (−38 % end-to-end; exact cell 16 ~4.5 min → ~0.5 min; max =
  16,909,590 exactly).

`CERTIFIER_VERSION` 5→6 per the bump rule (an exact-pass change; values byte-identical, the bump is insurance).
Locks: *exact c-loop pruning is value- and provenance-identical to the unpruned loop* (couplingPanel + 4 seeded fuzz
shapes driven at `incumbent = argmax − 1`, full-`CertLedger` equality incl. `cellProvenance`, plus a pruning-FIRES
counter via `CertifierTuning`); the certifier fuzz (exact == CP-SAT) and E8 provenance locks re-run green with pruning
live. A/B seam: `WAKFU_MAX_DAMAGE_CERT_NO_CPRUNE=1` on the `manual max-damage certifyLedger end-to-end` harness.
Remaining two-tier levers: the fast+tier-1.5 floor itself (~185 s @110 fixture incl. model build) and E10 (run the
certificate concurrently with the CP-SAT search — pure orchestration).

### Badge floor — tier-1.5 segment skip below the incumbent (2026-07-08, `CERTIFIER_VERSION` 6→7)

New `WAKFU_MAX_DAMAGE_CERT_TIMING=1` instrumentation decomposed the post-pruning floor (110 rescue, 189 s):
**model build ~0.6 s, tier-1 fast 18.3 s, tier-1.5 172.9 s (90 %), exact 0.8 s** — tier-1.5 *was* the floor
(2 survivors × ~6 worlds × ~110 step-1 segment DPs each). The cut: tier-1's step-8 harvest already visits every
(cell, crit-step) — it now optionally keeps that per-world 2-D array (`certifyAllCellsFast(perCellCByWorldOut)` ←
the pass's `fastPerCellCOutHolder`, ~110 KB), and each tier-1.5 (survivor, world) pass **skips every step-1 segment
whose step-8 bounds already sit at-or-below the incumbent threshold** (per-survivor raw threshold = the largest raw
that still scales `≤ incumbent`, binary-searched in `certifyLedger`). A skipped segment's step-1 value is `≤` its
step-8 bound `≤` the threshold, so it can never cross the incumbent — **the clearing decision is provably
identical**; the (sound, step-8) values are carried into the outputs instead, so a cleared cell may record the
slightly looser step-8 bound (decision-identical, NOT byte-identical ⇒ `CERTIFIER_VERSION` 6→7). Near the optimum
only the top few crit segments re-run at step 1.

**Measured (serial rescue shapes, identical decision fields on every run):**
- lvl 110 (incumbent 1,300,000): **189 s → 35.0 s** (tier-1.5 172.9 → 7.8 s; exact 0.8 → 8.9 s — the carried
  step-8 values are a few % looser as exact-pruning bounds, costing a handful of extra DPs in weak worlds; net ≫ 0).
- lvl 245 (incumbent 16,900,000): **385 s → 89.1 s** (fast 58.3 s / tier-1.5 6.6 s / exact 23.1 s).
- **Session total on the rescue badge: 620 → 89 s @245 and 243 → 35 s @110 (7× both).**

Scope: the skip needs a fresh tier-1 run + an incumbent, so the B6 `precomputedFast` branch (cached fast, new
incumbent) and the oracle/`forceTier2All` paths run tier-1.5 unskipped, unchanged. Lock: *tier-15 segment skip is
decision-identical to the full step-1 pass* — couplingPanel + 4 fuzz shapes × {won-badge `incumbent = max`,
lost-badge `max − 1`}: same tier-2 cells/exact values/provenance/badge verdict, every non-exact cell `≤ incumbent`,
`cellObjective ≤ fast` everywhere, and the skip FIRES. A/B seam: `WAKFU_MAX_DAMAGE_CERT_NO_T15SKIP=1`. The floor is
now tier-1-fast-dominated (58 s @245) — next levers: the step-8 fast DP itself, then E10 orchestration.

### E10 — search ∥ certificate overlap (2026-07-08, no bound/format change — orchestration only)

The badge's remaining latency (~35 s @110 / ~89 s @245 serial, above) was paid AFTER the search finished. Now
`MaxDamageSearch.run` **warms the certificate cache in the background off the first streamed incumbent**: any
feasible damage proxy is a sound elimination incumbent (a weak early one just leaves more survivors, whose tier-1.5
rows and the argmax exact cell accumulate into the same B4 `RawEntry` the proof reads). The warm-up runs at
`threads = 1` (never competing with the search's CP-SAT workers), only for shapes whose proof actually consults the
certificate (mirrors `proveOptimality`'s params-only gates: no prefilter / forced runes / survivability floor /
multi-element), never on the deterministic test path (`tuning != null`), and is cancelled (B8) when the search is
cancelled mid-flight or superseded by a new search — a NORMALLY completed search leaves it running.

The companion piece is **single-flight in `MaxDamageCertificateCache.certificate`**: at most one compute per key at
a time — a concurrent caller (the post-search proof arriving while the warm-up still runs) waits on the in-flight
latch (cancellation-responsive 100 ms poll), then reconstructs from the merged entry instead of duplicating the DP.
Post-search the proof therefore either full-reconstructs (warm-up done — badge effectively instant) or joins the
in-flight warm-up (badge at warm-up completion). Front-ends unchanged — the CLI/GUI `proveMaxDamageOptimality`
call path is identical.

Expected end-to-end: with a search duration ≥ the warm-up cost (~89 s @245), the badge lands ~instantly at search
end instead of ~1.5 min later. If the final incumbent exposes a survivor the warm-up didn't confirm exactly, the
proof pays a partial recompute (B6 reuses the cached fast tier) — still far below cold. Locks: *certificate cache
single-flights concurrent computes of the same shape* (4 concurrent callers ⇒ exactly ONE compute via
`computeCountForTest`, all see the same ledger) and *production search warms the certificate cache in the
background* (an eligible production-path search launches the warm-up + populates the cache; a forced-runes shape
launches nothing). No `CERTIFIER_VERSION` bump (orchestration only; bounds and cache format untouched).

### Fast-pass DenseDp port — a measured ~6 %, NOT the exact pass's 3-5× (2026-07-08, `CERTIFIER_VERSION` 7→8)

New `CERT_FAST_TIMING` instrumentation (same `WAKFU_MAX_DAMAGE_CERT_TIMING=1` env) splits the fast tier into
DP-stage vs harvest time per world: @245 the segment DPs are ~70 % and the harvest ~30 %. The segment DPs still ran
on `HashMap<Long, Frontier>` with a full frontier copy per stage — the exact shape P1.3's DenseDp port turned into
3-5× on the exact pass — so the same port was applied (double-buffered `DenseDp` sized
`apDimF × critDimF × 4 × (subCap+1)`, allocated once per pass; all four stage patterns + the harvest iterate the
live list). **Byte-identical values** (the 245 fast-ledger oracle reproduces bit-for-bit; fuzz / segment-skip /
c-prune / provenance locks green) — but the quiet-machine measurement is only **58.3 s → 55.0 s (~6 %)**: unlike
the exact pass's many small per-c DPs (where hashing/boxing dominated), the fast tier's time lives in the frontier
work itself (dominance scans inside large frontiers), which storage cannot help. Kept (strictly faster, and the two
passes now share one storage architecture), bump per the rule. **Lesson: the exact-pass playbook does not transfer
1:1 — the next fast-tier lever would be frontier-level (dominance-scan cost), which is diminishing-returns
territory.** Badge @245: 89.1 → 84.5 s cold (and ~0 behind a search via E10).

### C7 crit×diff AM-GM joint cut — re-implemented SOUND, MEASURED-INERT on the flagship (2026-07-09, stays OFF)

The re-attempt of the reverted 2026-07-07 cut, built strictly from the proof-checked derivation staged in
`perf-review-backlog §C7`: `critDiffJointReachHi(scenario, μ)` extends `damageMasteryCriticalReach` with a third
weighted axis (the raw `CRITICAL_HIT` pre-percent terms at weight μ) and carries **all three** lower-clamp slacks —
crit at μ, mastery at 1, critical-mastery at 5 (pricing the m/K clamps at zero was the reverted under-count);
`maxCritDiffProductBound` caps `term = crit·diff` by `min_μ C(μ)²/(4μ)` with
`C(μ) = min(jointReachHi(μ), μ·critCap + diffHi)`, exact integers, wide geometric μ grid around `diffHi/critCap`.
Behind `critDiffJointBound` (OFF in DEFAULT), single-element only, self-disabling vs `term`'s declared reach.
**Guards green in the staged order:** the engineered competing-pool fixture (crit-vs-mastery amulet clash +
negative-crit ring + `critCapPercent = 40`) asserts the cut FIRES (`maxDamageCritDiffCutBoundForTest` seam) AND
CP-SAT still reaches the exhaustive optimum — the exact assertion that killed the first attempt; plus the
exhaustive-panel `crit-diff-joint` / `crit-diff+joint` variants and the DEFAULT-off lock.

**A/B (canonical protocol, quiet machine): byte-identical.** F2 free-245 runes+subs at det 200/400/800 — obj AND
bound identical to the digit (obj 15.82M/16.55M/16.81M, bound 25.95M/25.87M/23.29M); F1 free-110 likewise.
`WAKFU_MAX_DAMAGE_DEBUG_CRITDIFF=1` shows why: `jointU == independent, ratio = 1.0000` at both levels — the AM-GM
min never beats the independent box, so the self-disable never adds the constraint. **Structural, not a tuning
miss:** at real levels raw crit SATURATES any cap (base 3 + abundant crit gear ≫ 100) without sacrificing mastery
slots, so the independent corner `(critCap, diffHi)` is jointly achievable and NO constant cap on `term` can be
tighter; the residual crit×diff dual gap is the LP's interior fractional pairing — E9 already showed crit is the
small factor and diff/K enumeration is the certificate's job. The cut fires only on scarce-crit pools (crit
competing for the cap below saturation). Kept in the registry OFF (sound, triple-locked, zero-cost when inert);
the `critDiff` harness variant + the debug env remain for any future capped-crit shape.

### Most-masteries production profile → greedy warm start SHIPPED + cap-pin soundness fix (2026-07-09)

**Profile first (new `manual most-masteries profile` harness + `SolverTuning.applyDominationOverride`, which
un-couples domination from `tuning == null` — every earlier tuned most-masteries measurement had silently run the
FULL pool).** F5 shape @245 (distance + AP12/MP6/HP2000/crit30, runes+subs), canonical protocol with the
production p1/l1 params: model build **0.15 s**; first CP-SAT solution at **26–32 s** (presolve + first feasible —
the GUI shows NOTHING for that long); proven optimum at 113–120 s (domination worth ~5 %). Pool sizes killed the
"extend the max-damage targeted compare-set" idea before it was built: full 7 884 → all-stat domination 6 858
(−13 %) → max-damage-shape proxy 6 643 (only −16 %) — ~3 % extra pruning does not buy a soundness argument.

**Shipped: C8(3) greedy warm start (`MostMasteriesWarmStart`), two uses from one construction.** A shortfall-aware
greedy (per-slot argmax of weighted requested stats; required-target credit normalized onto the mastery scale by
`target` — the power-6 penalty makes 1 AP of 12 worth a large slice of the whole score — consumed slot-by-slot;
top-2 distinct-name rings; 2H-vs-1H+off combo; ≤1-epic/relic repair; target-aware deterministic skill fill):
 1. **instant emission** — streamed as the first result, scored by the production scorer: first build at
    **~0.15 s instead of 24–28 s**, meeting ALL required targets (AP 12, MP ✓, HP ✓, crit ✓ on F5);
 2. **CP-SAT hint** — the equipment layer via `addHint`; CP-SAT's own first solution then lands at ~11 s
    (vs 24–28 s), and every axis still reaches the SAME proven optimum (10 705) — time-to-optimal is a wash
    (−19 % / +13 % across the domination axes), so the ship case is the trajectory, not the proof.
Consumers keep the LAST emission, so the greedy score also becomes the intermediate-emission floor
(`suppressBelowScore`) — a worse CP-SAT snapshot can no longer visibly regress the displayed build. Gated to
production most-masteries (no forced items) + the `SolverTuning.greedyWarmStart` A/B seam; locks: greedy validity +
scorer-exact first emission + monotone stream + optimum equality vs flag-off.

**Cap-pin soundness fix (found by reading, locked by test):** the 16 AP / 8 MP / 20 WP out-of-combat caps are HARD
constraints in every mode, but only max-damage PINNED those stats in domination — most-masteries/precision compared
them like any other stat, so a strictly-higher-AP twin could evict the only cap-safe item a cap-tight optimum
needs. AP/MP/WP are now pinned in all three modes (max-damage byte-identical — its pins just moved).

### Fast-tier frontier investigation — CLOSED: add-call-volume-bound, no order/copy lever (2026-07-09)

The DenseDp round-2 follow-up ("the next fast-tier lever would be frontier-level") is now evidence-settled.
Permanent flag-gated instrumentation (`WAKFU_MAX_DAMAGE_CERT_STATS` on the ledger harness): `Frontier.add` calls,
ACTUAL points visited by the dominance/compaction scans, rejections, max live frontier, and `copy()` volume.
@245 fast ledger (1 thread, ~57 s): **~409 M rejected adds, 8.66 B points visited (avg ~21 of ~96 live points per
rejection — the early-exit already works), max frontier 63, 26.6 M copies / 260 M points copied (~155 MB/s).**
Three micro-experiments, all value-identical by construction (rejection is an existential check; reordering never
changes the kept Pareto set):
 - newest-first rejection scan: points visited +3.5 %, wall unchanged — dominators skew old/strong, not recent;
 - self-organizing transpose (dominator swaps one step forward): points visited −2 %, wall unchanged;
 - copy accounting: carry-forward copies are ~155 MB/s and ~1.3 M allocs/s — nowhere near alloc/bandwidth-bound,
   so a copy-on-write carry would buy a few % at real aliasing risk.
**Wall time was invariant under ±4 % scan-count changes ⇒ the fast tier is bound by the intrinsic add-call VOLUME
(the DP's state × item × point convolution), not by per-add scan cost.** Further reduction means restructuring the
DP itself (fewer candidate points generated), not the frontier store. Shipped: the instrumentation only (never-taken
branches when off; scan order stays the original forward loop; no `CERTIFIER_VERSION` bump — no value-affecting
change). Don't re-run the order heuristics.

---

## Provenance

Produced by a 30-agent audit (6 per-mode/area deep readers → adversarial per-candidate verification that
each speedup provably preserves the optimum + proof → ranked synthesis), 2026-06-24. 13 candidates
survived verification; 10 were rejected (§4). During the audit the repo advanced 1.7.0 → 1.8.0 (4 feature
commits touching the solver); all six top picks were **re-verified against `main` @ 1.8.0** and remain
valid and unaddressed. Related: `docs/MAX_DAMAGE_PROVABLE_OPTIMUM.md` (why max-damage is provable),
`docs/FULL_DAMAGE_MODE_STATUS.md` (mode coverage), `AGENTS.md` §4 (engine overview).
