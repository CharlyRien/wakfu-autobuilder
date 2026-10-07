# CLAUDE.md

This file gives Claude Code its project context. The full agent guide lives in `AGENTS.md` —
it is imported below so both files stay in sync.

@AGENTS.md

## Claude Code working notes (project-specific)

- **Engine.** The solver is the Google OR-Tools CP-SAT solver
  (`autobuilder/.../WakfuBuildSolver.kt`, deterministic & optimal). It streams its result as a
  `Flow<SolverResult<BuildCombination>>` (`SolverResult` was formerly `GeneticAlgorithmResult`; the
  enclosing package is still named `genetic`). The original genetic-algorithm engine has been
  **removed** — OR-Tools is the only solver (no `WakfuSolver` toggle). See `AGENTS.md` §4.
- **OR-Tools is native.** It loads a native library at runtime, so running/testing the engine needs
  extra JVM args (`--enable-native-access=ALL-UNNAMED`, `--add-opens …`) — already wired in the
  `autobuilder` and `gui-compose` build scripts. The first search pays a one-time cold start; the
  GUI hides it behind a loading screen (`gui-compose/.../WarmupTiming.kt`, `BuildSearchModel`).
- **Build is heavy.** A cold `./gradlew build` resolves Compose Desktop + the native OR-Tools
  library. Prefer module-scoped tasks (`:autobuilder:test`, `:gui-compose:run`) while iterating.
- **GUI is Compose Desktop** (`gui-compose` module) — built programmatically in Kotlin, no FXML.
  i18n: `Tr` (`gui-compose/.../i18n/I18n.kt`) is a bare enum of keys; the EN/FR/ES/PT text lives in
  `gui-compose/src/main/resources/i18n/strings_{en,fr,es,pt}.properties`. `TranslationBundlesTest`
  guards completeness + placeholder parity in CI — run it after touching any `Tr` key or bundle.
  There is **no** generated i18n code. `docs/design-reference/` is the visual source of truth.
- **Run `./gradlew ktlintFormat`** before finishing a change; CI style is strict.
- **Release notes.** Every feat/fix/perf change adds a `changes/unreleased/` note in EN + FR
  (`<slug>.properties`: `type`, `en`, `fr`; see CONTRIBUTING.md › Release notes). CI fails a PR
  without one; the `no-changeset` label (maintainer) waives internal-only changes.
- Don't commit/push unless asked; this repo's default branch is `main`.

The GUI bundle guard iterates `Lang.entries`; new `Tr` keys must be added to every UTF-8 bundle with matching formatter tokens. Game names use `I18nText.localized`, and skill names use `skillLabel` (including HP).
