# Most-masteries performance plan — cut time-to-optimum at 245 (~138-149 s today)

Written 2026-07-11 from (a) the P0 GO/NO-GO measurement of the certificate campaign and (b) a
6-agent recon sweep (model encoding, primal heuristics, CP-SAT levers, prior verdicts, trajectory
analysis, completeness critique). Supersedes the *early-stop* ambition of
`MOST_MASTERIES_CERTIFICATE_PLAN.md` (see §1); its bound/DP machinery survives as this plan's P3.

---

## 1. P0 verdict: certificate early stop is NO-GO

Measured at 245 on the F5 production shape (distance mastery + AP12/MP/HP/crit, runes on, subs on,
production presolve p1/l1 + domination, det budget 600 s, 2 runs per arm, deterministic):

| arm | first emit | incumbent = optimum (M1) | proven OPTIMAL (M2) | objective |
|---|---|---|---|---|
| warm start OFF | 35.9–37.8 s | 147.1–149.3 s | 147.1–149.3 s | 10 705 |
| warm start ON  | ~0 s       | 138.5–138.9 s | 138.5–138.9 s | 10 705 |

**M1 = M2 to within milliseconds in all four runs.** The optimum arrives *with* the proof, so an
early stop has nothing to stop early. This is the NO-GO branch the certificate plan's §1 predicted.
A certificate can still award a badge or (P3) *construct* the optimum — it cannot shortcut this
search as designed.

### What the emission trajectories say (runs are effectively deterministic — <2 s divergence)

- **Dead zone t = 0 → ~36-38 s**: zero solver emissions in both arms (presolve + model build —
  the split is unmeasured, see P0.5). ~25 % of wall.
- **Staircase climb 38 → 127 s** with two ~22 s plateaus (76→98 s, 127→149 s off-arm). The last
  1.3 % of objective costs ~15 % of wall.
- **The greedy hint reseeds the whole search path** (disjoint value sequences; the hinted arm is
  *behind* mid-run, then wins the endgame via one large jump at ~89 s) but the **terminal ~22-24 s
  plateau at 99.9 % is hint-invariant** — both arms end with the identical signature. Net hint
  gain: 8.5–10.5 s of ~147 s.
- **Unresolved ambiguity**: solution callbacks cannot see the dual bound, so the terminal plateau
  is equally consistent with a *primal* wall (optimum genuinely hard to find) or a *dual* wall
  (bound descends slowly; the final +12 is produced by objective-shaving as the bound closes).
  Several recon findings depend on which it is — hence P0.5 below decides first.

⚠️ Single-shape evidence: every number above is F5@245. No winner ships without the §5 screen.

---

## 1bis. P0.5 OUTCOME (2026-07-11) — the wall is DUAL; re-ranking below supersedes §2's table

Measured on F5@245, production path, deterministic (both legs reproduced to the second across runs):

- **Oracle leg**: with the *known optimum's full assignment* hinted (equip + skills + runes + subs,
  19 793 vars), the incumbent sits at the optimum from **12.5 s** — and the proof still takes
  **146.5 s**. A perfect primal heuristic saves essentially nothing on time-to-proven.
- **Bound trajectory** (search log, `#Bound` lines): the dual is still **+14 %** above the optimum at
  t ≈ 115-140 s, **+5.4 %** at t ≈ 192 s, then collapses to close in the final ~2 s — CP-SAT proves
  by *tree exhaustion* (pseudo_costs), not by bound descent. The LP relaxation of the power-6
  penalty-product objective is too weak to certify anything early (the "foggy LP relaxation" the
  code doc predicted). Incumbent improvements mid-run come from `graph_var/arc_lns` workers.

Consequences — the §2 table re-ranks as follows:

1. **P3 (certificate as PROOF AUTHORITY) is promoted to the main track**, jointly with **P2a**.
   Not the P0-rejected *early stop of CP-SAT's own proof* — a replacement for the dual: tight DP
   bound U + incumbent ≥ U ⇒ proven, without waiting for CP-SAT. The oracle shows the incumbent
   side is solvable (~12 s with a good seed); the M3 tightness measurement of
   `MOST_MASTERIES_CERTIFICATE_PLAN.md` becomes the campaign's next gate.
2. **P2a (hard-constraints-first leg) stays the top model-side item** — it attacks the weak
   relaxation directly, and it is also the enabler of P3 on targets-met shapes (penalty == 1).
3. **P1b (ε-stop via relativeGapLimit) is MEASURED-DEAD on this shape**: the gap stays > 5 % until
   the terminal cliff, so any honest ε fires ~2 s before OPTIMAL. Do not build.
4. **P1a (objective floor) is downgraded to a cheap experiment**: it cannot tighten the upper
   bound, but the proof is by exhaustion, so pruning the tree from below *may* still shorten it —
   one deterministic A/B decides.
5. **P1c (restricted-pool race) is UX-only** (better early incumbents); it no longer claims proof
   time. P2b (overshoot split) keeps its slot: shrinking the ~1e14-scale objective fold is one of
   the few levers that can strengthen the relaxation CP-SAT exhausts against.

## 2. The plan at a glance

| phase | item | effort | expected | gate |
|---|---|---|---|---|
| P0.5 | diagnostic bundle: bound trajectory + subsolver attribution + oracle full hint + presolve/build split | S | information only — sizes the ROI of everything below | none — do first |
| P1a | hard objective floor from a *solved* greedy value | S | 10-30 % | none (sound by construction) |
| P1b | ε-stop: `relativeGapLimit ≈ 0.001` → "proven within 0.1 %" emission | S | ~15-17 % of wall (harvests the terminal plateau, primal- or dual-limited alike) | product decision on badge semantics |
| P1c | restricted-pool primal race (top-K pool leg → full-assignment hint + floor upgrade) | M | floor quality ↑; plausibly the best floor for the money | P1a shipped |
| P2a | hard-constraints-first leg (delete the power-6 penalty product from the searched model) | L | plausible 2-5× on targets-reachable shapes | P0.5 confirms the product objective is the fight |
| P2b | two-stage lexicographic: split the ×10 000 overshoot tie-breaker out of the searched objective | M | 10-30 % + LP conditioning for everything else | P0.5 |
| P3 | MM E8-analogue construct: one-cell (mastery, DI) frontier-DP bound U + hard `objective ≥ U` feasibility solve racing the search | L | order-of-magnitude ceiling on gated shapes; may fire never | after P1/P2 measurements; M3 tightness gate first |

Side bets (cheap lottery tickets, expectations deliberately low): full-layer hint (skills + runes +
subs + `repairHint`) — the mechanism gap is verified (only `equipVars` are hinted,
WakfuBuildSolver.kt:571-574) but the terminal plateau is hint-invariant, so state the expected
standalone impact as *low single digits, possibly zero on proof time*; greedy-ordered
decision-strategy dive worker; weapon-split (2H vs 1H+off-hand) decomposition spike; overlap
greedy-warm-start computation with `buildModel`; skill-var pruning for stats no target / weight /
overshoot credit touches (mind the §4 unsound-pruning traps).

---

## 3. Phase details

### P0.5 — the diagnostic bundle (do first, ~1 hour, zero production code)

One **serialized** instrumented run (no concurrent gradle; write to a file, not stdout — JUnit XML
swallows harness println):

- `solver.setLogCallback { file.append(...) }` + `logSearchProgress = true` wired through the
  existing `onSolverReady` hook (WakfuBuildSolver.kt:2395). ⚠️ line 2403 hardcodes
  `logSearchProgress = false` *after* the hook fires — verify the ordering or the capture is
  silently empty; check the log is non-empty within 10 s.
- Extend the solution callback (:2447) to log `bestObjectiveBound()` next to `objectiveValue()`.
- Extract: `#Bound` lines → bound(t) with per-subsolver attribution; solution lines → which
  subsolver produced the 89 s jump and the final +12; the presolve summary block → how much of the
  36 s dead zone is CP-SAT presolve vs Kotlin `buildModel` (stamp the Kotlin side too).
- **Oracle run**: `addHint` the *known* optimum's full assignment (equip + skills + runes + subs,
  read from a prior OPTIMAL solve) and measure time-to-OPTIMAL. That time is the hard ceiling of
  every primal-track investment.

Decision rules:
- bound(60 s) already within ~1 % of 10 705 → dual is tight early → bound injection is useless;
  levers are primal endgame + ε-stop; P3's value drops to badge-only.
- bound stays > ~11.5 k until late and collapses at close → dual-limited endgame → P3 (sound bound
  as a redundant `objective ≤ U`) gains a mechanism; note a sound *upper* bound is still vacuous
  for primal pruning (it never cuts a partial assignment), so it only ever helps the dual.
- oracle closes in seconds → primal tracks can nearly eliminate the 138 s; oracle closes at ~40 s →
  that 40 s is the primal ceiling and the rest is dual.

### P1a — hard objective floor from the greedy (mirror max-damage's `maxDamageRawFloor`, :564-565)

`model.addGreaterOrEqual(built.objective, F)`. **Derive F by fixing every decision var to the
greedy assignment and reading the solved objective** — never re-derive the value in Kotlin
arithmetic (an off-by-one above the true value makes the model INFEASIBLE and the flow emits
nothing). Subtract a 1-unit margin; keep a no-floor fallback; add a fixture where the greedy *is*
the optimum. All builds are feasible under soft targets, so the floor prunes purely
objective-wise from t = 0 (presolve fixing + floor-constrained LNS neighborhoods) and attacks the
38→127 s climb — ~65 % of wall — regardless of the P0.5 verdict.

### P1b — ε-stop (`relativeGapLimit`, currently unused anywhere in the solver)

`(bound − incumbent)/incumbent < 0.001` stops with a **proven-within-0.1 %** result at ~115 s on
the measured trajectory. This is *not* the rejected certcap (no bound is injected; CP-SAT's own
dual is the authority) and *not* the NO-GO certificate stop (no certificate involved). Ship as an
opt-in mode or two-tier emission (ε-proven build early, keep solving to OPTIMAL for the badge).
`ε = 0` on the deterministic test path or pinned-optimum tests break. Surface the badge honestly —
the "proven within X %" state already exists for max-damage.

### P1c — restricted-pool primal race

Side solve on a top-K-per-slot pool (K ≈ 15-30 by weighted requested-stat value) purely as a
**primal device**: its optimum is a feasible full-model build → full-assignment hint + floor
upgrade for P1a. Unsoundness of the cut is irrelevant — the full model keeps proof authority; a
bad cut just yields a weaker floor. This is E8's restrict-and-resolve shape (:1550-1562) without a
ledger, and it subsumes the fix-and-optimize matheuristic (one restricted solve instead of 10-15
windows). Budget-cap it; keep it off the deterministic test path.

### P2a — hard-constraints-first leg (the strongest structural candidate — two analysts converged
on the identical design independently)

> **E1 OUTCOME (2026-07-11): SHIPPED as the production most-masteries path.** Hard leg at F5@245:
> the SAME 10705 optimum proven OPTIMAL in **78.4 s vs ~147 s soft (1.88×)**. Wired: `hardConstraints`
> in `buildMostMasteriesObjective` (targets hard + unpenalized objective + overshoot tie-breaker kept,
> static-infeasibility skip propagated) and `WakfuBestBuildFinderAlgorithm.mostMasteriesHardThenSoft`
> (hard first; fallback to the soft model on INFEASIBLE, detected via the guaranteed final
> `progress == 100` send — the greedy warm-start emission makes "flow emitted" unusable — with the
> REMAINING user budget). **Deliberate semantics change:** reachable targets are now always met
> (the beta "conditions pas respectées" complaint class); the soft trade-off only survives on
> genuinely unreachable targets; `isOptimal` on the hard path = optimal among targets-met builds.
> Locks: hard == soft optimum on reachable-targets pools; soft-fallback on unreachable targets;
> E1 harness `WAKFU_MM_P2A=1`. Both suites green (autobuilder + gui-compose E2E).

Today's objective is `coreScore × penaltyMultiplier` through a 2001-entry power-6 table +
bucketed division (WakfuBuildSolver.kt:2237-2273, StatBuilder.kt:914-946) — the code's own doc
calls this "the penalty product, whose foggy LP relaxation traps the search" (StatBuilder.kt:1704).
Max-damage escaped the same pathology with a hard leg, and the machinery is already mode-agnostic
(`addRequiredTargetHardConstraints`, StatBuilder.kt:1719-1729; `hardConstraints` plumbing in
`optimize`, :493-497).

Design as SHIPPED (supersedes the earlier soft-leg-authority sketch): hard leg = `actual ≥ target`
+ plain diAdjusted mastery objective (+ overshoot semantics preserved); **the hard result IS the
answer whenever the leg produces a build** — the soft model runs only as the fallback when the hard
leg yields none (proven INFEASIBLE, or an UNKNOWN timeout without a solution — the real solver
status separates the two, exposed via `optimize(onTermination)`). Locked with hard == soft
optimum-equality fixtures on reachable-targets pools + the unreachable-fallback lock.

### P2a-E3 — unreachable-fallback cost (measured 2026-07-11, PRODUCTION path, 120 s budget)

> - **reachable F5@245: 13.7 s to PROVEN optimal in production** (cores−1 workers) — the 147 s P0
>   number was the 1-worker deterministic protocol, not what users experience. The user-facing
>   most-masteries problem is essentially solved by the hard leg.
> - static-unreachable (AP 99): statically skipped, soft gets the whole budget — fallback is free.
> - jointly-unreachable: **could not be constructed** — even PA 15/PM 8/CC 100/HP 10k (16.3 s) and
>   the absolute caps PA 16/PM 8/CC 100/HP 12k (112.4 s!) are FEASIBLE at 245 and prove
>   targets-met. Two consequences: (a) the INFEASIBLE-proof path is practically unreachable by
>   realistic requests (per-target static ceilings catch the rest; the UNKNOWN-timeout warning log
>   covers the residual), so no hard-leg budget cap is needed; (b) **near-frontier feasible shapes
>   are the new slow tail** (~112 s of a 120 s budget) — if a future campaign reopens perf work,
>   profile THOSE, not the F5 default.
> - Harness: `WAKFU_MM_E3=1` (three profiles, prints finalSends so a mis-crafted shape is visible).

### P2b — two-stage lexicographic overshoot split

> **E2 OUTCOME (2026-07-11): MEASURED-NO — do not enable.** A/B on the shipped hard leg at F5@245
> (same JVM, sequential, det 600, fixed seed): folded objective **49.2 s** vs two-stage split
> **157.0 s** (3.2× SLOWER), identical 10705 optimum both arms. The ×10 000 overshoot fold is not
> the enemy — it plausibly HELPS the proof (it breaks primary-score ties the plain objective must
> enumerate as plateaus, and feeds pseudo-cost learning). The implementation stays as a dormant A/B
> seam (`optimize(mmTwoStageOvershoot)`, default false, exact-equivalence lock green) plus the
> `WAKFU_MM_P2B=1` harness for future re-screens on other shapes; the `onTermination`/`SolveOutcome`
> plumbing it introduced is kept — it also fixes the INFEASIBLE-vs-UNKNOWN fallback conflation.
> Note the folded hard-leg baseline measured 49.2 s in this JVM vs 78.4 s in E1's separate run —
> wall-clock varies across sessions at equal det; in-JVM pairs are the comparable evidence.

`withOvershootTieBreaker` folds `primary × 10 000 + bonus` (:2293-2315): objective domain ~1e18,
one division + ~4 reified clamps per target on the objective path, and the primal keeps "improving"
through overshoot-only steps. Stage 1: maximize the primary alone. Stage 2: pin
`primary == best`, maximize overshoot in a short solve (infeasibility impossible), **before the
final emission on every exit path** (normal, stopWhenBuildMatch, ε-stop) — the emitted build must
still carry the overshoot behavior (skill dumps into HP/CC), locked by BuildCombinationTest + the
GUI E2E. Provably the same lexicographic optimum; also improves LP conditioning for every other
lever.

### P3 — MM E8-analogue construct (highest ceiling, widest variance, run LAST)

> **M3 OUTCOME (2026-07-11): NO-GO for the UNPENALIZED one-cell bound as proof authority — the
> certificate must be target-constrained.** Prototype (`MostMasteriesBoundPrototype`, test-side):
> exact (0.00 %) on all three small-pool fixtures (equipment / +runes / +runes+subs) once the
> solver's real gates were mirrored (scenarioGate fold, EC excluded in MM, SECONDARY_MASTERIES_AT_MOST
> two-worlds, ramps at reachable-source, exact per-branch skills); bound cost ~60-230 ms (M2 ✅).
> At F5@245: bound 11 909 vs **unpenalized** proven optimum **10 985** (+8.4 % prototype slack,
> mostly condition-bearing DI subs) vs **targeted** optimum **10 705**. The killer is the middle
> number: even a PERFECT unpenalized bound (10 985) sits **+2.6 % above the targeted incumbent** —
> a crit/AP-dumped build genuinely reaches 10 985 unpenalized, so a targets-met incumbent can never
> reach U and the badge never fires. The plan's §2 algebra (penalty ≥ 1 ⇒ unpenalized bound
> suffices) is refuted on this shape. **v2 design: bound the TARGETS-CONSTRAINED problem** — the
> required stats enter the DP as dimensions/gates (the max-damage certifier's cell architecture,
> which is exactly how max-damage solved the same problem via `maxDamageRawProxy` target gating) —
> and pair it with the P2a hard leg so the certified object is the hard-leg optimum. The soundness
> canary (`bound ≥ optimum`) caught two real under-counts during prototyping (perStatStep ramps,
> conversions) — keep it in every future lock.

One-cell frontier DP over (weighted-mastery, DI) → sound upper bound U (~1 s expected; reuse v15
family harvest budgets + the single-type rune fold verbatim) → feasibility solve with
`hardConstraints = true` + hard `objective ≥ U` + stopAtFirstSolution racing the normal search
(MaxDamageSearch.kt:273-310 pattern). Any solution found *is* the proven optimum; INFEASIBLE →
null → the normal search continues, cost ≈ one background solve.

v1 gates (F5 qualifies): single-or-no wanted element (the multi-element roll fold and
min-over-elements are loose across builds — the max-damage multi-element verdict transfers), no
forced runes, skills as a DP stage with best-case credit on PERCENT/major lines (over-count,
sound). Mandatory before building: the certificate plan's **M3 tightness measurement** — with
random-element lines credited at full value, a loose U makes the floor permanently INFEASIBLE and
the construct silently *fires never*. Soundness-bearing: `MM_CERTIFIER_VERSION`, fuzz `bound ≥
pinned CP-SAT` locks, exact-equality small-pool locks, the `certifierDroppedVars` ±1e7
tracked-domain gotcha for any unmodeled var.

---

## 4. Dead ends — do not retry (measured or proven)

- **Certificate-driven early stop for MM** — this plan's P0: M1 = M2, nothing to stop early.
- **(p3, l2) presolve/linearization for MM** — C8(2) MEASURED-NO on this exact shape: +33 %
  regression (`docs/perf-review-backlog.md`). The only untested residue is the `unset`
  (unlimited-presolve) arm as a *fresh* axis.
- **certcap** (bound capped at/below the incumbent) — soundness-rejection, permanent: prevents
  OPTIMAL. A P3 bound stays *outside* the model except as a sound `≤ U` redundant constraint.
- **Redundant sound *upper* bound as a primal lever** — semantically vacuous for primal pruning
  (can never cut a partial assignment); dual-only, gated on P0.5 saying the dual is the wall.
- **Targeted domination compare-set extension** — ~3 % pool, killed before build.
- **IntVar family/count encoding for sub copies (solver side)** — measured −19 % dual; families
  pay on the certifier/DP side only.
- **Expecting hint polish to move proof time** — measured twice (wash on time-to-OPTIMAL; the
  terminal plateau is hint-invariant). Full-layer hint stays a cheap side bet, not a pillar.
- **numFullSubsolvers=8** — measured null/regressive. A *different* value (e.g. 4, freeing cores
  for LNS/feasibility-jump primal workers) is a partially-screened axis: only after P0.5's
  attribution says dual workers idle early, and only under a multi-seed production-width median
  protocol (1w+interleave cannot measure portfolio composition at all).
- **The §4 unsound-pruning set** (`docs/SOLVER_PERFORMANCE.md:395-437`) — especially: never derive
  AP/MP/range domain caps from out-of-combat limits (caps are pre-sublimation; the overshoot
  tie-breaker *rewards* exceeding targets).
- **Symmetry work** — audited clean: domination dedupes identical items, rings are one pool with
  Σ≤2 + same-name ≤1 (no slot permutation), runes are a single-type fold, sub copies an ordered
  boolean chain. Nothing left to break.
- **Tight/reachable declared domains for the MM chain** — honest expectation LOW: precision's
  identical exercise (6ecd1258) measured *zero* change (presolve derives linear-chain bounds).
  Worth exactly one cheap deterministic A/B on the `nonNeg × factor` McCormick box under p1 (the
  single-pass presolve might not derive it), with the loose-vs-tight optimum-equality lock — and
  if it ships, whole-chain or nothing (the max-damage lesson: single-site tightening never
  propagates).

## 5. Validation protocol (unchanged discipline)

- Model/encoding changes: 1 worker + interleave, fixed seed, det-time / numBranches — the only
  accepted A/B evidence. Multi-worker wall medians (≥5 seeds, quiet machine) *only* where
  portfolio composition is itself the variable.
- Generalization screen before any production ship: (a) ~110 small-pool, (b) multi-element
  request (roll-assignment bools + min-over-elements), (c) precision mode (shares the StatBuilder
  chains P2b touches), (d) targets-unreachable shape (hard-leg fallback path). Per-shape verdict
  inversion is the norm in this codebase.
- Soundness locks: optimum-equality vs the soft path (P2a), exhaustive brute-force fixtures,
  `ε = 0` and floors/hints off on the deterministic test path (`SolverTuning` gating, invariant 4),
  fuzz `≥` locks + banked 245 oracle for P3.
- The P0 harness lives in the P0 agent worktree
  (`.claude/worktrees/agent-a06d68eda26d16f56`, WakfuBuildSolverTest "P0 gate") — port it into the
  repo as the standing measurement harness for every phase above.

## 6. Expected outcome

P1a + P1b alone plausibly take F5@245 from ~138 s to **~90-110 s to a proven-within-0.1 % result**
(floor compresses the climb, ε harvests the plateau). P2a is the shot at **2-5×** if P0.5 confirms
the penalty-product diagnosis. P3 is the order-of-magnitude ceiling but is gated, soundness-heavy,
and may legitimately conclude "fires never" — sequence it last, after the cheap levers have moved
the baseline.

## 7. Encoding campaign (2026-07-12) — post-P2a follow-up

Workload re-anchor: F5@245 is production-solved (~14 s); the driving shape is now the hard
near-frontier conjunction **AP16/MP8/CC100/HP12000** (~112 s production). Six proposals; measured
outcomes recorded here as they land. Harness: `MostMasteriesPerfExperimentTest`
(`WAKFU_MM_PERF_AB=1`, hard leg, p1/l1, 1w+interleave, same-JVM sequential arms).

### 7.1–7.3 Overshoot + product encodings — ALL MEASURED-DEAD (2026-07-12)

Canonical A/B (`WAKFU_MM_PERF_AB=1`, det 600, hard leg, p1/l1, 1w+interleave, seed 1, same JVM,
sequential arms). All f5 arms prove the same optimum (raw 107 054 998 / scored 10 705).

| arm | f5 det (base 35.24) | f5 branches (base 13 912) | frontier det (base 25.73) | verdict |
|---|---|---|---|---|
| `overshootExact` (HARD_EXACT_SIMPLIFIED) | **35.2378 — identical** | **13 912 — identical** | **25.7273 — identical** | **NULL**: literal no-op — presolve already derives the redundant `max(·,0)` elimination under the hard `actual ≥ target`. Keep CURRENT (less code). |
| `overshootHypograph` | 42.22 (+20 %) | 16 257, 46 conflicts | 34.62 (+35 %) | **MEASURED-NO** — the objective-induced encoding *removes* propagation the exact chain gives the solver. |
| `productTracked` | 44.54 (+26 %) | 13 662 | 36.07 (+40 %) | **MEASURED-NO** — matches the precision-mode lesson (6ecd1258), here actively harmful. |
| `productBinary` | 46.44 (+32 %) | 13 718 | 46.01 (**+79 %**) | **MEASURED-NO** — the max-damage binary-expansion win does NOT transfer to the MM product. |

The P2b pattern repeats: the shipped "naive" encodings out-propagate every hand-tightened
alternative. The seams stay in the codebase (CURRENT default, byte-identical) as the standing
A/B harness; do not retry these three arms.

### 7.4 Frontier shape is provably INFEASIBLE — reclassifies the slow tail (2026-07-12)

Every arm *proves* AP16/MP8/CC100/HP12000 **INFEASIBLE in ~23-26 det** (~23 s wall at 1w). The
earlier E3 conclusion "joint shape feasible (112.4 s)" misread the orchestrator output: E3's
SUMMARY line printed finalSends/objective but **not the hard leg's termination status**, and a soft-
fallback finalSend is indistinguishable from a hard-leg success in that line.

E3 re-run (2026-07-12, production path, 120 s budget) confirms:

