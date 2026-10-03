# GUI audit — October 2026

A read-only audit of the Compose Desktop GUI, followed by the quick wins for **1.12.1**. It looked at `main` as it stood just
before the 1.12.0 release commit (`a7d6a4a5`, where the window still read "Version 1.11.0 · Game data 1.93.1.62"). This file keeps
the findings so the rest stay a backlog: items **F1–F6** are fixed (with their commit), **B1–B9** are the audit's backlog (B7
was fixed on the way, the others are open), **N1–N5** were found while fixing.

## Method

- Every screen was rendered **off-screen** with Compose's `runSkikoComposeUiTest` (the `WAKFU_COMPOSE_SCREENSHOT` smoke run
  renders black in a headless session): builder idle / searching / done, EN and FR, at 1440×900, 1180×720 and tall
  captures, plus the library, compare, every modal and the request panel at 2×.
- The builds came from **real engine searches** (CRA 110, min level 80, 8–15 s budgets): most masteries, precision (seeded
  "Distance mastery 1" row and a realistic 500), max damage against a dungeon boss.
- State facts were recorded from the model (`BuildSearchModel`) with probes; contrast ratios were computed with the WCAG
  formula; data facts were read from the embedded JSON (`monsters.json`, `equipments.json`).
- The screenshots are not committed. The PR that fixed F1–F6 lists the before/after image names; the numbers that matter
  are quoted below.

## Fixed in 1.12.1

| # | Finding | Commit |
|---|---|---|
| F1 | Stop strands the best-so-far build | `dda71775` fix: stopping a search keeps the build usable |
| F2 | Switching the search mode destroys the rows and the result | `59a617ab` fix: switching the search mode keeps each mode's targets and results |
| F3 | My Builds / Compare mislabel Max Damage builds | `feb2286b` fix: Max Damage builds show their damage and boss in My Builds and Compare |
| F4 | The Precision headline exceeds 100 % | `4594d9e1` fix: the Precision match never reads above 100% |
| F5 | Zenith: two builds per export, raw error text | `afaaa867` fix: Zenith links are made once per build, and Zenith errors are readable |
| F6 | Clipped request-panel copy, low contrast, contradictory proof badge | `a715dc93` fix: request panel text no longer cut off, easier-to-read small text, clearer optimum badge |

### F1 — Stop strands the build — FIXED (`dda71775`)

- **Seen:** after Stop, `phase=Idle progress=0` with the build kept (`STOP: phase=Idle progress=0 buildKept=true items=12
  match=1744`; with a finder that never completes: `phase=Idle progress=0 build=true match=55`). The paperdoll cards dim to
  48 % (`PaperdollPanel`, `idle = phase == Idle`), the header reads "Awaiting search", and Save / Zenith / Export are
  disabled (`StatsPanel`, `enabled = phase == Done`).
- **Cause:** `BuildSearchModel.cancel()` reset the phase and progress but kept the build.
- **Fix:** a stop that already has a build ends in `Phase.Done` with `searchStopped = true`: the build is fully usable,
  reads "Best found · optimum not proven" with a "Search stopped early" hint, and makes **no proof claim** (`optimal` is
  dropped, the proof state cleared). A stop before any build stays idle. The background proof's Stop link (`stopProof`) is
  untouched. A search that *fails* after streaming a build ends the same way (see F5).
- **Tests:** `BuildSearchModelStopTest`, `StoppedSearchUiTest`.

### F2 — Mode switch destroys work — FIXED (`59a617ab`)

- **Seen:** `TARGETS before=[ACTION_POINT, MOVEMENT_POINT, RANGE, CRITICAL_HIT, MASTERY_DISTANCE, HP,
  RESISTANCE_ELEMENTARY_WIND, DODGE] | in max-damage=[] | back in most-masteries=[]`, and the shown build vanished with the click.
- **Cause:** `setMode` rewrote the rows in place (Max Damage → empty) and cleared the result.
- **Fix:** every mode keeps its own **rows and result** (`UiState.modeWorkspaces`): leaving a mode parks both, coming back
  restores them. A mode visited for the first time starts as before (rows carried over; Max Damage constraint-free).
  Re-selecting the active mode is a no-op; a running search is stopped and parked as a stopped result; picking a boss
  while already in Max Damage keeps its rows; "View this build as damage" is now undoable.
