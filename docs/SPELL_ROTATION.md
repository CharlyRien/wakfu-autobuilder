# Spell rotation optimizer (P4)
> **✅ Shipped.** The spell-aware + boss-aware max-damage objective, the rotation optimizer, the external AP-probe loop and resistance-debuff sequencing are all implemented & merged (PRs #153, #162, Lot 2). See [`FULL_DAMAGE_MODE_STATUS.md`](FULL_DAMAGE_MODE_STATUS.md) for the current state. Kept as the architecture/design record; code references may drift — trust the symbols.

How the max-damage flow optimizes for the **best per-turn spell rotation**, and how that became part
of the solver itself.

> **Update — the objective is now spell-aware AND boss-aware (fused into CP-SAT).** The original P4
> shipped a *post-processing* rotation pass (described below, still used for display). It has since
> been **superseded for build selection**: the rotation and the boss's element choice are now fused
> directly into the max-damage CP-SAT objective, so the solver jointly optimizes equipment + rotation
> + element. See **"Fused objective"** below. The earlier "don't fuse" evaluation is kept at the end as
> the record of *why* it looked hard — and how the factor-out trick made it tractable after all.

## What it does

After a max-damage search produces a build, a **post-processing pass** answers: *with this build's
AP, which spells should I cast this turn to deal the most damage?*

- Inputs: the build (its real AP, masteries, DI, crit), the class's actual spells (`spells.json` via
  `SpellCatalog`), and the attack `DamageScenario` (element, range band, orientation, target
  resistance, crit cap).
- Candidates: the class's real damage spells **in the scenario element** — the element-gating that
  stops a Cra being told to play Water (an unplayable element yields an *empty* rotation + a hint).
- Each candidate is scored with `BuildSpellDamage` (the build's resolved stats × the spell's base hit
  → expected damage per cast) and carries its AP cost.

## How the budget + selection work

`SpellRotationOptimizer` solves an **unbounded knapsack** over the AP budget by DP: `dp[a]` = the most
expected damage achievable with ≤ `a` AP; each spell may be cast repeatedly. It is exact and finds the
best *combination* of casts (e.g. it prefers `6AP+6AP` over `5AP+5AP+idle` when the former totals
more), not a greedy best-damage-per-AP pick.

**Known limitation, stated honestly:** per-turn **cast limits and cooldowns are not in the dataset**
(the extractor parses them as null). Without them the optimum can be "spam the most AP-efficient
spell". That is the true optimum *under the known constraints*; an optional `maxCastsPerSpell` is wired
for when cast-limit data lands. Both surfaces label the rotation as an upper-bound suggestion.

## Where it shows

- **CLI** (`autobuilder`): after a `--computation-mode max-damage` search —
  ```
  Optimal spell rotation (fire, 9 AP)
    3× Fulminating Arrow (3 AP, ~14925 dmg/cast) → ~44777
    Total: ~44777 expected damage/turn (9/9 AP used)
    Note: per-turn cast limits aren't modeled yet — treat as an upper-bound suggestion.
  ```
- **GUI** (`gui-compose`): a *Spell Rotation* card in the stats panel in max-damage mode (computed
  off-thread in `BuildSearchModel`, rendered by `StatsPanel`), per-cast lines + per-turn total + AP
  used, EN/FR.

The post-processing optimizer (`SpellRotationOptimizer`) is still used to **display** the rotation in
the CLI/GUI, and its `bestAcrossElements` is the exact scorer the solver ranks emitted builds by.

## Fused objective (spell-aware + boss-aware, in CP-SAT)

The build search itself is now spell- and boss-aware **in max-damage mode only** (the other modes are
untouched and unchanged in speed). The objective — `WakfuBuildSolver.StatBuilder.perTurnDamageScore` —
makes the solver pick equipment, element, and rotation **jointly**.

**The trick that makes it tractable.** Naively, "best rotation damage" couples the build's mastery
variables with the spell-selection variables (a bilinear, model-exploding term). But within one
element every same-element spell shares the *same* per-build multiplier `G_e` (mastery/DI/crit), so it
**factors out** of the rotation sum:

```
rotation_damage_e = G_e × Σ(base_s · count_s)   ⇒   the Σ(base·count) part is build-INDEPENDENT.
```

So the spell selection collapses to a **precomputed, build-independent knapsack table**
`T_e[ap] = best Σ base castable in ap AP` (`SpellRotationOptimizer.baseThroughputTable`), and the only
variable×variable product left is `throughput_e × perHit_e` — the same shape the model already
linearizes for `D·Graw`.

Per candidate element `e`:

| Term | How it's modelled |
|---|---|
| `perHit_e` = `D·Graw` | existing per-hit core, now element-parametrized (`perHitDamageScore`) |
| `throughput_e` = `T_e[apVar]` | `addElement(apVar, T_e, throughput_e)` — table lookup by the build's **AP variable** |
| element gating | intrinsic: a class with no `e` spells has an all-zero `T_e` ⇒ contributes nothing |
| `resFactor_e` = `(100 − res_e)` | the boss's per-element resistance (a weakness `res_e<0` amplifies it) |
| `damage_e` | `(throughput_e × perHit_e) × resFactor_e`, staged-scaled to keep domains ≤ ~6e13 |
| **boss-aware choice** | `total = max_e damage_e` — the solver picks the best **playable** element given the boss profile *and* the kit |

**Magnitudes.** The per-hit core (up to ~5e11) is divided by `PERHIT_DOWNSCALE`, multiplied by the
throughput, then the `× resFactor` product is divided by `FINAL_DOWNSCALE`, so every CP-SAT variable
domain stays well inside int64 (no presolve overflow) while keeping >1e5 levels of ranking resolution
even for low-level builds.

**Scorer lockstep.** `scoreFor`'s max-damage branch uses `SpellRotationOptimizer.bestAcrossElements`
(the same `max_e (throughput_e × G_e × resFactor_e)`) ÷ the existing required-target penalty, so the
build the objective maximizes is exactly the one emitted with the reported `matchPercentage`.

**Inputs.** `DamageScenario.elementResistances` (CLI `--boss-resistances 'fire:0,water:-90,…'`) switches
on boss-aware mode; without it, the objective optimizes the single `--scenario-element`.

**Performance.** The fusion adds only `O(#candidate elements)` variables (≤ 4 × a handful), dwarfed by
the equipment-selection model. Measured: a full-pool level-230 search is the **same wall-clock
single-element vs 4-element boss-aware** (~12.2 s each, of which ~5 s is the search budget and the rest
JVM + OR-Tools native cold start) — no measurable regression.

**Confinement.** Only `buildMaxDamageObjective` calls `perTurnDamageScore`; `buildMostMasteriesObjective`
and `buildPrecisionObjective` are untouched, so most-masteries / precision modes are byte-for-byte the
same and just as fast.

## Bonus evaluation (historical) — should the build *objective* be spell-aware?

> This evaluation argued *against* fusing, then noted the factor-out property as the escape hatch. The
> fusion above is exactly that escape hatch realized, so this section is kept only as the design record.

Question: instead of post-processing, should the build search maximize the *rotation's* damage directly?

**For the dominant case, it would change nothing — and here's the proof.** For a fixed element and
range band, every candidate spell shares the same build-dependent multiplier
`M = (1 + ΣMastery/100)(1 + ΣDI/100)·crit·…`. The rotation total is

```
total = M × Σ(base_i × count_i)
```

and the `count_i` (the knapsack solution) depend only on `base_i / apCost_i` — which are
**build-independent**. So `M` factors out: the build that maximizes the single-spell objective
(maximize `M` for the element) is the *same* build that maximizes the rotation total. **The
post-processing rotation is therefore optimal for the common case, and a spell-aware objective adds
nothing there.**

Where a spell-aware objective *could* differ — three secondary effects:

1. **AP breakpoints (real, the strongest case).** The value of +1 AP is a *step function*: it's worth
   a whole extra cast only when it crosses a spell's cost boundary. A mastery-linear objective treats
   AP as a flat constraint and misses that non-linearity, so it can under/over-value AP gear.
2. **Hybrid / multi-element builds (real).** If a class's best damage mixes two elements
   (the classic "fire/air Cra"), the rotation total depends on *both* masteries; the single-element
   objective optimizes one. A rotation-aware objective could pick a genuinely different bi-element build.
3. **Mixed range bands (minor).** A rotation spanning melee + distance spells values both masteries;
   builds usually specialize, so this rarely beats specializing.

**Feasibility of fusing into OR-Tools:** poor. The objective `M(masteries) × Σ(count_i)` is **bilinear**
(build-mastery variables × cast-count variables) — not expressible in CP-SAT without heavy
linearization. This is exactly the model-explosion risk the research doc flagged.

**Recommendation.** Keep the post-processing rotation — it is optimal for the common case and cheap. Do
**not** fuse the rotation into the CP-SAT model. If the secondary effects (AP breakpoints, hybrid
builds) are ever worth chasing, the right architecture is an **outer loop**, not fusion: run the build
search across a few `(element, AP-target)` candidates and keep the build whose *post-rotation* total is
highest — reusing the existing per-element search the boss mode already iterates. That captures the AP
breakpoint and hybrid wins without touching the solver model.

---

## Resistance-reduction debuffs + sequencing + the external loop

> The outer loop the evaluation above recommended **is now implemented** — and it does more than the
> AP-breakpoint capture it was first proposed for: it makes the build search aware of **resistance-
> debuff sequencing**.

### Data (what's extractable)

