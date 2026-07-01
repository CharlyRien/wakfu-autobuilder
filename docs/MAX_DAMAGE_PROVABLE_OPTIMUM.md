# Max-damage: a real optimality proof — research log

**Status:** **LANDED** (domain-propagation layer shipped; §6.1 done). The free max-damage solve now proves
`CpSolverStatus.OPTIMAL` (gap 0) on the full level-110 **and** level-245 EPIC pools — no AP-enumeration, no
global-constant hacks. **Goal:** make the max-damage (`FIND_BUILD_WITH_MAX_DAMAGE`) CP-SAT search return a
**proven optimum**, fast enough to ship — ideally as the default.

This is a **living log**: it records what was tried, what worked, what didn't, and what's next. Append as work
lands; don't rewrite history (the dead ends are the value). Symbol-first references; line numbers drift.

---

## 0. Landed (2026-06-22): the domain-propagation layer (§6.1)

The sound, per-pool reachable-domain layer from §6.1 is **implemented and verified**. Net effect: the **free**
mono solve (AP optimized jointly, no pin) returns **OPTIMAL gap-0** on the real full pool at both levels.

**What shipped** (`WakfuBuildSolver.kt`):
- `DomainTracker` — maintains `varDomain: Map<IntVar, LongRange>` (reachable range per chain var) + a `tight`
  flag. `tight=true` declares each chain var sized to its reachable range (clamped into the old `*_ABS_MAX`
  **overflow guards** via `decl`); `tight=false` reproduces the loose guard domains (every non-max-damage
  objective, and the soundness-test reference).
