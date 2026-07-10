# Certificate → Production Plan

> **✅ COMPLETE (P0–P6 shipped).** The badge is live in CLI + GUI, backed by the two-tier certificate
> (`CERTIFIER_VERSION` 8 as of 2026-07-09), the E8 rescue construction and the E10 search-time warm-up;
> guards = CI fuzz locks + the nightly lvl-245 ledger oracle. Kept as the architecture/execution record.

**Goal.** Ship the max-damage "proven optimal" badge, powered by the AP-cell certificate, computed
**on the user's machine for arbitrary requests** in seconds-to-minutes (single-core acceptable,
parallel faster). Precomputed data is an optional cache, **not** the strategy.

This plan is written to be executable by an agent without the full campaign context. Follow phases in
order; each phase has explicit acceptance criteria. When in doubt: **an over-count (certificate too
high) is sound but loose; an under-count (certificate below a real build) voids the proof and is the
only fatal bug class.** Bailing to CP-SAT (`Long.MAX_VALUE`) is always a safe answer.

---

## 0. Context — what exists at the time of writing

Branch `claude/optimistic-lewin-03113f`, key commits: `91aea533` (domination fix), `f6059c02`
(certifier production-shape), `c440a515` (3-D frontier), `e12f4ded` (MP honesty), `413f08f4`
(provenance), `cc29414f` (exact negative costs + same-name rings + clamp cells), `4cdcae45`
(below-base AP cells → complete ledger), `23f1452e` (fast-proof plan).

Everything lives in
`autobuilder/src/main/kotlin/me/chosante/autobuilder/genetic/wakfu/WakfuBuildSolver.kt`
inside the `StatBuilder` inner class (line numbers drift — always locate by symbol):

| Symbol | Role |
|---|---|
| `certifyMaxPerHitAtAp(scenario, apTarget)` | wrapper: enumerates the 6 worlds ({A, B=Unraveling, C=CriticalSecret} × {weapons free, restricted}), returns max |
| `certifyMaxPerHitAtApPass(...)` | ONE world's exact DP (the expensive part) |
| `Frontier` | 3-D Pareto set `(di, graw, mp)`, all maximized |
| `perCarrierContribution` / `perCarrierExactValue` / `perSubValue` | per-item optimistic max (VALUE dims) / per-item exact sum (COST dims: AP, crit) / per-sub exact |
| `subAllowedAt(sub, preCrit, preAp, c)` | per-state condition gate (CRIT/AP AT_MOST/AT_LEAST/EXACT) |
| `minSubsToCover(gap, sortedDesc)` | budget-sub slot charging |
| harvest loop (search `apDim is PRE-sub AP`) | reads exact bands, applies budget/slot coupling, takes `best` |
| provenance backtrack (search `Provenance backtrack`) | explain mode — mirrors every stage's option enumeration; **any change to a stage MUST be mirrored here** |
| `certifierObjectivesForTest` seam (search `certifyForTest && candidateElements.size == 1`) | cell → objective map, `-1` = bailed |
| certcap wiring (search `certCellRange`) | experiment: injects per-cell caps into the model (`MaxDamageExperimentConfig.certifierCellCap`, default **false**) |
| objective scaling | `clampedTable[ap] * (maxPerHit / PERHIT_DOWNSCALE) * resFactor / FINAL_DOWNSCALE` — the ONE formula; never re-derive it |

Production orchestration lives in `MaxDamageSearch.kt` (per-element enumeration; `proven =
allElementSolvesProved && !debuff`). GUI state in `gui-compose/.../state/BuildSearchModel.kt`,
strings in `gui-compose/.../i18n/I18n.kt` (`Tr` enum, EN/FR).

### The regression oracle (lvl-245 ledger)

`fireMaxDamageParams(245).copy(useRunes = true, useSublimations = true)`, `fullEpicPool(245)`,
`applyDomination = true`. **These values are deterministic. Any refactor of the exact pass must
reproduce them EXACTLY** (bit-identical integers):

```
1=0  2=1623600  3=2719010  4=3440030  5=6308940  6=7826280  7=8744670  8=10222080
9=11720310  10=12440665  11=13699800  12=14420250  13=15506475  14=15980430
15=16603625  16=16909590  17=0  18=0  19=0  20=0        incumbent = 16909590 (AP 16)
```

Cost at time of writing: ~9 min/cell serial (~3 h full ledger) — that is what this plan fixes.

### The iteration oracle (lvl-110 ledger)

`fireMaxDamageParams(110).copy(useRunes = true, useSublimations = true)`, `fullEpicPool(110)`,
`applyDomination = true` — same shape as the 245 oracle, cheaper to iterate against. Banked from the
full-cells audit (`WAKFU_MAX_DAMAGE_EXPERIMENT_LEVEL=110`, `totalMs≈2002750`). **Deterministic; every
P1/P2 refactor of the exact pass must reproduce these EXACTLY** (`-1` = bailed/infeasible cell,
`0` = feasible-but-empty i.e. no build reaches that AP):

```
0=-1  1=-1  2=-1  3=-1  4=-1  5=545675  6=682200  7=766085  8=902700
9=1023975  10=1092080  11=1193160  12=1243230  13=1304530  14=1310980
15=0  16=0  17=0  18=0  19=0  20=0        max cell = 1310980 (AP 14)
```

### Command crib sheet

```sh
# Full or filtered ledger audit (level defaults to 245; use 110 for fast iteration):
WAKFU_MAX_DAMAGE_CERT_AUDIT=1 [WAKFU_MAX_DAMAGE_CERT_CELLS=15,16] [WAKFU_MAX_DAMAGE_EXPERIMENT_LEVEL=110] \
  ./gradlew :autobuilder:test --rerun --tests "*certifier cell audit*"
# Result line: grep -o "CERT_AUDIT[^<]*" autobuilder/build/test-results/test/TEST-*.WakfuBuildSolverTest.xml

# Provenance (name a cell's winning state):
WAKFU_MAX_DAMAGE_CERT_EXPLAIN=16 ./gradlew :autobuilder:test --rerun --tests "*certifier provenance*"

# Micro-pool verification of a named composition (edit the name set in the test first):
WAKFU_MAX_DAMAGE_PROVENANCE_VERIFY=1 ./gradlew :autobuilder:test --rerun --tests "*provenance build verification*"

# The certifier lock suite (fast, ~25 s — run after EVERY certifier edit):
./gradlew :autobuilder:test --rerun --tests "*AP-cell certifier*"

# Commit gates: ./gradlew ktlintFormat && ./gradlew :autobuilder:test
```

**Ground rules for every phase** (non-negotiable):

1. Never weaken or delete a lock to make it pass. A failing `==` lock after a refactor means the
   refactor is wrong.
2. `Long.MAX_VALUE` (bail) is always sound. When a shape is not supported, bail — never guess.
3. Any change inside a DP stage must be mirrored in the provenance backtrack's `stageOptions`
   (same packing, same prunes, same option set), or `CERT_EXPLAIN` breaks.
4. Cost-cell packing is **biased** (`+apOff`, `+critOff`) so components stay non-negative; the state
   key stores `real + offset`. Do not "simplify" either — mixed-radix decode breaks on negative
   low digits (this exact bug shipped once: crit −10/ap 0 decoded as ap −1/crit +8).
5. Do not commit `common-lib/.../WakfuData.kt` (user-owned). Do not push. One user-facing
   `feat:`/`fix:` subject per commit, details in the body.
6. Wall-clock is thermally unreliable — for perf claims, repeat ≥3× and/or use the state-counter
   from P0.2; for CP-SAT comparisons use 1-worker deterministic tuning (existing harness).

---

## P0 — Baselines (½ session)

**P0.1 Bank the lvl-110 oracle.** The 245 audit is ~3 h; 110 is the iteration fixture.
Run the audit at level 110 (`WAKFU_MAX_DAMAGE_EXPERIMENT_LEVEL=110`, full cells), paste the
resulting `cells={...}` map into this file under "lvl-110 ledger", commit. All P1/P2 refactors are
validated against 110 first, 245 once per phase.

**P0.2 State-count instrumentation.** In `certifyMaxPerHitAtApPass`, behind
`System.getenv("WAKFU_MAX_DAMAGE_CERT_STATS") == "1"`, count: states (dp keys) after each stage,
total frontier points, total `Frontier.add` calls. Print one `CERT_STATS` line per (world, c) or an
aggregate per cell. This is the perf metric that ignores thermal noise. No behavior change; lock
suite + 110 audit must be byte-identical.

**Acceptance:** 110 ledger banked; `CERT_STATS` prints; all locks green; 245 cells 15,16 unchanged.

---

## P1 — Make the EXACT pass fast (1–2 sessions; target ~9 min → ≤ 2 min/cell serial)

The exact pass stays the tier-2 confirmer forever; every µs here also shrinks tier-1 later.

**P1.1 Negative-capable slots first + stage-aware headroom.** Today every transition prunes at
`band + 2·off` because a later stage might apply a negative item. Instead:
1. Order the item stages so slots containing any negative-AP/crit option run FIRST (ring stage
   included — move the ring block before the plain-slot loop; stage order is mathematically
   arbitrary, the DP is a commutative convolution; provenance matches stages by name, order-agnostic).
2. Maintain two running values while iterating stages: `apNegLeft`, `critNegLeft` = the summed
   worst-case negatives of the **not-yet-applied** stages (computed from the same per-slot
   `worstAp`/`worstCrit` helpers used for `apOff`/`critOff`).
3. Replace every intermediate `> band + 2·off` prune with `> band + <neg-left after this stage>`.
   After the last negative-capable stage this is the exact band — the axes stop being 2× wide.
4. `critDim` must still span the worst intermediate: size it `critItemHigh + critOff +
   maxIntermediateOvershoot + 1` where the overshoot is the max over stages of `critNegLeft`;
   simplest correct choice: keep `critDim` as today (harmless — dimension size ≠ visited states)
   and only tighten the prunes.
5. Mirror the prune changes in the backtrack.

*Trap:* the invariant "stored coordinate never underflows 0" relies on offsets summing every slot's
worst negative — do NOT shrink `apOff`/`critOff` themselves, only the *upper* prunes.

**Acceptance:** 110 ledger byte-identical; 245 cells {13,14,15,16} byte-identical; locks green;
`CERT_STATS` states ↓ (expect ~2–3×).

**Status: DONE.** Implemented as a reorderable `itemStages` list (plain slots + weapons + rings),
sorted biggest-negative-capacity first, with `apNegLeft`/`critNegLeft` running totals threaded into
a per-stage `applyCells(cells, apUpper, critUpper)` and the sub-stage prunes (skills + subs run at
the exact band `apCeil+apOff` / `critItemHigh+critOff`). `critDim` unchanged; `apOff`/`critOff`
unchanged. Backtrack pre-filter left at `2·off` (a documented sound superset of the per-stage
forward prune — still admits every real parent, so `CERT_EXPLAIN` is unaffected). Verified: 110
ledger byte-identical (all 21 cells); 245 cells {13,14,15,16} byte-identical; 9 AP-cell locks green.
`CERT_STATS` states at ap=5: unrestricted worlds 5.63 M→3.12 M / 4.70 M→2.64 M (≈1.8×),
weapons-restricted worlds 4.27 M→1.70 M / 3.62 M→1.46 M (≈2.5×).

