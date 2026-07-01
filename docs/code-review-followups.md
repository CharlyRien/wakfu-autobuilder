# Code-review follow-ups — solver-choosable-sublimations branch (2026-07-01)

Deferred findings from a **max-effort `/code-review`** of the sublimation work (branch
`claude/optimistic-lewin-03113f`). The confirmed correctness bugs and the two *safe* architecture items were
fixed on that branch; what remains here is the larger refactors + cleanup, kept for a later, separately-reviewed
pass.

> ⚠️ **This is provably-optimal solver code.** A mistake in the objective / pruning silently breaks the
> "proven optimum" guarantee without crashing. For every item below: keep the diff small, re-run the **full**
> suite (`./gradlew test`, incl. the soundness/optimum-preserving tests in `WakfuBuildSolverTest`), and for any
> objective/model change do a **cool-machine deterministic-time perf re-verify** (wall-clock is thermal-noisy —
> see `docs/SOLVER_PERFORMANCE.md`). Line numbers below are as of 2026-07-01 and will drift — anchor on symbols.

## Already fixed on this branch (context — do NOT redo)

| ID | What | Where |
|----|------|-------|
| A1 | objective↔display/selection dropped scenario-gated / best-element / per-element sublimation DI | `BuildSpellDamage.resolveStats` + `SpellRotationOptimizer.scoreSpells` (thread `damageScenario`) |
| A2 | pre-sealed history saves failed to decode → silently dropped | `SublimationEffectLenientSerializer` (`common-lib/Sublimation.kt`) |
| A3 | domination pruned the survivability-floor optimum | `dominationShape` adds HP + resistances when `scenario.survivabilityFloor` |
| A4 | `Double` products feeding hard CP-SAT bounds lost 2⁵³ precision | `clampedProductQuotient` (BigInteger) |
| B6 | `GeneticAlgorithmResult` → `SolverResult` | 9 files + docs (package stays `genetic`) |
| B2a | experiment **config** lifted out of the engine | `MaxDamageExperiments.kt` |
| B5 | reviewed → intentionally **not** changed | see [Decided](#decided--no-change) |

---

## Architecture (high value, large — one PR each)

### B1 — split `WakfuBuildSolver.kt` (5,481 lines)

One `object` holding a ~2,830-line `private class StatBuilder` (`WakfuBuildSolver.kt:2265`), an ~890-line
`perHitDamageScore` (`:3548`), plus domination, rune fold, sublimation folding, domain tracking and solution
decoding. Every concern shares one file, so any change forces the whole context and merge conflicts are near-certain.

- **Target shape:** extract cohesive units — `SublimationModelBuilder`, `RuneModelBuilder`,
  `DamageObjectiveBuilder`/`PerHitDamageFold`, `DominationFilter`, `DomainTracker` — leaving `WakfuBuildSolver`
  a thin orchestrator.
- **Feasibility:** `StatBuilder` is already a *standalone* `private class` (not `inner`), so moving it to its own
  file is mechanically clean — but it references many object-level `private` consts/types/helpers (`Term`,
  `RandomEntry`, `SkillTerms`, `PowerTable`, `DomainTracker`, `RuneModel`, `SublimationModel`, `DominationShape`,
  `mulRange`, `scenarioMasteryStats`, `effectiveStatForDomination`, `clampedProductQuotient`, the `DAMAGE_*` /
  `ELEMENTARY_*` constants). Each becomes an `internal`-visibility change. **Compiler-gated** (won't build until
  every ref resolves) but a large diff.
- **Effort/risk:** high effort, low *correctness* risk (compiler + tests gate it), but big review surface. Do the
  `StatBuilder` file-move first, then peel sub-builders out incrementally.

### B2b — move the experiment **methods** out of the production hot path

`MaxDamageExperimentConfig` config is already extracted (B2a), but the research/A-B *code* still lives inside
`StatBuilder` and its objective loop:

- `certifyMaxPerHitAtAp` (`:3988`, ~230 lines), `innerFrontierPrototype` (`:4216`, ~130 lines — writes only
  `System.err`, **no production/test consumer**), `maxPerHitProductBound` (`:3745`), `maxRotRawForElement` (`:3692`).
- `System.getenv("WAKFU_MAX_DAMAGE_*")` hooks at `:3464`, `:3467`, `:3474`, `:3779`, `:4075` — some inside
  `perTurnDamageScore`'s per-element loop.

All are gated off in production (`MaxDamageExperimentConfig.DEFAULT`). **Blocked on B1** (they use `StatBuilder`
state). After B1: relocate to a test-source `MaxDamageCertifier`/experiments harness behind a narrow seam.
`innerFrontierPrototype` is a likely straight **delete** — but confirm with the maintainer first, it may be
deliberately *banked research* (cf. the `test: bank …` commits + the Chaos "kept for a future fast model" note).

### B2c — tighten the AP-cell certifier for the new sublimation types

The enhanced AP-cell certifier (ported from the `wip/ap-cell-certifier` branch, validated by 3 CP-SAT soundness
tests) upper-bounds every per-AP-cell max so the optimum proves faster on low-core machines. It is **test-gated**
(`certifyForTest` / the `WAKFU_MAX_DAMAGE_CERTIFIER` env hook), **not yet wired into the production proof**. It
bails to CP-SAT for conversion / forced subs; for the newer **best-element-concentration** and **per-stat-step**
subs it does NOT bail — their Damage-Inflicted is folded at each term's MAX contribution, a SOUND but looser
upper bound (`certifyMaxPerHitAtAp`). **Before wiring it into the production proof:** tighten those two families
(or bail) and add a certifier-vs-CP-SAT test whose pool contains them, so the tight-bound claim is covered for the
current sublimation set.

### B3 — single source of truth for sublimation semantics

The CP-SAT objective (`buildSublimationTerms` `:4834`, `perHitDamageScore` `:3548`) and the re-scorers
(`FindClosestBuildFromInputScoring.sublimationFixedContributions`, `FindMostMasteriesFromInputScoring`,
`FindMaxDamageScoring`, and now `BuildSpellDamage`) are **independent transcriptions** of the same rules
(~45 "mirror the solver" comments). This is the fault line that produced **A1** — each new DI/mastery sub must be
credited in *four* places or they drift.

- **Proposed:** one `SublimationContribution` that yields a `stat → magnitude + gate` description from
  `(Sublimation, resolved pre-combat stats, scenario)`; the solver turns it into `Term`s, each scorer sums ints.
- **Caveat (why it's hard, not a quick win):** the objective emits `IntVar` constraints while the scorers do
  scalar int math — they're **two engines by necessity** (a deliberate, test-locked mirror). Only the *decision
  logic* can be shared, not the arithmetic. And "tests still green" does **not** fully prove a CP-SAT model
  refactor is optimum-preserving — needs targeted before/after equivalence checks. Related: `docs/MAX_DAMAGE_PROVABLE_OPTIMUM.md`.

### B4 — unify the per-family sublimation effect representation

`buildSublimationTerms` (`:4834`) is a per-family `if`-ladder (CONVERSION → `bestElementConcentration` →
`perStatStep` → generic `effects`), mirrored by the scorer ladder + `isModelableSublimation` + `dominationShape`
+ the domain fields on `Sublimation`. Adding the next family (e.g. Critical Secret, per-element DI — see
`AGENTS.md` §5 on the conservative choosable set) touches **5 lockstep sites**.

- **Proposed:** make `Sublimation` carry one ordered `List<SublimationEffect>` where conversion/concentration/
  per-stat-step are `SublimationEffect` subtypes with their own gate; solver + scorer each walk the one list with
  a per-subtype handler → a new family is one subtype + two handlers. Couples with **B3** and **B5**.

---

## Cleanup / duplication (mechanical, low risk — good first PRs)

### C1 — the scaled-range idiom is copy-pasted 8+ times *(correctness-adjacent)*

`if (coef >= 0) d.first*coef .. d.last*coef else d.last*coef .. d.first*coef` (and the scalar
`if (coef >= 0) d.last*coef else d.first*coef`) appears at `WakfuBuildSolver.kt` **2339-2341, 2348, 2568,
3356-3358, 3376-3378, 3794, 3806, 3818**. These are **sign-sensitive** bounds feeding CP-SAT big-M constants —
one hand-copy with `first`/`last` transposed is an *unsound domain* and a wrong "proven optimum". `mulRange`
(`:5412`) already computes the range product. Extract `Term.scaledRange()` / `.maxContribution()` and replace all
sites with one call.

### C2 — `effectiveStatForDomination` is a third copy of the `MAX_* → usable` fold

The `MAX_ACTION_POINT/MAX_MOVEMENT_POINT/MAX_WAKFU_POINTS → AP/MP/WP` mapping exists three times:
`effectiveStatForDomination` (`WakfuBuildSolver.kt:562`), `Characteristic.foldedForSublimation`
(`FindClosestBuildFromInputScoring.kt:733`), and `StatBuilder.effectiveStat`. If Ankama adds a `MAX_*` stat, one
copy gets updated and the domination pre-filter silently pins the wrong stat. Consolidate to one shared helper.

### C3 — `CharacIdCatalog.SCRIPT_NAME_TO_CHARAC` duplicates `ActionCatalog.CHARAC_CODE`

Two script-name → `Characteristic` maps in the **same module**: `CharacIdCatalog.kt:51` (6 entries) is a subset of
`ActionCatalog.kt:55` (~30). A mapping fixed in one silently stays wrong in the other, and unmapped ids resolve to
`null` → a sublimation quietly goes forced-only with no error. Reuse `CHARAC_CODE` (widen visibility) or extract a
shared `ScriptNameCharacteristics` table.

---

## Minor / efficiency (nice-to-have)

- **H1 — double full pass in the per-element-DI most-masteries scorer.** `FindMostMasteriesFromInputScoring.kt:87`
  runs `computeCharacteristicsValues` a second full time when per-element DI weights are present, though only the
  random-element roll assignment differs from `firstPass` (`:69`). Hoist the fixed-stat fold; re-run only the
  weighted roll. Gated to the per-element-DI case, so the common path doesn't pay it.
- **H2 — `scenarioElementStrongestVar` cache key.** `WakfuBuildSolver.kt:4917` memoizes by `sub.stateId` though the
  reified "scenario element is strongest" boolean is entirely sub-independent (`bestElementStrongestCache`, `:2924`).
  Latent (1 best-element sub today); with >1 it would mint N identical vars/constraints. Key on a singleton/scenario.
- **Dead no-op guards** in the random-element handling: `if (min(count, 1) == 0) continue` and
  `.filter { min(it.count, 1) > 0 }` are always true (`count ∈ {1,2,3}`). Delete.

---

## Decided — no change

### B5 — `Sublimation`'s solver/decoder fields are decoded game facts, not misplaced policy

Finder flagged `solverChoosable`, `bestElementConcentration`, `perStatStep`, `appliesBeforeCombat`, `scenarioGate`
on the pure-domain `common-lib/Sublimation.kt` as "engine policy in the domain model." On inspection they are all
**decoded game data** (the sub's actual effect / timing), belonging on the type like `effects`/`condition`. And
`solverChoosable` is computed by the extractor (`SublimationBuilder`) and baked into `sublimations.json` — moving it
off the type would force the autobuilder to *re-derive* modelability at load, duplicating the extractor and breaking
the "decode once, bake to JSON" pipeline (`AGENTS.md` §5). **Left as-is by design.**

---

## References

- In-repo: `docs/SOLVER_PERFORMANCE.md` (optimality-preserving speedups + why wall-clock timing is unreliable),
  `docs/MAX_DAMAGE_PROVABLE_OPTIMUM.md` (the per-element enumeration proof), `AGENTS.md` §4–5 (engine + data
  pipeline).
- Produced by a `/code-review max` pass; the A-family fixes + B6/B2a that preceded this backlog are on the same
  branch.
