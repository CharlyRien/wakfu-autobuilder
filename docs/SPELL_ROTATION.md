# Spell rotation optimizer (P4)

How the max-damage flow turns a discovered build into a concrete **per-turn spell rotation**, and an
evaluation of whether the build *search itself* should be made spell-aware.

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

## Architecture

Per `docs/SPELLS_AND_COMBO_RESEARCH.md`, this is a **post-processing pass, deliberately NOT fused into
the OR-Tools item model** — fusing spell sequencing into the build search would blow up the constraint
model. It reuses `SpellDamage` / `BuildSpellDamage` / `SpellCatalog`, so the damage maths lives in one
place.

## Bonus evaluation — should the build *objective* be spell-aware?

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