- `reachable` (F5): finalSend 14.5 s, objective 10 705, **optimal=true** — hard leg, unchanged.
- `static` (AP 99): 11.9 s, optimal=true — static-skip fallback, unchanged.
- `joint`: single finalSend at **t=121.8 s, objective 6 533, optimal=false** — i.e. the hard leg
  proves INFEASIBLE quickly, then the **soft fallback burns the full remaining budget by design**
  (it is the pre-P2a penalized problem, whose dual wall P0.5 documented) and returns best-effort.

**Reclassification of the slow tail:** there is no "feasible near-frontier 112 s hard solve".
The remaining slow workload is the **soft leg** — both as fallback for unreachable conjunctions
and for any user who runs without required targets. That re-aims the remaining pistes: the M3
bound as a redundant dual cut (§7.7) attacks exactly that model; the per-item filter (§7.5)
only helps the already-fast hard leg and gets deprioritized accordingly.

### 7.5 Per-item conditional-ceiling filter — dry-run says GO (measured 2026-07-12)

`MostMasteriesConditionalCeilingAnalysisTest` (`WAKFU_MM_ITEM_CEILING=1`), frontier shape, production
domination pool (6 886 items after domination, 7 884 base):

- **rejected 2 277 / 6 886 = 33.07 %** of the pool — every rejection via the **AP 16** target
  (an item occupying its slot without enough AP leaves AP16 unreachable even with best-case
  everything else). By slot: BOOTS 694, CHEST_PLATE 488, TWO_HANDED 458, ONE_HANDED 434, CAPE 88,
  AMULET 79, rest <10 each.
- MP8 / CC100 / HP12000 reject **zero** items each (relaxed ceiling too loose or genuinely
  reachable with any single item fixed).
- The screen's relaxations only ADD reach (rune competition, sub colours/conditions, joint skill
  budgets, condition gating all ignored; conversions into a probed stat bail) — a rejection is a
  sound "cannot be in any hard-feasible build".

Next step (not yet coded): apply as a hard-leg-only pre-solve pool filter (or fixed-to-zero
booleans), keyed on the same `U(stat | item) < target` certificate; add an optimum-equality lock
vs the unfiltered model and re-run the frontier timing.

### 7.6 Certifier FAST-harvest coordinate index — measured, byte-identical, modest (2026-07-12)

`indexedFastHarvestApCoordinates`/`indexedFastHarvestCritCoordinates` +
`WAKFU_MAX_DAMAGE_CERT_INDEXED_HARVEST=1` seam; locks: 5 000-case coordinate fuzz + full-ledger
byte-identity (`MaxDamageCertifierHarvestIndexTest`) + real-shape 245 ledger equality (below).

245 fire ledger, threads=1, incumbent=16 909 590, same JVM protocol as the C-series:

- Ledger **byte-identical** (max 17 674 020, every cell equal; identical valid-coordinate counts —
  the index visits exactly the reference's accepted triplets).
- FAST tier Σ per-world: **12.9 s → 10.9 s (−16 %)**; ledger total **25.1 s → 22.8 s (−9 %)**.
- Reading: consistent with the C8 frontier verdict — the fast tier is add-call-volume-bound; the
  index removes only the rejected-cell *scanning*, which is ~16 % of the tier. Real, exact, cheap
  to keep behind the seam; flipping the production default is a certifier change (bump
  `CERTIFIER_VERSION` even though values are byte-identical, per the standing rule).

### 7.7 M3 bound as a redundant dual cut on the SOFT model — MEASURED-NO, 2.1× WORSE (2026-07-12)

Seam `SolverTuning.mmMasteryScoreUpperBound` (redundant `masteryScore ≤ U`); harness
`WAKFU_MM_SOFT_CUT_AB=1` (F5@245, soft leg, U = 11 909 from the M3 prototype, det 600, 1w+interleave,
same JVM):

- softBaseline: OPTIMAL, det **51.49**, 14 257 branches, wall 132.8 s.
- softM3Cut: OPTIMAL, det **107.42 (+109 %)**, 17 035 branches, 115 conflicts, wall 232.6 s —
  same raw/scored optimum (equality lock passed).

The externally-derived tight ceiling actively *hurts* the by-exhaustion proof — the extra
constraint feeds the LP/propagation stack without pruning the exhaustion tree. Together with §4's
"redundant upper bound is vacuous for primal pruning", the redundant-bound idea is now dead in BOTH
directions. **Do not retry**; the seam stays as the standing harness.

### 7.8 Campaign close (2026-07-12)

All six pistes measured. 1-4 dead, 5 dry-run-GO-but-deprioritized (the hard leg is already fast),
6 real-but-modest behind its seam. The soft leg remains the only slow workload (fallback +
no-required-target requests) and every cheap structural lever against its dual wall is now
exhausted — the remaining ideas (Lagrangian target-aware certificate, M3-v2 multi-dimensional DP)
are heavy, soundness-critical builds whose prize is a best-effort path where proof matters least.
Recommendation: stop here; revisit only if user-facing soft-leg latency becomes a real complaint.

## 8. Campaign 2 — exact multiplier-axis decomposition + certifier reuse (2026-07-12)

The maintainer explicitly reopened the performance work after §7.8. This campaign does **not**
retry any of §7's six encodings/cuts. Its central observation is different: a redundant bound around
the same nonlinear CP-SAT model was harmful, so remove the troublesome product from the searched
model and keep its proof logic in a small, exact outer algorithm instead.

Two workloads must remain separate:

- **S-soft:** the most-masteries soft model, either after a jointly-unreachable hard request or a
  request with no hard target. Its two possible nonlinear axes are the target-penalty bucket and
  `mastery × (100 + DI)`.
- **C-fast:** the max-damage certificate's incumbent-independent fast tier. Its values are already
  useful and sound; the remaining work is repeated DP volume, not bound tightness.

### 8.1 Measurement matrix and stop rules

Extend `MostMasteriesPerfExperimentTest`; do not create another one-off runner. Every solver arm is
1 worker + interleave + seed 1, same-JVM sequential, and records objective, bound, status, branches
and deterministic time. Use three 245 fixtures:

| id | shape | purpose |
|---|---|---|
| S1 | F5 reachable hard leg | regression canary; already fast |
| S2 | AP16 / MP8 / CC100 / HP12000, soft leg directly | real fallback workload, without spending the hard-leg prelude |
| S3 | distance-mastery request with no required stat | isolates the inner mastery×DI product |

Add one 110 small-pool and one multi-element fixture only to the soundness/generalization screen;
they are not allowed to decide a 245 performance winner.

For the certificate, reuse `manual max-damage certifyLedger end-to-end` at 110 and 245 with three
incumbents: huge (pure fast tier), the known optimum (badge-winning production shape), and a weaker
incumbent (flood-control regression canary). Record fast / tier-1.5 / exact / total separately.

Standing acceptance rules:

- a solver change ships only with the identical folded objective and `OPTIMAL` status on every
  equality fixture, and a reproducible ≥15% deterministic-time win on S2 or S3 with no material S1
  regression;
- an outer algorithm may return early only after all remaining nodes have a sound upper bound at or
  below the incumbent; UNKNOWN sub-solves fall back to the current monolithic model;
- a certifier change keeps `bound >= exact/CP-SAT` on fuzz, preserves the badge verdict, bumps
  `CERTIFIER_VERSION`, and re-runs the lvl-245 oracle;
- one seam and one verdict per commit. A dry-run pruning rate below 10%, or an outer decomposition
  needing more than 12 proven sub-solves on S2/S3, is an immediate NO-GO.

#### 8.1.1 Baseline (MEASURED 2026-07-12, canonical protocol, det 600, lvl 245, pool 7 890)

| id | model | status | det used | wall | branches | folded optimum (scored) |
|---|---|---|---|---|---|---|
| S1 f5 hard leg | hard | **OPTIMAL** | 43.57 | 54.6 s | 16 099 | 107 054 998 (10 705) |
| S2 frontier soft-direct | soft | **OPTIMAL** | **343.47** | 727.5 s | 22 063 | 67 295 807 882 856 (6 739) |
| S3 DI isolate (no required stat) | soft=hard | **OPTIMAL** | 97.98 | 316.1 s | 135 085 | 10 985 |

Key structural fact: the soft leg on the frontier shape **does prove OPTIMAL** under the canonical
1-worker protocol — det 343.47, ~12 min wall. The earlier "burns the budget, non-proven at 120 s"
reading was a budget artifact, not a hardness wall. S-A's GO bar on S2 is therefore total
deterministic time `< 343.47` with the identical folded optimum `67 295 807 882 856`. S3 shows the
bare mastery×DI core alone already costs det ~98 (135 k branches) — the inner-product wall S-C
would have to move.

Review notes folded into execution (2026-07-12): (1) the S-A node bound must use `power6(lo)` when
the interval's proven core bound is negative (the penalized objective's domain is signed); (2) S-D's
assumption literals reify constraints the hard leg currently posts plainly — the A/B must measure
the hard leg S1 with/without assumptions too; (3) S2 soft-direct starts cold whereas the production
fallback inherits the failed hard leg's incumbents — encoding-vs-encoding comparisons are fair, but
a measured win does not transpose 1:1 to user wall-clock.

### 8.2 S-A — exact outer branch-and-bound on the penalty bucket (priority 1)

**New mechanism.** `applyConstraintPenalty` currently searches
`core × power6(bucket)` inside one model. Instead, run a best-first interval branch-and-bound over
the integer penalty bucket `b` (`0..maxIndex`, at most 2,000):

1. For an interval `[lo, hi]`, constrain only `b in [lo, hi]` and maximize `core` — the penalty
   multiplication is absent from this sub-model.
2. If the sub-solve proves `core <= C`, then
   `C × power6(hi) × OVERSHOOT_SCALE + (OVERSHOOT_SCALE - 1)` is a sound upper bound for the whole
   interval because both factors are non-negative and the power table is monotone.
3. Score the returned feasible assignment with its actual bucket, update the incumbent, then split
   only intervals whose upper bound is strictly above it.
4. A singleton bucket has a constant multiplier, so its exact folded objective is linear:
   `core × constant × OVERSHOOT_SCALE + overshootBonus`. No product equality remains.

When the queue empties, the incumbent is the exact current soft optimum, including the overshoot
tie-break. If any interval times out without a trustworthy bound, abandon the outer run and execute
the current model on the remaining user budget.

Why this is not §7.7: M3 injected a globally redundant constraint into the same weak model. S-A uses
an upper bound **outside** CP-SAT to discard whole multiplier intervals and gives every CP-SAT
sub-solve a simpler objective. It is the same proof pattern as element/AP enumeration, applied to
the actual loose axis.

POC order:

1. expose `penaltyBucket`, `core` and `overshootBonus` on `MostMasteriesObjectiveVars` under a test
   seam;
2. implement the interval driver test-side only and print sub-solve count + queue trajectory;
3. run S2. GO only if it proves with <=12 sub-solves and less total deterministic time than the
   monolith;
4. only then productionize cancellation, budget accounting and streamed-incumbent merging.

#### 8.2.1 S-A POC verdict (MEASURED 2026-07-12): NO-GO as-is — the inner mastery×DI product is the wall

Driver: best-first interval B&B, node det 60, incumbents from every interval solution via the
(core, bucket) capture, sign-guarded bounds, exact singleton re-solve. Run on S2, cap 24 solves.

- **ABANDONED at 25 sub-solves, total det 1 415.5** — 4.1× the morning monolith proof (343.47).
- **No node proves.** Even the root (buckets 0..2000) is FEASIBLE at det 60 with dual bound 1.14×
  its own incumbent (10 949 vs 12 529); narrow top nodes return bounds up to 59× loose
  (obj 6 352 / bound 374 187). Without tight per-node bounds the pruning never bites.
- The mechanism itself behaved: best-first descended to the top buckets (~1 876-1 985), INFEASIBLE
  intervals pruned (1 978..1 985), incumbent reached 93.7% of the optimum (63.07T / 67.30T).
- **Root cause is the INNER core, not the penalty axis**: S3 already showed the bare mastery×DI
  product costs det ~98 to prove alone (135 k branches). Every bucket-constrained node inherits
  that price, and 12 nodes × ~100 det can never beat a 343-det monolith. Removing the penalty
  product leaves a sub-model that is still nonlinear where it hurts.
- This is exactly §8.4's anticipated branch: **S-C is now unlocked** ("run S-C if S-A exposes the
  DI product as the new inner wall"). Singleton bucket (S-A) + fixed DI factor (S-C) makes the
  sub-model fully linear — measure S-C on S3 first per §8.9 step 4 before any composed tree.

⚠️ **Reproducibility anomaly — RESOLVED as run-to-run search variance (2026-07-12)**: three runs
of the identical nominal S2 softBaseline gave det 343.47 (OPTIMAL, 22 063 branches), det 982.5
(FEASIBLE at 600-budget overshoot, same-JVM after 26 prior solves) and det 465.35 (OPTIMAL,
38 233 branches, fresh JVM). The **optimum is stable** (67 295 807 882 856 in all proofs) but the
1w+interleave search path is NOT machine-reproducible on this soft workload — det varies ±35%+
across JVMs. Consequence: soft-leg det comparisons are valid ONLY between same-JVM sequential
arms (the A/B harness's existing shape); never compare det across runs. The S-A verdict stands
same-JVM: outer 1 415.5 vs monolith 982.5 = 1.44× worse.

#### 8.2bis.1 S4 baseline (MEASURED 2026-07-12, fresh JVM, det 600)

S4 max-damage soft-direct does **NOT prove**: FEASIBLE at det 600.0, objective 14 509 755 840 720,
bound 60 613 048 133 730 (**4.18× loose**), 25 434 branches, wall 25.7 min, 94 emissions. The MD
soft leg is much harder than the MM one (S2 proves at det ~343-465) — consistent with the
certificate being the proof authority in max-damage. S-E's prize is real (today this leg never
proves), but its inner core (the D·Graw bilinear chain) is heavier than mastery×DI, so S-E needs
the S-C-style inner treatment even more than S-A did. Gated on the S-C verdict.

### 8.2bis S-E — transfer the outer bucket B&B to the MAX-DAMAGE soft leg (added 2026-07-12)

The max-damage soft fallback wraps its survivable damage score in the **same**
`applyConstraintPenalty` power-6 product (`WakfuBuildSolver.buildMaxDamageObjective`, non-hard
path). S-A's decomposition therefore transfers, and is even simpler there: max-damage has **no
overshoot tie-breaker**, so the driver drops the ×10 000 fold entirely — interval bounds are
`C × power6(hi)` (sign guard as in S-A) and an interval solution's captured
`core × power6(bucket)` is already the EXACT folded value (nothing dropped).

Wiring (shared, landed with the S-A seam): `constrainPenaltyBucketInterval` is mode-agnostic and
consumed by both objective builders; `SolverTuning.mmPenaltyBucketInterval` reaches
`buildMaxDamageObjective` too. An opted-in survivability floor stays INSIDE the sub-model — S-E
removes only the required-target axis; a floored core is a second product the sub-solve still
carries (acceptable: the floor is opt-in and OFF in the S4 fixture).

Scope note: on the soft leg the certificate is not the proof authority (it bails on penalized /
floored objectives), so a CP-SAT OPTIMAL here directly improves the user story — today the fallback
burns the remaining budget without a proof.

Fixture: **S4 = max-damage, frontier targets (AP16/MP8/CC100/HP12000), soft-direct, lvl 245, CRA**
(`WAKFU_MM_C2_BASELINE=1 WAKFU_MM_C2_BASELINE_FIXTURES=S4`). Order: measure the S4 baseline, then
run the S-E driver **only if S-A GOs on S2** (same mechanism — an S2 NO-GO kills S-E too).

### 8.3 S-B — incumbent-conditioned soft item ceiling (priority 2, dry-run first)

Generalize §7.5's per-item hard-feasibility ceiling into a **soft-objective** ceiling. For every item
`x`, compute a deliberately relaxed upper bound for builds containing `x`:

`U(x) = Ucore(x) × power6(UtargetBucket(x))`, then add the maximum possible overshoot bonus.

`Ucore` independently over-credits mastery and DI; `UtargetBucket` independently over-credits every
required target using the existing slot/rarity/skill/rune/sub ceiling machinery. Ignoring slot
competition, sub conditions and shared budgets only raises the value and is therefore sound. Bail
instead of guessing on an unsupported conversion or negative interaction.

Given the already-available greedy feasible score `L`, drop `x` only when the **full folded**
`U(x) < L`. Equality is kept because `x` may win the tie-break. This is not the hard-leg AP16 filter:
an item is allowed to miss every target if enough mastery can compensate; it is removed only when
even its best relaxed soft build cannot beat the incumbent. It is also not the harmful M3 global cut:
the successful outcome removes item variables before model construction.

Gates:

- dry-run S2 + S3: report rejected count by slot and which factor bound it;
- <10% rejected => DROP; 10-20% => keep as research seam only; >=20% => build the filter;
- exhaustive small-pool equality, seeded random equality, then full-pool S1/S2/S3 A/B;
- derive the floor from the production scorer/fixed assignment, never duplicate score arithmetic.

**VERDICT (MEASURED 2026-07-12): DROP — 0.00% rejected on BOTH shapes** (pool 6 892; floors from
30-det production incumbents: S2 34 325 550 902 856, S3 10 712 — both ≥ 51%/97.5% of the optimum,
so the floors were not the problem). Each U-factor independently credits a near-BiS build around
the probed item, so the folded product upper bound exceeds any real incumbent for essentially
every item. The hard screen (§7.5, 33.07%) bites because a per-stat ceiling meets a HARD target;
a folded soft product has no such cliff. Do not retry with tighter floors — the looseness is
multiplicative in the bound construction itself. (Machinery kept: [CeilingAnalyzer] now shared
with §7.5; the DI probe credits BestElementConcentration.)

### 8.4 S-C — exact outer DI-factor decomposition (priority 3, gate on S3)

The binary DI expansion in §7.1-7.3 stayed **inside** one coupled model and regressed. A different
attack is to move the DI factor outside CP-SAT on the one-factor branch of
`diAdjustedPerElementMasteryScore` (no requested elemental minimum):

1. interval-node `[dLo, dHi]`: constrain the clamped DI factor to the interval and maximize the
   non-negative mastery tier `M`;
2. sound node bound: `floor(Mmax × dHi / 100)`;
3. singleton `d`: maximize `floor(M × d / 100)` with `d` constant — a scaled linear expression plus
   integer division, no variable×variable product;
4. best-first split until every remaining bound is <= the incumbent.

Run this directly on S3. If S-A wins but its fixed-bucket sub-solves are still dominated by the inner
DI product, S-C can later become the inner oracle, but do **not** start with a two-dimensional
penalty×DI tree. Multi-element requests stay on the current model until the mono branch proves the
mechanism. Stop if the reachable DI interval needs >12 proven nodes or the fixed-DI solve does not
move the 1-worker dual curve.

#### 8.4.1 S-C POC verdict (MEASURED 2026-07-12): NO-GO — and it closes the whole decomposition track

Driver on S3 (DI-factor axis [50..5100], node det 60, exact incumbents from interval captures):

- **Completed exactly**: outer optimum 10 985 at factor 158 == the monolith optimum (equality lock
  passed), no abandon, INFEASIBLE pruning worked. Correctness of the mechanism is fully validated.
- **Performance: 21 solves, total det 465.3 vs monolith 97.98 — 4.7× WORSE.** Even fully LINEAR
  sub-models cost ~22 det each, because every node re-pays the full item-space search. The
  monolith solves the entire product-coupled problem in 98 det; a node is only ~4× cheaper than
  the monolith, and any interval tree needs ≥ 10-20 nodes. The arithmetic can never close.
- Generalized verdict: **exact outer decomposition on ANY single axis (penalty bucket, DI factor,
  or their composition) is dead for the soft legs.** The per-node floor is the item-space search
  itself, not the removed product. S-A (§8.2.1), S-C (here) and by direct implication S-E
  (§8.2bis — its inner core is even heavier) are all closed. Do not retry interval trees with
  CP-SAT as the inner oracle; only an inner oracle that does NOT re-search the item space per node
  (a DP/certificate-style bound) could revive this, which is the M3-v2 shape §8.9 explicitly
  deferred.

Track status after S-A + S-C: the solver-decomposition arm of campaign 2 is CLOSED. Remaining:
S-B dry-run (§8.3), S-D cheap A/B (§8.5, unaffected — it is a cut, not a tree), C-0/C-A/C-B
(certifier). The S-A/S-C/S-E seams stay in the codebase as measurement instruments (production
always passes null).

### 8.5 S-D — recycle the hard-leg infeasibility core as a valid soft no-good (cheap side bet)

Gate each hard target with an assumption literal. A proven-INFEASIBLE hard leg can then return a
sufficient assumption core `C`. In the fallback model define exact `meetsTarget_i` literals and add:

`sum(meetsTarget_i for i in C) <= |C| - 1`.

This cut is logically implied by the already-proven hard model: no real build can meet every target
in the core. It reuses information currently discarded between the two solves and contains no
heuristic coefficient. Its ceiling is probably modest — one core only removes the impossible
all-met corner — so build it after S-A/S-B as a small A/B. DROP if the returned core contains every
target and the deterministic trajectory is unchanged; do not build iterative core enumeration
unless the first cut measurably fires.

The local OR-Tools 9.15 Java API already exposes `CpModel.addAssumption(s)` and
`CpSolver.sufficientAssumptionsForInfeasibility()`, so this POC needs no dependency change.

**VERDICT (MEASURED 2026-07-13): DROP — both §8.5 DROP conditions met.** Mechanics fully work:
the assumption-gated frontier hard leg proves INFEASIBLE at the same price (det 26.8 vs 25.3
plain) and returns the sufficient core; the reachable S1 hard leg shows no reification tax
(assume 44.6 vs plain 55.1 det, within the ±35% run variance; same optimum, lock passed). But the
core names ALL FOUR targets (the cut only removes the "every target met" corner, which the
penalty already prices), and the soft no-good arm is WORSE: baseline OPTIMAL det 335.3 vs no-good
FEASIBLE-only det 983.4 — the same lesson as §7.7's M3 cut, any added constraint on the weak soft
model slows it down. Do not build iterative core enumeration. (Seams kept: assumption gating +
core capture may serve M3-v2 as a diagnostic of which target axes bind.)

### 8.6 C-0 — bank the already-measured indexed harvest win

This is not a new experiment, but reopening performance work makes leaving a byte-identical measured
win disabled hard to justify. Flip `indexedFastHarvestEnabled` to the production default, retain an
OFF reference seam, bump `CERTIFIER_VERSION` 15 -> 16, and run the coordinate fuzz + full ledger
byte-equality + lvl-245 oracle. The recorded result is fast tier -16%, ledger -9% serial (§7.6).

Land this separately before changing the crit grid so every later A/B uses the faster baseline.

**LANDED 2026-07-12**: default flipped (`WAKFU_MAX_DAMAGE_CERT_INDEXED_HARVEST=0` is the OFF
reference seam), CERTIFIER_VERSION 15 → 16 with a history entry. Acceptance: harvest-index
coordinate fuzz + full-ledger byte-identity (2/2), the full WakfuBuildSolverTest lock suite
(215 tests, 0 failures), and the lvl-245 fast-ledger oracle reproduced **bit-for-bit with no
re-bank** in 12.6 s (the v15 family budgets + the index have made the pure fast tier that fast).

### 8.7 C-A — coarser tier-1 crit grid, tier-1.5 as the adaptive refinement (priority 1 certifier)

`FAST_C_SEGMENT_STEP = 8` was chosen before the incumbent-aware tier-1.5 segment skip existed. The
new orchestration changes the optimum: tier 1 no longer needs to be uniformly tight; it needs to be
cheap and eliminate most cells, while step-1 tier-1.5 refines the few survivors.

Add a test-only step seam and screen `{8, 12, 16, 24, 32}` end-to-end. A coarser segment folds at a
higher crit endpoint and therefore stays a sound upper bound; it may merely leave more survivors.
The decisive metric is **total ledger**, not fast-tier time.

A fresh warmed 245 run on current `main` gives useful structural evidence (not a stable benchmark):
six worlds, 15-16 step-8 segments/world, fast `13.329 s` of total `13.332 s` when a huge incumbent
eliminates every cell. Thus halving segment count has a real ceiling, but the optimum/weak-incumbent
arms must show that extra tier-1.5/exact work does not consume it.

Ship the coarsest step that keeps the same badge decision on all three incumbent regimes and wins
>=10% total at both 110 and 245. Bound arrays need not be byte-identical, so all soundness/oracle
locks and the version bump apply.

#### 8.7.1 C-A verdict (MEASURED 2026-07-13): NO-SHIP — the win lives in the wrong regime

Screen at 245, threads=1, steps {8, 12, 16, 24, 32} × incumbents {huge, optimum 16 909 590,
weak 15 218 631} (seam `WAKFU_MAX_DAMAGE_CERT_CSTEP`, kept):

| step | huge (pure fast) | optimum (badge regime) | weak |
|---|---|---|---|
| 8 (prod) | 11 632 ms | 22 921 ms | 52 410 ms |
| 12 | 9 406 | 22 849 | 52 789 |
| 16 | 8 204 | **20 885 (−8.9%)** | 52 319 |
| 24 | 7 725 | 20 958 | 51 296 |
| 32 | **6 829 (−41%)** | 22 066 | 51 557 |

