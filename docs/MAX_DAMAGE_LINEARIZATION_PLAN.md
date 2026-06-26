# Max-damage: linearize the bilinear objective — design note for a fresh start

**Status:** IMPLEMENTED, WITH FINAL A/B RESULTS — `codex/max-damage-linearization-impl` keeps the exact
AP-selector and binary-`D` product encodings, but deliberately leaves `crit·(M+5K)` as OR-Tools'
`AddMultiplicationEquality`. The crit one-hot was exact but too bulky on the full lvl-245 pool. The real wins were
tight leaf/rune domains and removing redundant rune/sublimation assignment grids:

- skill variables are capped to their branch point budget, not `Int.MAX_VALUE`;
- single-element random mastery is folded into one slot-aware sum;
- rune vars are bounded per carrier slot, then pure max-damage rune choices collapse to "best M rune" vs
  "crit rune only when it can beat M";
- normal sublimation carriers use one aggregate capacity constraint instead of `(sub × item)` booleans.

Current measured proofs: lvl-245 full runes+sublimations **63.3 s**; lvl-110 runes+sublimations **42.5 s**;
lvl-245 free max-damage **78.6 s** on the final verification run (earlier free-only A/B was **30.6 s**, so keep
free-pool timing noisy unless rerun in isolation).
**Goal:** make the `FIND_BUILD_WITH_MAX_DAMAGE` proof **fast** (ideally most-masteries-class, ~1 s, low-thread),
without changing the proven optimum.
**Symbol-first** (line numbers drift). Background: `docs/MAX_DAMAGE_PROVABLE_OPTIMUM.md` (how it became *provable*)
and `docs/SOLVER_PERFORMANCE.md` (the wider perf log).

---

## 0. TL;DR

Max-damage now proves OPTIMAL fast enough for the full lvl-245 runes+sublimations guard, but it remains the slowest
solver family. Latest cleaned-tree verification:

| test (full pool, `@slow`) | wall |
|---|---|
| max-damage runes+subs, lvl-245 | **63.3 s** |
| max-damage runes+subs, lvl-110 | **42.5 s** |
| max-damage free, lvl-245 | **78.6 s** |

Every **non**-max-damage proof (precision, most-masteries, feasibility) now runs in **≤12 s** and was promoted out
of `@slow`. The split is **objective shape, not search space**: a *sum* proves fast; a *product of large sums*
does not. Max-damage maximizes `Score = D · Graw` (bilinear, with `crit·M` nested) — the LP relaxation of a
product of two big variable sums is loose, so CP-SAT must branch hard to close the gap.

**The plan:** replace the generic products with **boolean-gated / one-hot** encodings (tight LP), **and** fix the
**loose operand bounds** that otherwise blunt the gating. The two are coupled — do them together.

---

## 1. The objective (what to linearize)

`StatBuilder.perTurnDamageScore` (`WakfuBuildSolver.kt`), per scenario element `s`:

```
M    = clamp(100 + ΣMastery, 0, DAMAGE_MASTERY_MAX)        // dmgM_s
K    = clamp(MASTERY_CRITICAL, 0, DAMAGE_MASTERY_MAX)      // dmgCriticalMastery_s
C    = clamp(CRITICAL_HIT, 0, critCap≤100)                 // dmgCrit_s        (crit chance %)
D    = 100 + clamp(DAMAGE_INFLICTED, -FLOOR, DI_MAX)       // dmgD_s
diff = M + 5·K                                             // dmgDiff_s   (tSumNaive)
term = C · diff                                            // dmgCritTerm_s (tMul → addMultiplicationEquality)
Graw = 400·M + term                                       // dmgGraw_s   (tSumNaive)
Score= D · Graw                                            // dmgScore_s  (tMul → addMultiplicationEquality)
```

The two generic products — `tMul(C, diff)` and `tMul(D, Graw)` — are the cost. Expanded: `Score = 400 D·M +
D·C·M + 5 D·C·K` (bilinear + trilinear). `tMul` calls `addMultiplicationEquality`; CP-SAT's generic product
propagation + a loose McCormick relaxation = the slow proof.

The scenario’s element / orientation / resistance / positional factors are constants that scale every build
equally and are dropped, so only this core matters. Per-element enumeration (`MaxDamageSearch`) solves each
candidate element separately and takes the max — the linearization slots **inside** one element solve.

---

## 2. Two coupled levers

### 2a. One-hot / gating the products (tight LP)

A product `x · boolean` has an **exact, tight** LP relaxation; a product of two large variable sums does not.
So encode the small-domain factors as selectors and gate:

- **crit one-hot** — `C ∈ [0, critCap]`, `critCap ≤ 100` ⇒ **~101 selectors**. `yC[c]∈{0,1}, Σ yC=1, C=Σ c·yC`.
  Then `C·M = Σ c·(M·yC[c])` and `C·K = Σ c·(K·yC[c])`, each `·yC[c]` a boolean-gated product (4 linear
  constraints: `z≤U·y, z≥L·y, z≤x−L(1−y), z≥x−U(1−y)`). ⇒ `Graw` becomes **linear**. **Measured regression:**
  exact but too many gates on the full lvl-245 pool; keep `crit·diff` generic unless a future benchmark proves
  otherwise.
- **`D·Graw`** — `D`'s *reachable* span is small (measured **~125**, `DI∈[-20,104]` with subs; see §3), so:
  - **best: binary-expand `D`** — `D = Dmin + Σ 2^b·bit_b` ⇒ `D·Graw = Dmin·Graw + Σ 2^b·(bit_b·Graw)`, only
    **~7 gated `Graw` copies** (log₂125). Cheaper and robust vs a full DI one-hot.
  - alternative: **source-expand `D`** — gate `Graw` behind the existing item booleans (`D·Graw = 100·Graw +
    Σ itemDI_i·(x_i·Graw)`); tighter (tied to the booleans CP-SAT already branches on) but more invasive (DI
    comes from gear/skills/subs).
- **`throughput · perHitScaled`** — throughput is a **table lookup by AP (≈6–12 values)**; an AP-selector gating
  replaces that product almost for free. Cheapest single win; do it first.

Selectors are **separate `Σy=1` sets** ⇒ they **add** (crit 101 + DI-bits ~7), they do **not** multiply. (An
*outer enumeration* — a separate solve per `(crit, DI)` cell — multiplies to ~12 k solves; **rejected**, do not
do that.)

### 2b. Tighten the operand bounds (or 2a barely helps)

Each gated product’s big-M constant **is the operand’s max bound**. If `M_max` is loose, the gated LP stays
loose and 2a delivers little. **Measured, the operands are ~20× loose** (§3). So 2a **requires** tightening
`M`/`K`/`Graw` first. This is the harder half — see §4.

---

## 3. Measurements (already done — trust these)

Build-only probe of the tracked reachable ranges (`maxDamageReachableRangesForTest`: build the model, return
`built.maxDamageTracked` as `(name, lo, hi)`), CRA fire/distance/face, lvl-245:

- **Selector axes are small & additive:** `dmgCrit` span **101**; `dmgDI` span **125** (`[-20,104]`).
- **Operands are absurdly loose:** `dmgM = [0, 100000]` (the `DAMAGE_MASTERY_MAX` *guard*; true achievable ~6 k),
  `dmgGraw = [0, 1e8]`, `dmgScore = [0, 1.1e10]`.
- **Root cause of the looseness** (widest-tracked-var dump): the **`rand_*` random-element mastery vars**
  (`applyGreedyRandom`, items that roll mastery onto a random element) each carry a **recorded reach ≈ 1.07e10**,
  declared against `STAT_WITH_PERCENT_ABS_MAX ≈ 1e9`. `dmgPreM` inherits it (the elemental fold sums them) ⇒ `M`
  clamps to the 100000 guard ⇒ `Graw`/`Score` blow up. It is **the same runes-off and runes-on**, so it is the
  base random-element + percent interval arithmetic, *not* runes.

Follow-up measurement after implementation:

- Before the skill-domain fix, the widest leaves included `skill_Mastery Elementary: 0..2147483647`, so fixed
  mastery skills with `maxPointsAssignable = Int.MAX_VALUE` were tracked at their raw per-skill cap instead of
  their branch's available points. That alone pushed `pre_MASTERY_ELEMENTARY` / `rand_MASTERY_ELEMENTARY_FIRE`
  to ~1.07e10.
- After capping each skill var to `min(skill.maxPointsAssignable, assignable.maxPointsToAssign)` and seeding
  `DomainTracker` with the same cap, plus aggregating single-element random mastery in one slot-aware sum:
  `dmgPreM = [-392,18099]`, `dmgM = [0,18099]`, `dmgGraw = [0,11942000]`, `dmgScore = [0,1313620000]`.
- With runes+sublimations enabled at lvl-245, the remaining waste was structural rather than arithmetic:
  full-pool runes initially built ~29k vars / 7.5k constraints, including ~21k rune vars. Carrier-slot rune
  bounds, max-damage rune-choice collapse and single-choice substitution reduced the full runes+subs model to
  ~8.7k vars / 1.2k constraints in the diagnostic profile, after which the lvl-245 runes+subs proof closed in
  **63.3 s**.

So the operand looseness was a **compound** problem: over-wide skill-variable leaves amplified the
`applyGreedyRandom` / `pre_<element>` chain. Tighten both the skill leaves and the random fold; do not clamp
downstream.

---

## 4. Tightening `M` (the hard half) — and its dead end

