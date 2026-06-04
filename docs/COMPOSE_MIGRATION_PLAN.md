# JavaFX → Compose Desktop — Migration Plan

**Goal:** replace the hand-made JavaFX `:gui` with a new **Compose Multiplatform for Desktop**
front-end that implements the Claude Design mockups **pixel-for-pixel**, while reusing the entire
Kotlin engine/domain unchanged.

**Read first:**
- `docs/UI_REDESIGN_HANDOFF.md` — product framing + the engine contract the UI binds to.
- `docs/design-reference/` — the design source of truth (copied from the Claude Design bundle):
  - `Wakfu Autobuilder.html` + `styles-clean.css` — the primary design ("Clean" theme).
  - `data.js` — the data model the mock uses (colors, slots, targets, skills).
  - `app.jsx`, `comp_core.jsx`, `comp_result.jsx` — component tree + state model + interactions.
  - `screenshots/` — `01-idle`, `02-search`, `03-done`, `11-radial`, the pickers, etc.
  - `tweaks-panel.jsx` / `styles.css` (cockpit) are **design-exploration scaffolding — ignore**;
    ship the *defaults* (copper accent, Manrope, standard depth, two-column paperdoll).

> Treat the JSX/CSS as a **visual spec, not code to port**. Recreate the look in idiomatic Compose;
> do not transliterate React.

---

## 1. Scope: reuse vs. replace

**Reuse untouched** (no engine work in this migration):
- `:common-lib`, `:autobuilder` (search engine + `computeCharacteristicsValues`), `:zenith-builder`.

**Replace** — everything in `:gui` is JavaFX-specific and goes away once the Compose app reaches
parity:

| Current `:gui` file | Fate | Why |
|---|---|---|
| `WakfuAutobuilderGUI.kt` (Application) | **delete** → new Compose `main()` + `Window` | JavaFX `Application` |
| `SplashScreen.kt` | delete (optional reimplement) | JavaFX animation |
| `components/**` (accordion, buildviewer, searchbar) | **delete** → new composables | JavaFX nodes |
| `eventbus/**`, `events/**` | **delete** | replaced by unidirectional state (§4) |
| `components/AutobuilderComputation.kt` | **delete** → logic moves into the state holder | — |
| `GuiCharacteristic.kt` | **adapt** → reuse the `Characteristic`↔label map for the stat catalog | useful mapping |
| `i18n/I18n.kt` + `generated/I18nKey.kt` + `i18n_*.properties` | **keep / adapt** | bundle approach is fine; route *all* strings through it |
| `GuiExtensions.kt` | review; port any still-useful helpers | — |
| `assets/**` (items, rarities, itemTypes, logo PNGs) | **keep** → move to Compose resources | real art |

**Recommendation:** build the Compose app in a **new module `gui-compose`** so `:gui` keeps building
until parity, then delete `:gui` in the final phase. The CLI (`:autobuilder`) is never affected.

---

## 2. Design system → Compose theme