- Tracked builders in `StatBuilder` — `tSum`/`tSumNaive` (interval sum), `tClamp`, `tMul` (interval product),
  `tDiv`, `tPercent`, `tElement`, `tConst`. Each declares its output from its inputs' ranges and records its
  own range. Leaves (`equipVar 0..1`, `skillVar 0..maxPoints`, `runeVar 0..slots`, sub `applies 0..1`,
  constants) are seeded in `StatBuilder.init`. The **whole** perHit + perTurn chain is covered (§5b: a partial
  retrofit won't prove).
- **Per-slot tightening** is the dominant lever: `reachableSumDomain` bounds each item stat-sum **per
  mutually-exclusive slot** (one item per slot, two rings; itemTypes treated as independent ≤limit groups — a
  sound over-estimate that even allows 2H+1H+off-hand together), not the naive Σ-over-all-candidates. This is
  what brings the mastery `M` domain down to its true ~6k from the Σ-all-items ~30–40k, which then cascades
  through `diff`/`graw`/`damageScore`/`perHitScaled`/`raw`.
- **Solver config:** `linearizationLevel=2` for max-damage (both the production wall-clock path and the
  deterministic test path); production `maxPresolveIterations` lifted `1 → 3` for max-damage only (reachable
  domains make presolve terminate fast; the other modes keep the single light pass). All gated on
  `mode == FIND_BUILD_WITH_MAX_DAMAGE` so the other modes' tuned paths are byte-identical.

**Verification (tests in `WakfuBuildSolverTest`, all green):**
- `…proves OPTIMAL on the full level-110 pool` — the headline PoC, reproduced via the production path
  (`optimize(params, fullEpicPool(110), SolverTuning(maxDeterministicTime=300))`); proves in **~50 s walltime**
  incl. cold start.
- `…proves OPTIMAL on the full level-245 pool` — proves too (larger pool ⇒ wider domains ⇒ more work);
  **~3.5 min walltime** at `maxDeterministicTime=600`. Provable, but this is the slow end of the budget — the
  ~1–3 s target still needs §7 dominance-pruning.
- `…every max-damage objective var stays within its declared reachable bound` — the **soundness lock**. Solves
  the lvl-110 model with **loose** declared domains (so the solver can drive each var as high as a real build
  allows) and checks every tracked var's solved value against the reachable `[lo,hi]` the tight build *would*
  declare. A value outside its range = an interval-arithmetic **under-estimate** that would silently cut the
  optimum. Green ⇒ the per-slot interval arithmetic is a true over-estimate.
- `…tight reachable domains preserve the exact max-damage optimum` — on a small pool where BOTH the tight and
  loose models prove, their optima are **equal** ⇒ tightening changes only provability/speed, never the answer.

**Follow-ups that also landed:** the external loop already computed `proven = (activePhases==1) && phase1Optimal`
(= mono ∧ no-debuff ∧ CP-SAT proved), so it now reports `isOptimal=true` for the mono no-debuff case for free —
no AP-enumeration scaffolding remained to drop. `SolverResult.maxDamageHeuristicPhases` now carries
whether a non-optimal result is *structural* (debuff-sequencing / multi-element split) vs merely time-limited,
and the GUI hint (`NOT_OPTIMAL_STRUCTURAL_HINT`) was rewritten — the old "the damage objective can't be proven"
copy is obsolete now that it can.

**Still open:** §7 (dominance-pruning toward ~1–3 s); the free model with runes+subs enabled (the tracked
chain stays sound — sub-conversion `moved` falls back to the guard, so subs only *loosen*, never unsound —
but provability with them on is unverified); production wall-clock-path timing on the cores−1 portfolio.

---

## 1. The problem

Today a max-damage search returns `FEASIBLE` "optimum not proven": it burns the whole user time budget and
never closes the bound. Measured on the real solver (CRA lvl-110, full pool, fire): `best_bound ≈ 204.8M` vs
`objective ≈ 2.27M` — a **~90× gap** that never closes, `lp_iterations: 0`.

### Root cause (confirmed): loose variable bounds → useless McCormick LP
The damage objective is a product chain:
```
M        = 100 + mastery_e                                   # linear in item/skill vars
graw     = 400·M + crit·(M + 5·critMastery)                  # products: crit·M, crit·critMastery
perHit_e = (100 + DI) · graw                                 # product: d·graw
throughput_e = addElement(apVar, T_e[·])                     # exact binary×const; T precomputed, monotone in AP
damage_e = resFactor_e · throughput_e · perHit_e             # product: throughput·perHitScaled
objective = max_e damage_e
```
CP-SAT relaxes a product `x·y` with a **McCormick envelope** whose looseness ∝ the box area
`(x_hi−x_lo)(y_hi−y_lo)`. The intermediate IntVars are declared with "safe huge" domains, **not reachable** ones:

| var | declared cap (orig) | reachable (lvl 110) | looseness |
|---|---|---|---|
| `STAT_ABS_MAX` (every stat sum domain) | 10,000,000 | ~6,000 | ~1,600× |
| `DAMAGE_MASTERY_MAX` (M, critMastery) | 100,000 | ~6,000 | ~17× |
| `DAMAGE_GRAW_MAX` (graw) | ~1e8 | ~4e6 | ~25× |
| `DAMAGE_SCORE_ABS_MAX` (perHit) | ~5e11 | ~1e8 | ~5,000× |
| `PER_TURN_THROUGHPUT_MAX` (throughput) | 60,000 | ≤1,252 | ~48× |

With boxes 3–5 orders too wide, the LP bound is worthless → CP-SAT can't certify the incumbent → it proves by
pure branching (slow) or never finishes within budget.

**AP was a red herring for the *bound*** — it's just the worst single product (large×large). And **`lp_iterations:0`
was a red herring for *speed*** — see §4.

---

## 2. Key conceptual result — drop AP-enumeration (the user was right)

An earlier design proposed **enumerating AP** (pin AP=a, solve, max over a) to dodge the throughput×perHit
product. **This is unnecessary.** The free `perTurnDamageScore` already optimizes AP jointly with crit/mastery/DI:
AP is just `actualStat(ACTION_POINT)`, one linear stat var; throughput is `addElement(apVar, table)` = an exact
`binary × constant` selection. AP is "special" only because it indexes a precomputed table — already handled
in-model. Damage = `M(build)·T(AP)`; `T` is monotone non-decreasing in AP but AP competes with mastery for the
item/skill budget, so the optimum sits at one of the few **breakpoints** of the step function `T` — which the
solver finds on its own. Confirmed empirically: the **free** model proves OPTIMAL with tight bounds and finds a
**better** build than any fixed AP pin (§4).

**Rule:** enumerate a dimension externally **only** if it changes the turn's *structure* (within-turn debuff
ordering, bi-element split) — never to make a product provable. Tighten the box instead.

---

## 3. The fix that works: tight reachable bounds

Tightening the intermediate **declared domains** to reachable maxima shrinks every McCormick box, so CP-SAT
certifies the bound and returns OPTIMAL. **Critical nuance:** tighten the *declared domain*, NOT the `clampVar`
cap. Lowering a clamp cap below a pool build's reachable value makes the clamp **bind**, adding a `min` plateau
that is *worse* (see exp E in §4). A declared domain at the true reachable max never binds and tightens McCormick.