**P1.2 n-dominance sweep.** After each sub stage, for every `(ap, crit, epic, relic)` group: if the
frontier at `n1` is pointwise dominated by the union of frontiers at `n0 < n1` (fewer sub slots
used, everything else equal-or-better), delete the `n1` points. Implement as: for each key, for each
point, check dominance against points of the same 4-tuple at strictly smaller n. Do it only after
sub stages (items/skills don't change n).
*Soundness:* dropping a dominated point can never under-count — any harvest that used it succeeds at
least as well from the dominating point (n only ever *costs* slots at harvest: `n0 + critGapSubs +
apGapSubs > subCap` is monotone in n).

**Acceptance:** same as P1.1 (ledgers byte-identical, states ↓ expect 1.5–2×).

**Status: ATTEMPTED → REVERTED (nearly inert for this DP; do not retry as-is).** Implemented exactly
as specified (a `sweepNDominance()` after every transition/rarity sub stage, grouping keys by the
`(ap,crit,epic,relic)` 4-tuple = `k / (subCap+1)` and dropping any point ≤ some strictly-smaller-`n`
point on all of `(di,graw,mp)`). It is **sound and byte-identical** (110 all cells + locks green), but
the win is negligible: at lvl-110 ap=5 it removed ~0.001 % of dp keys and only ~2.3 % of frontier
points, and the per-sub-stage O(P²) sweep *regressed* wall-clock (1.59 M→2.00 M ms, back to the
pre-P1.1 baseline). **Why the premise fails:** `n` counts *transition* subs, and every kept transition
sub adds *positive* di/graw (ramp subs trade −DI for +MP). So a higher-`n` state is almost always
genuinely better on some maximized axis than its lower-`n` ancestor — it is not dominated; the `mp`
axis specifically shields ramp subs from ever being swept. n-dominance only fires when a multi-sub
combo is pointwise beaten by a fewer-sub one, which is rare (~2.3 %). The estimated 1.5–2× does not
materialize because the frontier already keeps only non-dominated points per key. If revisited, target
a genuinely redundant axis instead (e.g. P1.3 dense storage is the real lever). Full write-up:
`docs/code-review-followups.md` §B2c.

**P1.3 Dense storage.** Replace `HashMap<Long, Frontier>` with an array indexed by the packed state
(the box `apDim × critDim × 2 × 2 × (subCap+1)` is ≤ ~1–2 M slots; allocate once per (world, c),
reuse via clear-list). Replace `Frontier.pts: ArrayList<LongArray>` with parallel `LongArray`s +
size (struct-of-arrays), grown geometrically. Kill the full-map copy per stage (`nd` prefilled from
`dp`): double-buffer two arrays and copy only touched groups, or iterate keys descending so
in-place updates never read their own writes (items add strictly positive epic/relic/n? — NO:
deltas can be negative on ap/crit, so keep the double-buffer approach).
*Trap:* the provenance snapshots deep-copy `dp` per stage — keep a snapshot path that materializes
the dense arrays into the old map shape (explain mode only, perf irrelevant there).

**Acceptance:** ledgers byte-identical; locks green; wall-clock ≥3× better on the 245 {15,16} audit
(measure 3 runs).

**Status: PARTIAL (cheap carry-forward win shipped; dense arrays still TODO).** The per-stage
carry-forward (`nd` prefilled from `dp` — the "skip this stage" option) re-added every point through
the O(n) dominance-checking `Frontier.add`, even though the source frontier is already non-dominated.
Replaced with `nd[k] = fr.copy()` in all three carry-forward sites (item/skill `applyCells`, normal
transition, rarity sub). Byte-identical (states & points bit-identical to P1.1 across all 6 worlds at
110 ap=5; 110 all cells + 245 {15,16} byte-identical; locks green), and it cut `Frontier.add` calls
~36–40 % and wall-clock 1.59 M→1.13 M ms at 110 (~1.4×), 245 {15,16} ~597→320 s/cell vs the P0
baseline. **Still TODO for the ≥3× target:** the dense packed-array + struct-of-arrays rewrite (kills
the HashMap allocation/boxing + the full-map copy per stage). The `copy()` win stacks under it.

**Status: DONE (dense storage shipped; ~2× combined, not the optimistic 3×).** Replaced
`HashMap<Long, Frontier>` with a `DenseDp` flat `Frontier?[]` indexed directly by the packed state key
(the key is already a mixed-radix index into `apDim × critDim × 2 × 2 × (subCap+1)`), a geometrically
grown `liveKeys` list for iteration/clear, and **two double-buffered stores allocated once per pass**
and swapped each stage — so there is no per-stage map allocation, no `Long` boxing, and no hashing. The
box is sized for the largest crit step (`apDim = apCeil+2·apOff+1`, `maxCritDim` at `c = cEnumMax`); every
smaller-`c` key is `< denseBox` since `critDim(c) ≤ maxCritDim`. Byte-identical: at 110 ap=5 the CERT_STATS
states/points/`add`-calls are **bit-identical to the `copy()` commit** across all 6 worlds (the DP is
provably unchanged), 110 all cells + 245 {15,16} reproduce the oracle, 9 locks green. Wall-clock: 110
1.13 M→0.97 M ms, 245 {15,16} 640 k→577 k ms (~1.1–1.17× over `copy()`; ~2.07× over the P0 baseline).
Provenance snapshots still materialize to the old `HashMap<Long,Frontier>` shape (explain-only path).
**Why not 3× (measured on a non-throttled machine — the numbers are reliable, not thermal noise):** the
remaining floor is the per-stage frontier *copy* (O(states×points) `LongArray` allocation for the
carry-forward). It cannot be shared away — a transition from an `n0` state can land on a carried `n0+1`
state, so sharing that frontier would corrupt `dp` mid-iteration (the plan's own double-buffer note). The
HashMap→dense swap only removed the hashing/boxing/allocation overhead, which is a genuinely small slice
here (~1.1×); dense is still worth keeping (byte-identical, kills GC/alloc pressure on the production
path), but the ≥3× target is not reachable by exact-pass micro-tuning. Closing the gap is P2's job (the
fast tier-1 pass).

---

## P2 — The fast shared pass (1–2 sessions; target: full ledger tens of seconds serial)

New function `certifyAllCellsFast(scenario): LongArray /* cell → maxPerHit upper bound */` next to
(not replacing) the exact pass. Two structural changes vs the exact pass:

> **Implementation notes (derived from the exact pass, 2026-07-03 — bank before building):**
>
> - **Setup sharing.** The apTarget-/c-independent setup is ~350 lines and deeply tangled (terms →
>   per-carrier/per-sub value maps → offsets → sub categorization → skill branches → `cEnumMax`,
>   `maxItemCrit`, `maxStart/PermCrit/Ap`, `minSubAp/maxSubAp`). Prefer **extracting it into a shared
>   `buildCertContext(scenario, convTaken, critSecret, csExcluded, weaponsRestricted)` helper** returning
>   an immutable struct, consumed by BOTH passes — the 245 oracle proves the extraction is byte-identical.
>   Duplicating it instead is faster to write but will drift; the fatal-under-count risk lives in the DP,
>   not the setup, so keep the setup single-sourced. `apHigh/apCeil/apFloor` are apTarget-dependent →
>   compute them per-cell at harvest, NOT in the shared context.
> - **Single-DP crit dimension.** With no c-loop, crit is a full state axis spanning ALL reachable item
>   crit: `[-critOff, maxItemCrit]` (not the per-c band `[-critOff, c-critConst]`). Size
>   `critDimFast = maxItemCrit + 2·critOff + 1` (keep the 2·off intermediate headroom). AP dim as today
>   but sized for the loosest cell (`apCeil` at the highest cell / `cEnumMax`). Reuse the dense
>   double-buffer (`DenseDp`) with the 4-D frontier.
> - **∃-gate recipe (P2.3), exact arithmetic from `subAllowedAt`.** At stage time neither `c` nor
>   `apTarget` is fixed; allow the sub iff SOME `(c ∈ [0,cEnumMax], apTarget ∈ [minCell,maxCell])` makes
>   its condition hold. Per type, using the state's item `preCrit`/`preAp`:
>   `preCombatCritMin(c) = max(critConst+preCrit, c-maxStartCrit)`,
>   `preCombatCritMax(c) = min(c, critConst+preCrit+maxPermCrit)`,
>   `preCombatApMin(apTarget) = max(apConst+preAp, apTarget-maxStartAp)`,
>   `preCombatApMax(apTarget) = min(apTarget, apConst+preAp+maxPermAp)`.
>   - `CRIT_AT_MOST n`: ∃c with `preCombatCritMin(c) ≤ n` ⇔ minimize over c (smallest c) ⇔
>     `critConst+preCrit ≤ n` (c can be pushed down to the item floor).
>   - `CRIT_AT_LEAST n`: ∃c with `preCombatCritMax(c) ≥ n` ⇔ maximize over c (c=cEnumMax) ⇔
>     `min(cEnumMax, critConst+preCrit+maxPermCrit) ≥ n`.
>   - `AP_AT_MOST n`: ∃apTarget with `preCombatApMin ≤ n` ⇔ smallest apTarget (=minCell) ⇔
>     `max(apConst+preAp, minCell-maxStartAp) ≤ n`.
>   - `AP_AT_LEAST n`: ∃apTarget with `preCombatApMax ≥ n` ⇔ largest apTarget (=maxCell) ⇔
>     `min(maxCell, apConst+preAp+maxPermAp) ≥ n`.
>   - `AP_EXACT n`: ∃apTarget with `preCombatApMin ≤ n ≤ preCombatApMax` (test the range endpoints).
>   - unknown types → `true`. Over-allowing is a sound over-count; tier-2 (exact) is the tightener.
> - **Harvest coupling** (`critGapSubs`/`apGapSubs`, `n0+gaps ≤ subCap`, force-taken conv/critSecret
>   gates) is per (cell, c) exactly as the exact pass — reuse `minSubsToCover` unchanged with the
>   per-cell `apHigh` and the per-c crit gap.
> - **Verification path:** single-world plumbing compiles first, but the `fast ≥ exact` lock only
>   becomes meaningful once all 6 worlds are enumerated (exact[a] = max over worlds). Expect to iterate
>   the ∃-gates against the tightness canary (`fast[16]/exact[16] ≤ ~1.10`).

**P2.1 4-D value frontier.** Frontier tracks `(di, m, critM, mp)` — `graw` is NOT pre-folded.
Dominance: all four maximized. Sound for every c simultaneously because
`graw_c = (400+c)·m + 5c·critM` is monotone in `m` and `critM` for all c ≥ 0, and the harvest
evaluates `graw_c` per (cell, c) as plain arithmetic. The c-outer-loop disappears.

**P2.2 One DP for all AP cells.** The AP axis is already a state dimension; run the DP once with
the loosest ceiling (`apCeil` of the highest cell) and harvest every cell from the same final
state set (per-cell band + budget/slot coupling exactly as today).

**P2.3 Condition gates relax to ∃.** `subAllowedAt` reads `(c, apTarget)`, which no longer exist at
stage time. Gate each sub with: allowed iff **there exists** a `(c, apTarget)` in the enumerated
ranges making it allowed (compute the bound arithmetic with the range endpoints — AT_MOST uses the
minimum achievable pre-combat value across the range, AT_LEAST the maximum). This is an over-count
(sound). Do NOT try to be exact here — tier 2 is the exactness.

**P2.4 Harvest.** For each cell a, for each c in `0..cEnumMax`, for each state in the (a, c) band:
`perHit = (dConst + di) × (grawConst_cEff + (400+cEff)·m + 5·cEff·critM)` with the mp-ramp valuation
applied as today (ramp value from state mp; note the ramp's DI is inside `di` for taken subs — in
the 4-D pass keep the same "ramp valued at transition time from state mp" mechanism; it stays sound
because mp on the frontier is exact-or-over).

**P2.5 Locks for the fast pass** (new tests, same file):
- `fast certifier upper-bounds the exact certifier on every cell` — run both at 110 (and the small
  `==` fixtures), assert `fast[a] ≥ exact[a]` for every non-bailed cell. **A single violation is a
  release-blocking under-count.**
- `fast certifier upper-bounds CP-SAT on the coupling panel` — reuse the existing ≥-panel pool,
  assert fast ≥ proven CP-SAT cell-max.
- Tightness canary (non-blocking, printed): `fast[16] / exact[16]` at 110 — track drift; expected
  ≤ ~1.10. If it explodes, the ∃-gates are too loose (see P2.6).

**P2.6 Fallback if the 4-D frontier explodes** (measure with `CERT_STATS`: if avg points/state
> ~8× the 3-D pass): keep the shared-cells change but reinstate a coarse c-grid (every 5th c,
evaluating each state at the grid point ≥ its band — still an upper bound) instead of full 4-D.
Decide by data, not preference.

**Acceptance:** both new locks green; 110 fast ledger computed in < 5 s serial; 245 fast ledger
< 60 s serial; exact pass untouched (245 oracle still byte-identical).

**Status (2026-07-03): core DP shipped — sound, capped, ~74× the exact pass; tightening TODO.**
- Implemented as a `fastAllCellsOut` branch inside `certifyMaxPerHitAtApPass` (reuses the entire
  world-adjusted setup in place — no duplication; the exact path is untouched when the param is null),
  with `certifyAllCellsFast` enumerating the same 6 worlds and max-merging per cell. A shape-level bail
  in any world bails the whole ledger, matching the exact pass's per-cell bail set.
- **Two bugs found by the locks, both fixed:** (1) the crit axis must span `maxItemCrit + maxSkillCrit`
  (the axis accumulates skill crit too — `critItemHigh` is a misnomer); capping at `maxItemCrit` pruned
  real states and under-counted every cell ~0.5 % (caught by the `fast ≥ exact` lock). (2) the unbounded
  4-D frontier **OOM'd the real 110 catalog in 24 s** (the P2.6 risk, confirmed as `Java heap space`) —
  fixed with `Frontier4.CAP = 48`: past the cap, the lowest-ranked points (mid-crit valuation proxy)
  fold into ONE component-wise-max point. Sound: the merged point dominates every folded one and the
  harvest is monotone in all four axes. CAP=128 measured only ~1–5 % tighter at 2.5× the time ⇒ the
  looseness is structural, not the cap.
- **Measured at 110 (real catalog):** full fast ledger **13.1 s** (exact: ~966 s ⇒ ~74×); `fast ≥ exact`
  on every cell vs the banked oracle; panel canary 1.0304. Exact-bailed cells (0–4) get real fast bounds
  (fast is more capable there — the lock skips them).
- **Tightening campaign (measured, ablation-driven):** per-cell ratios started at ~1.21–1.27 (110) /
  ~1.5 (245). Ablations at 110 cell 13 localized it: items+skills alone 1.056, +budget subs 1.158,
  +transitions 1.180, full 1.234 — i.e. the item layer's (m, critM) mixing amplified by `5c·critM` once
  budget crit raises the harvest c. c-bucketed ∃-gates: NO-OP on both real catalogs (every conditional
  DI sub is dominated by unconditional Elemental Concentration) though they take the panel canary
  1.03 → 1.0008. Ring-pair enumeration + skill m/critM split: barely moved the ledger — the 4-D CAP
  fold's component-wise mixing erased upstream tightening.
- **Resolution = P2.6 (data-driven):** replaced the capped 4-D frontier with a **coarse c-grid of 3-D
  passes** — segments over `[0, cEnumMax]` (step `FAST_C_SEGMENT_STEP = 8`, edges merged with kept
  crit-condition thresholds), each folding point graw at its top effective crit (both graw_c
  coefficients grow with c ⇒ sound), 3-D `(di, graw, mp)` frontiers (naturally small — the exact pass's
  shape; no cap, no axis mixing), per-segment ∃-gates (near-exact for free), per-(cell, c) harvest with
  exact constants and the exact per-(a, c) force-taken-conversion gate. Ring pairs + item/weapon raw
  lists hoisted out of the segment loop.
- **Measured (3-D grid):** 110 ledger 20.0 s, ratios **1.012–1.019** on every feasible cell; 245 ledger
  68.5 s, ratios **1.011–1.018** on cells 5–16 (low cells 2–4 ~1.4, irrelevant — an order of magnitude
  below the incumbent). Panel canary 1.0008. **Elimination shape achieved:** vs the incumbent, only
  cells {13, 14} survive at 110 and only cell {16} at 245 — the two-tier P3 orchestrator will confirm
  1–2 exact cells instead of 16. 245 fast time is 8.5 s over the < 60 s acceptance serially; P3's
  world-parallelism (6 worlds) covers it several times over.

---

## P3 — Two-tier orchestrator + parallelism (1 session)

**P3.1 Pre-materialized inputs.** `StatBuilder`'s term builders use lazy caches
(`prePercentCache` etc.) — NOT thread-safe. Build an immutable `CertifierInputs` snapshot
(all term lists, constants, sub entries, per-item Raws per world) **single-threaded**, then run
world passes from it. Verify: temporarily run the 6 worlds of one cell in parallel under
`-ea` + a repeat loop; results must equal the serial run every time.

**P3.2 Orchestrator** `certifyLedger(params, pool, runes, subs, applyDomination): CertLedger`:
1. Fast pass (P2) → per-cell upper bounds (max over 6 worlds; worlds parallel,
   `min(6, cores−1)` threads).
2. Cells with `fast[a] ≤ incumbentSoFar` (caller-supplied, may be absent → skip elimination) are
   done. For the remaining cells (typically 1–3), run the EXACT pass per (cell × world) in a work
   queue over the same thread pool.
3. Return `CertLedger(cellObjectives: Map<Int, Long> /* objective units via the ONE scaling
   formula */, bailedCells: Set<Int>, tier2Cells: Set<Int>, maxCellObjective: Long?)` — with
   `maxCellObjective = null` if any cell bailed.
Determinism: results must be independent of thread scheduling (each (cell, world) task is a pure
function; the merge is max — verify by running twice and diffing).

**Acceptance:** `certifyLedger` at 245 reproduces the oracle exactly through the exact tier (force
tier-2 on all cells via a test flag); with the fast tier engaged, end-to-end wall-clock ≤ 3 min on
the dev machine, ≤ target 10 min single-thread (set threads=1 to measure).

### P3 execution plan (in depth, 2026-07-03 — follow the increments IN ORDER, commit after each)

Everything below was verified against the code at commit `b977fa78`. Locate by symbol, never by line.

**Context an executor needs (all in `WakfuBuildSolver.kt` unless said otherwise):**
- `certifyAllCellsFast(scenario, cellCount): LongArray` — the fast tier-1 pass. Enumerates the 6
  worlds ({base, conversion, critSecret} × {weapons free, restricted}) SERIALLY via
  `certifyMaxPerHitAtApPass(..., fastAllCellsOut, fastCellCount)` and max-merges per cell. Returns
  `Long.MAX_VALUE` per cell when a world bails (all-or-nothing in practice — bails are shape-level).
- `certifyMaxPerHitAtAp(scenario, apTarget): Long` — the exact tier-2 pass for ONE cell (same 6
  worlds, `Long.MAX_VALUE` = bail).
- The ONE objective-scaling formula (never re-derive):
  `clampedTable[ap] * (maxPerHit / PERHIT_DOWNSCALE) * resFactor / FINAL_DOWNSCALE` — see the
  `certifyForTest` seam in `buildMaxDamageObjective` for the exact usage; `clampedTable`/`resFactor`
  are in scope there.
- Existing test accessors: `certifierCellObjectivesForTest` (exact map),
  `certifierExactAndFastCellObjectivesForTest` (exact + fast maps). Test fixtures:
  `fireMaxDamageParams(level)`, `fullEpicPool(level)`, `couplingPanel()` in `WakfuBuildSolverTest.kt`.
- **Measured reference data** (assert against these): banked 110/245 exact ledgers (§0); fast 110 =
  20.0 s, cells 1.012–1.019× exact; fast 245 = 68.5 s, 1.011–1.018× on cells 5–16. With incumbent =
  the oracle max, surviving cells are exactly **{13, 14} at 110** and **{16} at 245**.

**P3.0 — API shape (fix before coding).** New types next to the solver (internal for now; P4.1
makes the production API):

```kotlin
internal class CertLedger(
    val cellObjectives: Map<Int, Long>, // OBJECTIVE units (the ONE formula) — comparable to CP-SAT
    val bailedCells: Set<Int>,          // cells with NO sound bound (fast pass bailed)
    val tier2Cells: Set<Int>,           // cells confirmed by the exact pass
    val maxCellObjective: Long?,        // max over cellObjectives; null iff bailedCells non-empty
)
```

Semantics (these are load-bearing — implement exactly):
- Elimination: cell `a` is DONE when `fast[a] ≤ incumbentObjective` (incumbent is feasible, cert is
  an upper bound ⇒ no build in `a` beats it). `incumbentObjective == null` → skip elimination (all
  non-bailed cells go to tier 2) — production always passes one; only tests omit it.
- `cellObjectives[a]` = exact value for tier-2 cells, fast value for eliminated cells. Mixing is
  sound: both are upper bounds; eliminated cells only ever need `≤ incumbent`.
- If the EXACT pass bails on a surviving cell (`Long.MAX_VALUE`), KEEP the fast value (it is still a
  sound certificate) and leave the cell out of `tier2Cells` — do NOT mark it bailed. `bailedCells`
  is only for cells with no sound bound at all (fast bail).
- `forceTier2All: Boolean` test flag: exact pass on every non-bailed cell, ignore the incumbent —
  this is how the oracle-equality lock runs.

**P3.1 — Parallel-safety by warm-once (pragmatic; the full `CertifierInputs` extraction is NOT
required).** Facts verified in code: `StatBuilder`'s term builders lazily create **CpModel vars** on
cache miss (`prePercentCache`, `preSubCache`, `preCombatCache`, `damagePreMasteryTermsCache`,
`actualCache`, `elementCache`, `appliesVarCache`, `elementDiTermsByMastery`,
`bestElementStrongestCache`, `subDerivedVars`, `perStatStepVarCache` — declared together near the
top of `StatBuilder`). `CpModel` is NOT thread-safe ⇒ a cache MISS inside a parallel pass corrupts
the model. But all passes for one scenario touch the SAME cache keys (worlds differ only in
arithmetic — `convTaken`/`critSecret`/`weaponsRestricted` filter/zero terms AFTER retrieval), so:
1. Run ONE world's pass serially first (warm-up — populates every cache key the other passes read).
2. Then run the remaining worlds in parallel: cache HITS only ⇒ pure reads of `HashMap`s that no
   one mutates ⇒ safe.
3. **Mandatory verification test** (new, CI-runnable, small pool — reuse `couplingPanel()`):
   repeat ≥ 20×: run the 6 worlds' fast passes in parallel (fixed thread pool), assert the merged
   ledger equals the serial `certifyAllCellsFast` result EVERY iteration. If this flakes even once,
   parallelism ships OFF (threads = 1) — correctness first; revisit with the full snapshot refactor.
4. `Frontier.statsAddCalls` is a shared static counter — it races under parallelism. Do NOT
   synchronize the hot path: document that `CERT_STATS` numbers are only meaningful at threads = 1
   (the audits run serially anyway).

**P3.2 — Orchestrator, in two committable increments:**
- **Increment A (serial, all correctness):** `certifyLedgerForTest(params, equipmentsByItemType,
  runes, sublimations, applyDomination, incumbentObjective: Long?, forceTier2All: Boolean = false)`
  built like `certifierExactAndFastCellObjectivesForTest` (one `buildModel`, read the seam maps) —
  but computing: fast ledger → elimination → exact per survivor → `CertLedger`. Wire a
  `certifyLedgerForTest` path through the seam (a new flag like `certifyAllApForTest`, or simplest:
  compute inside the existing `certifyForTest` block behind a nullable incumbent parameter).
  Locks (new tests):
  1. `certifyLedger forceTier2All reproduces the exact map` — at the panel: equals
     `certifierCellObjectivesForTest` for every non-bailed cell; `tier2Cells` = all of them.
  2. `certifyLedger eliminates below the incumbent` — panel, incumbent = the exact max: assert
     every eliminated cell has `fast ≤ incumbent`, every survivor got a tier-2 exact value, and
     `maxCellObjective ≥ incumbent` (badge-condition compatibility).
  3. Update `docs` + this file's §P3 status with measured 110 survivors ({13, 14} expected).
- **Increment B (parallel):** thread pool `min(6, cores − 1)` (plain
  `Executors.newFixedThreadPool` + `invokeAll` — deterministic collection; avoid coroutines here).
  Parallelize (a) the 5 non-warm-up world fast passes, (b) the surviving `(cell × world)` exact
  passes as a work queue (`certifyMaxPerHitAtApPass` calls with explicit world args — mirror the
  world enumeration in `certifyMaxPerHitAtAp`). Merge = per-cell max (order-independent).
  Determinism lock: run the panel ledger twice with threads = 4 and diff the maps; plus the P3.1
  parallel-equality test. Default `threads` parameter: `min(6, cores − 1)`, overridable to 1.

**P3.3 — Acceptance measurements (bank the numbers in this file):**
- `forceTier2All` at 245 cells {15, 16} == the §0 oracle values (exact tier byte-identical through
  the orchestrator).
- End-to-end 245 ledger with `incumbentObjective = 16909590`: expect `tier2Cells == {16}`,
  wall-clock ≤ 3 min at default threads, ≤ 10 min at threads = 1 (measure once each; the user's
  machine is NOT thermally throttled — wall-clock is trustworthy).
- 110 end-to-end with incumbent = 1310980: expect `tier2Cells == {13, 14}`.

### P3 status (execution log)

**P3.0 + P3.1 — DONE** (commit `feat: warm-once parallel-safe fast certifier + CertLedger API`).
`CertLedger` shipped with the semantics above. `certifierWorlds()` extracted (mirrors
`certifyMaxPerHitAtAp`, which is untouched) and `certifyAllCellsFast(scenario, cellCount, threads)`
rebuilt on it with a warm-once parallel path: one world runs serially to populate every lazy
CpModel-var cache key, the rest run as pure reads on a fixed pool. The mandatory parallel-equality
lock (`fast certifier parallel pass equals the serial pass every iteration`, 20× threaded-vs-serial
on the 6-world coupling panel) is **green and has not flaked** — parallelism is proven CORRECT.
Default `threads = 1` keeps every existing audit byte-identical. (The production *default* ends up
serial anyway for a memory reason unrelated to correctness — see P3.3.)

**P3.2 Increment A — DONE** (serial orchestrator). `certifyLedger(...)` computes: fast bound every
cell → eliminate cells with `fastObj ≤ incumbent` → exact-confirm survivors → assemble `CertLedger`
(exact value for confirmed cells, fast value for eliminated / exact-bailed cells). Two locks green:
`certifyLedger forceTier2All reproduces the exact per-cell map` (tier-2 == exact-confirmed cells,
values bit-identical) and `certifyLedger eliminates cells at or below the incumbent`. **Measured 110
end-to-end** (`incumbent = 1310980`, `threads = 1`): `tier2Cells = {13, 14}` (exactly the P2
elimination shape), `bailedCells = {}`, `maxCellObjective = 1310980` == the oracle max, and the two
tier-2 cells reproduce the §0 oracle exactly (13 = 1304530, 14 = 1310980). Serial wall-clock
`totalMs ≈ 164968` (≈ 2.75 min: ~20 s fast + the two exact cells). The exact tier is unchanged —
only the orchestrator calls it.

**P3.2 Increment B — DONE** (parallel path, shipped OFF by default). The exact tier-2 confirmation
fans out over a `(cell × world)` work queue (`Executors.newFixedThreadPool` + `invokeAll`); the
per-cell merge is an order-independent `max`, identical to `certifyMaxPerHitAtAp`. The determinism
lock (`certifyLedger parallel result equals the serial result and is deterministic`: a `forceTier2All`
panel ledger at `threads = 4` equals the serial one and is run-to-run identical) is green, alongside
the 20× fast parallel-equality lock — so the parallel path is **correct**. `CertLedger` is a data
class; `certifierDefaultThreads()` centralizes the production default.

**P3.3 — Acceptance measured + a memory finding (2026-07-03).** All correctness targets met; the
`≤ 3 min` parallel target is NOT safely reachable and parallelism ships OFF by default:

| Run (serial, `threads = 1`) | Result | Wall-clock |
|---|---|---|
| 110 end-to-end, `incumbent = 1310980` | `tier2 = {13, 14}`, `max = 1310980` == oracle; cells 13/14 == oracle | `totalMs ≈ 164968` (2.75 min) |
| 245 end-to-end, `incumbent = 16909590` | `tier2 = {16}`, `max = 16909590` == oracle | `totalMs ≈ 344281` (5.7 min) |
| 245, `incumbent = 16603624` (forces {15,16} to survive) | `tier2 = {15, 16}`; cell 15 = 16603625, cell 16 = 16909590 — **both == the §0 oracle** | `totalMs ≈ 639605` (10.7 min) |

The third run is the `forceTier2All`-at-{15,16} acceptance: rather than exact-confirm all 21 cells
(~100 min serial), an incumbent just below cell 15 leaves exactly {15, 16} surviving, so the exact
tier confirms both and they reproduce the oracle. `bailedCells = {}` in every run (245 has no bailed
cell; 110's cells 0–4 are eliminated below the incumbent and carry sound fast values).

**Memory finding (why the default is serial).** With the production default `min(6, cores − 1)`, the
245 end-to-end and `forceTier2All` runs **OOM a stock ~4 GB heap** (`OutOfMemoryError` in
`Frontier.add`): a single level-245 exact DP holds ~0.7–1 GB of frontier `LongArray`s, so fanning a
survivor cell's 6 worlds across threads needs ~4–6 GB of *live* heap at once. The serial tier does
them one at a time and never exceeds one DP. Correctness/robustness first ⇒ `certifierDefaultThreads()`
returns **1**; the parallel path stays implemented, correct and locked (panel scale), opt-in via an
explicit `threads > 1` on a large heap. The `≤ 3 min` target inherently needs the exact-tier
fan-out that OOMs, so it is deferred to a future **memory-aware scheduler** (bound concurrent DPs by
free heap, or shrink the exact-DP footprint) — tracked here, not a P3 blocker. Serial `≤ 10 min` is
comfortably met (5.7 min at 245).

**P4 hooks to leave in place (design now, wire in P4):** `incumbentObjective` is a parameter (P4
calls the ledger DURING/after search with the live incumbent for early-stop); the ledger is
per-element by construction (the model is per-element) so boss-mode element elimination is just
"compute the cheap fast ledger for element X before committing to its full solve".

---

## P4 — Production wiring (1–2 sessions)

**P4.1 Engine API.** Expose the certificate from the solver side (companion function on
`WakfuBuildSolver`, NOT a test seam): builds the model once (`buildModel` — the certifier needs
`StatBuilder`'s terms; note this initializes OR-Tools natives, which production has already
warmed), runs `certifyLedger`. Signature mirrors `certifierCellObjectivesForTest` but returns
`CertLedger`.

**P4.2 `MaxDamageSearch` integration.** Read `MaxDamageSearch.kt` first (`solveProbe`,
`probePlan`, the `proven` flag, per-element enumeration). Wiring:
- After the per-element search completes (keep it sequential initially — simplest and the badge is
  async anyway), compute the element's `CertLedger`.
- **Badge condition (exact semantics):** `provenByCertificate = ledger.maxCellObjective != null &&
  incumbentObjective >= ledger.maxCellObjective`. (≥, not ==: cert ≥ true max per cell, incumbent
  feasible ⇒ incumbent optimal. Equality is expected but not required.)
- **Self-check (mandatory):** the incumbent's own cell must satisfy
  `ledger.cellObjectives[incumbentAp] >= incumbentObjective`. If violated → the certifier
  under-counted on live data → log loudly, set `provenByCertificate = false`, never crash.
- "Proven within X%": when not proven, `X = maxCellObjective / incumbentObjective − 1` (only if no
  cell bailed). Expose both on the result type the GUI consumes (extend `SolverResult` metadata or
  the `MaxDamageSearch` result — follow the existing `proven` plumbing).
- Multi-element/boss composition: unchanged — overall proven iff every element solve proven; the
  certificate is per-element. Resistance shares certificates: cache the per-cell `maxPerHit`
  (resistance-free) and rescale with the ONE formula per boss.
- Forced runes / forced subs present → skip the ledger entirely (certifier bails anyway): badge
  absent, tooltip "proof unavailable with forced runes/sublimations".

**P4.3 Session cache.** In-memory map keyed by
`(WakfuData.VERSION, CERTIFIER_VERSION /* new Int constant — bump on ANY certifier change */,
level, element, scenario-shape fields (rangeBand, orientation, berserk, healing, critCapPercent),
maxRarity, useRunes, useSublimations, sorted excluded-item ids hash, sorted forced-item names)` →
per-cell `maxPerHit` array. Resistance and search duration are NOT in the key. Disk persistence is
a stretch goal — do memory-only first.

**P4.4 GUI.** `BuildSearchModel`: after search completion, launch ledger computation on
`Dispatchers.Default`; UI state gains `proofState: {None, Proving, ProvenOptimal, ProvenWithin(x),
Unavailable}`. `StatsPanel`/`TopBar` (find the existing proven/optimal indicator via grep
`isOptimal` in `gui-compose`): render the badge + tooltip. Add `Tr` entries (EN/FR):
"Proven optimal", "Proving optimality…", "Proven within %s%%", "Proof unavailable (forced
runes/sublimations)". Screenshot smoke test must still pass
(`WAKFU_COMPOSE_SCREENSHOT=/tmp/out.png ./gradlew :gui-compose:run`).
**Run `:gui-compose:test`** — `BuildSearchModelE2ETest` asserts the rejection surface (known gotcha).

**P4.5 CLI.** Where the CLI prints the final result (grep `isOptimal` / result rendering in
`autobuilder/.../Main.kt`), append the proof line: `Proven optimal (certificate)` / `Proven within
X%` / nothing when unavailable.

**Acceptance:** GUI default search at 245 (runes+subs on) shows "Proven optimal" within ~2 min of
search end on the dev machine; forcing a rune/sub hides the badge; boss-mode auto shows per-element
proofs composing as before; full `./gradlew test` green (this is what CI runs).

### P4 status (execution log, 2026-07-03) — DONE (5 commits)

- **P4.1** `WakfuBuildSolver.maxDamageCertificate(params, pool, runes, subs, applyDomination,
  incumbentObjective, threads)` — production entry (not a test seam): one `buildModel` + `certifyLedger`,
  returns a public `CertLedger?` (null for multi-element / non-max-damage / bailed shapes). `CERTIFIER_VERSION`
  (Int = 1) added. Lock: production API == the orchestrator seam.
- **P4.2** `MaxDamageSearch.proveOptimality(baseParams, pool, runes, subs, result)` →
  `MaxDamageProof.{ProvenOptimal, ProvenWithin(fraction), Unavailable}`. `result.isOptimal` short-circuits
  (no certificate); else `incumbent = result.maxDamageObjective` vs `ledger.maxCellObjective`
  (`≥` ⇒ optimal, else `ProvenWithin(maxCell/incumbent − 1)`); mandatory self-check
  `cellObjectives[incumbentAp] ≥ incumbent` (else log + Unavailable). Unavailable for forced runes/subs,
  **required-stat targets** (their penalty multiplier makes the objective non-comparable to the damage-only
  certificate — the common max-damage search has EMPTY targetStats, so the units line up directly), or an
  un-proven boss. `SolverResult.maxDamageObjective` (raw CP-SAT objective) plumbed through. Lock: strip
  `isOptimal` on a real optimum ⇒ the certificate re-proves it; lower incumbent ⇒ ProvenWithin; forced sub ⇒
  Unavailable.
- **P4.3** `MaxDamageCertificateCache` — memory-only, thread-safe memoization of `maxDamageCertificate`,
  conservatively keyed (whole params minus duration/stop/AP-pin/workers + data & CERTIFIER_VERSION + sorted
  pool/rune/sub ids + incumbent). Over-keys safely; a hit is never stale. The resistance-free `maxPerHit`
  sharing across bosses is deferred (needs the orchestrator to expose raw per-cell `maxPerHit` + per-element
  boss certs). Lock: identical inputs ⇒ same cached instance; different incumbent ⇒ distinct entry.
- **P4.4 GUI** — `UiState.proofState: ProofState {Idle, Proving, ProvenOptimal, ProvenWithin(x), Unavailable}`;
  `BuildSearchModel` runs the proof in its own `proofJob` after a max-damage search (off the critical path),
  applying the verdict only while the shown build is unchanged. `StatsPanel.MatchHero` renders it: "proven"
  when CP-SAT **or** the certificate proved, "Proving optimality…", "Proven within X%", or the forced-runes/subs
  reason. New EN/FR `Tr` strings. `:gui-compose:test` (incl. `BuildSearchModelE2ETest`) + screenshot smoke green.
- **P4.5 CLI** — `WakfuBestBuildFinderAlgorithm.proveMaxDamageOptimality(params, result)` (the single
  production proof entry both front-ends call; rebuilds the same pool/runes/subs) drives the CLI proof line.
  **Verified live**: a lvl-35 CRA fire max-damage search prints `Proven optimal (certificate)` after the
  rotation.
- **Deviation from acceptance:** the "within ~2 min" GUI target inherits P3.3's serial default (parallelism
  OOMs at 245); at 245 the async badge takes ~5 min serial. The badge still appears (async, non-blocking) —
  latency, not correctness. Boss/multi-element proofs via the certificate are deferred (composition unwired);
  boss still relies on CP-SAT's per-element `isOptimal` as before.

