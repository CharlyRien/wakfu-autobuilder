# Full-damage plan — best build + spells played, vs a given boss, mono/bi/multi-element

The **global, trackable plan** for the coherent full-damage mode. Multiple agents follow this doc: each
**lot** is an independently-claimable unit. Keep the status dashboard current as work lands.

> **Conventions.** Written in English to match the other design docs (`SPELL_ROTATION.md`,
> `BOSS_MODE_INTEGRATION_PLAN.md`, `SUBLIMATIONS_LOT3_RESEARCH.md`). Code references are **symbol-first**;
> the `:NNN` line hints are indicative (they drift — trust the symbol). Status legend: ✅ done · 🔶 partial
> (in progress / data present but unwired) · ⬜ todo.

## Status dashboard

| Lot | What it unlocks | Status | Depends on |
|---|---|---|---|
| **0** Boss data + selection | "vs a given boss" (auto-element) | ✅ **done** — CLI + GUI boss picker / icons ([#161](https://github.com/CharlyRien/wakfu-autobuilder/pull/161)) | — |
| **1** Cast limits in the fused table | realistic rotation (no 1-spell spam) | ✅ **done** — per-turn + cooldown caps joined to `Spell` and bounded in both the fused table & display path; WP **cost** extracted too (display-ready), WP **model** deferred (per-fight pool) | — |
| **2a-b** Bi-element objective + double-count fix | bi-element builds are *searched* | ✅ **done** — `perTurnDamageScoreBiElement` linear, scale-identical to mono, double-count-safe, tested + verified | — |
| **2c-d** Enumeration + pruning | bi-element **optimality** | ✅ **done** — `MaxDamageSearch` Phase 3 enumerates `(pair × AP × split)` in parallel; dead-pair + Pareto-frontier pruning; bi-element `sequencedScore` via `bestSequencedRotationBiElement` | 2a-b |
| **2e-f** Scorer lockstep + display | honest `matchPercentage`, UI | ✅ **done** — one `bestSequencedTurn` seam (mono \| joint bi); winning split rides on `GeneticAlgorithmResult.maxDamageBiElement`; shared-debuff (no double-spend); CLI + GUI render the merged two-element turn | 2c-d |
| **3** Sublimations on the damage path | major end-game lever | ⬜ todo (model on `feat/sublimations`, unmerged) | — |
| **4** Passives on the damage path | class-real damage ceiling | ⬜ todo (data on `main`, unwired) | — |
| **5** Coherence floor (survivability + role) | builds that don't die / aren't fantasy positioning | ⬜ todo | — |

Lots **0, 1, 5** are independent and parallelizable now. **2** is the core (extension, not a refactor). **3/4**
consume Lot 2's scenario gating.

---

## Target architecture (mapped to code)

```
Layer 1 — "perfect turn" tables (offline, BUILD-INDEPENDENT)
   SpellRotationOptimizer.baseThroughputTable(spells, maxAp) -> LongArray   [SpellRotation.kt:368]
   ⇒ ALL rotation combinatorics (cast limits included) live here. 0 solver variables.

Layer 2 — CP-SAT inner solve (per scenario)
   WakfuBuildSolver.StatBuilder.perTurnDamageScore / perHitDamageScore     [:1167 / :1228]
   ⇒ picks equipment + skill points. Throughput enters as a CONSTANT (mono: addElement; bi: newConstant).

Layer 3 — external enumeration loop
   MaxDamageSearch: today (element × AP-target, ±3 window — HEURISTIC, see :76)   [:91]
   ⇒ tomorrow (element-PAIR × AP-target A × split a). max of exact solves = proof.
```

**Golden rule (never violate):** never let a spell-count variable meet a mastery variable inside CP-SAT —
that bilinear product is the explosion. The factor-out keeps it out: `rotation = G_e(build) × throughput_e(AP)`,
and `throughput_e` is precomputed, build-independent, injected as a **constant**.

---

## Lot 0 — Boss data + selection

**Goal.** Pick a boss → its per-element resistances fill `DamageScenario.elementResistances` → the existing
boss-aware fused objective (`candidateElements` + `max` over elements) auto-picks the best playable element.

**CLI — ✅ DONE** on `feat/boss-mode-integration` (see `docs/BOSS_MODE_INTEGRATION_PLAN.md` for the detail).
Ported `Monster.kt`, `monsters-v1.91.1.54.json` (715 monsters / 226 bosses), `BossScenario.kt`,
`monsters-extractor`. Added `DamageScenario.againstAllElements(monster)` (the builder that feeds
`candidateElements()` directly), `BossDisplay.turnsToKill`, `WakfuBestBuildFinderAlgorithm.monsters` +
`findMonster`, CLI `--boss` / `--boss-element` / `--boss-difficulty`. **Dropped `BossSearch`** (its per-element
`run()` is subsumed by the fused objective; only the difficulty-aware turns-to-kill survived).
`flatResistanceToPercent` delegates to `common-lib Resistances.flatToPercent` (display ↔ scoring agree).
`--boss-resistances` stays as a manual override for the ~110 bestiary-missing bosses.

**GUI — ⬜ TODO** (the bulk): boss picker (searchable dropdown + resistance profile + difficulty +
turns-to-kill) wired into `main`'s `RequestPanel`/`AppShell`/`BuildSearchModel`/`UiState`/`StatsPanel` +
`i18n/I18n.kt`. **Boss icons:** add `gfx: Int? = null` to `Monster` + parse `entry["gfx"]` in
`MethodWakfuBestiary` (the API returns it; nullable ⇒ current json still deserializes), re-bake the json, add
one `copyAssetDir("monsters")` line to `gui-compose:generateAssets` (source `Vertylo/wakassets/monsters/<gfx>.png`,
the same repo as item icons), render via `rememberClasspathBitmap`. (`bossIllustrations/` is keyed by a
dungeon-boss id, not `gfx` — unused.)

---

## Lot 1 — Cast limits in the fused table (the easy win; 0 solver variables)

**Goal.** Bound the rotation so it stops spamming one spell. No structural solver change.

**What landed (done):**
1. ✅ **Load + join.** `SpellCatalog.spells` joins `spell-cast-limits-v<VERSION>.json`
   (deserialized as the new `common-lib SpellCastLimit`) onto each `Spell` by `id`, populating
   `Spell.maxCastPerTurn` (new field) and `Spell.cooldown`. A new `Spell.maxCastsThisTurn` computed
   property folds those into a single per-turn cap (`cooldown > 0` ⇒ 1; `maxCastPerTurn` `0`/null ⇒
   unbounded), applying the data's "`0` = unlimited" convention.
2. ✅ **Bound `baseThroughputTable`** — now the item-layered bounded knapsack (each spell `s` capped at
   `c_s = maxCastsThisTurn ?: maxAp`); a spell with no limit gets `c_s = maxAp ≥ maxAp/cost`, so it
   reproduces the old unbounded table exactly. `model.addElement(apVar, table, throughput)` is
   **unchanged** — only the table's contents change, so the optimality proof holds. The display path
   (`bestRotation` → generalized `boundedKnapsack(items, apBudget, caps: List<Int>)`) uses the **same**
   per-spell caps, keeping the objective and the re-scorer (`bestSequencedRotation`/`bestAcrossElements`,
   the lockstep guard in `WakfuBuildSolverTest`) consistent.
3. ✅ **Cooldown** (`cooldown > 0` ⇒ cap = 1) folded into `maxCastsThisTurn`. **Per-target** skipped
   (single-target only; `SpellCastLimit.maxCastPerTarget` is parsed but not propagated).

**Deferred — model only (data now present):** WP cost is extracted (`pw_base` → `SpellCastLimit.wpCost`
→ `Spell.wpCost`, joined in `SpellCatalog`; 103/715 player spells cost WP) and carried for display, but
**not yet folded into the rotation**. WP is a per-*fight* pool, so bounding a single turn needs the 1-D
amortized `WP_pool / n_turns` model — with the pool kept a **constant** (not the build's `WAKFU_POINT`
variable) to preserve the build-independence of `baseThroughputTable` (a build-dependent WP cap would
reintroduce the bilinear blow-up). Pure-WP (0-AP) damage spells additionally need a flat **additive**
throughput term — they can't be AP-knapsack items. Open knob: where `n_turns` comes from (boss
turns-to-kill vs. a fixed assumption). **Per-class regen:** the scarce-pool assumption is most wrong for
**Xelor** — base WP is already doubled (12, `Character.kt`) *and* it regenerates WP in-fight
(`Horlogerie`/`Cours du temps` passives, ~+1 WP/turn). That regen is conditional/triggered/scripted in
the passive data (the "state assumed up" bucket Lot 4 defers), so it can't be derived — model WP budget
as a **per-class** policy (`amortized-pool` for most classes; `renewable` / near-unbounded for Xelor and
the niche Eni-Tridelta / Foggernaut-stasis cases) under a documented assumption.

---

## Lot 2 — Bi / multi-element (the core; explosion-free AND provably optimal)

For a pair `{e1,e2}`, total AP `A`, split `a` (AP on e1):

```
turn_damage = resFactor_e1 · T_e1[a] · perHit_e1  +  resFactor_e2 · T_e2[A−a] · perHit_e2
              └──────────── const1 ────────────┘     └──────────── const2 ────────────┘
```

### 2a — The inner objective stays LINEAR (the load-bearing point)

`perHit_e1` / `perHit_e2` are already produced by `perHitDamageScore` [WakfuBuildSolver.kt:1228] — one IntVar
"core" per element, **no spell-count variable inside**, already element-suffixed (designed for this). The
objective becomes `LinearExpr.term(perHit_e1, const1) + term(perHit_e2, const2)` — purely linear.

> **Fatal trap:** do **NOT** route bi-element through `model.addElement(apVar, table, throughput)` [:1192]. That
> call + `addMultiplicationEquality(raw, [throughput, perHitScaled])` [:1200] is the mono-mode bilinear term. In
> bi-element, `T_e[split]` is injected as a literal `newConstant()` → no variable×variable product. Total AP is
> pinned by the existing `maxDamageApTarget` equality [WakfuBuildSolver.kt:587]; the split `a` is the **outer-loop
> index**, not a model variable.

### 2b — Fix the generic / random mastery DOUBLE-COUNT (confirmed; else results are wrong)

`scenarioElementMasteryVar` [WakfuBuildSolver.kt:1265] folds `MASTERY_ELEMENTARY` (the "+all elements" line)
**and** the `*_RANDOM_ELEMENT` lines onto its element (via `elementVars` [:1327]). Building two cores by calling
it twice with `[e1]` then `[e2]` would:
- add generic mastery to **both** cores → counted twice;
- assign a "1 random element" line **in full** to e1 **and** e2 → counted twice.

**Fix:** a **single** `elementVars(wantedElements = listOf(e1, e2), …)` call. The "+all elements" line legitimately
applies *in full to each element's own damage* (not a stat to split); the random line is then split correctly
(`effectiveCount = min(count, 2)`). The element cache key is `(generic, wantedElements-list)`, so `[e1,e2] ≠ [e1]`
— two singleton calls will **not** dedupe. Crit / DI / secondary masteries (rear/distance/berserk) are
build-global and **shared** by both cores — correct; do **not** split them.

### 2c — The enumeration in `MaxDamageSearch` (extend the existing probe loop)

Phase 2 today fans out AP-target probes in parallel [MaxDamageSearch.kt:91, `async(Dispatchers.IO)` :108] and
**explicitly disclaims optimality** (it's a heuristic over a ±3 window, `AP_WINDOW_*` :42-43, see the comment
at :76). Extend it to `(pair, A, split)`. **`OPT = max over (pair, A, a) of CP-SAT_opt` becomes a real proof iff:**

1. **Full reachable AP range, not the ±3 window.** For bi-element the optimal `A` is *not* near the damage-only
   `A₀` (a split needing +2 AP to seat the 2nd element's opener can dominate). Enumerate `A ∈ [6..16]`
   (`MAX_AP_TARGET`).
2. **Split `a ∈ 0..A` inclusive** (the `a=0`/`a=A` ends are mono-element) ⇒ bi-element **dominates** mono, never
   regresses.
3. **Inner solve reaches proven optimality** (`CpSolverStatus.OPTIMAL`) per scenario. A time-sliced solve is a
   valid lower bound (fine in practice) but not a proof — which is exactly why the loop disclaims optimality today.

### 2d — Keep it tractable (worst case ≈ 1100 solves → tens)

Pruning, by impact:
1. **Share one model per pair.** Equipment / skill / rune vars + both `perHit` cores are identical across all
   `(A,a)`; only the two coefficients + the AP-pin change. Build once per pair, re-`Solve` the mutated model →
   construction cost `1100 → 6`.
2. **Pareto frontier of splits.** At fixed `(pair, A)`, a split `(T_e1[a], T_e2[A−a])` is dominated if another
   has `t1' ≥ t1 ∧ t2' ≥ t2`. Keep the frontier → a handful, not `A+1`.
3. **Dead pairs.** All-zero table = the class has no spells in that element (the `return@mapNotNull null` already
   exists [WakfuBuildSolver.kt:1181]) → 6 pairs usually prune to 1–3 live.
4. **AP monotone bound** to skip `A` values that can't beat the incumbent.

Net ≈ 30–200 cheap re-solves of a prebuilt model, parallelized like the current probe loop.

### 2e — Scorer lockstep (else the reported `matchPercentage` lies) — ✅ done

The re-scorer/display **`max`ed over a single element** while the objective **sums** the split. Resolved by a
**single seam**, `SpellRotationOptimizer.bestSequencedTurn(build, char, clazz, scenario, biElement)`: mono ⇒ the
old `bestSequencedRotation`; bi ⇒ `bestSequencedTurnBiElement`, which merges into ONE `SpellRotation`
(`element = null`, each `SpellCast` keeps its own element). `MaxDamageSearch.sequencedScore` and **both** displays
call it, so scored == shown. The winning `BiElementSplit` rides to the display on
`GeneticAlgorithmResult.maxDamageBiElement` (set in `consider`); the total AP is re-derived from the build's real
AP, so nothing else has to be threaded through.
- **Joint debuff-AP accounting (done):** one **shared** debuff subset is paid **once** off the total AP (the data
  exposes no per-element debuff, so a reduction lowers resistance for *both* elements), then the **remaining** AP is
  re-split in the solver's intended A : B proportion — removing the double-spend. Guarded by a `SpellRotationTest`
  asserting no duplicate debuff and `apUsed ≤ totalAp` (SRAM Assassination oracle).
- **`FindMaxDamageScoring` sum-branch: NOT needed** — `computeScore`/`expectedDamage` have **no production caller**
  (the live damage path is entirely `SpellRotationOptimizer`); folding a bi-element branch there would be dead code.

### 2f — Display — ✅ done

A bi-element turn renders as the merged `SpellRotation`: the CLI (`spellRotationReport` / `bossSummary`) groups casts
under per-element sub-headers and labels the header "fire + earth"; the GUI `StatsPanel` groups casts by
`spell.element` under a colored element label (`SpellCastRow` extracted, shared with the mono layout). The single
`effectiveResistancePercent` line is omitted for bi (two elements ⇒ two post-debuff resistances). Note: a live bi
**win** is data-dependent (mono usually wins without hybrid "+all elements" gear / sublimations), so the grouping
branch is validated by the engine test, not a fixed CLI run.

### Multi (k ≥ 3)

Same machinery (k-subset + composition of `A` into k parts), but split count explodes. **Stop at k=2** (the
end-game sweet spot); gate k=3 (Huppermage) behind a flag with k-dim Pareto + dead-pair pruning — rarely optimal.

---

## Lots 3–5 (broader scope — consume Lot 2's scenario gating)

- **Lot 3 — Sublimations on the damage path.** Merge `feat/sublimations` (model already designed:
  `Sublimation.kt` with `SublimationKind` FLAT/STATIC_CONDITIONAL/CONVERSION/COMBAT_CONDITIONAL + `ScenarioGate`).
  Fold `solverChoosable` effects into the build's resolved stats the objective reads; `CONVERSION` as from→to;
  `ScenarioGate` evaluated against the chosen scenario (and each element of a bi-element pair); `COMBAT_CONDITIONAL`
  stays forced-input only. Anti-synergies (e.g. Brutality drains Distance) are explicit via `CONVERSION`.
- **Lot 4 — Passives on the damage path.** Data is on `main` (`spell-passives-v1.91.1.54.json` via `bdata-extractor`)
  but **unwired**. Model with the **same** `SublimationCondition`/`ScenarioGate` vocabulary (reuse, don't reinvent);
  fold solver-modelable effects into resolved stats. Triggered/stacking/build-up passives that change the
  multiplier mid-fight are out of scope for the single-turn proof (model under a documented "state assumed up"
  assumption or leave to forced-input).
- **Lot 5 — Coherence floor (orthogonal; no data dep).** A **survivability floor** (min HP×res EHP + role lock/dodge,
  promoting the existing soft-penalty machinery to a default) so the optimum can't be a turn-1 corpse, and
  **role/positioning presets** replacing the silent `BACK + DISTANCE + uncapped-crit` default (today `orientation =
  BACK` folds rear mastery into build *selection*, not just display). Cap crit% to the reachable ceiling unless a
  class enabler is present. Biggest coherence gain for least effort; touches neither the table nor the enumeration.

---

## Optimality boundary (state it honestly in CLI/GUI/docs)

You **prove**: the best build for a **representative single perfect turn**, under explicit assumptions
(element[-pair], role/positioning, the boss's per-element resistances, a per-turn resource budget), with each
inner solve OPTIMAL and the `(pair × A × split 0..A)` enumeration exhaustive. You **do not prove** multi-turn
dynamics — build-up, inter-turn WP regen, state stacking, ramp (the *build discoverer* vs *fight simulator*
boundary; no static optimizer proves it). **Note:** the external loop currently *already disclaims optimality*
(±3 AP window) — Lot 2c's full-range enumeration is precisely what upgrades it to a proof for the bi-element case.

## Tests / guardrails

- **Objective scale-identity (M1, done):** degenerate splits (`a=0`/`a=A`) of `perTurnDamageScoreBiElement`
  reproduce the mono `perTurnDamageScore` exactly ⇒ bi ≥ mono.
- **Double-count regression (M1, done):** a build with "+all elements" gear + a random-element line — generic
  mastery counted **once** per element core (single `elementVars` call).
- **Enumeration + pruning (M2, done):** `paretoFrontierSplits` keeps only non-dominated interior splits;
  `biElementScenarios` gates dead pairs; the loop streams a positive-damage bi-aware build.
- **Joint shared-debuff (M2e, done):** `bestSequencedTurnBiElement` casts a shared debuff **once**
  (`apUsed ≤ totalAp`, no duplicate debuff) — SRAM Assassination oracle in `SpellRotationTest`.
- **Engine determinism:** every OR-Tools test uses `SolverTuning` (det-time / seed / workers), else CI flakes.

## Sequencing

```
Lot 0 (GUI) ─┐
Lot 1 ───────┼─ (independent, now) ─┐
Lot 5 ───────┘                      ├─► coherent boss-aware mono-element full-damage
Lot 2a-b ─► 2c-d ─► 2e-f ───────────┘   then bi/multi-element
Lot 3 (merge subs) ─┐
Lot 4 (passives)   ─┴─► class-real damage ceiling (consume Lot 2's scenario gating)
```

**Recommended next:** **Lot 2 is complete** (a-f: objective, enumeration + pruning, scorer lockstep, display).
The remaining levers are **Lot 3** (merge sublimations onto the damage path — model already on `feat/sublimations`)
and **Lot 4** (wire the on-`main` passives data), both consuming Lot 2's scenario gating, plus the orthogonal
**Lot 5** coherence floor. Multi-element (k ≥ 3) stays deliberately out of scope (rarely optimal; see below).
