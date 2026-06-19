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
  (`I18nText` fr/en/es/pt), `rarity`, `itemType`, `characteristics: Map<Characteristic, Int>`.
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

`BuildCombination` (in `autobuilder/domain`) = `equipments + characterSkills`, with `isValid()`
enforcing slot/rarity/weapon rules.

---

## 4. The search engine (`autobuilder`)

The engine is the **Google OR-Tools CP-SAT solver**. It streams its best-so-far build as a
`Flow<GeneticAlgorithmResult<BuildCombination>>` — a legacy type name; there is now a single engine,
so the CLI and GUI consume it identically.

`WakfuBestBuildFinderAlgorithm.run(params)` is the entry point: it filters & groups the embedded
equipments by `ItemType` (applying level/rarity/forced/excluded filters), then hands them to the
solver.

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

---

## 5. Data pipeline

Item data is **not** fetched at runtime by the apps — it is baked into `autobuilder/src/main/resources/`
as **fixed-name** JSON files (no version in the filename):

1. `equipments-extractor` downloads `items.json`, `equipmentItemTypes.json`, `actions.json`,
   `recipeCategories.json` from `https://wakfu.cdn.ankama.com/gamedata/:version` (auto-detecting the
   latest version) and writes `equipments.json` (the `Equipment` list).
2. `spells-extractor` → `spells.json`. (Monsters are no longer scraped — see `bdata-extractor` below.)
3. `WakfuBestBuildFinderAlgorithm` / `SpellCatalog` / `PassiveCatalog` load these by fixed name via the
   classpath at startup (e.g. `equipments.json`).
4. The GUI's `generateAssets` Gradle task (`gui-compose`) downloads matching item icons from the
   [`Vertylo/wakassets`](https://github.com/Vertylo/wakassets) repo into
   `gui-compose/src/main/resources/assets/items/<guiId>.png`.
5. `bdata-extractor` decodes the **local game client's** scrambled static-data tables — `Spell` (66),
   `StaticEffect` (68), `State` (67), `Monster` (42) inside `contents/bdata/<id>.jar`, plus the
   `contents/i18n/i18n_<lang>.jar` name bundles — and writes `spell-cast-limits.json`,
   `spell-passives.json`, `sublimation-stacking.json` (per-sublimation `max_level` + `is_cumulable`),
   **`sublimations.json`**, **`spell-damage.json`**, and **`monsters.json`** (boss-mode data: level/HP/flat
   elemental resistances + localized name/family + icon `gfx`, replacing the old third-party MethodWakfu/Fandom
   scrape).
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
- **`AppShell`** (`shell/`) — `TopBar` (brand logo, language toggle, class, level/min-level, the
  progress + match/mastery meters, Search button) above a 3-column body:
  - **`RequestPanel`** (`request/`) — search mode, target-stats editor, constraints (per-rarity
    allow/exclude toggle chips, search duration…), forced / excluded item chips.
  - **`PaperdollPanel`** (`paperdoll/`) — the 14 equipment slots of the discovered build.
  - **`StatsPanel`** (`stats/`) — the headline hero (match `%` in precision mode, **cumulated
    requested mastery** in most-masteries mode), mastery summary, desired-vs-achieved grid, skill
    tree, and the Zenith open/copy actions.
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
./gradlew test                                    # run all tests (this is what CI runs)
./gradlew ktlintCheck                             # lint  (ktlintFormat to auto-fix)

./gradlew :gui-compose:run                        # launch the Compose Desktop GUI
./gradlew :autobuilder:run --args="--help"        # CLI help
./gradlew :equipments-extractor:run               # regenerate the equipments JSON from Ankama CDN
./gradlew :spells-extractor:run                   # regenerate the class-spells JSON (scrapes encyclopedia; resumable)
./gradlew :bdata-extractor:run                    # regenerate spell cast-limits/passives + monsters JSON (decodes the local game binaries)
./gradlew :gui-compose:generateAssets             # (on demand) download item icons from wakassets

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
- CI: `.github/workflows/build.yml` runs `./gradlew test` on every push.
  `.github/workflows/deploy.yml` (manual `workflow_dispatch`) builds jars + runs Conveyor against
  `gui-compose/conveyor.conf` (`make copied-site`).
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
- The `gui-compose` `generateAssets` task hits the network (downloads the wakassets zip) — it is not
  part of the normal build and is run on demand.
- Tests use JUnit 5 + AssertJ (`autobuilder` also uses `kotlin-test`).
- There is no `LICENSE` file yet despite README references; contact is Discord `Chosante`.

---

## 10. Branches

| Branch | Purpose |
|---|---|
| `main` | Stable line: OR-Tools CP-SAT engine + Compose Desktop GUI, JDK 25. |
| `gh-pages` | Conveyor download site (generated). |
| `dependabot/*` | Automated dependency bumps. |