---

## P5 — Coverage extensions (1 session, optional but cheap)

**P5.1 Forced items → slot pinning (restores exactness).** In `certifyMaxPerHitAtApPass`, when
`params.forcedItems` is non-empty: for each forced French name, restrict that slot's option list
(`itemsByType` / ring entries) to the items matching the name (all rarities — the model forces
`Σ same-named ≥ 1`, rarity stays free). A forced ring pins ONE of the two ring picks: implement by
seeding the ring stage with the forced ring's options as a mandatory first pick (and same-name
exclusion vs the second). Also delete the empty option for that slot ("none" must not be offered).
Domination is already off with forced items (`dominationShape` returns null) — pool is bigger,
certifier slower; acceptable.
*Locks:* extend the negAP/same-name `==` test with a `forcedItems = listOf("Twin")` variant
asserting cert == pinned CP-SAT optimum.

**P5.2 Other shapes audit.** Run the fast+exact ledger for: level 110/200/230 CRA fire; one melee
class scenario (e.g. Iop earth MELEE band); one healing/berserk scenario. Bank the numbers in this
file. Purpose: catch silent bails (a `-1` cell on a default shape = a supported-shape gap; fix or
document).

**P5.3 Forced subs (only if demanded).** Generalize the force-take world machinery: a forced
modelable sub becomes `convTaken`-style forced in EVERY world (charge its slot, gate its condition,
apply its terms unconditionally). Scripted/non-modelable forced subs keep the bail. This is the
most delicate extension — skip unless users ask; the badge-absent behavior is honest.

