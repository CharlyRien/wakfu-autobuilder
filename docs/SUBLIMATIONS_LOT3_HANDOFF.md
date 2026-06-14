# Lot 3 — Sublimations: exploration & implementation handoff

**Audience:** an exploratory agent (e.g. launched via `/goal`) tasked with researching and then planning/
implementing **solver-chosen sublimations** in the Wakfu Autobuilder. This document front-loads every
piece of context gathered so far so you can start from a running jump, not from zero.

**Branch / worktree:** all prior work lives on `worktree-enchantments-and-damage-mode` (branched from
`ortools-engine-integration`). Lots 1 (runes) and 2 (max-damage mode) are **done, tested, not committed**.
Lot 3 (this doc) is **not started**.

**User's locked decision for Lot 3:** sublimations are **chosen by the solver** from a *curated subset*
of statically-evaluable sublimations (epics/relics with damage/static-conditional effects), via a
hand-maintained effect table. Truly combat-conditional sublimations stay **user-forced input**
(`forcedSublimations`, the sublimation analogue of `forcedItems`).

> Read `docs/ENCHANTMENTS_PLAN.md` first (the original research; §2/§3 cover sublimation data, §8 the
> damage formula). This handoff supersedes its Phase-2 sublimation sketch with concrete data + prior art.

---

## 1. What Lots 1 & 2 already built (the hooks Lot 3 plugs into)

Lot 3 does **not** start from scratch — the engine already has most of the scaffolding:

- **Runes / sockets (Lot 1).** Every `Equipment` now carries `maxShardSlots` (0–4). The solver models
  socket allocation in `WakfuBuildSolver.createRuneModel` → per-`(item, stat)` `runeCount` vars, capped
  by the item's sockets, folded into `StatBuilder.prePercentStat`. **This is exactly the coupling point
  for normal sublimations**, which pin 3 socket colours on a carrier item and therefore constrain rune
  doubling there (`RuneType.slotRawIds` / `doubleBonusPosition`). `BuildCombination` gained
  `runes: Map<Equipment, List<RuneType>>`.
- **`DAMAGE_INFLICTED` stat + max-damage mode (Lot 2).** There is now a real
  `Characteristic.DAMAGE_INFLICTED`, a `ScoreComputationMode.FIND_BUILD_WITH_MAX_DAMAGE`, a configurable
  `DamageScenario` (`autobuilder/.../domain/DamageScenario.kt`), a scorer `FindMaxDamageScoring`, and a
  solver objective `WakfuBuildSolver.buildMaxDamageObjective` that maximizes expected damage
  (`D·Graw`, `D = 100 + ΣDI`). **This is what makes DI-granting sublimations valuable and rankable** —
  the whole reason Lot 3 waited for Lot 2. A `+X% damage` sub feeds straight into `D`.
- **Epic/relic caps already exist.** `WakfuBuildSolver.addBuildValidityConstraints` enforces "≤1 EPIC
  item" and "≤1 RELIC item" (rarity rules). The epic/relic *sublimation slot* caps ("≤1 epic sub, ≤1
  relic sub, on an epic/relic item") mirror this pattern directly.
- **Shared constraint penalty.** `WakfuBuildSolver.applyConstraintPenalty` wraps any "core score"
  (mastery sum or expected damage) with the AP/MP/range shortfall penalty — sublimation contributions
  ride through the same machinery once they feed the stat term loop.
- **Scorer/solver consistency pattern.** `computeCharacteristicsValues` (shared scorer) was extended for
  runes; do the same for sublimation flat-stat contributions so `scoreFor` == the solver objective.

**Key files (cite by function, line numbers drift):**
| Concern | File |
|---|---|
| Solver | `autobuilder/.../genetic/wakfu/WakfuBuildSolver.kt` (`createRuneModel`, `addBuildValidityConstraints`, `StatBuilder.prePercentStat` / `expectedDamageScore`, `applyConstraintPenalty`, `solutionToBuild`) |
| Scorers | `FindMaxDamageScoring.kt`, `computeCharacteristicsValues` in `FindClosestBuildFromInputScoring.kt` |
| Domain | `common-lib/.../Equipment.kt` (`Characteristic`, `ItemType`), `RuneType.kt`, `autobuilder/.../domain/{BuildCombination,DamageScenario}.kt` |
| Extractor | `equipments-extractor/.../EquipmentExtractor.kt` (+ `extractRunes`), `.../dtos/Item.kt` (the `Sublimation` DTO + `SublimationParameters` already parse!) |
| Params/entry | `WakfuBestBuildFinderAlgorithm.kt` (`WakfuBestBuildParams`, resource loading) |
| CLI / UI | `autobuilder/.../Main.kt`, `gui-compose/.../request/RequestPanel.kt`, `state/{UiState,BuildSearchModel}.kt` |

