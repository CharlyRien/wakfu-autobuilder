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
- a **JavaFX desktop GUI** (`gui` module)

---

## 2. Module map

Gradle multi-module, Kotlin, JVM toolchain pinned in the version catalog (`settings.gradle.kts`).

```
common-lib            Pure domain model. No project deps. Everyone depends on it.
  ▲
  ├── equipments-extractor   Standalone tool: pulls Wakfu game data from Ankama's CDN
  │                          and regenerates the embedded equipments JSON.
  ├── zenith-builder         Talks to the zenithwakfu.com builder API to create a build URL.
  │     ▲
  ├── autobuilder            CLI + the SEARCH ENGINE. Depends on common-lib + zenith-builder.
  │     ▲                    Embeds the equipments-vX.Y.Z.json data file as a resource.
  └── gui                    JavaFX desktop app. Depends on autobuilder + zenith-builder + common-lib.
```

| Module | Type | Entry point | Key libs |
|---|---|---|---|
| `common-lib` | library | — | kotlinx-serialization |
| `equipments-extractor` | app | `me.chosante.equipmentextractor.MainKt` | Fuel (HTTP), serialization |
| `zenith-builder` | library | — | Fuel, coroutines, serialization |
| `autobuilder` | app | `me.chosante.autobuilder.MainKt` | Clikt + Mordant (CLI), coroutines |
| `gui` | app | `me.chosante.WakfuAutobuilderGUIKt` | JavaFX 21, AtlantaFX, Ikonli, Conveyor |

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

### Current approach on `main`: **genetic algorithm**
- `genetic/GeneticAlgorithm.kt`: generic GA — population → score → select → cross → mutate, looped
  until a time budget elapses (or 100 % match if `stopWhenBuildMatch`). Runs scoring in parallel
  coroutines. Emits a `Flow<GeneticAlgorithmResult<BuildCombination>>` carrying the best-so-far
  individual, `matchPercentage`, and `progressPercentage`.
- `genetic/wakfu/`: the Wakfu specialization — `Population`, `Cross`, `Mutation`, `Selection`
  (tournament), and the two scorers.
- `WakfuBestBuildFinderAlgorithm.run(params)` is the entry point: it filters & groups the embedded
  equipments by `ItemType` (applying level/rarity/forced/excluded filters) and runs the GA.

### Two scoring modes (`ScoreComputationMode`)
- `FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT` ("most-masteries") — default. Constrain AP/MP/range/
  crit to exact targets, then **maximize** the requested masteries. Scorer:
  `FindMostMasteriesFromInputScoring`.
- `FIND_CLOSEST_BUILD_FROM_INPUT` ("precision") — hit exact target values for *every* requested
  characteristic. Scorer: `FindClosestBuildFromInputScoring`.

### Inputs: `WakfuBestBuildParams`
`character`, `targetStats: TargetStats`, `searchDuration`, `stopWhenBuildMatch`, `maxRarity`,
`forcedItems`, `excludedItems`, `scoreComputationMode`. `TargetStats` normalizes per-stat weights
and expands `MASTERY_ELEMENTARY` / `RESISTANCE_ELEMENTARY` into their four elements.

### ⚠️ `linear-programming` branch rewrites the engine
The `linear-programming` branch replaces the GA with a **Google OR-Tools CP-SAT solver**
(`genetic/wakfu/WakfuBuildSolver.kt`, ~880 LOC) for a *deterministic, optimal* search:
- Adds `com.google.ortools:ortools-java`; **bumps the JVM toolchain 21 → 25**; ktlint 11 → 14;
  Wakfu data `1.89.1.41 → 1.91.1.53`.
- `WakfuBestBuildFinderAlgorithm.run()` becomes non-suspend and delegates to
  `WakfuBuildSolver.optimize()`, which streams solutions via `callbackFlow` — **the
  `Flow<GeneticAlgorithmResult<BuildCombination>>` contract is preserved**, so the CLI/GUI keep
  working. Currently only the "most-masteries" mode is supported by the solver.
- Tests need extra JVM args (`--enable-native-access=ALL-UNNAMED`, `--add-opens …`) and the native
  OR-Tools library is loaded at runtime (`Loader.loadNativeLibraries()`).

When touching the engine, check **which branch you are on** — the two implementations are very
different even though they share types and the output Flow.

---

## 5. Data pipeline

Item data is **not** fetched at runtime by the apps — it is baked in:

1. `equipments-extractor` downloads `items.json`, `equipmentItemTypes.json`, `actions.json`,
   `recipeCategories.json` from `https://wakfu.cdn.ankama.com/gamedata/:version`.
2. It writes `autobuilder/src/main/resources/equipments-v<version>.json` (the `Equipment` list).
3. `WakfuBestBuildFinderAlgorithm` loads that resource via the classpath at startup
   (`equipments-v$VERSION.json`, where `VERSION` is a const in `autobuilder/Main.kt`).
