# Mixed runes under a secondary budget

2026-10-06 · Wakfu data 1.93.1.62 · written against `CERTIFIER_VERSION` 57 / `ENGINE_RESULTS_VERSION` 2 (PR #251). "v56
fold" below = the all-or-nothing fold this replaces; the measurements below were taken on that code.

**Ported onto main on 2026-10-11 as `CERTIFIER_VERSION` 62 / `ENGINE_RESULTS_VERSION` 7** (61 / 6 shipped with 1.18.0, so
the change takes the next numbers, AGENTS.md §4). What the port changed:
- **Item stat gates** (landed on main meanwhile, v57/v58) read the out-of-combat sheet, runes included, so they are rune
  readers too: `statGateRuneReads` (RuneModelBuilder.kt) feeds every gated stat of the pool into `maxDamageRuneReads` as an
  OPAQUE stat (never pruned, never pruning) and a threshold (`mixedStats`). Without it the choice collapse pruned the
  elemental rune for the larger distance one on every carrier, so a distance-gated item could only be worn with a rune fill
  that broke its gate: on `MixedRuneSecondaryBudgetTest`'s gate case (CRA 110, an amulet gated `distance mastery ≤ 20`) the
  fold PROVED 178,265 against the count model's 302,495 — a wrong "proven optimal" that main had independently of this
  change. With the gate as a threshold, caps 230 / 270 also need a part-filled item (fold 320,170 / 323,200, counts
  320,675 / 323,705).
- **Sublimation stacking** (#269, v60/v61): Neutralité III now reaches its family level 4 (+32 % DI instead of 24), so the
  minimal repro below now reads fold 1,382,985 vs count model 1,408,170 (+1.82 %); the mechanism is unchanged.
  `negativeStatSources` also reads a stacking sub's per-level values (`valuesByLevel`), not only its top value.
- The cost measurements below were NOT re-run on main. The GUI-default regression they show is addressed by two exact
  remedies, built in parallel and integrated on `perf/mixed-runes-integrated` (section "Integration" below): the HYBRID
  ("The hybrid": the search runs on the fold again, the count model polishes its final build and is the only source of an
  optimality proof) and the count CLASSES ("Count classes": the count model made ~6× smaller), which every count stage of
  the hybrid uses. The wall-clock A/B on a calm machine is still to be run (`docs/perf-review-backlog.md` §E OPEN #2).

## The gap

Max-damage folds the rune model: every socketed item is filled with ONE rune type (a pick bool per type,
`Σ picks = equipped`), and the choice collapse keeps the Pareto set of those types per carrier
([RUNE_CHOICE_COLLAPSE_FIX.md](RUNE_CHOICE_COLLAPSE_FIX.md)). The premise: a rune's value is the same in every socket
of an item, so the best single type beats any mix. That holds while every reader of a rune is LINEAR in its count —
and a Neutralité-family cap (`each secondary mastery ≤ 0`) is not. An item's NEGATIVE line of a capped secondary leaves
that stat a budget: −102 melee on a helmet, −58 on a ring, and the build may carry 160 melee before the cap breaks.
Melee runes are worth 33 against 22 for an elemental one (rune level 11), but the fold sells them in whole items of
4 × 33 = 132: one item of them leaves 28 of the budget unspent, two overshoot it. A mixed helmet (2 melee + 2 elemental)
spends it. The fold missed that build, and CP-SAT's `OPTIMAL` over the fold was a wrong "Optimal proven" badge.

## Measurement (`main` @ `bbf1c11`, v56)

A seeded negative-biased fuzz (scratch harness, not committed): 3–5 socketed slots × 2 items (+ rings, a weapon), item
levels within 60 of the character's, every scenario secondary and crit mastery drawn NEGATIVE four times in five, a
random part of the Neutralité family with Critical Secret / Unraveling, choosable or one member forced; each pool solved
free and with random AP / MP / RANGE / CC rows (soft and hard leg). Reference: the per-stat COUNT model
(`forceRuneCountModel`). Deterministic protocol (1 worker, seed 1, interleave, det 60 per solve).

| | comparisons | count > fold | gap |
|---|---|---|---|
| v56 fold, seeds 4–80 | 456 | 64 (13 of 77 pools, in every mode each ran) | +0.049 % … +2.857 % |
| this change, seeds 1–80 | 474 | 0 (fixed fold = count optimum, all proven) | — |

Both Neutralité modes diverge, free and rowed alike: the per-stat budget does not care. Every certificate ledger of
both runs (every cell confirmed exactly; the target-aware ledger on the hard leg) was ≥ the count optimum — 0 of 314
under with this change — so the wrong badge came from the CP-SAT proof over the folded model, not from the certificate (below).

The minimal repro (delta-debugged from seed 9, `MixedRuneSecondaryBudgetTest`): CRA 245 fire / melee / back, Neutralité
III choosable, four items — helmet (414 elemental, −102 melee, 4 sockets), belt (365 fire, −214 rear, 4), chest (369
elemental, 4), ring (159 elemental, −58 melee, 4). v56 fold 1,320,570; count model 1,328,235 (+0.58 %). The optimum
takes Neutralité III with a helmet of 2 elemental + 2 melee runes.

### The same flaw without a sublimation: a required row

A required row on a rune stat is a threshold too. A scratch sweep with no sublimation at all — CRA 50, three 4-socket
items of 300 fire mastery, a hard (and a soft) HP row swept across the reachable boundary — found the fold below the
count model on 28 of 102 points (51 targets × hard / soft leg; the fixed fold equals the count model on all 102) (+0.21 % … +0.44 %): when the items and skills fall just short of the row, one HP rune
tops it up and the rest of the item stays mastery, where the fold sold HP runes only by the whole item. A GUI-default
max-damage request (HP 2000) has this shape, so its CP-SAT `OPTIMAL` was exposed too. (The same sweep at level 110
found no divergence in the 84 points it covered before the run was stopped — larger skill-point HP covers the shortfall there.) The fix covers it with
the same rule; the target-aware certificate never reads HP, so it was and stays sound for it.

## The fix: count carriers (option a, exact)

`MaxDamageRuneReads.mixedStats` names the rune types a single-type fill cannot represent exactly. A carrier whose kept
choices include one of them beside another type gets one integer COUNT per kept type (`0..slots`, `Σ = slots·equipped`);
every other carrier keeps its picks (or the equip-var alias). The general fold (a target row added a non-damage rune
type) uses the same rule over all its types.

A type is mixed iff a THRESHOLD reads it:
- a CHOOSABLE `stat ≤ t` cap with a budget that buys something: `t > 0`, or some source can make the stat negative
  (`negativeStatSources`: an item line of the pool, a sub's stat effect — skills, runes and the base never are) — and
  the runes are worth something under the cap: an M-feeding type always; critical mastery only with `t > 0` or a
  conversion out of it (under `crit ≤ 0` a crit rune is clamped out of `Graw = (400 + c)·M + 5c·max(0, K)`). While the
  cap is not taken it restricts nothing and the fill is linear again;
- a FORCED condition (its effect is gated by it and may be a malus: the build may want to cross it either way) or a
  non-`≤` comparison — always;
- a ramp source, a best-element concentration's masteries, a required target row with a positive target or a stat a
  source can make negative.

Why that is exact: fix every other decision of a build. A carrier whose types no threshold reads (or none with a budget)
faces a linear objective over its fills — `Graw` at a fixed crit rate, its `max(0, ·)` clamps convex — and whatever
holds a budgetless type at 0 holds it at 0 in every fill, so a vertex (one type) is optimal. On a count carrier nothing
is lost. The Pareto pruning and the choice gates hold rune by rune (swapping one socket from a dominated type to its
dominator keeps every taken cap and never lowers the objective), so they stay; a gated count is posted
`count ≤ slots·Σ subVar`.

In the real catalog every secondary but healing has negative lines (Cartes Truchesques −120 distance, La Zimasse −400
rear, …), and domination keeps them (it pins the secondaries a cap reads), so in production every carrier offering a
scenario secondary beside another type becomes a count carrier as soon as a Neutralité-family cap is choosable — that
is option (b) in practice, reached by an exact local rule. The cost is below.

### Every non-linear reader of a rune stat (adversarial review, 2026-10-06)

The first version keyed the rule on the stats as the model names them, and the review found three threshold reads it
missed — each with the all-or-nothing fold proving (`OPTIMAL`) less than the count model, no sublimation needed:
- **(a)** an "all resistances" row stays `RESISTANCE_ELEMENTARY` in a max-damage request (the GUI splits it only in
  most-masteries), but the runes are per element: CRA 200, a 0-row (a floor) with −105 fire resistance on the best
  helmet — fold 849,355 vs count 853,830; with −160 on every element — 741,060 vs 800,130 (+8.0 %); a positive row
  (prefiltered, so never badged) up to +10.4 %. Rows are now keyed like `relevantRuneStats` (`runeKeysOf`);
- **(b)** a negative AGGREGATE resistance line (16 catalog items) or a negative random-element one lowers the four
  per-element resistances, but was recorded under its own key: a fire 0-row with −105 on every element — 849,355 vs
  853,830. `negativeStatSources` now expands such lines onto the four elements (masteries alike);
- **(c)** the survivability floor reads HP × the resistances through `min(EHP, floor)` and a bucket table: CRA 20, three
  4-socket items of 60 fire, an EHP floor of 440 — 12,800 vs 12,900. HP and the four resistances are now thresholds
  whenever the floor is on. (This path could show a badge: `proveOptimality` returns ProvenOptimal on CP-SAT `OPTIMAL`
  before its survivability gate.)

The full list of what the max-damage model reads from a rune stat (runes exist for the elemental / melee / distance /
berserk / rear / critical / healing masteries, the four resistances, HP, lock, dodge and initiative):

| Reader | Linear in a carrier's counts? | In `mixedStats` |
|---|---|---|
| damage objective `Graw = (400+c)·M + 5c·K` (M ≥ 0, K ≥ 0 clamps), DI / %-skill multipliers | yes (convex) | no |
| a choosable `≤ t` sub condition (Neutralité family, Critical Secret, Furie's `dodge < level`) | no, while taken | when a budget buys something |
| a forced condition, an `≥` / `=` condition | no | always |
| a required row: hard `actual ≥ target`, soft power-6 penalty; a 0-row is a floor (`≥ 0` / the halving) | no | target > 0, or a negative source |
| per-element resistance rows / floors with random-element rolls (`familyFoldElements`) | no | through the per-element row keys |
| the survivability floor (`min(EHP, floor)`, bucket table) | no | HP + four resistances |
| a per-stat-step ramp's source (only MP in the catalog: no rune) | no | always |
| a best-element concentration (per-element masteries; the generic rune shifts every element alike) | yes for the rune | (per-element keys) |
| a conversion: crit → elemental at ≤ 100 % (Dénouement) | per-source ceiling, bounded | crit, when budgeted |
| any other conversion (none in the catalog) | rounding step | always |
| an item stat gate of the pool (`x_item ⇒ outOfCombatStat ⋚ value`: distance mastery, lock, dodge in the data; port, v62) | no | always (and opaque to the pruning) |

A carrier is a count carrier as soon as ONE of its types is mixed; in the general fold every carrier offers every type,
so one mixed type makes them all count carriers (that is why an HP row hides the three gaps above).

## Certificates

The AP-cell certifier reads a count carrier like an explicit-pick carrier: one option per kept type at its full-fill
VERTEX (`perPickExact` now scales a var's coefficient by its domain's top: 1 for a pick, `slots` for a count). Those are
the options v56's picks gave the same carriers, so every bound is unchanged (locked cell for cell and world for world,
capped aux worlds included). They cover the mixed fills because each pass's valuation of a carrier, every other choice
fixed, is CONVEX in its rune counts: the normal worlds' `(400 + c)·max(0, M) + 5c·max(0, K)` at a crit step; world N's
per-source `pos(e + d) + pos(k) − (d + k + o)` (the Lagrangian relaxation that holds for any signed per-source lines,
mixed fills included); a conversion's per-source ceiling weighted `400 + c ≥ 5c`; Critical Secret's zeroed critM; and no
rune feeds the exact AP / crit / MP / range / rarity axes. A convex function on a carrier's fill simplex peaks at a
vertex, which is an option. So the certificate bounded the count optimum already — the fuzz agrees (0 under of 314) —
and it still does.

- **E8 construct / provenance replay**: the construct re-solves the certificate's items with runes and skills free in
  the production model, which now has the count carriers — a construct can only be a real build, so it is sound; it can
  now reach a mixed optimum it could not before.
- **Target-aware bound** (v52): its filters read AP / MP / CC / RANGE, which no rune feeds; locked in the same tests.
- **`MaxDamageRuneReads` Pareto pruning**: unchanged; a count carrier keeps exactly the pruned set, which the per-socket
  dominance argument covers.
- **Soft certificate** (`MaxDamageSoftCertificate`): already enumerated mixed rune counts per item; untouched.
- **`CERTIFIER_VERSION` 57** (reused, unreleased): the mirror's input changed (count vars); main's equip conditions
  already invalidate every cell cached by 1.14.2.

## Cost

Deterministic A/B (`SolverTuning`: 1 worker, seed 1, `interleaveSearch`, det 120; production pool, domination on;
runes + every choosable sub; CRA fire), v56 model (`runeMixedCarriers = false`) vs v57. "First" = det time to CP-SAT's
first solution (`stopAtFirstSolution`); "final" = the objective at det 120 (raw damage proxy).

| Request | pool | vars / cons v56 → v57 | first (det) v56 → v57 | final v56 → v57 | status |
|---|---|---|---|---|---|
| free 110 (MD110F) | 2,209 | 8,358 / 6,123 → 8,375 / 6,123 | 25.6 → 18.1 | 1,589,940 → 1,624,980 (+2.2 %) | FEASIBLE both |
| free 245 (MD245F) | 6,938 | 27,227 / 20,029 → 27,244 / 20,029 | 41.9 → 36.6 | 18,107,280 → 15,736,960 (−13.1 %) | FEASIBLE both |
| GUI default 110 (MD110) | 2,282 | 16,604 / 2,419 → 16,604 / 2,419 | 22.6 → 18.7 | raw 1,316,190 → 1,261,490 (−4.2 %) | FEASIBLE both |

The model barely grows (a pick becomes a count: same var count; +17 where a crit-swap carrier became two counts). The
first solution comes earlier in all three. The objective at the det-120 deadline is one deterministic trajectory per
row: better at free 110, worse at free 245 and on the GUI default, where every socketed carrier became a count carrier
(the HP 2000 row). None of the six runs proved its optimum at det 120 on one worker; production closes these shapes
through the certificate warm-up (unchanged bounds) on all cores. `MaxDamageFirstSolutionLatencyTest` reads det 8.95
(budget 20).

### Production path (review follow-up): 3 runs per arm, multi-worker, wall clock

`PerfBaselineE0Test` (`WakfuBestBuildFinderAlgorithm.run` + the GUI's proof chain), 60 s search, 4 cores, runes + every
choosable sub, CRA fire, proof capped at 120 s and the E8 construct at 60 s; arms alternate run by run. v56 fold =
`WAKFU_MD_MIXED_RUNES=0` (an A/B kill switch on the production path). "First" = the first CP-SAT solution (not the greedy
warm start), ms from the search start; "final" = the search's raw damage proxy; "badge" = the certificate verdict after
the search (+ the E8 construct's crowned build where it succeeds).

| Request | arm | first (ms) | final | badge |
|---|---|---|---|---|
| GUI default 110 (MD110) | v56 fold | 12,326 / 11,789 / 11,826 | 1,407,940 / 1,393,800 / 1,408,950 | within 9.1 / 10.2 / 9.0 % |
| | this change | 13,417 / 13,946 / 13,803 | 1,180,690 / 1,189,780 / 1,215,715 | within 30.1 / 29.1 / 26.4 % |
| GUI default 200 (MD200) | v56 fold | 29,497 / 30,427 / 24,876 | 3,078,780 / 2,296,725 / 3,145,030 | unavailable (cap) ×3 |
| | this change | 37,004 / 35,287 / 35,386 | 3,264,960 / 3,147,615 / 2,461,250 | unavailable (cap) ×3 |
| free 245 (MD245F) | v56 fold | 18,900 / 15,403 / 14,542 | 19,023,750 / 19,534,375 / 19,270,750 | within 6.9 / 4.1 / 5.5 %, E8 → 20,328,360 proven ×3 |
| | this change | 23,225 / 21,980 / 24,178 | 19,009,500 / 18,719,750 / 19,014,250 | within 6.9 / 8.6 / 6.9 %, E8 → 20,328,360 proven ×3 |

- **GUI default 110 regresses** in every run: −15 % at the deadline (mean 1,195,395 vs 1,403,563), and the badge goes from
  "within ~10 %" to "within ~29 %". Its rows make EVERY rune type a threshold read (HP 2000; the 0-rows on wind
  resistance and dodge with negative lines in the catalog; distance / rear under the Neutralité budget; crit with
  Dénouement), so every carrier carries seven integer counts instead of seven pick bools.
- GUI default 200: mixed (means 2,957,935 vs 2,840,178, +4.1 %, one low run per arm), no badge either way within the cap.
- Free 245: the same proven optimum in every run through the E8 construct (+3.6 s to the proof on average); the first
  solution comes ~6 s later.

### The gated encoding (tried, reverted)

`0c0070d2`: per count carrier, `x_t = slots·p_t + n_t` with the fold's single-type picks `p_t`, `Σ p_t + mixed =
selected`, `Σ n_t = slots·mixed`, and every `mixed` bool HINTED 0 (no decision strategy, no solver parameter) so the
search starts in the fold's subtree. Exact, and invisible to the certifier (it still reads `x_t`): every soundness lock
passed, the vertex lock included. Same E0 protocol, 3 runs per arm, both arms re-run (GUI default 245 added; it runs
before free 245 in each JVM, which is why free 245's badges hit the 120 s cap here):

| Request | v56 fold final (median) | gated final (median) | first solution, v56 → gated (median) | badge, v56 / gated |
|---|---|---|---|---|
| GUI default 110 | 1,407,940 / 1,410,970 / 1,416,020 (1,410,970) | 1,130,720 / 1,091,810 / 1,135,240 (1,130,720, −19.9 %) | 12.4 s → 19.9 s (+60 %) | within 8.5–9.1 % / within 35–41 % |
| GUI default 200 | 3,712,460 / 2,822,175 / 3,957,800 (3,712,460) | no CP-SAT solution in any run | 23.9 s → none | unavailable / unavailable |
| GUI default 245 | 14,324,790 / 14,491,230 / 13,096,200 (14,324,790) | 14,390,490 / 14,188,850 / 14,147,400 (14,188,850, −0.9 %) | 18.8 s → 37.3 s (+98 %) | unavailable ×3 / ×3 |
| free 245 | 18,323,125 / 18,180,625 / 18,204,375 (18,204,375) | 18,890,750 / 18,698,375 / 18,605,750 (18,698,375, +2.7 %) | 31.2 s → 32.0 s | unavailable ×3 / within 8.7 % once (E8 → 20,328,360), else unavailable |

The deterministic latency lock read det 20.0 (hinted) / 20.3 (unhinted) — over its budget of 20 — against 10.27 for the
plain counts. It misses every acceptance criterion (median ≤ 2 % worse, first solution ≤ 10 % later, badge in v56's
range), and is worse than the plain counts, so it was reverted (`f86a9b97`): the branch keeps the plain counts, which
still regress GUI default 110 by ~15 %.

## The hybrid: search on the fold, polish and verify on the count model (2026-10-12)

What ships: where the fold is inexact the search no longer runs on the count model. It runs on the v61 fold, and the count
model is used where it is cheap and where it decides correctness. `WakfuBuildSolver.mixedRuneHybrid` (called from `optimize`
for every max-damage leg, hard or soft, whose fold has a would-be count carrier):

1. **Fold stage.** `buildModel(runeMixedHybridFold = true)` builds the fold. Every carrier `mixedStats` would make a count
   carrier keeps its single-type picks and is recorded in `RuneModel.foldInexactCarriers` (`createRuneModel(materializeCountCarriers
   = false)`). The model is the one `WAKFU_MD_MIXED_RUNES=0` builds, so it has the same incumbents and the same first
   solution. It searches the leg's budget minus a polish reserve (`hybridFoldStageBudget`: 10 % of the budget, at most 6 s or 6
   deterministic units) and streams its builds as the direct solve does. A full single-type fill is also a count fill, so every
   build it shows is a real build. Its `OPTIMAL` only proves the fold's optimum, so it never stamps a result.
2. **Polish.** The count model on the fold's final items only (pool restricted to them, no domination, `forceFullPool`), cut
   at `objective ≥` the best objective so far and hinted with that build. It runs twice: first with every sublimation var
   pinned (runes and skills free; near-instant, it takes most of the gain), then with the sublimations free on what is left of
   the reserve (`hybridPolishStageBudget`). An item may still be dropped. The polished build is a build of the request worth
   at least the fold's, so the leg never ends below the fold. It is shown at once.
3. **Verify, only after a PROVEN fold optimum** with budget left: the full count model (production pool), cut at `objective
   ≥ p` (p = the polished objective) and hinted with the polished build, on what is left. Its `OPTIMAL` is the leg's only
   optimality stamp, and it is the count model's own proof. Out of time means the best build so far, unproven.
4. **Fold INFEASIBLE** proves nothing: two rows reading two rune types of one item can need a mixed fill. The count model then
   searches the rest of the budget directly, and its own INFEASIBLE is what the hard leg's soft fallback reads. A fold with no
   solution at all (UNKNOWN) ends the leg as the direct solve would.

The comparisons read the objective variable exactly (`solver.value(objective)`), never the double `objectiveValue()`: a soft
leg's penalized objective passes 2^53.

**Where it applies** (`mixedRuneHybridApplies`): max-damage, count carriers on (`WAKFU_MD_MIXED_RUNES` ≠ 0,
`SolverTuning.runeMixedCarriers`), production by default (kill switch `WAKFU_MD_MIXED_HYBRID=0` = the plain counts). A tuned
solve opts in with `SolverTuning.mixedRuneHybrid` (default false, so every existing deterministic test keeps the plain count
model, the E8 construct's restricted re-solve included). Excluded: the E8 fallback's `maxDamageRawFloor` feasibility search,
`stopAtFirstSolution`, the name-hinted / bucket-interval measurement seams. A fold with no inexact carrier IS the count model:
it runs the direct solve, and its OPTIMAL is trusted as before. Test seam `SolverTuning.mixedRuneHybridVerify = false` skips
step 3. Diagnostics: `WAKFU_MD_HYBRID_DEBUG=1` prints one `MD_HYBRID` stderr line per hybrid leg (each stage's status,
objective, deterministic time and wall time).

### Soundness

- **Builds.** Every build the leg emits is a solution of an exact model. The fold's solutions are count solutions with
  full single-type fills. The polish and verify solutions come from count models: on a restricted pool, every item is still a
  pool item and every equip condition and gate is still posted.
- **Badges.** `isOptimal` (and the leg's `OPTIMAL` outcome) comes only from the verify stage, an `OPTIMAL` of the full count
  model. It is still gated on `!needsItemPrefilter`, like everywhere. The fold's `OPTIMAL` and `INFEASIBLE` are never
  forwarded: `MaxDamageSearch` (phase-1 `isOptimal`, the boss probes' `proved`, `proveOptimality`'s `result.isOptimal`
  shortcut, `refineSoftLegProof`) and `optimizeHardThenSoft`'s fallback decision only ever see the count model's verdicts.
  Relax-then-check is most-masteries only and is untouched.
- **Certificate.** The post-search certificate, the E10 warm-up early stop and the E8 construct are unchanged. The certifier
  mirrors the COUNT model (vertex argument, CERTIFIER_VERSION 62), so its bound covers every build the leg can return. The
  construct crowns a build only by reaching that bound, and runs on the count model (tuned path, no opt-in). The soft
  certificate's no-condition oracle (`timedMaxDamageProfileForTest`) builds the count model by default.
- **Versions.** No certifier change: `CERTIFIER_VERSION` 62 stays. The engine's results change (the polish), so the unreleased
  `ENGINE_RESULTS_VERSION` 7 history entry was extended (AGENTS.md §4).

### Deterministic A/B (1 worker, seed 1, interleave, det 120; production pool, domination on; greedy warm start on)

`MixedRuneHybridAbTest` (manual, `WAKFU_HYBRID_AB=1`) runs `MaxDamageSearch.optimizeHardThenSoft` (the production leg
pair) on the E0 requests: CRA, runes and every choosable sublimation, GUI-default rows, or free with distance mastery 1. Arms:
fold = `runeMixedCarriers = false`, counts = the plain count model, hybrid = `mixedRuneHybrid = true`. "Final" = the leg's last
build: the raw damage proxy, and in brackets the leg it came from. "First" = deterministic time to CP-SAT's first solution
on the hard leg (`stopAtFirstSolution`, no warm start). The hybrid's first solution is the fold's, because its fold stage is
the same model. Two JVM runs of MD110 (all arms) and MD245 gave identical numbers.

| Request | fold final | counts final | hybrid final (fold stage → polished) | hybrid vs fold | first, fold / counts (det) |
|---|---|---|---|---|---|
| GUI default 110 (MD110) | 1,173,695 (hard) | 1,233,860 (hard) | 1,188,975 (1,173,695 → 1,188,975) | +1.3 % | 22.7 / 14.1 |
| GUI default 200 (MD200) | 9,137,400 (soft: the hard leg found nothing) | 4,848,060 (hard) | 9,139,340 (soft, as the fold) | +0.02 % | 29.1 / 33.2 |
| GUI default 245 (MD245) | 15,021,210 (hard) | 9,215,765 (hard) | 15,336,570 (14,567,880 → 15,336,570) | +2.1 % | 33.9 / 29.7 |
| free 245 (MD245F) | 18,791,000 | 19,349,125 | 19,247,000 (18,791,000 → 19,247,000) | +2.4 % | 38.0 / 31.2 |

- The hybrid ends at or above the fold on all four. Its fold stage gives up 6 deterministic units to the reserve. At MD245
  that cost a late fold improvement (14.57 M at det 114, 15.02 M at 120), which the polish more than recovered (+5.3 %).
- On ONE worker the plain counts beat the fold at MD110 (+5.1 %) and MD245F (+3.0 %), and lose at MD245 (−39 %). On
  MD200 the counts find a hard-leg build and the fold does not within det 120 (the hard leg then falls back to the soft
  leg). The production multi-worker E0 numbers above point the other way at MD110 (counts −15 %). The 1-worker protocol is
  load-independent but is not a proxy for the 3-worker portfolio. The definitive A/B is the wall-clock E0 run.
- Polish cost: pinned 0.02–0.1 det (≤ 0.7 s wall); free 4–6 det (proven at MD245 / MD245F, capped at MD110 / MD200).

Early variants, measured and rejected (MD110 unless noted, det budgets as given):
- **Polish budget** (det 30, fold stage at det 28.5 = 867,140): pinned sublimations proved 982,695 (+13.3 %) in 0.02 det,
  free sublimations proved 1,047,635 (+20.8 %) in 3.9 det; a free polish capped at 1.5 det stopped unproven (937,810 from
  a fold stage of 780,235, in another JVM run). Hence pinned first, then free on the rest of the reserve.
- **Two-stage time split** (fold 70 %, polish, then the count model hinted with the polished build for the remaining
  ~30 %): 1,137,405, against 1,188,975 for the shipped split. The count model improved the polished build by 1.6 % in 31 det.
  The fold gained 10.6 % between det 84 and 114.
- **Count stage after an unproven fold** (the leftover reserve, hinted, ≥ 1 det): MD245 built the full count model (4.8 s)
  and found nothing in 2 det. Dropped: only a proven fold optimum runs the count model on the full pool. (Re-tried on the
  count classes after the integration, with the same result: "Integration" below.)

E0 sanity (production path, 4 cores, 60 s, one run per arm on a loaded machine, not a measurement): MD110 hybrid 1,447,330
(fold stage 1,441,270 at 54 s, polish 5.9 s), badge "within 3.59 %", first CP-SAT solution 12.7 s. The fold arm in the
same session: 1,430,160, "within 4.84 %", 12.5 s. Baseline fold median on this PC: 1,440,260 (within 3.7–5.1 %).

Not done: polishing the INTERMEDIATE fold incumbents during the search (only the final one is polished, so the builds streamed
mid-search are fold builds). It would need a second solver beside CP-SAT's workers, and the sublimation pinning read back
from a streamed build.

## Count classes (2026-10-12): the exact count model made fast

`perf/mixed-counts-speed`, on top of the v62 port. Deterministic harness `MixedRuneCountsPerfHarnessTest` (manual,
`WAKFU_MIXED_PERF=shape|run`): 1 worker, interleave, a fixed seed, a det budget; production pool (domination on), the
production experiment config (`MaxDamageExperimentConfig.DEFAULT` — the bare `SolverTuning()` default is NOT production),
presolve 3 as in production, the greedy warm start hinted as in production; the GUI-default rows on the HARD leg (what
production runs first), the free request as a plain solve. Search logs through `SolverTuning.searchLogSink`. Arms: **fold**
(`runeMixedCarriers = false`, the v61 all-or-nothing fill — inexact), **counts** (the v62 per-carrier counts),
**classes** (this change).

### Diagnosis: the per-carrier counts copy one decision onto thousands of interchangeable carriers

`WAKFU_MIXED_PERF=shape` on the GUI-default requests: EVERY rune type except the generic elemental one is in `mixedStats`
(HP: the 2000 row; wind resistance and dodge: 0-rows with negative lines in the pool; distance and rear: the Neutralité
budget, plus the distance-mastery gates from level 200; crit mastery: its budget / Dénouement), so every carrier offering
two types — all of them — is a count carrier: 2,004 carriers × 7 types at level 110, 6,727 × 7 at 245.

- The LP relaxations of the fold and the counts are the SAME polytope (`x_t = slots·p_t`, `p ∈ [0, 1]` ⇔ `x ∈ [0, slots]`)
  and the variable count is the same (16,566 at level 110: a pick became a count). What differs is structure: the fold's
  `Σ picks = x_c` presolves into 2,024 exactly-one constraints (clauses: SAT propagation), the counts' `Σ n_t = slots·x_c`
  stay 2,063 linear constraints over 41,710 terms. At det 60 (MD110, one trajectory each) the counts searched 6,950 branches
  against the fold's 14,199–35,105, with 28× fewer boolean propagations, and spent more of the budget in the LP (41k LP
  iterations against 14k in the bare-config pair).
- Presolve is most of the first solution: ~12 s wall / det 10–18 for both per-carrier models at level 110, det 31–48 at
  245 — and the det-60 budget OVERSHOOTS to det 63–72 there, presolve not being interruptible. The first CP-SAT solution
  is the greedy hint's completion in both.
- Every later improvement comes from LNS (`rnd_cst_lns`, `graph_arc_lns`, `graph_dec_lns`) and `quick_restart_no_lp`, in
  both arms; the dual bound stays at 4–6 M against a ~1.2–1.4 M incumbent. It is a primal (search) problem, not a bound
  problem.
- The mechanism: a build wears ~12 carriers, but the counts give each of the thousands of candidate carriers its own
  copy of the rune decision. The rune readers never tell two carriers apart when they have the same item type, socket count
  and per-socket values: the 2,004 carriers of MD110 fall into **60 classes** (110 at level 245). An LNS move that swaps a
  helmet for another one must also move its seven counts — in the fold that is one pick, with counts it is a vector the
  neighbourhood rarely repairs.

### The fix: one count per type per CLASS (exact, search model only)

`RuneCountClass` (SolverModel.kt), built by `createRuneModel(aggregateCountClasses = true)`: count carriers sharing their
item type, socket count and rune choices (same rune, same per-socket value — and therefore the same choice gates) hold ONE
count per type for the class, `m_t ∈ 0..slots·maxWorn` (two rings, else one), with `Σ_t m_t = slots · Σ_{c ∈ class} x_c`.

- **Exact.** Every reader of a rune reads it through `StatBuilder.baseTermsFor` — `Σ_c Σ_t coeff_t · n_{c,t}` — and a
  class's carriers share `coeff_t`: the stat totals depend on the per-carrier counts only through the class sums. Per-carrier
  → class: summing gives the class constraint and the same totals; a choice gate `n_{c,t} ≤ slots·Σ subVar` implies
  `m_t ≤ hi·Σ subVar`. Class → per-carrier: `N` worn carriers and `Σ_t m_t = slots·N` split into N fills of exactly `slots`
  runes (`RuneCountClass.distribute`, used by `solutionToBuild`), and a type with `m_t > 0` has a taken gate sub. Item
  stats, sockets and sublimation carriers ride the equip vars, untouched; forced runes keep the per-stat count model (no
  fold, no class). Same optimum, same objective per build.
- **Domains.** `reachableSumDomain` bounds every objective-chain var per item slot; a class var's share rides each of its
  carriers at `0..slots`, exactly like that carrier's own count, so the declared domains are IDENTICAL to the per-carrier
  model's (locked on the GUI-default and free requests at levels 110 and 245, both legs). An under-estimate would cut the
  optimum.
- **Certificates: untouched.** The AP-cell certifier mirrors per-carrier vars, so every model that feeds a certificate
  (`certifyLedgerForTest`, `certifyAllApForTest`, the explain, the fast-only audit, the `certifierCellCap` experiment) is
  built WITHOUT classes, and `certifyMaxPerHitAtApPass` bails (sound) on a model with classes should one ever reach it. The
  certifier's input is byte-identical: no `CERTIFIER_VERSION` change, the vertex lock holds as is. The E8 construct re-solves
  through `optimize` (classes on): it only ever sees real builds.
- Model size (GUI default, production pool): level 110 16,566 vars / 2,415 constraints → 2,958 / 471 (420 class counts);
  200 43,612 / 6,016 → 6,855 / 765; 245 54,681 / 7,464 → 8,362 / 847; free 245 31,049 / 16,087 → 7,843 / 949.
- Seams: `SolverTuning.runeCountClasses` (default true), kill switch `WAKFU_MD_RUNE_CLASSES=0` (per-carrier counts),
  `maxDamageSolveForTest(runeCountClasses)`.

### Deterministic A/B (seed 1)

| Request | first solution det: fold / counts / classes | det 60: fold / counts / classes | det 120: fold / counts / classes |
|---|---|---|---|
| GUI default 110 (MD110) | 17.69 / 10.04 / **1.59** | 1,239,270 / 1,183,245 / **1,419,050** | 1,244,320 / 1,183,245 / **1,425,110** |
| GUI default 200 (MD200) | none by det 402 / 85.86 / **27.58** | none / none (det 71) / **5,664,800** | none (det 223) / 5,061,270 / **7,391,400** |
| GUI default 245 (MD245) | 48.46 / 31.04 / **10.69** | 8,532,240 (det 72) / 9,927,270 (det 63) / **15,251,160** | 15,461,400 / **15,577,470** / 15,411,375 |
| free 245 (MD245F) | 17.73 / 17.52 / **8.08** | 13,406,120 (det 65) / 16,070,220 (det 71) / **19,396,625** | 19,358,625 / 19,382,375 / **19,501,125** |

(A "(det N)" is a budget the per-carrier model's presolve overshot.) The classes reach their first solution 2–11× sooner
and lead at det 60 everywhere (+14.5 % / from nothing / +79 % / +45 % over the fold); at det 120 they lead on three of four,
and the fold catches up on GUI default 245 (−0.3 %, one trajectory). Multi-seed:

| Request | seeds | fold (per seed) | classes (per seed) | median fold → classes |
|---|---|---|---|---|
| MD110, det 60 | 1–3 | 1,239,270 / 1,202,345 / 1,185,740 | 1,419,050 / 1,324,110 / 1,427,130 | 1,202,345 → 1,419,050 (+18.0 %) |
| MD110, det 120 | 1–3 | 1,244,320 / 1,273,610 / 1,224,120 | 1,425,110 / 1,348,350 / 1,427,130 | 1,244,320 → 1,425,110 (+14.5 %) |
| MD200, det 60 | 1–3 | none ×3 | 5,664,800 / 4,052,745 / 7,265,610 | none → 5,664,800 |
| MD200, det 120 | 1–3 | none / none / 4,856,175 | 7,391,400 / 7,754,625 / 7,539,480 | none → 7,539,480 |
| MD245, det 60 | 1–3 | 8,532,240 ×3 (presolve overshoots to det 72–90; the hint's completion) | 15,251,160 / 14,094,840 / 14,576,640 | 8,532,240 → 14,576,640 (+70.8 %) |
| MD245, det 120 | 1–7 | 15,461,400 / 13,941,540 / 14,885,430 / 8,536,620 / 13,115,910 / 13,895,550 / 14,784,690 | 15,411,375 / 14,574,450 / 15,608,130 / 14,964,270 / 14,896,380 / 14,835,060 / 16,166,625 | 13,941,540 → 14,964,270 (+7.3 %; 6 of 7 seeds) |
| MD245F, det 60 | 1–3 | 13,406,120 / 18,392,000 / 19,254,125 | 19,396,625 / 18,634,250 / 19,178,125 | 18,392,000 → 19,178,125 (+4.3 %) |
| MD245F, det 120 | 1–7 | 19,358,625 / 19,517,750 / 19,296,875 / 19,263,625 / 19,460,750 / 19,249,375 / 19,877,670 | 19,501,125 / 19,073,625 / 19,242,250 / 19,401,375 / 19,688,750 / 20,226,270 / 19,458,375 | 19,358,625 → 19,458,375 (+0.5 %; 4 of 7) |

On this deterministic one-worker protocol the classes' median is ahead of the (inexact) fold's at every budget on all four
requests, and their first solution comes sooner everywhere — but see the next section: production's multi-worker portfolio
ranks them differently. Free 245 is the closest: its optimum (20,328,360, proven by the E8 construct) is within ~5 % of both,
and the best seed of all (20,226,270) is a classes run. The dual bound is also tighter with the classes (GUI default 110:
3.08 M against the fold's 4.16–4.41 M at det 60–120).

### Production's portfolio ranks them differently (3 workers, wall clock)

The interleaved one-worker search is NOT production's portfolio. With 3 workers and no interleave (the GUI's 4-core search:
`default_lp` + `fixed` full workers, `fj` first solution, then feasibility pump, RINS/RENS and the graph / random LNS
interleaved on the helper threads), 60 s wall, `-XX:ActiveProcessorCount=4`, the same harness (`WAKFU_MIXED_WORKERS=3
WAKFU_MIXED_INTERLEAVE=0`, budget `w60`, one solve at a time; wall clock, so on a shared PC):

| Request | fold | counts | classes | median fold / counts / classes |
|---|---|---|---|---|
| GUI default 110 (seeds 1–8; counts 3–5) | 1,440,260 / 1,443,290 / 1,282,380 / 1,427,130 / 1,447,330 / 1,442,280 / 1,446,320 / 1,447,330 | 1,330,315 / 1,091,565 / 1,244,365 | 1,418,175 / 1,331,100 / 1,340,670 / 1,318,920 / 1,411,490 / 1,334,135 / 1,301,520 / 1,329,360 | 1,442,785 / 1,244,365 / 1,332,618 (−7.6 % vs the fold, +7.1 % vs the counts) |
| free 245 (seeds 1–5; counts 3–5) | 19,648,375 / 19,745,750 / 19,460,750 / 19,078,375 / 19,389,500 | 19,244,625 / 18,843,250 / 19,273,125 | 18,437,125 / 19,517,750 / 19,432,250 / 19,881,125 / 19,097,375 | 19,460,750 / 19,244,625 / 19,432,250 (−0.1 %) |

`PerfBaselineE0Test` (the production path: hard leg, proof chain; 60 s, 4 cores, MD110 then MD245F per JVM, arms
alternating, 2 runs): fold MD110 1,427,130 / 1,427,130 (first CP-SAT solution 13.0 / 12.1 s, badge within 5.1 %),
classes 1,340,670 / 1,273,030 (first 4.6 / 4.1 s, within 11.8 / 17.8 %); MD245F fold 19,296,875 / 19,353,875 (first
19.3 / 19.6 s), classes 17,147,500 / 17,301,875 (first 15.6 s ×2), no badge either way. (For reference, the plain counts'
E0 runs of PR #251 read MD110 1.18–1.22 M, within 26–30 %.)

So on production's portfolio the classes recover about half of the GUI-default-110 regression (counts −14 % → classes −8 %
against the inexact fold; first solution ~3× sooner) and reach parity on free 245 in the harness (the two E0 runs of free
245 were low; too few to conclude). The fold's 3-worker search converges on GUI default 110 almost every time (1.44 M in 7
of 8 runs): its whole-item fills are a much smaller space for the pump / RINS / LNS neighbourhoods. With 8 workers the
classes reach the same 1,447,330 in 38 s (one 300 s run) — the GUI-default-110 optimum needs no mixed fill, so the exact
model's extra space only costs search there.

Tried on top of the classes and dropped:
- **Deviation form** (keep the fold's per-carrier picks and give each class signed deviations `d_t`, `Σ_t d_t = 0`,
  `slots·Σ_c p_{c,t} + d_t ≥ 0` — exact, same domains): det one-worker it is the fold again (presolve-bound: first solution
  det 13.4 / 73.8 / 42.5 / 26.4 on the four requests, det 120 1,183,245 / 4,223,380 / 15,483,300 / 19,536,750); 3 workers it
  is the most erratic arm (GUI default 110: 1,420,060 / 1,064,880 / 1,079,150 / 1,409,960 / 1,208,075; free 245 median
  19,330,125). Reverted.
- **Linearization level 1** for the class model (3 workers, GUI default 110, seeds 6–8): 1,321,720 / 1,403,850 / 1,317,180
  against 1,334,135 / 1,301,520 / 1,329,360 — no change.

### The other levers (2026-10-12)

- **Fewer threshold reads (static analysis): no gain on these requests.** Every type the shape run lists in `mixedStats`
  can bind: HP 2000 is above level 110's base 1,150 and, at 245 (base 2,500), items with negative HP lines survive
  domination; the wind-resistance and dodge 0-rows have negative lines in the pool; distance / rear have the Neutralité
  budget (and distance-mastery gates from 200); crit has its budget / Dénouement. Even dropping one would leave every
  carrier a count carrier (one mixed type beside another is enough).
- **Fewer count vars per carrier (merge the non-threshold types): nothing to merge** — the generic elemental rune is the
  only non-mixed type on the GUI default. The classes make the count-var count small anyway (420 at level 110).
- **Dominance cuts** (e.g. never overshoot a `≥` row by a whole rune of a type nothing else reads): not tried. An exact
  version needs, per type, every reader of its stat (HP feeds `%HP` and the survivability floor; dodge is read by Furie's
  `dodge < level` and by gates; resistances by the random-roll folds), and the measured gap it would close is gone.
- **Search guidance** (decision strategy, rune hints, CP-SAT parameters): not needed after the classes; the per-carrier
  models' main cost was presolve and LP size, which no parameter fixes.
- **Next, if wanted:** aggregate the fold's PICK carriers the same way (`p_{k,t} ∈ 0..maxWorn`, `Σ_t p = Σ x_c`, also
  exact) — requests whose carriers stay picks (no threshold read) would get the same model shrink; the certificates would
  again read the per-carrier model.

## Integration (2026-10-12): the hybrid's count stages on the classes

`perf/mixed-runes-integrated` merges, in this order, `perf/certificate-245` (clean), `perf/mixed-hybrid` (clean) and
`perf/mixed-counts-speed` (conflicts in RuneModelBuilder.kt, SolverModel.kt, WakfuBuildSolver.kt, AGENTS.md and the docs).
Both features stay on together:

- **The fold stage is the plain fold.** `optimize` builds it with `runeMixedHybridFold = true` and `runeCountClasses =
  false`. A fold-stage model has no count carrier (`createRuneModel(materializeCountCarriers = false)` leaves every would-be
  count carrier on its picks and records it in `foldInexactCarriers`), so there is nothing to aggregate anyway; the explicit
  `false` keeps it that way. Its incumbents and first solution are the fold's.
- **Every count stage uses the classes.** `mixedRuneHybrid`'s `countModel` (polish with the sublimations pinned, polish
  free, verify, the fold-INFEASIBLE fallback) passes `runeCountClasses = SolverTuning.runeCountClasses` (production: on;
  `WAKFU_MD_RUNE_CLASSES=0` turns it off inside `buildModel`). `hintPoint` hints a class var with the sockets of its type over
  the class's worn carriers (an unworn carrier holds none, so the hint meets `Σ_t m_t = slots · Σ_c x_c`).
- **Classes × pinning.** The polish restricts the pool to the fold build's items: its classes are those carriers (one class
  per item, the two rings one class of two when they match), and an item the polish drops simply leaves its class sum. The
  verify and fallback stages see the full pool, where a class spans many unworn carriers. Either way `Σ_t m_t = slots ·
  Σ_c x_c` counts only the worn ones, and `solutionToBuild` splits a class over its WORN carriers only
  (`RuneCountClass.distribute(equipped, …)`); the new lock puts the worn helmet second in its class to catch a split over
  every carrier.
- **Hybrid off** (`WAKFU_MD_MIXED_HYBRID=0`): the direct count search uses the classes (branch 3's behaviour). **Mixed runes
  off** (`WAKFU_MD_MIXED_RUNES=0`): every stage is the fold; there is no count carrier, no class, no hybrid.
- **Certificates** keep per-carrier counts: every model built for one is built without classes, and the certifier bails on a
  model with classes. The **E8 construct** re-solves on the tuned path (hybrid off), so as on branch 3 it searches the exact
  count model with classes. It only returns solutions of that model, which are real builds.
- No certifier change: `CERTIFIER_VERSION` 62 / `ENGINE_RESULTS_VERSION` 7 (unreleased; history entry extended).

Arms for an A/B (production path, env vars read at JVM start):

| Arm | `WAKFU_MD_MIXED_RUNES` | `WAKFU_MD_MIXED_HYBRID` | `WAKFU_MD_RUNE_CLASSES` |
|---|---|---|---|
| fold (inexact, ≤ v61) | `0` | (ignored) | (ignored) |
| classes only (count model searched directly) | unset | `0` | unset |
| hybrid + classes (default) | unset | unset | unset |
| hybrid, per-carrier count stages | unset | unset | `0` |

### Deterministic A/B (1 worker, seed 1, interleave; production pool, `MaxDamageExperimentConfig.DEFAULT`, presolve 3, greedy hint)

`MixedRuneCountsPerfHarnessTest` (`WAKFU_MIXED_PERF=run`, arms `fold,classes,hybrid,hybridPerCarrier`, PAR 6), one leg as
production runs it first: the HARD leg for the GUI-default rows, the plain solve for free 245. Objective = the leg's final
raw proxy; "none" = no build on that leg (production then runs the soft leg). For the hybrid: fold stage → polished.

| Request | det | fold | classes only | hybrid + classes | hybrid per-carrier |
|---|---|---|---|---|---|
| GUI default 110 (MD110) | 60 | 1,143,135 | **1,220,055** | 1,148,865 (1,135,495 → 1,148,865) | 1,148,865 |
| | 120 | 1,167,965 | **1,350,370** | 1,174,650 (1,167,965 → 1,174,650) | 1,174,650 |
| GUI default 200 (MD200) | 60 | none (det 79) | **5,818,935** | none (fold stage none, det 73) | none |
| | 120 | none (det 139) | **5,907,075** | none (det 133) | none |
| GUI default 245 (MD245) | 60 | 8,722,770 (det 79) | **14,075,130** | 14,007,240 (8,722,770 → 14,007,240; det 73) | 14,007,240 |
| | 120 | 13,763,475 | **15,137,280** | 13,585,025 (12,562,050 → 13,585,025) | 13,585,025 |
| free 245 (MD245F) | 60 | 13,406,120 (det 65) | 19,396,625 | **19,458,375** (13,406,120 → 19,458,375; det 65) | 19,458,375 |
| | 120 | 19,358,625 | **19,501,125** | 19,451,250 (19,358,625 → 19,451,250) | 19,451,250 |

("det N": presolve overshot the budget.) Two repeats of MD110 fold / classes at det 60 in one JVM, and the same jobs in a
second JVM, gave identical numbers. Seeds 2 and 3, same binary (per seed 1 / 2 / 3, then the median):

| Request | det | fold | classes only | hybrid + classes | median fold / classes / hybrid |
|---|---|---|---|---|---|
| MD110 | 60 | 1,143,135 / 1,191,840 / 1,135,495 | 1,220,055 / 1,254,870 / 1,377,640 | 1,148,865 / 1,182,290 / 1,163,190 | 1,143,135 / **1,254,870** / 1,163,190 |
| | 120 | 1,167,965 / 1,217,625 / 1,135,495 | 1,350,370 / 1,254,870 / 1,380,670 | 1,174,650 / 1,227,175 / 1,163,190 | 1,167,965 / **1,350,370** / 1,174,650 |
| MD200 | 60 | none ×3 | 5,818,935 / 6,537,615 / 5,645,090 | none ×3 | none / **5,818,935** / none |
| | 120 | none / none / 4,772,400 | 5,907,075 / 7,415,970 / 6,463,160 | none / none / 5,005,200 | none / **6,463,160** / none |
| MD245 | 60 | 8,722,770 / 8,722,770 / 8,597,940 | 14,075,130 / 14,348,880 / 14,802,210 | 14,007,240 / 13,974,390 / 15,319,050 | 8,722,770 / **14,348,880** / 14,007,240 |
| | 120 | 13,763,475 / 12,077,850 / 8,597,940 | 15,137,280 / 15,397,125 / 15,950,500 | 13,585,025 / 15,319,050 / 15,102,240 | 12,077,850 / **15,397,125** / 15,102,240 |
| MD245F | 60 | 13,406,120 / 18,392,000 / 19,254,125 | 19,396,625 / 18,634,250 / 19,178,125 | 19,458,375 / 19,458,375 / 19,479,750 | 18,392,000 / 19,178,125 / **19,458,375** |
| | 120 | 19,358,625 / 19,517,750 / 19,296,875 | 19,501,125 / 19,073,625 / 19,242,250 | 19,451,250 / 19,517,750 / 19,479,750 | 19,358,625 / 19,242,250 / **19,479,750** |

- On this one-worker protocol **classes only leads on the three GUI-default requests** (median +9.8 / +15.6 % over the
  fold at MD110, a hard-leg build at MD200 where the fold has none, +64.5 / +27.5 % at MD245). **The hybrid leads on free
  245** (+5.8 / +0.6 % over the fold, +1.5 / +1.2 % over the classes). The hybrid ends at or above the fold in 23 of 24
  runs; the exception is MD245 det 120 seed 1 (−1.3 %, where the 6-det reserve cost the fold a late improvement). Its polish is large where the fold stage
  stops at the greedy hint's completion (MD245 det 60: +60.6 %, MD245F det 60: +45.1 %), small otherwise (+0.6 to +8.1 %).
- **MD200, hard leg: the fold finds no build** (none by det 402 on branch 3), so neither does the hybrid's fold stage, and
  the polish has nothing to start from. The classes find one by det 28 (branch 3). In production the leg pair then runs the
  soft leg, and on 4 cores the fold's first MD200 hard solution came at 25–30 s ("Production path" table above).
- **Classes in the hybrid change nothing measurable here.** The polish's restricted pool has one class per item, so its
  model and trajectory are identical with or without classes. The verify stage never ran, because no fold stage proved
  its optimum within det 120, and no fold was INFEASIBLE. The classes pay off in the hybrid only where those stages run:
  small or proven requests, and the INFEASIBLE fallback. The new lock exercises all of them.
- **Tried, dropped: a count stage on the leftover after an unproven fold**, now that the count model is cheap (classes,
  hinted with the polished build, cut ≥ it). It ran on MD110 det 60 (0.97 det), MD245 det 120 (2.04) and MD245F det 120
  (5.99, where the polish proved in 0.04). It ended UNKNOWN every time, with no better build, and cost 2–10 s of wall.
- **Not done: polishing the fold's intermediate incumbents during the search (optional item).** In the deterministic
  protocol it cannot change the fold-stage objective, since the fold never sees the polished builds. So the only measurable
  effect would be on the streamed builds. In production it needs a second CP-SAT solve beside the portfolio, and production
  already leaves exactly one core to the host and GUI (`searchWorkers = cores − 1`), so it would steal a core either way.
  It would also need the sublimation pinning read back from a streamed build. The final-build polish costs 5–6 det / 13–15 s
  of wall clock at MD110 on one worker. Concurrent polishes would compete with the portfolio for most of the search. Left
  for the wall-clock A/B to motivate.
- The deterministic protocol is not production's portfolio: branch 3 measured the fold ahead on 3 workers at MD110
  (wall-clock tables above). The definitive comparison is the wall-clock E0 A/B of the four arms above.

**The numbers depend on the binary, not only on the model.** The same request and arm gave MD110 fold det 60 = 1,143,135 on
this branch, 1,169,580 on `perf/mixed-counts-speed` rebuilt today and 1,239,270 in that branch's table above. Classes gave
1,220,055 / 1,401,940 / 1,419,050. MD245F reproduced exactly (13,406,120 / 19,396,625). Each binary is reproducible run
to run. An unrelated code change (here the certificate merge) changes the model's construction order and so the
trajectory. The most likely cause is the JVM's identity hash order in hash collections of model objects. Compare arms
within one binary, over several seeds.

## Tests

`MixedRuneSecondaryBudgetTest`:
- the minimal repro: v56 (`runeMixedCarriers = false`) proves 1,320,570, the count model and the fixed fold 1,328,235;
  the exported build takes Neutralité III and mixes types on an item; every AP cell's exact / fast / tier-1.5 bound ≥
  the count model pinned to that cell;
- the seeded lock (9 seeds × free / forced / rowed hard leg): the fold proves the count optimum, the forced-exact ledger
  bounds it, and v56 diverges on ≥ 10 cases (its sensitivity);
- the vertex lock: with count carriers the certificate equals v56's cell for cell, every tier and the capped aux worlds;
- the review's three repros (a)–(c) and a seeded general-fold lock (rows, floors, aggregate rows at 0 and positive, the
  survivability floor; no sublimation, both legs): fixed fold = count model everywhere. Removing (a) fails both; removing
  (b) or (c) fails the repro test (the seeded lock alone catches only (a));
- the HP-row case: v56 117,000 vs 117,500 on the hard leg (and the soft one), the target-aware ledger ≥ it;
- the `mixedStats` rule;
- the hybrid (2026-10-12), through the production leg (`WakfuBuildSolver.optimize` / `optimizeHardThenSoft` with
  `SolverTuning.mixedRuneHybrid`): on the repro and the HP-row case (both legs), the fold alone proves 1,382,985 / 117,000,
  and the hybrid ends on 1,408,170 / 117,500, proven by its verify stage. With the verify stage off, the fold's OPTIMAL stays
  unproven and the polish still reaches the count optimum on the HP row. On 41 seeded, general-fold (both legs) and gate
  pools, the hybrid ends on the count model's optimum, proven (unbadged only on the prefiltered positive "all resistances"
  rows); the fold alone proves less on 13 of them. A fold proven INFEASIBLE (lock and dodge rows on one 4-socket helmet,
  each needing part of it) hands the hard leg to the count model, which meets both. Mutations checked: stamping the
  fold's OPTIMAL fails the first test, and trusting the fold's INFEASIBLE fails the last.
- the hybrid × classes (integration, 2026-10-12): a stage observer (`SolverTuning.mixedRuneHybridStageObserverForTest`)
  records each hybrid stage's rune model on the repro, the HP row (both legs, each also with interchangeable TWINS: a weaker
  helmet listed first, a second ring of the same class) and the lock/dodge helmet whose fold is INFEASIBLE. The fold stage
  has no count carrier and no class. Every count stage (polish pinned / free, verify, the fallback) has classes covering
  every count carrier and no per-carrier count var. The leg ends on the per-stat count optimum, proven, with every socket of
  every worn carrier filled. The verify stage of the twin pools holds classes of two carriers. With the classes off, the same
  stages are per-carrier and reach the same optimum. Mutations checked: count stages built without classes fail it; a class
  split over every carrier instead of the worn ones fails it (the worn helmet's sockets come out empty).

`RuneCountClassTest` (the count classes):
- seeded pools of interchangeable carriers (two helmets, three rings — two worn — of one level and socket count) under the
  three threshold shapes that make count carriers (an HP row + dodge floor, an "all resistances" floor with negative lines,
  the Neutralité budget with its choice gates), both legs: classes = per-carrier counts = the per-stat count model, all
  proven; every shape has a class of ≥ 2 carriers. Mutation-checked: a class top of ONE ring fails it (and the next test);
- the production solve path (`optimize`): a build from the class model fills every socket of every worn carrier, wears two
  rings of one class, and reaches the per-carrier optimum;
- the declared reachable domains of every tracked objective-chain var are IDENTICAL with and without classes on the
  GUI-default and free requests (levels 110 / 245, production pool, both legs).

`MaxDamageFirstSolutionLatencyTest` reads det 2.48 with the classes (10.30 with the per-carrier counts); its budget went
from 20 to 6.

Without the fix (count carriers disabled in production) the repro and the seeded lock fail (1,320,570 vs 1,328,235;
seed 9: 2,212,350 vs 2,275,560). Without the certifier's vertex scaling the vertex lock fails (the repro's capped world
drops from 1,369,845 to 1,250,490 at AP 7).