`preM`'s reachable hi is computed by a **naive sum** over aggregate mastery stat vars (`elementMasteryVar`,
`actualStat(rangeBand.masteryCharacteristic)`, …); those inherit the ~1e10 `rand_*` reach. The fix is to compute
`M`'s reach **per-slot-aware** — analogous to `reachableSumDomain` (which bounds item terms by *best-per-slot*,
RING×2, a sound over-estimate) — but the mastery chain feeds through `applyGreedyRandom`, so it needs a
socket/slot-aware reach for the random-element rolls too. Intricate and **soundness-critical** (an *under*-estimate
silently cuts the optimum).

**DEAD END (measured, do not repeat):** clamping only `M` (`tClamp(preM, 0, tightCeiling)`) does **nothing** —
`preM` and the `rand_*` vars keep their billion-wide declared domains and still pollute the model. A 30000-ceiling
experiment ground 12 min+ and was killed. You must tighten the **reach computation** of `preM`/`rand_*`, not clamp
downstream.

---

## 5. Suggested order (incremental, A/B each step — never bundle)

The host’s measurement floor means you **cannot** bundle changes and attribute the gain (deterministic_time swings
~2.3×; see `docs/SOLVER_PERFORMANCE.md`). Use a **reproducible** A/B: `numSearchWorkers=1` + fixed seed (1-worker
CP-SAT is deterministic, thermal-immune) on a right-sized instance, and compare `isOptimal` + `numConflicts`/
`numBranches`. Benchmark targets: the `@slow` `max-damage free lvl-245` (82 s) and `runes+subs lvl-110` (297 s).

1. **Tighten skill domains first.** This was the hidden root cause: `Int.MAX_VALUE` mastery skill caps polluted
   the reachable ranges even though category constraints already limited them.
2. **Use a slot-aware single-element random fold.** For max-damage's single played element, every random-element
   mastery line with `count >= 1` can feed that element; aggregate those item terms once and bound them through
   `reachableSumDomain`.
3. **AP-selector for `throughput·perHit`** — cheap and still retained.
4. **binary-expand `D·Graw`** — retained after the `Graw` bound is tight.
5. **Do not use crit one-hot by default.** It was exact but regressed lvl-245; with tight `M`/`K`, the generic
   `crit·diff` product is the better tradeoff.
6. **Collapse pure max-damage rune choices.** All M-feeding runes enter the same `M` sum, so pick the best
   carrier-specific M rune and keep a crit-mastery alternative only when its rune value is larger. Single-choice
   carriers substitute the equipment var directly; two-choice carriers model crit as `critPick ≤ equip` and
   subtract the default M rune when crit is chosen.
7. **Aggregate normal sublimation carriers.** Because every normal sub has the same optimistic carrier set
   (any equipped ≥3-socket item), replace `(sub,item)` assignment vars with
   `selected normal subs ≤ equipped normal carriers`.

After each: confirm the **proven objective is byte-identical** to the current model on the same request, and that
it still proves `OPTIMAL`.

---

## 6. Soundness — non-negotiable

Every reformulation is **exact** (same optimum) by construction, but verify:

- **Optimum-preservation lock:** new-model proven `Score` == current-model proven `Score`, on a small pool both
  prove (mirror `single-type rune fold preserves the max-damage optimum` and `tight reachable domains preserve the
  exact max-damage optimum`).
- **Bound under-estimate lock (REQUIRED for §4):** `maxDamageVarBoundsForTest` already solves the *loose* model
  and asserts every tracked var's solved value ∈ its declared reachable `[lo,hi]`. **Extend its coverage** so a too-
  tight `M`/`preM`/`rand_*` bound trips it, and add a **shape that drives `M` to its true max** (else the guard has
  no teeth). This is the mechanism that catches a future code change silently breaking the tightened bound.

---

## 7. Rejected / dead ends (don't re-try)

- **Outer enumeration** (separate solve per `(crit, DI)` cell): grid ≈ 101×125 ≈ 12 k solves. Inner one-hot adds,
  not multiplies — use that.
- **One-hot `D` over the full clamp guard** (`DI_MAX`=5000 ⇒ ~5100 selectors): too fat. The *reachable* span is
  ~125 (one-hot fine, binary-expand better).
- **Clamping only `M`** (§4): no-op; `preM`/`rand_*` stay loose.
- **Generic warm-start (`addHint`), CP-SAT param knobs (`optimizeWithCore` = 28-min catastrophe), tight domains for
  most-masteries:** all measured no-ops/regressions — see `docs/SOLVER_PERFORMANCE.md`.

---

## 8. Context: what's already shipped (don't redo)

- **Per-element enumeration** (`MaxDamageSearch`), **tight reachable domains** (`DomainTracker`, max-damage +
  precision), **per-slot domination** (all modes), **rune fold** + **exact socket fill** (most-masteries) — the
  linearization composes with all of these.
- Max-damage **already proves** (this is a *speed* project, not a correctness one). It runs on the nightly
  `slowTest` job, so there's no regression risk to PR CI while this is in progress.