---

## 4. Experiment log (chronological)

Setup: real solver via `WakfuBuildSolver.optimize(params, pool, SolverTuning())`; full lvl-110 EPIC pool
(`equipments` filtered `rarity≤EPIC`, `level∈0..110`), CRA, fire, **empty targets** (so `applyConstraintPenalty`
adds no extra product), no runes/subs unless noted. `SolverTuning` = full presolve + det-time 60/worker + seed 1.
`OPTIMAL` is read off `SolverResult.isOptimal` (= `status==OPTIMAL`); `responseStats()` for the rest.
Walltime is noisy under concurrent load — **det-time is the machine-independent metric**.

| # | Config | status | objective | best_bound | gap | lp_iters | branches | det-time | walltime |
|---|---|---|---|---|---|---|---|---|---|
| A | orig loose bounds, FREE (production today) | FEASIBLE | 2.27M | 204.8M | ~90× | 0 | — | (budget) | ~60s |
| B | **PoC**: AP-pinned=11, const throughput, loose bounds, full presolve | **OPTIMAL** | 1,157,580 | =obj | **0** | 0 | — | 251–319 | 33–62s |
| C | tight global bounds + lvl-2, AP-pinned=11 | OPTIMAL | 1,157,580 | =obj | 0 | 0 | — | 115 | 17.5s |
| D | **tight global bounds + lvl-2, FREE** (clean machine) | **OPTIMAL** | 1,276,125 | =obj | **0** | 0 | 2441 | **135** | **18.4s** |
| E | clamp DAMAGE_MASTERY_MAX→6000 (BINDS), AP-pinned | FEASIBLE | 1,157,580 | 1,253,160 | ~8% | 0 | 2078 | 394 | 98s |
| F | D + division-folding (remove `perHitScaled` mid-floor) | OPTIMAL | 1,278,145 | =obj | 0 | 0 | 2042 | **255** | 43s |
| G | D but `numSearchWorkers=1` (diagnostic) | FEASIBLE | 1,276,125 | 1,423,450 | ~11.5% | **648,997** | 748,271 | 60 (cap) | 50s |

Tight global constants used in C/D/F/G: `STAT_ABS_MAX 10M→100k`, `DAMAGE_MASTERY_MAX 100k→12k`,
`DAMAGE_DI_MAX 5k→1k`, `CLAMP_INTERMEDIATE_MAX 8e9→1M`, `PER_TURN_THROUGHPUT_MAX 60k→4k`. These are
**level-110-safe only** — production must compute them per-pool (§5).

### What each experiment proved
- **B** — provability is achievable at all: removing the worst product (pin AP → constant throughput) + full
  presolve reaches OPTIMAL even with loose bounds. But ~40s and needs AP pinned.
- **C/D** — **the real fix.** Tight bounds make the **FREE** model (no AP pin) prove OPTIMAL, gap 0, ~18s. D's
  objective (1,276,125) > C's pinned (1,157,580): the free solve picks a better AP itself ⇒ AP-enumeration is
  obsolete. **This is the design.**
- **E** — *don't lower the clamp cap.* `clampVar(M,0,6000)` binds (some pool build's mastery > 6000), adds a
  plateau, and it went FEASIBLE with a worse det-time. Tighten the **domain**, not the clamp.
- **F** — *division-folding is a dead end.* Removing the `perHitScaled = perHit/100000` floor between the two
  products made it **worse** (det 135→255): it loosens the throughput×perHit box 100,000×, which outweighs any
  LP benefit. The mid-floor is not the LP-killer.
- **G** — **`lp_iterations:0` is a red herring.** With one worker the LP runs hard (648,997 iters) → the LP *is*
  built. In the 8-worker portfolio a **CP-propagation** worker wins the race (det 135) while the LP worker would
  grind to an 11.5% gap at its budget. There is **no "engage the LP" win** — the portfolio already picks the
  fast path. The single-worker LP-heavy solve is *slower*, not faster.

---

## 5. What works / what doesn't (summary)