### P5 status (execution log, 2026-07-03)

- **P5.1 forced-item slot pinning — DONE (non-ring/non-weapon slots).** In `certifyMaxPerHitAtApPass` the
  certifier now restricts a forced SINGLE-OCCUPANCY slot to the forced-name options and makes its exact-pass
  stage MANDATORY (no "skip slot" carry-forward in `applyCells`), so the exact ledger equals the pinned CP-SAT
  optimum even when the forced item is suboptimal. `perCost(keepWorthless=true)` keeps a null forced option so
  a mandatory slot never empties to a `0` under-count; a mandatory stage with no in-band cell BAILS. Only the
  EXACT pass is pinned — the fast pass stays loose-but-sound (`fast ≥ exact` holds), which is all the tier-2
  orchestrator needs. **Forced RINGS and WEAPONS bail** (badge honestly absent): a forced ring leaves its
  SECOND slot free (any distinct-name ring), which restricting the whole ring pool can't express without
  under-counting a `forced + free-ring` build; the combined weapon slot bakes in explicit empties. A sound
  two-pick ring rework is deferred. Locks: `certifier matches pinned CP-SAT with a forced item` (per-cell `==`,
  with a load-bearing check that forcing the worse item lowers the optimum) and `certifier bails on a forced
  ring`. **Oracle byte-identical**: the full lvl-110 exact ledger reproduces §0 exactly (non-forced path
  unchanged), `totalMs ≈ 1.008 M` (≈ the known ~0.97 M baseline — no perf regression).
