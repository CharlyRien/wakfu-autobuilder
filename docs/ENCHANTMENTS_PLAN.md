# Enchantments (Runes + Sublimations) — Engine Integration Plan

**Goal:** let the autobuilder account for Wakfu's **enchantment system** when searching builds —
primarily **runes** (per-item socketed stats), and optionally **sublimations** (special
combination/epic/relic effects). This document is the research + the concrete, phased plan.

**Status:** plan validated, **not yet implemented**. Game data is already current
(`1.91.1.54`); no version bump required to start.

**Decisions locked:**
- **Runes are always at max level** for the character's level, and **doubling uses WakForge's exact
  model** (a rune doubles on its 2 favoured equipment slots — see §4e/§5). This is the *best
  achievable* setup, which is what an autobuilder should target — not a conservative lower bound.
- **Sublimations:** out of scope for the mastery objective. The real home for them is a **future
  "max-damage" objective mode** built on Wakfu's exact damage formula (§4 Phase 2c). Genuinely
  combat-conditional sublimations stay **user-forced input** (like forced items).

**Read first:**
- `AGENTS.md` §4 (the OR-Tools CP-SAT solver) and §5 (the data pipeline).
- This branch (`ortools-engine-integration`) uses the **OR-Tools CP-SAT** engine
  (`autobuilder/.../genetic/wakfu/WakfuBuildSolver.kt`), not the genetic algorithm.

> **Headline finding:** the data is *already downloaded*. Your `equipments-extractor` pulls
> `items.json`, which **already contains runes and sublimations** — it parses them and then throws
> them away. Runes drop almost perfectly into the existing solver. Sublimations mostly do not (their
> effects are conditional combat states, not flat stats).

---

## 1. How enchantments work in-game

### Sockets ("châsses")
- Each equipment piece can have **0–4 sockets**. In the current data, **4 sockets** for 7065 items,
  **0** for 775 (pets, mounts, some small items). 2H weapons / daggers / shields have special rules.
- Every socket has a **colour**: red (▢), green (⬠), blue (△), or **white (○ = wildcard)**.
- Socket colours are **rolled randomly at identification and are re-rollable** by the player at an
  enchantment workshop. → **Colours are NOT a fixed property of an item.** For "best-in-slot"
  theory-crafting we assume the player arranges colours as desired.

### Runes (a.k.a. shards / "éclats")
You socket a rune into a socket to add a stat. There are **17 runes**, one per stat. Each rune:
- has a **colour** (1=red / 2=green / 3=blue),
- has a **level (1→10)**, gated by character level (`shardLevelRequirement = [0,36,51,66,81,96,126,141,171,186,216]`),
  whose magnitude follows a **global curve** `shardLevelingCurve = [1,2,4,8,16,32,64,192,576,2304,9216]`,
- **doubles** its value when the rune colour matches the socket colour (white sockets match any).
  Because colours are re-rollable, in BiS the player always matches → runes are effectively doubled.

### Sublimations
Scrolls that add a **unique effect** (usually conditional). Three families:
- **Normal** — require a **specific ordered 3-colour socket pattern** on **one item**
  (e.g. *Carnage II* = `[red, red, green]`). Stackable up to a per-sub cap.
- **Epic** — a **single character-wide epic slot** (on an epic item).
- **Relic** — a **single character-wide relic slot** (on a relic item).

