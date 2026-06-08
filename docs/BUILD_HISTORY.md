# Build history & comparison (gui-compose)

Local, file-based history of saved builds plus a side-by-side comparison view. Lets you keep the
builds you've found, reload one to tweak & re-run it, and compare two of them stat-by-stat — so you
never redo the same search twice.

## User-facing behavior

- **Save** (Stats panel, enabled once a search is done): names the build (pre-filled, e.g.
  `Cra 110 · Distance`) + optional note, writes it to the local library. When a build is already
  loaded the dialog offers **Update** (overwrite) or **Save as new**.
- **My Builds** (TopBar tab): searchable grid of saved-build cards — name, class·level, date,
  match%/mastery headline, a 14-icon strip, note. Per-card actions: **Load**, **Compare**,
  **Rename**, **Delete** (confirmed).
- **Load**: restores the saved build's *request* (targets, class, rarity, forced/excluded… — so it
  can be tweaked) and its *result* (paperdoll + stats, shown without re-running), and marks it as the
  **active build** (name shown in the TopBar).
- **Search lock**: while a saved build is loaded the Search button is locked (🔒). Clicking it asks
  to confirm before re-optimizing — so editing a saved build is deliberate. Detach (✕ on the active
  chip) returns to an "unsaved build".
- **Compare**: side-by-side A | B (picked from the library), with a per-row "best" marker over every
  stat where they differ. Reads each build's stored `achieved` map — no engine recomputation.

## Where things live

| Concern | Location |
|---|---|
| DTO (`HistoryEntry`, request/result snapshots) | `common-lib` → `me.chosante.common.history` |
| Persistence (`HistoryRepository`, OS data dir) | `gui-compose` → `me.chosante.ui.history` |
| Mappers (`UiState`/`BuildCombination` ↔ DTO) | `gui-compose` → `HistoryMapping.kt` |
| Library / Compare screens | `gui-compose` → `LibraryScreen.kt`, `CompareScreen.kt` |
| State (`Screen`, `CompareSlot`, active build…) | `UiState.kt` / `BuildSearchModel.kt` |
| Dialogs (save/rename/delete/re-search) | `components/Modals.kt` (`ModalHost`) |
| i18n | `i18n/I18n.kt` (`Tr` enum, EN/FR) |

### Why the DTO is in common-lib
The `@Serializable` DTO must be compiled with the kotlinx-serialization plugin, which **clashes with
the Compose Kotlin plugin in gui-compose** ("serialization core version is unknown"). common-lib
already has the working serialization setup and owns the referenced `Equipment`/`Characteristic`/
`Rarity` serializers. gui-compose keeps only the JSON *runtime* and calls with an explicit serializer
(`Json.encodeToString(HistoryEntry.serializer(), …)`).

## Storage

- One JSON file per build under the per-user OS data dir (atomic write: temp + `Files.move`):
  - macOS `~/Library/Application Support/WakfuAutobuilder/history/`
  - Windows `%APPDATA%\WakfuAutobuilder\history\`
  - Linux `${XDG_DATA_HOME:-~/.local/share}/wakfu-autobuilder/history/`
- **Local is the source of truth.** No auto-save, no auto-purge: explicit named save, explicit
  delete. A corrupt file is skipped on load, never fatal.
- Never serializes the live engine graph (`BuildCombination`/`Character`/`CharacterSkills`). Stores
  the full `List<Equipment>` (serializable; drives paperdoll/tooltips/compare offline), a flat
  `skill name → points` map (rebuilt via `reconstructSkills`), the `achieved` map, match, optimal,
  and a cached Zenith URL. Carries `schemaVersion` + `dataVersion` for reproducibility/migration.

## Zenith

Builds created via the Zenith API are anonymous/unowned, and a security change closed editing of
them — so "update an existing Zenith build" is **not possible today**. Zenith stays export/share
only; the search-lock is a *local* overwrite guard, fully decoupled from Zenith. (A cached Zenith URL
is stored per entry for instant re-share.) Reconnecting builds to a Zenith account is a separate,
later workstream.

## Deferred / follow-ups

- Compare a saved build against the *current workspace* build (today both sides come from the
  library).
- Mini-paperdoll grids in the compare view (currently a 14-icon strip).
- Pinning / tagging builds; export/import a single build file.