- **P5.2 other-shapes audit — DONE, no gaps.** Fast ledger over CRA-fire lvl 110/200/230, Iop earth MELEE
  200, CRA fire berserk 200, CRA fire healing 200 (`manual max-damage shape audit`, env
  `WAKFU_MAX_DAMAGE_SHAPE_AUDIT=1`): **every shape has `bailed = {}`** — no silent supported-shape gap. Fast
  times 26–69 s/shape. (Cells 0–4 = 0 are below-base-AP feasible-but-empty, not bails.)
- **P5.3 forced subs — ASSESSED → DEFERRED (honest bail kept).** The certifier bails on ALL forced subs at
  a single, deliberate guard: `if (subModel.forced.isNotEmpty()) return Long.MAX_VALUE` in
  `certifyMaxPerHitAtApPass` ("Forced subs must apply unconditionally and bind their carrier — not modeled").
  A tempting one-line relaxation — treat a forced sub as OPTIONAL and rely on the certifier maximizing over
  take/not-take (a superset ⇒ sound upper bound, tight when the sub is beneficial) — does NOT hold up:
  (a) `isModelableSublimation` requires `solverChoosable`, so it can't cleanly gate arbitrary forced subs;
  (b) it is not established that a forced sub's terms even enter the certifier's term lists (it was written
  assuming forced subs are absent), so a beneficial forced effect the certifier can't see would UNDER-count
  (fatal); (c) epic/relic carrier binding. A correct+tight implementation is the plan's own "most delicate
  extension" (force-apply terms + charge slot + gate condition + bind carrier, per sub shape) in the most
  bug-prone certifier area. Given the narrow benefit (badge on forced-sub searches) vs the fatal-under-count
  risk, the explicit bail stays — `proveOptimality` returns Unavailable (badge absent), which is sound and
  honest exactly as the plan prescribes. A future attempt must start by proving forced-sub terms are captured
  (or bail per non-captured shape) and lock `certExact == pinned CP-SAT` across flat / conversion / conditional
  / per-stat-step forced subs before wiring it on.

### P5.3 execution plan (in depth, 2026-07-03) — SUPERSEDES the deferral above (user demanded)

P5.3 is now demanded, so the deferral no longer applies. The assessment's concerns are answered
below — every design decision is already made. Read this whole section before touching code, then
execute the increments IN ORDER, committing each only when its listed locks are green. Do not
re-derive decisions; when a case is not covered here, BAIL for that shape (always sound) and note it.