Lift the exact tokens from `docs/design-reference/styles-clean.css` `:root`. Encode them once as a
Compose design-system (don't use stock Material colors).

```kotlin
// theme/Tokens.kt — single source of truth, mirrors styles-clean.css
object WColor {
    val bg        = Color(0xFF17191E)
    val surface   = Color(0xFF1E2128)
    val raised    = Color(0xFF262A32)
    val border    = Color(0xFF2C313A)
    val hairline  = Color(0xFF24282F)
    val text      = Color(0xFFE7E5E0)
    val muted     = Color(0xFF969BA5)
    val faint     = Color(0xFF5F656F)
    val accent    = Color(0xFFD98A45) // copper
    val accentPress = Color(0xFFC2783A)
    val accent2   = Color(0xFF45B8A6) // teal
    val success   = Color(0xFF5BA86A)
    val warning   = Color(0xFFD98C4D)
    val danger    = Color(0xFFD9655C)
    // elements
    val water = Color(0xFF5AA3DE); val fire = Color(0xFFD9655C)
    val earth = Color(0xFF7FAE5C); val air  = Color(0xFFA6B6C2)
}
object WRarityColor {                 // placeholders from data.js — confirm vs assets/rarities/*.png
    val common=Color(0xFFB9BEC7); val uncommon=Color(0xFF6FBF73); val rare=Color(0xFFE0913C)
    val mythic=Color(0xFFD9A441); val legendary=Color(0xFFE8C24A); val relic=Color(0xFFE0683C)
    val souvenir=Color(0xFF5FBFB0); val epic=Color(0xFFB07BD9)
}
object WDimens { val radius=12.dp; val gap=16.dp; val pad=20.dp }
```

**Typography** — bundle the fonts as resources (the mock pulls them from Google Fonts; desktop must
embed TTFs): **Manrope** (display + UI, weights 400–700) and **IBM Plex Mono** (all numeric values:
levels, stats, %). Map to a `Typography`/`WType` object. Headers/brand use Manrope 700.

Expose tokens via `CompositionLocal`s (or a top-level `object`) and wrap the app in a single
`WTheme { }`. Material 3 may be the substrate, but every surface/typography default is overridden by
these tokens.

---

## 3. Layout & component tree → composables

The mock's structure (from `app.jsx`) maps 1:1 to a Compose tree:

```
App (Window, "Wakfu Autobuilder", maximized)
└─ AppShell  Column
   ├─ TopBar        Row   → brand · Class dropdown · Lvl/min fields · Progress meter · Match meter · Search/Stop
   └─ BodyRow       Row   (3 columns, 1px hairline separators)
      ├─ RequestPanel   width 300–340  → ZoneHeader + scroll{ SearchModeSeg, TargetStatsCard, ConstraintsCard, ForcedItemsCard, ExcludedItemsCard }
      ├─ PaperdollPanel width 440–1fr  → ZoneHeader + Doll{ leftCol(5), CharArt, rightCol(5), bottomRail(4) } + Disclaimer
      └─ StatsPanel     width 330–380  → ZoneHeader + scroll{ MatchHero, (idle→EmptyHint | done→DesiredVsAchieved + SkillTree + Actions) }
   Overlays: Tooltip (hover), AddStatModal, ItemPickerModal, Toast
```

Implementation notes:
- **3-column body:** `Row` with the column min/max widths from CSS
  (`minmax(300,340) | minmax(440,1fr) | minmax(330,380)`). Each column = `Column { ZoneHeader();
  Box(Modifier.verticalScroll) { ... } }`. Separators = 1px `Divider` in `hairline`.
- **`Card`**: `Surface(color=surface, border=hairline, shape=RoundedCornerShape(radius))` with a
  small caps `card-title` (muted, with a trailing hairline line).
- **TopBar meters:** Progress (teal) + Match (green) — animate width with `animateFloatAsState`.
- **Search button:** copper, label "Search"; while `searching` becomes "Stop" + spinner.
- **Reusable bits:** `Dropdown`/`Menu` (class, max-rarity), `Toggle` (stop-at-match), `SegmentedTwo`
  (search mode), `GlyphChip` (28dp rounded stat glyph), `NumberField` (mono, right-aligned).

### Paperdoll (the centerpiece)
- Default layout = **two flanking columns + center character art + bottom rail of 4** (screenshots
  `01/02/03`). Left = helmet, amulet, shoulder pads, chest, cape. Right (text right-aligned) =
  emblem, belt, ring I, ring II, boots. Rail = weapon, second weapon, pet, mount.
- `Slot` = `Row { SlotIcon(42dp, item art + rarity badge dot), Column { type, name, "Lv X · Rarity" } }`.
  States: `empty` (dashed, 50% opacity), `filled` (rarity-tinted icon + border), `justLanded`
  (one-shot land animation), `idle` (whole doll dimmed to 45%).
- **`CharArt`** center panel: shows class name + "Level N" + a placeholder frame now; swap in
  class art later. Pulses (`searching`) while a search runs.
- **Radial layout** (`screenshots/11-radial.png`, `.doll-radial`) = optional Phase 6 nice-to-have:
  10 slots positioned on a circle via `Modifier.offset`/a custom `Layout`. Ship columns first.

### Stats panel
- **MatchHero**: big mono `match%` (green at 100, else neutral) + "Build Match" + bar.
- **Idle** → `EmptyHint` ("No build yet…"). **Done/Searching** → `DesiredVsAchieved` list of
  `StatRow`s + `SkillTree` + actions.
- **`StatRow`**: glyph · name(+"exact/maximize") · `achieved / target` (mono) · status icon
  (`✓` ok / `▾` miss / `↑` over-maximize) · thin progress bar. Color by status
  (success/warning/teal). Logic is in `comp_result.jsx::StatRow` — copy the thresholds.
- **`SkillTree`**: the 5 branches with `points/max` and per-line `current/max`.
- **Actions**: `Open in Zenith ↗` (teal, enabled only when `done`) + `Copy build link` (ghost) +
  inline error banner with Retry.

### Overlays
- **Tooltip** on slot hover → item fr/en, rarity tag, level, per-stat lines with element color dots.
  Use Compose `TooltipArea` or a cursor-following `Popup`.
- **AddStatModal** (filter + 2-col catalog) and **ItemPickerModal** (search FR/EN, Require/Ban) →
  `DialogWindow` or an overlay `Box` + `Popup`. See `comp_result.jsx`.
- **Toast** bottom-center, auto-dismiss.

---

## 4. State & engine wiring (the core of the migration)

The prototype **fakes** the search with a `setInterval` ramp (`app.jsx::runSearch`). Replace that
one function with **collecting the real engine `Flow`** — everything else in the state model maps
directly.

### UI state (replaces the event bus entirely — unidirectional)
```kotlin
enum class Phase { Idle, Searching, Done }

data class UiState(
    val clazz: CharacterClass = CharacterClass.CRA,
    val level: Int = 110, val minLevel: Int = 80,
    val mode: ScoreComputationMode = FIND_BUILD_WITH_MOST_MASTERIES_FROM_INPUT,
    val targets: List<TargetRow> = defaultTargets(),      // id, characteristic, target, kind, glyph, color
    val maxRarity: Rarity = Rarity.EPIC,
    val duration: Int = 20, val stopAtMatch: Boolean = false,
    val forced: List<Equipment> = emptyList(), val excluded: List<Equipment> = emptyList(),
    val phase: Phase = Phase.Idle,
    val progress: Int = 0, val match: BigDecimal = BigDecimal.ZERO,
    val build: BuildCombination? = null,                  // drives the paperdoll
    val achieved: Map<Characteristic, Int> = emptyMap(),  // drives Desired-vs-Achieved
    val lastLanded: ItemType? = null,                     // land animation
    val zenith: ZenithState = ZenithState.Idle,
    val modal: Modal? = null, val toast: String? = null,
)
```

### The state holder (replaces `AutobuilderComputation` + all `@Listener`s)
```kotlin
class BuildSearchModel(private val scope: CoroutineScope) {
    var ui by mutableStateOf(UiState()); private set
    private var job: Job? = null

    fun search() {
        job?.cancel()
        val s = ui
        val character = Character(s.clazz, s.level, s.minLevel)
        val params = WakfuBestBuildParams(
            character = character,
            targetStats = TargetStats(s.targets.map { TargetStat(it.characteristic, it.target) }),
            searchDuration = s.duration.seconds,
            stopWhenBuildMatch = s.stopAtMatch,
            maxRarity = s.maxRarity,
            forcedItems = s.forced.map { it.name.fr },     // engine matches on FRENCH name
            excludedItems = s.excluded.map { it.name.fr },
            scoreComputationMode = s.mode,
        )
        ui = ui.copy(phase = Phase.Searching, progress = 0, match = BigDecimal.ZERO)
        job = scope.launch(Dispatchers.Default) {
            // main branch: run(...) is `suspend`. linear-programming branch: it is NOT — drop `await`
            WakfuBestBuildFinderAlgorithm.run(params)
                .conflate()
                .onCompletion { withMain { ui = ui.copy(phase = Phase.Done) } }
                .collect { r ->
                    val achieved = computeCharacteristicsValues(
                        buildCombination = r.individual,
                        characterBaseCharacteristics = character.baseCharacteristicValues,
                        masteryElementsWanted = params.targetStats.masteryElementsWanted,
                        resistanceElementsWanted = params.targetStats.resistanceElementsWanted,
                    )
                    val landed = newlyLanded(ui.build, r.individual)
                    withMain {
                        ui = ui.copy(
                            progress = r.progressPercentage, match = r.matchPercentage,
                            build = r.individual, achieved = achieved, lastLanded = landed,
                        )
                    }
                }
        }
    }
    fun cancel() { job?.cancel(); ui = ui.copy(phase = Phase.Idle, progress = 0) }
}
// withMain { } = withContext(Dispatchers.Swing) { } — marshal back to the UI thread for Compose
```

- **Live fill is real and free:** the GA streams *best-so-far* continuously, so the paperdoll fills
  in and meters climb exactly like the mock — no simulation needed.
- **Newly-landed slot:** diff previous vs new `equipments` by `ItemType`/`equipmentId` to trigger the
  one-shot `justLanded` animation, then clear `lastLanded` after ~560ms.
- **Zenith action:** `ZenithInputParameters(character.copy(characterSkills = build.characterSkills),
  build.equipments).createZenithBuild()` (suspend) → loading → success toast / error banner + Retry.
- **Cancel** cancels the `Job` (cooperative; mirrors the JavaFX `job?.cancel()`).

---

## 5. Domain mapping (mock data → real types)

The mock invented names/colors. Bind to real domain instead. Concrete mappings the implementer must
apply:

**Slots** (`data.js SLOTS` → `ItemType`): helmet→HELMET, amulet→AMULET, epaul→SHOULDER_PADS,
chest→CHEST_PLATE, cape→CAPE, emblem→EMBLEM, belt→BELT, **ring1/ring2→RING (×2)**, boots→BOOTS,
weapon→ONE/TWO_HANDED_WEAPONS, **weapon2→OFF_HAND_WEAPONS** (empty when a 2H is equipped),
pet→PETS, mount→MOUNTS. (The `BuildViewer.kt` slot-placement logic — incl. the 1H/2H/off-hand rules
— is correct; reuse it as the reference.)

**Rarity** (mock keys → enum): `unusual→UNCOMMON`, `mythical→MYTHIC`, rest match. Order:
`COMMON<UNCOMMON<RARE<MYTHIC<LEGENDARY<RELIC<SOUVENIR<EPIC`. Build caps: ≤1 EPIC, ≤1 RELIC
(`BuildCombination.isValid()`).

**Skill branches** (mock → code): intel→`Intelligence`, strength→`Strength`, agility→`Agility`,
**fortune→`Luck`**, major→`Major`. The mock's skill *lines* are illustrative — use the real
`SkillCharacteristic` subclasses (see `SkillsTable.kt` for the exact per-branch list and the
`current/max` from `pointsAssigned`/`maxPointsAssignable`).

**Stat catalog & the "exact vs maximize" tag.** Don't store `kind` per stat — **derive it from the
mode**: in `most-masteries`, AP/MP/Range/Crit are *exact* and masteries/resistances are *maximize*;
in `precision`, *every* requested stat is *exact*. Build the "Add target stat" catalog from the real
`Characteristic` enum (reuse `GuiCharacteristic` for labels/grouping). `TargetStat.userDefinedWeight`
is not in the design — leave at default 1 (optional future "weight" control).

**Icons:** swap the placeholder glyphs for real art — `assets/items/<guiId>.png` (item),
`assets/rarities/<rarity>.png` (badge), `assets/itemTypes/<id>.png` (slot). **Still to source:**
per-characteristic stat icons and element chips (mock used text glyphs). Item art remains
community-sourced (Vertylo/wakassets) — keep the "not affiliated with Ankama" disclaimer.

---

## 6. Module & build setup (`gui-compose`)

```kotlin
// gui-compose/build.gradle.kts
plugins {
    kotlin("jvm")
    id("org.jetbrains.kotlin.plugin.compose") version "<kotlin-version>" // must match Kotlin 2.3.x
    id("org.jetbrains.compose") version "1.8.+"                          // pick current stable
    alias(libs.plugins.ktlint)
    application
}
dependencies {
    implementation(project(":autobuilder"))   // the engine
    implementation(project(":zenith-builder"))
    implementation(project(":common-lib"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(platform(libs.kotlinx.coroutine.bom))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing") // Dispatchers.Swing for Compose/Desktop
}
kotlin { jvmToolchain(libs.versions.jvm.get().toInt()) }   // 21 on main, 25 on linear-programming
application { mainClass = "me.chosante.ui.MainKt" }
```
- Register the module in `settings.gradle.kts`.
- **JVM target:** on `main` (JDK 21) Compose is well-supported. On `linear-programming` (JDK **25**),
  verify the chosen Compose/compose-compiler version supports the toolchain before committing; bump
  Compose if needed. This is the single biggest setup risk — validate in Phase 0.
- Suggested package layout under `gui-compose/src/main/kotlin/me/chosante/ui/`:
  `Main.kt`, `theme/`, `state/` (UiState, BuildSearchModel), `shell/` (TopBar, AppShell),
  `request/`, `paperdoll/`, `stats/`, `components/` (Dropdown, Toggle, GlyphChip, NumberField,
  Modal, Tooltip, Toast).

---

## 7. Phased execution

Each phase is independently demoable. Match the corresponding screenshot before moving on.

| Phase | Status | Deliverable | Done when |
|---|---|---|---|
| **0 · Spike** | ✅ | `gui-compose` module builds; empty `Window`; tokens + fonts load; **verify Compose on the target JDK** | window opens, Manrope/IBM Plex Mono render |
| **1 · Shell** | ✅ | TopBar + 3-column body scaffold + theme + ZoneHeaders | matches `01-idle` chrome (no data) |
| **2 · Request panel** | ✅ | Search-mode seg, target-stat rows + number fields + remove, constraints (rarity dropdown, duration, stop toggle), forced/excluded chips | left column == `01-idle` left |
| **3 · Engine wiring** | ✅ | `BuildSearchModel` collects the real `Flow`; TopBar meters + phase transitions live | Search runs, Progress/Match climb, phase idle→searching→done |
| **4 · Paperdoll** | ✅ | Two-column doll + char art + rail; **live fill** + land animation + idle/searching states + hover tooltip | matches `02-search`/`03-done` center |
| **5 · Stats panel** | ✅ | MatchHero, Desired-vs-Achieved (`computeCharacteristicsValues`), SkillTree, Zenith/Copy actions + error/toast | matches `03-done` right; "Open in Zenith" produces a real link |
| **6 · Modals & polish** | ✅ (radial TODO) | AddStat + ItemPicker modals (over real `Characteristic` enum + embedded equipment list, async-loaded with a spinner), empty/error states, keyboard, resize, optional radial layout | pickers work; resizes cleanly |
| **7 · i18n** | ✅ | en + fr; every string (incl. stat labels) via the bundle — typed `Tr` enum + `LocalLang` + live EN/FR toggle | language switch fully localized |
| **8 · Package & cutover** | 🚧 packaging wired | Conveyor (`gui-compose/conveyor.conf` + `dev.hydraulic.conveyor` plugin, validated via `printConveyorConfig`); **delete `:gui`** + README/`AGENTS.md` deferred until parity confirmed | installer runs; old JavaFX gone |

Also done outside the original plan: TopBar class dropdown + editable level/min (so the engine can search any class/level, not just the level-110 Cra default).

**Remaining before cutover:** optional radial layout (Phase 6), end-to-end real-search verification, then Conveyor `make` an actual installer and delete `:gui` + update README.

---

## 8. Packaging

Two options (decide at Phase 8):
- **Keep Conveyor** (`gui/conveyor.conf` → retarget to `gui-compose` jars). Preserves the gh-pages
  download site + auto-update. Most config carries over; it's still a JVM app.
- **Compose `nativeDistributions`** (jpackage) — simplest to bootstrap, but you'd re-create the
  download/update story.
Either way keep: unsigned-build reality (users bypass OS gatekeeper first launch), the logo icon, the
macOS min version. If targeting the **LP branch**, the installer must bundle the **native OR-Tools
library** and ship a **JDK 25** runtime — factor size/native-extraction in.

---

## 9. Risks & gotchas

- **Compose on JDK 25 (LP branch)** — verify compiler support up front (Phase 0). Lowest-risk path:
  do the migration on `main`/JDK 21 first; the preserved `Flow` contract carries it to the LP branch
  later with only the `suspend`-vs-not tweak on `run()`.
- **`run()` signature differs by branch** — `suspend` on `main`, plain on `linear-programming`; both
  return the same `Flow`. Collect inside a coroutine either way (the skeleton in §4 works for both).
- **UI-thread marshaling** — Compose/Desktop renders on the Swing EDT; do engine work on
  `Dispatchers.Default`, push state with `withContext(Dispatchers.Swing)` (don't touch `ui` off-EDT).
- **Live-fill flicker** — best-so-far updates churn; `conflate()` the flow and debounce/animate slot
  swaps so items don't strobe (mock uses a ~560ms land window).
- **Stat "kind" is mode-derived**, not intrinsic (§5) — get this right or the ✓/▾/↑ statuses lie.
- **Forced/excluded match on `name.fr`** — the picker shows localized names but must store/send FR.
- **Element/stat icons don't exist yet** — placeholder glyphs are acceptable until art is sourced;
  don't block the migration on them.

---

## 10. Definition of done

- New Compose desktop app reproduces the Claude Design mockups (idle / searching / done, pickers,
  tooltip, radial optional) with the §2 tokens — visually faithful, not stock Material.
- A real search runs end-to-end: configure → live progress + live paperdoll fill → discovered build
  with Desired-vs-Achieved stats + skills → working "Open in Zenith" link.
- Built on a single unidirectional state holder; **no event bus**.
- en + fr localized (all strings, incl. stat labels).
- Installer builds for ≥1 OS via the chosen pipeline.
- `:gui` (JavaFX) deleted; `README.md` + `AGENTS.md` updated to describe the Compose front-end.
```
