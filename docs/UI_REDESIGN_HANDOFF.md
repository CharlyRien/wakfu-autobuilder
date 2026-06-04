# UI Redesign Handoff — Wakfu Autobuilder

**Audience:** a fresh design/implementation session (e.g. "Claude design") with no prior context.
**Goal:** replace the dusty, hand-made JavaFX GUI with a modern, game-grade desktop UI inspired by
[zenithwakfu.com](https://zenithwakfu.com/builder/fnkbb), built with **Compose Multiplatform for
Desktop**.

Read `AGENTS.md` (repo root) first for the overall architecture. This document is the brief for the
**view layer only** — the engine and domain modules are reused untouched.

> **Status update:** the design has since been produced (Claude Design mockups live in
> `docs/design-reference/`) and turned into a concrete, step-by-step
> **`docs/COMPOSE_MIGRATION_PLAN.md`** — go there to execute. This file remains the *why/contract*
> background; the plan is the *how*.

---

## 0. TL;DR

- Build a new **Compose for Desktop** front-end that **reuses `:common-lib`, `:autobuilder`
  (engine), and `:zenith-builder`** as-is. Delete the old JavaFX `:gui` view layer once parity is
  reached.
- The visual **north star is Zenith** (dark, polished, "Wakfu paperdoll" with stat panels).
- **But the interaction model is inverted.** Zenith is a build *editor* (you place items → it shows
  stats). This tool is a build ***discoverer*** (you set *target stats + constraints* → it *searches*
  → it reveals the best build). Design the flow as **INPUT → SEARCH → RESULT**, not as an editor.
- The engine already exposes a `Flow` of progressively-improving results — lean into it with a
  **live-updating paperdoll** that fills in as the search runs.

---

## 1. What the product does (so the design serves it)

The user describes a character and what they want, then the app searches Wakfu's item database for
the optimal equipment combination.

**Inputs the user provides**
- Character **class** (18 options) and **level** (+ optional min level).
- **Target characteristics** — desired values for stats (AP, MP, range, crit, HP, the various
  masteries & resistances, lock/dodge/etc.).
- **Search mode**:
  - *Most-masteries* (default): hit exact AP/MP/range/crit, then **maximize** the chosen masteries.
  - *Precision*: hit exact target values for **every** requested stat.
- **Constraints**: max rarity, forced items, excluded items, search duration, "stop at 100% match".

**Output the app produces**
- A **`BuildCombination`**: up to 14 equipped items + an allocation of skill points across the 5
  branches.
- A **match %** (how well the found build meets the targets) and live **progress %**.
- A one-click **"Open in Zenith"** shareable build URL.

The redesign must make that INPUT → SEARCH → RESULT story obvious and pleasant.

---

## 2. Design north star & identity

Pull the *aesthetic* from Zenith and the *brand* from the app's own logo
(`gui/src/main/resources/logo.png` — a steampunk Steamer kid: copper/bronze, teal goggles, dark
leather, warm cream wordmark).

**Mood:** dark, tactile, game-UI, "theorycrafting cockpit". Not a generic Material form.

### Suggested design tokens (starting point — tune in-session)
```
Background      #15171C   (near-black slate)
Surface         #1F2229
Surface raised  #272B33
Border/hairline #343A44
Text primary    #ECE8E1   (warm off-white)
Text muted      #9AA0AC
Primary accent  #E08A3C   (copper/orange, from the logo)
Primary pressed #C8772E
Secondary accent#3FC7B4   (teal, from the goggles)
Success/match   #46A758
Warning/miss    #E5894D
```

**Wakfu element colors** (water / fire / earth / air) and **rarity colors** are part of the visual
language — *confirm exact values against the live game / Zenith*. Don't invent them: the repo already
ships the canonical **rarity badges** (`assets/rarities/<rarity>.png`) and **slot icons**
(`assets/itemTypes/<id>.png`); derive accent colors from those.

**Typography:** one display face for the wordmark/headers (slightly characterful) + one clean UI
sans for data. The current `Calibri Light` is a placeholder — replace it.

---

## 3. Proposed information architecture

A **three-zone** desktop layout. Left = what you ask for. Center = what you got. Right = the numbers.

```
┌───────────────────────────────────────────────────────────────────────────────────┐
│  ◆ Wakfu Autobuilder            [ Class ▾ ]  Lvl [110]      ⟳ Search   �(62%)──────  │  top bar
├──────────────────┬──────────────────────────────────────┬───────────────────────────┤
│  REQUEST (input) │            DISCOVERED BUILD           │   RESULTING STATS         │
│                  │              (paperdoll)              │                           │
│  Search mode     │                                       │  Match  ███████░░  78%    │
│   ◉ Most mast.   │   [Helmet]   ( character )   [Emblem] │                           │
│   ○ Precision    │   [Amulet]   (  silhouette )  [Belt ] │  AP   11/11  ✓            │
│                  │   [Should]   (   /art     )  [Ring1] │  MP    5/5   ✓            │
│  Target stats    │   [Chest ]                   [Ring2] │  Range 2/2   ✓            │
│   AP      [11]   │   [Cape  ]                   [Boots] │  Dist. mast. 512 �‹max›   │
│   MP      [ 5]   │                                       │  HP    2034/2000 ✓       │
│   Range   [ 2]   │   [Weapon 1] [Weapon 2] [Pet] [Mount] │  …                        │
│   Dist.m. [500]  │                                       │  ── Skills ──             │
│   + add stat ⊕   │   tooltip on hover: item stats        │  Major / Int / Str …      │
│                  │                                       │                           │
│  Constraints     │                                       │  [ Open in Zenith ↗ ]     │
│   Max rarity ▾   │                                       │  [ Copy link ]            │
│   Duration  [20] │                                       │                           │
│   Forced / Excl. │                                       │                           │
└──────────────────┴──────────────────────────────────────┴───────────────────────────┘
```

Notes:
- **Search is the hero action.** Big, always-visible, with the live progress bar + match %.
- **Paperdoll**, not a flat 2-column card grid (the current weakness). Slots arranged around a
  central character art/silhouette, weapons/pet/mount on a bottom rail. Mirror Zenith's slot
  positions where it helps recognition.
- **Live fill:** as the engine streams better builds, items animate into their slots and the stat
  panel re-computes. This is a signature moment — make it feel alive.
- **Desired vs actual** on every stat: show target and achieved with a ✓ / shortfall, color-coded
  (`Success` when met, `Warning` when below). This replaces the current green/coral table rows.
- **Empty state:** before the first search, show the paperdoll as dimmed silhouettes (reuse the
  current blurred-placeholder idea, done well).

---

## 4. The engine contract the UI binds to

**You do not need to touch the algorithm.** It is already a coroutine `Flow` API. Wire it to Compose
state and you're done.

### Inputs — build a `WakfuBestBuildParams`
```kotlin
WakfuBestBuildParams(
    character = Character(clazz, level, minLevel),     // me.chosante.common
    targetStats = TargetStats(listOf(                  // me.chosante.autobuilder.domain
        TargetStat(Characteristic.ACTION_POINT, target = 11, userDefinedWeight = 1),
        TargetStat(Characteristic.MASTERY_DISTANCE, target = 500),
        // …one per stat the user requested
    )),
    searchDuration = 20.seconds,
    stopWhenBuildMatch = false,
    maxRarity = Rarity.EPIC,
    forcedItems = emptyList(),      // matched on the FRENCH item name (equipment.name.fr)
    excludedItems = emptyList(),
    scoreComputationMode = ScoreComputationMode.FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT,
)
```

### Run it — collect the result `Flow`
```kotlin
// me.chosante.autobuilder.genetic.wakfu.WakfuBestBuildFinderAlgorithm
WakfuBestBuildFinderAlgorithm.run(params)   // Flow<GeneticAlgorithmResult<BuildCombination>>
    .conflate()
    .collect { result ->
        result.matchPercentage      // BigDecimal, 0..100  → match meter
        result.progressPercentage   // Int, 0..100         → progress bar
        result.individual           // BuildCombination     → paperdoll + skills
    }
```
- `BuildCombination.equipments: List<Equipment>` → place each by `equipment.itemType` into its slot
  (two `RING`s, weapon rules per `ItemType`). Icon = `assets/items/${equipment.guiId}.png`,
  rarity badge = `assets/rarities/${rarity}.png`, slot icon = `assets/itemTypes/${itemType.id}.png`.
- `BuildCombination.characterSkills` → the skills panel (5 branches; `allCharacteristic` lists each
  with `pointsAssigned` / `maxPointsAssignable`).

### Computed totals for the stat panel
Reuse the engine helper the old UI used:
```kotlin
// me.chosante.autobuilder.genetic.wakfu.computeCharacteristicsValues(...)
computeCharacteristicsValues(
    buildCombination = result.individual,
    characterBaseCharacteristics = character.baseCharacteristicValues,
    masteryElementsWanted = params.targetStats.masteryElementsWanted,
    resistanceElementsWanted = params.targetStats.resistanceElementsWanted,
) // -> Map<Characteristic, Int> of achieved values
```

### Zenith link (the "share" action)
```kotlin
// me.chosante.zenith-builder
ZenithInputParameters(
    character = character.copy(characterSkills = result.individual.characterSkills),
    equipments = result.individual.equipments,
).createZenithBuild()   // suspend -> shareable URL (String). Network; wrap in try/catch + spinner.
```

> **Engine-agnostic by design.** On `main` the engine is a genetic algorithm and
> `WakfuBestBuildFinderAlgorithm.run` is `suspend`; on the `linear-programming` branch it's an
> OR-Tools solver and `run` is **non-suspend** but returns the **same `Flow` type**. Bind to the
> `Flow` and the UI works on either. (GA streams *best-so-far* continuously → great for live fill;
> the solver streams improving solutions via solution callbacks → also fine.) See `AGENTS.md` §4.

---

## 5. Recommended Compose structure

Create a **new module** (suggested `gui-compose`) so the old `:gui` keeps working until parity, then
remove `:gui`. The CLI (`:autobuilder`) is unaffected throughout.

```
gui-compose/
  build.gradle.kts        // org.jetbrains.compose plugin + compose.desktop.currentOs
  src/main/kotlin/me/chosante/ui/
    App.kt                // root @Composable + window
    theme/                // colors, typography, shapes (the design tokens above)
    state/
      BuildSearchViewModel.kt   // holds UiState, owns the search coroutine Job
      UiState.kt
    screens/
      request/            // class/level, mode, target-stat editor, constraints
      paperdoll/          // slots + character art + live fill + item tooltips
      stats/              // desired-vs-actual panel, skills, Zenith actions
    components/           // StatRow, SlotCard, RarityBadge, MatchMeter, …
```

**State, not an event bus.** The old GUI glued widgets together with a custom pub/sub
(`DefaultEventBus`, `@Listener`). Replace it with **unidirectional state**: a `ViewModel`/state
holder exposing a `StateFlow<UiState>`; the search coroutine updates it; composables read it.

```kotlin
class BuildSearchViewModel(private val scope: CoroutineScope) {
    var ui by mutableStateOf(UiState())
        private set
    private var job: Job? = null

    fun search(params: WakfuBestBuildParams) {
        job?.cancel()
        ui = ui.copy(phase = Phase.Searching, progress = 0)
        job = scope.launch(Dispatchers.Default) {
            WakfuBestBuildFinderAlgorithm.run(params).conflate().collect { r ->
                ui = ui.copy(
                    match = r.matchPercentage, progress = r.progressPercentage,
                    build = r.individual,
                )
            }
            ui = ui.copy(phase = Phase.Done)
        }
    }
    fun cancel() { job?.cancel(); ui = ui.copy(phase = Phase.Idle) }
}
```

**Assets:** existing PNGs under `gui/src/main/resources/assets/` (items, rarities, itemTypes, logo)
can be copied into the new module's resources and loaded with `painterResource` / `loadImageBitmap`.

**Theming:** Material 3 dark as a base is fine, but the identity should come from a **custom design
system** (the tokens in §2), not stock Material. Keep it cohesive with the steampunk logo.

---

## 6. Assets — have vs. need

**Have** (`gui/src/main/resources/assets/`):
- `items/<guiId>.png` — item icons (used at ~40px today).
- `rarities/<rarity>.png` — 8 rarity badges.
- `itemTypes/<id>.png` — slot-type icons (incl. `-1.png`, `-2.png`).
- `logo.png` — 512×512 brand mark / splash.

**Likely need to source or design:**
- **Per-characteristic / stat icons** (AP, MP, range, crit, the masteries & resistances). The
  current UI shows stats as plain text — a Zenith-grade panel wants small stat glyphs.
- **Element color chips/icons** (water/fire/earth/air) for elemental masteries & resistances.
- A **character silhouette / paperdoll backdrop** (or per-class art) for the center zone.
- Empty-slot placeholders per slot type (today: a single `items/0000000.png` fallback).

Flag licensing: game art is Ankama's. The repo already pulls item icons from the community
[`Vertylo/wakassets`](https://github.com/Vertylo/wakassets) repo (see the `generateAssets` Gradle
task) — reuse that source; keep the existing unofficial-app disclaimer visible.

---

## 7. i18n

Today's i18n is half-done: an `i18n_{en,fr}.properties` bundle feeds a generated `I18nKey` enum, but
many strings bypass it (`GuiCharacteristic` stat names are hardcoded English with a `// TODO: I18N`,
plus literals like "Minimum Level", "Weapon 2", "Level $level"). The roadmap explicitly wants
**French** support.

For the redesign: pick a clean Compose-friendly i18n approach (e.g. a `Strings` provider with
`en`/`fr` maps, or Compose resources) and route **all** user-facing text + every stat label through
it from day one. Don't reproduce the half-migrated state.

---

## 8. Packaging (don't break releases)

The app ships as **native installers via Conveyor** (`gui/conveyor.conf`), unsigned, published to the
`gh-pages` download site. Two viable paths — decide in-session:
- **Conveyor on the Compose JVM app** (keeps the current release pipeline & download site). Point
  Conveyor at the new module's jars; most config carries over.
- **Compose's own `nativeDistributions`** (jpackage) — simpler to start, but you'd re-do the
  download-site/auto-update story.

Either way, keep: unsigned-build reality (users bypass OS gatekeeper on first launch), the icon, and
the macOS min-version. **Heads-up:** if/when the `linear-programming` branch lands, packaging must
bundle the **native OR-Tools library** and the app targets **JVM 25** — size and native-lib
extraction matter for the installer.

---

## 9. Suggested phasing

1. **Scaffold** `gui-compose` module + window + theme tokens; render a static mock with sample data.
2. **Wire the engine**: `BuildSearchViewModel` collecting `WakfuBestBuildFinderAlgorithm.run(...)`;
   prove the live progress + match meter update.
3. **Request panel**: class/level, mode toggle, dynamic target-stat editor, constraints.
4. **Paperdoll**: slot layout + icons/rarity/tooltips + **live fill** animation during search.
5. **Stats panel**: desired-vs-actual rows (reuse `computeCharacteristicsValues`) + skills tree +
   **Open in Zenith** action.
6. **Polish**: empty/searching/done states, errors (Zenith call failure), keyboard, resizing.
7. **i18n** pass (en/fr) routing all strings.
8. **Packaging** parity, then **delete `:gui`** (old JavaFX) and update README + `AGENTS.md`.

---

## 10. Decisions to settle in the design session

- **Single vs. multiple results.** The engine returns one best `BuildCombination`. Worth surfacing
  "alternatives"? (Would need engine support — out of scope unless asked.)
- **Live paperdoll churn.** Continuous best-so-far updates look alive but can flicker; decide on
  debounce/animation so items don't strobe between slots.
- **How to present "match %"** vs per-stat ✓/shortfall — which is the primary signal?
- **Forced/excluded item pickers** need item search (names are French in data; show localized name,
  match on `fr`).
- **Class-specific character art** vs a single neutral silhouette for the paperdoll center.
- **Target the `linear-programming` branch** as the base if it's about to merge (JVM 25 + OR-Tools),
  or build on `main` (GA) first and let the preserved `Flow` contract carry you over later.

---

## 11. Definition of done

- New Compose desktop app reaches feature parity with the JavaFX GUI: configure a search, run it with
  live progress, see the discovered build as a paperdoll with desired-vs-actual stats + skills, and
  open it in Zenith.
- Cohesive dark, Wakfu-flavored visual system (not stock Material); responsive to window resize.
- All user-facing strings localized (en + fr).
- Packaged installer builds for at least one OS via the chosen pipeline.
- Old `:gui` module removed; `README.md` and `AGENTS.md` updated to describe the Compose front-end.
```
