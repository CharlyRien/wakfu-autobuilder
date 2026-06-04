# CLAUDE.md

This file gives Claude Code its project context. The full agent guide lives in `AGENTS.md` —
it is imported below so both files stay in sync.

@AGENTS.md

## Claude Code working notes (project-specific)

- **Verify the branch before engine work.** `main` uses a genetic algorithm; `linear-programming`
  replaces it with an OR-Tools CP-SAT solver and bumps the JVM toolchain 21 → 25. The two share
  types and the output `Flow`, but almost nothing else. See `AGENTS.md` §4.
- **Build is heavy.** A cold `./gradlew build` resolves JavaFX + (on the LP branch) the native
  OR-Tools library. Prefer module-scoped tasks (`:autobuilder:test`, `:gui:run`) while iterating.
- **Don't hand-edit generated code.** `gui/src/main/kotlin/generated/I18nKey.kt` is produced from
  `i18n_*.properties` by the `generateKotlinI18nKeys` Gradle task. Edit the `.properties` files.
- **Run `./gradlew ktlintFormat`** before finishing a change; CI style is strict.
- **UI redesign in progress (JavaFX → Compose Desktop)** — for any GUI task, start from
  `docs/COMPOSE_MIGRATION_PLAN.md`, with `docs/UI_REDESIGN_HANDOFF.md` for context and
  `docs/design-reference/` as the visual source of truth.
- Don't commit/push unless asked; this repo's default branch is `main`.
