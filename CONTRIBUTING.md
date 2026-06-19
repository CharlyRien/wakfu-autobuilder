# Contributing to Wakfu Autobuilder

Thanks for helping out! This guide covers the day-to-day workflow. For the deep architecture (the search
engine, the domain model, the GUI), read [`AGENTS.md`](AGENTS.md) — it's written for both humans and AI
assistants and is the single source of truth for how the codebase fits together.

## Prerequisites

- **JDK 25** (pinned in `gradle/libs.versions.toml`). Use the Gradle wrapper (`./gradlew`) — never a
  system Gradle.
- For running/regenerating the embedded game data: a local **Wakfu install** (only needed for the
  `bdata-extractor` step — see [Updating the game data](#updating-the-game-data)).

## Build, run, test

```sh
./gradlew build                              # build everything (heavy: Compose Desktop + native OR-Tools)
./gradlew test                               # what CI runs
./gradlew ktlintFormat                       # auto-fix style (run before every commit — CI style is strict)

./gradlew :gui-compose:run                   # launch the Compose Desktop GUI
./gradlew :autobuilder:run --args="--help"   # the CLI
```

Prefer **module-scoped** tasks while iterating (`:autobuilder:test`, `:gui-compose:run`) — a cold full
build resolves Compose Desktop + the native OR-Tools library and is slow.

> **OR-Tools is native.** Any module that runs the solver (`autobuilder`, `gui-compose`) already wires the
> required JVM args (`--enable-native-access=ALL-UNNAMED`, the two `--add-opens`) for `run`/`test`. The
> first launch pays a one-time native cold start; the GUI hides it behind a loading screen.

## Conventions

- **Kotlin official style**, enforced by **ktlint**. Run `./gradlew ktlintFormat` before committing.
- Package root is `me.chosante`. Item names in `--forced-items` / `--excluded-items` and in the engine's
  filtering are matched in **French** (`equipment.name.fr`), regardless of UI language.
- Tests: JUnit 5 + AssertJ (the engine also uses `kotlin-test`). Engine tests must use a deterministic
  `SolverTuning` (fixed det-time / seed / workers) or they flake on CI.
- **Commits:** each commit should be a real feature or fix; fold incidental chores (warning/lint fixes,
  deprecations) into the related commit rather than standalone `chore:` commits. Don't commit/push unless
  asked; the default branch is `main`.

## The embedded game data

The apps do **not** fetch game data at runtime — it is baked into `autobuilder/src/main/resources/` as
**fixed-name** JSON files (no version in the filename):

| File | Produced by | Source |
|---|---|---|
| `equipments.json` | `equipments-extractor` | Ankama CDN (`gamedata/<version>`) |
| `spells.json` | `spells-extractor` | Ankama encyclopedia (scraped) — name/element/AP/range/icon + the max-level base hit |
| `monsters.json`, `spell-cast-limits.json`, `spell-passives.json`, `sublimation-stacking.json`, `spell-damage.json` | `bdata-extractor` | the **local game client's** scrambled binaries (+ i18n names) |
| `monster-overlay.json` | *(committed overlay — boss-tier `rank` by monster id; the one editorial fact not in any client table. Everything else, incl. `gfx`, is decoded from bdata.)* | — |
| `sublimations.json` | `bdata-extractor` | effects/condition/max-level decoded from the local State (67) → StaticEffect (68) tables; identity/name/rarity/colours from the CDN `items.json` (itemTypeId 812) |
| `runes.json` | *(currently hand-maintained — no committed extractor)* | — |

The **data version** lives in exactly one place: [`common-lib/.../WakfuData.kt`](common-lib/src/main/kotlin/me/chosante/common/WakfuData.kt)
(`WakfuData.VERSION`). The apps stamp it as their `dataVersion`; the extractors fetch CDN assets for it.
Because the resource filenames are fixed, a version bump touches only that constant — no file renames, no
stale `*-v<old>.json` left behind.

### Updating the game data

After Ankama updates the client, **update your local Wakfu install first**, then run:

```sh
./scripts/update-game-data.sh [path-to-wakfu-install]   # default: /Applications/Ankama/Wakfu
```

It auto-detects the latest version from the CDN, bumps `WakfuData.VERSION`, regenerates every artifact in
dependency order (equipments → spells → sublimations → bdata [spell artifacts + monsters] → icons), then
tells you to review `git diff`, run `./gradlew test`, and commit.

This is **maintainer-local**: the `bdata-extractor` step decodes the local game binaries and **cannot run
in CI** (the binaries are never on the CDN), which is why the JSON it produces stays committed.

To regenerate a single dataset, run its extractor directly, e.g. `./gradlew :spells-extractor:run` or
`./gradlew :bdata-extractor:run --args="<install> <version>"`. The `bdata-extractor` compares its output
against the committed file and refuses to overwrite on any semantic drift — set `BDATA_FORCE_WRITE=1` to
accept an intentional change (the diff is printed first).

**Gotchas when bumping a version:**
- The `bdata-extractor` reads the **untagged** binary table layout (`Tables.kt` positional schemas,
  derived from `jac3km4/wakfu-bdata`). If a table's field schema drifts between client versions, its
  per-record size guard fails loudly — re-derive that table's schema from the reference and fix `Tables.kt`.
- Sublimation effects are decoded from bdata via a small action overlay + criterion-script parser
  (`SublimationBuilder.kt`). The solver-choosable set is validated by `SublimationReproductionTest` (the
  audited static subs must stay choosable; combat/scripted subs must stay forced-only). If a client bump
  changes a sublimation's structure, that test flags it — re-run it after regenerating.

## Project layout (quick reference)

Gradle multi-module; everyone depends on `common-lib` (pure domain model). The search engine lives in
`autobuilder` (Google OR-Tools CP-SAT — deterministic & optimal); the GUI is `gui-compose` (Compose
Desktop, built programmatically in Kotlin — no FXML). The standalone `*-extractor` modules regenerate the
embedded data. See [`AGENTS.md`](AGENTS.md) §2–§6 for the full map and the engine internals.
