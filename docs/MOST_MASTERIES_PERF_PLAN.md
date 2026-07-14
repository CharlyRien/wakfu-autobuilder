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
