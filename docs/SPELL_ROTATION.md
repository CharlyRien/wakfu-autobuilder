# Spell rotation optimizer (P4)

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

- Inputs: the build (its real AP, masteries, DI, crit), the class's actual spells (`spells-v*` via
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