**⚠️ THE ONE TRAP (read twice — this is the assessment's concern (b), sharpened).** P5.1 left the
FAST pass unpinned because pinning items only RESTRICTS the space, so the untouched fast pass stayed
a superset and `fast ≥ exact` held for free. Forcing a sub ADDS value. A fast pass that does not
credit a forced sub's beneficial terms sits BELOW real builds — a fatal under-count on the
ELIMINATED cells (which never reach tier 2 and are "certified" by fast alone). So BOTH passes must
credit every forced sub; the fast pass may credit more loosely (unconditionally), never less.

**Sound model of a forced modelable sub F (all decided):**
- **Slot charge (both passes):** `subCap -= 1` per forced sub, at the existing derivation site
  (`val subCap = MAX_SUBLIMATIONS − (convTaken/critSecret ? 1 : 0)`, ≈ L5362). Exact: CP-SAT's
  `≤ MAX_SUBLIMATIONS` cap counts F's pinned var. `subCap < 0` already bails.
- **NORMAL F:** also consumes one ≥3-socket carrier — the certifier's carrier assumption is
  aggregate and deliberately over-counted (sound); no extra work needed.
- **EPIC/RELIC F:** spends the dedicated epic (relic) sub slot AND requires a carrier: harvest only
  states with the epic (relic) DP axis ≥ 1. Discarding epic=0 states is EXACT, not a restriction —
  in the forced space every real build hosts the carrier (`gateSublimationsOnCarrierItems` forces
  it), so no real build maps to a discarded state. Since Σ epicSub ≤ Σ epicItems ≤ 1, a forced EPIC
  sub also EXCLUDES every other epic sub from the optional pools (same for RELIC).
- **FLAT F:** fold its decoded per-effect values into the pass CONSTANTS (DI → the constant DI fold,
  mastery → the graw base, crit → `critConst`, AP → `apConst`, MP → the mp base) — the `convTaken`
  precedent: worlds apply forced specials by adjusting constants, not by DP stages. Route forced
  crit/AP through the existing permanent/start split (`permanentSubTermsByStat` — forced vars ARE in
  `subModel.subVars`, so their permanent terms are already visible there). Getting the split wrong
  breaks OTHER conditional subs' pre-combat gates (`maxStartCrit`/`maxPermCrit` etc.).
- **STATIC_CONDITIONAL F (condition ∈ SUPPORTED_SUB_CONDITIONS):** the EXACT pass gates the credit
  per state with the same pre-crit/pre-AP windows the choosable transitions use (conditions read
  PRE-sub stats; AP/crit are DP axes ⇒ exact). A failed gate means credit ZERO — but the slot stays
  charged (an equipped sub with an unmet condition is inert yet socketed). The FAST pass credits
  unconditionally (sound; segment `subAllowedExists` tightening is optional).
- **CONVERSION F:** run ONLY the `convTaken = F` worlds (drop the base worlds, keep the weapon
  variants). **CRITICAL_SECRET-like F:** only the cs worlds. ⚠️ CONFLICT BAIL: forced conversion
  with any CS-like sub present (choosable or forced), or forced CS with any conversion present —
  today's enumeration has NO (conv+cs) world and adding one is out of scope ⇒ `Long.MAX_VALUE`.
  (>1 conversion already bails.)
- **Forced sub with a NON-modelable shape** (COMBAT_CONDITIONAL, unsupported condition,
  null-conversion): bail — per-sub shape check, replacing the blanket guard.
- **Forced best-element-concentration / per-stat-step subs:** ride the existing optimistic DI folds
  (sound over-count, documented at the current guard site); NOT in the `==` lock set.
- **Exclude forced subs from every OPTIONAL pool** (`keptSubs` transitions, crit/AP budget lists):
  they are already taken; leaving them in double-counts (sound, but kills the `==` locks). Reuse the
  same per-sub Raw decode the pools use to obtain F's values for the constants credit.

**Increments (each committable, `ktlintFormat` + listed locks green; never weaken an existing lock):**

- **Inc 0 — audit + refactor, behavior IDENTICAL.** (1) Split `isModelableSublimation` into a pure
  shape predicate `isModelableSubShape(sub)` (kind + supported-condition checks only) and the
  choosable-policy wrapper (solverChoosable + `dropsDamageWithNoMasteryToProtect` + the
  best-element single-element rule). (2) Audit EVERY certifier consumption of `subModel.subVars.keys`
  (`rtk proxy grep -n "subModel.subVars.keys" WakfuBuildSolver.kt` — world enumerations ×3 incl.
  `certifierWorlds`, `keptSubs`, MP-ramp detection, budget lists) and bank a forced-vs-choosable
  decision table HERE. The blanket `subModel.forced.isNotEmpty()` bail (≈ L4974) STAYS. Locks: full
  existing suite green, lvl-110 oracle byte-identical.
- **Inc 1 — forced FLAT.** Constants credit + subCap decrement + epic/relic harvest gate +
  optional-pool exclusion, in BOTH passes; narrow the blanket bail to "any forced sub whose shape is
  not yet handled". Locks: (a) NEW `certifier matches pinned CP-SAT with a forced FLAT sub` —
  per-cell `==` on the coupling panel forcing `FlatDi`, and separately `EpicDi` (proves the carrier
  gate); (b) NEW load-bearing check mirroring P5.1's: add a panel sub with a NEGATIVE flat DI effect,
  force it, and assert the certified optimum drops below the free-pool certificate on some cell
  (proves the slot charge + negative terms really bind); (c) the `fast ≥ exact` lock re-run on a
  forced fixture; (d) lvl-110 oracle byte-identical (non-forced path untouched).
- **Inc 2 — forced STATIC_CONDITIONAL.** Exact per-state gate + unconditional fast credit. Locks:
  per-cell `==` forcing `CondDi` (CRIT_AT_MOST 30); Inc 1 locks stay green.
- **Inc 3 — forced CONVERSION + CRITICAL_SECRET.** World filtering + the conflict bails. Locks:
  per-cell `==` forcing `UnravelPanel`; per-cell `==` forcing `CritSecretPanel`; forced-conv with a
  CS-like sub present → every cell bails; forced COMBAT_CONDITIONAL → every cell bails (regression
  of the narrowed guard).
- **Inc 4 — production wiring.** Remove the forced-subs early-return in
  `MaxDamageSearch.proveOptimality` (the certificate now decides: bail ⇒ null ledger ⇒ Unavailable);
  bump `CERTIFIER_VERSION` to 2 (P5.1 should have bumped it — harmless for the in-memory cache, but
  keep the discipline); update §P4.2's gate list here + the GUI `PROOF_UNAVAILABLE_FORCED` copy
  (forced SUBS can now prove — name runes only, or go generic); add the P5.3 status entry. Locks:
  the P4.2 `proveOptimality` lock's forced-sub case flips to assert the certificate path (a forced
  modelable sub on the tiny pool ⇒ ProvenOptimal; a forced non-modelable one ⇒ Unavailable); full
  `:autobuilder:test` + `:gui-compose:test` green.

**Acceptance (bank the numbers here):** all forced coupling-panel locks green; lvl-110 production
fixture + one real forced FLAT DI sub end-to-end `certifyLedgerForTest` with the free-run incumbent
(expect runtime within ~2× the 110 baselines: ~27 s fast, ~2.75 min end-to-end); lvl-110 non-forced
oracle byte-identical.

**Hard rules (unchanged):** an under-count is the ONLY fatal bug class — when in doubt, bail
(`Long.MAX_VALUE` is always sound); never weaken or delete a lock to make it pass; NEVER commit
`common-lib/.../WakfuData.kt`; don't push; Gradle test stdout is in the JUnit XML under
`autobuilder/build/test-results/test/`, and `--rerun` is required when only env vars changed.

### P5.3 status (execution log)