**Works:**
- Tight **declared domains** at reachable maxima (the dominant lever; 90× gap → 0).
- The free model (no AP pin) + full presolve + the 8-worker portfolio → OPTIMAL in ~18s, finds the best AP itself.
- `linearizationLevel=2` is set in experiments but is **not** the thing that flips it (the LP isn't the winner);
  harmless, keep it but don't rely on it.

**Doesn't work / dead ends (don't retry):**
- AP-enumeration — unnecessary (§2).
- Lowering `clampVar` caps — binds, adds plateaus, worse (E).
- Division-folding (removing the mid-chain floor) — loosens the big product, worse (F).
- Forcing/relying on the LP — it already runs; CP-propagation wins anyway (G).

**Inherent cost:** ~15–20s to prove OPTIMAL over ~1,200 booleans × 14 slots (lvl-110 pool). That is **faster +
proven** vs today (60s, FEASIBLE-unproven) ⇒ **viable as the default**, with graceful "best found" fallback when
a bigger pool (lvl 245) can't prove within the budget.

---

## 5b. Implementation finding (2026-06-22): the looseness is PERVASIVE

Scoping the production change revealed the loose bounds are **not one constant** — they're spread across the
whole var-building stack, so tightening any single site (or even all the stat sums) does **not** propagate:
- `prePercentStat` / `preSubStat` / `sumVar` declare stat-sum domains as `[-STAT_ABS_MAX, STAT_ABS_MAX]` (1e7).
- **`elementVars`** (the source of the `M` mastery var that `perHitDamageScore` reads — NOT `prePercentStat`)
  builds its own random/greedy sums with `STAT_WITH_PERCENT_ABS_MAX` and `*priorityScale` domains.
- `clampVar(preM, 0, DAMAGE_MASTERY_MAX)` declares `m`'s domain from `DAMAGE_MASTERY_MAX` (1e5), **not** from
  `preM`'s (now-tight) domain — so a tight input does not yield a tight clamp output.
- the products (`addMultiplicationEquality`) and `applyPercent` declare outputs from `PRODUCT_ABS_MAX` etc.
- sub conversions declare `moved` as `[0, STAT_ABS_MAX]`.

**Consequence:** a single-constant or stat-sum-only fix won't engage the tight McCormick; the global-constant
experiment (exps C/D) worked only because it tightened *every* relevant constant at once — but global constants
aren't sound across levels (245 mastery > the lvl-110-safe value). The sound production fix must tighten **every
var's declared domain from its inputs**, i.e. a domain-propagation layer (next).

Var maxes available for seeding (all selection vars are ≥ 0): `equipVar`=1 (bool), `skillVar`=`skill.maxPointsAssignable`,
`runeVar`=`equip.maxShardSlots`, sub `applies`=1 (bool), passive=`newConstant(value)` (max=value), sub `moved`
= percent% of `reachableMax(from)`.

## 6. Production design (the ~15–20s proven default)

1. **Per-pool reachable bounds via a domain-propagation layer (sound, the load-bearing change).** Because the
   looseness is pervasive (§5b), retrofit a thin **domain-tracking** layer onto the var builders: maintain
   `varDomain: Map<IntVar, LongRange>` and have each builder declare its output domain from its inputs:
   - seed: `equipVar`→`0..1`, `skillVar`→`0..maxPointsAssignable`, `runeVar`→`0..maxShardSlots`, sub `applies`
     →`0..1`, `newConstant(v)`→`v..v`.
   - `sumVar(terms,const)` → `const + Σ coeff·domain(var)` (interval arithmetic; sound over-estimate ignoring
     exactly-one-per-slot — presolve + the slot constraints tighten it to per-slot).
   - `clampVar(x,lo,hi)` → `max(lo,x.lo)..min(hi,x.hi)` (now inherits a tight input).
   - `addMultiplicationEquality(z,[x,y])` → interval product of `domain(x)·domain(y)`.
   - `applyPercent`, `addDivisionEquality`, `elementVars`' random/greedy sums → same treatment.
   Then every product's McCormick box is reachable-sized automatically, no per-level global constants. Keep the
   old `*_ABS_MAX` constants only as int64 overflow guards (`coerceIn`). **Soundness is critical:** the interval
   arithmetic must be a true over-estimate — an under-estimate silently cuts the optimum (lock with a test).