Many sublimation effects are **conditional on combat** (e.g. "if healed last turn", "if only one
element used this turn") → **not representable** by a static-stat solver. A subset condition on
**static start-of-combat stats** (AP ≤ 10, block ≥ 40, range ≤ 1, crit ≤ 10…) which *are* knowable
from the build.

---

## 2. Data situation

The extractor already downloads `items.json` and **parses** runes (`itemTypeId 811`) and
sublimations (`812`), then discards them at output:
[Item.kt:26-31](../equipments-extractor/src/main/kotlin/me/chosante/equipmentextractor/dataretriever/dtos/Item.kt#L26).
The DTO already captures everything we need
([Item.kt:88-139](../equipments-extractor/src/main/kotlin/me/chosante/equipmentextractor/dataretriever/dtos/Item.kt#L88)):

- **Rune (811):** `shardsParameters { color, shardLevelingCurve, shardLevelRequirement, doubleBonusPosition }`
  + `equipEffects[].effect.definition.actionId` (the stat).
- **Sublimation (812):** `sublimationParameters { slotColorPattern, isEpic, isRelic }`
  + effect = `actionId 304` (a **state id** in `params[0]`).
- **Equipment:** `baseParameters.maximumShardSlotNumber` (=4); **no colours** (RNG, per-instance).

Counts in `1.91.1.54`: **17 runes**, **467 sublimations** (388 normal / 32 epic / 47 relic; the
curated community lists usable counts are ~146 / 31 / 43, the rest being obsolete dupes).

Three consequences:

1. **15 of 17 runes map directly onto the existing `Characteristic` enum** — **no new stat type
   needed**. The 2 exceptions (single-target / area mastery → `actionId 400`) have no enum entry and
   are **dropped by WakForge too** → drop them. See the mapping table in §6.
2. **Socket colours are not in the data** → a modelling assumption must be fixed (see §5).
3. **Sublimation effects are not flat stats** — `items.json` only gives the **state id**; the
   human-readable effect lives in `states.json` (downloadable) and the logic is conditional. A
   **hand-curated `stateId → effect` table** is required to use them at all
   ([noredlace/wakfu-sublimations](https://github.com/noredlace/wakfu-sublimations) provides the
   effect text, deduped).

Current data is already at `1.91.1.54`
([equipments-v1.91.1.54.json](../autobuilder/src/main/resources/equipments-v1.91.1.54.json)); CDN
`config.json` confirms the same version.

---

## 3. Feasibility analysis

### Runes → yes, clean and faithful ✅
The solver computes each stat as
`prePercentStat[char] = base + Σ_selected items·value + Σ skills` (term loop at
[WakfuBuildSolver.kt:~1054](../autobuilder/src/main/kotlin/me/chosante/autobuilder/genetic/wakfu/WakfuBuildSolver.kt#L1054)).
Runes are just **extra per-item allocatable stats** — they plug straight into this loop. Variable
count is tiny (see §4c).

### Sublimations → mostly no ❌ (state this plainly)
- The solver maximises **masteries / static stats**, not **final damage** or combat effects. Most
  sublimation effects (`+X% damage if…`) have no `Characteristic` equivalent and depend on combat
  context → the solver cannot **value** them, hence cannot **choose** them.
- **Recommendation:** treat sublimations as **user input** (the player declares which ones they run)
  → the engine (a) adds any flat-stat contribution, (b) **reserves the 3-colour pattern** on an item
  (which constrains rune colour-matching there). Letting the solver *pick* sublimations is an
  advanced Phase 2b, limited to a curated subset evaluable on static stats.
- **The real unlock is a "max-damage" objective mode** (Phase 2c): once the objective is *expected
  damage* via Wakfu's exact formula, `+%damage` and static-conditional sublimations become
  rankable and the solver can choose them. Combat-conditional ones remain forced input.

---

## 4. Phased plan

### Phase 0 — Data (essentially done)
- Data already at `1.91.1.54`; the extractor already parses 811/812. Nothing to bump to start. ✔️

### Phase 1 — Runes (the core feature; tractable)

**a) Extractor** — export runes instead of discarding them
([EquipmentExtractor.kt](../equipments-extractor/src/main/kotlin/me/chosante/equipmentextractor/EquipmentExtractor.kt)):
- Handle the `811` branch → emit a `runes-v<version>.json` resource (sibling of the equipments
  resource): `{ id, name (fr/en/es/pt), color (1/2/3), characteristic, levelingCurve,
  levelRequirement, doubleBonusPosition, gfxId }`.
- Map `actionId → Characteristic` using the table in §6. Drop `actionId 400` (single-target/area).

**b) common-lib** — new model:
- `RuneColor { RED, GREEN, BLUE }` and
  `RuneType { id, name, color, characteristic, valueAt(level: Int, doubled: Boolean): Int }`.
- Extend the build result so chosen runes are reconstructable/displayable, e.g.
  `BuildCombination` gains a `runes: Map<Equipment, List<RuneAssignment>>`
  (`RuneAssignment { runeType, doubled }`).

**c) Solver (CP-SAT)** — simple, bounded formulation:
- For each socketable item `i` (maxSlots=4) and each **requested, rune-coverable** stat `s`, create
  an integer var `runeCount[i][s] ≥ 0`.
- Constraints:
  - `Σ_s runeCount[i][s] ≤ 4` and `Σ_s runeCount[i][s] ≤ 4 · selected[i]`
    (sockets only count when the item is equipped; linear because `selected[i]` is boolean).
- Stats: in the `prePercentStat[s]` term loop, add `Term(runeCount[i][s], runeValue(s))` for each `i`.
  Runes then contribute exactly like item stats — objective and scoring need no special-casing.
- **Double-bonus decision** (must be fixed — see §5).
- Cost: ~14 items × a few requested stats ⇒ a few dozen integer vars + linking constraints.
  Negligible for CP-SAT.

**d) Scorer + UI** — `scoreFor()` recomputes the final stat from the combination, so including runes
there is automatic once they're in the model. In the Compose UI, render the chosen runes per
equipment card (rune `gfxId` → icon, same mechanism as item icons).

**e) `runeValue` — solved (transcribe WakForge's tables).** The CDN `shardLevelingCurve`
(`[1,2,4,…,9216]`) is an **internal coefficient, not the displayed stat** — Ankama's transform from
it to the shown value is undocumented and irregular. So the practical answer (and what every
community tool does) is to use the **final per-level value tables** directly. WakForge already
transcribed them from in-game (`useConstants.js`); copy them verbatim. See the tables in §6.

`runeValue(stat, itemSlot)` is then a **constant** (given character level), no extra variable:
```
level     = max rune level allowed by character level   (RUNE_LEVEL_REQUIREMENTS, §6)
baseValue = LEVEL_TABLE(stat)[level - 1]                 (one of the 6 tables, §6)
runeValue = baseValue * (itemSlot.rawId ∈ rune.doubleBonusPosition ? 2 : 1)
```
i.e. each rune doubles **only on its 2 favoured equipment slots** (the slots whose native colour
matches the rune's colour — encoded in `doubleBonusPosition`), base value elsewhere. This is exactly
`getRuneValue()` in WakForge's `useStats.js`. Cross-check one rune in-game once to confirm the tables
for the current patch.

### Phase 2 — Sublimations (optional, incremental)
- **2a (recommended):** `selectedSublimations` as **input** (like `forcedItems`). The engine reserves
  the `slotColorPattern` (pins 3 socket colours on one item → constrains that item's rune doubling)
  and adds flat-stat contributions for the "flat" subs. No need to value conditional effects.
- **2b (advanced):** let the solver **choose** from a **curated subset** of static-evaluable
  sublimations (epics like *Inflexibility* / *Unraveling*), via a hand-maintained `stateId → effect`
  table (built from `states.json` + noredlace). The "max 1 epic + 1 relic" rule mirrors the existing
  epic/relic *item* caps in `addBuildValidityConstraints`
  ([WakfuBuildSolver.kt:~358](../autobuilder/src/main/kotlin/me/chosante/autobuilder/genetic/wakfu/WakfuBuildSolver.kt#L358)).
- **2c (the real goal) — "max-damage" objective mode.** A new `ScoreComputationMode` that optimises
  **expected damage** via Wakfu's **exact damage formula** (now sourced — see §8) instead of summing
  masteries. In that world many sublimations become *valuable and rankable*: a `+X% damage` or
  static-conditional sub (`+15% dmg if AP ≤ 10`) feeds directly into the damage function, so the
  solver can legitimately **choose** them. Pieces this needs: (1) the damage formula
  (base dmg → +mastery% → +crit/rear/berserk/distance/melee conditions → −enemy resistance, the
  floored-resistance quirk is already noted in WakForge `calcElemResistancePercentage`); (2) an
  attacker/target context input (crit rate, single vs multi-target, range, rear, enemy resistances);
  (3) the curated `stateId → effect` table from 2b. **Truly combat-conditional sublimations**
  (e.g. "if healed last turn") that can't be evaluated statically stay **user-forced input** — a
  `forcedSublimations` list, the sublimation analogue of `forcedItems`.

---

## 5. Open decisions & risks

- **Double-bonus = LOCKED to WakForge's model** (best achievable, not conservative): a rune doubles
  only on the 2 equipment slots in its `doubleBonusPosition`, base value elsewhere (§4e). Note:
  "double everything" is **not** in-game-achievable (re-rolling châsse colours can't double a
  non-favoured slot), so this model is both realistic *and* optimal. The doubled/undoubled value is a
  constant per `(stat, ItemType)` — no decision variable needed.
- **Rune↔sublimation coupling:** a normal sublimation pins 3 socket colours on its item ⇒ would
  reduce rune doubling there. Ignored in the first pass (sublimations are not solver-chosen anyway).
- **Elemental mastery rune** is generic (boosts all 4 elements) — consistent with the
  "min of requested elements" objective. There is **no** per-element *mastery* rune (only per-element
  *resistance* runes).
- **`doubleBonusPosition` semantics — resolved:** the two values are **equipment-slot raw ids** (the
  small 0–15 index space in §6, *not* `ItemType.id`). A rune doubles when placed on a slot whose
  rawId is in this list. We must transcribe the `ItemType → rawId` map from WakForge's
  `ITEM_SLOT_DATA` (§6). A couple of slots (off-hand, accessories) still need confirming, but they
  mostly have no sockets anyway.

---

## 6. Reference data (extracted from `items.json` 1.91.1.54)

### Colour codes (shared by rune `color` and sublimation `slotColorPattern`)
| Code | Colour | Shape | Rune stats in this colour |
|---|---|---|---|
| 1 | Red | Square | melee, distance, berserk, single-target, area mastery; earth resistance |
| 2 | Green | Pentagon | critical, rear mastery; dodge; initiative; fire resistance |
| 3 | Blue | Triangle | elemental mastery; healing mastery; lock; HP; water & air resistance |
| 0 | White | Circle | wildcard (matches any colour) |

### The 17 runes → `Characteristic`
| id | name (fr) | color | actionId | → Characteristic |
|---|---|---|---|---|
| 27094 | Maîtrise Elémentaire | 3 | 120 | `MASTERY_ELEMENTARY` |
| 27095 | Maîtrise Monocible | 1 | 400 | *(drop — no enum)* |
| 27096 | Maîtrise Zone | 1 | 400 | *(drop — no enum)* |
| 27097 | Maîtrise Mêlée | 1 | 1052 | `MASTERY_MELEE` |
| 27098 | Maîtrise Distance | 1 | 1053 | `MASTERY_DISTANCE` |
| 27099 | Maîtrise Berserk | 1 | 1055 | `MASTERY_BERSERK` |
| 27100 | Maîtrise Critique | 2 | 149 | `MASTERY_CRITICAL` |
| 27101 | Maîtrise Dos | 2 | 180 | `MASTERY_BACK` |
| 27102 | Tacle | 3 | 173 | `LOCK` |
| 27103 | Esquive | 2 | 175 | `DODGE` |
| 27104 | Initiative | 2 | 171 | `INITIATIVE` |
| 27105 | Résistance Feu | 2 | 82 | `RESISTANCE_ELEMENTARY_FIRE` |
| 27106 | Résistance Eau | 3 | 83 | `RESISTANCE_ELEMENTARY_WATER` |
| 27107 | Résistance Terre | 1 | 84 | `RESISTANCE_ELEMENTARY_EARTH` |
| 27108 | Résistance Air | 3 | 85 | `RESISTANCE_ELEMENTARY_WIND` (air = wind) |
| 27109 | Vie | 3 | 20 | `HP` |
| 27110 | Maîtrise Soin | 3 | 26 | `MASTERY_HEALING` |

Global rune curve: `shardLevelingCurve = [1,2,4,8,16,32,64,192,576,2304,9216]`,
`shardLevelRequirement = [0,36,51,66,81,96,126,141,171,186,216]` (same for all 17).

### Rune value per level (transcribed from WakForge `useConstants.js` — the calibration, §4e)
Index by rune level (1 → 11); double on favoured slots. Cross-check in-game per patch.
```
RUNE_LEVEL_REQUIREMENTS         = [0, 36, 51, 66, 81, 96, 126, 141, 171, 186, 216]  // char lvl gating rune lvl 1..11
RUNE_MASTERY_LEVEL_VALUES       = [1, 3, 4, 6, 7, 10, 15, 19, 24, 30, 33]   // melee/distance/berserk/critical/rear/healing
RUNE_ELEMENTAL_MASTERY_VALUES   = [1, 2, 3, 4, 5, 7, 10, 13, 16, 20, 22]    // generic elemental mastery
RUNE_RESISTANCE_LEVEL_VALUES    = [2, 5, 7, 10, 12, 15, 17, 20, 22, 25, 27, 30]   // earth/fire/water/air resistance
RUNE_DODGE_LOCK_LEVEL_VALUES    = [3, 6, 9, 12, 15, 21, 30, 39, 48, 60, 66]
RUNE_INITIATIVE_LEVEL_VALUES    = [2, 4, 6, 8, 10, 14, 20, 26, 32, 40, 44]
RUNE_HEALTH_LEVEL_VALUES        = [4, 8, 12, 16, 20, 28, 40, 52, 64, 80, 88]
```
Max-level (lvl 11, char ≥ 216) value, **base → doubled on favoured slot**: mastery `33 → 66`,
elemental mastery `22 → 44`, resistance `27 → 54`, dodge/lock `66 → 132`, initiative `44 → 88`,
HP `88 → 176`.

### Equipment-slot raw ids (for `doubleBonusPosition`, transcribed from WakForge `ITEM_SLOT_DATA`)
| ItemType | rawId | | ItemType | rawId |
|---|---|---|---|---|
| HELMET | 0 | | BELT | 10 |
| SHOULDER_PADS | 3 | | BOOTS | 12 |
| AMULET | 4 | | CAPE | 13 |
| CHEST_PLATE | 5 | | 1H/2H weapon | 15 |
| RING (left) | 7 | | RING (right) | 8 |

(Off-hand weapon + accessory slots TBD — mostly socketless.) Worked example: the elemental-mastery
rune (27094) has `doubleBonusPosition [5,13]` = CHEST_PLATE + CAPE, so it doubles only on those two
slots; the distance-mastery rune (27098) has `[10,15]` = BELT + weapon.

### Sublimations
- All 467 encode the effect as `actionId 304` with `params[0]` = a **state id** (e.g. *Ruine II* →
  5980, *Dévastation II* → 5981, *Précision chirurgicale* → 5076). Effect text/logic is **not** in
  `items.json` — resolve via `states.json` + a curated table.
- Normal subs all carry a 3-entry `slotColorPattern` (e.g. *Carnage II* `[1,1,2]`); epic/relic carry
  `[]` with `isEpic`/`isRelic = true`.

---

## 7. Key files to touch

| Concern | File |
|---|---|
| Parse/emit runes | `equipments-extractor/.../EquipmentExtractor.kt`, `.../dtos/Item.kt` |
| Domain model | `common-lib/.../Equipment.kt` (Characteristic/ItemType), new `RuneType` |
| Build result | `autobuilder/.../domain/BuildCombination` |
| Solver | `autobuilder/.../genetic/wakfu/WakfuBuildSolver.kt` (`prePercentStat`, `solutionToBuild`) |
| Entry/params | `autobuilder/.../genetic/wakfu/WakfuBestBuildFinderAlgorithm.kt`, `WakfuBestBuildParams` |
| Data version | `autobuilder/.../Main.kt` (`VERSION`) — already `1.91.1.54` |
| UI | `gui-compose/.../paperdoll/PaperdollPanel.kt` + equipment card visuals |

---

## 8. Wakfu damage formula (for Phase 2c)

Verified identical against the wakfucalc formulas page and the Wakfu wiki. Per single hit:

```
damage = ( Base
           × (1 + ΣMastery / 100)        // relevant masteries (flat), summed
           × Orientation                 // 1 face · 1.10 side · 1.25 back
           × Crit                        // 1 non-crit · 1.25 crit
           × (1 + ΣDI / 100)             // Damage Inflicted % ("% dégâts"), additive
           × (1 − Res% / 100)            // target elemental resistance (capped 90%)
         + FixedDamage − Barrier ) × Block      // Block: 1 · 0.8 · 0.68 (Blocking Expert)
```

**ΣMastery — sum of the masteries that apply in the chosen scenario:**
- Elemental: spell-element mastery **+** generic elemental mastery.
- Secondary (conditional): **distance** (target ≥ 3 cells) *or* **melee** (1–2 cells);
  **single-target** *or* **area**; **critical** mastery (only on a crit).
- Tertiary: **berserk** (caster ≤ 50% max HP), **rear** (attacking from behind), **healing** (heals).

**Resistance:** `Res% = floor( (1 − 0.8^(flatRes/100)) × 100 )`, max 90%
(matches WakForge `calcElemResistancePercentage`).

**Crit** (probabilistic): crit rate base 3%, cap 100% (WakForge `useStats.js:207`). On a crit, both
the ×1.25 applies **and** critical mastery joins ΣMastery. For optimisation use **expected damage**:
```
E[damage] = (1 − critRate)·damage(noncrit) + critRate·damage(crit)
```

**ΣDI** is additive (regular + conditional DI), floored at −50% (−100% with Theory of Matter) before
adding conditional DI.

### What this means for the optimiser
- For a **fixed attack scenario** (element, range, position, single/area, crit rate, target Res),
  `Base`, `Orientation`, `Res`, `FixedDamage`, `Barrier`, `Block` are **constants** → the build only
  moves `ΣMastery`, `critRate`, `ΣDI`. The objective reduces to maximising
  `(1 + ΣMastery/100) × (1 + 0.25·critRate) × (1 + ΣDI/100)` — a **product of build-dependent terms**
  (nonlinear). CP-SAT can express it via `addMultiplicationEquality` (already used in the solver).
- **New things to model** (not in the engine today): a **Damage Inflicted %** characteristic
  (mostly comes from sublimations/buffs — *this is what makes sublimations valuable*), crit as a
  *damage* multiplier (not just a target stat), and a **scenario input** object
  (element, range, position, single/area, target resistance, crit-rate cap).
- The two dropped runes (single-target/area, `actionId 400`) and `MASTERY_*` they feed would need
  enum entries here, since secondary masteries enter ΣMastery.

Sources: [wakfucalc formulas](https://sites.google.com/view/wakfucalc/en/guides/formulas) ·
[Wakfu wiki: Damage](https://wakfu.wiki.gg/wiki/Damage) ·
[forum: Wakfu damage formula](https://www.wakfu.com/en/forum/22-combat-strategy/230652-wakfu-damage-formula) ·
[Disc's Guide to Damage Formulas](https://www.wakfu.com/en/forum/143-guides/166113-guide-disc-guide-damage-formulas)

---

## Sources
- [Ankama CDN config](https://wakfu.cdn.ankama.com/gamedata/config.json) ·
  [Ultimate Enchantment Guide](https://www.wakfu.com/en/forum/143-guides/234611-ultimate-enchantment-guide) ·
  [Epic/Relic Sublimations](https://www.wakfu.com/en/forum/143-guides/231826-epic-relic-sublimations) ·
  [Fandom: Sublimation](https://wakfu.fandom.com/wiki/Sublimation) ·
  [wakfu.how sublimations](https://wakfu.how/theory-crafting/sublimations.html)
- Data-source references: [WakForge](https://github.com/Tmktahu/wakforge) (runes+subs UI & value logic) ·
  [noredlace/wakfu-sublimations](https://github.com/noredlace/wakfu-sublimations) (curated effect text) ·
  [Markkop/corvo-astral](https://github.com/Markkop/corvo-astral) (CDN pipeline to copy)
