# Elemental rune booking in the max-damage collapse

2026-10-03 / 04 · Wakfu data 1.93.1.62 · `CERTIFIER_VERSION` 53 → 54 (52 → 53 before the rebase onto #225).

> **Update (2026-10-04, `CERTIFIER_VERSION` 56).** The Neutralité family's condition holds EACH secondary mastery ≤ 0
> on its own — it is NOT their sum (the game's criterion is an `and` of six per-stat atoms, State 67 → StaticEffect
> 68). The pruning below was derived under the sum reading; its reads are now one bound per secondary, see
> [the per-stat section](#per-stat-secondary-cap-2026-10-04-certifier_version-56). The rest of this page describes the
> v54 / v55 state.
PR #226 (the fix) is superseded by a rebase onto `main` @ `d4f69bbb` (#227) that adds the search-cost mitigation
below (per-carrier Pareto pruning + choice gates) and two review findings.

## The bug

The max-damage rune collapse stored each carrier's best M-feeding rune under the scenario's range-mastery
key. `StatBuilder.baseTermsFor` uses that key as the actual stat, so an elemental rune contributed to distance
or melee mastery. The damage sum was unchanged, but the Neutralité family's secondary-mastery condition
charged elemental runes against its budget: no Neutralité-family sub could be active on a free max-damage build
carrying runes, so a wrong "Optimal proven" badge was possible. A damage-neutral HP=0 row enabled the general rune
fold and restored the real optimum. On the real catalog, CRA 80 fire / melee / face proved 796,125 on `main` while
797,775 exists with Neutralité III + Inflexibilité II + Ambition III.

## The fix (#226)

The collapsed M choice now uses the rune's own characteristic. A single choice still substitutes the equip
variable; the critical-mastery alternative still uses one swap bool, with `extraTerms` suppressing the default
under that same characteristic and `suppressedBy` suppressing it in the exported build. The certifier accepts
the actual scenario-mastery keys and removes the actual default contribution when splitting the crit option.

The helmet fixture also exposed a smaller gap after fixing the key: 2,194,930 versus 2,198,020. A secondary
default is not a safe substitute for a lower-valued elemental rune when the secondary read is capped: the
elemental choice frees budget for skill points. These carriers retain explicit single-type picks; carriers with a
dominating elemental default retain the compact collapse. The certifier detects explicit-pick carriers separately
from equip-var aliases, so the two forms can coexist without removing an aliased item's own stats from the
certificate's base terms.

World N's category terms now put an elemental default in E rather than D. For a default contribution r,
`capRead(E+r,D,K,O) = capRead(E,D+r,K,O) + r`: the positive damage term is identical, but the secondary charge
is removed. The swapped option has the same final category totals as before. Thus every source's best World N
option can only rise; normal-world damage values are unchanged. Version 54 invalidates the old cached bounds.
Retaining explicit alternatives likewise preserves the previous options and can only raise World N's best
per-carrier valuation; the normal worlds still prune dominated damage options to the same values.

`RuneChoiceCollapseTest` locks the CRA-230 fire/distance/face reproduction with Neutralité III: cape
(fire 1000, four sockets, level 230) + socketless boots (fire 1000), and the variant adding a level-200 helmet
(fire 1000, rear −120, four sockets). It compares the free MASTERY_DISTANCE=1 request with the same request plus
HP=0, requires Neutralité selected, and checks every feasible AP cell against the exact, fast and tier-1.5
certificate bounds. A separate doubled-crit fixture checks the elemental-default suppression, four-rune export
and the certifier's normal-world split. On `main` its first test fails (1,342,090 vs 1,540,880).

## The search cost, and its mitigation (review, 2026-10-04)

The review found no under-count but a search slowdown on the production path: the Neutralité family is choosable
by default, so #226 gave every carrier whose best rune is a secondary mastery 3–4 pick bools (every scenario
mastery + crit) instead of the equip-var alias — on 7 of the 9 socketed slot kinds. GUI-default rows were
unaffected (an HP / resistance / dodge row already selects the general fold).

### Per-carrier Pareto pruning — the rule, derived from the model's readers

A rune of a candidate type (the scenario's M-feeding masteries — elemental, range band, rear, berserk, healing —
and critical mastery) enters the CP-SAT model only through `baseTermsFor` of its own characteristic, so it is read
exactly where that characteristic is read (`MaxDamageRuneReads` in `RuneModelBuilder.kt`):

| Reader | What it reads | Side the build prefers |
|---|---|---|
| damage objective `Graw = (400 + c)·M + 5c·K` | M = 100 + Σ scenario masteries (weight 1), K = crit mastery, c ∈ [0, 100] | more |
| Neutralité family (`SECONDARY_MASTERIES_AT_MOST`) | EACH of the six secondaries — melee, distance, berserk, rear, **critical**, healing — on its own: one bound per stat (v54 / v55 read their SUM at weight 1 — wrong, see the per-stat section) | choosable: less; forced: exact |
| Critical Secret (`CRITICAL_MASTERY_AT_MOST`) | critical mastery alone | choosable: less; forced: exact |
| Dénouement (conversion crit → elemental, 100 %) | pre-sub critical mastery | re-labels part of a crit rune into M |
| `HIGHEST_ELEM_MASTERY_GT_REAR` / `_HEALING` | — not solver-modelled (the sub applies unconditionally); no sub in the data carries them | — |
| required target rows, ramps, %-skills, best-element concentration | never a candidate rune type in max-damage | unknown ⇒ "opaque" |

Choice Q **dominates** choice P on the same carrier (same socket count) iff:
- **objective:** both are M-feeding and `v_Q ≥ v_P`, or Q is M-feeding and P is crit with `v_Q ≥ v_P`
  (∂Graw/∂M = 400 + c ≥ 5c = ∂Graw/∂K — the inequality the original collapse already relied on, tied to
  `perHitDamageScore`'s coefficients and its M ≥ 0 / K ≥ 0 clamps). Dénouement re-labels at most the crit rune's own
  value into M, so it never lets crit beat an M rune of at least its value. Crit never dominates an M rune (c = 0);
- **every modelled condition** reads Q at least as well as P on the side the build prefers (a choosable sub only
  restricts a build once taken, so a `≤` cap prefers less; a FORCED sub's effect is gated by its condition and may
  be a malus, so it needs equality);
- **neither type is opaque** (read by anything else: a conversion out of it other than crit → M at ≤ 100 %, a ramp,
  a %-skill, a required row, a per-element mastery compare). Opaque types are never pruned and never prune.

Ties keep the first type in the fixed order elemental, range band, rear, berserk, healing, crit. The relation is a
preorder, so every dropped choice has a kept dominator: swapping the dropped choice for it keeps every taken sub's
condition and never lowers the objective — the pruned model keeps the full-choice optimum, and the certifier, which
reads the same `runeVars`, mirrors the same (smaller) pick set.

So, to the reviewer's examples (as derived for v54 / v55, under the SUM reading): crit mastery IS secondary for the
caps, so an equal-valued distance rune cost the same budget and dominated the crit rune (it also beats it under
Critical Secret); no modelled condition told rear from distance, so equal-valued rear / distance runes were one choice.
Under the per-stat rule (v56) both conclusions fall: see below. A strictly smaller secondary or crit rune
survives as the "half-budget" option, and the elemental rune always survives on a secondary-best carrier.
At rune level 11 (secondary 33, elemental 22, doubled on favoured slots), fire / distance / rear with the
family choosable:

| Slot | Values (E / D / R / C) | Kept by #226 | Kept now (gated) |
|---|---|---|---|
| helmet, amulet, ring | 22 / 33 / 33 / 33 | E, D, R, C | E (gated), D |
| chest, cape | 44 / 33 / 33 / 33 | E | E |
| shoulders | 22 / 33 / 33 / 66 | E, D, R, C | E (gated), D, C |
| boots | 22 / 33 / 66 / 33 | E, D, R, C | E (gated), D (gated), R |
| belt | 22 / 66 / 66 / 33 | E, D, R, C | E (gated), D, C (gated) |
| weapon | 22 / 66 / 33 / 66 | E, D, R, C | E (gated), D, R (gated) |

With no secondary cap modelled the rule reduces exactly to the original collapse (best M rune, plus crit when
larger).

### Choice gates

A kept choice that only a CHOOSABLE conditional sub keeps (above: the elemental rune, the half-budget secondary /
crit) is useless while none of those subs is taken: it is posted with `pick ≤ Σ subVar` over them
(`RuneModel.choiceGates`). Soundness: in any build where those subs are untaken, swap the gated pick for its
never-gated dominator — it is at least as good on every read that is still active. The fixed-search workers decide
the conditional subs first, zero side first (`postConditionalSubBranchingStrategy`), so the cap-free subtree is
exactly the original collapse again. The certifier never reads the gates (it bounds a relaxation of the same
optimum).

### Finding (2): forced items

#226 treated `dominationShape(...)?.pinned == null` as "capped", but forced items (and per-item forced runes, and
unsupported conditions) also make it null. The readers now come from the subs the model actually builds
(`modelledSublimations`), so a forced-item request with no secondary cap keeps the compact collapse.

### The fold under a positive secondary budget (✅ fixed in CERTIFIER_VERSION 57)

The single-type fold (and the collapse built on it) was not exact when an item's NEGATIVE secondary line gives a
`≤ 0` cap a positive budget: a mixed item can fill it exactly. Fixed by per-carrier rune counts — see
[MIXED_RUNE_SECONDARY_BUDGET.md](MIXED_RUNE_SECONDARY_BUDGET.md).

## Per-stat secondary cap (2026-10-04, CERTIFIER_VERSION 56)

The game holds EACH secondary mastery ≤ 0 on its own: Neutralité III (State 6931 → effect 397776), Abandon II (6932),
Prétention III (6933), Ambition III (7115) and Inflexibilité II (7256, live branch 394768) all gate their bonus on
`GetCharac("MELEE_DMG", "target") <= 0 and GetCharac("RANGED_DMG", "target") <= 0 and … BERSERK_DMG … CRITICAL_BONUS …
BACKSTAB_BONUS … HEAL_IN_PERCENT … <= 0`. A cap carrier can therefore not offset a positive secondary with a negative one
of ANOTHER stat (the player's Xelor build: distance +76 and crit +240 against rear −304 and berserk −12 sum to 0, and none
of its three caps fires in game) — only within the same stat.

`MaxDamageRuneReads` now reads such a condition as one `Bound` per secondary (`Bound(stat, side, choosableSub)`), each
held on its own. Choice Q dominates choice P only if Q reads no more than P on EVERY stat bound of a taken sub, so a
rune of one secondary never stands in for a rune of another: a rear rune can be absorbed by a −430-rear item where an
equal distance rune cannot. On a secondary-best carrier every secondary candidate therefore survives beside the elemental
rune (only an elemental rune at least as large prunes them), and the choice gates keep the cap-free subtree the original
collapse (the best M rune, plus crit when larger):

| Slot | Values (E / D / R / C) | Kept by #226 | Sum pruning (v54 / v55, gated) | Per-stat (v56, gated) |
|---|---|---|---|---|
| helmet, amulet, ring | 22 / 33 / 33 / 33 | E, D, R, C | E (gated), D | E (gated), D, R (gated), C (gated) |
| chest, cape | 44 / 33 / 33 / 33 | E | E | E |
| shoulders | 22 / 33 / 33 / 66 | E, D, R, C | E (gated), D, C | E (gated), D, R (gated), C |
| boots | 22 / 33 / 66 / 33 | E, D, R, C | E (gated), D (gated), R | E (gated), D (gated), R, C (gated) |
| belt | 22 / 66 / 66 / 33 | E, D, R, C | E (gated), D, C (gated) | E (gated), D, R (gated), C (gated) |
| weapon | 22 / 66 / 33 / 66 | E, D, R, C | E (gated), D, R (gated) | E (gated), D, R (gated), C (gated) |

The pick count is #226's again (the gates still restore the cap-free subtree). A pool-aware refinement — a secondary no
read source can make negative can never host a rune of its type next to a taken cap, so its rune could stand in for an
equal one — was considered and not built: every secondary but healing has negative lines on real items (Cartes
Truchesques −120 distance at level 78, Bâton Bola −57 critical at 75, Main de Cire Momore −150 melee, La Zimasse −400 rear,
Le Hachis Niyomi −400 berserk), and the domination filter pins secondaries so it keeps them: it would almost never fire.

Locks: `RuneChoicePruningTest` — the per-stat rule per slot on the real catalog (and its gates), and a CRA 230
fire / distance / back fixture (`an equal rear rune survives the distance one …`) where the optimum takes Neutralité III
with the helmet's four REAR runes absorbed by a −1000-rear cape: pruned + gated == full-choice == general fold. RED under
a mutation that makes every secondary bound read all six (the sum): pruned 1,852,970 vs full 1,878,720. The seeded lock
(24 pools, every third one with non-positive secondary lines only, so caps are taken) stays green and is NOT sensitive to
that mutation — the dedicated fixture is the lock for it.

Cost (production path, `PerfBaselineE0Test`, 4-core profile, 120 s, three runs each): `MD245F` proves the same
20,811,420 at 71.0 / 62.0 / 75.6 s against 58.3 / 64.1 / 61.7 s on `ed97adfa` (+13 % mean) — the certificate warm-up
that closes the proof reads the larger pick set. The nightly lvl-245 fast ledger is bit-identical (the added picks only
tie existing ones). The free face fixtures (`MD80MF` / `MD200MF` / `MD230DF`) were not re-measured.

## Production-path measurements

`PerfBaselineE0Test` (`WAKFU_E0=1`, the GUI's search + proof chain), CRA fire, runes + subs, EPIC, free request (the
range band's maximized mastery row), 120 s, 4-core profile (`-XX:ActiveProcessorCount=4 -Xmx3g`) unless noted; Apple
M5, the shared Gradle lock held exclusively, cold certificate caches. Fixtures: `MD110F` / `MD245F` (distance / rear,
the E0 fixtures of `docs/perf-next-steps-2026-10.md` §8) and the review's free face requests, now named E0 fixtures `MD80MF` / `MD200MF` / `MD230DF`. Two
runs each (run 2 in reverse variant order); "proven" = the search ended proven (CP-SAT OPTIMAL or the certificate's
in-search construct), otherwise the certificate's badge. main = `d4f69bbb` (`CERTIFIER_VERSION` 53); PR = #226 rebased
on it (54); "+ pruning" ships the Pareto pruning and the choice gates (the gates alone in the last column for reference).

| Request | main | PR as-is | PR + pruning (shipped) | pruning, no gates |
|---|---|---|---|---|
| MD110F | 1,596,510 / 1,551,360 — within 1.03 / 3.97 % | 1,523,080 / 1,481,205 — within 5.90 / 8.89 % | 1,596,510 / 1,610,745 — within 1.03 / 0.14 % | 1,610,745 ×2 — within 0.14 % |
| MD245F | proven 50 / 58 s | proven 57 / 64 s | proven 54 / 56 s | proven 51 / 55 s |
| MD245F, 10 cores | proven 51 / 100 s | proven 115 / 112 s | proven 96 / 86 s | proven 95 / 100 s |
| 80 melee face | 796,125 — proven 32 / 35 s | 783,750 ×2 — within 6.95 % | 792,000 ×2 (Neutralité III + Inflexibilité II + Ambition III) — within 5.83 % | 796,125 / 779,625 — within 5.29 / 7.51 % |
| 200 melee face | 10,571,825 — proven 42 / 74 s | 10,332,420 / 10,380,535 — within 6.38 / 5.89 % | 10,352,130 / 10,268,910 — within 6.18 / 7.04 % | 10,366,290 / 10,268,910 — within 6.04 / 7.04 % |
| 230 distance face | 14,853,230 — proven 44 / 59 s | 14,658,595 / 14,660,940 — within 2.29 / 2.27 % | 14,607,005 / 14,740,670 — within 2.65 / 1.72 % | 14,444,805 / 14,248,125 — within 3.80 / 5.23 % |

**What the pruning recovers.** The distance / rear fixtures: MD110F is back at (or above) main's value and badge;
MD245F proves as on main on 4 cores; on 10 cores (where main itself varies 51 / 100 s) the means read main 76 s, PR
114 s, pruned 91 s. That proof is the E10 certificate warm-up landing (`warmDoneMs` = the search end in every variant),
and with the pruning the 245-rear certificate costs what main's does again (below).

**What it does not recover: the face fixtures**, which stay unproven at 120 s, a few tenths of a percent to ~3 % below
main's values. The fix itself is the cause: it makes the Neutralité family's world reachable — at 80 melee face that
world holds a better build than main's proven one, so main's 32 s "proof" of 796,125 was a wrong badge (797,775 exists; the review's 600 s
10-core PR run found it at 126 s, and the shipped model finds it at 121 s with the same budget — Neutralité III +
Ambition III, 32 elemental runes — still "within 5.07 %"). Control: the shipped variant with the four caps EXCLUDED from
the request (`WAKFU_E0_EXCLUDE_SUBS`) proves 796,125 / 10,571,825 / 14,853,230 at 15 / 36 / 49 s — main's values at
main's speed. So the pruned model is main's model wherever no cap is taken; what remains is the cost of the cap world.
Its badges cannot close either: the v54 certificate bounds these shapes at 838,200 / 10,992,040 / 14,993,930 (v53:
806,025 / 10,594,210 / 14,918,890), 4–5 % above the best builds — world N (the capped world) is the loose part.
Follow-up: a tighter world-N bound, or a world split of the search (the cap-free world, then the cap world under the
cap-free optimum as a cutoff).

**Model size** (vars / constraints of the full max-damage model, EPIC pool, domination on, every sub; main → PR →
pruned): 80 melee face 2,004 / 674 → 4,664 / 1,384 → 4,082 / 1,384 (+1,103 gate constraints); 200 melee face 7,071 /
1,800 → 19,308 / 5,069 → 16,599 / 5,069 (+5,044); 245 rear 7,812 / 1,219 → 27,323 / 5,643 → 20,052 / 5,643 (+7,211). The explicit picks still roughly double
main's model where a cap is choosable: the elemental rune must stay available on every secondary-best carrier.

**Certificate cost** (the fast-tier ledger alone, 1 thread, production pool; main / PR / pruned): 245 rear 18.3 / 21.5 /
19.4 s, 110 rear 5.8 / 11.9 / 11.7 s, 80 melee face 9.9 / 9.6 / 9.7 s, 200 melee face 11.1 / 22.4 / 20.7 s, 230 distance
face 15.1 / 31.1 / 28.3 s; the PR and the pruned model certify identical bounds on all five shapes. With the real
incumbent (fast tier + the exact tier on the surviving cells, as the E10 warm-up runs it): 245 rear 21.9 / 26.3 /
22.3 s, 110 rear 13.8 / 20.1 / 20.0 s — at 110 the extra cost is v54's corrected world N, not the picks.

## Validation

- CI locks: `RuneChoicePruningTest` — the rule per slot on the real rune catalog (and the gates' sub sets), the original
  collapse when no cap is modelled, forced and opaque readers, and pruned + gated == full-choice (every candidate on
  every carrier, no gate) == general fold on 24 seeded pools (1 worker, seed 1, interleaved search; six optima carry a
  Neutralité-family sub). The seeded lock is RED under two mutations: dominance that ignores the condition reads (main's
  collapse) and gates on Neutralité III alone. `RuneChoiceCollapseTest` (its first test fails on main: 1,342,090 vs
  1,540,880), `MaxDamageTargetAwareCertificateTest`, `SoundnessReviewAdversarialTest`, `DominationSoundnessReproTest`,
  `WakfuBuildSolverTest`: green.
- The manual max-damage fuzz on fresh seeds with the new knobs (`WAKFU_REVIEW_MD_COLLAPSE` / `_NEUTRALITE` / `_PRUNE`):
  240 collapse-shaped cases — Neutralité III choosable (60) and forced (60), plus required AP / MP / RANGE / CC rows with
  it choosable (40) and forced (80) — 3,538 cell comparisons, 240 ledgers, 666 target-aware hard-leg cells, 0 bails,
  0 failures; pruned + gated == full-choice on all 240. Default shapes (40, seeds 86000): 642 cells, 40 ledgers, pruned + gated == full-choice on all 40, 0 failures.
- The review's collapse-shape scratch fuzz (never committed) on fresh seeds, 30 free + 20 rowed pools × {choosable,
  forced}: 1,324 cells (+ the same on the domination-reduced pool), 500 ledgers, 256 target-aware cells, 0 failures;
  collapse == general fold in all 100 comparisons (the per-stat count model beat the fold on 7 pools — the OPEN item).
- `:autobuilder:slowTest --tests '*lvl-245 fast certifier ledger*'`: the banked lvl-245 oracle reproduced exactly (no
  re-bank).
- `./gradlew test ktlintCheck` (every module, the full `:autobuilder:test` included): 891 tests, 81 skipped, 0 failures;
  ktlint clean (`ChangeFragmentsTest` validates the reworded release note).

## Historical: deterministic flagship bench (pre-#225, before the pruning)

Measured by #226 on `e4cb857c` (certifier 52 → 53), i.e. before the domination-contract fix (#225) and before the
pruning; superseded by the production-path table above. The `manual max-damage experiment ab` harness, full eligible
Epic pool, CRA fire/distance, free targets, runes + all choosable sublimations, domination on, one worker, seed 1,
`interleaveSearch=true`, `maxDeterministicTime` 30 / 120. Raw points: [rune-choice-collapse-bench.csv](rune-choice-collapse-bench.csv).

| Level | Deterministic budget | Before objective | After objective | Before bound | After bound |
|---|---:|---:|---:|---:|---:|
| 110 | 30 | 1,166,175 | 1,045,725 | 2,294,025 | 3,274,050 |
| 110 | 120 | 1,425,690 | 1,277,865 | 2,281,980 | 3,018,915 |
| 245 | 30 | 5,540,250 | 8,037,000 | 28,212,360 | 33,285,600 |
| 245 | 120 | 15,367,120 | 12,528,125 | 27,609,360 | 33,282,920 |
