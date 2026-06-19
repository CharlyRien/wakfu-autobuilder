# Lot 3 — Sublimations: research findings & phased implementation plan

> 🛑 **SUPERSEDED (2026-06-19) — historical context only.** The WakForge / noredlace / hand-curated pipeline
> described below (and the `docs/sublimations-research/` scripts + data) has been **removed**. Sublimations
> are now generated entirely from first-party sources by `bdata-extractor`: effects / build-static condition /
> scenario gates / max level from the local State (67) → StaticEffect (68) tables (`SublimationBuilder.kt`),
> identity / name / rarity / slot-colours from the CDN `items.json`. The `SublimationKind` / condition
> vocabulary and the solver-modeling design in §4 are still accurate and in use; the data-sourcing sections
> (§1, §2) are obsolete. See `AGENTS.md` §5 and `SublimationReproductionTest`.

**Status:** RESEARCH PHASE COMPLETE (no solver wiring yet — by design).
**Author:** parallel research session, worktree `research/sublimations-lot3` (branched from `d83396c`).
**Supersedes the open questions in** `docs/SUBLIMATIONS_LOT3_HANDOFF.md` (read that first for framing).

This document reports what the research phase established: the data sources, a working EN↔FR/stateId
**matcher (95.7% automatic, 100% with ~10 hand-curated)**, the **effect-table schema**, the CP-SAT
modeling design for the solver-choosable buckets, and a **phased implementation plan**. All data,
prototype scripts and the join report live under `docs/sublimations-research/`.

> ⚠️ **Git reality correction (important).** The handoff assumed Lots 1 & 2 were both uncommitted on
> `worktree-enchantments-and-damage-mode` (`f838242`). That is now stale. As of this research:
> - **Lot 1 (runes) is MERGED into `main`** (release 1.3.0 = `d83396c`): `common-lib/RuneType.kt`,
>   `Equipment.maxShardSlots`, `BuildCombination.runes`, `WakfuBuildSolver.createRuneModel`, **and the
>   `Sublimation` DTO in `equipments-extractor/.../dtos/Item.kt` already parse itemTypeId 812**.
> - **Lot 2 (max-damage) is the uncommitted working tree of `feat/max-damage-mode`** (= main + Lot 2):
>   `Characteristic.DAMAGE_INFLICTED`, `ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE`,
>   `domain/DamageScenario.kt`, `FindMaxDamageScoring.kt`, `WakfuBuildSolver.buildMaxDamageObjective`.
>
> So Lot 3 plugs into a **stable, committed rune model** (from main) plus the **Lot-2 max-damage
> objective** (to be committed on `feat/max-damage-mode`). Lot 3's final wiring merges onto
> `feat/max-damage-mode` once Lot 2 is committed.

---

## 0. TL;DR for the implementer

1. **Join on numeric `stateId`, not names.** 467 sublimation item rows in `items.json` collapse to
   **232 distinct effects** by `stateId` (each effect ships as 1–3 item rows = different drop sources).
2. **WakForge `state_data.json` is the primary, machine-readable source** — keyed by `stateId`, with
   per-level numeric values and templated text. It covers **222/232 (95.7%)** of distinct subs.
   The remaining **10 are the newest subs** (`stateId ≥ 9060`); hand-curate them → 100%.
3. **noredlace is the secondary/human-readable cross-check** (204/232 by name). Keep it for tooltips
   and to catch WakForge/game discrepancies (e.g. the two sources disagree on **Anatomy**).
4. **Three engine buckets** (per handoff) + a **fourth structural kind, CONVERSION** that the research
   surfaced (Unraveling: crit→elem mastery; Anatomy: elem→rear mastery).
5. **Ship epic/relic first.** They use a dedicated character-wide slot (`slotColorPattern == []`), so
   they have **no rune-socket colour conflict** — the cleanest, highest-value first phase. The famous
   DI epics (Inflexibility, Steadfast, Brutality, Outrage, …) are all here and all CP-SAT-modelable.
   Normal (colour-coupled) subs are a later phase because they reserve 3 rune sockets on a carrier.

---

## 1. Data sources (all downloaded into `docs/sublimations-research/data/`)

