# CLAUDE.md

This file gives Claude Code its project context. The full agent guide lives in `AGENTS.md` —
it is imported below so both files stay in sync.

@AGENTS.md

## Claude Code working notes (project-specific)

- **Engine.** The default solver is the Google OR-Tools CP-SAT solver
  (`autobuilder/.../WakfuBuildSolver.kt`, deterministic & optimal); the original genetic algorithm
  is still selectable via the `WakfuSolver` parameter. Both stream the same
  `Flow<GeneticAlgorithmResult<BuildCombination>>`. See `AGENTS.md` §4.
- **OR-Tools is native.** It loads a native library at runtime, so running/testing the engine needs
  extra JVM args (`--enable-native-access=ALL-UNNAMED`, `--add-opens …`) — already wired in the
  `autobuilder` and `gui-compose` build scripts. The first search pays a one-time cold start; the
  GUI hides it behind a loading screen (`gui-compose/.../WarmupTiming.kt`, `BuildSearchModel`).
- **Build is heavy.** A cold `./gradlew build` resolves Compose Desktop + the native OR-Tools
  library. Prefer module-scoped tasks (`:autobuilder:test`, `:gui-compose:run`) while iterating.
- **GUI is Compose Desktop** (`gui-compose` module) — built programmatically in Kotlin, no FXML.
  i18n is the hand-written `Tr` enum in `gui-compose/.../i18n/I18n.kt` (EN/FR); there is **no**
  generated i18n code. `docs/design-reference/` is the visual source of truth.
- **Run `./gradlew ktlintFormat`** before finishing a change; CI style is strict.
- Don't commit/push unless asked; this repo's default branch is `main`.
