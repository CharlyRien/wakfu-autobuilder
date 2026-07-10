# Solver bottleneck diagnosis & experiment plan — 2026-07-07

> **✅ CONCLUDED (2026-07-09).** Every E-series experiment has a measured verdict (log in
> `SOLVER_PERFORMANCE.md` §7); the perf backlog is closed except C8(3) (blocked, speculative — see
> `perf-review-backlog.md`). Kept as the diagnosis/method record; do not re-run settled experiments.

Goal: find *where* the solver actually loses time (per mode, per layer), then fix it with
evidence-ranked experiments — up to and including architecture changes. Written to be executable by
any agent. Companion docs: `docs/SOLVER_PERFORMANCE.md` (perf log + §6 A/B harness),
`docs/perf-review-backlog.md` (shipped A/B/C items), `docs/MAX_DAMAGE_LINEARIZATION_PLAN.md` (the
current exact binary-product model and how it got there), `docs/CERTIFICATE_PROD_PLAN.md` (the
certificate DP).

**The central premise (new, 2026-07-07):** we now have a measurement protocol that is actually
stable — CP-SAT's **deterministic interleave** (`interleaveSearch=true` + fixed workers + fixed seed +
`maxDeterministicTime` checkpoints), which makes the whole portfolio advance in lockstep on
deterministic time and produces reproducible (det, objective, bestBound) trajectories. Every
performance verdict measured BEFORE this harness existed (wall-clock era, free-running multi-worker
era) is a **belief, not a fact**. This plan (1) re-baselines everything under the new protocol,
(2) runs cheap diagnosis passes that localize the bottleneck, (3) only then spends effort on
experiments, ranked by what the diagnosis says.

Soundness rejections are NOT reopened by the harness change — anything rejected because it was
*unsound* (certcap, naive ceiling-skip, the §4 prunings, MP-target certificate axes) stays rejected.
Only *performance* verdicts ("measured no-op", "measured regression") are re-screenable.

---

## Execution log (2026-07-08)

- **P0 foundation ✅** — protocol nailed: **1 worker + `interleaveSearch`** is byte-identical reproducible AND
  full-portfolio (8-worker interleave still jitters ~1–2 %). Harness built: `manual bound-layer audit`
  (`WAKFU_BOUND_AUDIT`) + reusable `manual max-damage experiment ab` (`WAKFU_EXP_AB`, named-config A/B under the
  protocol). Commits `672a62b7`, `198369c5`. (CSV output + full F1–F7 fixture matrix + 3-repeat determinism harness
  are the remaining P0 polish.)
- **E0 (C6) ✅ REMOVED** — box-delta probe showed the slice fires but tightens perHit only 0.34 % (cosmetic);
  trajectory swings are noise. §7 / §4 E0.