---

## 2. Verified game-data facts (from `items.json` / `states.json` @ `1.91.1.54`)

Confirmed by direct inspection of the Ankama CDN data:

- **Counts:** 467 sublimations total — **32 epic, 47 relic, 388 normal** (the raw count; the curated
  *usable* lists are smaller, see §3).
- **Encoding:** each sublimation is `itemTypeId 812`. Its effect is `equipEffects[].effect.definition.
  actionId == 304` with `params[0]` = a **state id**. `item.sublimationParameters = { slotColorPattern:
  List<Int>, isEpic: Boolean, isRelic: Boolean }`.
- **Slot pattern:** **normal** subs carry a 3-entry `slotColorPattern` using the same colour codes as
  runes (`1`=red, `2`=green, `3`=blue; the items.json sometimes uses `0`/white = wildcard). **Epic and
  relic** subs carry `slotColorPattern: []` — they go in a single **character-wide epic / relic slot**
  (on an epic / relic item), not in socketed colours.
- **⚠️ The effect text is NOT in the game data.** `states.json` exists and is downloadable, but the
  sublimation state entries have **`description == null`** — there is no machine-readable effect. So the
  human-readable effect MUST come from a curated community source (§3). The DTOs (`Item.kt`) already parse
  `812` into a `Sublimation` type, then the extractor discards it — re-emitting `{id, name(fr/en/es/pt),
  slotColorPattern, isEpic, isRelic, stateId}` is straightforward (mirror `extractRunes`).
- **Names** in the game data are French (`title.fr`), with en/es/pt also present.

---

## 3. Prior art (researched — study these before designing)

### 3a. `noredlace/wakfu-sublimations` — THE curated effect table ⭐
<https://github.com/noredlace/wakfu-sublimations> (live: a React site). This is the **single most useful
asset for Lot 3**: hand-curated, deduped, English effect text. Data files:
`wakfu-sublimations/src/assets/data/{epic,relic,sublimations}.json`.

- **`epic-sublimations.json`** (31 entries): `{ "Name", "Effect", "Obtained From", "Since Patch" }`.
  Effect is free English text, e.g.
  `Inflexible → "+15% damage if bearer has 10 AP or less"`,
  `Anatomy → "if highest Elemental Mastery > Rear Mastery: -20% damage inflicted, +40% rear damage"`,
  `Brutality → "if bearer has exactly 0 Range: +20% damage in melee and area"`.
- **`relic-sublimations.json`** (43 entries): same shape. e.g.
  `Ancestral Energy → "with 6 WP+: 4% Damage inflicted, 15 Elemental Resistance"`.
- **`sublimations.json`** (146 normal): `{ "Name", "Socket1","Socket2","Socket3" (G/R/B letters!),
  "Tier1","Tier2","Tier3" (effect per stack level), "MaxLevel", "ObtainedFrom", "SincePatch", "Notes" }`.

**Critical integration gap to solve:** noredlace data is **keyed by English Name + Socket colours
(G/R/B)**, with **no `stateId` and no item id**. The game data (`items.json`) has French names + numeric
id + stateId + numeric `slotColorPattern`. So linking the curated effect to the engine-modelable
sublimation requires **matching by name** (translate fr↔en, or build an id→en-name map from the items.json
`title.en`) and/or by colour pattern. Plan a robust, test-covered matcher; expect a few unmatched/renamed
entries to curate by hand.

### 3b. `eight04/wakfu-autobuild` — direct prior art for the damage objective ⭐
<https://github.com/eight04/wakfu-autobuild> — *"A CLI tool that finds the equipment combination with the
highest damage factor."* Its `lib/solver.js` `calcScore` **matches our Lot 2 formula exactly** (good
cross-check), and adds refinements worth considering:

```js
// eight04 calcScore(factor) — validated identical in spirit to our buildMaxDamageObjective:
mastery         = 100 + baseMastery + factor.mastery
criticalMastery = 100 + baseMastery + baseCriticalMastery + factor.criticalMastery  // (+= mastery)
criticalHit     = clamp(baseCriticalHit + factor.criticalHit, 0, 100)
ap              = baseAp + factor.ap
damageInflicted = 100 + baseDamageInflicted + factor.damageInflicted
score = (mastery*(100-crit) + criticalMastery*crit*1.25)/100  *  ap/6  *  damageInflicted/100
```

Refinements our Lot 2 does NOT yet have (candidate follow-ups, not Lot 3 itself):
- **`ap/6` factor** — more AP ⇒ more casts ⇒ more damage (`--ap-to-damage`). We dropped AP from the
  damage objective; this would re-add it as a multiplier.
- **Multi-element weighting** (`--element` = 1/2/3): an item giving `+100` mastery in N elements counts
  as `100` for a mono build but `floor(100 * N / charElements)` for a tri build. Our `DamageScenario`
  models a single element only.
- **`--range-to-damage`**: +3 DI per range. **`--major`**: treats major points as items to optimize
  (we already optimize skills in CP-SAT). Search uses **Pareto-dominance pruning** (`overpower`) — N/A
  to us (we use CP-SAT), but conceptually interesting.

