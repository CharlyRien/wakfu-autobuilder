# Mixed runes under a secondary budget

2026-10-06 · Wakfu data 1.93.1.62 · `CERTIFIER_VERSION` 57 / `ENGINE_RESULTS_VERSION` 2 — both unreleased when this landed
(1.14.2 shipped 56 and no engine-results version), so this change extends their history entries instead of bumping
them (AGENTS.md §4: at most one bump per release). "v56 fold" below = the all-or-nothing fold this replaces.

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
- the `mixedStats` rule.

Without the fix (count carriers disabled in production) the repro and the seeded lock fail (1,320,570 vs 1,328,235;
seed 9: 2,212,350 vs 2,275,560). Without the certifier's vertex scaling the vertex lock fails (the repro's capped world
drops from 1,369,845 to 1,250,490 at AP 7).