**Inc 0 — DONE.** Split `isModelableSublimation` into the pure shape predicate `isModelableSubShape(sub)`
(kind + supported-condition only) and the unchanged choosable-policy wrapper (behavior-identical; full
`:autobuilder:test` green, oracle untouched — the split doesn't touch the DP). Audit of every certifier
consumption of `subModel.subVars.keys` / `subModel.forced`, with the forced-sub decision banked here:

| Site (`WakfuBuildSolver.kt`, symbol) | Reads | Forced-sub F handling |
|---|---|---|
| World enum ×3 (`certifierWorlds`, `certifyMaxPerHitAtAp`, `certifyExplainAtAp`): `conversionSubs` / `critSecretLike` / `hasNoWeaponSubs` | `subVars.keys` by kind/condition | **Inc 3**: forced CONVERSION → keep only `convTaken=F` worlds; forced CS-like → only cs worlds; conflict (forced conv + any CS-like, or forced CS + any conv) → bail. Forced FLAT/conditional don't touch (not conv/cs). |
| `subCap` (≈L5368) | `MAX_SUBLIMATIONS − (conv/cs?1:0)` | **Inc 1**: `− 1` per forced sub (slot charge). `subCap < 0` already bails. |
| `keptSubs` (≈L5410) → `critBudgetSubs`/`apBudgetSubs`/`normalTransitionSubs`/`epicSubs`/`relicSubs` | `subEntries` (the OPTIONAL pools) | **Inc 1**: EXCLUDE forced subs (they are already taken — credited via constants, not optional; leaving them double-counts). |
| Pass constants `diBase`/`critBase`/`apBase`/`mpBase` (via `prePercentTermsFor`) folded into `constant`/`critConst`/`apConst`/`mpBase` | model terms | **Inc 1**: fold each forced FLAT effect's Raw value in (DI→`constant`, mastery→graw base, crit→`critConst`, AP→`apConst`, MP→`mpBase`); route forced crit/AP through the permanent/start split (`permanentSubTermsByStat`). |
| Epic/relic harvest gate (state epic/relic DP axis) | DP axes | **Inc 1**: a forced EPIC/RELIC sub → harvest only states with that rarity axis ≥ 1 (exact in the forced space; `gateSublimationsOnCarrierItems` forces the carrier). Excludes every other epic/relic sub from the optional pools. |
| `mpRaritySubFree` (≈L5085) | epic/relic sub MP | **Inc 1**: exclude forced epic/relic subs (their MP is credited via the constant). |
| `perStatStepSpecsByTarget` (≈L3303), `hasMpRampSubs` (≈L5062) | `subVars.keys` perStatStep | Forced perStatStep / best-element-concentration ride the EXISTING optimistic DI folds (sound over-count; **NOT** in the `==` lock set). |
| `appliesVar` (≈L7781) | `subModel.forced` | MODEL side. **Inc 2 fixed a real bug here**: it used to apply a forced sub's conditional effect UNCONDITIONALLY (invalid builds); now a forced sub with a supported condition gates its effect on `subVar ∧ condHolds` (gated var registered via `tracker.record` + `subDerivedVars`, the `fwGate` way). The certifier DP mirrors the gate per state at harvest. |
| Blanket bail (≈L4980) `if (subModel.forced.isNotEmpty()) return Long.MAX_VALUE` | `subModel.forced` | STAYS through Inc 0; **Inc 1** narrows it to "any forced sub whose shape is not yet handled". |

**Inc 1 — DONE (scoped to NORMAL FLAT Damage-Inflicted).** The blanket bail is replaced by a per-sub shape
guard near `subEntries`; a forced sub is creditable iff it is a **NORMAL FLAT sub with only a DI effect**, else
BAIL (sound). A creditable forced sub: (a) its DI folds into `diConst` (`forcedFlatDiCredit`, computed once
before the fast/exact branch ⇒ **both** passes credit it — THE ONE TRAP); (b) `subCap −= forcedSubs.size`
(slot charge); (c) it is excluded from `keptSubs` (the optional budgets/transitions) so it isn't double-counted.
Locks green: `certifier matches pinned CP-SAT with a forced FLAT sub` (per-cell `==` on a **crit-free** pool
where the certifier is exact — the coupling panel is only `≥`, so `==` uses a tight fixture, forcing `FlatDi`
alongside a choosable `FlatDi2`); the load-bearing `forced negative-DI sub lowers the certified optimum`; and
`fast certifier upper-bounds the exact certifier with a forced FLAT sub`. Full `:autobuilder:test` green; the
non-forced path is byte-identical by construction (empty `forcedSubs` ⇒ every addition is a no-op) — lvl-110
oracle reproduces §0. **Deferred to later increments** (each currently BAILS, sound): forced EPIC/RELIC subs
(carrier gate + epic-slot exclusion of other epic subs), forced FLAT subs with mastery/critM/crit/AP/MP effects
(the plan's `permanent/start` crit-AP split), and the STATIC_CONDITIONAL / CONVERSION / CRITICAL_SECRET shapes
(Inc 2 / Inc 3).

**Inc 2 — DONE (2026-07-03). The "~1.65 % under-count" was NOT a certifier bug — it was a CP-SAT MODEL bug
the new lock exposed.** The WIP's prime suspect (skill-crit vs the pre-crit gate) was wrong. `CERT_DEBUG`
showed the certifier's AP-6 winner already credited CondDi at its best legal state (`c=30, crit=27, di=155`),
yet CP-SAT was higher — because `appliesVar` **exempted forced subs from their own condition** ("Forced subs
apply unconditionally"): the model's "optimum" stacked 12 Luck points into crit (pre-combat crit 40) AND
CondDi's +30 DI — a build that cannot exist in game (an equipped sub whose build-static condition fails is
inert). The certifier, which gates exactly, was RIGHT.
- **Model fix:** a forced sub with a SUPPORTED condition keeps its pinned `subVar` (it must stay equipped —
  that reserves the slot/sockets, and the search may still break the condition) but its EFFECT gate becomes
  `subVar ∧ condHolds` (`subForcedApplies_<id>`): it stops being credited when the condition is broken.
  Choosable subs keep `subVar ≤ condHolds` (unchanged). Unsupported-condition forced subs keep the optimistic
  unconditional credit as before (the certifier bails on those shapes anyway).
- **Certifier plumbing:** the forced-conditional DI term now rides the GATED var, so it is attributed back to
  its sub the `fwGate` way — `tracker.record(gated, 0..1)` + `subDerivedVars[gated] = sub`. (Without the
  registration, `tracker.of` falls back to `±STAT_ABS_MAX` and a constants fold exploded to ~562 e9 — caught
  immediately by the same lock. An unregistered derived var is ALWAYS a bug; register like `fwGate`.)
- **Proof it was the model:** after the fix, pinned CP-SAT at AP 6 returns exactly the certifier's old value
  (**279840 == cert**), and `AP-cell certifier matches pinned CP-SAT with a forced conditional sub` is GREEN
  per-cell. Full `:autobuilder:test` (204/0 failures) + `:gui-compose:test` (98/0) green — no other lock moved,
  so choosable-sub behavior is untouched. Net user-visible effect beyond the badge: a forced conditional sub
  no longer yields INVALID search results that assume its effect while breaking its condition.

**Inc 3 — DONE (2026-07-04, + the epic/relic machinery).** Prepared by a behavior-identical refactor:
`certifyMaxPerHitAtAp` / `certifyExplainAtAp` dropped their duplicate world enumerations and now consume
`certifierWorlds()` — the single source of truth — so the forced filtering lives in ONE place.
- **Key insight — inert coverage:** a forced world-special stays EQUIPPED in every real build, so worlds
  where it is NOT taken must model it EQUIPPED-BUT-INERT (condition broken / moved-0), or those builds are
  under-counted. The plain-forced machinery does this for free: in a base world the special lands in
  `forcedPlainSubs` (slot charged; the shape guard `continue`s it as `inertSpecial` — zero credit is exact
  since its budgets/moved terms are already excluded). Its ACTIVE builds get its own world, unchanged.
- **Forced plain EPIC/RELIC flat-DI subs** (the Inc 1 deferral): occupy THE ≤1 slot of their rarity — the
  choosable rarity-sub stage is emptied, same-rarity special ACTIVE worlds are dropped (cannot coexist —
  exact), `mpRaritySubFree` skips the occupied rarity, and the exact harvest requires the carrier item's
  rarity axis ≥ 1 (exact in the forced space: CP-SAT's `gateSublimationsOnCarrierItems` forces the carrier).
  The fast pass stays loose (no carrier gate — sound).
- **Coverage bails** (sound, badge honestly absent): forced CS-like sub ≠ the single-EPIC shape; both
  specials forced; one special forced with the other present in a NON-both-EPIC pairing (a both-ACTIVE
  build would be covered by no world; both-EPIC can never coexist — Σ epicSub ≤ 1 — so there the split is
  exact and only the other's ACTIVE world drops); a forced special whose rarity slot is held by a forced
  plain sub; >1 forced plain sub of one rarity. Also fixed the latent DOUBLE slot-charge (a forced
  conversion/CS IS its world's convTaken/critSecret — `subCap` now charges only `forcedPlainSubs`).
- **Locks (first-run green):** forced conversion / forced Critical-Secret / forced epic flat-DI each
  per-cell `==` pinned CP-SAT on the Unraveling-trio fixture (`forcedSpecialTrio()`), the epic lock with
  the load-bearing "forcing the weaker epic strictly lowers the certificate" check; forced non-epic
  conversion + CS present → every cell bails; `fast ≥ exact` under every forced shape; forced-conversion
  search → ProvenOptimal end-to-end. Full `:autobuilder:test` 209/0. The (d) Unavailable case moved to a
  still-unmodelable shape (a MASTERY-effect forced sub) — same intent, the epic example became modelable.

**Inc 4 — PARTIAL, DONE.** `proveOptimality` no longer blanket-skips forced sublimations — the certificate
decides: a forced sub it can model (a NORMAL DI-only sub, FLAT via Inc 1 or supported-conditional via Inc 2)
is credited and can prove; any shape it can't (conversion / crit-secret / epic / non-DI) BAILS ⇒ null ledger ⇒
Unavailable. `CERTIFIER_VERSION` bumped to **2** (Inc 1 changed the certifier). GUI `PROOF_UNAVAILABLE_FORCED`
copy kept — it renders only when `proofState == Unavailable` AND a forced rune/sub is present, so it stays
contextually accurate (a forced sub that proves shows "Optimal proven"). Locks: `proveOptimality proves a
build with a forced flat-DI sublimation` (⇒ ProvenOptimal); the existing lock's forced-sub Unavailable case
forces a real EPIC sub (a shape the certifier still bails on). **Net user-visible result after Inc 3:
forced DI-only sublimations of ANY rarity (flat and supported-conditional), forced Unraveling-shape
CONVERSION subs and the forced Critical-Secret shape now get the "Proven optimal" badge; the remaining
badge-absent forced shapes are non-DI effects (mastery/critM/crit/AP/MP) and the coverage-bail pairings
documented under Inc 3.**

**Inc 5 — DONE (2026-07-04): forced COMBAT_CONDITIONAL + non-DI effects (the Inc 1 "deferred" list).**
User demanded: players commonly force combat-conditional subs and effect subs the model handles. The
Inc 1/2 shape guard is replaced by a full classification of `forcedPlainSubs` that mirrors `appliesVar`
EXACTLY (same three branches):
- **COMBAT_CONDITIONAL (136 of 232 subs — the most-forced kind):** the model credits NOTHING
  (`buildSublimationTerms` skips the kind) — equipped-but-inert. The certifier now `continue`s (zero
  credit; slot + epic/relic occupancy still charged) instead of bailing. Lock honeypot: a 500-DI combat
  effect that must NOT be credited on either side.
- **Unconditional (no condition / unsupported type — model credits on the pinned subVar):** DI/mastery/
  critM fold into `diConst`/`mConst`/`critMConst` (critM is deliberately NOT world-converted: a conversion
  reads preSubStat — no subs — and world C zeroes only PRE-COMBAT critM, so a start-of-combat sub critM
  legally survives both); MP into `mpFreeMax` (unclamped — a forced −MP really lowers the ramp source);
  PERMANENT crit/AP into `critConst`/`apConst` (feeding the c/AP arithmetic AND the pre-combat windows);
  START-OF-COMBAT crit into `forcedStartCritTotal` — a free always-present crit budget that widens
  `maxSubCrit` (band floor), joins `maxStartCrit` (AT_MOST hiding), extends `cEnumMax`, and is subtracted
  from the budget gap in BOTH harvests (the fast harvest has its own `critGapSubsC` — missing it there
  broke `fast ≥ exact` on first run; the exact-side credit was right).
- **Supported condition:** per-state gated credit generalized from DI-only to (di, m, critM) — the exact
  harvest adds `grawOf(credit)` next to `forcedCondDiHere` under the same `subAllowedAt` gate; the fast
  pass credits unconditionally (`forcedCondMTotal`/`forcedCondCmTotal` into `grawConstC`). Each axis is
  CLAMPED ≥ 0 — crediting a negative at a state whose builds may break the condition would under-count
  them (this also fixes a latent Inc 2 hole for negative conditional DI). NO_OFFHAND_OR_TWO_HANDED is
  world-split exactly like optional keptSubs: constants credit in the weapons-restricted world, inert in
  the free world.
- **Remaining bails (sound):** forced `perStatStep` ramps (Featherweight), start-of-combat AP, negative
  start-of-combat crit, and supported-conditional subs carrying crit/AP (a state axis can't be gated per
  condition — Measure's BLOCK≥40 +10 crit bails). The (d) Unavailable lock moved AGAIN, to a forced ramp
  sub (mastery became creditable).
- **Locks (per-cell `==` pinned CP-SAT on the trio fixture + fast ≥ exact):** forced combat (NORMAL and
  EPIC — the epic one also drops both special-ACTIVE worlds), permanent crit (Influence shape — shifts the
  conversion's CRIT_AT_LEAST window), start-of-combat crit (Berserk Critical shape — reaches c, never the
  window), flat mastery (Carnage), flat critM (stays un-converted per world), permanent AP (Vivacity —
  every cell band/window shifts by one), supported-conditional mastery (per-state gate), unsupported
  condition (optimistic on both sides), + forced-combat-sub search → ProvenOptimal end-to-end.
  `CERTIFIER_VERSION` stays 2: values for every previously-certifiable input are unchanged (all new terms
  are zero when nothing is forced — the lvl-110 oracle must stay byte-identical).

**Up-front request validation (same session, sibling feature):** `validateRequest` now also rejects
invalid-by-construction forced sets BEFORE any search (GUI pop-up via `RequestValidationProblem`, CLI
prints each problem and exits 1 instead of a stacktrace): two forced items in one slot (2 for rings),
forced 2H + 1H/off-hand, >1 forced EPIC/RELIC item, an item both forced and excluded, a forced epic/relic
sub whose carrier rarity the search excludes, >10 forced subs. Each has an EN/FR message + a
`describe()` line for the CLI.

### P4 badge robustness follow-ups (post-review, 2026-07-03)

- **Wrong-badge fix**: `BuildSearchModel.loadBuild` reset neither `proofState` nor `proofJob`, so a prior
  search's `ProvenOptimal` painted a green badge on a loaded build the certificate never saw (and a running
  proof stuck on "Proving…"). Fixed: cancel `proofJob` + reset `proofState = Idle` on load; the prover is now
  injectable and a `BuildSearchModelE2ETest` case asserts loading clears the badge.
- **Polish**: `proveOptimality` also skips CLI-style `forcedRunes`; the CLI prints an "Optimality not proven"
  verdict on Unavailable (no dangling hint); percent formatting uses `Locale.ROOT` (CLI + GUI); `conveyor.conf`
  sets `-Xmx3g` so the ~1 GB lvl-245 certificate DP has heap on 4 GB machines (needs a real Conveyor-CLI
  packaging check in the release step — `printConveyorConfig` only validates the Gradle side).

---

## P6 — Hardening & release (1 session)

**P6.1 Fuzz lock (CI-runnable).** Property test: N=25 seeded random small pools (3–6 slots, 2–4
items each, stats drawn from {mastery, critM, crit ±, AP ±, MP, DI}, 0–3 synthetic subs from the
supported shapes, optional same-name ring twins) → assert `certExact(cell) == ` pinned CP-SAT
OPTIMAL for every non-bailed cell, and `certFast(cell) ≥ certExact(cell)`. Fixed seeds (no
`Random()` without seed — CI determinism). This guards every future certifier edit.

**P6.1 — DONE (2026-07-04), and it immediately EARNED ITS KEEP: it surfaced + fixed a pre-existing
FATAL under-count.** Test: `max-damage certifier fuzz lock …` in `WakfuBuildSolverTest` (a plain
`@Test`, CI-runnable). Two adjustments to the spec, both because they make it *correct*, not weaker:

1. **The exact pass is a sound UPPER BOUND, not universally exact**, so the per-cell lock is
   `certExact(cell) ≥ CP-SAT` (an under-count is the one fatal class) with a *tightness counter*
   (`tightCells ≥ ½` of the cells must be `== CP-SAT`) rather than a blanket `==` — random crit-band
   / coupling shapes make the exact pass legitimately loose (`>`), exactly as the P2/P3 coupling-panel
   locks already use `≥`. It also adds the production-faithful lock the plan's per-cell `==` omitted:
   the two-tier **ledger's `maxCellObjective` must `≥` the true optimum** (max CP-SAT over all cells)
   on every non-bailed pool — that is the number `proveOptimality` compares to the incumbent, so it is
   the real "no wrong badge" invariant. A shape-level ledger bail (null) is tolerated (sound: badge
   withheld); `nonNullLedgers ≥ 8` and `tightLedgers ≥ 5` keep the production path genuinely exercised.

2. **The fix it forced (the reason it went red first):** the exact pass **UNDER-COUNTED every
   below-AP-constant cell** (`apHigh < 0` — cells reached by wearing −AP gear). The negative-AP
   charging DP is buggy: across the 25 seeds it read `cert < CP-SAT` by up to ~16 % — but ONLY ever at
   `apHigh < 0`; at/above the AP constant it stays a valid bound. A below-base cell whose true max
   exceeds a modest incumbent, under-counted below it, is a wrong "proven optimal" badge. **Fix:** the
   exact pass now BAILS on every below-constant cell (`if (apHigh < 0) return Long.MAX_VALUE`, was
   `< -apOff`); the orchestrator keeps the SOUND fast bound there (fast ≥ CP-SAT is locked and held on
   every below-base cell in the survey), which still eliminates these low-AP cells — the optimum always
   wants MORE AP, so a below-base cell is never the survivor a tight bound is needed for. `CERTIFIER_VERSION`
   bumped **2 → 3** (invalidates the session cache). Full `:autobuilder:test` green; the lvl-245
   "proven by certificate alone" story is unaffected (below-base fast bounds are ≪ the endgame optimum,
   so they were already fast-eliminated — the exact below-base value was never load-bearing there).
   This is distinct from the Inc 2 "1.65 %" case (that was a CP-SAT MODEL bug); this one is a genuine
   certifier-DP under-count, now walled off by a bail.

**P6.2 Nightly ledger regression.** New `@Tag("slow")` test: compute the 245 exact ledger and
assert the oracle values verbatim (update the oracle intentionally when the catalog version bumps —
the assert must reference `WakfuData.VERSION` so a data update fails loudly with a "re-bank the
oracle" message instead of silently passing).

**P6.2 — DONE (2026-07-04).** `lvl-245 fast certifier ledger reproduces the banked oracle` in
`WakfuBuildSolverTest` (`@Tag("slow")`). Asserts the lvl-245 **tier-1 FAST** ledger for the production
shape (runes + subs, full EPIC pool) == the banked `LVL245_FAST_LEDGER_ORACLE` bit-for-bit, `bailedCells`
+ `tier2Cells` empty, and — the soundness lock — `maxCellObjective ≥ 16_909_590` (the fast bound must
upper-bound the proven exact optimum, else the winning cell could be wrongly eliminated). The certifier
is a **pure DP over the embedded data, not an OR-Tools solve**, so — unlike the flaky free-solve proof
tests — it is deterministic and **cannot flake**; the oracle is version-pinned via
`LVL245_LEDGER_ORACLE_VERSION = "1.92.1.59"`, named in the failure message so a data bump (which shifts
the pool ⇒ the ledger) fails loudly with a re-bank prompt.

  **Why the FAST tier, not `forceTier2All` exact:** the full exact ledger is **~1 h serial → many hours
  on a CI runner** (the certifier is single-thread CPU-bound and CI machines are ~4–8× slower) — a
  non-starter for a nightly. The fast pass is **~70 s** and still covers every cell. It is driven through
  the REAL two-tier orchestrator with a huge incumbent so every cell is eliminated on its fast bound alone
  (`cellObjectives` = the production fast ledger). The exact tier is guarded separately, cheaply, by the
  many (CI-runnable) `… certifier matches pinned CP-SAT …` unit locks + the P6.1 fuzz lock's forceTier2All
  ledger on small pools. (The one-off ~1 h `forceTier2All` run was still done once to **re-validate the
  P6.1 fix at endgame scale**: exact `bailed=[]`, `tier2=[6..20]`, cells 0–5 below the AP constant carry
  the fast bound, optimum unchanged at 16_909_590 — it just isn't what the nightly re-runs.)

**P6.3 Docs.** Update `AGENTS.md` §4 with a short paragraph (the certificate exists, where it
lives, the badge semantics, the CERTIFIER_VERSION bump rule). Move the campaign log's final state
into `docs/MAX_DAMAGE_PROVABLE_OPTIMUM.md` (link, don't duplicate).

**P6.4 Release checklist.** GUI semver bump (`gui-compose/build.gradle.kts`); squash-merge
discipline (one user-facing `feat:` line — "What's new" shows one line per commit); verify the
badge on a cold install (first-launch OR-Tools warm-up must precede the certifier — it already
does, the certifier runs after the search); `./gradlew test` + `slowTest` green.

**P6.4 — DONE (2026-07-04), with two notes.**
- **GUI semver**: `gui-compose/build.gradle.kts` version carries `// x-release-please-version` — it is
  **release-please-managed**, bumped automatically from the conventional-commit history (the P6 `fix:` /
  `feat:` lines). A manual edit would collide with the release PR, so none was made. (Note: `AGENTS.md`
  §2 still says "currently 1.0.0" — stale; the live version is 1.8.0.)
- **Cold-install badge**: already correct by construction — the certifier runs *after* the search
  completes, so the first-launch OR-Tools warm-up (which the search itself pays) always precedes it.
- **Tests**: full `./gradlew test` (all modules) green in ~2 min. `slowTest` is the heavy nightly path
  (~1.5 h); the P6.2 fast-ledger oracle adds only ~70 s (it was scoped to the fast tier precisely so a CI
  runner can afford it) and is deterministic + verified (ran green end-to-end), and the below-base fix
  leaves every free-solve proof untouched. The
  nightly `slowTest` on `main` is separately RED on a **pre-existing flaky** free-solve test
  (`…proves OPTIMAL with runes and sublimations on the full level-110 pool`) — OR-Tools worker
  non-determinism under an oversubscribed CI runner, not a certificate issue and not introduced here.

### P6 — COMPLETE (2026-07-04)
All four sub-tasks landed on `docs/denouement-feasibility`: P6.1 fuzz lock (+ the below-base-AP
under-count fix it surfaced, `CERTIFIER_VERSION` 2→3), P6.2 version-pinned lvl-245 oracle, P6.3 docs
(AGENTS.md §4 + campaign final state), P6.4 checklist. The certificate is production-ready: badge wired
in GUI + CLI, forced-item / forced-sub coverage, bail-first soundness, and CI + nightly regression
guards. The whole 6-phase plan (P0–P6) is now done.

---

## Risk register

| Risk | Detection | Mitigation |
|---|---|---|
| 4-D frontier explosion | `CERT_STATS` points/state ≫ 8× 3-D | P2.6 coarse-c fallback |
| Fast pass under-counts (fatal) | `fast ≥ exact` lock, fuzz lock | fix or widen ∃-gates; never ship without the lock |
| Thread nondeterminism | run-twice diff in P3 | pure per-(cell,world) tasks, max-merge only |
| Live under-count (unknown unknowns) | P4.2 self-check `cert(incumbentAp) ≥ incumbent` | badge off + loud log, solve unaffected |
| Catalog update shifts shapes | production-shape `@slow` lock + P6.2 oracle vs `WakfuData.VERSION` | bail-first design: new shapes → no badge, never a wrong badge |
| Perf regression sneaks in | P0.2 `CERT_STATS` counters in the audit output | compare counters, not wall-clock |

## Effort summary

P0 ½ + P1 1–2 + P2 1–2 + P3 1 + P4 1–2 + P5 1 + P6 1 ≈ **7–9 focused sessions**, each ending in a
committed, gates-green state (ktlintFormat + `:autobuilder:test`; `:gui-compose:test` whenever the
GUI is touched; never commit `WakfuData.kt`; never push without being asked).