### 3c. `Tmktahu/WakForge` — the reference web build planner
<https://github.com/Tmktahu/wakforge> (live: wakforge.org). We already transcribed its **rune** value
tables + doubling model (Lot 1, see `RuneType.kt`). For Lot 3, inspect its **sublimation** handling:
`src/models/useStats.js` `calcStateContribution(states, targetEffect)` (how it sums state/sublimation
effects), its sublimation data files, and `src/components/characterSheet/RunesSubsTabContent.vue` (UI:
how it validates the 3-colour pattern against an item's sockets + the epic/relic slot rules).

### 3d. Others
- `Markkop/corvo-astral` (<https://github.com/Markkop/corvo-astral>) — Discord bot with `/subli` (search
  subs by name / slot combo / source) and `/calc` (damage calc). Its CDN→data pipeline is a clean
  reference; the `/subli` data is another curated source to cross-check noredlace against.
- `DroneDD/Wakfu-Rune-Builder` — rune configs + "available sublimations according to equipment rune
  colours" — directly models the **rune ↔ sublimation colour coupling** we need.
- Forum guides: [Epic/Relic Sublimations](https://www.wakfu.com/en/forum/143-guides/231826-epic-relic-sublimations),
  [Ultimate Enchantment Guide](https://www.wakfu.com/en/forum/143-guides/234611-ultimate-enchantment-guide),
  [Fandom: Sublimation](https://wakfu.fandom.com/wiki/Sublimation).

---

## 4. The hard problems Lot 3 must solve (your research/design agenda)

1. **Classify the curated effects into engine-modelable buckets.** Effects are free text. Triage each
   curated sublimation into:
   - **(a) Flat stat** — e.g. "+X% Damage inflicted", "+N Elemental Resistance". → feeds the stat term
     loop directly (becomes valuable in max-damage mode).
   - **(b) Static start-of-combat conditional** — e.g. "+15% DI **if AP ≤ 10**", "if 40+ block: …", "if
     0 range: …", "if highest elem mastery > rear mastery: …". The condition depends on the *build's*
     start-of-combat stats, which the solver knows → modelable with CP-SAT indicator constraints
     (`onlyEnforceIf` on a boolean tied to the condition). These are the **interesting** ones the solver
     can legitimately choose.
   - **(c) Combat-conditional** — "if healed last turn", "for each turn without…", "when the bearer
     suffers…". Not statically knowable → **out of solver scope**; only available as `forcedSublimations`
     input, contributing whatever flat baseline they declare.
   Most epics/relics are (b); most normals are (c) or weak (a). Expect to curate a small high-value subset
   (the famous DI epics: *Inflexible/Inflexibilité*, *Steadfast/Constance*, *Unraveling/Dénouement*,
   *Brutality/Brutalité*, *Anatomy/Anatomie*, etc.) and the relics. Decide the data shape for the curated
   table (suggest a committed `sublimation-effects-vX.json` keyed by sublimation id → structured effect:
   `{ kind, characteristic?, value?, condition? }`).

2. **Linking** (§3a gap): build & test an id ↔ curated-effect matcher (English name from items.json
   `title.en`, fallback colour-pattern + source). Log unmatched entries.

3. **Solver modeling.** Add to `WakfuBuildSolver`:
   - `subVar[s]` booleans for the curated, solver-choosable subset; `Σ epic ≤ 1`, `Σ relic ≤ 1` (mirror
     `addBuildValidityConstraints` epic/relic item caps); per-sub **stack caps** (normal subs stack up to
     a per-sub max — see `MaxLevel` in noredlace).
   - **Epic/relic gating:** an epic sub requires an epic item equipped (≤1 epic item already enforced);
     ditto relic. Tie `subVar` to the presence of an epic/relic carrier.
   - **Normal-sub colour-pattern reservation:** choosing a normal sub pins 3 socket colours on one
     carrier item → those sockets can no longer freely double runes. Couple with Lot 1's `runeCount`
     (this is the rune↔sub interaction the plan flagged; first pass may approximate or ignore, but design
     for it).
   - **Static-conditional effects (bucket b):** model the condition as a boolean (e.g. `apActual ≤ 10`)
     and enforce the effect only when both `subVar` and the condition hold. CP-SAT `addLinearExpression…
     onlyEnforceIf` patterns; the solver then trades stats to *satisfy* lucrative conditions.
   - Feed all chosen-sub stat/DI contributions into the term loops so `buildMaxDamageObjective` values
     them and `scoreFor`/`computeCharacteristicsValues` stay consistent.
   - `BuildCombination` gains `sublimations: List<Sublimation>`; `solutionToBuild` reconstructs them.

4. **Inputs / surfaces.** `forcedSublimations: List<String>` on `WakfuBestBuildParams` (conditional subs
   the player declares). CLI `--forced-sublimations` + output of chosen subs. Compose: show chosen subs +
   a forced-sublimations picker (reuse the forced-items chip pattern).

5. **Extractor.** Re-emit `sublimations-vX.json` (mirror `extractRunes`'s `811` branch for `812`). Note:
   unlike Lot 2, Lot 3 **does** need a (cheap) extractor change + a committed curated-effects file.

---

## 5. Suggested first moves for the exploratory agent

1. **Read** the Lot 1+2 diff on this worktree (`git diff ortools-engine-integration`) to internalize the
   rune/socket model and the max-damage objective — Lot 3 mirrors both.
2. **Pull** the three noredlace JSONs + WakForge `useStats.js` `calcStateContribution` + a WakForge
   sublimation data file; line up ~10 high-value epic/relic DI subs end-to-end (game id → curated effect
   → proposed structured representation).
3. **Prototype the matcher** (items.json id/en-name ↔ noredlace Name) and report the match rate +
   unmatched list.
4. **Design** the curated `sublimation-effects` schema (kinds a/b/c) and the CP-SAT modeling for buckets
   a + b, then write a phased plan (extractor → curated table + matcher → domain model → solver selection
   + caps → static-conditional modeling → colour-pattern/rune coupling → CLI/UI → tests).
5. Keep scope honest: **bucket (c) is forced-input only**; the solver-chosen value is buckets (a)+(b),
   realized mainly through the **max-damage** objective.

## 6. Gotchas carried over

- OR-Tools engine tests need the JVM args in `AGENTS.md` §4 (`--enable-native-access=ALL-UNNAMED`, the
  `--add-opens`). Engine tests must stay deterministic (small pools / exhaustive comparison, as in
  `WakfuBuildSolverTest`).
- Item/sub names are matched in **French** in the engine's filtering (`forcedItems`), but the curated
  effect data is **English** — keep the two name spaces explicit.
- Run `./gradlew ktlintFormat` before finishing; don't commit/push unless asked (default branch `main`).
- Modeling assumption stays **best-achievable / BiS** (see memory `autobuilder-optimistic-modeling`):
  assume the player can roll the colours / run the subs a committed player would.

## 7. Source index
- Curated data: noredlace/wakfu-sublimations `src/assets/data/{epic,relic,sublimations}.json`.
- Damage prior art: eight04/wakfu-autobuild `lib/solver.js` (`calcScore`/`calcItemFactor`), `README.md`.
- Reference planner: Tmktahu/wakforge `src/models/useStats.js`, `src/components/.../RunesSubsTabContent.vue`.
- Game data: `https://wakfu.cdn.ankama.com/gamedata/1.91.1.54/{items,states}.json` (subs = itemTypeId 812,
  effect actionId 304 → params[0] = stateId; states have null descriptions).
- In-repo: `docs/ENCHANTMENTS_PLAN.md` (§2/§3 data, §8 formula), this file, and the Lot 1+2 diff.