- **Why the build is parked, not left on screen:** its headline (mastery score / % match / expected damage), its
  achieved-stat grid (resolved with the mode's random-element assignment) and its rotation are read by the mode that found
  it, and Save / Export snapshot the *live* mode and rows together with the result — keeping it under another mode would
  pair it with the wrong request.
- **Tests:** `BuildSearchModelModeSwitchTest` (13).

### F3 — My Builds / Compare mislabel Max Damage builds — FIXED (`feb2286b`)

- **Seen:** a saved Max Damage boss build read "10528% Match" under a "Precision" pill in My Builds; Compare repeated it and
  added "Mastery score 0". A max-damage build stores its **expected damage per turn** as its `match`.
- **Cause:** `LibraryScreen.HeadlineBadge` / `PillsRow` and `CompareScreen` only had a mastery branch; everything else was
  treated as precision.
- **Fix:** a damage branch (value + "Expected damage" label, "Max Damage" pill, a mode label on every compare column, an
  "Expected damage (engine)" row next to the mastery row with a dash under the builds a row does not apply to). The boss
  line needs the boss, which saves did not record, so a saved Max Damage build now stores it (`RequestSnapshot.boss`,
  optional → older saves load unchanged), and **loading** such a build brings the boss, its element and difficulty back and
  scores the rotation against it. This also closes the audit item "saved boss builds forget the boss" (B7, below).
- **Tests:** `LibraryScreenUiTest`, `CompareScreenUiTest`, `HistoryMappingTest`, `BuildSearchModelSavedBossTest`.

### F4 — Precision headline above 100 % — FIXED (`4594d9e1`)

- **Seen:** `PRECISION with Distance mastery target 500: match=248.5`; with the seeded "Distance mastery 1" row the library
  card read "20330%".
- **Cause:** once every target is met, `FindClosestBuildFromInputScoring` keeps scoring the overshoot (so the search prefers
  the better of two builds that both meet the targets). That is an ordering score, not a percentage.
- **Fix:** the *displayed* match is a whole percent capped at 100 with "Targets met" / "Cibles atteintes" (stats hero, top
  bar meter, library card, compare column). The raw value stays in the state and in saved builds, where it orders builds.
  A library card that is both targets-met and proven optimal shows both lines.
- **Tests:** `MatchDisplayTest`, `PrecisionMatchUiTest`, `LibraryScreenUiTest`, `CompareScreenUiTest`.

### F5 — Zenith — FIXED (`afaaa867`)

- **Seen:** `ZENITH builds created after Open + Copy on the same build: 2`; the banner printed `api.zenithwakfu.com` and
  `Timed out waiting for 30 ms` (10 000 ms in production).
- **Cause:** `createZenithLink` ignored `ui.zenithUrl`; the error banner printed `exception.message`.
- **Fix:** a build is exported once — the link made for the build on screen (or saved with it) is reused by both actions,
  and a request made while the link is being created is ignored. A failure reads "Zenith did not answer — check your
  connection and retry." / "Zenith n'a pas répondu — vérifie ta connexion et réessaie." with a **Retry** link that repeats
  the action that failed; a browser that cannot open points at "Copy build link" instead. A crashing search gets the same
  plain sentence + Retry. The raw text goes to the log (`printStackTrace`).
- **Tests:** `BuildSearchModelZenithTest` (12), `ErrorBannerUiTest`.

### F6 — Readability — FIXED (`a715dc93`)

- **(a) Clipped copy.** The mode segments carried a second line that was cut at every width (`minimum constraints,`;
  `Précisi/on` in French at 260 dp), and the 77 dp priority bar left stat names ~30 dp (`Cri…`, `He…`, `minim/um`).
  Now: the selector shows three titles with the selected mode's sentence under them (the others in a hover tooltip) and stacks
  the options below 292 dp of panel width; target rows wrap the name onto two lines, the priority bar is 53 dp, and below 312 dp
  of panel width the controls move to a second line. Checked at 260–440 dp (including the two switch points), EN and FR,
  with the real text layout (no ellipsis, no break inside a word).
- **(b) Contrast.** `faint` (#5F656F) measured 2.45–3.0:1 on the cards; it is now #8F939A.

  | text colour | on `bg` | on `surface` | on `raised` |
  |---|---|---|---|
  | `faint` before (#5F656F) | 3.00 | 2.74 | 2.45 |
  | `faint` after (#8F939A) | 5.70 | 5.22 | 4.66 |

  (`docs/design-reference/styles-clean.css` still carries the mock-up's #5F656F; the app now deviates on purpose.)
- **(c) Badge wording.** "Optimal prouvé à 2.2 % près" read as a success under "Meilleur trouvé · optimum non prouvé". A build
  the certificate bounds now reads as one line, "Best found · at most 2.2% below the optimum" / "Meilleur trouvé · au plus
  2,2 % sous l'optimum", with the number formatted in the app's language.
- **Tests:** `RequestPanelLayoutUiTest`, `ThemeContrastTest`, `BoundBadgeUiTest`.

## Open backlog (from the audit)

| # | Area | Finding | Evidence / lead |
|---|---|---|---|
| B1 | Save dialog | Focus, Esc and Enter: opening "Save build" puts the focus on the **note** field, not the name; Esc does not close the dialog; Enter does not submit. | Probe: `SAVE-DIALOG textfield #10 focused=false text='Cra 110 · Distance'` / `#11 focused=true text=''`; `AFTER ESC on the root: modal=SaveBuild`. `SaveBuildModal` (`Modals.kt`) has no `FocusRequester`, no key handling and no `keyboardActions`, so Enter is unhandled too (read from the code, not probed). |
| B2 | Boss picker | The names are French in the EN UI; there is a junk entry `` !@#dh`~ `` (id 5295, level 238, rank 1); "Cire Momore" appears 3×. | `monsters.json`: 226 boss-tier rows; `RequestPanel.BossCard` and the picker (`Modals.kt`) show `name.fr` "regardless of UI language" because "English names are lowercased" — none of the 226 is any more (the boss line F3 added to My Builds / Compare already uses the UI language). The junk row and the triplicate come from the bdata extraction / `monster-overlay.json` curation. |
| B3 | i18n leftovers | Class names are the capitalised enum names in both languages (`TopBar.displayName()`, `HistoryEntry.classDisplayName()`); hard-coded English fragments "AP", "WP", "Lv", "res"; raw enum names `EPIC` / `NORMAL` (sublimation rarity) and `BLOCK_PERCENTAGE` (passive flat stats). | `StatsPanel` rotation card (`"… AP, −… res"`, `"→ …% res after debuffs"`, `"… AP)"`), `CompareScreen` / `ClassSpellsPanel` (`"$it AP"`, `"$it WP"`), `PaperdollPanel` / `Modals` / `StatsPanel` (`sub.rarity.name`), `StatsPanel.PassivesResult` (`it.key.name`), `PaperdollPanel` / `Modals` (`"Lv …"`). |
| B4 | Headline noise | "0 Requested mastery" is shown before any search (and "0 Expected damage" in Max Damage); Max Damage shows an empty "Desired vs Achieved" card. | `MatchHero` renders its headline number even with no build; `DesiredVsAchieved` has no empty state. |
| B5 | Stale data | Nothing tells the player that a saved build was computed with older game data. | `HistoryEntry.dataVersion` is stored but never compared with `WakfuData.VERSION`. |
| B6 | Item picker | It shows the first 60 of 2 783 equippable items (level 110, no minimum level; 1 257 with min 80) with no count and no slot filter. | `Modals.kt` item picker; 7 899 distinct items in `equipments.json`. |
| B7 (fixed) | Saved boss builds forget the boss | **Fixed as a side effect of F3** (the boss is now saved and restored). Builds saved before 1.12.1 carry no boss and still load as a manual scenario. | `BOSS after reload of a boss build: selectedBoss=null bossElement=null` (before). |
| B8 | Persistence | Nothing is remembered between launches except the language, the library sort / grouping, the tag registry and the "check optimality" switch. | `LibraryPreferences`: class, level, targets, rarities, duration… are lost on restart. |
| B9 | Issue #128 residue | The stats column can reflow into two columns when it is widened (it is resizable up to 460 dp). | `AppShell` `statsWidth` coerce range; not re-checked after the F6 changes (they touch the request column only). |

## Found while fixing

| # | Finding | Lead |
|---|---|---|
| N1 | The paperdoll's "No item here improves the requested stats" explanation never shows after a **finished** search: it is gated on `Phase.Idle`, and a finished search is `Phase.Done`. (It only showed after a Stop, which now ends in `Done`.) Probably meant `phase == Done && !searchStopped`. | `PaperdollPanel`, `emptyHints` filter. |
| N2 | "View this build as damage" keeps the previous mode's `match` (the mastery score or the % match) as the "Expected damage" headline; only the rotation and the per-position breakdown are recomputed. | `BuildSearchModel.viewCurrentBuildAsMaxDamage`. Probed: after the view `match` is still 1500 (the old score) while the rotation totals 113.4. |
| N3 | A stopped Max Damage search has no per-position damage breakdown: it is only computed once the stream completes. | `search()` completion path vs `cancel()`. |
| N4 | "Could not save / import / duplicate build" banners still print the raw exception message (file paths). | `BuildSearchModel` `saveBuild` / `importBuild` / `duplicateBuild` failure handlers. |
| N5 | The CLI still prints the raw precision score (`248.5% match found so far`); the 100 % cap of F4 is GUI-only. | `autobuilder/Main.kt`. |