Resistance reductions on the encyclopedia spell pages are **flat, all-element** ("−N Elemental
Resistance") and last the turn ("Removed at the start of your turn"). Element-specific reductions do
**not** occur in this data version. Durations/conditions are mostly not inline (state-tooltip pages are
fetchable but out of scope). So `Spell.targetResistanceReductionFlat` captures only the **flat
magnitude**, for **active** spells, with the target read from the nearest `[enemy]`/`[caster]`/`[ally]`
picto — self/ally reductions dropped, enemy-or-ambiguous kept (ambiguous flagged `resistanceTarget?`).
Nothing is invented.

**Coverage: 8 active enemy resistance-debuff spells** across 8 classes — Sram *Assassination* (−100),
Ouginak *Sidekick* (−100), Sadida *Toxines*/*Sudden Chill* (−50), Iop *Focus* (−50), Pandawa *Bamboo
Blow* (−50), Osamodas *Weakening Cry* (−50), Ecaflip *Three Cards* (−30). 6 have an unconfirmed target
(active targeted spells, assumed enemy, flagged). Passive/self resistance changes are intentionally not
captured.

### Why flat, and why order matters

The flat→percent curve `res% = 100·(1 − 0.8^(flat/100))` is **concave**, so a flat debuff must be
applied in flat then re-converted (`Resistances`): removing 100 flat is a different % swing depending
on the target's base resistance. And because reducing resistance raises **every following** hit, the
rotation is no longer just *selection* — it's *sequencing*: cast the debuff first, then the damage.

### Sequencing model (`SpellRotationOptimizer.bestSequencedRotation`)

Per candidate element, enumerate the (tiny) subsets of the class's resistance debuffs — each applied at
most once — and for each: lower the target's flat resistance, then knapsack the **remaining** AP at the
post-debuff resistance; credit the debuff casts' own AP and damage; keep the subset (possibly none) with
the highest turn total. Exact for the single-turn case and trivial (debuffs are ≤ a couple per class).

### The external loop (`MaxDamageSearch`) — why post-processing alone is not enough

A pure post-processing rotation has the flaw the user flagged: the CP-SAT objective picks the build's
**AP and masteries without knowing the best sequencing**, so it can pick an AP value optimal for
damage-only throughput but suboptimal once a debuff is sequenced. So max-damage now runs an **external
loop** (confined to max-damage; other modes unchanged):

1. one unconstrained boss-aware solve → the objective's natural AP `A₀`;
2. **AP-pinned** probe solves around `A₀` (`maxDamageApTarget` hard-constrains AP to exactly N), run
   **in parallel**;
3. every resulting build re-scored with the **debuff-aware** sequenced rotation;
4. keep the build with the highest real per-turn damage.

This captures the AP breakpoints **and** (by probing the AP-vs-mastery frontier) the cases debuff
sequencing changes — without fusing the bilinear sequencing term into CP-SAT.

**Proof it catches a breakpoint a single solve misses** (deterministic test, Sram vs a 55%-res boss):

| Build | AP | Fire mastery | Damage-only valuation | Debuff-aware valuation |
|---|---|---|---|---|
| **A** | 13 | 600 | 1419 | **1716** |
| **C** | 12 | 660 (13th AP's slot spent on mastery) | **1470** | 1643 |

A damage-only objective picks **C** (1470 > 1419). With Assassination sequenced, **A** wins (1716 >
1643) — its 13th AP lets the 1-AP debuff fit without dropping a damage cast. The loop probes AP=13 and
lands on A.

### Performance

The loop is ~`single-solve wall-clock + ~2 s`: the probes share the search budget and run in parallel,
so a full-pool level-230 search with a 10 s budget completes in ~19 s (vs ~17 s for one solve) — the
extra cost is just building the few probe models. Confined to max-damage; the other modes are a single
solve, unchanged.

### Impact (debuffs, AP choice, boss interaction)

- **Debuffs help whenever there's AP room** — a flat reduction always lowers effective resistance (even
  creating a *weakness* at 0 res), so opening with one never hurts the following hits. It's *skipped*
  only when the AP can't be spared for it **plus** enough following hits to profit. The honest rule is
  "debuff when there's room to profit", not "always".
- **It changes the optimal AP, not just the rotation.** A debuff costs a fixed AP, so the build's best
  AP can shift to one that fits "debuff + a clean damage rotation" — which only the **external loop's AP
  probing** (not post-processing, not the CP-SAT objective) can find.
- **Boss interaction.** The captured debuffs are all-element, so they lower every element equally and
  don't *change* the boss-aware element choice — but they raise the chosen element's value, and are
  worth more AP against a more-resistant boss (more headroom to claw back).
- **Hybrid (bi-element) builds** are a noted future extension: the loop probes single elements × AP;
  enumerating element *pairs* would catch split-damage builds (it multiplies the candidate count, hence
  deferred).