Badge decision invariant everywhere (tier2 survivors [16] and max 17 674 020 identical at every
step) — soundness confirmed. But the ≥10% total-ledger bar is met ONLY in the huge-incumbent
regime (pure fast tier, i.e. the CI oracle — not a user path). In the badge regimes the coarser
tier-1.5 segment-skip rows hand the refinement tiers back most of what the fast tier saves:
optimum-regime best is −8.9% (step 16), weak-regime is noise. Shipping would also re-bank the
245/110 oracles (cell values change with the step, unlike C-0). Below the bar → keep step 8.

Corollary — **C-B (§8.8) is closed by its own gate**: "attempt only if C-A leaves the fast tier as
a user-visible floor." The screen shows the badge-regime total is dominated by tier-1.5/exact
survivor refinement, not the fast tier (huge-regime fast ~11.6 s vs optimum-regime total 22.9 s,
and a −41% fast-tier cut moved the optimum-regime total by <9%). A fast-tier-only prefix
factoring has even less reachable headroom than C-A's step change. Do not build.

### 8.8 C-B — factor the weapon-world common prefix (priority 2 certifier, gated)

The same fresh run shows six worlds arranged as three `(conversion, critical-secret)` pairs, each
with `weaponsRestricted = false/true`. Stage instrumentation shows the non-weapon item stages have
exactly identical transition counts within every pair; today they are recomputed twice.

The substantial version is two increments:

1. A/B a byte-identical fast-tier stage order with the weapon stage after ordinary slots, rings and
   skills (but before weapon-conditioned sub transitions). Stage transitions are additive, yet the
   equality lock is mandatory because intermediate AP/crit pruning is subtle. Revert immediately if
   the order alone inflates the frontier.
2. Only if increment 1 is neutral/winning, compute that common prefix once per
   `(conversion, critical-secret)` regime, snapshot it, then fork into unrestricted/restricted weapon
   suffixes. Process the pair serially to cap peak memory; parallelism remains between independent
   regimes.

The theoretical ceiling is meaningful only after the stage move: rings + skills + ordinary slots
account for roughly half of the observed per-world scan volume, whereas the prefix before today's
early weapon stage is small. Acceptance: byte-identical cell values, provenance either identical or
replaying to the same bound, >=15% fast-tier win, peak heap <=1.2x. Otherwise keep the simpler six
independent passes.

### 8.9 Execution order

1. Add the S1/S2/S3 matrix and close the baseline (no production behavior change).
2. Build **S-A test-side**. It has the highest upside because it deletes the exact product identified
   as the soft dual wall. Stop the solver track if its node count explodes.
3. In parallel with S-A measurements, run **S-B dry-run**. It is cheap and can reject itself without
   production code.
4. Run **S-C** only for S3 or if S-A exposes the DI product as the new inner wall.
5. Run **S-D** last as a cheap cut; it must not delay the structural work.
6. Certifier: land **C-0**, then screen **C-A**. Attempt **C-B** only if C-A leaves the fast tier as a
   user-visible floor.

Do not start M3-v2 or a target-aware Lagrangian certificate in this campaign. S-A/S-C first test the
same decomposition thesis with CP-SAT as the exact inner oracle, far less new soundness code and a
clear early node-count kill switch.