4. The GUI's `generateAssets` Gradle task downloads matching item icons from the
   [`Vertylo/wakassets`](https://github.com/Vertylo/wakassets) repo into
   `gui/src/main/resources/assets/items/<guiId>.png`.

**Updating to a new Wakfu version** = run the extractor, bump `VERSION` in `autobuilder/Main.kt`
(and the version string used to locate the resource), then run `generateAssets`.

---

## 6. GUI architecture (`gui`)

JavaFX 21, themed with **AtlantaFX** (`NordDark`) + **Ikonli** (feather icons). The UI is built
**programmatically in Kotlin — there is no FXML.**

- `WakfuAutobuilderGUI` (`Application`): splash screen → `BorderPane` with
  `top = SearchBox`, `center = SplitPane(Accordion | BuildViewer)`, `bottom = disclaimer`.
- Left **`Accordion`** of four panes: `BuildParamsBox` (class, mode, level, duration, rarity…),
  `CharacteristicTable` (editable *desired vs actual* stat grid; rows go green/coral on match),
  `SkillsTable` (TreeTable of the 5 skill branches), `ItemsForcedTable`.
- Right **`BuildViewer`**: a 2-column grid of 14 `EquipmentCard`s, blurred behind a placeholder
  until a search runs.
- Top **`SearchBox`**: search / cancel buttons, progress bar, match-% label, "create Zenith build"
  button, resulting build hyperlink.

**Communication is a custom event bus** (`eventbus/DefaultEventBus`, `@Listener`, `Event.publish`).
The search runs off-thread (`AutobuilderComputation`, `Dispatchers.Default`); each component
subscribes to `AutobuildStart/Update/End/Cancel`, `ZenithBuildCreated`, `Browse` events and updates
itself on `Dispatchers.JavaFx`. There is **no shared view-model** — state lives inside the widgets.

**i18n**: `i18n_en.properties` / `i18n_fr.properties` → a Gradle task (`generateKotlinI18nKeys`)
generates the `generated.I18nKey` enum at compile time; `I18n.valueOf(key)` resolves at runtime.
i18n is **incomplete** — `GuiCharacteristic` stat names are hardcoded English (`// TODO: I18N`) and
several UI strings ("Minimum Level", "Weapon 2", "Level $level", …) bypass the bundle.

> **A complete UI redesign to Compose Desktop is underway.** For any GUI work, read in order:
> 1. `docs/COMPOSE_MIGRATION_PLAN.md` — the step-by-step JavaFX → Compose plan (start here).
> 2. `docs/UI_REDESIGN_HANDOFF.md` — product framing + the engine/UI contract.
> 3. `docs/design-reference/` — the Claude Design mockups (HTML/CSS/JSX + screenshots) = visual
>    source of truth. `styles-clean.css` + `Wakfu Autobuilder.html` are the primary design.

---

## 7. Build, run, test

Java 21+ required (Java 25 on the `linear-programming` branch). Use the Gradle wrapper.

```sh
./gradlew build                                   # build everything
./gradlew test                                    # run all tests (this is what CI runs)
./gradlew ktlintCheck                             # lint  (ktlintFormat to auto-fix)

./gradlew :gui:run                                # launch the JavaFX GUI
./gradlew :autobuilder:run --args="--help"        # CLI help
./gradlew :equipments-extractor:run               # regenerate the equipments JSON from Ankama CDN

# Example CLI search:
./gradlew :autobuilder:run --args="--level 110 --action-point 11 --movement-point 5 \
  --mastery-distance 500 --hp 2000 --range 2 --cc 30 --class cra --create-zenith-build --duration 60"
```

`./gradlew jar` produces `wakfu-autobuilder-cli.jar` (fat jar) for the CLI.

---

## 8. Packaging & release

- The GUI is packaged with **Conveyor** (`dev.hydraulic.conveyor`) into native installers for
  Windows / macOS / Linux. Config: `gui/conveyor.conf` (release, publishes to the `gh-pages`
  branch) and `gui/conveyor-local.conf` (local). Root task `./gradlew conveyorRun` runs it locally.
- Builds are **unsigned** (no paid signing certificate) — users must bypass OS security on first
  launch; keep that constraint in mind for any packaging change.
- CI: `.github/workflows/build.yml` runs `./gradlew test` on every push.
  `.github/workflows/deploy.yml` (manual dispatch) builds jars + runs Conveyor.
- Dependencies are kept current by Dependabot (grouped Gradle + GitHub Actions PRs).

---

## 9. Conventions & gotchas

- **Kotlin official code style** (`gradle.properties`), enforced by **ktlint**. Run `ktlintFormat`
  before committing. The `generated/` package is excluded from linting.
- Package root is `me.chosante`. Versioning: the CLI version string tracks the **Wakfu data
  version**; the GUI has its own semver (`gui/build.gradle.kts`).
- Item names in `--forced-items` / `--excluded-items` and in the engine's filtering are matched in
  **French** (`equipment.name.fr`), regardless of UI language.
- The `gui` `generateAssets` task hits the network (downloads the wakassets zip) — it is not part of
  the normal build and is run on demand.
- Tests use JUnit 5 + AssertJ (`autobuilder` also uses `kotlin-test`). On `linear-programming`,
  engine tests require the OR-Tools JVM args noted in §4.
- There is no `LICENSE` file yet despite README references; contact is Discord `Chosante`.

---

## 10. Branches

| Branch | Purpose |
|---|---|
| `main` | Stable. Genetic-algorithm engine, Wakfu data `1.89.1.41`, JVM 21. |
| `linear-programming` | **Major engine rewrite** to OR-Tools CP-SAT (deterministic optimal). JVM 25, data `1.91.1.53`. See §4. |
| `update-1.89` | Wakfu data/version bump work. |
| `gh-pages` | Conveyor download site (generated). |
| `dependabot/*` | Automated dependency bumps. |
