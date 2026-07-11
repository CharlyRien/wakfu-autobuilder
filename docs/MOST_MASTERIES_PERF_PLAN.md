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

Today's objective is `coreScore × penaltyMultiplier` through a 2001-entry power-6 table +
bucketed division (WakfuBuildSolver.kt:2237-2273, StatBuilder.kt:914-946) — the code's own doc
calls this "the penalty product, whose foggy LP relaxation traps the search" (StatBuilder.kt:1704).
Max-damage escaped the same pathology with a hard leg, and the machinery is already mode-agnostic
(`addRequiredTargetHardConstraints`, StatBuilder.kt:1719-1729; `hardConstraints` plumbing in
`optimize`, :493-497).

Design: hard leg = `actual ≥ target` + plain diAdjusted mastery objective (+ overshoot semantics
preserved); on INFEASIBLE fall back to today's soft model. **The soft leg stays the authority** —
the hard result feeds it as hint + sound objective lower bound (hard-feasible builds achieve the
max penalty multiplier, so the bound is exact and optimum-neutral). Lock with optimum-equality
fixtures vs the soft path, including target-miss shapes.

### P2b — two-stage lexicographic overshoot split

`withOvershootTieBreaker` folds `primary × 10 000 + bonus` (:2293-2315): objective domain ~1e18,
one division + ~4 reified clamps per target on the objective path, and the primal keeps "improving"
through overshoot-only steps. Stage 1: maximize the primary alone. Stage 2: pin
`primary == best`, maximize overshoot in a short solve (infeasibility impossible), **before the
final emission on every exit path** (normal, stopWhenBuildMatch, ε-stop) — the emitted build must
still carry the overshoot behavior (skill dumps into HP/CC), locked by BuildCombinationTest + the
GUI E2E. Provably the same lexicographic optimum; also improves LP conditioning for every other
lever.

### P3 — MM E8-analogue construct (highest ceiling, widest variance, run LAST)

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
