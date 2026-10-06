# AGENTS.md — Wakfu Autobuilder

Guidance for AI agents (Claude Code, Cursor, etc.) working in this repository.
Human contributors will also find this a faster on-ramp than the README.

---

## 1. What this project is

**Wakfu Autobuilder** finds the *optimal combination of equipment* for a character in
[Wakfu](https://www.wakfu.com) (an MMORPG by Ankama), given a set of **desired stats** as input.

It is a **build *discoverer / searcher***, not a build editor:

> You give it constraints (level, class, target characteristics, max rarity, forced/excluded
> items) → it **searches the item space** → it outputs the best build it found (14 equipment
> slots + skill point allocation) and can publish it as a shareable
> [zenithwakfu.com](https://zenithwakfu.com) link.

Two front-ends ship from the same engine:
- a **CLI** (`autobuilder` module)
- a **Compose Desktop GUI** (`gui-compose` module)

---

## 2. Module map

Gradle multi-module, Kotlin, JVM toolchain pinned to **JDK 25** in the version catalog
(`gradle/libs.versions.toml`, the standard location Gradle auto-loads as `libs` — and that
Dependabot can parse for automated dependency-update PRs).

```
common-lib            Pure domain model. No project deps. Everyone depends on it.
  ▲
  ├── equipments-extractor   Standalone tool: pulls Wakfu game data from Ankama's CDN
  │                          and regenerates the embedded equipments JSON.
  ├── spells-extractor       Standalone tool: scrapes the Ankama encyclopedia and regenerates the
  │                          embedded class-spells JSON (element / AP / damage per class).
  ├── bdata-extractor        Standalone tool: decodes the local game client's scrambled static-data
  │                          binaries (contents/bdata/*.jar) and regenerates the embedded
  │                          spell-cast-limits + spell-passives JSON. Pure JVM, no native deps.
  ├── zenith-builder         Talks to the zenithwakfu.com builder API to create a build URL.
  │     ▲
  ├── autobuilder            CLI + the SEARCH ENGINE. Depends on common-lib + zenith-builder.
  │     ▲                    Embeds the equipments-vX.Y.Z.json data file as a resource.
  └── gui-compose            Compose Desktop app. Depends on autobuilder + zenith-builder + common-lib.
```

| Module | Type | Entry point | Key libs |
|---|---|---|---|
| `common-lib` | library | — | kotlinx-serialization |
| `equipments-extractor` | app | `me.chosante.equipmentextractor.MainKt` | Fuel (HTTP), serialization |
| `spells-extractor` | app | `me.chosante.spellextractor.MainKt` | java.net.http (HTTP), serialization |
| `bdata-extractor` | app | `me.chosante.bdataextractor.MainKt` | java.util.zip + java.nio (decode), serialization |
| `zenith-builder` | library | — | Fuel, coroutines, serialization |
| `autobuilder` | app | `me.chosante.autobuilder.MainKt` | Clikt + Mordant (CLI), OR-Tools, coroutines |
| `gui-compose` | app | `me.chosante.ui.MainKt` | Compose Multiplatform (Desktop), Conveyor |

> A legacy JavaFX GUI module (`gui`) used to exist; it has been removed — `gui-compose` is the
> only GUI. If you find a doc/reference still mentioning `gui`, AtlantaFX, Ikonli, FXML or
> `WakfuAutobuilderGUIKt`, it is stale.

---

## 3. Core domain (`common-lib`)

These types are the vocabulary of the whole codebase — learn them first.

- **`Character`** (`Character.kt`): `clazz`, `level`, `minLevel`, `characterSkills`. Computes base
  stats (AP=6, MP=3, WP=6 / 12 for Xelor, HP=`50 + level*10`, crit=3, control=1) via
  `baseCharacteristicValues`.
- **`CharacterClass`**: the 18 Wakfu classes + `UNKNOWN`.
- **`Equipment`**: `equipmentId`, `guiId` (used to resolve the item icon PNG), `level`, `name`
  (`I18nText` fr/en/es/pt), `rarity`, `itemType`, `characteristics: Map<Characteristic, Int>`, and
  `percentOfLevel` — the "X% of the level as <stat>" lines (only the Dofus Pourpre's 100% Elemental Mastery
  today). Those depend on the wearer's level, so the per-request pool resolves them into `characteristics`
  (`Equipment.atLevel`, in `WakfuBestBuildFinderAlgorithm.poolFor`): every stat reader downstream sees plain
  stats. The raw `WakfuBestBuildFinderAlgorithm.equipments` catalog is unresolved — never build a pool from it
  without `atLevel`. The catalog also joins each item's EQUIP criterion (`equipCriterion`, `@Transient`: never read from
  `equipments.json` nor saved) from `item-criteria.json` — see §4 "Item equip conditions".
- **`ItemType`**: the 14 equippable slots (amulet, ring, boots, helmet, cape, belt, chestplate,
  shoulder pads, emblem, pet, mount, 1H/2H/off-hand weapons). Each carries Ankama's numeric `id`.
- **`Rarity`**: ordered enum `COMMON < UNCOMMON < RARE < MYTHIC < LEGENDARY < RELIC < SOUVENIR < EPIC`.
  Build validity caps: at most **1 EPIC** and at most **1 RELIC** per build.
- **`Characteristic`**: ~50 stats (elemental/melee/distance/etc. masteries, resistances, AP/MP/WP,
  range, HP, crit, control, lock, dodge, wisdom, prospection, block %, armor %, …).
- **Skills** (`skills/`): `CharacterSkills` exposes 5 branches — `Intelligence`, `Strength`,
  `Agility`, `Luck`, `Major` — each an `Assignable` of `SkillCharacteristic`s. Available skill
  points derive from level; `Major` points unlock at levels 25/75/125/175. Values are `FIXED` or
  `PERCENT` (`UnitType`), and some are `PairedCharacteristic` (one point feeds two stats).
- **Sublimation conditions** (`SublimationConditionType`, meaning in `SublimationSemantics.kt`): the Neutralité
  family's `SECONDARY_MASTERIES_AT_MOST` (Neutralité, Ambition, Inflexibilité, Prétention, Abandon) holds iff EACH of the
  six secondary masteries (melee, distance, berserk, rear, critical, healing) is ≤ the threshold on the first-turn sheet —
  never their sum: the game's criterion is an `and` of six per-stat atoms, and `bdata-extractor` fails on any other
  shape. The certificates price it through a SUM budget — a sound relaxation (`secondaryMasteriesSumBound`).

`BuildCombination` (in `autobuilder/domain`) = `equipments + characterSkills`, with `isValid()`
enforcing slot/rarity/weapon rules.

---

## 4. The search engine (`autobuilder`)

The engine is the **Google OR-Tools CP-SAT solver**. It streams its best-so-far build as a
`Flow<SolverResult<BuildCombination>>` (there is a single engine, so the CLI and GUI consume it
identically). `SolverResult` was formerly `GeneticAlgorithmResult`; the enclosing package is still
named `genetic` for historical reasons.

`WakfuBestBuildFinderAlgorithm.run(params)` is the entry point: it filters & groups the embedded
equipments by `ItemType` (applying level/rarity/forced/excluded filters), then hands them to the
solver.

> **`ENGINE_RESULTS_VERSION` (`autobuilder/.../domain/EngineResultsVersion.kt`) — bump it on ANY change that can alter which
> build a search returns or a build's score**: the solver, the scorers, the item pre-filter, the rune or sublimation modelling,
> the certificates. **A `CERTIFIER_VERSION` bump implies an `ENGINE_RESULTS_VERSION` bump**; a pure speed-up that returns the
> same builds needs none. Every saved build records it (`HistoryEntry.engineResultsVersion`), and "My Builds" badges a save
> with a lower one (or none: saved before the field existed) as **obsolete** — a re-run may find a better build or score —
> beside the game-data reason (`HistoryEntry.dataVersion` ≠ `WakfuData.VERSION`). It is a plain constant, NOT in
> `WakfuBuildSolver` (whose init loads OR-Tools), so the GUI reads it for free. `EngineResultsVersionTest` locks the pair
> (`CERTIFIER_VERSION`, `ENGINE_RESULTS_VERSION`): update it with the bump.

> A genetic-algorithm engine used to be selectable via a `WakfuSolver` enum. **It has been removed —
> OR-Tools is the only solver.** Any reference to a GA, a `WakfuSolver` enum / solver toggle, or
> `genetic/{GeneticAlgorithm,Selection}.kt` / `genetic/wakfu/{Population,Cross,Mutation}.kt` is stale.

### Google OR-Tools CP-SAT
- `genetic/wakfu/WakfuBuildSolver.kt`: models the build as a constraint-optimization problem and
  solves it with CP-SAT for a **deterministic, provably optimal** result. Streams improving
  solutions via `callbackFlow`.
- **Native**: OR-Tools ships ~100 native dylibs loaded at runtime via `OrToolsNativeLoader.load()`
  (`WakfuBuildSolver`'s `init` / `warmUp()`). On macOS that loader extracts them **once** into
  `~/Library/Caches/WakfuAutobuilder/ortools-native/<fingerprint>/` and reuses them: the stock
  `Loader.loadNativeLibraries()` re-extracts to a fresh temp dir every launch, and macOS's
  code-sign validation of freshly written dylibs (~10–25 s) stalls the whole UI thread — the
  startup-freeze root cause. Only the **first** launch (or first after an OR-Tools bump) pays the
  validation; later launches load in ~0.1 s. Other OSes keep the stock loader. The GUI hides the
  cold start behind a loading screen (see §6).
- Running/testing the solver needs extra JVM args — see §9.

### Two scoring modes (`ScoreComputationMode`)
- `FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT` ("most-masteries") — default. Constrain AP/MP/range/
  crit to exact targets, then **maximize** the requested masteries. Scorer:
  `FindMostMasteriesFromInputScoring`. (`Characteristic.isMaximizableMastery()` /
  `isRequiredMostMasteriesTarget()` split the maximized masteries from the hard constraints, and are
  shared by both the scorer and the CP-SAT solver so they stay in lockstep.)
- `FIND_CLOSEST_BUILD_FROM_INPUT` ("precision") — hit exact target values for *every* requested
  characteristic. Scorer: `FindClosestBuildFromInputScoring`.

When required targets cannot all be met, the *soft* objectives (the most-masteries fallback, the max-damage
soft leg) multiply the core by a power-6 penalty on the weighted target ratio. That multiplier is defined ONCE
— `penaltyMultiplier` in `WakfuBuildSolver.kt`, **floored at 1** so targets far out of reach (< ~10%) still
rank builds by their core (unfloored, every bucket there was 0 and the empty build tied the optimum). The
certificates and research harnesses call it — never re-derive `i⁶ / powScale` — and the re-scorers cap their
continuous factor at `MAX_PENALTY_MULTIPLIER` to match. The opt-in survivability floor's gentle power-2 table
is floored at 1 the same way.

### Rows of target 0: FLOORS ("never negative"), in every mode
A row of target 0 on a REQUIRED stat (`isRequiredMostMasteriesTarget()`: a resistance, dodge, lock, AP… — the GUI's default
"air resistance 0" / "dodge 0" rows) is a **floor**: `TargetStats.floorCharacteristics` + `TargetStats.resistanceFloorElements`,
read ONCE for the solver and the scorers (`StatBuilder.floorReads`, `TargetFloors.kt`):
- most-masteries / max-damage **hard leg**: `actual ≥ 0` (`addRequiredTargetHardConstraints`); a request whose only required rows
  are floors runs the hard leg too (`MaxDamageSearch.optimizeHardThenSoft`, `TargetStats.hasFloors`);
- their **soft legs**: the penalized objective is HALVED (once, however many floors are broken) while a floor is below 0
  (`applyConstraintPenalty`; the scorers divide by 2 — `FLOOR_BROKEN_DIVISOR`) — a factor ≤ 1 after the power-6 multiplier, so the
  leg stays feasible and every certificate (which ignores floors) stays an upper bound;
- **precision**: its existing halving (`precisionHalves`), which also reads masteries of target 0 (`TargetStats.zeroMasteries`).

A resistance row of target 0 **wants no element** (`resistanceElementsWanted` skips it), so it never makes a request
multi-element (`needsItemPrefilter` reads the wanted elements only). Its floor reads the element **as the game does**: a request
with a resistance floor places the family's random-element rolls over the wanted AND the floored elements together — ONE joint
fold (`foldElements`; `readsJointPerElementRows` is true in every mode), each roll on `rollCover` of them: a positive roll on
`min(k, n)` (it can lift a floor instead of feeding a row), a negative one on as few as the elements outside the fold leave it
(`freeSinks` = 4 − n take the rest, so "−30 on 1 random element" only hits a floor when every element is read). The model
(`StatBuilder.familyFoldElements` / `applyGreedyRandom`, free placement) and the scorers (`ElementRowObjective` with its floors kept
by `placeKeepingFloors`, the keep-or-break choice made on the whole build by `keepsFloorsFirst`: precision by its own objective,
most-masteries / max-damage by what the score divides by, `requiredPenaltyFactor`) follow that rule exactly. EVERY resistance
fold follows it, with or without a floor (`resistanceFreeSinks`: "fire resistance 10" alone leaves a "−30 on 1 random element" to
the three other elements — in the model's folds and in every scorer assigner, `placedResistanceRolls`); only mastery folds keep the
historical rule (every roll on `min(k, wanted)` wanted elements, a negative one included — no random mastery line of the data is
negative). "All resistances 0" is a floor on each element no other row wants. A 0-valued row on a stat another row targets with a non-zero value
is left to that row (no floor). A floor no build of the pool can break (tracked reach ≥ 0) adds nothing to the model. Maximized
masteries keep their meaning: no floor, an element of target 0 stays wanted (most-masteries maximizes it). The certificates ignore
floors and never read resistance rolls (a relaxation — sound, `CERTIFIER_VERSION` untouched); the E8 construct's fast re-solve
runs the hard leg whenever the request has floors, so the build it crowns meets them, and its full-pool fallback is skipped then (a
fast miss behind a floor means a binding floor the ledger ignores — the fallback could only run out its cap). Every max-damage
reader resolves its stats through ONE function, `FindMaxDamageScoring.penaltyStats` (the mode and scenario included, so a
scenario-gated sublimation — "Esquive Berserk III" — counts as the solver counts it). The GUI sends a row only for a field the
player filled in (a typed 0 is a floor, a blank field nothing), and a reloaded save whose request the OLD reading pre-filtered
(`TargetStats.legacyNeedsItemPrefilter`, evaluated on the rows read the old way — `BuildSearchModel.legacyTargetStats`: a blank field
as a row of 0, most-masteries' "all resistances" split — where a resistance 0-row counted as wanted) never gets its stored proof flag
back. Locks: `ZeroTargetRowsTest` (unit cases per mode / leg, the review's repros, the E8 construct, a seeded model ⇔ scorer fuzz
and the wider fuzz with real sublimations / runes / scenarios — both also checked against a game oracle that tries every in-game
placement of the rolls), `ElementRowAssignmentTest` (exhaustive placements with floors and free sinks).

### Relax-then-check: the most-masteries legs of a request with floors
The floors slow CP-SAT's most-masteries proof down (the GUI default carries two), while the best build very often keeps them
anyway. So a most-masteries leg — hard or soft — of a request with floors runs in stages against ONE deadline
(`WakfuBuildSolver.relaxThenCheck`):
1. the RELAXED model (`StatBuilder.relaxFloors`: no floor read — no `≥ 0`, no halving — each resistance family folded over its
   wanted elements alone) on at most half the budget (`RELAXED_STAGE_SHARE`). On the hard leg it shows a build only when the
   scorers' read keeps every floor and meets every target (on the soft leg any build, its score halved when a floor breaks); it
   never stamps a certificate-comparable objective and sends no final;
2. the CHECK: the floored model with `objective = w`, w the relaxed objective read EXACTLY (the objective variable's value, taken
   only when the response's own objective agrees), hinted, silent, for at most as long as the relaxed solve ran (and a tenth of the
   budget). Its build, once the scorers' read confirms its floors, is a floored build worth w: the floored optimum — the leg's result,
   proven — when the relaxed stage PROVED w optimal; otherwise (relaxed stage out of time) the floored stage's hint;
3. otherwise the FLOORED model on what is left, hinted and NOT cut: a redundant `objective ≤ v` made the floored proof 5-10× slower
   when a floor binds (measured), so the relaxed optimum only serves the check.

Why it is sound: for every build, floored objective ≤ relaxed objective (the hard leg's objective reads no floor; the soft leg's
halving only lowers a core ≥ 0; the relaxed folds place the rolls as the game does over the wanted elements, and both objectives
only grow with them — a request with a negative target or priority takes the direct solve). So no floored build is worth more than
the relaxed optimum v, and a floored build worth v — all the check's model accepts — is the floored optimum. The leg's optimality
stamp comes from the check (that argument) or from the floored stage's own OPTIMAL, never from the relaxed stage. A relaxed stage
proven INFEASIBLE proves the floored leg infeasible. A floored stage that ends unproven below the best build the relaxed stage
showed delivers that build; a relaxed stage with no solution at all leaves the floored stage the greedy warm start, as the direct
solve. The price of splitting one budget: a leg that needs more than half of it to find ANY solution, with no warm start, can end
with no build where the direct solve finds one (the slow fuzz's two-element soft legs on 1 worker; production always hints the warm
start). Max-damage and precision keep their direct floored solves. Locks: `RelaxThenCheckTest` (relaxed ≥
floored objective build by build on seeded pools, the leg ends on the direct floored solve's optimum when the floors hold and when
one binds, on real data too, nothing shown breaks a hard-leg floor, the budget split).

### Inputs: `WakfuBestBuildParams`
`character`, `targetStats: TargetStats`, `searchDuration`, `stopWhenBuildMatch`, `maxRarity`,
`forcedItems`, `excludedItems`, `excludedRarities`, `scoreComputationMode`. `TargetStats` normalizes
per-stat weights and expands `MASTERY_ELEMENTARY` / `RESISTANCE_ELEMENTARY` into their four elements.

### Why the solver can leave slots empty (mount/pet/…) — *not a bug*
"Most-masteries" mode maximizes **only the requested** masteries (under the required-stat
constraints) and has no tie-breaker to fill otherwise-empty slots. So if no item in a slot can
improve any requested stat, the proven optimum leaves that slot empty. Concrete case: the default
request (distance mastery + AP/MP/HP/…) returns **no mount**, because every mount in the data
carries only `MASTERY_ELEMENTARY` — which matches none of those targets, so adding one cannot raise
the objective and the proven optimum leaves the slot empty. (A lexicographic secondary objective —
"max total elemental mastery among optimal builds" — would fill such slots and was considered, but
deliberately not implemented.)

### The domination pre-filter (it decides what the search AND every certificate see)
Production solves drop per-slot DOMINATED items before the search (`DominationFilter.kt`: `dominationShape` →
`filterDominatedPool`, all three modes), and the certificates read the **same reduced pool** — so an item wrongly
evicted makes CP-SAT's `OPTIMAL` and the certificate's bound wrong together (a wrong "proven optimal" badge). `A`
may evict `B` only if it can replace `B` in EVERY build of the model with no loss, on every dimension the model or a
certificate reads from an item (CERTIFIER_VERSION 53 audit, `docs/perf-review-backlog.md` §E):
- stats `≥` on the compared stats, `==` on the pinned ones (stats a ≤ / exact / parity sub condition reads, AP / MP /
  WP and their MAX_* riders), `≤` on the minimized ones; sockets `≥` (rune capacity, normal-sub carrier);
- rarity both ways: `A` epic ⇒ `B` epic (the ≤1-epic / ≤1-relic budget), and `B` epic ⇒ `A` epic while an epic sub is
  modelled (`B` may be the only carrier of the build's epic sub) — relic alike;
- runes: the item's LEVEL caps its rune level, so `A`'s rune-level cap must be `≥` `B`'s (`==`, with equal sockets in
  max-damage, when a modelled rune type is a pinned stat);
- rings: `B` goes only when its dominators span two different NAMES (two rings of one name are never worn together).

- equip conditions (see below): an item another pool item REQUIRES is never evicted, and `A`'s required items and
  conflict partners must be subsets of `B`'s.

Adding anything the model reads from an `Equipment` (a new field, a level- or name-dependent term) means adding its
clause there — and bumping `CERTIFIER_VERSION`, since the certificates' pool changes.

### Item equip conditions (what the game lets a character wear)
The client's Item table carries an EQUIP criterion per item (`ItemEquipCriterion`, decoded into `item-criteria.json` by
`bdata-extractor`, §5; joined onto each catalog item as `Equipment.equipCriterion`). The game checks it at equip time AND
re-checks the whole equipped set, so every rule is a rule on the FINAL build. The engine enforces (one set of helpers,
`domain/EquipConditions.kt`, read by every consumer):
- **REQUIRES** (`HasEquipmentId(x)`): the four nation swords (RELIC, +3 AP) each need their zero-stat EPIC ring — CP-SAT
  `x_sword ≤ x_ring` (`addEquipConditionConstraints`); the pool drops an item whose required item can't be worn in the
  request (`withRequirementsMet` in `groupAndFilterEquipments`: rarity cap, level band, exclusion); forcing the sword
  forces its ring (`forcedNamesWithRequirements`: kept beside the forced items, counted by `validateRequest`); the
  multi-element prefilter and the E8 provenance keep the ring. Only the USER's forced names narrow a slot, and the RING
  slot is never narrowed: a build wears two rings, so forcing one ring (or a sword, whose ring takes one) leaves the second
  free — the model's `Σ same-name ≥ 1` equips each forced item. (Narrowing it to the forced names made CP-SAT prove OPTIMAL
  a forced-sword build 7.4 % below the true forced optimum; every certificate bails on a forced item.)
- **FORBIDS** (`not HasEquipmentId(x)`), read as the SYMMETRIC closure (the Lieute rings list their bans only in their
  CRAFT criterion): `x_a + x_b ≤ 1` per pair — five ring triples of different names (Issé Sceau's triple shares a name).
- **CLASS-ONLY** (`IsBreed`, mapped to `CharacterClass` by breed id: the client's SACRIER is our SACRIEUR) and **NEVER**
  (`False`): static pool filters (`isWearableBy`), and forced off in the CP-SAT model too (`x = 0` in
  `addEquipConditionConstraints`), so a raw pool — the lvl-245 oracle's, a research harness's — never returns another
  class's item. A character of class `UNKNOWN` (CLI without `--class`) wears no class item.
- `BuildCombination.isValid(characterClass)` checks all four; the greedy warm start never picks a requiring item alone
  (each sword + ring BUNDLE is a candidate, ranked by `rescore`) and never pairs excluding rings; `validateRequest`
  rejects a wrong-class / never / requirement-unavailable forced item and two excluding forced items (`RequestValidationProblem`).
- NOT enforced, kept for display: stat gates (`GetCharac` / `GetCharacMax` bounds — pending an in-game check of whether
  an item's own bonus counts) and player-state conditions (company rank, achievement, gauges, crime score: assumed
  satisfied). `not HasAnotherSameEquipment()` is the existing same-name ring rule.

The certificates read REQUIRES (CERTIFIER_VERSION 57) and ignore FORBIDS (a relaxation: two rings that exclude each
other may pair in a bound — sound, looser). The AP-cell certifier — the max-damage proof authority — splits every world in
two ([CertWorld.bundle], `requirementBundleSplit`): the builds wearing no nation sword (swords removed) and the builds
wearing one (sword + ring as ONE ring-stage entry, weapon slot left to off-hands), so the epic budget AND the ring slot the
ring takes are exact (the lvl-245 ledger fell 1.0–2.3 % on cells 12–17: the v56 proven optimum wore Épée de Brâkmar
without its ring). The most-masteries and soft certificates offer the sword FUSED with its ring in its own slot (`wornOpts`:
stats, runes, rarity summed), which counts the epic budget but leaves the ring's slot free — an over-count of at most one
ring. Locks: `EquipConditionsTest`, `EquipConditionsCertificateTest` (soundness on every pass, and the AP-cell ledger EXACT
on conflict-free seeded pools), `EmbeddedItemCriteriaDataTest`.

### The multi-element item pre-filter (a HEURISTIC: what a multi-element search sees, and why it never earns a badge)
A request wanting more than one element of mastery or resistance (`WakfuBuildSolver.needsItemPrefilter`: two specific
elements, or the aggregate `MASTERY_ELEMENTARY` / `RESISTANCE_ELEMENTARY` — a resistance row of target 0 wants nothing, see
"Rows of target 0") would blow up the random-element modelling on
the full late-game pool, so `buildModel` shrinks every slot first (`prefilterRelevantEquipments`, BEFORE the domination
filter): the forced items, the top 8 items of each relevant characteristic, and the top 8 by a **combined mastery score**
(`combinedMasteryScore`: the sum over the wanted elements of the specific mastery, plus the generic one and each
random-element line on `min(k, elements)` elements, plus every requested non-elemental mastery, once per element; 0 when
no elemental mastery is wanted). The score also breaks ties on a stat's value — AP / MP / range / crit are small
integers, so the cut falls inside a tie group — and the sort is stable, so what is still tied keeps pool order and the
result stays deterministic. Ranked on single stats alone, the item that is best on none but strong on all (395
random-element + 395 distance mastery) was lost: 6 of 20 most-masteries requests with a proven full-pool optimum lost
0.19–4.6 % (all four level-245 requests with a distance row); the combined ranking recovers all six with no regression
(deterministic A/B: 1 worker, interleave, seed 1, 120 det-s).

It stays lossy by construction, so CP-SAT's `OPTIMAL` over the reduced pool proves nothing global: every proof path gates
on `needsItemPrefilter` (`SolverResult.isOptimal`, `MaxDamageSearch.proveOptimality`, `proveMostMasteriesQuality`, the
bound cache, the E8 construct) and a prefiltered request never earns a badge. No certificate reads the reduced pool (the
most-masteries bound builds its own from `poolFor` + domination; the max-damage certificate is unreachable for such a
request), so changing the ranking needs no `CERTIFIER_VERSION` bump. Locks: `PrefilterRankingTest` (what is kept),
`PrefilterOptimalityTest` (no badge).

The GUI says so instead of suggesting a longer search (`Tr.NO_PROOF_TITLE` / `Tr.NO_PROOF_BODY` in the stats headline):
`UiState.prefilteredRequest` is read from `TargetStats.needsItemPrefilter` when a search starts or a saved build is loaded,
so it belongs to the RESULT (it travels with `ShownResult`) and never follows the target rows as they are edited later.
The post-search check is not started for such a request either. If a proof of these requests ever lands (the planned
background full-catalog proof, `docs/perf-review-backlog.md` §E), revise that text and that gate together. The other
no-badge messages stay as they were: a result that merely ran out of time keeps the "raise the search duration" hint
(`Tr.NOT_OPTIMAL_HINT`). Locks: `BuildSearchModelNoBadgeExplanationTest`, `NoProofExplanationUiTest`.

### The max-damage optimality certificate ("proven optimal" badge)
Max-damage mode can **prove** the build it found is the global optimum, and the GUI/CLI show a badge
saying so. The proof is an independent **certificate**, not a re-solve: `MaxDamageSearch.proveOptimality`
(entry point `WakfuBestBuildFinderAlgorithm.proveMaxDamageOptimality`) calls
`WakfuBuildSolver.maxDamageCertificate`, a two-tier per-AP-cell dynamic program (`WakfuBuildSolver.kt`,
`certifyLedger` → `certifyAllCellsFast` + `exactForCells`). It computes a **sound upper bound** on the
best achievable objective for every AP cell; the badge is awarded iff the incumbent (the best build
found) is `≥` the ledger's `maxCellObjective`. Badge states: **proven optimal**, **proven within X%**
(`maxCellObjective / incumbent − 1`), or **unavailable** (a shape the certifier bails on — multi-element
/ boss, or a forced-rune / unsupported-sub shape; a bail is always sound, it just withholds the badge).
- **The one invariant is soundness: the certificate must never UNDER-count a cell** (that would award a
  wrong badge). Every pass is a *sound upper bound*, exact on most shapes but legitimately loose on some
  (crit bands, couplings) — so its locks assert `≥`, never `==`, and **when in doubt the certifier BAILS**
  (`Long.MAX_VALUE`, always sound). Below the AP constant the exact pass bails and the sound fast bound
  carries the cell.
- **`CERTIFIER_VERSION` (`WakfuBuildSolver.kt`) must be bumped on ANY certifier change** (fast pass,
  exact pass, orchestrator, scaling formula, world/sub enumeration). It keys the in-memory per-cell
  cache alongside `WakfuData.VERSION`, so a bump invalidates every cached bound instead of serving a
  stale (possibly now-unsound) one.
- **Hard-leg results get a TARGET-AWARE ledger (CERTIFIER_VERSION 52).** A result of the hard-constraints leg
  (`SolverResult.maxDamageHardConstraintsMet`) of a request with a positive AP / MP / CC / RANGE row is compared with a
  ledger that enforces those rows in every pass (`StatBuilder.certifierTargetAware`; each filter reads a sound
  OVER-estimate of the build's own stat), so its badge means "within X% of the best build that meets the targets". The
  flag is part of the certificate cache key (memory and disk); soft-leg and free results keep the target-blind ledger.
  Kill switch `WAKFU_MD_TARGET_AWARE=0`; locks in `MaxDamageTargetAwareCertificateTest` (oracle: the pinned hard-leg
  CP-SAT optimum per AP cell).
- **Guards:** a CI-runnable fuzz lock (`WakfuBuildSolverTest`, seeded random pools → `certExact/fast ≥`
  pinned CP-SAT, ledger `≥` true optimum) plus a nightly `@Tag("slow")` lvl-245 ledger oracle keyed on
  `WakfuData.VERSION`. The full campaign log lives in `docs/MAX_DAMAGE_PROVABLE_OPTIMUM.md`; the plan +
  execution log in `docs/CERTIFICATE_PROD_PLAN.md`.

### The most-masteries quality badge ("proven within X%")
A most-masteries search that ends without a CP-SAT proof (short budget, low-core machine) still gets a
badge from `MostMasteriesCertificate.bound`, a sound DP upper bound on the soft folded objective
(`WakfuBestBuildFinderAlgorithm.proveMostMasteriesQuality` = the memoized bound + the pure
`compareMostMasteriesQuality`). The bound is **incumbent-free**, so `WakfuBestBuildFinderAlgorithm.run`
computes it in the search's TAIL (E10-for-MM, `MostMasteriesBoundCache`): one full-tier pass started 30 s
before the budget ends but never in the search's first 30 s (beside the steep early phase it measurably
slowed CP-SAT), on one DP thread while CP-SAT owns the cores (all chunk workers once the search ends),
memoized single-flight per request, superseded by any new search and cancelled when the search proves
OPTIMAL — so the badge is normally ready the moment the search ends (shorter budgets compute it post-search).
The DP thread count is a pure work knob (identical bound); orchestration changes never bump
`CERTIFIER_VERSION`. Measurements: `docs/MOST_MASTERIES_PERF_PLAN.md` §8.17–§8.19.

### The post-search check is the GUI user's call
Both badges above cost processor time at the end of a search and after it (the max-damage refinement was
measured at > 25 min), so the GUI makes that work understandable and stoppable. While it runs the stats
headline shows its cue — spinner, elapsed time, an info tooltip (`Tr.PROOF_INFO`) and a **Stop** link
(`BuildSearchModel.stopProof()`: cancels the proof, keeps the current build and badge — a "proven within X%"
badge just loses its `refining` cue, a check that knew nothing yet falls back to the "not proven" hint). The
persisted **"Check optimality after the search"** switch (`UiState.verifyOptimality` via `LibraryPreferences`,
default ON, offered in most-masteries / max-damage only) turns it off. **OFF starts no proof work after the
search ends** — no certificate wait, no E8 construct, no silent refinement, no bound compute — and cancels the
engine's own leftovers through `WakfuBestBuildFinderAlgorithm.cancelBackgroundProofs()` (the certificate / bound
warm-ups started beside the search outlive it by design, so a proof can join them; Stop reaches them too). What
costs nothing still shows: a CP-SAT-proven result, and a most-masteries bound the search's tail already finished
(`proveMostMasteriesQuality` with a `shouldContinue` that is already false is a memo-only *peek*). Call
`cancelBackgroundProofs()` only while no search runs — it would cancel that search's own warm-ups.

---

## 5. Data pipeline

Item data is **not** fetched at runtime by the apps — it is baked into `autobuilder/src/main/resources/`
as **fixed-name** JSON files (no version in the filename):

1. `equipments-extractor` downloads `items.json`, `equipmentItemTypes.json`, `actions.json`,
   `recipeCategories.json` from `https://wakfu.cdn.ankama.com/gamedata/:version` (the `:version` is read
   from `WakfuData.VERSION`, not auto-detected, so every extractor pins the same version) and writes
   `equipments.json` (the `Equipment` list). Equip lines are decoded from their `actions.json` description;
   action 999 (no description: "X% of the level", the stat in its `subEffects`) becomes `percentOfLevel`, states
   (304) are skipped, and any other undescribed action fails the run unless it is known to grant no stat.
2. `spells-extractor` → `spells.json`. (Monsters are no longer scraped — see `bdata-extractor` below.)
3. `WakfuBestBuildFinderAlgorithm` / `SpellCatalog` / `PassiveCatalog` load these by fixed name via the
   classpath at startup (e.g. `equipments.json`).
4. The GUI's `generateAssets` Gradle task (`gui-compose`) extracts matching icons (items/spells/states +
   class artwork + equipment-slot `itemTypes` + the rune socket-colour `runes` shards + the 36 HUD stat
   `icons`, mapped to `miscellaneous/characteristics` by the `Characteristic` each represents) from the **local
   game client's** `contents/gui_jar/gui.jar` — TGAs keyed by the same ids as our data, converted to PNG under
   `gui-compose/src/main/resources/assets/<set>/<id>.png`. (The community `Vertylo/wakassets` repo was just a
   PNG mirror of these.) Two sets stay committed-static because the client has no clean equivalent: monster
   boss **portraits** (the client only keys monsters by gfx as 132×41 banners — the 200×200 are sprite renders)
   and the 8 **rarity** badges (gui.jar has a filled icon only for epic/relic; the rest are border frames).
5. `bdata-extractor` decodes the **local game client's** scrambled static-data tables — `Spell` (66),
   `StaticEffect` (68), `State` (67), `Monster` (42) inside `contents/bdata/<id>.jar`, plus the
   `contents/i18n/i18n_<lang>.jar` name bundles — and writes `spell-cast-limits.json`,
   `spell-passives.json`, `sublimation-stacking.json` (per-sublimation `max_level` + `is_cumulable`),
   **`sublimations.json`**, **`spell-damage.json`**, **`runes.json`** (itemTypeId 811 shards from the CDN
   `items.json` — colour/double-bonus + the boosted stat from the equip-effect action; replaces the old
   hand-maintained file), and **`monsters.json`** (boss-mode data: level/HP/flat elemental resistances +
   localized name/family + icon `gfx`, replacing the old third-party MethodWakfu/Fandom scrape).
   **Spells stay on the encyclopedia** (`spells-extractor` → `spells.json`: name/element/AP/range/icon + the
   *max-level* base hit) because the spell-damage *renderer* is client-only — no decoder reproduces it, and
   every community tool (WakForge, Zenith) also uses Ankama's rendered output. But `bdata-extractor` adds the
   piece the encyclopedia lacks: **`spell-damage.json`**, the per-level damage formula `floor(base + inc·level)`
   from Spell (66) → StaticEffect (68), *anchored* on the encyclopedia value (the bdata effect whose value at
   max level equals it). `SpellCatalog` joins it so `SpellDamage` scales each hit to the **caster's level**
   instead of always showing max-level damage (`SpellDamageScalingBuilder`; ~86% get the exact bdata slope, the
   rest a linear approximation through the known max-level value — never a regression at max level).
   **Sublimations** are now fully first-party too: identity/name/rarity/slot-colours from the CDN `items.json`
   (`itemTypeId` 812, `actionId` 304 → `params[0]` = `stateId`); effects, build-static condition, scenario
   gates and max level decoded from the State (67) → StaticEffect (68) tables via a small sublimation-local
   action overlay + criterion-script parser (`SublimationBuilder.kt`) — replacing the retired WakForge /
   noredlace / hand-curated Python pipeline (`docs/sublimations-research/`, deleted). The solver-choosable
   set (clean permanent statics only; combat/scripted subs stay forced-input) is locked by
   `SublimationReproductionTest`. The Spell/State/StaticEffect
   layouts are hand-written positional schemas in `Tables.kt`; the **Monster** layout is instead
   **auto-derived from the client's JVM bytecode** by `SchemaGenerator` — a zero-dep, pure-JVM port of
   `jac3km4/wakfu-bdata-gen` using JDK's `java.lang.classfile`. (A table's wire layout is its binary-data
   class's `protected` fields in class-file order; the right class for a table id is the one whose derived
   schema cleanly decodes that `.bin`.) This is because the Monster record's layout **drifts between client
   versions** — 1.92.x inserted a new `Vec` mid-record — which a hand-written schema can't survive; deriving
   it from bytecode "just works" each bump. The **only** monster fact not in any client table is boss-tier
   `rank` (an editorial Ankama taxonomy — npc_rank/family_rank/MonsterType.kind don't reproduce it), carried
   by the committed `monster-overlay.json` (id → rank, rank ≥ 1); monsters with no entry default to rank 0
   (regular, hidden from the GUI boss picker). It needs a local Wakfu install (the binaries are **not** on
   the CDN), so unlike the other extractors it **cannot run in CI** — the JSON it produces stays committed.
   See `docs/SPELL_CAST_LIMITS_EXTRACTION.md` / `docs/SPELL_PASSIVES_EXTRACTION.md` for the format.
   It also writes **`item-criteria.json`** (`ItemCriteria.kt` + `ItemCriterionParser.kt`): the EQUIP criteria of the
   `equipments.json` items (so it runs after `equipments-extractor`), raw expression + typed form (`ItemEquipCriterion`:
   required / forbidden item ids, classes, never, unique-equipped, stat gates, player-state atoms), sorted by item id.
   Everything is found STRUCTURALLY in the client bytecode, never by an obfuscated name: the table id from the table-type
   enum's `ITEM` constant, the record PREFIX from the ITEM binary-data classes' `read(reader)` calls up to the first
   `String[]` (the alternating (kind, expression) criteria — only that prefix is decoded, each record by its own
   offset + seed, so a field Ankama appends later never breaks it), the kinds from the enum holding `EQUIP` /
   `USE_IN_FIGHT` / `PICK_UP`, the breed ids from the class-name enum. Guards that fail the run: a record whose first field
   is not its index id, an odd criteria list, an unknown kind, two EQUIP entries, and — in the typed parse of a pool
   item's expression (`Critere.g`: `and`/`et`/`&&`, `or`/`ou`/`||`, `not`/`non`/`!`, comparisons, arithmetic, `#…#`) — an
   unknown function, an `or`, a negated conjunction or an unmapped characteristic / breed. CI lock on the committed file:
   `EmbeddedItemCriteriaDataTest`; install-gated reproduction: `ItemCriteriaDecodeTest`.

**The data version is a single source of truth:** `WakfuData.VERSION` in `common-lib`
(`common-lib/.../WakfuData.kt`). The apps stamp it as `dataVersion`; the extractors fetch CDN assets for
it. Fixed filenames mean a bump touches only that constant — no renames, no stale `*-v<old>.json`.

**Updating to a new Wakfu version** = update the local game client, then run
`./scripts/update-game-data.sh [wakfu-install]` (auto-detects the version, bumps `WakfuData.VERSION`,
regenerates every artifact in order, then prompts to review + test + commit). See `CONTRIBUTING.md`
"Updating the game data". The untagged binary layout `bdata-extractor` reads can shift between client
versions; its per-record size guard fails loudly if a table's field schema drifts (re-derive the schema
in `Tables.kt` if so).

---

## 6. GUI architecture (`gui-compose`)

**Compose Multiplatform (Desktop)** on JDK 25. The UI is built **programmatically in Kotlin — there
is no FXML/XML.** Package root `me.chosante.ui`, organized by feature: `shell`, `request`,
`paperdoll`, `stats`, `state`, `components`, `i18n`, `theme`, `testing`.

- **`Main.kt`** — `application { Window(...) }`. The window opens **floating** (centered, clamped to
  the usable screen) so the loading screen paints instantly, is pulled to the foreground (useful for
  un-bundled `gradle run` launches), and is **maximised only once warm-up completes** — maximising at
  launch ran the macOS zoom transition during the native load and froze startup. `App()` gates on
  `BuildSearchModel.isReady`: it shows a **`LoadingScreen`** (`shell/`) — app wordmark + a
  self-calibrating warm-up `%`/ETA — while OR-Tools' native cold start is paid off the UI thread,
  then **`Crossfade`s** into the main UI. It also sets the window / macOS dock icon from
  `assets/branding/app-icon.png`.
- **`BuildSearchModel`** (`state/`) — the **single shared view-model** (Compose `mutableStateOf`
  `UiState`). It runs the search off-thread (`Dispatchers.Default`), collects the engine `Flow`,
  and owns warm-up state (`WarmupTiming`), background icon preloading (started **after** warm-up so
  the two never compete during startup), the equipment catalog, and Zenith build creation. The
  warm-up itself waits for the window's first frame (`windowShown`) before touching the native
  engine. (Unlike the old JavaFX GUI, state is centralized here — not in the widgets.)
  A **saved build is re-scored when loaded** (`loadBuild` → `rescored()`): a save keeps the `match` /
  `achieved` of the rules it was found under, so the shown ones are recomputed with the search's own request
  mapping, stats grid and scorer (`WakfuBestBuildFinderAlgorithm.rescore`, no solver); the stored ones are
  only a fallback, and a score that moved drops the stored "proven optimal" flag (compared as the stored
  `Double`, not as `BigDecimal`), and so does a build that breaks an item EQUIP condition
  (`WakfuBestBuildFinderAlgorithm.equipConditionViolation`, which reads the catalog's criteria by item id: a save carries none —
  one made before the conditions were enforced may wear a nation sword without its ring). The E8 constructed-optimum swap stores
  that same `rescore`, so a swapped build reloads unchanged. The library cards and the compare view show the same re-score:
  `BuildSearchModel.rescoreLibrary` re-scores the saved builds off the UI thread when either view opens (cancellable, cached per
  entry + data + engine version, published as `UiState.libraryRescores`, read through `UiState.shownEntry`); the stored numbers
  show until it lands. A save made with other game data or an older `ENGINE_RESULTS_VERSION` gets the **obsolete** badge
  (`history/Obsolescence.kt`, `components/ObsoleteBuildCue.kt`) with its reasons and a "Re-run the search" action
  (`BuildSearchModel.rerunSearch`).
- **`AppShell`** (`shell/`) — `TopBar` (brand logo, language toggle, class, level/min-level, the
  progress + match/mastery meters, Search button) above a 3-column body:
  - **`RequestPanel`** (`request/`) — search mode, target-stats editor, constraints (per-rarity
    allow/exclude toggle chips, search duration, the "Check optimality after the search" switch…),
    forced / excluded item chips.
  - **`PaperdollPanel`** (`paperdoll/`) — the 14 equipment slots of the discovered build.
  - **`StatsPanel`** (`stats/`) — the headline hero (match `%` in precision mode, **cumulated
    requested mastery** in most-masteries mode), the optimality badge with its background-check cue
    (info tooltip + Stop link, §4), mastery summary, desired-vs-achieved grid, skill tree, and the
    Zenith open/copy actions.
  Long panels show conditional **scroll-hint** badges (`components/ScrollHints.kt`).
- **Visuals** (`components/`): `IconPreloader` decodes item icons off-thread into a cache;
  `rememberClasspathBitmap` loads PNGs from the classpath. `theme/` holds the dark palette
  (`WColor`/`WTypography`/`WDimens`). Branding assets live in `assets/branding/` (a translucent
  wordmark + a rounded-square "squircle" app icon).
- **i18n** (`i18n/I18n.kt`): a **hand-written `Tr` enum** carrying EN/FR strings; `tr(Tr.X)` resolves
  through the `LocalLang` composition local. **There is no generated i18n code.**
- **Screenshot smoke test**: setting `WAKFU_COMPOSE_SCREENSHOT=/path` (or the `wakfu.compose.screenshot`
  system property) renders the app to a PNG and exits (`testing/ScreenshotCapture`); in this mode
  warm-up gating is skipped so the real UI renders immediately.

> `docs/design-reference/` (HTML/CSS/JSX mockups + screenshots) is the **visual source of truth**.
> `styles-clean.css` + `Wakfu Autobuilder.html` are the primary design.

---

## 7. Build, run, test

JDK 25 required. Use the Gradle wrapper.

```sh
./gradlew build                                   # build everything
./gradlew test                                    # run all tests (CI runs the same set, sharded)
./gradlew ktlintCheck                             # lint  (ktlintFormat to auto-fix)

./gradlew :gui-compose:run                        # launch the Compose Desktop GUI
./gradlew :autobuilder:run --args="--help"        # CLI help
./gradlew :equipments-extractor:run               # regenerate the equipments JSON from Ankama CDN
./gradlew :spells-extractor:run                   # regenerate the class-spells JSON (scrapes encyclopedia; resumable)
./gradlew :bdata-extractor:run                    # regenerate spell cast-limits/passives + monsters JSON (decodes the local game binaries)
./gradlew :gui-compose:generateAssets             # (on demand) extract item/spell icons from the local client's gui.jar

# Compose GUI screenshot smoke-check (renders the app, writes a PNG, exits):
WAKFU_COMPOSE_SCREENSHOT=/tmp/out.png ./gradlew :gui-compose:run

# Example CLI search:
./gradlew :autobuilder:run --args="--level 110 --action-point 11 --movement-point 5 \
  --mastery-distance 500 --hp 2000 --range 2 --cc 30 --class cra --create-zenith-build --duration 60"
```

`./gradlew jar` produces `wakfu-autobuilder-cli.jar` (fat jar) for the CLI.

---

## 8. Packaging & release

- The GUI is packaged with **Conveyor** (`dev.hydraulic.conveyor`) into native installers for
  Windows / macOS / Linux. Config: `gui-compose/conveyor.conf` (release, publishes to the `gh-pages`
  branch) and `gui-compose/conveyor-local.conf` (local builds, no GitHub/OAuth). The app icon is
  generated by Conveyor from `assets/branding/app-icon.png`.
- Local: `./gradlew conveyorRun` (root task; targets `gui-compose`) — requires the `conveyor` CLI
  installed. Offline wiring check: `./gradlew :gui-compose:printConveyorConfig`. Single-platform
  build, e.g.: `conveyor -f gui-compose/conveyor-local.conf -Kapp.machines=mac.aarch64 make mac-app`.
- Builds are **unsigned** (no paid signing certificate; macOS gets an ad-hoc signature) — users must
  bypass OS security on first launch; keep that constraint in mind for any packaging change.
- CI: `.github/workflows/build.yml` runs the per-push tests on every push, split into parallel matrix shards
  (`-PciTestShard=<name>`; a catch-all `remaining` shard runs every class not assigned to a named one) behind one
  aggregate `build` check. Rebalancing: `docs/CI_TEST_SHARDS.md`.
  `.github/workflows/deploy.yml` builds jars + runs Conveyor against `gui-compose/conveyor.conf`
  (`make copied-site`). It is chained **automatically** by `release-please.yml` (via `workflow_call`)
  when merging the release PR publishes a release — releasing = merge the release-please PR, nothing
  else. It can also be run manually (`workflow_dispatch`) to re-deploy without a release.
- **Release notes.** `CHANGELOG.md` stays release-please's *technical* history; players read one short
  note per user-visible change in the What's new dialog, in the UI language. **Every feat/fix/perf
  change adds a `changes/unreleased/` note in EN + FR**: `changes/unreleased/<slug>.properties`
  (UTF-8) with `type=feat|fix|perf`, `en=`, `fr=` (required), `es=` / `scope=cli|gui` (optional) —
  format in CONTRIBUTING.md › Release notes.
  - Enforced by `ChangeFragmentsTest` (format, in `./gradlew test`) and the `changeset.yml` PR check
    (a feat/fix/perf commit or PR title needs an ADDED note; the `no-changeset` label waives it for
    internal-only changes; release-please's PR is exempt). Optional hook:
    `git config core.hooksPath scripts/git-hooks`.
  - Filing: `release-notes.yml` (called after every release-please run, and on human pushes to the
    release branch) moves `changes/unreleased/*` into `changes/<version>/` ON the release PR branch
    (`scripts/changesets/assign-release-notes.sh`), re-applied because release-please regenerates
    that branch whenever its notes change. Merging the release PR archives the notes; a note that
    raced the merge is filed under the release that shipped it by the next release PR.
  - `gui-compose`'s `generateReleaseNotes` compiles every note into `release-notes.json` (unreleased
    notes labelled as the version being built) for `ui/state/WhatsNew.kt`; releases ≤ 1.11 keep their
    CHANGELOG rendering. Never delete `changes/unreleased/.gitkeep` (it stops git from reading the
    filing as a directory rename). Scenario tests: `scripts/changesets/test-changesets.sh` (in CI).
- Dependencies are kept current by Dependabot (grouped Gradle + GitHub Actions PRs).

---

## 9. Conventions & gotchas

- **Kotlin official code style** (`gradle.properties`), enforced by **ktlint 14**. Run `ktlintFormat`
  before committing. The `generated/` package is excluded from linting.
- **OR-Tools native args.** The engine loads a native library, so any module that runs it
  (`autobuilder`, `gui-compose`) configures these JVM args in its `build.gradle.kts` for `run` and
  `test`: `--enable-native-access=ALL-UNNAMED`, `--add-opens=jdk.unsupported/sun.misc=ALL-UNNAMED`,
  `--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED`. Engine tests will fail to load the native
  lib without them.
- Package root is `me.chosante`. Versioning: the **CLI** version string tracks the **Wakfu data
  version** (`VERSION` in `autobuilder/Main.kt`); the **GUI** has its own semver
  (`gui-compose/build.gradle.kts`, currently `1.0.0`).
- Item names in `--forced-items` / `--excluded-items` and in the engine's filtering are matched in
  **French** (`equipment.name.fr`), regardless of UI language.
- The `gui-compose` `generateAssets` task reads the **local** Wakfu client (`contents/gui_jar/gui.jar`,
  via `-Pwakfu.install=`) — maintainer-local (like `bdata-extractor`), not part of the normal build, run on
  demand. It is no longer a network download.
- Tests use JUnit 5 + AssertJ (`autobuilder` also uses `kotlin-test`).
- **A tuned solve is reproducible only within one JVM run.** `TargetStats` is a `HashSet` whose order follows the
  `Characteristic` enum's identity hash, which depends on the JVM run (down to which tests ran before in the same JVM); the
  model's row order, and so CP-SAT's search, follows it. A tuned test close to its deterministic budget can pass alone and fail
  in a suite: give it headroom.
- There is no `LICENSE` file yet despite README references; contact is Discord `Chosante`.

---

## 10. Branches

| Branch | Purpose |
|---|---|
| `main` | Stable line: OR-Tools CP-SAT engine + Compose Desktop GUI, JDK 25. |
| `gh-pages` | Conveyor download site (generated). |
| `dependabot/*` | Automated dependency bumps. |