**AMENDED 2026-07-12 (maintainer GO): M3-v2 is approved as the follow-up once the §8 queue closes**
(S-B verdict, S-D, C-A). The S-A/S-C measurements sharpened its brief: the decomposition mechanism
is correct but dies on an inner oracle that re-searches the item space per node; M3-v2 is exactly
the missing oracle — a target-aware multi-dimensional DP (extending the M3-v1 (mastery, DI) cell,
bound 11 909 vs optimum 10 985 on S3) that sweeps the item space ONCE and yields sound per-bucket
bounds on the full folded soft objective. Two consumers to evaluate, in order: (1) a soft-leg
certificate — prove the CP-SAT incumbent optimal externally and STOP EARLY (the max-damage badge
pattern, directly user-visible on today's never-proving fallbacks, incl. S4); (2) the inner oracle
of the S-A interval tree. Certifier discipline applies wholesale: never under-count, bail when
unsure, fuzz lock `bound ≥ pinned CP-SAT`, measured verdict before any production wiring.

### 8.9bis M3-v2 measurement log (2026-07-13, test-side prototype `MostMasteriesTargetBoundPrototype`)

Target-aware DP: state `(DI, AP, MP, CC, HP, epic, relic) → best M`, achievement dims saturated at
their target and bucketed UP; stages = exact ring/weapon pairs (dominance-pruned per-item option
sets, pair stages first while the state space is tiny) → single slots → subs knapsack (10/1/1,
carrier binding, world B) → skills (per-branch enumeration, %HP multiplicative on the dim, AFTER
subs for soundness). Soundness canary (`bound ≥ banked optimum`) assertive and green throughout.

| increment | S2 (frontier, folded) | S3 (core) | states | wall |
|---|---|---|---|---|
| 1: coarse grids, two-stage rings/weapons | +38.26% | +15.28% | 1.24M | 28 s |
| 2: exact pairs + multiplicative %HP | +31.28% | +12.38% | 1.23M | 32 s |
| 3: fine grids (DI exact, CC 2, HP 100) | **+25.35%** | **+8.41% = v1 parity (11 909)** | 12.15M | 207 s |

Reading (superseded by increments 4-6 below): the v1 core floor carries over intact; the
target-axis cost is the ratio between the two columns.

**Increments 4-6 (2026-07-13, maintainer GO "on va au bout"):**

| increment | S2 | S3 | note |
|---|---|---|---|
| 4: exact AT_MOST conditions (cap-flag states: Inflexibilité AP≤10, Constance CC≤10, Mesure III CC≤50 — all EPIC ⇒ 2 bits, no world explosion; Armure lourde MAX_MP−1 rider) | **+11.20%** | +8.41% | the target-specific cost collapses 15.6% → 2.6% |
| 5: constraint semantics (REJECT paths over the threshold, ceil-lenient budgets; condition read w/o the sub's own contribution) + cap stats tracked even untargeted | +11.20% | +8.41% | no move — the conditions are genuinely satisfiable at ~no mastery cost in the model |
| 6: STATE-DEPENDENT ramp (Poids Plume MP→DI priced at the path's own MP dim at collapse; MP tracked even untargeted) | +11.20% | **+6.77%** | attribution named the ramp as the largest single over-credit (Δ1 743); S2's binding state is elsewhere |

Attribution harness (diag toggles, UNSOUND — deltas only): no layer is pure over-credit (removing
any of subs/skills/runes drops the bound BELOW the optimum — the optimum uses them all); the
looseness is interaction-level. Current state: **S2 +11.2%, S3 +6.8%, DP wall ~200-235 s serial at
fine grids (12-14M states, 6g heap)**. Next tightening candidates (diminishing): S2's binding-state
provenance (needs DP backtracking), oracle-build layer pricing (captureAssignment vs DP credits),
Secret critique's CRITICAL_MASTERY_AT_MOST (needs a crit-mastery dim). The RACE harness (§ below)
measures the user-facing value at the current tightness before any further spend.

**RACE VERDICT (MEASURED 2026-07-13): NO-WIRE — production CP-SAT beats the certificate on its own
turf.** S2, production path (multi-worker wall-clock, domination, 600 s budget), sequential legs:

- Solver: first build at 0.05 s, best incumbent (= the optimum) at 191 s, **proven OPTIMAL at
  199 s wall** (det 1040 — multi-worker parallelism turns the 1w det-465/19-min profile into
  3.3 min). The campaign premise "the soft leg never proves in-budget" was a 1-WORKER artifact:
  production proves S2 fine at a 600 s budget.
- DP bound: 227 s wall — ARRIVES AFTER THE FULL PROOF, and would only say "proven within 11.2%"
  where the solver already says OPTIMAL, exactly.
- On budgets where the solver genuinely cannot prove (60-120 s user budgets), the 227 s serial DP
  cannot either. The one remaining never-proves workload is the MAX-DAMAGE soft leg (S4, bound
  4.18×) — outside this DP's objective; a D·Graw-core certificate would be a separate build.

M3-v2 CLOSED as measured NO-WIRE **for fast machines**. Deliverables kept as reusable instruments
(all test-side, all soundness-canaried): the target-aware DP with exact AT_MOST-condition state
modeling, the constraint-vs-cap semantics distinction, the state-dependent ramp, the attribution
harness (diag toggles), the sub-condition inventory, and the race harness itself.

**REOPENED as the LOW-CORE BACKUP (maintainer scenario 2026-07-13)** — on a 2-4-core machine the
proof profile is the 1-worker one (~15-20 min), where a single-thread DP genuinely wins. The
presumed blocker (low-core ⇒ low-RAM vs the fine grid's ~6 GB) fell to the grid screen
(`WAKFU_MM_M3V2_GRIDS`, steps now mutable + per-grid JVMs):

| grid (DI/CC/HP) | S2 bound | states | est. heap | wall |
|---|---|---|---|---|
| 1/2/100 (fine) | +11.20% | 14.2M | ~6 GB | 227-235 s |
| 1/10/500 (coarse) | **+11.20% — IDENTICAL** | 580k | **~33 MB** | **15.2 s** |
| 2/10/500 | +11.20% — identical | 580k | ~33 MB | 14.0 s |

The binding state saturates its targets, so achievement rounding never touches it — ALL of the
bound's precision comes from the condition modeling (increments 4-6), none from grid fineness.
Operating point for the backup: coarse grid, **≤15 s wall on this machine (≈30-60 s on a weak
core), trivial memory, "proven within ≤11.2%"** — against a 15-20 min 1-worker proof. Wiring
proposal: trigger ONLY when the soft fallback terminates non-OPTIMAL (and the shape is supported —
bails hide the badge, never fake it); opt-in "check quality (~1 min)" or automatic when
`cores ≤ threshold`.

### 8.10 Campaign 2 close (2026-07-13)

Every §8 item measured, one ship:

| item | verdict |
|---|---|
| S1/S2/S3/S4 baselines | banked (§8.1.1, §8.2bis.1); S2 soft PROVES (det ~343-465); S4 MD-soft does NOT (bound 4.18×) |
| S-A outer bucket B&B | NO-GO — mechanism correct, inner mastery×DI wall (§8.2.1) |
| S-C outer DI-factor B&B | NO-GO — closes the whole decomposition track: per-node floor = the item-space search (§8.4.1) |
| S-E max-damage transfer | closed with S-A/S-C (seam landed & kept) |
| S-B soft item ceiling | DROP — 0.00% rejected, multiplicative looseness (§8.3) |
| S-D infeasibility-core no-good | DROP — full core + soft slowdown; no reification tax though (§8.5) |
| **C-0 indexed fast harvest** | **SHIPPED — production default, CERTIFIER_VERSION 16, oracle bit-for-bit (§8.6)** |
| C-A crit-grid step | NO-SHIP — ≥10% only in the huge-incumbent regime (§8.7.1) |
| C-B world-prefix factoring | closed by its own gate (§8.7.1 corollary) |

Protocol fact banked along the way: the 1w+interleave soft-leg search path is NOT
machine-reproducible run-to-run (det ±35%+ at a stable optimum) — soft det comparisons are valid
only between same-JVM sequential arms.

Next (maintainer GO, §8.9 amendment): **M3-v2** — the target-aware multi-dimensional DP bound,
consumer (1) = soft-leg certificate / early stop first. All §8 seams stay as measurement
instruments (production passes null everywhere; the only default that changed is C-0's).

### 8.11 Backup certificate SHIPPED (2026-07-13)

Wired end-to-end (maintainer UX choice: AUTOMATIC + phase display):

- **`MostMasteriesCertificate`** (main sources — the promoted M3-v2 DP, coarse grid default
  DI 1/CC 10/HP 500; steps stay mutable for the measurement harnesses in
  `MostMasteriesCertificateTest`: tightness, attribution incl. `netNegatives`/`noSecretCritique`
  sizing toggles, grid profile, race).
- **`SolverResult.mostMasteriesObjective`**: the certificate-comparable raw objective, stamped only
  when the searched model matches the certificate's units (MM soft leg, or MM without required
  targets; measurement seams excluded).
- **`WakfuBestBuildFinderAlgorithm.proveMostMasteriesQuality`** → `MostMasteriesProof`
  (ProvenOptimal / ProvenWithin(percent) / Unavailable), mirroring `proveMaxDamageOptimality`;
  bails hide the badge, never fake one.
- **GUI**: automatic trigger after a most-masteries search whose CP-SAT leg ended non-OPTIMAL,
  through the EXISTING ProofState pipeline — the user sees "Vérification de l'optimalité… (Xs)"
  then "Optimal prouvé à X% près" (or the proven-optimal headline if the incumbent reaches the
  bound). No new UI surface needed.
- **Locks**: CI soundness fuzz (3 seeded pools, bound ≥ pinned CP-SAT soft optimum — also locks the
  stamping end-to-end); full autobuilder suite + gui-compose suite green.

Known bound-quality roadmap (measured, §8.9bis attribution): exact negative-mastery penalty
(~91% of the S3 residual, ~29% of S2's) needs per-penalized-mastery state dims — a vNext; the
S2-specific remainder is cross-slot interaction looseness. Current guarantee: within ~11.2% on the
frontier shape, delivered in ~15 s (fast core) / ~30-60 s (weak core).

### 8.12 Tightening increment 7 — negative-mastery penalty: MEASURED-NEUTRAL, track closed (2026-07-13)

Design executed: signed saturated deficit dim for the dominant penalized char (pool stats: 60/6 892
negative items, BACK/BERSERK only), exact item deltas, offset-rune composition axis, sound sub/skill
positive constants, low-end bucket reconstruction (under-counts the penalty — sound), CI lock green.

**Result: the S2/S3 bounds did not move by a single unit (74 834 204 489 999 / 11 729) while states
rose 580k → 5.8M and wall 15 s → 259 s.** Reading: modeled FAITHFULLY (cross-item positive offsets,
as the model's `min(total, 0)` arithmetic allows), the penalty is ~zero on the binding paths — 616
items carry +BACK lines, deficits melt legitimately. The attribution's "91% of the S3 residual"
was a DIAGNOSTIC ARTIFACT: the `netNegatives` toggle nets per item, forbidding cross-item offsets,
so it over-penalizes relative to the model. Increment REVERTED (the shipped operating point stays
+11.2% / 15 s / 33 MB); the `negstats` design-gate harness is kept.

Do-not-retry: per-char deficit tracking in this DP. The remaining overshoot is CROSS-SLOT
INTERACTION looseness (per-slot BiS-on-all-axes options combining beyond any real build) — the
only known lever is per-slot option-set coupling (certifier-exact-tier scale); revisit only if a
user-facing need for a tighter X% materializes.

### 8.13 Cross-slot coupling increment 8 — the block gate: SHIPPED (2026-07-13)

Instrument first (§ user GO "couplage d'options par slot"): binding-path PROVENANCE — per-stage
state retention + backward reconstruction with option identity (`Opt.src`, `bound(provenance =
true)`, `WAKFU_MM_M3V2_PATH=1`). The S2 path exposed the target: **Mesure (EPIC, +10 DI/+10 CC)
credited while its BLOCK_AT_LEAST-40 condition was unreachable by the binding build.**

Fix: a BLOCK dim (0..40 sat, step 5, 4 bits) fed by EVERY source (item lines, sub credits incl.
through the normal knapsack packing, block skills — a missed source could wrongly deny = unsound);
AT_LEAST gating on the OVER-counted dim (never wrongly denies a real build); the EPIC/RELIC sub
stages move AFTER skills so the gate reads the full final sheet (gating earlier could deny a build
whose later layers supply the block).

| | S2 | S3 | states | wall |
|---|---|---|---|---|
| before | +11.20% | +6.77% | 580k | 15 s |
| after (step 5) | **+9.87%** | +6.77% | 4.6M | 83 s |
| step-10 attempt | +11.20% (gain erased) | — | 2.6M | 44 s |

Step 5 is the sweet spot: the gate's tightness lives in the per-OPTION ceil (small +3/+9 block
lines must not round to a threshold's worth — step 10 measured the whole gain away). The binding
path now PAYS for its block sources (M 6 239 → 6 164 with Dérobade continue/Ravage secondaire in
the path) — the coupling works as designed. CI soundness lock green throughout.

### 8.14 ROADMAP — Lagrangian-DD POC (maintainer GO 2026-07-14)

**Context for resumption.** Shipped state on `perf/mm-campaign-2` (6 commits: `0ee72378` perf C-0,
`583feff7` feat quality badge, `0b002aba` docs neg-penalty NEUTRAL, `ef57cbd2` perf block gate,
`b06f3df8` feat two-tier badge, + this roadmap): the most-masteries backup certificate
(`MostMasteriesCertificate`, a relaxed decision diagram in the literature's terms) awards
"proven within X%" automatically on non-proven soft results. Operating points: QUICK tier
(no block gate) +11.2% / ~15 s / ~33 MB; FULL tier +9.87% / ~83 s (states 4.6M). CI soundness lock
(3 seeds) green; provenance instrument (`WAKFU_MM_M3V2_PATH=1`) reconstructs the binding path.
PENDING measurement: the fine-grid (1/2/100) point post-block-gate — tells how much of the +9.87%
is merge/bucket loss (its own >20-min wall already confirms finer grids are not the way).

**Why Lagrangian-DD.** Literature check (2026-07-13/14, links in the conversation/§ above):
our certificate = a relaxed DD; the field's standard tightening at CONSTANT state size is
Lagrangian arc-cost augmentation (Bergman/Cire/van Hoeve, *Lagrangian bounds from decision
diagrams*): dualize valid inequalities the state does not enforce, re-sweep the SAME DP per
multiplier, minimize over λ by subgradient. Sound for ANY λ ≥ 0 by weak duality (given VALID
inequalities). k sweeps × 15 s = wall-bounded by construction — exactly the docs corollary
("move the 1-worker curve; don't chase solver knobs" — SOLVER_PERFORMANCE.md final verdict,
which also buries objShaving/probing3 for good).

**POC design sketch (the λ-design is the actual work):**
1. Candidate valid inequalities to dualize, by suspected residual share:
   (a) the TRUE 10-normal-sub cap across knapsack + flagged extra stages (today over-counted by
   ≤2 free rides); (b) saturation-loss coupling: arcs know their PRE-saturation contributions, so
   overflow beyond a dim's cap can be λ-priced instead of silently absorbed (design care: the
   inequality must stay valid — overflow ≥ 0 always true; the useful form is a correlation cut,
   to be derived from binding-path data); (c) any inequality suggested by fresh provenance runs.
2. Subgradient loop OUTSIDE bound(): λ₀ = 0 (today's bound), k ≤ 8 iterations, step from the
   observed violation; keep the MIN bound across iterations (each is independently sound).
3. Acceptance gates: soundness canary green at every λ (CI lock + S2/S3 ≥ optimum); tightness
   S2 ≤ +8.9% (≥1pt) to continue past the POC; wall ≤ ~2 min full tier (the two-tier GUI hides
   it anyway); one seam + one verdict per commit, plan §8.14 updated with numbers.
4. NO-GO exits: multipliers never bind (violations ~0 on binding paths — then the residual is
   real cross-slot correlation and the next step is Peel-and-Bound, below) or wall blows.

**Follow-up (bigger, only if POC shows binding multipliers or dies cleanly): Peel-and-Bound.**
DD branch-and-bound (Rudich et al. 2022/JAIR 2023; ddo framework; CODD ECAI 2024): our relaxed DD
+ a restricted DD (drop states instead of merging — trivial variant of the same code) + exact-
cutset branching = an EXACT solver for the soft leg, replacing CP-SAT's 15-20-min 1-worker proof.
Campaign-scale; assets already in place (DP, harnesses, oracle locks, provenance).

**Standing discipline reminders:** soft-leg det comparisons same-JVM only (±35% run variance);
no concurrent gradle during timings; manual-harness println lands in JUnit XML; env-gated runs
need `rtk proxy env`; measurement scripts must not run in two copies (file races).

#### 8.14.1 POC execution log (2026-07-14)

**Candidate (a) — the TRUE 10-normal-sub cap: ANALYTIC NO-GO, no sweep needed.** The λ seam was
implemented (`bound(subCapLambda)`: −λ per normal-sub arc across knapsack + flagged stages, +10λ
add-back at collapse — sound for any λ ≥ 0 by weak duality) plus a `WAKFU_MM_LAGRANGE=1` sweep
harness. Before sweeping, the S2 binding-path provenance run (full tier, wall 362 s with
provenance retention) settled it a priori: the binding path takes **NORMAL x9 in the knapsack +
Poids Plume I flagged = exactly 10 normal subs**. A count-10 path is *invariant* under the
dualization (−10λ arcs + 10λ add-back cancel), so `bound(λ) ≥ bound(0)` for every λ — the
multiplier can never bind. This is precisely the roadmap's NO-GO signature, hit analytically.

**What provenance actually indicts: per-option CEIL, not cross-slot correlation.** The S2 binding
state claims cc=100 and hp=12000 (both targets saturated), but summing the path's REAL
contributions: CC = 3 base + 6+7+3 items + 70 subs + 10 Mesure = **99 < 100**, and HP = 2500 base
+ 5765 items = **8265 ≪ 12000** — the missing ~3.7k HP is manufactured by `ceil(hp/500)` per item
across 11 item options (up to 499 phantom HP each). Neither λ candidate has anything to price on
this path (no dim overflows its cap either — candidate (b) doesn't bind here). The residual is
GRID (merge/bucket) loss — OR real merge looseness. The fine-grid arms arbitrated.

**Grid arbitration: the bound is GRID-INVARIANT — the residual is real merge looseness, not
bucket loss.** hp-only fine grid (1, 10, 100): bound **73 934 594 729 999 = +9.87%, bit-for-bit
identical** to the shipped coarse grid, at 23.3M states / ~1.3 GB / 469 s. (The ceil-exploiting
coarse binding path is real, but at fine grid ANOTHER merged path reaches the exact same value —
the DD merge glues one path's best-M onto another's target saturation.) The full fine grid
(1, 2, 100) never finished: killed after >80 min wall at ~3 GB+ heap — the wall alone rules it
out even as a measurement, and the hp-arm invariance predicts it would change nothing but the
≤1-crit phantom.

**POC VERDICT: NO-GO — clean death, exactly the roadmap's exit.** No valid inequality among
candidates (a)/(b)/(c) is violated on binding paths; the +9.87% residual is relaxed-DD MERGE
looseness (states fuse incomparable paths' best coordinates), which duals cannot price and only
branching can cut. The λ seam was reverted (provably inert code has no place in a
soundness-critical file); this log + the git history of the branch are the POC record.
**Next per the roadmap: the Peel-and-Bound campaign** (relaxed DD + restricted DD + exact-cutset
branching = an exact soft-leg solver) — campaign-scale, needs its own maintainer GO.

### 8.15 CAMPAIGN — Peel-and-Bound (maintainer GO 2026-07-14)

**Prize.** An EXACT soft-leg answer (true optimum + proof) from the DD machinery: replaces the
15-20-min 1-worker CP-SAT proof on low-core machines, and upgrades the badge from
"proven within 9.87%" to "proven optimal" (or a small residual %) everywhere the shape is
supported. Reference points: prod multi-worker CP-SAT proves S2 in 199 s; the relaxed DD gives
+9.87% in 83 s (full tier) / +11.2% in 15 s (quick).

**Architecture (Rudich et al. Peel-and-Bound / ddo / CODD, adapted to our stage-DP):**
- *Relaxed DD* = the shipped `MostMasteriesCertificate` (upper bound — merge via bucketed keys +
  per-option ceil + saturation).
- *Restricted DD* = the SAME stage machinery with EXACT-PRIMAL semantics + beam width W (drop
  lowest states instead of merging): every surviving final state is ≤ some REAL build's folded
  value, so its max is a true LOWER bound / incumbent. Exactness deltas vs the certificate: raw
  accumulation (no per-option ceil; re-laid key, hp exact 14 bits), netNegatives-style negative
  penalized-mastery pricing (per-item netting UNDER-counts the model's cross-item min(total,0) —
  sound on the primal side, the mirror of why it was unsound as a bound), drop non-HP PERCENT
  skills (reachableMax pricing over-counts), on-path ramps only. Saturation at target stays EXACT
  for the fold (totalActual clamps per-stat at target).
- *Branch* = exact-cutset states (the DP prefix run exactly until width explodes), children
  bounded by the relaxed suffix sweep started from the child state (the "peel" seam:
  `bound(initialStates=…)`), pruned against the restricted incumbent.

**Phases + gates (one seam + one verdict per commit):**
- **P&B-1 — restricted DD (primal).** Test-side prototype forked from the certificate. Gate:
  the SOUNDNESS CANARY is `beamValue ≤ banked optimum` at every width (a violation = an exactness
  bug); SUCCESS = beam hits the S2/S3 optima (67 295 807 882 856 / 10 985) at some W in ≤ ~60 s.
  A near-miss (≥ 99%) still GOes phase 2 (the incumbent only prunes; CP-SAT's own incumbent can
  seed it in production).
- **P&B-0 — exact-prefix width scoping (cheap, piggybacks P&B-1's exact arithmetic).** Layer
  width per stage at W=∞ exact states: where does the width blow past ~10⁷? That layer = the
  exact cutset; its state count = the B&B root fanout.
- **P&B-2 — branching POC.** Peel seam on the relaxed `bound()` (start from an arbitrary states
  map + stage suffix), branch the cutset, prune vs incumbent. Gate: proves the S2 optimum
  (bound == incumbent after search) in ≤ ~5 min single-thread; node-count trajectory reported.
  NO-GO exits: fanout ≥ ~10⁵ with per-node suffix sweeps ≥ ~1 s (wall explodes), or child bounds
  barely drop below the root bound (the looseness lives in the suffix, branching can't reach it).
- **P&B-3 — productionize** (only on P&B-2 GO): peel instead of recompile, incumbent from the
  production search, wiring behind the existing proof pipeline (quick badge → refine → exact).

**Discipline:** the restricted DD is a PRIMAL object — its failure mode is over-counting (a fake
incumbent would wrongly prune real optima in P&B-2), so every relaxation choice must round DOWN;
the canary is the reverse of the certificate's. Same measurement rules as ever (same-JVM det
pairs, no concurrent gradle, XML-captured println).

#### 8.15.1 P&B-1 verdict (2026-07-14): canary GREEN; S3 99.24%; S2 ~72% plateau — the beam is
NOT the S2 primal; phase 2 proceeds with a seeded incumbent (as the gate anticipated)

`MostMasteriesRestrictedDD`(+Test, `WAKFU_MM_PNB1=1`), exact-primal semantics per the campaign
design. **The PRIMAL CANARY held at every width on both shapes** — the exactness rules
(signed raw accumulation with reject-below-0, in-state 10-sub cap, per-item negative netting,
dropped unmodelable credits) are validated machinery for P&B-2 node incumbents.

- **S3: 99.24% of the optimum at W=10k / 5.8 s** — and the SAME 10 902 at W=50k/200k: the
  residual 0.76% is the SEMANTIC ceiling (dropped credit layers), not beam loss.
- **S2: ~72% plateau, rank-sensitive and non-monotone in W.** Retrospective partial-fold rank:
  69.2/72.7/71.6% (W=10k/50k/200k). Optimistic suffix-maxima rank: WORSE on S2 (49/54/68%) while
  much better on S3 — the static suffix over-promises target fill to mastery-rich states that can
  no longer collect it (their cores are high, 8 364 vs 5 871, but they miss cc/ap). Neither pure
  rank works: S2's fold couples target saturation × mastery too tightly for a beam heuristic.
- **Perf lessons banked:** (1) the 1H×off single-stage pair product = 4.2e9 transitions on S2
  (93% of the run) — split via a `twoH` state bit into main-hand/off-hand stages (exact, ×23
  wall); (2) mid-stage compaction is mandatory (exact keys barely collide — |states|×|options|
  materializes whole and OOM-killed the first run); (3) beam trim rank must be computed once per
  entry, never in a sort comparator (the comparator thrash was a 10× wall).

**Verdict: P&B-1 machinery VALIDATED (sound primal, S3 near-miss), S2 incumbent-by-beam
REJECTED.** Per the phase gate, P&B-2 runs oracle-mode: incumbent = the banked S2 optimum
(production would seed it from the CP-SAT search result — always available since the proof, not
the search, is the slow leg). Full restricted sweeps at useful widths cost 25 s (W=10k) to 353 s
(W=200k) on S2 — B&B nodes must sweep SUFFIXES at narrow widths, not the full DD.

#### 8.15.2 P&B-2 verdict (2026-07-14): NO-GO — BOTH §8.15 exits hit; CAMPAIGN CLOSED

Probe: `optionVeto` seam on the certificate (vetoing options only restricts the relaxation —
sound upper bound of the vetoed subspace; harness-only, forces src population) + the fix/exclude
chain on the S2 binding RINGS choice, quick tier, oracle incumbent (`WAKFU_MM_PNB2=1`).

- **FIX-CHILD bound == ROOT bound, bit-for-bit** (74 834 204 489 999, +11.20%): fixing the
  binding rings pair tightens NOTHING — the looseness is entirely SUFFIX-intrinsic. Since the
  B&B proof state is max(children) ≥ the fix child = the root, branching on that choice can
  NEVER lower the proof. The "child bounds barely drop" exit, hit at its theoretical worst.
- **Exclude chain decays glacially**: +11.20 → +8.69% over 6 nodes (~0.4pt/node, ~16 s each),
  a near-equivalent substitute ring binding every time ("X+Souvenir ancestral" for 7 different
  X). Closing ~11 points at that rate ⇒ hundreds of nodes on ONE slot, before multiplying by
  the other 13. The "fanout explodes" exit too (P&B-0 piggyback data: exact-prefix pre-trim
  layer widths already hit 10⁵-2×10⁶ on the early item stages).

**The synthesis (with §8.14.1's grid invariance): the residual is an ENSEMBLE property.** The
+9.87% is not carried by one phantom path but by a large ensemble of near-equivalent relaxed
paths, each slightly phantom for a different reason (a ceil here, an as-if condition there, a
knapsack free-ride elsewhere). Any DD whose merge fuses dims component-wise (the certificate's
bucketed key IS such a merge operator; an exact-arithmetic ddo-style merged DD would fuse the
same way) intrinsically glues one path's best-M onto another's target saturation — and
branching plays whack-a-mole against the ensemble. Duals can't price it (§8.14.1), grids can't
resolve it (§8.14.1), branching can't cut it (this section). **The DD family is bound-limited
at ~+10% on S2-class folded shapes; only the item-space search itself (CP-SAT) closes the last
mile — which is the §8 decomposition verdict re-derived from the dual side.**

**Operating point stands as shipped**: two-tier badge (+11.2% quick / +9.87% full) as the
low-core backup; prod multi-worker CP-SAT proves S2 in 199 s. Campaign artifacts kept:
`MostMasteriesRestrictedDD`(+Test) — a validated sound-primal beam (useful if a node-incumbent
consumer ever appears) — and the certificate's `optionVeto` seam (harness-only). The only
remaining never-proves workload stays S4 (max-damage soft, 4.18× — needs a D·Graw certificate,
a different construct, priced separately).

## 9. CAMPAIGN — the S4 D·Graw certificate (max-damage soft leg; maintainer GO 2026-07-14)

**Context for resumption.** After the review-fix wave (`902fa393..f97cef60`) the MM certificate is
fully sound, S2 +9.87% bit-for-bit, full tier **55 s** (chunked parallel stages + power6 table +
shared proof pool; world split for cap subs, per-state knapsack world B). The ONLY workload that
never proves anywhere is **S4 — the max-damage SOFT leg** (targets-unreachable fallback): its
naive bound sits at 4.18× (§8.1.1), so no badge fires. GPU offload was assessed and deferred
(10-50× plausible on the DP but cross-platform JVM↔GPU stack + a second soundness-critical
implementation; in-JVM parallel worlds measured SLOWER — GC-bound; INTRA-stage chunking was the
win instead).

**The construct.** Clone the MM certificate ARCHITECTURE (stage DP over the dominated pool,
bucketed key, worlds for AT_MOST cap subs, per-state world B, two tiers, shouldContinue) with the
CORE swapped: instead of `M × (100+DI)/100`, mirror the max-damage soft objective's damage proxy
(`maxDamageRawProxy` lineage — Graw from the scenario masteries × (100+DI) × the crit-band
factor from the cc dim), folded by the SAME power6 target arithmetic. Key facts to honor:
- The scenario masteries play the m-role (additive per item, runes on the mastery axis).
- cc feeds BOTH the crit factor in the core AND the CRITICAL_HIT target fold — monotone in the
  dim, so the DP stays sound; mirror the solver's exact crit-band arithmetic (see
  buildMaxDamageObjective / the certifier's per-cell scaling formula — and the banked
  "objective-proxy vs sequencedScore ~0.6% divergence" fact: certify the PROXY, which is the
  solver's own objective, exactly like maxDamageRawProxy does for the hard leg).
- BAIL (no badge) on: multi-element/boss scenarios (per-element enumeration territory), forced
  runes/subs/items, conversions — same conservative gates as both existing certifiers.
- Consumer: `proveMaxDamageQuality` alongside proveMostMasteriesQuality; GUI trigger = max-damage
  search completing non-OPTIMAL through the soft fallback (SolverResult already carries
  maxDamageObjective / maxDamageRawProxy — check which is the comparable objective for the SOFT
  leg and stamp it if missing).

**Phases + gates (one seam + one verdict per commit):**
1. **S4-0 — bank the oracle.** 1-worker seeded CP-SAT on a canonical S4 fixture (the §8.1.1
   protocol: unreachable-targets max-damage shape at 245); bank the soft objective value.
2. **S4-1 — core-mirror prototype** (test-side first, MostMasteriesBoundPrototype pattern):
   items+runes only, no subs/skills; canary `bound ≥ oracle` + tightness read.
3. **S4-2 — full layers** (subs incl. worlds/world B, skills, ramps) + the A1/A2-style
   conditional-sub locks transposed; CI fuzz lock (seeded pools, fresh CP-SAT compare like
   MM_CERT_LOCK).
4. **S4-3 — production wiring**: prove function + GUI badge through the existing ProofState
   pipeline (zero new UI), two tiers, shouldContinue.
   Gates: canary green everywhere; tightness ≤ ~+25% to ship (S4's naive 4.18× means even +50%
   is a user-visible win — the gate is a quality floor, not a kill); wall ≤ MM's (55 s full).
   NO-GO exits: the crit-band coupling breaks per-state monotonicity (would need a world per
   crit band — cost gate ≤ 3× wall), or the soft-leg objective proves non-mirrorable outside
   CP-SAT (then bank why, close).

**Standing discipline:** same-JVM det pairs; no concurrent gradle in timings; `./gradlew --stop`
before env-gated harness runs (the daemon FREEZES its env snapshot — silent skips otherwise);
BDATA_FORCE_WRITE only for intended oracle diffs; soft det ±35% run variance.

### 9.1 S4-0 — oracle banked (MEASURED 2026-07-14, canonical protocol, det 600, lvl 245)

Fresh re-bank on this branch (the condition-timing + sub-identity fixes moved the incumbent):

| id | status | det used | wall | branches | objective (incumbent) | CP-SAT dual bound |
|---|---|---|---|---|---|---|
| S4 mdFrontier soft-direct | FEASIBLE | 600.0 | 22.3 min | 27 718 | **12 326 932 936 900** | 70 516 354 052 645 (**5.72×**) |

Down from the 2026-07-12 incumbent 14 509 755 840 720 — consistent with the model TIGHTENING
(Ravage×Neutralité first-turn exclusion removed a +DI pairing) — while the dual gap widened
(4.18× → 5.72×). Still never proves. ⚠️ The incumbent is NOT a proven optimum: canary
comparisons (`bound ≥ incumbent`) are necessary-only, and a tightness ratio vs it UPPER-bounds
the true ratio. Comparable objective on the soft leg = `SolverResult.maxDamageObjective`
(= `solver.objectiveValue()`, the penalized `damage × power6` — already stamped, nothing to add).

### 9.2 S4-1 — core-mirror prototype verdict (MEASURED 2026-07-14): CANARY GREEN, 3.36×, GO to S4-2

`MaxDamageSoftBoundPrototype` (test-side): the MM certificate's stage DP with the core swapped —
per-state value = the single scalar `W = (400+critCap)·M + 5·critCap·K` (upper-bounds Graw for
every build since `crit ≤ critCap`; no (M, K) Pareto front, no crit-mastery dim needed), AP dim
saturating at MAX_OUT_OF_COMBAT_AP (throughput[AP] grows with AP), fold = the solver's exact
damage chain `throughput[ap]·⌊D·W/PERHIT_DOWNSCALE⌋·resFactor/FINAL_DOWNSCALE × power6(bucket)`
(no overshoot scale). All conditional subs credited AS HELD (pure relaxation), EC's +DI credited
penalty-free, conversions at percent·reachableMax(from).

| read | value |
|---|---|
| soundness lock (3 seeded pools, `bound ≥ maxDamageObjective`, OPTIMAL solves) | **GREEN** (ratios 1.88–2.10 — loose: no CC target ⇒ critCap fold over-credits K ~25×) |
| S4 frontier bound | 41 473 440 000 000 — canary GREEN vs the 12.33T incumbent |
| tightness vs (non-proven) incumbent | **3.36×** — already TIGHTER than CP-SAT's own dual (5.72×) |
| binding state | W=8 412 500, d=98, ap16/mp8/cc100/hp12000 all SATURATED, e=1 r=1 |
| attribution (Δ from 41.47T) | noCondSubs 24.51T (**−41%** — the lever), noSkills 31.33T (−24%), noRunes 32.21T (−22%), noSubs 9.94T |
| wall / states | 570 s, 3.47M states, sequential-only — needs the chunked-parallel port; default test heap OOMs, run with `WAKFU_TEST_MAX_HEAP=8g` |

GO to **S4-2**: transpose the MM exact-condition machinery — assume worlds for the AT_MOST cap
subs, world B (M-cap knapsack, here on the SECONDARY part of M and on K for Secret Critique),
block gating, the ramp bit, mpCapMinus — plus the chunked parallel apply. The seeded-pool ~2×
additionally motivates the anticipated crit-band worlds (weights at the band's hi, collapse
filter `cc_dim ≥ lo` — sound because the dim over-counts) for NO-CC-target shapes; on the S4
shape the binding states saturate cc=critCap so the W fold is already exact there.

### 9.3 S4-2 — exact-condition layers verdict (MEASURED 2026-07-14): 3.26×, condition machinery ≠ the lever

Transposed the full MM machinery (assume worlds for AP/CRIT cap subs with LOW dims, per-state
world B with the exact secondary budget knapsack, block dim, ramp bit, mpCapMinus, chunked
parallel apply). Locks stay GREEN (soundness holds through the world split). Reads:

| read | v1 (credit-as-held) | v2 (exact conditions, quick tier) |
|---|---|---|
| S4 frontier bound | 41.47T (3.36×) | **40.16T (3.26×)** — barely moved |
| binding state | d=98, all targets saturated | worldB fold, W=8.58M vs cap 15.9M (**cap does not bind**), d=40+ramp24, all targets saturated |
| wall / states | 570 s / 3.47M (sequential) | 761 s / 17.3M (parallel; ramp+mpMinus bits ×4 the space) |
| seeded locks | 1.88–2.10× | 1.72–3.77× (assume-world CONSTANTS looser than v1's in-state crediting on small pools — sound, noted) |

Structural findings:
- the worldB W-cap `wM·(100+elemReach+secBudgetCap)` sits ABOVE any coherent state's W (global
  per-slot-max stacks ≈ 2× a real path) ⇒ the Neutralité-family +DI still rides a full-mastery
  state. A real fix = one WORLD per objective-capping sub with secondary-positive parts excluded
  from W (the MM assume-world pattern, not a fold-time cap).
- the SUB LAYER carries 30.2T of the 40.2T bound (noSubs = 9.94T < the 12.33T incumbent) — the
  dominant looseness is the normal-sub knapsack + flagged stages riding above the 10-cap (Poids
  Plume's ramp24 = +14.6% core rides FREE above the 10) — not the conditional machinery.
- the binding state saturates every unreachable target (multiplier = max) — achievement
  over-claim compounds the core looseness.
- BLOCK dim on this shape ×9 states → OOMs 8g and 1h20 wall: S4's full tier is NOT viable with
  the MM grid; quick tier (blockGate=false) is the operating point for now.

NEXT: port the binding-path PROVENANCE (the instrument that cracked MM's +38%→+11.2%) and let
the path drive targeted fixes; candidate seams in looseness order = per-carrier worlds for
objective-capping subs, socket/copy realism in the normal-sub knapsack, ramp inside the 10-cap,
crit-band worlds (no-CC-target shapes).

### 9.4 S4-2b — provenance verdict + conversion net-weight fix (MEASURED 2026-07-15): 3.26× → 2.19×

The binding path (coarse-grid provenance) named the culprit: **Dénouement** (epic CONVERSION sub,
100% critM → elemental, CRIT_AT_LEAST 40) claimed w=3 832 000 — **45% of the whole W** — through
the additive `percent·reachableMax(from)` credit. In W terms the conversion is NEUTRAL at
critCap=100 (w_from = wK = 500 = wM = w_to): the real carrier's Graw does not change. Fix:
conversion credit = `max(0, w_to − w_from) × movedMax` (into-DI/CC conversions stay additive —
they cannot debit W).

| read | pre-fix | post-fix |
|---|---|---|
| S4 frontier bound (quick tier) | 40.16T (3.26×) | **26.95T (2.19×)** |
| binding state | W=8.58M d=40+ramp24 | W=4.75M **d=80**+ramp24 (epic slot → Elemental Concentration +20 DI), worldB Neutralité still riding, all targets saturated |
| attribution | condSubs −41% | condSubs +13.7%, noSkills 19.1T (−29%), noRunes 21.5T (−20%) |

Path facts banked: items/skills stages are EXACT per-slot/per-branch (real lines, Strength
21+40=61 budget checks out); the 9-normal-sub d=40 is plausibly real (cumulable per-element DI
stacks); the remaining suspects = worldB's +DI on full-W states, target saturation (max
multiplier on unreachable targets), rune-axis ceilings.

⚠️ The 2.19× is measured against the NON-PROVEN det-600 incumbent — the true ratio is better by
whatever gap remains above 12.33T. Next: an overnight multi-worker S4 solve to push (or prove)
the reference incumbent, then per-carrier worlds for the objective-capping subs.

### 9.5 S4-0b — incumbent pushed (MEASURED 2026-07-15, 8 workers, det 6000, wall 2h27)

Multi-worker S4 solve (`WAKFU_MM_C2_WORKERS=8`): still FEASIBLE (no proof), but the incumbent
rose 12.33T → **17 702 078 146 500** (+43.6%) and CP-SAT's own bound fell 70.5T → 56.2T. The
**certificate (26.95T quick tier) is now the tightest known upper bound on the S4 optimum** —
better than the solver's own dual. Measured tightness vs the new incumbent: **1.523×** (+52.3%),
an UPPER bound on the true ratio (optimum ∈ [17.70T, 26.95T]). Already in the plan's
"even +50% is a user-visible win" zone. Reference incumbent for the harness:
`WAKFU_S4_ORACLE=17702078146500`.

### 9.6 S4-2c — carrier WORLDS for objective-capping subs: STRUCTURALLY WRONG, reverted (2026-07-15)

Attempted one world per objective-capping sub (weights zeroed on the capped component + exact cap
constant at fold). The soundness lock killed it instantly — and rightly: **Neutralité, Prétention
and Ambition are NORMAL subs** (only Inflexibilité II / Secret critique are EPIC), so several can
coexist in one build and per-sub worlds do NOT partition the build space (this is exactly why the
MM certificate handles its worldB per-state, not per-world). Reverted to the sound §9.4 state
(2.19× vs old incumbent, ≤1.523× vs the pushed 17.70T one).

Banked design for the correct seam (NOT yet implemented — wall-gated): three WEIGHT ARMS per
world instead of per-sub worlds — {plain: no capper staged; secZeroed: secondary weights 0, ALL
sec-cappers staged + Secret critique staged cap-ignored, + wM·secondaryBudgetCap(0) constant;
critZeroed: wK 0, Secret critique staged, sec-cappers excluded, + wK·ownGrants constant}. Every
build is covered by ≥1 arm (SC+sec-capper lands in secZeroed with SC's cap ignored — sound).
Cost: ×3 the DP wall — and the quick tier already runs 761 s vs the ≤55 s ship gate, so the WALL
seams (drop the ramp/mpMinus bits by folding them as constants — the binding state saturates MP
anyway; coarser d step; constant-fold the low-value slots) must land first.

**Interim S4 verdict (checkpoint):** the certificate at §9.4 is the TIGHTEST KNOWN upper bound on
the S4 optimum (26.95T vs CP-SAT's 56.2T at det 6000) — ≤ +52.3% vs the best incumbent — sound
everywhere (locks green), but the production gates are not met: tightness gate needs the 3-arm
seam (+ maybe more), wall gate needs ~15× off 761 s. Both are engineering, not walls — the
campaign continues or pauses on maintainer priority.

### 9.7 S4-2d — weight arms land; the old per-sub worldB fold had a MULTI-CARRIER soundness hole (2026-07-15)

The 3-arm split {plain, secZero, critZero} replaced the worldB per-state fold. First run re-inflated
Dénouement inside critZero (zeroing wK made the conversion "profitable" again — fixed by capping a
conversion's `moved` at the ARM CAP when converting FROM the zeroed component: covered builds hold
it ≤ t + own grants). Verdict (fine grid): bound 33.68T = **1.90×**, binding = secZero with
**d=138** — the arm stages Neutralité+Prétention+Ambition TOGETHER (all NORMAL, +DI each), which a
real sec≤0 build CAN do.

**Consequence — the previous 26.95/27.48T readings were UNSOUND for multi-capper builds**: the
worldB fold considered ONE capper's credits per state, so a build carrying several (their DI
stacks) could exceed the fold. The same pattern exists in the PRODUCTION MM certificate
(`MostMasteriesCertificate.worldBSubs`, folded per sub): a real multi-capper build's
`min(M, caps) × (100 + Σd_i)` can exceed every single-capper fold. On MM's shapes the worldB
candidates are almost certainly dominated (their M-cap ≈ secondaryBudgetCap(0) is tiny vs the
main states' full M), so the shipped badge NUMBERS likely stand — but the soundness ARGUMENT has a
hole that a crafted shape could bind. FIX REQUIRED in MM (stage the cappers arm-style, or fold
capper SUBSETS); S4's arms are the proven pattern.

Grid ledger (3-arm, sound): fine 33.68T/1.90×/34 min; d4/hp1000 35.10T/1.98×/4 min;
d10/hp2000/cc20 36.89T/2.08×/2 min. Locks green (seeds 1.47-3.35).

### 9.8 — MM production fix: world-B SUBSET fold (2026-07-15, found via the S4 transposition)

The S4 arm work (§9.7) exposed a latent soundness hole in the PRODUCTION MM certificate: the
world-B fold considered ONE objective-capping sub per state, but Neutralité/Prétention/Ambition
are NORMAL rarity — a real build can carry several and their DI/CC credits STACK, so a
multi-carrier build could exceed every single-sub fold. Fix: fold every non-empty SUBSET
(≤ 2^6−1; two-EPIC combos impossible and skipped) with the SUM of members' credits and the MIN of
their caps (each condition bounds M independently ⇒ min is sound). Measured: **S2 +9.87%
BIT-FOR-BIT identical** (73 934 594 729 999), S3 +6.77% identical, wall 56.6 s, every lock green —
the hole never bound on real shapes, closed at zero tightness cost.

### 9.9 S4-2e — NET DI per sub (MEASURED 2026-07-15): 1.90× → 1.74×

Static audit of the DI sources found Anatomie (EPIC): +40 back-gated AND −20 unconditional — the
positive-parts-only read credited +40 where the sub's exact whole is +20. Fix: a sub's DI lines
SUM (signed, gate-matched), clamped ≥ 0 per sub. Fine grid **30.85T = 1.743×** (binding d 138→118,
still secZero, all targets saturated); d4/hp1000 1.82×/4 min; d10/hp2000/cc20 1.92×/2 min. Seed
locks stayed sound (two at 1.35-1.40×, one loose at 3.35×) on OPTIMAL small-pool solves.
Trajectory: 3.36 → 2.19 → 1.90 (sound) → 1.74 — vs a still-UNPROVEN 17.70T incumbent.

### 9.10 — CP-SAT isolation: the soft core proves without sublimations (2026-07-15)

The maintainer insisted the "complicated soft-penalty formula proved before." Tested it instead of
arguing. **S4 max-damage soft-direct, 8 workers, NO sublimations → status=OPTIMAL in det 253 / wall
3m31** (rawObjective == bestBound == 5 824 249 128 720). The bilinear `D·Graw × power6(penalty)`
objective is NOT the provability wall — CP-SAT closes it cleanly multi-worker. The wall is the
**sublimation machinery**: with subs, 8 workers × det 6000 (2h27) stays FEASIBLE at 3.17× dual;
without, det 253 proves. Subs are essential to the optimum (obj 5.82T no-subs → ≥17.70T with-subs,
~3×), so they can't be dropped — but the model proves once their encoding is lighter.

**Consequence.** This opened a useful second proof path; it did not invalidate the D·Graw
certificate campaign. A full-model CP-SAT proof would provide the exact oracle and a +0% badge,
while the independent certificate provides an actual sound upper bound even when CP-SAT does not
close. Characterize the sublimation tax in parallel with certificate tightening; do not assume the
full-model incumbent is optimal merely because a restricted model proves the same value.

### 9.11 — real parallelism helps but does not close S4-with-subs; the deterministic runs were misleading (2026-07-15)

CRITICAL measurement artifact found: every prior "multi-worker" S4 run used the DETERMINISTIC
det-budget path (`SolverTuning` non-null → interleaved workers for reproducibility → ~1 physical
core wall even at numSearchWorkers=8). Production uses `tuning == null` → a real wall-clock parallel
portfolio on (cores−1) threads. First REAL-parallel S4-with-subs run (all cores, 15 min wall):
still FEASIBLE (incumbent 17.68T) but dual **37.89T = 2.14×** — vs the deterministic det-6000
(2h27) 56.2T/3.17×. Real parallelism closes the dual far faster (56→38 in 15 min vs 2h27) — the
right lever — but 15 min doesn't finish the proof. The certificate (30.85T) still beats CP-SAT's
real-parallel dual (37.89T). No-subs proves (§9.10); with-subs is the wall. Next: real-parallel +
condition-stripped to test whether the 16 reified conditions are the specific tax.

### 9.12 — isolation result: modeled conditional subs dominate the CP-SAT proof wall (2026-07-15)

S4 with subs but WITHOUT the 16 condition-bearing catalog entries (15 of them are actually
solver-modeled; `Force Herculéenne`/`AP_ODD` is unsupported and filtered), real parallel →
**OPTIMAL in 49 s**, objective == bound == **17 702 078 146 500**, equal to the incumbent the
2h27 full-subs run banked. Two findings:
1. The reified CONDITIONS (disjunctive if-then structure; the 07-14 condition-timing fix hardened
   the Neutralité-family ones) are the dominant isolated CP-SAT provability wall — remove them and
   this restricted model proves in under a minute. The bilinear objective, stacking and carrier
   matching are tractable on that restricted shape.
2. The equality is strong evidence that the full-model incumbent may already be optimal, but it is
   **not a proof**: the no-condition feasible set is a subset of the full feasible set. A conditional
   sub could still produce a better, undiscovered full-model build.

**This adds a CP-SAT target to the S4 campaign.** A lighter exact condition encoding or a complete
search-side world split could yield a +0% proof. Until one actually proves every full-model world,
the D·Graw certificate remains the primary source of an independent upper bound.

⚠️ REGRESSION CONFIRMED for the maintainer's report: the condition-timing fix (39532d15) added
firstTurnStat reified vars that made the soft max-damage leg harder for CP-SAT to prove. Not a
correctness regression (the fix is right), but a PROVABILITY one — the badge that fired before on
sub-heavy soft requests may now be withheld. A complete, sound world-split search could restore it.

### 9.13 — 2 h real-parallel run: incumbent stable at 17.70T, still unproven (2026-07-15)

Full-subs S4, production wall-clock parallel portfolio (all cores), 2 h wall: FEASIBLE, incumbent
**17 702 078 146 500** (reached at ~14 min, never beaten in the remaining 1h46), dual 35.55T =
**2.01×** (37.9T at 15 min → 35.55T at 2 h — the dual has effectively stalled). This confirms the
incumbent's empirical stability and the severe proof wall, **not** global optimality: FEASIBLE plus
"never beaten" remains a lower bound only. A provable encoding or the independent certificate must
close the interval. Data folded into `docs/S4_CONDITION_ENCODING_PROBLEM.md`.

### 9.14 — certificate resumed; legal negative-secondary budget cuts the sound bound to 1.630× (2026-07-15)

The restricted-model result in §9.12 does not prove the full model, so work resumed on the S4
certificate while testing one easy exact CP-SAT encoding projection.

#### CP-SAT condition projection — exact, measured, REJECTED

For a solver-chosen conditional sub, the existing encoding creates `b ⇔ condition` and posts
`subVar ≤ b`. Because `b` has no other consumer on this path, its projection onto the real model
variables is exactly the one-sided constraint `subVar ⇒ condition`. Forced subs still need the full
truth-value gate because they may be equipped while inert. The projected implementation passed the
sublimation semantic/differential locks, but proof performance regressed:

| protocol | full reification | projected implication | verdict |
|---|---:|---:|---|
| real parallel, 180 s | incumbent 17.351T, dual 35.921T | incumbent 15.243T, dual 39.431T | worse primal and dual in this noisy wall sample |
| deterministic, fixed seed, `interleaveSearch=true`, 1 worker, det 60 | incumbent 6.286T, dual 71.380T, 144.6 s | incumbent 10.833T, dual 77.210T, 184.7 s | better early primal, but dual **+8.17%** and wall **+28%** at equal deterministic work |

The proof objective is the dual, so this arm is **parked and reverted**. Do not retry the direct
implication alone without a new propagation idea; exactness and fewer booleans did not translate to
a stronger CP-SAT proof.

#### Identical-condition memoization — exact, measured, REJECTED

Four choosable subs (`Neutralité III`, `Prétention III`, `Ambition III`, `Inflexibilité II`) share
the exact normalized predicate `SECONDARY_MASTERIES_AT_MOST(0), firstTurn=true`. A cache keyed by
`SubConditionSpec` made them reuse one sum and one reified boolean, reducing the 15 modeled catalog
conditions to 12 unique reifications without changing the feasible set.

Same-JVM deterministic A/B, fixed seed, one worker, `interleaveSearch=true`, det 60, with the
max-damage production cut configuration explicitly held equal:

| arm | incumbent | dual bound | wall |
|---|---:|---:|---:|
| four full reifications | 7.526083577250T | **46.789725248960T** | 156.182 s |
| one shared predicate | 4.217834479125T | 50.920109934095T | **130.419 s** |

The smaller model is 16.5% faster in wall but its dual is **8.83% worse**, and its incumbent is
worse too. The duplicate reifications evidently provide useful propagation/search redundancy.
Rejected and reverted; do not retry predicate memoization alone.

#### Direct light-weapon conflicts — exact, measured, REJECTED

The last local projection isolated the one purely structural predicate
`NO_OFFHAND_OR_TWO_HANDED` (`Expert des armes légères III`). For a solver-chosen sub, its full
truth-value reification plus `subVar ≤ condition` was replaced by the exact binary conflicts
`subVar + equipVar ≤ 1` for every off-hand/2H choice. Forced-sub semantics were deliberately left
fully reified.

Same-JVM deterministic A/B under the identical protocol/configuration:

| arm | incumbent | dual bound | wall |
|---|---:|---:|---:|
| full reification | 7.526083577250T | **46.789725248960T** | 156.062 s |
| direct binary conflicts | **7.773156462500T** | 50.920109920720T | **131.982 s** |

The direct conflicts improve the early incumbent and wall, but worsen the proof dual by
**8.83%**—almost exactly the same bad dual reached by identical-condition memoization. Rejected and
reverted. Together with the generic implication experiment, this exhausts the credible local
“remove/project a reification” family: CP-SAT benefits from the redundant truth-value structure on
this nonlinear objective even when the projected feasible set is identical.

#### Sound certificate tightening — legal negative-secondary layout

Coarse provenance showed the binding `secZero` arm carrying `armSecCap=3323`. That number was the
sum of independent per-type negative-secondary maxima, including impossible simultaneous
`2H + 1H + off-hand` weapons and multiple EPIC/RELIC layouts. For a Neutralité-family condition,
write the signed all-secondary sum as:

```text
P_all - N <= t  =>  P_scenario <= P_all <= t + N
```

The new independent bound maximizes `N` over legal distinct-ring and weapon layouts with the real
one-EPIC/one-RELIC budgets. Base, runes, skills and sublimations are all credited independently on
their negative side, deliberately relaxing their budgets. Forced-passive shapes now bail rather
than omitting their positive lines. Therefore the result remains an upper bound; taking the minimum
with the previous signed-budget knapsack is sound. On S4 the cap is `3323 → 2587` (−22.15%), and
`armConstW` falls `1,661,500 → 1,293,500`.

Results:

| read | before | after |
|---|---:|---:|
| coarse provenance bound | 34.05573T | **31.85706T** (−6.46%) |
| fine-grid sound bound | 30.85T (1.743×) | **28.85412T (1.6300×)** |
| fine-grid work | — | 46,857,707 states / 3,031,851 ms (50m31.9s) |
| binding state | `secZero`, all targets saturated | `Wbase=4,022,500`, `armConstW=1,293,500`, `armSecCap=2587`, `d=118`, AP16/MP8/CC100/HP12000 saturated |

The certified interval is now **[17.7020781465T, 28.85412T]**. Three seeded exact-CP-SAT
soundness locks remain green (`bound ≥ optimum`; ratios 3.3514, 1.4014, 1.3504). This is a
test-side prototype change, not the production certifier, so `CERTIFIER_VERSION` is unchanged.

#### Independent exact target envelopes — SOUND but MEASURED-NO, reverted

The path still saturates every nominally unreachable target, and `Geometry.applyOne` rounds HP/CC
upward at every stage. A constant-state-size experiment packed independent maxima
`(max W, max exact HP, max exact CC)` into the existing map value for each abstract key. The maxima
may come from different builds, so their Cartesian combination is a sound relaxation; collapse
takes the minimum of the old bucket upper and the exact-value upper.

It did not move S4: the same coarse binding key has `hpUpper=12000` and `ccUpper=100` through other
relaxed paths, so the bound remains **31.85706T**. Seeded soundness stayed green, but that campaign
slowed from ~5m25 to ~10m in this sample. Reverted. This also disproves the simple “per-stage
rounding is the next lever” hypothesis: an axis bound independent of objective value loses the same
tradeoff as the original DP.

#### Coupled `W + λ·CC` support function — SOUND, coarse win

The next experiment preserves one tradeoff without widening the DP map. For each existing abstract
key, retain

```text
Hλ = max(W + λ·CCpositive)
```

where `CCpositive` is the sum of non-negative CC contributions. Since the build's signed critical
hit value `c` is always `≤ CCpositive`, a build whose `c` lies in an integer band `[lo, hi]` obeys
`W ≤ Hλ − λ·lo`; evaluating its target penalty at `hi` also only over-counts. The maximum of those
rectangles over the bands is therefore a sound upper bound. Negative `c` is covered by the `lo=0`
rectangle, and values above the target by the final target-capped rectangle. `λ=0` is exactly the
previous prototype. This is different from §8.14's inert multiplier on the global 10-sub count: the
support is maximized **inside every merged DP key**, so it directly prevents taking `W` from a
low-CC path and CC target credit from another path.

Coarse grid (`DI=10`, `CC=20`, `HP=1000`, CC bands of 5):

| λ | sound bound | vs λ=0 |
|---:|---:|---:|
| 0 | 31.85706T | — |
| 5,000 | **30.87351T** | **−3.09%** |
| 7,000 | 30.95817T | −2.82% |
| 10,000 | 31.08267T | −2.43% |

The winner remains the `secZero` arm and the `CC=100` band. The state count is unchanged; only the
scalar retained at each key changes. `λ≈5000` is the measured coarse minimum (the winning support
line switches to a ~107-positive-CC path immediately above it).

The manual soundness lock was strengthened to include an unreachable `CC=100` target, so it really
exercises the banded collapse. Three fixed-seed, one-worker, `interleaveSearch=true` CP-SAT oracles
all proved optimal and stayed below the certificate:

| seed | exact CP-SAT optimum | support bound | ratio |
|---:|---:|---:|---:|
| 1 | 0.568852079565T | 2.114631300285T | 3.7174× |
| 2 | 2.276045688840T | 3.889129242460T | 1.7087× |
| 3 | 2.113135111800T | 3.602508108220T | 1.7048× |

Status: retained in the test-side prototype; production certifier and `CERTIFIER_VERSION` remain
unchanged.

The canonical fine grid (`DI=1`, `CC=10`, `HP=500`) confirms that the coupling survives refinement:

| read | λ=0 | λ=5,000 support |
|---|---:|---:|
| fine-grid sound bound | 28.85412T (1.6300×) | **28.04238T (1.5841×)** |
| improvement | — | **−2.81%** |
| states | 46,857,707 | 46,857,707 (unchanged) |
| measured wall | 3,031,851 ms | **1,979,857 ms (32m59.9s)** |
| binding | `secZero`, d=118, targets saturated | `secZero`, d=118, CC band 100..100, `Wupper=5,166,500`, targets saturated |

The certified S4 interval is now **[17.7020781465T, 28.04238T]**. The wall comparison is not a
controlled speed claim (different run/session), but importantly the support adds no states and did
not make this sample slower. This is a certificate-tightening milestone, not yet a production port:
the existing production certifier bounds raw damage and cannot represent S4's unmet-target soft
penalty, so this prototype remains a separate proof path until its interval is useful enough.

#### Couple Neutralité's negative budget to the chosen item layout — SOUND, large coarse win

The `secZero` arm previously maximized two incompatible layouts independently: the DP chose the
largest non-secondary `W0`, while the fold added `wMastery·2587` from a separate legal layout with
the most negative secondary mastery. For every build satisfying `P_all − N ≤ t`, however,
`P_scenario ≤ t + N`. Therefore the same scalar DP can price each item as
`W0(item) + wMastery·N(item)` and leave only non-item negative sources in the constant. This
preserves the exact ring/weapon/EPIC/RELIC coupling without a new dimension or state.

Conversions from the capped component are valued relative to the already-reserved `wMastery` per
unit: `max(0, w_destination − wMastery)·moved`. Thus Dénouement's critM→elemental conversion is
correctly neutral at the S4 crit cap (500−500=0), rather than double-credited or omitted. A separate
loose raw ceiling remains only for future conversions into DI/CC.

Combined with the winning CC support (`λ=5000`, band 5), the coarse bound falls
**30.87351T → 24.13557T (−21.83%)**, or **1.3634×** the incumbent. The winner remains `secZero`, but
`armConstW`/`armSecCap` fall to zero and the actual item layout carries the negative-budget credit
inside `support`. All three strengthened deterministic exact-pool locks remain green.

The canonical fine grid confirms essentially the same gain:

| read | CC support only | + coupled negative item layout |
|---|---:|---:|
| fine sound bound | 28.04238T (1.5841×) | **21.92445T (1.2385×)** |
| improvement | — | **−21.81%** |
| states | 46,857,707 | 46,857,707 |
| DP wall | 1,979,857 ms | **1,708,112 ms (28m28.1s)** |
| binding | `secZero`, d=118 | `secZero`, d=118, `Wupper=4,039,000`, all targets saturated |

The certified S4 interval is now **[17.7020781465T, 21.92445T]**. Next: replay only this winning
world/arm with item-level `secN`/`secOther` provenance, then decide whether the exact net budget
`N − positiveNonScenarioSecondary` closes more of the remaining 23.85% gap.

#### Net secondary item budget + exact normal-sub packing — SOUND, coarse wins

The coupled `secZero` provenance exposed another impossible Cartesian product. Its chosen item
layout supplied only `Nitem=430` negative secondary mastery but also `Pother=1439` positive
non-scenario secondary mastery. The condition actually implies

```text
Pscenario + Pother - N <= t  =>  Pscenario <= t + N - Pother
```

so the item credit is now `500·(Nitem-Pother)`, not `500·Nitem`; non-item sources remain
independently over-credited. The coarse bound falls **24.13557T → 23.58279T (−2.29%)** and the
winning arm switches from `secZero` to `plain`, eliminating Neutralité-family cap looseness from
the maximum. The three exact seeded locks stay green with the prior values (ratios 3.7174,
1.7087, 1.7048).

The new `plain` provenance then showed a local aggregation loss in the normal-sub knapsack:
`Ravage secondaire II` contributes +3 CC per copy but every copy was rounded to a whole CC bucket,
and `Vélocité II`'s useful +1 MP had its −10 DI rider clamped away before packing. The retained
test seam (`WAKFU_S4_EXACT_NORMAL_SUBS=1`) sums CC exactly and DI signed across the shared ten-sub
knapsack, then rounds/clamps once at its output. This is sound: positive CC only saturates at the
target, while signed DI is never saturated before every negative rider has been accumulated.

| read | net item budget | + exact normal-sub pack |
|---|---:|---:|
| coarse bound | 23.58279T | **21.12267T (−10.43%)** |
| coarse wall | 205 s without retained path | **156 s** |
| binding | `plain`, d=110, targets saturated | `plain`, d=100, CC band 100, targets saturated |
| ratio vs incumbent | 1.3322× | **1.1932×** |

The strengthened exact locks also improve and remain green: seed 1 `1.967223541710T ≥
0.568852079565T` (3.4582×), seed 2 `3.649609241740T ≥ 2.276045688840T` (1.6035×), seed 3
`3.365390133840T ≥ 2.113135111800T` (1.5926×).

The next path selects `Expert des armes légères III` together with an off-hand (`Dagues Tylo`),
violating `NO_OFFHAND_OR_TWO_HANDED`. An exact one-bit item/sub coupling lowers the coarse bound only
**21.12267T → 20.94090T (−0.86%)** while slowing the no-provenance run **156 s → 344 s**.
Moving the weapon stage next to the sub knapsack made the late transition still slower. Both forms
were rejected and removed; do not retry this bit without a value-side/two-channel implementation
that avoids doubling states.

The canonical fine grid confirms the exact normal-sub gain, though at a material wall cost:

| read | coupled item layout | + net item budget + exact normal subs |
|---|---:|---:|
| fine sound bound | 21.92445T | **20.48772T (−6.55%)** |
| ratio / proven gap vs incumbent | 1.2385× / 23.85% | **1.1574× / 15.74%** |
| states | 46,857,707 | **53,452,911 (+14.1%)** |
| DP wall | 1,708,112 ms (28m28.1s) | **2,856,407 ms (47m36.4s, +67.2%)** |
| binding | `secZero`, d=118, Wupper=4,039,000 | `plain`, d=94, Wupper=4,241,500, CC band 100 |

The canary is green (`20.48772T >= 17.7020781465T`). A post-tightening slope sweep shows that the
old λ=5000 optimum no longer applies after `secZero` disappears: coarse λ=7000 and λ=10000 both
give **21.03801T** versus 21.12267T at λ=5000. The support changes to a 100-positive-CC path at
λ=7000, so higher slopes stay on the same `Wupper=4,224,500` plateau. The canonical fine rerun at
λ=7000 confirms **20.40555T = 1.1527×** (a further −0.40%), with the same 53,452,911 states and
2,821,827 ms (47m01.8s). Binding remains `plain`, d=94, CC band 100.

The certified interval is now **[17.7020781465T, 20.40555T]**, a proven maximum gap of **15.27%**.
The remaining visible relaxation is the same merged key claiming
the CC=100 target band: its support-max provenance carries only 88 positive CC, while another
lower-W path makes the bucket's independent CC upper reach 100. A useful next experiment therefore
needs a second support slope / per-key convex envelope; another independent CC maximum was already
measured inert, and exact CC as a full state dimension would multiply the 53M-state wall.

#### Iteration funnel — replace the 47-minute promotion loop

The fine run is no longer the development loop. `scripts/s4-certificate-screen.sh` exposes targeted
profiles and reuses compiled classes via `cleanTest` instead of rebuilding every task:

```sh
./scripts/s4-certificate-screen.sh coarse plain  # base/plain only: diagnostic, UNSOUND
./scripts/s4-certificate-screen.sh di plain      # DI=1; CC/HP coarse
./scripts/s4-certificate-screen.sh cc plain      # CC=10; DI/HP coarse
./scripts/s4-certificate-screen.sh hp plain      # HP=500; DI/CC coarse
./scripts/s4-certificate-screen.sh coarse cert   # all worlds/arms: sound screen
./scripts/s4-certificate-screen.sh adaptive cert # coarse worlds + DI-refine only contenders
./scripts/s4-certificate-screen.sh fine cert     # promotion only (~47 min)
```

Every diagnostic line is stamped `diagnostic=BASE_PLAIN_UNSOUND`; it never checks or updates the
certified interval. `WAKFU_S4_TIMINGS=1` prints wall time per stage and per world. Promotion policy:
rank ideas on `coarse plain`, select the affected-axis profile, then run `coarse cert` + the seeded
exact locks; pay `fine cert` only for a material winner (normally ≥1–2% coarse, or a wall reduction).

Measured funnel on the current S4 shape (warm compiled classes; Gradle wall includes JVM startup):

| command | sound? | bound | states | DP / Gradle wall |
|---|---|---:|---:|---:|
| `coarse plain` | no, diagnostic | 21.03801T | 108,816 | 6.3 s / **9 s** |
| `cc plain` | no, diagnostic | 21.03801T | 218,455 | 9.5 s / **12 s** |
| `hp plain` | no, diagnostic | 21.03801T | 226,413 | 9.5 s / **12 s** |
| `di plain` | no, diagnostic | **20.40555T** | 553,424 | 18.2 s / **21 s** |
| `coarse cert` | yes | 21.03801T | 4,461,075 | 145.2 s / **2m28s** |
| `di cert` | yes | **20.40555T** | 22,387,811 | 794.4 s / **13m18s** |
| `adaptive cert` | **yes** | **20.40555T** | 5,014,499 | 182.8 s / **3m06s** |
| `fine cert` | yes | 20.40555T | 53,452,911 | 2,821.8 s / **47m02s** |

The adaptive certificate first computes every coarse world upper bound, then DI-refines contenders
in descending coarse-bound order until the best refined bound covers every remaining coarse bound.
This is sound because unrefined worlds keep their independently sound coarse upper. On S4 it refines
only base/plain (`21.03801T → 20.40555T`); the next unrefined upper is base/critZero at 20.06691T.
Therefore the mixed-grid maximum is exactly the canonical fine bound while being **15.2× faster**.
The 47-minute grid is now only an occasional calibration/nightly lock, not the promotion loop.

### 9.15 — signed AP/MP folds + value-side weapon split close S4 to 0.343% (2026-07-16)

The post-funnel provenance exposed four independent relaxations. All changes below remain in the
**test-side S4 prototype only**; the production CP-SAT encoding and `CERTIFIER_VERSION` are
unchanged.

1. Item `MAX_ACTION_POINT < 0` is folded into the existing signed AP coordinate (outside the
   optimistic AP-assumption worlds). Negative-capable slots run before positive-only slots and the
   prefix headroom guard prevents saturation before a later debit. Direct DI base/plain fell
   **20.40555T → 18.75468T**.
2. The first `MAX_MOVEMENT_POINT < 0` prototype used an extra `mpCapMinus` bit. It reached
   17.929834329T diagnostically but made the adaptive certificate take **13m31s**. It was replaced
   by signed MP in the existing coordinate: item `MP + min(MAX_MP, 0)`, and exact signed NORMAL-sub
   packing for Armure lourde. The coordinate reserves only
   `target MP + maximum remaining negative riders`; excess positive MP can be saturated soundly.
   Coarse worlds deliberately keep the old looser read and only contender refinements pay for the
   signed axis. This hybrid took **8m31s** at that stage, with the same bound. The original bit is
   dormant and must not be retried as the default representation.
3. `secZero` provenance found Coiffeuse Mortelle's scenario-relevant `-430 MASTERY_BACK` being used
   as condition budget while its negative objective contribution was ignored. The correct bound is
   `Sscenario <= t + Noutside - Poutside`; scenario-negative mastery never creates free budget.
   Direct secZero DI fell **18.064318932T → 17.0947283265T**, removing that arm from contention.
4. `Expert des armes légères` was still combined with an off-hand. Instead of the rejected state
   bit (§9.14, +120% wall), a value-side union prices two independent sound arms:
   `noExpert` excludes the carrier, while `expertEligible` excludes off-hand and two-handed weapon
   layouts. The max of both arms is the union upper bound. Direct plain DI fell
   **18.75468T → 17.8148716845T** without doubling every DP state.

The earlier proposed per-key multi-slope envelope was implemented and measured before these
structural changes. Slopes `{0,7000}` and `{7000,10000,20000,50000}` produced **no bound gain** and
slowed the direct DI screen from about **18 s to 74 s**; it was fully reverted. After the structural
changes, a new single-slope sweep found the discrete support knee:

| λ | direct DI base/plain bound |
|---:|---:|
| 0 | 18.346302777T |
| 4000 | 17.8322245365T |
| 5000 | 17.795349726T |
| 5200 | 17.7888424065T |
| 5400 | 17.7801659805T |
| 5600 | 17.773658661T |
| 5800 | 17.7671513415T |
| **6000** | **17.7628131285T** |
| 6200 | 17.7671513415T |

The sound adaptive all-world run at λ=6000 refined only base/plain and base/critZero:

| result | value |
|---|---:|
| final upper bound | **17.7628131285T** |
| proven no-condition incumbent/lower bound | **17.7020781465T** |
| remaining absolute gap | **0.0607349820T** |
| remaining relative gap | **0.343095%** |
| states | 9,949,088 |
| DP wall | 411,315 ms (**6m54s**) |
| refined plain | 17.7628131285T (2,069,882 states, 91.75 s) |
| refined critZero | 16.9211998065T (2,069,882 states, 81.48 s) |
| largest remaining coarse world | secZero 17.6934017205T |

The incumbent itself was re-solved with the production no-condition S4 model and proven
`OPTIMAL`, raw = bound = **17.7020781465T**, in **58.4 s**. An opt-in
`WAKFU_MM_DUMP_BUILD=1` dump now records its complete items, runes, sublimations and skills for
provenance comparisons. Its resolved frontier is **AP15 / MP8 / CC100 / HP12466 / DI84**, with
elemental 2983, distance 1221, back 2158 and critical mastery 2409; raw proxy = 19,382,375. The
certificate's binding arm is also on AP15 / DI84, so the residual 0.343% is now localized to the
mastery (`Graw`) relaxation rather than AP/MP/DI target folding.

The strengthened deterministic seeded-pool oracle lock (`1 worker`, `randomSeed=1`,
`interleaveSearch=true`) is green with every new flag enabled and λ=6000:

| pool | exact CP-SAT objective | prototype upper | ratio |
|---|---:|---:|---:|
| seed 1 | 0.568852079565T | 1.885243906000T | 3.3141× |
| seed 2 | 2.276045688840T | 3.493916752480T | 1.5351× |
| seed 3 | 2.113135111800T | 3.243714903000T | 1.5350× |

The full lock wall was **18m25s**; this is an exhaustive development guard, not the normal S4
certificate wall. The current certified interval is therefore
**[17.7020781465T, 17.7628131285T]**. Remaining work should explain the last 60.735G through the
binding `plain/noExpert` provenance (ring/rune/sub/skill coupling); do not retry the multi-slope
envelope, the `mpCapMinus` bit, or the old global Expert state bit without a new argument.

Two post-lock axis probes localize that residue further. DI-exact/CC=5 (four times finer than the
adaptive refinement) stayed **bit-identical at 17.7628131285T**, with 4,345,706 states and 4m55s.
DI-exact/HP=250 likewise stayed identical, with 4,505,304 states and 3m41s. The binding read is
`Wupper=4,451,000`, i.e. `Graw=8,902`, while the exact incumbent resolves to `Graw=8,871`: only
**31 mastery points** remain relaxed. Do not spend another iteration on CC=5 or HP=250; the next
useful split is identity/value-side separation of the unconditional EPIC arm from each remaining
conditional EPIC carrier, reusing the exact 58-second no-condition optimum for the former.

### 9.16 — forensic provenance: CP-SAT did not miss a better build; the certificate envelope is loose (2026-07-16)

The previous conclusion that only “31 mastery points” separated the abstract binding read from the
incumbent needed a constructive check. Provenance was extended with exact equipment id/level/rarity
and the per-item rune-axis composition, then rerun on the binding `plain/noExpert` arm at DI=1.
Its support argmax is:

- AP15 / MP8 / **97 actual CC** / HP12430 after 11 intelligence points / DI84;
- `Anneau creux de Wakfu + Anneau Chuchotis ancestral`, all four sockets on the W axis;
- Ravage III, Carnage III ×2, Vivacité II, Destruction III ×2, Poids Plume III,
  Influence vitale III ×2, Brûlure III, then Anatomie;
- Strength elemental 21 + distance 40; Luck crit 20 + back 41; the same four majors.

The reconstructed `BuildCombination` is legal, but the exact scalar re-score is only
**22,251.2901** (AP15 / MP8 / CC97 / HP12430 / DI84), versus **111,332.4534** for the banked
incumbent. It is therefore not a hidden better build. The certificate combines this path's maximum
support `Hλ` with the CC=100 rectangle. That remains **sound**: for every real CC=100 path,
`W + λ·100 <= Hλ`, even if the path attaining `Hλ` itself has CC97. It is simply a convex-envelope
relaxation, so provenance is diagnostic rather than a constructive witness.

Three independent CP checks confirm the verdict:

| check | result |
|---|---:|
| binding arm, final soft objective `>= incumbent + 1`, 8 workers / 300 s | `UNKNOWN`, no feasible solution found, dual 29.817890904430T |
| exact no-condition cell AP=15, MP>=8, CC>=100, HP>=12000, plain raw objective | **OPTIMAL 19,382,375 in 14.76 s** |
| same cell with the remaining conditional EPIC subs, 180 s | FEASIBLE at the same 19,382,375; dual 30,024,750 |

The exact no-condition cell equals the incumbent's raw proxy, proving CP-SAT did not miss anything
in the space used by the provenance. Removing every conditional sub from the certificate is also
bit-identical (`17.7628131285T`, 49 s), so conditional EPIC identities are not the current lever.

Forcing the DP's CC coordinate to exact units (`ccStep=1`, support bands of 1) separates the 97 and
100 keys and gives **17.760644022T**, i.e. a sound targeted binding-arm gap of **0.330842%**. The
improvement is only 2.1691065G while the naive cost jumps to **21,127,639 states / 36m57s**; this is
a calibration, not a production plan. HP=100 with conditional subs removed is also inert at
17.7628131285T and costs 11,250,726 states / 15m40s. Do not retry naive full CC=1 or HP=100.

**Verdict:** the certificate is the loose side; CP-SAT did not overlook the provenance build. The
next useful implementation should preserve a cheap coarse CC upper for penalty folding while adding
a targeted value-side lower/frontier for the CC=100 contender, rather than multiplying the whole DP
by 20. Provenance must always be exact-rescored before being treated as a candidate incumbent.

### 9.17 — semantic condition partition closes S4 at 0.0000% (2026-07-16)

The proposed second CC support was retried only after §9.16 supplied a new, exact reason: the
λ=6000 support argmax has 97 CC while the binding rectangle prices CC100. A second independently
maximized support `Hμ` was retained on the same keys and both W ceilings were intersected at
collapse. The discrete knee is μ=6100: the support changes from a 97-positive-CC path to a
111-positive-CC path. At the knee, the CC100 W ceiling improves only **4,451,000 → 4,450,700**.
That 300-unit reduction disappears in the integer damage/downscale chain, leaving the final bound
**bit-identical at 17.7628131285T**. The base/plain DI screen slowed from about 92 s to **169 s**.
The experiment was reverted: μ=6100 is not retained anywhere.

This also clarifies the role of λ. Every λ≥0 is independently sound because
`W <= Hλ - λ·lo` on a CC band whose lower endpoint is `lo`; λ can only affect tightness and runtime,
never correctness. The measured λ=6000 is therefore an S4 tuning, not a universal game constant.
Unsupported target shapes still bail. In particular this prototype supports only required
AP/MP/CC/HP targets; a request for an unreachable resistance target (for example 10,000 resistance)
must return “certificate unavailable”, not reuse the S4 tuning as though it generalized.

The useful split is instead semantic and exhaustive:

```text
all feasible builds
  = builds selecting no condition-bearing sub
  ∪ builds selecting at least one condition-bearing sub
```

The first partition already has an independent CP-SAT proof: **OPTIMAL
17.7020781465T**. For the second partition, the prototype adds one dormant key marker at bit 49.
It stays zero through every equipment stage and starts splitting only when sub options are packed;
normal-sub aggregate options preserve it exactly, and assumed cap worlds / secZero / critZero arms
are marked as condition-bearing by construction. Collapse discards marker=0. This is not the old
global Expert bit: the expensive equipment prefix is unchanged, and the marker represents the
complete semantic partition rather than one structural predicate.

A naive all-world condition-only run was stopped after it exceeded a reasonable iteration budget.
The sound adaptive union is cheaper:

1. price every world with the normal coarse full-space certificate;
2. keep every coarse world already ≤ the no-condition optimum;
3. only for contenders, DI-refine the `condition-used=1` partition;
4. cap that world's complementary no-condition partition by the independently proven CP optimum.

Measured result:

| component | sound upper |
|---|---:|
| exact no-condition CP-SAT partition | **17.7020781465T** |
| conditional base/plain DI refinement | 17.0253169185T |
| conditional base/critZero DI refinement | 16.9211998065T |
| largest unrefined coarse world | 17.6934017205T |
| final union upper | **17.7020781465T** |

The adaptive run refined two worlds, visited 12,725,057 aggregate states and took **648,776 ms
(10m48.8s)**. Therefore the S4 interval is now
**[17.7020781465T, 17.7020781465T]**: the banked incumbent is globally optimal for this supported
fixture, via a hybrid proof (exact CP-SAT on the no-condition partition + independent sound DP on
the condition-used partition).

The new partition lock adds `Σ conditionalSubVar ≥ 1` to the deterministic CP-SAT test oracle.
Seed 1 is green: exact conditional optimum **0.551021875745T** ≤ certificate
**1.885243906000T** (3.4214×), status `OPTIMAL`, wall 6m23 for the one-seed manual campaign.
Before production integration, run all three partition seeds, remove the manual oracle injection in
favor of a typed proof result/cache entry, and optimize the 10m49 wall. Production certifier code and
`CERTIFIER_VERSION` remain untouched in this prototype campaign.

### 9.17 — takeover: partitioned locks ×3 green, typed oracle, grid cascade — pipeline 10m49 → ~5m52 (2026-07-16)

Continuation of the §9.14-9.16 campaign (remaining-work items 1-3):
1. **Partitioned lock, all 3 seeds GREEN** (`conditionalOnly=true`): each seed's conditional-only
   exact CP-SAT optimum proves OPTIMAL and stays under the conditional-only DP bound
   (ratios 3.4214 / 1.6586 / 1.6754).
2. **Typed no-condition oracle**: the harness now SOLVES the no-condition model (production
   real-parallel portfolio) and REQUIRES `OPTIMAL`, memoized per (data version, request, pool) —
   measured 49.4 s, objective 17 702 078 146 500 exactly. `WAKFU_S4_ORACLE` remains as a trusted
   override for controlled A/Bs only; the screen script no longer banks a default.
3. **Adaptive grid cascade + coarse HP=2000**: the top world refines straight at DI=1; later worlds
   try DI=4 and escalate only while still above the running best (every grid independently sound).
   Bound **bit-identical 17 762 813 128 500** (ratio 1.0034), DP wall **490.7 s → 302.7 s (−38%)**;
   end-to-end oracle+certificate ≈ **5m52**. critZero now stops at DI=4 (50 s vs 109 s).

Remaining: item 4 — the production port (prove entry + GUI badge through ProofState, CERTIFIER_VERSION
bump) with the review guardrails: data-version-fingerprinted oracle cache, AP-headroom `require` →
BAIL, and a 2-3-shape generality screen before enabling the badge broadly.

### 9.18 — EXACT closure reproduced + pipeline 10m49 → 6m38; CP-SAT cross-check inconclusive (2026-07-16)

The full hybrid union (typed solved oracle + conditional-only adaptive DP with the grid cascade)
reproduces the second agent's EXACT S4 closure:

| read | value |
|---|---:|
| oracle (no-condition CP-SAT, PROVEN OPTIMAL) | 17 702 078 146 500 (42.4 s) |
| conditional-only plain (DI1) | 17 025 316 918 500 — under the oracle |
| conditional-only critZero (**stopped at DI4** by the cascade) | 17 289 947 911 500 — under the oracle |
| largest unrefined coarse world | 17 693 401 720 500 — under the oracle |
| **final bound / gap** | **17 702 078 146 500 / 0.0000%** |
| end-to-end wall | **6m38** (oracle 42 s + DP 356 s) vs the pre-cascade 10m49 (**−39%**) |

The S4 full-model optimum is therefore **exactly proven**: every conditional world's sound upper
bound falls below the proven no-condition optimum, so no conditional build can beat it.

Independent CP-SAT cross-check (partition + cutoff — full catalog, `requireAnyConditionalSublimation`,
`penalizedObjectiveCutoff = oracle+1`, 9 workers, 30 min): **UNKNOWN** — no conditional build above
the cutoff was FOUND (weak positive signal) but no refutation either (dual stalled at 35.45T, the
reification wall as always). The DP-side proof stands alone; the cross-check is banked as
inconclusive, do not re-run longer without a new idea.

Remaining: item 4 — the production port (prove entry + ProofState badge showing **proven optimal
+0%** on the S4 leg, CERTIFIER_VERSION bump, fingerprinted oracle cache, AP-headroom require→bail,
generality screen beyond CRA-245).

### 9.19 — Wall-time campaign on the exact-closure pipeline: 6m38 → ~2m45 (2026-07-16)

Four measured iterations on `adaptive cert` + `WAKFU_S4_REQUIRE_CONDITIONAL=1` (commits
`232ca198` + `20a7218d`); every run reproduced the exact closure (bound 17 702 078 146 500,
ratio 1.0000).

**Shipped (kept):**

1. **Full grid cascade in union mode** — the final bound is `max(oracle, worlds)`, so a world only
   needs the cheapest grid landing at or below that floor. The top world no longer goes straight to
   DI=1 (154 s): every contender cascades DI10 → 4 → 1, escalating only while above
   `maxOf(oracle, refinedBest)`. Both S4 contenders stop at DI10 (plain 17.611T / critZero 17.474T,
   under the 17.702T oracle) — refinements 154+68 s → ~29+29 s.
2. **Concurrent oracle** — the no-condition CP-SAT solve (typed oracle) runs in a
   `CompletableFuture` alongside the coarse DP sweep (independent; first touched after coarse).
   8 workers + the 1-thread DP coexist fine: oracle ~37-47 s, fully hidden under coarse. Adaptive
   profile only — targeted A/B screens keep the sequential solve so their walls stay clean.
3. **2× coarser assume-worlds** — cap-sub worlds sit ~45% under the main worlds; pricing them at
   `di/hp/cc × 2` is sound (coarser buckets merge states under a max) and cannot promote one into
   refinement. Coarse sweep 102 s → 78 s (states 3.87M → 2.36M).
4. **Oracle-floored refinement skip** — in union mode `refinedBest` starts AT the oracle, so any
   coarse world already at or below it skips refinement outright.

**Measured dead ends (do-not-retry, 10-core M-series / 8 GiB test heap):**

- **World-level parallel DP (pool of 4)**: every world slowed 4-6× (Mesure III plain 20 s → 125 s;
  oracle degraded 37 s → 150-163 s). The DP is memory-bandwidth/GC-bound — same verdict as the MM
  certificate's world-parallel measurement. A parallel DI10 refinement wave lost the same way
  (29 s solo → 87-94 s per world). Rebalancing oracle workers 8→6 did not rescue it (4m22-4m28
  vs 2m43).
- **Conditional-only coarse pass**: bound-INERT — at the coarse grid the binding path already
  carries a conditional sub, so the marker bit doubled every main world's states (63k → 126k)
  without moving one bound. The 19.4T → 17.6T refinement tightening comes from the finer seams
  (HP=1000, signed MP, light-weapon split), not the conditional marker.

**Operating point:** end-to-end exact closure **2m43-2m58** wall (thermal-state dependent; was
6m38, pre-cascade 10m49). Remaining wall = coarse ~78 s + two DI10 refinements ~60-95 s, with the
oracle hidden. Run-to-run variance on the DP stages is large (same refinement measured 29 s and
52 s across runs) — further micro-tuning is below the noise floor. CORRECTION: the DP's stage
apply is ALREADY chunked-parallel (up to 8 threads, ported from the MM certificate) — which is
exactly why world-level threads collapsed (4 worlds × 8 intra-stage = 32 threads) and why the
concurrent oracle degraded when stacked on it. The CPU is already saturated; there is no idle
parallelism left to harvest in this pipeline.

### 9.20 — PRODUCTION PORT SHIPPED: the soft leg gets its badge (2026-07-16)

Commits `cbd3019a` + `bf37062a`. The S4 exact closure now runs through the real production proof
pipeline — **the last never-proves workload is closed in production**.

**What shipped:**
- `MaxDamageSoftBoundPrototype` moved to main sources as **`MaxDamageSoftCertificate`**
  (test harness renamed `MaxDamageSoftCertificateTest`, all env seams intact — the locks now guard
  the production DP directly). Bail-hardening: packed-key bucket overflow and the signed-AP-fold
  headroom check return null (badge withheld) instead of throwing.
- **`MaxDamageSoftCertificate.hybridUnionUpper`** — the production orchestrator: no-condition
  CP-SAT solve (240 s budget, `OPTIMAL` objective or its dual bound on timeout — both sound)
  CONCURRENT with the conditional-only DP (coarse sweep + §9.19 DI10→4→1 cascade, winning seams
  hardcoded: λ=6000/band 5, net item budget, exact normal-sub knapsack, signed AP/MP folds,
  light-weapon split). Memoized per data/certifier version + request + pool + catalog — PROVEN
  oracles only, so a transient timeout dual can't pin a loose bound.
- **`MaxDamageSearch.proveOptimality`**: the target-missing soft branch (previously a hard
  `Unavailable`) routes to `proveSoftLegQuality` — PENALIZED-units comparison, ledger-style
  self-check (`incumbent > upper` ⇒ error log + badge suppressed), and a **badge-quality floor**:
  gap > 25% ⇒ `Unavailable` (a "proven within 46%" badge is noise). GUI badge + CLI verdicts flow
  through the existing `proveMaxDamageOptimality` pipeline — zero GUI/i18n changes needed.
- **No `CERTIFIER_VERSION` bump**: the shared AP-cell certifier math is untouched (no cached bound
  can go stale); the soft-union memo embeds `CERTIFIER_VERSION` so future bumps invalidate it too.

**Gates run (all green):**
- Full `:autobuilder:test` CI suite (fuzz lock + MM canaries) after the move.
- **E2E** `manual S4 production soft proof end-to-end` (`WAKFU_S4_PROD_PROOF=1`): an incumbent at
  the typed re-proven optimum returns **ProvenOptimal** through `proveMaxDamageOptimality`.
- **Generality screen** (`WAKFU_S4_PROD_SCREEN=1`), shapes beyond CRA-245 — sound everywhere,
  bails clean, never throws:

  | shape | union | no-cond oracle | short primal | badge quality |
  |---|---:|---:|---:|---|
  | iop-200 frontier (AP15/MP8/CC100/HP10k) | 9.168T | 8.909T PROVEN | 7.047T ✓ | ~+3% over oracle |
  | cra-140 AP14/MP7 | 3.069T | 2.095T PROVEN | 2.093T ✓ | +46% — capped to Unavailable |
  | xelor-245 with RANGE target | bail | — | — | unsupported-target gate ✓ |

**Gotcha found by the first E2E run (worth remembering):** the oracle was initially given
`certifierDefaultThreads()` — the HEAP-bound world-DP formula (~4 on an 8 GiB JVM). At 4 CP-SAT
workers the no-condition solve times out and its stalled dual (+4%) silently degraded the exact
closure to "proven within 3.99%". CP-SAT workers are memory-cheap; the oracle now gets its own
count (`cores−2` in [4,8]) — the §9.11 real-parallel lesson strikes again.

**Follow-ups (not blockers):** tightness off the S4 frontier varies (exact at S4, +3% iop-200,
+46% cra-140 → capped); a per-shape tightening campaign is possible if beta feedback asks for it.
The DP grid steps stay mutable object state guarded by `@Synchronized` on the orchestrator.

### 9.21 — Off-frontier tightening: the conditional CP-SAT probe (2026-07-16)

Diagnosis of the §9.20 cra-140 +46% (commit `230c368a`):

- **Run A** (conditional adaptive union at cra-140): the looseness is NOT one world — the whole DP
  family sits at 3.0-3.5T vs the 2.0946T oracle, main world included (+46% at DI4). At 140 the
  assume worlds (Mesure III `CRIT_AT_MOST 50 → +20 DI`, Constance) even sit ABOVE the main world,
  the inverse of 245. The binding path (`WAKFU_S4_PATH`) shows a coherent real-ish composition
  (ap=13 mp=7, 10 normal subs, Mesure III carried; the "cc=300" read is a print artifact — raw
  crit 30 × ccStep, under the 50 threshold); the looseness is spread across the relaxations
  (support-λ tuned on S4-245, critCap weights, sub packing), no single culprit — a per-shape DP
  tightening would be its own campaign.
- **Run B** (conditional-only CP-SAT probe, cutoff = oracle+1, 300 s): status UNKNOWN, dual
  **2.329T = +11.2%** — on a small low-level pool the reification wall is SOFT and CP-SAT's own
  dual crushes the DP.

**The regimes are complementary** — DP tight on huge pools where CP-SAT stalls (S4-245: DP exact,
CP dual 2×); CP-SAT tight on small pools where the DP's relative looseness explodes. Shipped:
`hybridUnionUpper` takes **min(DP conditional bound, conditional-probe dual)** on the conditional
side (min of two sound uppers; a probe INFEASIBLE above the cutoff collapses the conditional side
onto the oracle = exact closure). Gated at a **10% DP-vs-oracle gap** — tight shapes never pay it
(S4 and iop-200 skip; iop-200 wall 349 s → 179 s) — with a **300 s budget** where it fires.

| shape | before | after | notes |
|---|---:|---:|---|
| S4-245 E2E | ProvenOptimal | ProvenOptimal | probe skipped, zero cost |
| cra-140 | +46% (capped → no badge) | **+12.9%** | real badge; 300 s probe |
| iop-200 | +2.9% | +2.9% | probe skipped, −3 min wall |

**Process (user feedback, now standing):** every orchestrator change gets its soundness read on
the FAST seeded union lock first — `manual S4 union soundness lock on seeded pools`
(`WAKFU_S4_UNION_LOCK=1`, ~1 min, pinned full-model CP-SAT optimum vs the production union,
`seededPools()` shared with the DP lock) — and pays the full-pool screens exactly once at the end.

#### 9.21bis — CORRECTION: the probe must be the PLAIN full-model solve (2026-07-16, `6bc740f7`)

The user challenged §9.21's "CP-SAT cannot prove the soft leg even at 140" — and was right.
Measured at cra-140: the **plain full model proves OPTIMAL in 104 s / 2 634 branches**
(`WAKFU_S4_CP_PLAIN=1`). §9.21's inference was poisoned by its own probe design: the
{cutoff at oracle+1 + require-a-conditional-sub} model is a strictly HARDER problem (a
near-optimal-infeasibility proof — UNKNOWN at 300 s, dual +11%, on the very shape the plain solve
closes). **The conditional-reification wall is a LARGE-POOL phenomenon** — at 245 the plain dual
stalls 2-4×, at 140 it just closes.

Shipped: `hybridUnionUpper`'s probe is now the plain full-model solve — `OPTIMAL` collapses the
union onto the TRUE optimum; a timeout's dual is still a sound upper on every build. Same 10% gate
and 300 s budget. Results: **cra-140 badge +12.9% → ProvenOptimal (exact, 2 094 581 834 070)**;
S4-245 E2E ProvenOptimal unchanged (probe skipped); iop-200 +2.9% unchanged (probe skipped).

Method note: this correction went seeded-union-lock (1 min, ratio 1.0000 ×3) → single full-pool
confirmation, per the fast-locks-first protocol.

### 9.22 — Condition-encoding campaign on the cra-140 testbed: the wall resists ALL local surgery (2026-07-16/17)

Goal (user): make the conditional-sub reifications digestible by CP-SAT — ideally the full plain
solve under 10 s at cra-140 (baseline 104-169 s thermal-dependent; no-cond 4.5 s; no-subs 0.38 s).
Method: fast-locks-first, one arm ≈ 3-5 min on the testbed, det as the thermal-robust metric.

**Every arm measured (full catalog unless noted), and its verdict:**

| arm | result | verdict |
|---|---|---|
| baseline (lin2) | OPTIMAL 104-169 s, det ~1060 | reference |
| lin1 | OPTIMAL 134 s, det 700 (−34%) — but did NOT replicate in the union flow (probe failed to close a shape the lin2 probe closes) | REVERTED — "no solver knobs, ever" re-confirmed |
| lin0 / presolve8 / detectLinearizedProduct | FEASIBLE at 240 s | worse — dead |
| M1: redundant big-M rows + conflict cliques (adds, removes nothing) | FEASIBLE 240 s, det 1250 | worse — the B-family curse extends to ADDING rows |
| M2: decision strategy (conditional bools first, zero side) | FEASIBLE 240 s | neutral (ignored: no fixed-search worker in the 8-portfolio) |
| M2b: M2 + extraSubsolvers("fixed") | FEASIBLE 240 s | neutral-worse |
| incumbent objective floor | 249 s / 381k branches (vs 104 s / 2.6k) | 2.4× worse — objective-side constraints hurt BOTH directions |
| soft AP cell (AP pinned hard ⇒ AP conditions constant, penalty near-constant) | OPTIMAL **83 s** | helps ~2× but ×8 cells ⇒ dead for <10 s; crit/secMast reifications carry the wall |
| (AP,MP) pinned cell — penalty fully CONSTANT, zero bilinear (new `maxDamageMpPin` seam) | FEASIBLE at 120 s | dead — the wall is purely reifications×damage-objective, NOT the penalty product |
| profile read (log run) | full workers: millions of branches, 3.7×10⁸ propagations | the LP never cuts fractional conditional subs; the dual is ground out by CDCL |

Combined with the second agent's B/B2/B3 (§6 of the handoff doc): **the conditional-sub wall is
not digestible by any local encoding or parameter surgery** — twelve measured arms across two
campaigns now agree. The remaining routes are structural: a K-dimension (crit-mastery-aware) DP
certificate rework — the main-world binding path at 140 prices W at critCap=100 weights on a
cc=30 state, the single largest identified residual — or CP-SAT-internal work beyond model surgery.

**Positive finding shipped (`hybridUnionUpper`):** **λ is per-shape** (§6.A4 said so; now measured
off-frontier): λ=6000 (245-calibrated) is ACTIVELY loose at cra-140 — sweep {0, 2000, 6000, 12000,
20000} → +25.9% / +28.5% / +46.5% / +95.6% / +169.4%. The production DP now re-sweeps at λ=0 and
takes the min when the λ=6000 pass did not close, gated at the same 10% as the probe — tight
shapes pay nothing. (lin1 in the probe was shipped then REVERTED the same night: its single
−34%-det measure did not replicate in the union flow — a probe that closed at lin2 stopped
closing. The "no solver knobs, ever" verdict now covers the soft probe too.)

**Reality of the <10 s target:** not reachable by re-encoding. At 140 the practical picture is:
searches ≥ ~2 min self-prove (`result.isOptimal`, no proof step at all); shorter searches get the
exact badge from the probe in ~1-2 min (lin1). The scoped next campaign, if wanted: the K-dim DP
rework (closing the DP under the oracle at low level would make the union exact in oracle+DP ≈
10-15 s with NO probe).

#### 9.22ter — THE 15th ARM WINS: conditions digestible by NOT reifying them (2026-07-17, `16264fb9`)

After fourteen arms proved no reification surgery works, the winning move was to remove the
reifications entirely — soundly. The **RELAXED probe**: conditional subs KEPT (slots, credits,
epic/relic pressure intact) but conditions STRIPPED — zero indicators in the model. Sound upper on
every build: a real build whose conditional subs are inert is covered by its variant without them
(same value, feasible in the relaxed model).

| fixture | relaxed probe | verdict |
|---|---|---|
| cra-140 full catalog | **OPTIMAL in 7.4 s, objective = the EXACT optimum** (2 094 581 834 070) | free conditional credits do not improve the optimum ⇒ relaxed == incumbent ⇒ proof closed |
| S4-245 | FEASIBLE at 300 s, dual 29.4T | useless on large pools — the union takes over |

Production wiring (`hybridUnionUpper` STEP 0, 45 s budget): `relaxedUpper ≤ incumbent` ⇒
**ProvenOptimal immediately**; otherwise the relaxed upper mins into the union BEFORE the λ-0 and
CP-probe gates (a tight relaxed read short-circuits both heavy fallbacks). **E2E through
`proveMaxDamageOptimality`: cra-140 badge in `proofWallMs=4299` — 4.3 s, goal `< 10 s` met**
(was ~2 min); S4-245 unchanged ProvenOptimal (226 s incl. the 45 s relaxed overhead).

The §9.22 verdict refines to: the conditional reifications are indigestible **inside** the model —
and unnecessary for the PROOF on shapes where the optimum doesn't want the conditional credits
even free. Complementary regimes, third instance: relaxed probe (low level, instant) / DP+oracle
union (high level, exact) / plain probe (mid, gated).

**Generality caveat (user challenge, confirmed by measurement):** the fast path is
REQUEST-dependent, not level-dependent. It fires iff (a) the relaxed model proves within its
budget and (b) the conditional credits do not improve THAT request's optimum even free. cra-140
AP14/MP7 has both; iop-200 has neither (relaxed FEASIBLE at 120 s, dual 16.5T — falls back to the
union, badge unchanged). A pool-size gate was tried and REVERTED on user feedback: no a-priori
proxy can decide (b) — the probe is deliberately UNGATED, its 45 s budget being the bounded cost
of asking. On requests where a conditional sub genuinely belongs to the optimum, the fast path
simply never concludes (it cannot prove relaxed ≤ incumbent) — it can delay, never mislead.

#### 9.22quater — Controlled re-test: isolated conflict cliques are a NO-GO (2026-07-17)

M1 had combined two distinct ideas, redundant big-M rows and pairwise conflict clauses. The
clauses were therefore re-tested alone: `subA + subB ≤ 1` only for non-forced conditional subs
whose normalized same-stat/same-sheet integer domains are disjoint. They are sound implied
constraints and change neither the feasible set nor the objective.

Controlled cra-140-apmp pairs (1 worker, interleave, det=120, two seeds, alternating order) gave
candidate/baseline dual ratios **1.0049** and **1.0246**. Seed 2 reduced branches to 0.369× but
still worsened the bound by 2.46%; seed 1 worsened both bound and branches. The implementation was
reverted and the arm moved to DO-NOT-RETRY. Next controlled candidate: lin1 multi-seed/multi-shape.

#### 9.22quinquies — Controlled lin1 multi-seed/multi-shape: NO-GO (2026-07-17)

The earlier isolated lin1 win did reproduce on some seeds, but not robustly. Alternating
same-JVM pairs at 1 worker/interleave/det=120 produced candidate/lin2 dual ratios:

- cra-140-apmp seeds 1..3: **0.7770, 1.2465, 0.9050**;
- iop200-frontier seeds 1..2: **0.9850, 1.1777**.

Lin1 wins three reads but loses two by much more, and its IOP incumbents were worse on both seeds.
This is seed-dependent work redistribution, not domination. Production remains lin2; the generic
controlled-pair harness retains explicit seed/range support for the remaining queue. Next: M2b
with the existing conditional-first decision strategy actually exercised by the `fixed` subsolver.

#### 9.22sexies — Conditional-first `fixed` subsolver: NO-GO (2026-07-17)

The controlled test finally made the existing decision strategy effective by adding the `fixed`
subsolver under interleave. It was decisively harmful on cra-140-apmp: candidate/baseline dual
ratios **1.0409 / 1.2473**, branch ratios **6.04× / 1.99×** across two alternating seeds.
The explicit all-zero-conditional-first world order is not a shortcut for this objective; it
creates a much larger search tree. Portfolio unchanged. Next controlled screen: symmetry level.

#### 9.22septies — Symmetry levels 3/4 are inert (2026-07-17)

`symmetry_level=3` matched baseline bit-for-bit on two cra-140-apmp seeds (objective, dual,
branches, conflicts, LP iterations). A level-4 screen was equally identical. The repeated
secondary-mastery predicates do not yield additional exploitable symmetry through this knob.
Production unchanged. Remaining controlled confirmations: presolve8 and product detection.

#### 9.22octies — Final controlled knobs: presolve8 loses, product detection inert (2026-07-17)

Presolve8 produced candidate/baseline dual ratios **1.0397 / 1.2531** across two alternating
cra-140-apmp seeds. Although it found better incumbents and fewer branches, it materially weakened
the upper bound. `detectLinearizedProduct=true` was exactly identical to baseline on both seeds
(objective, dual, branches, conflicts, LP iterations).

This completes the controlled re-test queue. Conflict clauses, lin1, fixed branching, symmetry,
extra presolve and product detection yield no robust proof improvement; there is no winning
cross-combination to test. Production model and solver parameters remain unchanged. The next
performance work should be structural: make the conditional certificate crit-mastery-aware (K
dimension/Pareto states), or pursue per-carrier world enumeration where its economics fit.

Full-suite follow-up: the fingerprint completeness tripwire exposed that the earlier
`maxDamageMpPin` diagnostic seam was missing from its declared field list and was not normalized
in `MaxDamageCertificateCache.keyFor`. The MP pin is now normalized away alongside the AP pin,
with an explicit equality lock. This is the correct cache semantics because both pins are
probe-internal and the production soft certifier bails whenever the MP pin is active.

#### 9.22nonies — Relaxed-probe soundness correction + lazy-condition prototype (2026-07-17)

**Correction to §9.22ter:** `copy(condition = null)` did not preserve static conditional subs.
`isModelableSubShape(STATIC_CONDITIONAL)` requires a non-null condition, so those entries were
silently filtered and the alleged upper could under-count a conditional-carrying optimum. The
central transform now maps modeled `STATIC_CONDITIONAL → FLAT`, keeps conversions structured,
preserves the original one-copy rule, and leaves unsupported conditions untouched.
`CERTIFIER_VERSION` is 17 at this historical checkpoint (superseded by the v18 production
conditional-world promotion below).

Re-measured cra-140-apmp: corrected relaxed model is OPTIMAL **2,377,161,024,480 in 2.87 s**, not
the exact 2,094,581,834,070. The old 4.3 s E2E proof is invalidated; the corrected upper is +13.49%
and must fall through to the union/plain authorities.

A new conditional-carrying fixture made structural iteration cheap to test: CRA-80/free,
conditional-only catalog. Direct exact = **522720 in 11.81 s**; corrected relaxation = **663120
in ~1.1 s**. Outer approximation (solve relaxed → pin full assignment in exact → restore selected
violated gates) is sound and its exact validation costs milliseconds, but its upper solves are not
cheap enough:

- restore every selected condition: proves 522720 in 4 iterations / **84.30 s**;
- restore only individually violated conditions: finds 522720, but dual is still **577440 at 60 s**.

**NO-GO:** lazy reification/no-good iteration reproduces the proof wall and loses to the 11.81 s
direct exact solve. It also shows why simply minimizing indicator count is insufficient: restoring
the already-satisfied Mesure III condition strengthened the global relaxation enough to help.
Future work must generate a stronger generalized stat/condition cover cut, or move to world/DP
bounds; do not retry assignment no-goods or selected-gate restoration as-is.

#### 9.22decies — External conditional-world B&B proves the previously open CRA-80 oracle (2026-07-17)

Flat per-carrier enumeration is a NO-GO: 16 conditional-only worlds took 52.56 s and their max
upper stayed 663120 because every world relaxed all non-carrier conditions. The `8492` world alone
still credited `6821, 6931, 7115` for free.

A stronger, exhaustive external B&B now lives behind `WAKFU_S4_WORLD_BB=1`. From any relaxed
assignment that selects an invalid condition-bearing `s`, create children `{s excluded}` and
`{s forced, condition exact}`. This covers every exact build whether the parent solve is OPTIMAL
or merely FEASIBLE. Any child whose sound dual is at/below the incumbent is pruned immediately.
Exact pinned-assignment validation remains millisecond-scale.

An impact heuristic (DI > AP/MP/range/crit > ordinary stats; perf-only, never soundness-relevant)
reduced the conditional-only CRA-80 tree from 11 nodes / 62.78 s to 7 / 26.76 s. At 2 s/node the
8-worker portfolio twice proved in 9.47/10.51 s, but a hot repeat was inconclusive; deterministic
1-worker work is 108.79 det versus ~61.79 det exact, so this is not a universal replacement.

The important general result is the **full catalog**. Monolithic CRA-80 had only FEASIBLE 820040
after 120 s (dual 1,018,080). The external B&B proves **820040 OPTIMAL** in the stable 7-node tree,
reproduced twice at **94.73 s / 86.80 s**. Root and some children may stay FEASIBLE; branches
`6931 forced`, `7115 forced`, and `6821 forced` are nevertheless eliminated by duals below 820040.

Soundness chain for node-local domination is explicit: each mixed node is a relaxation of its
exact node; `dominationShape(params, mixedSubs)` pins all stats read by conditions still exact and
therefore preserves the mixed optimum. Hence
`exactNodeOpt ≤ mixedNodeOpt = dominatedMixedOpt ≤ bestBound`. Do NOT replace this with one pool
dominated under the full exact catalog: measured safe but far weaker (stops at incumbent 820040 /
dual 1,053,360).

The second full-catalog shape closes too: CRA-110 incumbent **1,565,500 is PROVEN OPTIMAL** in
9 nodes / **124.07 s**, versus monolithic CP-SAT still FEASIBLE after 300 s (dual 1,885,670).
This required the complete fallback rule: when the current relaxed assignment is already
exact-valid but the node dual remains above the incumbent, branch on the highest-impact condition
still relaxed anyway. The only extra split was `7256`; excluded closed at 1,547,320, forced had
dual 762,035. Exhaustivity is unchanged for any chosen condition.

A fast non-manual CI lock now constructs an impossible relaxed `crit ≤ 0` carrier plus a valid
conditional carrier, pins the monolithic exact optimum, and asserts that the external partition
branches on the impossible sub and prunes every leaf at/below that optimum.

Status at the end of this experiment: **production candidate**. This is the first structural CP
encoding in the campaign to prove two real full-catalog oracles that the monolithic conditional
model failed to prove in larger budgets. Promotion and its gates are recorded next.

#### 9.22undecies — Conditional-world B&B promoted to production, v18 (2026-07-17)

The remaining soundness/operations gates are closed. The engine now returns a bounded typed result:
`Proven(upper)`, `Inconclusive(frontierUpper)`, or `Counterexample(exactObjective)`. It uses a strict
150 s total budget, 15 s/node, max 64 nodes. CP-SAT duals are conservatively rounded with `ceil`
(never truncated), primals with `round`, and only conditionals actually present in the mixed node
are branchable. Exhaustion/cancellation/UNKNOWN therefore produces a sound inconclusive frontier,
not an exception or a proof.

Production `hybridUnionUpper` runs this after the corrected free-condition probe. A proof returns
immediately. An unfinished tree's global upper is combined by `min` with the existing relaxed +
no-condition + DP/plain-probe authorities; a pinned exact counterexample simply falls back. Thus
the new route cannot worsen the badge and cannot broaden the accepted shapes. This changes cached
certificate behavior, so `CERTIFIER_VERSION 17 → 18`.

The fast CI campaign covers 8 seeded, genuinely multi-node pools twice: at the pinned monolithic
optimum the tree must close; at `optimum − 1` it must refuse proof and expose an exact pinned
counterexample. The mandatory production-union lock also passed 3/3 against independent seeded
one-worker CP-SAT optima in **9m19s**:

| seed | exact CP-SAT | v18 union | ratio |
|---:|---:|---:|---:|
| 1 | 568,852,079,565 | 568,852,079,565 | 1.0000 |
| 2 | 2,276,045,688,840 | 2,276,045,688,840 | 1.0000 |
| 3 | 2,113,135,111,800 | 2,113,135,111,800 | 1.0000 |

Next: re-bank cold real-pool E2E timing under v18, then evaluate sibling-node parallelism with a
divided worker budget. The production promotion itself is complete.

#### 9.22duodecies — v19 bounded low-level policy after the first E2E run (2026-07-17)

The first CRA-80 production-path run did not close inside v18's 150 s world budget on a hot
machine, then continued into the legacy no-condition/DP/plain-probe budgets; it was manually
stopped after ~7 minutes. That serial budget stacking defeats the purpose of the structural route.

v19 confines conditional-world B&B to the measured level≤110 range and gives it a terminal 180 s
budget. `Inconclusive` returns `min(relaxedUpper, frontierUpper)` immediately (sound global upper,
possibly too loose for a badge); `Counterexample` returns the relaxed global upper. Levels above
110 skip B&B entirely and retain the previous certificate timing. Also, an `OPTIMAL` CP-SAT node
now uses its rounded exact objective as its dual: applying `ceil` to an exposed floating bound can
manufacture `optimum+1` from numerical epsilon and prevent an otherwise complete leaf from pruning.
`CERTIFIER_VERSION 18 → 19`.

#### 9.22terdecies — v20 removes soundness-neutral candidate probes (2026-07-17)

The bounded v19 CRA-80 production run returned soundly after 194.6 s (relaxed probe + 180 s tree)
but only at `937840`, +14.36% over incumbent 820040. The earlier 87-95 s summaries counted the
mixed upper solves recorded in tree reads but omitted per-candidate pinned validations. Those
validations selected an individually violated condition; they were never required for the
exhaustive `{s=0 | s=1+exact-condition}` partition.

v20 deletes the per-candidate model rebuild/solve loop and branches directly on the highest-impact
selected conditional. Exact full-assignment validation remains (it is the counterexample/validity
authority). Soundness and coverage are unchanged; the total budget now pays almost entirely for
node duals. `CERTIFIER_VERSION 19 → 20`.

Production E2E re-run, CRA-80/full catalog/incumbent 820040: **upper 820040, proven exactly**, in
**200.4 s** on the hot machine (corrected relaxed probe + complete bounded tree). The older
86.8-94.7 s figures were sums of mixed-node `solver.wallTime()` only and omitted orchestration;
200.4 s is the number to use for product expectations. v19 under the same bounded orchestration
returned 937840 (+14.36%) in 194.6 s, so removing the heuristic probes recovers exact closure for
only ~6 s more total wall.

#### 9.22quaterdecies — 245 generalization rejected; parent-dual intersection ships as v21

The exact production budget was screened at S4-245/full catalog with incumbent
17,702,078,146,500, 8 workers, 15 s/node, 180 s total. Exclusion-first explored 11 FEASIBLE nodes
and left a 50.920T frontier before ancestor intersection. Forced-first then processed 16 nodes;
several compound-required children became INFEASIBLE in ~1 s, but every feasible child retained
the root's finite-time dual, leaving **36.515T**. The existing fine DP upper is only **20.406T**.

**NO-GO for generalizing beyond 110:** large-pool CP-SAT node duals do not tighten in 15 s, and
world splitting multiplies those obligations. Branch order is not the bottleneck. Keep the
measured level≤110 production gate and the existing DP/oracle path at 245.

The trace exposed one universally valid free cut: child worlds are subsets of their parent, hence
their sound upper is `min(childSolverDual, inheritedParentDual)`. v21 ships that intersection so a
short child solve can never make the frontier numerically worse than an ancestor. The forced-first
order stays an env-gated research option. `CERTIFIER_VERSION 20 → 21`.

#### 9.22quindecies — certificate-first pivot; crit-aware scalar envelope (research OFF)

Strategic decision: stop optimizing external conditional CP as the main answer. Desired order is
(1) monolithic CP-SAT proof in ≤2 min for any request, else (2) one fast certificate across all
levels. The low-level B&B remains a sound fallback, but its ~200 s CRA-80 proof and failed 245
screen do not justify more heuristic work ahead of the certificate.

Before paying for a dense `(M,K)` DP dimension, the existing scalar W was transported from a crit
anchor A to each actual CC band using the sound worst coefficient ratio
`max((400+c)/(400+A), c/A)`. λ support was extended to damage-relevant CC bands even when CC is
not a target. This is research-only/OFF and changes no packed key or state count.

The 3-seed CP-SAT soundness lock passed. On CRA-80 conditional partition (834,540 states), the old
1,686,240 upper falls to 1,362,240 at the 245-calibrated λ=6000, then **928,800 (+13.26%)** at
λ=1500, in ~4.9 s. A per-band multi-anchor envelope `{50,75,100}` reaches **918,000 (+11.95%)**
in roughly three scalar passes; adding anchor 25 is inert.

The worst remaining CC band is 75..79 and anchor 100 is already its tightest support. Therefore
the residual is not principally lost M/K correlation; a dense K dimension is no longer the next
implementation. Diagnose the band-75 winning world/path and attack its DP merge or condition-arm
relaxation. This is substantially more promising operationally than B&B: a useful +11.95% bound
in seconds, with the same universal certificate architecture.

#### 9.22sexdecies — State-dependent MP ramp closes most of the low-level residual (research OFF)

Per-band provenance showed that CRA-80's surviving `A=100/base/plain/expertEligible` path borrowed
`Poids Plume III`'s globally maximal MP→DI contribution while its own item path did not own that
MP. The new research seam packs the ramp in the exact ten-normal-sub knapsack, keeps signed MP,
and evaluates its DI at collapse. A dedicated incompatible-item CP-SAT canary plus eight seeded
random item-path oracles prove soundness and non-regression in 39 s total. The broad full-catalogue
seeded lock was too expensive and was stopped after 40 minutes, so production remains unchanged
and `CERTIFIER_VERSION` stays 21 while the remaining real-shape gates run.

Results: CRA-80's four-anchor envelope is **825,840 (+0.707%)** versus 918,000 before the fix;
CRA-110 is **1,595,805 (+1.94%)** in 21.1 s; CRA-140 is **2,270,344,148,380 (+8.39%)** in 33.5 s.
One-anchor adaptive CRA-80 is **836,640 (+2.02%) in 11.2 s**. The reconstructed winner now has
`ramp=0`, confirming that the removed credit was the diagnosed cross-path merge.

λ must be calibrated by scale/arm: IOP-200 `secZero` improves monotonically across the measured
0/1500/6000 reads (9.630T/9.330T/**9.168T**), but CRA-80 `secZero@6000` regresses to 1.195M. The
research harness therefore uses 1500 below level 175 and uses 6000 only for high-level `secZero`.
Every selection is independently sound; this is a performance/tightness policy, not semantics.

An HP support dual was a clear NO-GO and has been removed: conservative maximum-HP% scaling turned
μ=10 into 9.420T and μ=100 into 14.026T on IOP-200, versus 9.168T at μ=0. Any future HP correlation
work must condition the HP% aptitude exactly before attaching a scalar HP price.

#### 9.22septdecies — Best-first grids put IOP-200 below two minutes (research OFF)

The first correctly partitioned IOP-200 hybrid with state-dependent ramp and per-world λ produced
**9,167,663,226,930 (+3.01%)** in **321.9 s / 12.27M states**. Best-first tier scheduling plus
elision of condition markers already implied by ASSUME/secZero/critZero worlds reduced this to
144.2 s / 6.06M states, bit-identical. The marker optimization has an explicit bound/core equality
and non-increasing-state-count lock on both objective arms.

Targeted reads then proved HP=2000 bit-identical to HP=1000 for plain DI10 (9.005T), critZero DI10
(8.955T), and the binding secZero DI1 (9.168T). With HP=4000 only in the coarse sweep, HP=2000 in
refinements, and secZero going DI10→DI1 directly, the complete sound union is now **73.6 s / 2.37M
states**, or 82 s including Gradle startup, with the exact same 9.167663T upper. This is a 77% wall
and 81% state reduction from the session baseline. DI20 scouts were NO-GO (too loose, +25 s).

#### 9.22octodecies — v22 production fallback is DP+DP, not a second CP-SAT

On S4-245 the optimized conditional-only DP closes under the known oracle floor in **103.5 s**:
plain DI10=17.238T, critZero DI10=17.136T, remaining coarse worlds≤17.693T, hence the exact
17.702078T floor carries the union. The real production experiment showed why the old concurrent
oracle must not remain: 8 CP workers compete with the DP and take 178.4 s total; 2 workers time out
at 240 s, and 4 workers finish at 275.6 s without proof.

A dedicated no-condition DP is cheap because filtering `condition != null` removes all condition
worlds. At DI1/HP4000/CC20 it is bit-identical to HP2000, returns **17.762813T (+0.343%)**, and takes
~12 s including Gradle. v22 therefore follows the requested product order: main CP-SAT first; if
it stalls, no second CP solve — combine the no-condition DP with the conditional best-first DP.
Production S4 reports the sound global 17.762813T bound in **103.5 s runtime**, inside two minutes.
The trade-off is explicit: the badge is currently ProvenWithin(0.343%), not ProvenOptimal. The next
exactness task is only the 60.735B no-condition DP residual. `CERTIFIER_VERSION 21 → 22`.

Production IOP-200 E2E reports **9.267128776880T (+4.13% vs the conservative 8.9T floor)** in
**62.9 s runtime**. Its no-condition DP, not the tighter 9.167663T conditional authority, owns the
union. This corrects the interpretation of the earlier 73.6 s/+3.01% conditional-only benchmark.

Promotion gates: normal `:autobuilder:test` passes in 5m43; ktlint passes; the mandatory seeded
production-union lock passes 3/3 in 4m59. Exact CP optima 0.569T/2.276T/2.113T are covered by v22
uppers 1.156T/3.826T/3.684T. Their poor ratios are expected on tiny artificial level-200 pools and
only suppress the badge; the independent non-under-count contract is closed.

#### 9.23 — v24: μ{0,250,500} lock, guarded acceptance floor, DI4-skip, 4× assume-worlds (2026-07-18)

v23's promotion checklist was completed and the wall campaign continued with per-stage/per-DP-pass
instrumentation (`soft-leg proof stage=…` / `refine world=…` INFO logs; a test-only
`log4j2-test.xml` surfaces them in JUnit output). All measurements below are on a thermally
SATURATED machine (6+ consecutive multi-minute runs); same-session deltas are meaningful, absolute
walls are upper estimates.

**Gates closed:** the μ soundness lock now sweeps μ∈{0,250,500} (3 seeds × 3 μ green, every bound
covers the exact capper optimum; μ=500 tightest on the synthetic pools — μ is genuinely
shape-dependent). The mandatory production-union lock passed three more times, bit-identical.

**Shipped in v24 (CERTIFIER_VERSION 23 → 24):**

1. **Guarded acceptance floor** (`PROD_SOFT_BADGE_ACCEPT_FRACTION = 0.015`): the refinement queue
   stops once every world is within 1.5% of the search incumbent — the DP can never fall below a
   conditional-carrying optimum, so a sub-2% ProvenWithin is this route's terminal state anyway.
   Guarded to worlds past one fine read (`tier >= 0`): a COARSE bound inside the band must still
   pay its DI10 pass, else S4-245 would trade ProvenOptimal for a ProvenWithin stop. Measured:
   IOP-200 199.7 s → 157.0 s same-thermal, verdict 0.704% → 1.082% (plain skips DI4+DI1);
   S4-245 keeps ProvenOptimal.
2. **DI10→DI1 jump for ALL worlds at level ≥175** (was secZero-only): plain's DI4 read measured
   as pure wall (bound 9.060T at IOP, WORSE than its own DI10 9.005T).
3. **Assume-worlds 2× → 4× coarser grid** (DI40/HP16000/CC80 at coarse): the nine S4 assume-worlds
   cost 55 s of the 69 s coarse sweep at 2×; at 4× they cost ~43 s (bounds rise 10.4→12.5T max,
   still ≫ margin under the 17.702T authority). Sub-linear payoff: the DP cost floor is per-option
   scanning, not state count (states −40% → wall −20%).
4. **E2E contract made shape-aware**: S4 must be ProvenOptimal (gap 0.0); other shapes ≤2%
   (the acceptance floor's band). The old flat ≤1% predated the floor.

**Failed/measured-neutral this session:** merging μ=250 into the primary refinement for the mid
band (202 s — loses the per-arm selectivity of the second pass; the μ500-both + μ250-binding-arm
pair ≈ μ250-both, confirmed cost-neutral).

**State per shape (hot walls / same-thermal deltas):**

| shape | verdict | wall (hot) | note |
|---|---|---:|---|
| S4-245 | ProvenOptimal | 153.4 s (was 167.7 same-thermal) | coarse 56.6 s + DI10×2 51.6 s + noCond/region ~45 s |
| IOP-200 | ProvenWithin 1.08% | 157-160 s (was 199.7) | secZero DI1 μ500+μ250 = 45 s is the last DP chunk |
| cra-140 | ProvenWithin 10.6% | 462 s HOT (plain probe burned 420 s without proving; ~104 s cold) | the conditional-carrying open gap, unchanged |
| cra-80 | (not re-measured) | ~200 s v20 read | B&B ≤110 leg |

**Next levers, in order:** (1) cooldown E2E measurements — the absolute numbers above are
thermally inflated and the ≤120 s question is open for S4/IOP; (2) sibling-node parallelism in
`conditionalWorldBranchAndBound` (nodes are independent CP solves; 2 nodes × workers/2 ≈ halves
the CRA-80 tree wall — the §9.22undecies deferred idea); (3) the DP per-option scan cost (the real
floor everywhere: grid coarsening has hit sub-linear returns); (4) cra-140-class remains
research-blocked (K-dim DP rework / HANDOFF 2 route 1).

#### 9.23bis — Cool-machine reference, sibling-parallel B&B, and a hard-cert self-check catch (2026-07-18)

**Thermal was NOT the dominant factor**: after a deliberate 15-minute cooldown, S4-245 measured
**158.0 s ProvenOptimal** — within noise of the hot 153-158 s reads. The v24 walls are real walls.
Breaking 120 s at S4/IOP requires the structural chantier (fold the per-cap-sub assume-worlds as an
exact per-state knapsack, the MM "world B" pattern — coarse 57-69 s → ~20 s expected), not more
orchestrator surgery.

**Sibling-parallel conditional-world B&B shipped** (`WakfuBuildSolver.conditionalWorldBranchAndBound`):
the serial DFS became a locked frontier with 2 node-workers at `workers/2` CP threads each
(1 worker below 4 CP workers, so the controlled 1-worker harnesses are unchanged). In-flight nodes
stay part of the timeout frontier; terminal outcomes (counterexample/inconclusive) win over
concurrent siblings; reads keep locked ids. The seeded CI campaign (pinned-optimum must close;
optimum−1 must refuse + counterexample) passes against the parallel driver.

Measured on the soft-leg `cra80-ap10` production path: **29 nodes in 180 s (~6.2 s/node, about 2×
the serial 11-15 s/node throughput) but the tree still does NOT close** — ProvenWithin(3.34%) in
194.7 s. The ≤110 B&B leg needs more than node throughput on target-bearing shapes.

**Hard-leg certificate self-check caught a live under-count**: `cra80-free` (a NO-target request)
routes to the AP-cell ledger, which logged `cell 6 bound=418880 < proxy=820040` and correctly
suppressed the badge (Unavailable). This is the self-check working as designed, but the under-count
itself is a soundness bug to root-cause (suspect: the v17-23 sublimation-semantics changes reaching
the hard certifier's enumeration). Spawned as a separate task; NOT caused by today's v24 changes
(which touch only the soft certificate and the B&B driver).

**E2E contracts as of v24**: `s4` must be ProvenOptimal; other shapes ≤2%. cra80-ap10 (3.34%) and
cra-140 (probe-bound) intentionally FAIL the manual E2E — they are the honest markers of the two
open structural gaps (≤110 tree closure; conditional-carrying optima / K-dim DP).

#### 9.23ter — The orchestrator lever space is EXHAUSTED at ~135 s (2026-07-18)

Three final controlled attempts, all reverted with in-code notes:

1. **`requireConditionalSub` at coarse** (+ marker elision): every S4/IOP coarse bound came out
   BIT-IDENTICAL — the binding coarse path already carries a conditional sub. Inert.
2. **Refine-grade HP step (2000) for base-world coarse**: bounds again BIT-IDENTICAL for +14%
   states — the binding coarse path OVERSHOOTS the HP target, so the bucketed HP penalty never
   engages. The coarse looseness is in the seams, not the HP grid.
3. **Unsplit-first DI10 scout**: NO-GO — the light-arm split IS the dominant DI10 tightener
   (S4 plain: 18.129T unsplit vs 17.238T split), so the half-cost scout rarely clears the floor
   and its cost stacks (S4 +7.6 s, IOP +13 s).

Best observed walls in the cooler late-session window: **S4-245 ProvenOptimal 135.3-135.7 s**,
**IOP-200 1.08% 133.9 s**. Composition at S4: noCond DP+region ~33 s, coarse ~50 s (of which
assume-worlds ~43 s), DI10 refinements ~45 s. Every remaining second is either DP-internal
(per-option scan) or structural:

- **Assume-world knapsack fold** — the analysis in this session established it is NOT a mechanical
  transform: AP/CC AT_MOST conditions read a RAW-low sheet that base worlds do not track; folding
  needs either raw-low state dimensions (the state cost the split exists to avoid) or a per-state
  slack bound. An unconditional best-cap-credit award is analytically dead (it would inflate
  plain far above the authority). This is the §9.23 chantier, now with its design constraints
  mapped.
- **≤110 B&B tree closure** (cra80-ap10 does not close at 2× node throughput).
- **cra-140-class K-dim DP rework** (HANDOFF 2 route 1).

The two-minute goal stands at: S4-245 ✅ (~135 s), IOP-200 ✅ (~134 s) under two minutes on the
observed best-case machine state, but WITHOUT margin; cra80-ap10 (194.7 s) and cra-140 ❌ pending
the structural work above.

#### 9.24 — v25: EVERY reference request proves under two minutes (2026-07-18)

Two structural moves close the goal the orchestrator levers could not:

1. **The `capFree` cover arm** (CERTIFIER_VERSION 24 → 25). Each assume-world is priced ONCE with
   a new arm that stages every objective-capper sub with NO cap and NO forcing — with both
   `armZero*` flags false, every mastery line and conversion prices at its full sound ceiling
   (conversions fall to the `reachableMax` branch), so the single read is a sound superset of the
   world's three arm reads. The world sweep drops 12 → 6 worlds; S4's coarse falls 52.9 → 25.7 s.
   The merged bounds stay far under the authority (Mesure III: 13.07T vs 17.702T), so nothing new
   enters the refinement set; if it ever did, the queue re-prices it at DI10 as usual.
2. **A proof-phase deadline** (`PROOF_PHASE_DEADLINE_SECONDS = 110`): the open-ended CP legs —
   the ≤110 conditional-world tree and the full-model plain probe — are clipped to what remains
   of the budget (with a 20 s floor). The DP legs are never clipped, and S4/IOP never reach the
   CP legs, so their exactness is untouched.

Measured (production E2E, same machine/day):

| shape | verdict | wall | before |
|---|---|---:|---:|
| S4-245 | **ProvenOptimal** | **96.3 s** | 135-158 s |
| IOP-200 | ProvenWithin 1.08% | 119.5 s | 134-199 s |
| cra80-ap10 | ProvenWithin 3.34% | 110.1 s | 194.7 s |
| cra-140 | ProvenWithin 13.49% | 110.3 s | 462 s (hot, unbounded probe) |

Notably cra80-ap10's clipped 12-node tree yields the SAME 3.34% frontier as the 29-node run —
the v21 parent-dual intersection carries the pruning. cra-140's 13.49% is the relaxed-probe upper
(the clipped plain probe did not tighten on a hot machine; cold it may still prove outright inside
its ~70 s slice) — its QUALITY remains the open K-dim research gap, but its wall no longer is.

Locks: the union lock and the μ{0,250,500} lock pass under v25; per-shape E2E contracts now
encode the product: `s4` = ProvenOptimal, `iop200-frontier` ≤ 2%, deadline-clipped shapes ≤ 25%
(the badge floor) — ALL with a 150 s wall assertion.

#### 9.25 — v25b: k-ary branching + parent hints make the world tree CLOSE — 3 of 4 shapes ProvenOptimal (2026-07-18)

The user raised the bar to **ProvenOptimal under two minutes everywhere**. Campaign results:

1. **k-ary SOS branching** in `conditionalWorldBranchAndBound`: at a BRANCH node over selected
   conditionals [c1..ck], emit {c1 req} ∪ {c1 excl, c2 req} ∪ … ∪ {all excl} instead of the binary
   chain. Same coverage, but the "all excluded" child skips the substitute-ring rediscovery that
   the binary chain paid one 15 s node at a time: cra80-ap10 closure 43 nodes/456.8 s → 13/140.1 s.
2. **Parent-assignment hints**: each child receives the parent's mixed-solve assignment as a CP-SAT
   solution hint (deduped by var name — duplicate hint entries are MODEL_INVALID). Closure
   13 nodes/140 s → **10 nodes/104 s**, then 9/61 s and cra-140 **6 nodes/65.5 s** (a tree that
   never closed under the binary chain).
3. **B&B-first routing**: the relaxed probe is now LAZY — on the ≤gate route the tree runs first
   with its full budget and a Proven result never pays the probe at all.
4. **Gate extended 110 → 140** (measured closed; 200+ stays out — mixed duals +218%).
5. **Soundness fix caught by the self-check**: MODEL_INVALID (from the initial duplicate-var
   hints) fell into the prune test with a garbage native bound of 0 and became a fake closed
   contribution (`Proven(upper=0)` → badge suppressed by the self-check exactly as designed).
   Any status outside {OPTIMAL, FEASIBLE, INFEASIBLE} now ends the tree inconclusively.

Failed/reverted with in-code notes: 4×2 node-workers (2 CP threads cannot close required-nodes;
CPU-bound at 2×4 anyway); `objective ≤ inheritedUpper` node cap (161 s vs 140 s — §9.22 cap
do-not-retry holds); 10 s/node (tree explodes, 56 nodes).

**Production E2E, ProvenOptimal walls:** S4-245 **96.3 s**, cra80-ap10 **61.1 s** (9 nodes),
cra-140 **35.1 s** (5 nodes). The ONLY remaining non-optimal shape is IOP-200
(ProvenWithin 1.08%, ~119 s): no measured CP route proves it (monolith +136% at 600 s; mixed
nodes +218%), so its route is the P3 research task — close the conditional DP's last 0.704%
(cross-path merge looseness in the secZero core) or find a better conditional incumbent.

#### 9.26 — v27: per-request λ auto-calibration + GUI proof narration (2026-07-18, user GO)

The user's generalization concern ("nos 4 formes sont des échantillons — le 0.7% peut être plus
grand ailleurs; il faut automatiser la découverte du moyen de prouver par requête") shipped as:

1. **Badge-owner λ auto-calibration** (CERTIFIER_VERSION 27): after the μ pass, the pipeline
   re-reads THE WORLD OWNING THE UNION MAX at the alternate λ (LOW-band worlds only, since that is
   the measured miscalibration class) and min-intersects, iterating while the owner changes. Each
   λ is independently sound. One extra DP read only when it can change the verdict. Placement
   matters: BEFORE the μ pass the owner is misidentified (secZero at its μ500 bound) — measured as
   a no-op at 116 s/1.08%; after the μ pass it fires correctly.
   Journey: naive always-alt = 434 s (compounded by a secPos dedup regression, see below) →
   146 s → badge-owner-only post-μ = **141.5 s hot, badge 1.082% → 0.704%** at IOP-200.
   Trade-off vs no-calibration (116-120 s / 1.08%) accepted by the user for genericity; the
   150 s E2E wall contract absorbs it.
2. **GUI proof narration** (user GO): `onPhase` threaded proveMaxDamageOptimality →
   proveOptimality → proveSoftLegQuality → hybridUnionUpper's stage stamps; ProofProgress gains
   `detailKey`; StatsPanel maps stage keys to EN/FR labels ("Balayage des mondes conditionnels…",
   "Auto-calibration de la preuve pour cette requête…", "Tentative de preuve CP-SAT complète…",
   B&B = "Exploration des mondes de sublimations conditionnelles…"). Unknown keys fall back to the
   generic label.
3. **secPos dedup regression fixed**: populating `Opt.secPos` unconditionally split stat-identical
   ring pairs / weapon combos at distinct() — a 2.5-7× refine-read slowdown (secZero DI1 20 s →
   143 s) with IDENTICAL bounds. All secPos population is now gated on `secDimActive` (the
   dormant P3 seam), and refine walls returned to baseline.

Gates: full :autobuilder:test + :gui-compose:test + ktlint green; μ{0,250,500} lock green (also
green with the research dimension ON); union lock green.

#### 9.27 — First generality matrix run: the hot spots are FOUND (2026-07-18)

The matrix harness (`WAKFU_S4_PROOF_MATRIX=1`, 6 shapes across classes × levels × target styles,
full production proof each) delivered its purpose on the first run — the 4 reference shapes were
hiding real hot spots:

| shape | verdict | wall |
|---|---|---:|
| cra50-apmp | **ProvenOptimal** | 35.1 s ✅ |
| iop110-full | **Unavailable** | **225.4 s** ❌❌ |
| xelor155-apmp | ProvenWithin **12.87%** | 110.4 s ⚠️ |
| cra185-full | ProvenWithin 3.15% | 137.3 s ⚠️ |
| iop215-full | ProvenWithin 0.35% | **179.6 s** ❌ |
| sacrieur230-apmp | ProvenWithin 9.55% | 49.1 s ⚠️ |

Diagnoses to run (next campaign, driven by this instrument):
1. **iop110-full**: the ≤140 B&B did not close (Unavailable = the inconclusive frontier exceeded
   the 25% badge floor) and burned 225 s — the k-ary+hint tree needs the same closure diagnosis
   that fixed cra80-ap10, or the shape must fall through to the DP union instead of the terminal
   Inconclusive return.
2. **iop215-full**: badge excellent (0.35%) but 179.6 s — the mid-band pipeline needs its stage
   breakdown (likely the same μ/DI1 cost class as IOP-200 pre-v24).
3. **xelor155-apmp 12.87% / sacrieur230-apmp 9.55%**: loose DP classes never diagnosed (Xelor WP
   mechanics; 230 without CC/HP targets) — provenance runs needed.

The matrix is now the campaign driver: after each fix, re-run it; add shapes as classes/styles
get coverage. This is the automation the user asked for ("trouver les moyens de prouver en
fonction de la requête") on the DETECTION side; the per-request routing (B&B gate, λ
auto-calibration, complementary regimes min) is the response side.

#### 9.28 — v28: the matrix campaign's first sweep (root prognosis + DP fall-through + DI10 alt-λ)

Three generalization mechanisms shipped, driven by the §9.27 matrix hot spots:

1. **B&B root prognosis** (`rootBailFraction = 0.25`, root gets a 30 s budget): a root dual beyond
   incumbent+25% means the reification wall makes the tree unclosable (iop110-full root +142%
   never closes; cra80-ap10 +14% closes in 5-9 nodes) — bail after ONE node. The root budget
   matters: at 15 s a thermally-slowed cra80 root stayed FEASIBLE with a loose dual and
   FALSE-bailed (ProvenOptimal lost); at 30 s it closes OPTIMAL and the prognosis is reliable.
2. **Inconclusive tree → DP fall-through** (was a terminal return): iop110-full went
   Unavailable/225 s → ProvenWithin(2.78%)/114-125 s. The lazy relaxed probe is now gated at
   >10% union gap in BOTH min sites (it burned its full 45 s at the END of a 2.78% proof).
3. **DI10 alternate-λ before descent**: a LOW-λ world still above the floor at DI10 tries the
   alternate λ at the SAME grid once before paying DI4/DI1 (iop215: plain paid DI1@1500 43.7 s,
   stayed above the floor, then the post-μ calibration re-paid DI1@4000 — the DI10 alt read
   closes it for ~13 s). The post-μ badge-owner calibration is kept as the badge-side play.

Matrix sweep 2 (hot machine, full suite run just before):

| shape | sweep 1 | sweep 2 |
|---|---|---|
| cra50-apmp | ProvenOptimal 35 s | ProvenOptimal 54.6 s |
| iop110-full | Unavailable 225 s | **2.78% / 125.3 s** |
| xelor155-apmp | 12.87% / 110 s | 12.87% / 113.9 s |
| cra185-full | 3.15% / 137 s | 3.15% / **107.7 s** |
| iop215-full | 0.35% / 180 s | **ProvenOptimal 124.0 s** |
| sacrieur230-apmp | 9.55% / 49 s | 9.55% / 55.6 s |

CERTIFIER_VERSION 28. Full suite + union lock green. Remaining matrix items: the loose badges
(xelor155 12.87%, sacrieur230 9.55% — provenance diagnoses), iop110's 2.78%, and re-verifying the
four reference shapes under v28 (theory: untouched — S4 λ6000, IOP-200 floor-stops before the alt
gate, cra80/cra140 close in the tree).

#### 9.28bis — v28 reference non-regression + the 10-shape state (2026-07-18 close)

References under v28: S4-245 ProvenOptimal 108.5 s; IOP-200 0.704% 133.8 s; cra-140 ProvenOptimal
49.9 s; cra80-ap10 ProvenOptimal 41.0 s. Combined with sweep 2, the 10 measured shapes:

- **ProvenOptimal**: S4-245 (108 s), cra80-ap10 (41 s), cra-140 (50 s), cra50-apmp (55 s),
  iop215-full (124 s).
- **ProvenWithin ≤3.2%**: IOP-200 (0.70%/134 s), iop110-full (2.78%/125 s), cra185-full
  (3.15%/108 s).
- **Loose badges, wall fine**: xelor155-apmp (12.87%/114 s), sacrieur230-apmp (9.55%/56 s).

Every measured shape returns its verdict in ≤134 s on a loaded machine. Open campaign items:
provenance diagnoses for the two loose-badge classes; the 2-dim S⁺×negB research for the
sub-1% shapes' ProvenOptimal; extend the matrix as coverage grows.

#### 9.29 — Matrix sweep 3 (12 shapes): coverage doubled, two class-patterns emerge (2026-07-18)

| shape | verdict | wall (loaded machine, 11 h of benches) |
|---|---|---:|
| cra50-apmp | ProvenOptimal | 71.5 s |
| iop110-full | 2.78% | 151.3 s (thermal; 114-125 isolated) |
| xelor155-apmp | 12.87% | 111.5 s |
| cra185-full | 3.15% | 108.3 s |
| iop215-full | ProvenOptimal | 123.9 s |
| sacrieur230-apmp | 9.55% | 57.4 s |
| feca65-full | 14.96% | **193.3 s** ← hot spot |
| enutrof125-cchp | 2.89% | 135.3 s |
| panda170-apmp | 13.33% | 114.0 s |
| eca195-full | 6.55% | 106.2 s |
| osa225-full | **ProvenOptimal** | 124.2 s |
| steamer240-apmp | 9.42% | 56.3 s |

Two clear class-patterns for the next iteration:

1. **AP/MP-only shapes share 9-13% badges** (xelor155, panda170, sacrieur230, steamer240 — and
   likely feca65's style contributes): one common phantom suspected (the crit-support λ pricing on
   shapes with NO CC target?). ONE provenance diagnosis (WAKFU_S4_PATH on a binding world of
   xelor155-apmp) should close four shapes at once.
2. **feca65-full stacks fallbacks to 193 s**: low-level full-target = B&B root-bails (loose root),
   DP lands 15% (> the 10% gates) → relaxed 45 s AND the CP probe both fire. The per-leg deadline
   clip must apply to the low-level path too (budget the relaxed+probe legs against
   PROOF_PHASE_DEADLINE remaining, as the ≥175 legs already are).

The matrix is doing its job: each sweep finds the next class of work. State: 12/12 shapes return a
displayable verdict; 9/12 under 120 s hot, 3 between 124-193 s (thermal-inflated); 4/12
ProvenOptimal.

#### 9.30 — v29: capFree re-split converts two more shapes (matrix sweep 4, 2026-07-18 night)

Sweep-3's "AP/MP-only loose badge" pattern root-caused: the badge owner was the **capFree merged
assume cover** (xelor155: capFree/Mesure III at +12.87% — priced with NO caps, fine while under
the authority as at S4/IOP, but at small/mid levels it emerges ABOVE and owns the badge). Fix
(CERTIFIER_VERSION 29): any capFree world still above the floor at the final tier is RE-SPLIT
into its exact three-arm partition (plain/secZero/critZero per assume — sound, and lazy: never
triggered when the cover stays under the authority, so S4/IOP pay nothing).

Sweep 4 (machine at 12 h of continuous benches — walls ~+25% thermal):

| shape | sweep 3 | sweep 4 |
|---|---|---|
| sacrieur230-apmp | 9.55% / 57 s | **ProvenOptimal** / 113 s |
| steamer240-apmp | 9.42% / 56 s | **ProvenOptimal** / 115 s |
| feca65-full | 14.96% / 193 s | 9.29% / 209 s |
| xelor155-apmp | 12.87% / 112 s | 10.79% / 141 s |
| panda170-apmp | 13.33% / 114 s | 12.17% / 150 s |
| (others) | — | stable |

**6/12 matrix shapes ProvenOptimal** (cra50, iop215, sacrieur230, osa225, steamer240 + references
cra80-ap10/cra-140/S4-245 outside the matrix = 9 total). Remaining campaign: xelor155/panda170
residual owners (~11-12%, post-capFree — identify via the E2E refine logs), feca65 (209 s wall +
9.3%), enutrof125 (184 s thermal). Union lock green.

#### 9.31 — Sweep 4bis: feca65 209 → 163.7 s, badge 14.96% → 5.91% (2026-07-18 night)

Three low-level-path fixes (CERTIFIER_VERSION 29, same version — orchestration only):
1. Lazy relaxed probe deadline-clipped (was a fixed 45 s burned late).
2. **Queue RESUME after the capFree re-split** — the re-split can hand the union max to a world
   still at a coarse tier (feca65: secZero/base owned the badge at DI10 only; its DI1 costs ~2 s).
   Badge 9.29% → 5.91%.
3. **Early no-condition oracle**: the oracle future now starts BEFORE the B&B (it was serialized
   after it — a measured 45 s hole at feca65 whose oracle takes ~39 s), at HALF workers during the
   overlap (8+8 tree workers on 10 cores doubled the oracle wall and re-created the hole).

feca65 residual (~164 s hot): a ~35 s join hole remains (the 4-worker overlapped oracle still
outlives the 30 s tree bail) + the stacked legs (relaxed 29 s, λ-calib 21 s, re-split+resume 32 s)
— each individually justified; the next cut is joining the oracle lazily at first REAL need and
fusing the λ-calib/re-split passes. Campaign continues; walls at 14 h of continuous benches.

#### 9.32 — Sweep 5 close: xelor 5.95%, thermal variance exposes the wall-budget prognosis (2026-07-18 late)

- **xelor155-apmp: 10.79% → 5.95% / 101.4 s** — the sweep-4bis queue-resume plus extending the μ
  support pass below level 175 (the ≥175 gate was arbitrary; a 40 s probe measured μ=250
  tightening secZero DI1 by 1.1% at 155). The 30-60 s single-world probes predicted the outcome
  before any E2E — the fast-iteration protocol the user asked for, now the campaign standard.
- **cra80-ap10 became erratic under 15.5 h of continuous load**: the 30 s wall-budget root no
  longer closes, the tree goes Inconclusive and the DP fall-through returns 4.45%/117 s instead
  of ProvenOptimal/41 s. The prognosis and node budgets are WALL-based and therefore
  load-sensitive. **Next fix: switch the root/node budgets to CP-SAT DETERMINISTIC time**
  (maxDeterministicTime — the seam already exists in conditionalWorldBranchAndBound), which the
  controlled-protocol work already showed is load-invariant. Also: never bail on a timeout alone
  — only on a genuinely hopeless dual.
- Oracle placement finalized: future starts AFTER the B&B (before it stole cores from proving
  trees: cra80 41→176 s even at half workers); the LAZY join still overlaps it with the whole DP
  phase, so feca65's serial hole stays gone (128.5 s, 209 at sweep 3).

Milestone committed as `f4e00bb2` (v17→v29, 16 files, +5074). μ lock green with the <175 μ pass.

#### 9.33 — Deterministic node budgets: the B&B is load-invariant (2026-07-18 close)

The wall-budget prognosis erraticism (§9.32) is fixed: per-node budgets are now CP-SAT
DETERMINISTIC time (det 60, root ×2 — measured roots close at det 50-70, inner nodes 20-48),
with the wall raised to a coarse 45 s safety net. Under 16 h of continuous bench load:
cra80-ap10 back to **ProvenOptimal (113.6 s, 9 nodes — the tree closes reliably)**; cra-140
ProvenOptimal 106.9 s; B&B CI locks green. Cold walls unchanged (det binds at ~8-25 s/node).

#### 9.34 — AP/MP-only class closed to its structural floor (2026-07-18 end)

panda170-apmp: 12.17% → **8.13% / 85.2 s** (queue-resume + μ<175, same as xelor). The residual
owner on BOTH class representatives is `secZero/base` after every pass (xelor 5.95%, panda 8.13%)
— the SAME structural secondary-credit looseness as IOP-200's 0.70%, larger on AP/MP-only shapes.
No quick wins left in this class: walls are healthy (85-101 s), the badges await the 2-dim
S⁺×negB modeling campaign (P3), now with six well-characterized target shapes
(IOP-200 0.70%, iop110 2.78%, enutrof125 2.89%, cra185 3.15%, xelor155 5.95%, feca65 5.91%,
panda170 8.13%).

Probe-vs-E2E note: the 40 s secZero probe (split-both, 3.8333T) and the E2E μ-pass per-arm reads
(3.9113/3.8309T) disagree by ~2% on the same world — worth a 30 s parameter-diff check at the
start of the P3 campaign (suspected: light-arm mapping or band defaults in the probe harness).