| File | Origin | Role |
|---|---|---|
| `items-full.json` | Ankama CDN `gamedata/1.91.1.54/items.json` (15 MB) | game truth: ids, fr/en names, `slotColorPattern`, `isEpic/isRelic`, `stateId` |
| `subs-game.json` | extracted (jq) from the above | **467** sub rows, the engine-side input |
| `states-full.json` | Ankama CDN `states.json` | `stateId → title{fr,en,…}` only — **NO effect text** (confirms handoff) |
| `wakforge-state_data.json` | WakForge `src/models/state_data.json` | **structured per-level effect data, keyed by `stateId`** — the primary source |
| `wakforge-en_states.json` | WakForge `statesTranslations/en_states.json` | text templates (`"5073_1": "{num_0}% damage inflicted"`) |
| `wakforge-useStats.js` / `useStates.js` | WakForge | `calcStateContribution` reference (how WakForge sums state effects) |
| `epic-/relic-/sublimations.json` | noredlace `wakfu-sublimations` | curated **English free-text** effects (31/43/146) — human-readable cross-check |

**Key encoding facts (verified):** sub = `itemTypeId 812`; effect = `equipEffects[].effect.definition`
with `actionId == 304`, `params[0]` = `stateId`. `sublimationParameters = {slotColorPattern, isEpic,
isRelic}`. Normal subs carry a 3-entry `slotColorPattern` (codes `1=RED,2=GREEN,3=BLUE`, matching
`common-lib/RuneColor`); epic/relic carry `[]` (dedicated slot). The **state title differs from the
item title** (item "Inflexibility" → state 5073 "Inflexible") — relevant only for name-joins.

---

## 2. The matcher (deliverable #3) — `docs/sublimations-research/matcher_prototype.py`

**Result (run `python3 matcher_prototype.py`; full table → `matcher_report.json`):**

```
distinct sublimations (by stateId): 232      (from 467 raw item rows)
coverage:
  WakForge structured (stateId join):  222/232  (95.7%)   <-- PRIMARY, machine-readable
  noredlace text (name join):          204/232  (87.9%)   <-- secondary, human-readable
  either source:                       222/232  (95.7%)   (noredlace ⊂ WakForge)
by kind:   epic 31/32 · relic 43/47 · normal 148/153 (WakForge)
```

**The 10 unmatched-by-either** (need hand-curation — all newest, `stateId ≥ 9060`):
`Secondary Embellishment`, `Surge`, `Judgment`, `Diagnosis`, `Critical Save II` (normal);
`Wakfu Seal`, `Single Block`, `Absolution`, `Punishment` (relic); `Critical Secret` (epic).

**Why the numeric join wins:** names are noisy across three spaces (item title.en, state title.en,
noredlace `Name` with `(old …)` rename annotations). `stateId` is the stable key shared by game data
and WakForge, so the primary table needs **zero** name-matching. noredlace's 17 unmatched entries are
all `"X (old Y)"` renames already covered by WakForge via `stateId`; stripping the parenthetical would
lift the text cross-check but is unnecessary for the engine table.

**Risk #1 verdict: substantially de-risked.** 95.7% automatic structured coverage; the 10-entry tail
is a known, finite, one-time hand-curation (mostly high-value relics/epic worth doing anyway).

---

## 3. End-to-end trace of 10 high-value DI subs (deliverable #2)

`game id → stateId → WakForge structured effect → proposed structured representation`.
(Run `python3 resolve_effects.py` for the live trace; level-1 values shown.)

| state | item.en | condition (bucket) | effect → engine representation |
|---|---|---|---|
| 5073 | Inflexibility (EPIC) | AP ≤ 10 *(b)* | **+15% `DAMAGE_INFLICTED`**; +10 ForceOfWill if char lvl ≥100 |
| 5074 | Steadfast (EPIC) | crit ≤ 10% *(b)* | **+20% `DAMAGE_INFLICTED`**; +50 crit-resist |
| 5075 | Measure (EPIC) | block ≥ 40% *(b)* | +10% crit; +10% DI **on critical hit** *(scenario-gated)* |
| 5446 | Brutality (EPIC) | range == 0 *(b)* | **+20% `DAMAGE_INFLICTED`** gated `{rangeBand∈melee, area}` |
| 6705 | Outrage (EPIC) | dodge < 100%·level *(b)* | **+15% DI** gated `{berserk}`; +lock |
| 6706 | Outrage II (EPIC) | range ≥ 4 *(b)* | **+15% DI** gated `{berserk, ranged}`; +1 range |
| 6708 | Controlling Space (EPIC) | range == 1 *(b)* | +15% DI on targets 2/4 cells *(scenario-gated)* |
| 5249 | Wield type: Dagger (EPIC) | dagger equipped *(b, item-type)* | +25% DI front / +15% side / −30% back *(orientation-gated)* |
| 5216 | Directives (RELIC) | — *(a, flat-ish)* | +15% DI by area spells in-line *(scenario-gated)* |
| 5450 | Stasification (RELIC) | per-turn ramp *(c)* | +2%/turn DI (max 20) — **forced-input only** |
| 5077 | Unraveling (EPIC) | crit ≥ n *(b, CONVERSION)* | convert n% crit-mastery → elem-mastery |