2. **Production solver config:** `linearizationLevel=2`; **lift `maxPresolveIterations=1`** (it was a symptom of
   loose bounds — once domains are reachable, presolve terminates fast). Keep wall-clock budget; on a pool too
   big to prove in time, return the best-so-far as `FEASIBLE` (today's fallback).
3. **Drop the AP-enumeration scaffolding:** the mono AP-window probe in `MaxDamageSearch` (kept only for the
   debuff case) and any `perTurnDamageScoreProvable`/pin-for-provability path. Keep bi-element + debuff phases as
   *structure-changing* features (§2), not as provability workarounds.
4. **`proven` flag honesty:** `proven = (free mono solve OPTIMAL) && !hasResistanceDebuff && !multiElementRequest`.
   The proof is of the base-throughput per-turn proxy; for debuff/multi-element the displayed sequenced damage can
   reshuffle, so those stay "best found".
5. **Tests:** (a) **soundness** — for a sample of requests, every objective var's solved value ≤ its declared
   bound (a violation = a cut optimum); (b) **regression/monotonicity** — the free tight-bound optimum ≥ the old
   heuristic's found build on the same input; (c) deterministic OPTIMAL assertion on a small pool.

Code touch-points: `WakfuBuildSolver.kt` — constants `:67–90`, `perHitDamageScore` (`crit·diff`, `d·graw`),
`perTurnDamageScore` (`addElement` + product + two `addDivisionEquality`), stat builders `prePercentStat` /
`baseTermsFor` / `sumVar`, solver config `tuning==null` branch (`maxPresolveIterations=1`). External loop
`MaxDamageSearch.kt` (`proven` flag, AP-window probe). Throughput table `SpellRotation*.baseThroughputTable`.

---

## 7. Path to ~1–3s (separate effort — NOT yet done)

Solver tuning is exhausted (§4). The only lever left is **shrinking the model**:

- **Pool dominance pruning (most promising).** In each slot, drop item B if some item A *dominates* it: A ≥ B on
  every objective-relevant stat (element/secondary mastery, crit, crit mastery, DI, AP/MP if they gate
  throughput) **and** on every constraint-relevant stat. Removing dominated items is **sound** (B can never be in
  a strictly-better build). Could cut ~hundreds of items/slot → tens, shrinking booleans 5–10× → proof in
  seconds. **Risks:** dominance must include *every* stat the objective OR a constraint reads (miss one → cut the
  optimum); multi-objective (mastery vs AP vs crit) makes strict dominance rarer than it looks; weapon
  slot-coupling (2H vs 1H+offhand). Needs a soundness test (pruned optimum == unpruned optimum on samples).
- **Warm-start hint (untested).** Feed the heuristic build via `model.addHint` so the primal sits at the optimum
  at t≈0 and only the dual proof remains. Caps at maybe ~2× speedup (the proof is dual-bound-dominated), so
  unlikely to reach ~1–3s alone — but cheap, worth measuring, composes with pruning.
- **Gap-limited certificate (not a true optimum).** `relativeGapLimit ≈ 0.5%` → "provably within 0.5% of the best
  possible" fast. Honest but not what was asked (true optimum); keep as a fallback knob, not the default.

---

## 7b. Spin-off (2026-06-22): the item prefilter is now scoped to MULTI-element only

The full-pool tractability the domain work demonstrated for max-damage prompted a look at the **item prefilter**
(`needsItemPrefilter` / `prefilterRelevantEquipments`) used by **most-masteries / precision** — a top-N-per-stat
**heuristic** that "trades global optimality for tractability". Benchmark (`solveForBenchmark`, full lvl-245 EPIC
pool, det-time 120; numbers are walltime):

| request | prefiltered | full pool |
|---|---|---|
| most-masteries, single fire mastery (lvl 245) | OPTIMAL 0.3 s, pool 562 | **OPTIMAL 3.4 s**, pool 7884, **same score** |
| precision, single fire mastery (lvl 245) | OPTIMAL 0.0 s | **OPTIMAL 1.2 s**, same score |
| most-masteries, single fire (lvl 110) | OPTIMAL 0.4 s | **OPTIMAL 1.1 s**, same score |
| most-masteries, **aggregate** all-elements (lvl 245) | **FEASIBLE** (no proof), pool 635 | (worse) |

(The benchmark ran default/full presolve; production most-masteries uses `maxPresolveIterations=1`, which is
faster but still does not *prove* the aggregate optimum — it stays FEASIBLE either way.)

Root cause confirmed: the explosion is `applyGreedyRandom`'s per-item × per-element assignment booleans +
**O(elements²)** ordering constraints — minted **only when a single elemental fold has >1 wanted element**. A
single specific element takes the cheap `effectiveCount == elementCount` branch (no assignment vars), so the full
pool stays small and **proves the true optimum in ~1–4 s** (even under the production light-presolve config,
`maxPresolveIterations=1` → 4.4 s). So the heuristic only ever *pruned* the optimum there for no tractability gain.

**Landed:** `needsItemPrefilter` narrowed to `masteryElementsWanted.size > 1 || resistanceElementsWanted.size > 1`.
Single-element mastery/resistance requests (the common case) now solve the **full pool → proven global optimum**;
removing a heuristic from a superset pool is **sound by construction** (full ⊇ prefiltered ⇒ optimum can only rise).
The **aggregate / multi-element** case keeps the prefilter. It *was* intrinsically slow — even the small
prefiltered 635-item pool only reached FEASIBLE at 204 s — because the bottleneck was the **random-element
MODELLING**, not pool size (see §7c, now fixed). Locked by tests (`…no longer prefiltered (full pool, proven
optimum)`, `…aggregate request still prefilters`).

---

## 7c. Landed (2026-06-22): the multi-element random-element assignment was greedy AND slow

The 204 s aggregate FEASIBLE traced to `applyGreedyRandom` (`WakfuBuildSolver.kt`): a random-element mastery item
rolls its value onto k∈{1,2,3} elements, and for a >1-element request the model minted, **per item**, a per-element
assignment boolean + a cardinality constraint + **O(elements²) reified pairwise-ordering constraints** to force the
roll onto the highest-*deficit* elements — replicating the scorer's greedy `assignValues`. That ordering is the
explosion, AND it pins a **provably suboptimal** assignment: the most-masteries objective maximizes the *minimum*
element, but deficit-sorting optimizes target coverage, so it starves the lowest element (worst when targets are
unequal). Brute force (`RandomElementAssignmentTest`) found the greedy beatable in **86 821 / 200 000** aggregate
trials — and a naive max-min water-fill is *also* beatable (no simple greedy is optimal for atomic k-of-m rolls;
only an exact solve is).

**Fix (ALL non-trivial modes — the assignment is freed wherever its objective is monotone in the element vars).**
The CP-SAT model now picks the random assignment **freely** — only the cardinality constraint, no ordering — and
the active objective drives it to the true optimum. The scorer mirrors it with the matching **exact** assignment
(branch & bound over the ≤4 element values), so the freed model and the re-scored build stay consistent:
- **most-masteries** → MIN objective → `assignMaxMin{Mastery,Resistance}RandomValues` (mastery always; aggregate
  `RESISTANCE_ELEMENTARY` when requested; over the `masteryElementsToMinimize` subset).
- **precision** → CAPPED-sum objective → `assignMaxCapped{Mastery,Resistance}RandomValues`.
- **max-damage** → single element ⇒ assignment is degenerate, left on the greedy path (unchanged).

A simple greedy is optimal for *neither* objective (the deficit-greedy AND a naive max-min water-fill are both
beaten by brute force — `RandomElementAssignmentTest` locks each exact assignment == exhaustive). Consistency is
locked end-to-end: the solver's optimum == the exact-scorer exhaustive optimum on small pools with random items,
for aggregate mastery, the unequal-target multi-specific case, aggregate resistance, AND precision aggregate.

**Result (lvl-245, prefiltered production pool):** aggregate **most-masteries FEASIBLE 204 s → OPTIMAL 3.8 s**
(constraints 10 379 → 2 139), proven optimum **rose 4336 → 4755** (greedy was leaving mastery on the table); the
full unprefiltered pool now even proves (≈72 s) to the same 4755. Aggregate **precision → OPTIMAL 1.6 s**. The
prefilter stays on for multi-element requests (3.8 s vs 72 s), but no longer misses the optimum.

---

## 8. Open questions / next experiments

- [x] **Domain-propagation layer (§6.1)** — **DONE** (see §0). Per-slot reachable domains + tracked builders
      cover the whole perHit + perTurn chain; the free model proves OPTIMAL on lvl 110 AND 245, no global
      constants. Soundness test (loose-declared build, every var's solved value within its recorded reachable
      range) written first and green.
- [x] **Soundness test** — DONE: one PANEL (`…reachable bounds hold across classes, elements, scenarios and
      levels`) over multiple classes/elements, rear+berserk, a multi-element boss, and lvl-245 — so an interval
      under-estimate can't hide in a shape one fixed case never exercised. Runs on a tiny high-stat
      `soundnessStressPool` (drives the chain to end-game magnitudes instantly), so it's ~5 s and STRONGER than
      the old single full-pool CRA-fire-110 lock (~100 s, now removed/folded in).
- [x] **Multi-element / boss provability** — DONE (§7d). In-model splitting is UNPROVABLE (sum of bilinear terms);
      provable per-element enumeration ships instead (each candidate element solved single-element, max over proven
      optima). Bi-element machinery removed.
- [ ] **Dominance pruning** — implement, measure model-size + det-time drop, prove soundness on samples.
      (Now the lever for ~1–3 s, esp. to cut the lvl-245 ~3.5 min proof.)
- [ ] **Warm-start hint** — measure det-time with/without.
- [x] Does the free model still prove with **runes + subs** enabled? **MEASURED: NO** with the old per-stat
      integer rune counts (CRA fire lvl-110, full pool: never proved in **7 min**; runes are the dominant cost —
      subs-only proved in ~66 s, runes-only didn't in 150 s). **FIXED by the single-type rune fold (§9).** Now
      proves in **~82–106 s walltime** / det-time 600, same optimum. (The choosable subs are all fine: 0 are
      conversions, and the 3 `SECONDARY_MASTERIES_AT_MOST` ones have value=0 ⇒ all-elemental, no intra-item mix.)
- [ ] Production (`tuning==null`, wall-clock, cores−1 workers) timing vs the tuning-path numbers here.
- [x] Higher-level pools (245) — proves at `maxDeterministicTime=600` (~3.5 min walltime). Budget/fallback
      threshold tuning (graceful "best found" when a pool can't prove in the wall-clock budget) still open.

---

## 7d. Landed (2026-06-22): multi-element splitting is UNPROVABLE → provable per-element enumeration for bosses

**The question.** A boss request puts several elements in play (`elementResistances` ⇒ `candidateElements()`
returns N). Could the solver pick a *multi-element* build (split AP across elements) when that beats the best
single element, and **prove** it? The user pushed for this ("the solver needs to select a build with multiple
elemental masteries if that's the thing doing the best damage").

**Measured — multi-element splitting does NOT prove, at any arity** (full lvl-110 EPIC pool, det tuning):
- In-model AP split (`Σ_e throughput_e(ap_e)·perHit_e·resFactor_e`, `Σ ap_e ≤ AP`): **311s FEASIBLE** for 3 live
  terms, **240 det-time still FEASIBLE** for 2 terms. Summing the per-element McCormick gaps (instead of `max`-ing
  → one live term) leaves the dual bound uncloseable. The single in-model `max over elements` *also* fails — a
  4-element boss (3 live) ran **562s FEASIBLE** (bundling 3 products in one solve, even under `max`, is too much).
- The gain doesn't justify it anyway: on the default Cra-vs-boss the split's best-found was **~1.4% over the
  proven single-element optimum** — and unproven. Build-independent headroom (equal-mastery upper bound) is
  ~5–14% per class but collapses to low-single-digits after mastery dilution; domain research confirms the
  dominant lever is element *selection*, not intra-turn splitting (the classes that truly mix — Huppermage runes,
  Foggernaut Stasis — use special mechanics our per-element model doesn't represent).

**The provable decomposition (shipped).** A *single*-candidate element solve PROVES in seconds (one bilinear
term). So `MaxDamageSearch` now solves **each candidate element as its own single-element problem** (in parallel)
and takes the max over the proven per-element optima. This is a valid proof of the boss optimum: the global-best
build `B*` has some best element `e*`, and the `e*`-solve finds `argmax_B damage_{e*}(B) ≥ damage_{e*}(B*) =
f(B*)`, so `max_i f(B_i) = max_B f(B)`. Measured all OPTIMAL (wide lvl-1..110 pool: Fire 62s / Earth 41s / Air
178s; narrow minLevel=level pool ≈ 6s each) — faster than the old never-proving 562s, and now reported `proven`.

**Removed.** The external bi-element enumeration (`biElementScenarios`/`paretoFrontierSplits`/`MAX_BI_PROBES`),
the in-model `perTurnDamageScoreBiElement`, `BiElementSplit` + its `maxDamageBiElement` plumbing,
`bestSequencedTurn`/`bestSequencedTurnBiElement`, and `SolverResult.maxDamageBiElement`. `proven =
allElementSolvesProved && !hasResistanceDebuff`; the Sram/Sadida debuff AP-window phase stays (gated, now pinned
to the winning element so each probe is a fast single-element solve), and is the only thing that keeps a result
`maxDamageHeuristicPhases`. Locked by `MaxDamageSearchTest "boss multi-element loop proves the optimum via
per-element enumeration"`. NOTE: `solveProbe` must tie-break toward `isOptimal` (CP-SAT emits the optimal build
un-proven first, then proven — a plain `maxBy{score}` returns the un-proven copy and loses the proof).

---

## 8b. Landed (2026-06-24): the single-type rune FOLD cracks runes+subs provability

**The wall.** With runes+subs ON (the GUI default) the free max-damage solve never proved (CRA fire lvl-110,
full pool: no proof in **7 min**). Isolation: **runes** are the dominant cost (subs-only proved ~66 s;
runes-only didn't in 150 s). The old rune model gave every socketable POOL item one INTEGER count var per rune
stat (0..slots), Σ ≤ slots·equipped — hundreds of integer vars whose branching the proof can't close.

**Two domain levers (sound, shipped, but not enough alone):**
1. `fillSocketsForMaxDamage` — for max-damage the socket cap becomes Σ = slots·equipped (full fill). The generic
   elemental-mastery rune is non-secondary and only raises the (constraint-free) objective, and overshooting a
   required target is never penalised (`requiredConstraintPenaltyFactor` caps actual at target), so the optimum
   always fills its sockets; pinning equality removes the underfill assignments. Cut combinations, didn't prove.
2. `runeMasteryOverCount` — the scenario-mastery `preM` naive sum double-counts runes ACROSS its mastery stats
   (each stat's reach assumes its rune fills every socket, but sockets are shared). Subtract that provable
   over-count (`naiveRuneSum − socketAwareMax`, per-slot best) to tighten M's declared upper bound. Tightened
   the bound, still didn't prove (the integer var COUNT was the wall).

**The fix — single-type rune fold (`createRuneModel`).** Key fact: `RuneType.valueOn` doubles per item-SLOT,
uniform across an item's sockets (NOT per socket), so a rune's value on an item is fixed ⇒ filling an item
entirely with its single best-value type is ≥ any intra-item MIX (the objective is linear in each mastery for a
fixed build; the per-item choice is build-dependent only through which marginal wins). So intra-item mixing is
freedom the optimum never uses. Replace the per-(item,stat) integer counts with ONE BOOLEAN PICK per stat per
item, Σ picks = equipped; a pick contributes `slots·coeff`, leaf seeded 0..1. Collapsing the integers to
booleans is what lets CP-SAT PROVE: **runes+subs now proves in ~82–106 s** (same optimum the count model
reaches but can't certify). Gated to where single-type is provably optimal: max-damage, no forced runes, and no
`SECONDARY_MASTERIES_AT_MOST` sub with value>0 in play (there an intra-item secondary/elemental mix can be
optimal — but every choosable such sub has value=0; a forced one falls back to the count model). The other
modes / no-rune / forced-rune paths keep the count model byte-identical.

**Verification.** `single-type rune fold preserves the max-damage optimum` (fold optimum == count optimum on a
socketed rune pool, via the `forceRuneCountModel` seam) + the rune-aware `max-damage reachable bounds hold`
panel (now socketed + runes) + a head-to-head (fold proves 6598 in 82 s; count reaches the SAME 6598 unproven
in 200 s) + the slow det-time lock `max-damage proves OPTIMAL with runes and sublimations on the full level-110
pool` + a 5-dimension adversarial review (all sound, 0 confirmed issues). Caveat: the fold's optimality is tied
to per-item (not per-socket) doubling — revisit if the rune model ever gains per-socket colours.

**Not "seconds".** Runes+subs is now ~bare-model proof time (~1.5 min); the ~1–3 s target still needs §7
dominance-pruning (separate). A one-off run once showed a best-found 6752 vs the proven 6598 — a pre-existing
PROXY-vs-sequenced-rotation gap (the CP throughput proxy ≠ the displayed rotation DP), not a fold regression
(the head-to-head count run reached 6598 too); closing that gap is separate work.

---

## 9. References
- Engine: `autobuilder/.../genetic/wakfu/WakfuBuildSolver.kt`, external loop `MaxDamageSearch.kt`.
- Memory: `maxdamage-optimum-unprovable.md`. Plan context: `FULL_DAMAGE_MODE_STATUS.md`.
- OR-Tools CP-SAT: McCormick relaxation of `AddMultiplicationEquality`; `linearization_level`,
  `num_search_workers`, `max_presolve_iterations`, `relative_gap_limit`.