- **§2.1 primal/dual ✅** — F4 (and by structure F2) are **bounding-limited** (optimum found early, bound stays loose).
- **§2.2 bound-layer audit ✅** — loosest layers: **crit×diff 4.1–4.5× + crit-mastery + rotRaw 3.0–3.2×**; perHit
  (C6's target) is NOT the binding face. Level-robust (F1 110 == F2 245). §7.
- **§1.4 re-screen — `perApRotRawCut` ✅ SHIPPED** (`3e82ec35`) — the rotRaw-layer bound: −18.8 % dual bound on free
  F1, marginal+no-regression on constrained; sound; the old "no help" verdict was a pre-interleave artifact.
- **E6 crit-band ⏸️ built + measured → flag-off** (`fec40a6d`) — targets the LOOSEST box (crit×diff) but the A/B is
  noisy; **lesson: box-looseness is a candidate, not a guarantee**.
- **E8 ✅ MEASURED → GO + fast-path SEAM FIRES** — `manual E8 DP-solvable measurement`: both free lvl-110 and
  lvl-245 are DP-CONSTRUCTIVE (argmax items re-solve to *exactly* the cell bound, ratio 1.0; lvl-245 = 16.9M optimum
  where CP-SAT stays stuck at 26M). Built `WakfuBuildSolver.dpConstructProvenOptimum` (run DP → backtrack argmax items
  → re-solve tiny restricted pool → accept iff `proxy ≥ cellBound` ∧ valid; sound — else fall back). The blocker the
  first cut hit (constructed build tripped `isValid()`) was NOT a seam bug but a **stale validity check**:
  `hasLegalSublimations()` still enforced the retired `runes + 3·subs ≤ sockets` budget that commit `54761dc6`
  removed from the model (golden runes form the sub pattern AND carry their stat ⇒ full rune set kept alongside the
  sub). The model, sub-model builder, and certifier all already credit every socket as a rune; only `isValid()` was
  stale. Fixed in `6eefe734` (line 81 → just `runes ≤ sockets`), aligned with the certifier, **no cert-soundness
  change**. Seam now FIRES end-to-end (lvl-110: constructs the 1,310,980 optimum, valid + isOptimal). `manual E8
  construct proven optimum` asserts it. **Production wiring B/C/D SHIPPED** — (B) cache-coherent seam + (C) production
  entry point `constructMaxDamageProvenOptimum` (`0526ea1f`); (D) CLI shows/Zenith-swaps the constructed optimum
  (`0526ea1f`) + GUI async-swaps the build & flips the badge to ProvenOptimal (`43a10f4b`), `:autobuilder:test` &
  `:gui-compose:test` green. So a timed-out max-damage search is now RESCUED to the proven optimum in both front-ends.
  **Item A = two halves. (A1 robustness) ✅ SHIPPED** (`d9db1a47`, 2026-07-08) — structured provenance: the seam reads
  TYPED equipmentIds threaded through the explain backtrack (`Opt.ids` → `CertExplain.itemIds` →
  `BuiltModel.certifierExplainItemIds`), no fragile `slot:`-string parse. Explain-output only, NO CERTIFIER_VERSION bump,
  fail-safe; E8 construct fires via ids + full suite green. **(A2 perf) ✅ SHIPPED** (2026-07-08) — the ~9-min win. E8 used
  to re-run the explain DP (`certifyExplainAtAp` ≈ 2+ full exact-cell passes ≈ ~9 min @245) on every rescued search; now the
  badge's EXISTING exact pass records a lightweight `CellProvenance(worldIndex, c)` pointer (the argmax world + winning
  crit-step) — nearly free, since `certifyMaxPerHitAtAp`/`exactForCells` already track the max, they just surface which
  world/c achieved it. Refinement of the original "snapshot every c" sketch (that put frontier-copy overhead on the common
  badge path; the pointer doesn't). It rides `CertLedger.cellProvenance` (a `@Serializable` value type) → accumulates through
  the in-mem `RawEntry` + `DiskRecord` as an OPTIONAL defaulted field → survives `reconstruct`; the seam replays only that
  one (world, c) as a single explain pass (`certifyExplainAtApFromProvenance` → `certifierExplainItemIdsFromProvenanceForTest`)
  with **graceful fallback to the A1 full scan** ⇒ **NO CERTIFIER_VERSION bump, NO oracle re-run** (additive, bounds
  unchanged, fail-safe). E8's provenance cost ~9 min → ~seconds; the badge pays only a pointer write. Three CI locks:
  replay==full-scan equivalence, disk round-trip, pre-field backward-compat decode. Full suite + ktlint green. Details:
  SOLVER_PERFORMANCE §7 + the `e8-fastpath-isvalid-stale` memory.
- **Untested-flag inertness sweep ✅ DONE** (`367e8fb7`) — `certifierCellCap` / `dGrawCutoff` / `apCeiling` / `critBand`
  re-screened via the harness: only `dGrawCutoff` is truly inert (and production-UNREACHABLE — `objectiveCutoff` is
  test-only); `certifierCellCap` PROVES the optimum in-model but is certifier-cost-dominated + redundant with E8;
  `apCeiling` / `critBand` perturb. All stay OFF, evidence-justified. §7.
- **E2 (conversion-conservation cut) ✅ SHIPPED** (2026-07-08, `DEFAULT` flipped ON) — the clampSlack HALF of C1's
  conversion double-bank: a CONVERSION sub's `moved` var was still counted in the per-stat clamp slack, crediting an
  unreachable clamp-restoration (`moved ∈ [0, percent%·preSubStat(from)]` can't drive the source below its no-conversion
  min). Excluding `conversionMovedVars` from the slack **UNSTICKS the lvl-245 flagship dual bound** (25.98M frozen →
  23.29M, −10.4% @det800 — a descent `perApRotRaw` could not produce), −19%/−10% early on the constrained shape,
  free-110 a horizon wash. Sound (targeted fires+`withinBound` lock + objective-chain fuzz), no `CERTIFIER_VERSION` bump.
  Details: SOLVER_PERFORMANCE §7.
- **E9 (outer-enumerate crit bands) ❌ POC → NO-GO** (2026-07-08) — crit is the small factor of `crit×diff` (the
  looseness is diff/K), and the certificate already does outer-crit enumeration + exact K/diff. Dominated; scaffolding
  reverted. §4 E9 + SOLVER_PERFORMANCE §7.
- **Certificate two-tier speed: exact-pass c-loop pruning ✅ SHIPPED** (2026-07-08, `CERTIFIER_VERSION` 5→6) — tier-1.5's
  step-1 pass now keeps its per-crit-step harvest (`Tier15Result.perCByCell`, free) and the ledger threads each row into
  the SAME (cell, world) exact pass, which B&B-prunes its c-loop (seed argmax-bound c, skip `ub[c] < best`; tie-aware
  update ⇒ value AND provenance byte-identical). **Rescue-shape A/B: 110 243→189 s (−22 %), 245 620→385 s (−38 %; the
  exact argmax cell ~4.5 min → ~0.5 min), identical ledgers.** ⚠️ Measured dead-end: a per-(cell, world) bound self-call
  REGRESSES 1.87× (110 T2ALL 835→1560 s) — prune only where the bound is already paid (production badge path); oracle/
  forceTier2All stay unpruned. Details: SOLVER_PERFORMANCE §7.
- **Badge floor: tier-1.5 segment skip ✅ SHIPPED** (2026-07-08, `CERTIFIER_VERSION` 6→7) — `CERT_TIMING` decomposition
  showed tier-1.5 was 90 % of the post-pruning floor (172.9 s of 189 s @110). Tier-1's step-8 harvest now keeps its
  per-world per-(cell, crit-step) bounds, and tier-1.5 skips every step-1 segment already at-or-below the incumbent
  threshold — clearing decision provably identical, cleared cells may record the step-8 bound (⇒ the bump).
  **Rescue A/B: 110 189→35.0 s, 245 385→89.1 s; session total 7× both levels (620→89 s @245, 243→35 s @110).**
  Decision-identity lock over won/lost badge shapes + `WAKFU_MAX_DAMAGE_CERT_NO_T15SKIP` seam. SOLVER_PERFORMANCE §7.
- **E10 ✅ SHIPPED** (2026-07-08) — search-time certificate warm-up + single-flight cache (see §4 E10): the badge now
  overlaps the search wall-clock and lands ~instantly at search end when the duration covers the warm-up.
- **Fast-pass DenseDp port ✅ SHIPPED but a measured WASH-ish ~6 %** (2026-07-08, `CERTIFIER_VERSION` 7→8; 58.3→55.0 s
  @245): the segment DPs were ~70 % of the fast tier (`CERT_FAST_TIMING`), but unlike the exact pass the cost lives in
  the frontier dominance work, not map overhead — the P1.3 playbook does not transfer 1:1. Byte-identical (oracle
  bit-for-bit). Next fast-tier lever would be frontier-level — diminishing returns; STOP here on the fast tier.
- **D tickets ✅ ALL DONE** (2026-07-08): D1 solver-vs-certificate badge copy; D3 slow-test restructure (1w+interleave
  deterministic bare proofs — the 110 one proves in ~9 s; runes+subs re-specified to search→certificate→E8-rescue,
  det120 search since incumbent quality is irrelevant to the guarantee; `timeout-minutes: 240` CI backstop — the
  pre-existing catalog no-bail proof alone runs ~1 h); D4 was already shipped with A1 (stale entry).
- **P0 ✅ CLOSED as-scoped** (2026-07-08): the protocol (1w+interleave+seed, det sweep, trajectory-vs-trajectory) is
  nailed and productized (`SolverTuning.interleaveSearch` now reaches BOTH solve paths); the harness emits parseable
  per-checkpoint `EXP_AB` lines to `WAKFU_EXP_AB_OUT` with a repeats axis; F2/F4 axes exist and F5 has a dedicated
  A/B (`manual most-masteries param ab`). The remaining niceties (a single F1–F7 monolithic baseline table, F6/F7
  harness axes) have NO consumer left — the E-series is concluded, each verdict recorded per-experiment in
  SOLVER_PERFORMANCE §7 — so they are deliberately not built.
- **Remaining open:** C7 (staged: the full proof-checked derivation now lives in perf-review-backlog §C7 — start
  there, fresh session) and C8(3) (blocked on a from-scratch greedy; criteria unchanged). The headline is banked:
  badge ~85 s cold @245 (was ~10 min at this session's start), ~0 behind a normal search (E10), rescues in seconds
  (E8).

---

## 0. Goal metrics — measure what the user feels

Three user-visible latencies, per mode. Every experiment must state which one it targets.

| Metric | Definition | Roughly today |
|---|---|---|
| **M1** time-to-first-build | search start → first streamed build | seconds (streaming works) |
| **M2** quality-at-budget | best build found within the user's `searchDuration` | good (primal side is strong) |
| **M3** time-to-verdict | search start → "proven optimal / within X%" badge | the real pain: minutes on hard shapes; certificate carries free 245; constrained range-shapes stay "within X%" |

Mode-by-mode, where M3 stands (2026-07-07): free single-element proves in-model at ~lvl-110 scale;
free lvl-245 sub-heavy does NOT prove in-model (bound stuck ~26M vs 16.9M optimum — the certificate
DP proves it instead); constrained `--pa/--pm` proves via the hard leg; constrained range-binding
(`--po`) does not prove (C6 under test, not improving so far); most-masteries is the slowest mode
end-to-end; boss/multi-element = per-element enumeration, budget-starved at 245.

---

## 1. P0 — the measurement foundation (do this before ANY experiment)

### 1.1 Canonical protocol (the only accepted evidence from now on)

- `interleaveSearch = true`, fixed `numSearchWorkers` (pick one: the interleave portfolio size we
  standardize on), fixed `randomSeed`, solve to a fixed `maxDeterministicTime`; sample the trajectory
  at det checkpoints (e.g. 25 / 50 / 100 / 200 / 400): record `(det, objective, bestBound, status)`.
- Confirm determinism ONCE per fixture: 3 repeats must be byte-identical trajectories. If a fixture
  is not byte-identical under interleave, say so in the results table and treat it as noisy.
- Wall-clock is recorded but only as a sanity column (quiet host, no thermal load).
- Comparisons are **trajectory-vs-trajectory** (bound at each det checkpoint), not single numbers.
  A variant "wins" if its bound is tighter at most checkpoints AND final status is not worse.
- Where the verdict is a status flip (UNKNOWN → OPTIMAL within budget), status is noise-free and is
  the primary readout (the C4 lesson).

### 1.2 Harness work (small)

The §6 harness in `SOLVER_PERFORMANCE.md` + the `WAKFU_MAX_DAMAGE_VARIANTS` variant screen already
exist. Extend, don't rebuild:

- Emit the trajectory as CSV (`det,objective,bound,status` per checkpoint per variant) instead of a
  single end value — one artifact per run, diffable, plottable.
- Add a `--fixtures` axis so one invocation runs the whole fixture matrix (below).
- Keep per-subsolver `solve_log` capture (the `logSearch` knob) for attribution when a trajectory
  shifts.

### 1.3 Fixture matrix (the workloads everything is measured on)

| ID | Fixture | Why it's in the matrix |
|---|---|---|
| F1 | free max-damage, lvl 110, full runes+subs | proves in-model today — regression canary |
| F2 | free max-damage, lvl 245, full runes+subs | does NOT prove in-model — the dual-gap flagship |
| F3 | constrained `--pa 11` binding, lvl 110/245 | the common user target shape; hard leg proves |
| F4 | constrained `--pa 11 --pm 4 --po 4` | the open range-binding shape (C6's target) |
| F5 | most-masteries default request, lvl 245 | slowest mode |
| F6 | precision default request, lvl 245 | control — should stay fast |
| F7 | boss multi-element, lvl 245 | per-element enumeration under budget pressure |

Known optima exist for F1/F2 (certificate/campaign log) and can be produced once for F3–F6 with a
long pinned run — needed as ground truth for the §3 diagnosis passes.

### 1.4 Re-baseline the belief table

One deterministic-interleave run per standing belief that predates the harness. Cheap (each is one
trajectory), and it upgrades beliefs to facts either way:

| Standing verdict | Settled with a reliable harness? | Action |
|---|---|---|
| C4 knobs (probing3, objShaving, interleave variants) | YES — RUN5 used the interleave closer | closed, do not re-run |
| 2026-06-24 "§1 no-ops" (AP-slice, per-cell memo) | NO — wall-clock era | one re-screen each (expectation low — the presolve argument is analytic — but now it becomes a fact) |
| Old knob screens: `numFullSubsolvers=8`, `detectLinearizedProduct`, division-floor removal, dGrawCutoff, per-probe read-set domination | NO — pre-interleave, 8 free-running workers | one re-screen each on F1+F2 |
| `perApRotRawCut` "perturbs without helping" | ✅ RE-SCREENED → **WIN, SHIPPED** (§7) | old verdict was a pre-interleave artifact; −18.8 % dual bound on free F1, marginal+no-regression on F4 → `DEFAULT.perApRotRawCut = true` |
| certcap, ceiling-skip, MP-target axes, §4 prunings | soundness rejections | stay rejected regardless of harness |
| C6 Lagrangian | ✅ DONE — removed (fires but 0.34 % box, cosmetic; §7 + §4 E0) | closed |

Deliverable of P0: a baseline table (`SOLVER_PERFORMANCE.md` §7) — F1–F7 × (M1, M2 trajectory, M3),
plus the re-screened belief table. Everything below cites this table.

---

## 2. P1 — diagnosis passes (localize before optimizing)

Run these on the fixtures that fail their M3 goal (F2, F4, F5, F7). Each pass produces a named
artifact appended to `SOLVER_PERFORMANCE.md` §7.

### 2.1 Primal/dual split

From the P0 trajectories: for each fixture, when is the eventual optimum first FOUND (objective
reaches it) vs when is it PROVEN (bound meets it)? If found-at ≪ proven-at (the known pattern:
"incumbent at ~15 s, dual bound decays asymptotically"), the fixture is **bounding-limited** and only
dual-side work (cuts, tighter boxes, decomposition, certificate) can help — primal ideas (hints,
search strategies, more workers) are dead on arrival for M3. If found-at ≈ proven-at, the search is
**exploration-limited** and hints/strategies matter.

**E1 — the definitive primal-ceiling test (cheapest experiment in this plan, run it first):**
re-solve each failing fixture with the known optimum injected via `addHint(...)`. Hints are a pure
primal warm start (they change no feasible set — sound by construction). If proof time is unchanged
with a perfect hint, the fixture is 100% bounding-limited and every primal-side idea is permanently
closed for it — one run buys a category-level verdict.

### 2.2 Bound-layer audit (which LAYER is loose?)

At the known optimum, compare each intermediate layer's **declared/derived bound** vs its **true
value at the optimum**: `grawLin`, the crit term `crit·(M+5K)`, the DI product `D`, `perHit`
(`D·Graw`), the per-AP rotation sum, and the final objective. Implement as a test seam (the
`precisionVarBoundsForTest` pattern): build the model, read each tracked box, print
`layer, declaredHi, trueAtOpt, ratio`. The layer with the biggest ratio is where the LP has room to
"float". This is how the §A4 conversion double-bank (+41% Graw) was found by hand — systematize it.
Also read the **root bound after presolve** (bound at the first det checkpoint) per layer-relevant
variant: it separates "the declared box is loose" from "the box is fine but the LP relaxation of the
encodings drops it".

### 2.3 Pin ablations (which DECISION layer owns the gap?)

Fix subsets of decisions to the known optimum and measure proof det-time: (a) items pinned, subs
free; (b) subs pinned, items free; (c) skills pinned; (d) crit pinned; (e) runes pinned. The subset
whose pinning collapses proof time owns the combinatorial gap. Expectation from history: subs
(the sub-heavy F2 regression appeared exactly when choosable subs went 27→47). If (b) collapses F2,
architecture experiments should target the sub layer specifically (see E8/E9 framing).

### 2.4 Encoding size audit

Per fixture: presolved model stats (variables, constraints, booleans — CP-SAT response carries
them). Look for blow-ups in the product encodings (`tTableProduct` / binary expansions) and the
sub/rune grids. A layer that is both LOOSE (2.2) and BIG is the primary surgery target.

### 2.5 JVM-side profile (only if wall ≫ det)

If a fixture's wall-clock is much worse than its det suggests, profile the Kotlin side
(async-profiler, one run): model build, domination, `reachableSumDomain` grids (C6 added λ×μ
sweeps), certificate orchestration. Otherwise skip — C3 already memoized the known rebuild cost.

---

## 3. Decision gate

After P0+P1 there is a table saying, per failing fixture: bounding- vs exploration-limited (2.1/E1),
loosest layer (2.2), owning decision layer (2.3), encoding hot spots (2.4). **Pick experiments from
§4 by what that table says — do not run Tier 2/3 experiments before the diagnosis exists.** The
expected shape of the verdict, from all prior evidence: F2/F4 bounding-limited with the gap owned by
the sub layer + product relaxations; F5 bounding-limited on the objective product; F7
budget/orchestration-limited, not model-limited. But let the table say it.

---

## 4. Experiment catalog

Every experiment: hypothesis → exact change → fixtures → success criterion (trajectory win per
§1.1) → soundness argument. Record win AND lose in `SOLVER_PERFORMANCE.md` §7 (negative results are
what stop re-litigating).

### E0 — C6 verdict: ✅ DONE (2026-07-07) → REMOVED

**Result (F4, `--pa11pm4po4`, real catalog, lvl 110, canonical 1w+interleave protocol):** the slice **fires** but
the box delta is only **0.34 %** (perHit bound 235.2M → 234.4M); the c6on/c6off bound trajectory oscillates ±6 %,
which is ~18× the 0.34 % the cut moves ⇒ **search-perturbation noise, not causal**. 3-repeat determinism confirmed.
This is the **"fires + barely-tighter box + trajectory-noise ⇒ remove"** branch: the perHit box is not the binding
face on constrained shapes, so the **"tighten perHit further" channel is spent** (also **demotes a C7 retry** for
constrained shapes). F4 is confirmed **bounding-limited** (opt found det≈100, bound 31 % loose at det800) — C6 was
the right category (dual-side) but the wrong lever. Full log: `SOLVER_PERFORMANCE.md` §7. Code reverted (C6 branch
dangling; harness in `stash@{0}`). **Next dual-side work targets the sub / product layers, not perHit (→ §2.2).**

<details><summary>Original E0 spec</summary>

On F4 (and one binding-`--pa` real-catalog shape): (1) does the Lagrangian slice ever WIN the min
(`maxDamageLagrangianFired` on the real model, not the fixture)? (2) if it fires, log untargeted-min
vs Lagrangian-min (box delta %); (3) c6on vs c6off bound trajectories. Outcomes: never fires ⇒
shadow price ≈ 0 on real catalogs (targets are fractionally free) — remove per the acceptance rule;
fires + box tighter + trajectory flat ⇒ the perHit box is not the binding face on constrained
shapes — remove AND record that the "tighten perHit further" channel is spent (this also demotes a
C7 retry for constrained shapes); trajectory improves ⇒ keep, and C7 gains a motive.
</details>

### Tier 1 — cheap, model-preserving

- **E1 — hint-incumbent proof solve** (described in 2.1). Also the production variant: hint the hard
  leg with the soft/phase-1 incumbent, hint probe N+1 with probe N's build. Ship only if a fixture is
  exploration-limited. Guard: hints OFF under `SolverTuning` (determinism, invariant).
- **E2 — conversion-conservation cut.** ✅ **SHIPPED (2026-07-08) → `DEFAULT.conversionConservationCut = true`.**
  The root-caused loose end: the LP banked moved critical mastery twice. It landed NOT as a new model constraint but as
  the missing half of C1 — the per-stat **`clampSlack`** in `damageMasteryCriticalReach` still counted the conversion
  `moved` term (`−moved`) at full domain, crediting a clamp-restoration for a source `moved ∈ [0, percent%·preSubStat]`
  can never reach. Excluding `conversionMovedVars` from the clampSlack tightens the `diff`/`graw` box. **Result: unsticks
  the lvl-245 flagship dual bound** (25.98M frozen → 23.29M, −10.4% @det800), −19%/−10% early on F4, free-110 a horizon
  wash. Sound: `withinBound` targeted lock (fires + no under-count) + objective-chain fuzz; no `CERTIFIER_VERSION` bump.
  Fixtures F1/F2/F4. Details: SOLVER_PERFORMANCE §7.
  <details><summary>Original E2 hypothesis</summary>
  The LP banks moved critical mastery twice because `tClamp`'s upper side is only combinatorially enforced. Add a linear
  conservation cut tying the conversion's source decrement to its destination increment (the moved quantity enters
  exactly one of the two clamped sums). Hypothesis: this is the remaining sub-layer looseness C1's aggregation didn't
  reach. Fixtures F2, F4. Soundness: the cut must hold for every integer-feasible build (exhaustive panel + fuzz).
  </details>
- **E3 — C8(2): most-masteries presolve/linearization parity** (`maxPresolveIterations=3`,
  `linearizationLevel=2`, currently max-damage-only). Fixture F5. Already specced; now measurable
  reliably.
- **E4 — item multiplicity merge.** Items identical on every objective-relevant characteristic
  collapse into one variable with count ≥ 1. Hypothesis: shrinks the pool presolve must chew
  (2.4 tells you if pool size even matters). Sound: bijection between solutions.
- **E5 — the §1.4 re-screen batch** (one deterministic run per old belief, listed above).

### Tier 2 — structural cuts / encodings (gate on the 2.2 audit)

- **E6 — crit-band disjunction.** ⏸️ **IMPLEMENTED + MEASURED (2026-07-08) → NOISY, kept flag-off.** Built as
  specced (`critBandDisjunction` flag; per-band `inBand ⇒ term ≤ hi·diff`; sound, exhaustive-panel locked — the
  crit-band variant passes). But despite crit×diff being the LOOSEST box (4.5×), the A/B is not a reliable win: it
  oscillates on F1 (worse than baseline at det600 +5.2%, catches up only at det800) and interferes with the shipped
  `perApRotRawCut` when combined. Kept OFF. **The key lesson (§7): a loose declared box is a candidate, not a
  guarantee — "box loose" ≠ "LP floats there"; the rotRaw layer (3.2× box) won, the crit×diff layer (4.5×) didn't.**
  The cut + soundness lock stay in-tree (re-testable — e.g. crit-capped scenarios where crit×diff dominates).
  <details><summary>Original E6 spec</summary>
  If 2.2 says the crit·(M+5K) relaxation is loose: per crit-band
  boolean with per-band redundant linear caps (`term ≤ bandMax_b` under band b), bands from the
  certificate's c-grid logic. The certificate already proves band-folding is where crit tightness
  comes from. Soundness: redundant cuts over an exact encoding — exhaustive panel + fired-assertion
  (the C7 lesson: prove the cut is non-vacuous AND run the exhaustive guard — C7's under-count was
  caught exactly there).
  </details>
- **E7 — per-slot budget cuts for the sub layer.** If 2.3 says subs own the gap: redundant cuts
  linking sub effects to their slot/socket budget at the LP level (the certificate's
  budget-sub-charging trick, in-model). Same guard discipline as E6.

### Tier 3 — architecture (big, talked-through, gated on P1)

- **E8 — certificate-DP as the primary prover (and, where tight, the primary SOLVER).**
  The DP already: (i) proves the free lvl-245 optimum alone, (ii) is exact enough that its argmax
  state == the incumbent, (iii) has provenance/backtracking machinery to NAME the winning state's
  items/subs/skills.
  > **✅ MEASUREMENT DONE (2026-07-08) → GO.** `manual E8 DP-solvable measurement` (test-only, no seam yet):
  > both **free lvl-110 AND free lvl-245 are DP-CONSTRUCTIVE** — the DP's argmax item set re-solves to *exactly*
  > the cell bound (ratio 1.0000; lvl-245 = 16,909,590, the campaign optimum, the shape where in-model CP-SAT
  > stays stuck at ~26M). So the default free search can skip CP-SAT: DP-seconds, not CP-SAT-minutes. Build the
  > structured-provenance seam next. Full result: `SOLVER_PERFORMANCE.md` §7.
  > **FEASIBILITY (researched 2026-07-08): moderate new seam, not blocked.** The backtrack
  > (`MaxDamageCertifier.kt` `certifyExplainAtAp` :619, match block :2811-2836) already resolves each
  > winning `Equipment`/`Sublimation`/skill option internally — it just emits `List<String>` display names
  > and drops the objects. The units bridge is production-proven: a cell's scaled bound is directly
  > comparable to a build's **`maxDamageRawProxy`** (NOT `FindMaxDamageScoring.computeScore`, a different
  > scale) — this is exactly `proveOptimality`'s `incumbentProxy ≥ maxCell` check (`MaxDamageSearch.kt:340`).
  > Measurement step = (1) new **structured-provenance** return: accumulate typed `CertPick` objects at the
  > existing match block (bump `CERTIFIER_VERSION` per the invariant); (2) argmax driver over
  > `certifyLedgerForTest`; (3) assemble `BuildCombination` (rune + sub-carrier reconstruction are the
  > fiddliest — DP tracks aggregate n/epic/relic, not carriers); (4) pin the build via
  > `maxDamageObjectiveValueForTest` (:776), read its raw proxy, compare to the cell bound → the
  > "DP-solvable fraction". Unreconstructable argmax ("provenance broken" :2843) ⇒ count as not-DP-solvable
  > (sound fallback). The existing `manual max-damage provenance build verification` test is the manual,
  > hand-typed version of this loop — it proves the concept but isn't automated. Architecture: run the fast ledger first (~seconds–70 s); **backtrack the argmax
  cell's state into a candidate build; validate it (`BuildCombination.isValid`) and score it with the
  real scorer. If score == cell bound, that build IS the proven optimum** — search over, no CP-SAT.
  If not (fantasy state / bail shape), fall back to today's flow with the DP bound as the badge.
  Measure first, build second: on F1–F4, backtrack today's ledger argmax and report the fraction of
  shapes where score == bound (the "DP-solvable fraction"). If it's high for no-target requests, the
  default GUI search collapses from CP-SAT-minutes to DP-seconds. Constrained requests keep the hard
  leg (the DP can't model targets — settled). Soundness is free: the DP stays a sound upper bound;
  the equality check is what promotes it to a constructive proof.
- **E9 — outer enumeration of the loose product axis.** ❌ **POC → NO-GO (2026-07-08, crit axis) — dominated by the
  certificate.** A test-only crit-band pin measured whether pinning the clamped crit to a band makes the stuck lvl-245
  solve prove. It does not, for two independent reasons: **(1) crit is the SMALL factor** of `crit×diff` — decompose the
  4.5× at the optimum as `(critCap·diffMax)/(crit_opt·diff_opt) = (100/85)·(diffMax/diff_opt) ≈ 1.18 × 3.8`; the
  looseness is dominated by the **diff factor (M+5K ≈ crit-mastery K; audit K 4.30× / diff 4.23×)**, not crit chance.
  Banding crit only lowers `b_hi` in `term ≤ b_hi·diff`, but the optimum has HIGH crit (≈85) so `b_hi` stays ≈ the
  baseline cap — no tightening where the optimum lives (POC: `band[68-100]` bound ≈ baseline; `band[80-89]` at det2500
  only −1%, still 35% gap, 47 min for ONE band). **(2) The certificate already IS this**, done better: its per-c grid
  outer-enumerates crit AND optimizes K/diff EXACTLY per crit value (the mastery/crit-mastery frontier DP). CP-SAT
  crit-band enumeration leaves K/diff to the loose LP relaxation — strictly weaker than the certificate, which exists
  precisely because in-model CP-SAT can't prove this. The residual 245 crit-cluster gap is the certificate's job (it
  proves the free optimum; E8 constructs it; constrained requests get the CP-SAT incumbent + the certificate bound).
  Scaffolding reverted; full data in SOLVER_PERFORMANCE §7.
  <details><summary>Original E9 hypothesis</summary>
  The repo's proven pattern (elements, AP probes, the certificate's c-grid): when an in-model product relaxation is the
  loose layer, enumerate one axis OUTSIDE the solve and make each inner solve linear/tight. Candidate axes, by 2.2's
  verdict: DI bands (inner objective becomes `band_hi × Graw`, linear), or crit bands. Proof = max over per-band proven
  solves (a partition, so the max over bands is the global optimum, like per-element enumeration). Cost: N_bands × a
  now-fast solve; wins if per-band proofs are ≫ N× faster than the coupled proof. Note: the in-model *encodings* are
  already exact (binary expansions); the looseness is in their LP relaxation, which outer enumeration sidesteps.
  </details>
- **E10 — full search ∥ certificate overlap. ✅ SHIPPED (2026-07-08).** `MaxDamageSearch.run` warms the
  certificate cache in the background off the FIRST streamed incumbent (threads = 1, B8-cancelled on
  mid-flight search cancellation / supersession, eligibility mirrors `proveOptimality`'s params-only
  gates, skipped under deterministic test tuning), and `MaxDamageCertificateCache.certificate` is now
  SINGLE-FLIGHT per key — the post-search proof reconstructs from the warm entry or joins the in-flight
  compute instead of duplicating the DP. Badge lands ~instantly at search end when the duration covers
  the warm-up (~35 s @110 / ~89 s @245). Front-ends unchanged; no CERTIFIER_VERSION bump. §7.
- **E11 — boss-mode budget restructure** (`SOLVER_PERFORMANCE` §1.4): phase 1 gets the full budget;
  plus E8's fast ledger as a per-element bound to ORDER (not skip — skipping was rejected as unsound)
  element solves, best-first. Targets F7's M3.

---

## 5. Guardrails

- Soundness invariants and the rejected-ideas list in `docs/perf-review-backlog.md` §0 apply to every
  experiment here. The C7 revert is the template: exhaustive guard + cut-fired assertion, always.
- Ship criterion: trajectory win (§1.1) on ≥ 2 fixtures of the matrix, regression on none, full
  `:autobuilder:test` + ktlint green. Model-changing experiments (E2/E4/E6/E7/E9) additionally run
  the fuzz lock and, if they touch anything the certificate consumes, re-check the
  `CERTIFIER_VERSION` rule.
- One experiment per branch/commit; results (win or lose) recorded in `SOLVER_PERFORMANCE.md` §7
  before the next one starts.

## 6. Suggested sequence

1. **P0** (harness CSV + fixture matrix + baseline + belief re-screens) — the foundation; everything
   else is uninterpretable without it.
2. **E1 + 2.1** on F2/F4/F5 — one run per fixture, buys the primal-vs-dual category verdict.
3. **2.2 + 2.3** (bound-layer audit + pin ablations) on the bounding-limited fixtures.
4. **E0** (C6 verdict) — ✅ DONE (removed; §4 E0). The box-delta probe (0.34 %) settled it and confirmed F4 is
   bounding-limited with the perHit box not the binding face — so dual-side effort moves to the sub/product layers.
5. Pick from the catalog by the decision gate (§3): expected first picks are **E2** (if the audit
   confirms the sub/clamp layer) and **E8's measurement step** (cheap, and its upside — DP-seconds
   instead of CP-SAT-minutes for the default search — is the largest on the table).
6. Tier 3 build-out only for experiments whose measurement step won.