**Design facts this trace establishes:**
- Conditional DI is gated on **two** independent things: a **build-static predicate** (AP/crit/block/
  range/dodge/weapon — solver-knowable) **and** a **scenario gate** (melee/area/back/berserk/ranged —
  already encoded in Lot 2's `DamageScenario`). The solver can evaluate both at solve time.
- Effects can grant **secondary stats** (ForceOfWill, crit-resist, lock) irrelevant to the damage
  objective — record them, but only DI/mastery move `buildMaxDamageObjective`.
- **CONVERSION** (Unraveling/Anatomy) is a genuinely different shape: it *moves* value between two stat
  terms. Modelable in CP-SAT but more involved → later phase.

---

## 4. The curated effect-table schema (deliverable #4)

Commit a hand-maintained, WakForge-seeded resource `sublimation-effects-v<VERSION>.json` loaded the
same way as `runes-v<VERSION>.json` (lazy classpath resource in `WakfuBestBuildFinderAlgorithm`).
One entry per **distinct `stateId`**:

```jsonc
{
  "stateId": 5073,
  "names": { "fr": "Inflexibilité", "en": "Inflexibility", "state": "Inflexible" },
  "rarity": "EPIC",                       // EPIC | RELIC | NORMAL  (from isEpic/isRelic)
  "slotColorPattern": [],                 // [] epic/relic ; [2,1,3] normal (RuneColor codes)
  "maxLevel": 1,                          // 1 epic/relic ; up to 6 normal (stacking)
  "kind": "STATIC_CONDITIONAL",           // FLAT | STATIC_CONDITIONAL | CONVERSION | COMBAT_CONDITIONAL
  "solverChoosable": true,                // a + b (+ some conversions) = true ; c = false (forced-only)
  "condition": { "type": "AP_AT_MOST", "value": 10 },   // null for FLAT
  "effects": [
    { "characteristic": "DAMAGE_INFLICTED", "value": 15, "scenarioGate": null },
    { "characteristic": "FORCE_OF_WILL",    "value": 10, "scenarioGate": { "minCharacterLevel": 100 } }
  ],
  "source": "wakforge:5073",
  "rawText": { "en": "At the start of combat, if 10 AP or less: 15% damage inflicted; …" }
}
```

### 4a. Condition vocabulary (finite — enumerated by `resolve_effects.py`)
The non-indented "condition" lines across all 222 WakForge subs cluster into **~50 distinct templates**,
most appearing 1–8×. The **build-static** subset (bucket b) is small and closed:

```
AP_AT_MOST(n) · AP_AT_LEAST(n) · AP_EXACT(n) · AP_ODD
CRIT_AT_MOST(n) · CRIT_AT_LEAST(n)
BLOCK_AT_LEAST(n)
RANGE_AT_MOST(n) · RANGE_AT_LEAST(n) · RANGE_EXACT(n)
DODGE_LT_PCT_OF_LEVEL(n) · SECONDARY_MASTERIES_AT_MOST(n)   // melee+distance ≤ n
WEAPON_TYPE_EQUIPPED(dagger|shield|two_handed)
HIGHEST_ELEM_MASTERY_GT(rear|healing)                       // needs max-over-elements var
```

> **Recommendation:** assign `kind`/`condition` via a **hand-authored template→spec map over the ~50
> templates** (a few hours, robust), NOT regex heuristics. The prototype `classify.py` uses regex and
> is deliberately rough — it under-counts bucket b (misfiles `Unraveling`/`Anatomy` as "manual" because
> their phrasing is "at the start of combat **if**" without the comma, and they are conversions). Its
> value is enumerating the vocabulary, not the final buckets.

### 4b. The three+one buckets and how the solver treats them
- **(a) FLAT** — unconditional stat. Trivial: `contribution = value · subVar` folds into the stat loop.
- **(b) STATIC_CONDITIONAL** — start-of-combat predicate on build-static stats → CP-SAT indicator.
- **(CONVERSION)** — move value between two stat terms when condition holds (later phase).
- **(c) COMBAT_CONDITIONAL** — temporal/in-combat ("per turn", "when suffering", "every N spells",
  Brazier's rotating conditions). **Out of solver scope** → available only as **forced input**,
  contributing whatever flat baseline (if any) it declares.

**Rough scope (from `classify.py`, buckets to be finalized by the template map):** a clearly-modelable
core exists today — the marquee DI epics are all bucket b — with **≥16 damage-relevant solver-choosable
subs** even on the conservative regex cut, and more once `a_flat_or_manual` (75) is hand-triaged.
112 are combat-conditional (forced-only). 10 need hand-curation.

---

## 5. CP-SAT modeling design (for the eventual wiring — NOT done here)

Everything mirrors Lot 1's rune model and Lot 2's objective. Cited coupling points (line numbers
drift — cite by function):

1. **Selection vars** — `subVar[s]: BoolVar` for each `solverChoosable` sub. Build a
   `createSublimationModel(...)` next to `createRuneModel` (`WakfuBuildSolver.kt`).
2. **Caps & carrier gating** — mirror `addBuildValidityConstraints`' epic/relic **item** ≤1 rule:
   `Σ epic subVar ≤ 1`, `Σ relic subVar ≤ 1`; and **gate on a carrier**: an epic sub needs an epic item
   equipped (`epicSubVar ≤ Σ epicItemVars`), ditto relic. (Epic/relic items are already ≤1 each.)
3. **FLAT contribution** — add `Term(subVar, value)` into `StatBuilder.prePercentStat(char)` exactly
   where runes add `Term(runeVar, coefficient)` (the loop at `prePercentStat`, the documented coupling
   point). Then `buildMaxDamageObjective` values DI subs for free (it reads `actualStat(DAMAGE_INFLICTED)`
   through `prePercentStat`), and the scorer stays consistent if `computeCharacteristicsValues` adds the
   same contribution.
4. **STATIC_CONDITIONAL** — reify the predicate as `condVar` (e.g. `apActual ≤ 10` via a reified linear
   constraint), then `appliesVar = AND(subVar, condVar)` (`addMultiplicationEquality` or
   `addBoolAnd … onlyEnforceIf`), and add `Term(appliesVar, value)`. The solver will trade stats to
   *satisfy* lucrative conditions — the whole point. Scenario gates (`{berserk}`, `{rangeBand}`,
   `{orientation}`) are **compile-time constants** for a given `DamageScenario`, so a gated effect whose
   scenario doesn't match is simply dropped from the term list (no var needed).
5. **Scorer parity** — extend `computeCharacteristicsValues` (shared by `FindMaxDamageScoring` /
   `FindClosestBuildFromInputScoring`) to fold the chosen subs' contributions, so `scoreFor ==` solver
   objective (the same discipline runes followed).
6. **Output** — `BuildCombination` gains `sublimations: List<Sublimation>`; `solutionToBuild`
   reconstructs from `subVar` values (mirror the rune reconstruction).
7. **NORMAL subs (later phase)** — choosing a normal sub pins 3 socket colours on **one carrier item**,
   reducing that item's free rune doubling. Couple with Lot 1's `runeCount` / `slotRawIds` /
   `doubleBonusPosition`. The colour codes already align (game `slotColorPattern` 1/2/3 ↔ `RuneColor`).
   First pass may approximate (best-achievable: assume the player can host it) but the value of normal
   subs is lower and the coupling is the real complexity — defer.

---

## 6. Phased implementation plan (deliverable #5)

Each phase is independently shippable & testable. Phases 1–5 deliver the marquee value (solver-chosen
epic/relic DI subs). Phases 6–7 add normal subs + conversions.

- **Phase 0 — Extractor (cheap).** Re-emit `sublimations-v<VERSION>.json` from `items.json`: mirror the
  rune branch for `itemTypeId 812`, **dedup by `stateId`**, emit `{stateId, names, rarity,
  slotColorPattern, maxLevel}`. (`equipments-extractor`; the `Sublimation` DTO already parses.)
  *Artifact: the dedup logic + the prototype `subs-game.json` shape are already proven here.*

- **Phase 1 — Curated effect table.** Add `generate_draft_table.py`-style seeding (this research ships a
  prototype, §7) → `sublimation-effects-v<VERSION>.json`. Hand-author the **template→{kind,condition}**
  map over the ~50 templates; hand-curate the **10** newest subs; cross-check against noredlace; commit
  the file. Scope it to **EPIC + RELIC** first.

- **Phase 2 — Domain model.** `common-lib`: `Sublimation`, `SublimationCondition` (sealed/enum),
  `SublimationEffect`, `ScenarioGate`. `BuildCombination.sublimations`. Load the resource in
  `WakfuBestBuildFinderAlgorithm` like `runes`. `WakfuBestBuildParams.useSublimations`,
  `forcedSublimations: List<String>` (French names, like `forcedItems`).

- **Phase 3 — Solver: FLAT + selection + caps.** `createSublimationModel`, epic/relic ≤1 + carrier
  gating, FLAT contributions into `prePercentStat`, reconstruct in `solutionToBuild`. Scorer parity in
  `computeCharacteristicsValues`. Tests: a forced DI relic raises expected damage; ≤1 epic/relic holds.

- **Phase 4 — Solver: STATIC_CONDITIONAL (the interesting bit).** Reified condition vars + `appliesVar`
  + scenario-gate compile-time drop. Tests (deterministic `SolverTuning`): Inflexibility chosen ⇒
  build keeps AP ≤ 10 and damage reflects +15% DI; Steadfast ⇒ crit ≤ 10%. Verify the solver *declines*
  a conditional sub when the condition costs more than it gives.

- **Phase 5 — Surfaces.** CLI `--forced-sublimations` + print chosen subs (mirror the rune ASCII table
  in `Main.kt`). Compose: show chosen subs in `StatsPanel`/paperdoll + a forced-sub picker reusing the
  forced-items chip pattern (`RequestPanel`, `i18n/I18n.kt` `Tr` enum).

- **Phase 6 — CONVERSION subs** (Unraveling, Anatomy, …): move value between two stat terms under the
  static condition. Reconcile WakForge vs noredlace on Anatomy (prefer the live game = WakForge).

- **Phase 7 — NORMAL subs** (colour-coupled): stacking count vars (0..maxLevel), colour-pattern socket
  reservation coupled to the rune model. Highest complexity, lowest marginal value — last.

**Merge:** Lot 3 lands on `feat/max-damage-mode` once Lot 2 is committed there (Lot 3 depends on
`DAMAGE_INFLICTED` + `buildMaxDamageObjective`).

---

## 7. Reproducing / artifacts

```
docs/sublimations-research/
  data/                      all downloaded sources (see §1)
  matcher_prototype.py       stateId/name join + coverage report   -> matcher_report.json
  resolve_effects.py         WakForge template resolver: DI trace + condition vocabulary
  classify.py                rough a/b/c bucket scope counts (regex; to be replaced by template map)
  generate_draft_table.py    DRAFT auto-seed of the effect table for epic/relic (proves the pipeline)
  matcher_report.json        full 232-row join table (committed)
```

## 8. Decisions taken autonomously (no user prompt, per instructions)
- **Primary source = WakForge `state_data.json` joined on `stateId`** (machine-readable, 95.7%), with
  noredlace as human-readable cross-check. Rationale: numeric join is robust; structured values feed
  CP-SAT directly; dual-source catches discrepancies (Anatomy).
- **Dedup the 467 item rows to 232 effects by `stateId`** as the engine unit.
- **Ship epic/relic before normal subs.** No colour-socket conflict, holds the marquee DI value.
- **Bucket assignment via a hand-authored template→spec map**, not regex (the regex prototype is for
  vocabulary discovery only).
- **`maxLevel`** taken from WakForge per-level tables (epic/relic = 1; normal up to 6).
- **Modeling stays best-achievable / BiS** (consistent with `autobuilder-optimistic-modeling`): assume
  the player can host a chosen sub; scenario gates come from the configured `DamageScenario`.
```
