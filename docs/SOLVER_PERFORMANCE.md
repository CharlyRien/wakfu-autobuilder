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
A `Map<QueryKey, GeneticAlgorithmResult>` consulted before solving; on a miss, solve and store **only if
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

## Provenance

Produced by a 30-agent audit (6 per-mode/area deep readers → adversarial per-candidate verification that
each speedup provably preserves the optimum + proof → ranked synthesis), 2026-06-24. 13 candidates
survived verification; 10 were rejected (§4). During the audit the repo advanced 1.7.0 → 1.8.0 (4 feature
commits touching the solver); all six top picks were **re-verified against `main` @ 1.8.0** and remain
valid and unaddressed. Related: `docs/MAX_DAMAGE_PROVABLE_OPTIMUM.md` (why max-damage is provable),
`docs/FULL_DAMAGE_MODE_STATUS.md` (mode coverage), `AGENTS.md` §4 (engine overview).
