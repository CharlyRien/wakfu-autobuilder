# GUI audit — October 2026

A read-only audit of the Compose Desktop GUI, followed by fixes on `main` and the final follow-ups. It looked at `main` as it
stood just before the 1.12.0 release commit (`a7d6a4a5`, where the window still read "Version 1.11.0 · Game data 1.93.1.62").
All findings **F1–F6**, **B1–B9** and **N1–N10** are now fixed. The entries below retain the original findings alongside the
commit subjects and regression coverage.

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

## Fixed in 1.13.0

The four small GUI items of the backlog, **B1, B2, B4, B5**. Same method as above (the real screens rendered off-screen, keyboard
probes through `performKeyInput`, data read from the embedded JSON); the "before" captures are `main` at `6c6f4625`. The commits
are the branch's own: a rebase-merge rewrites them, the subjects stay.

| # | Finding | Commit |
|---|---|---|
| B1 | Save dialog: focus, Esc, Enter, and a suggested name that is already taken | `58356c01` fix: the Save dialog opens on the name field, closes with Esc and saves with Enter |
| B2 | Boss picker: French names in the English app, a garbled entry, "Cire Momore" three times | `08307847` fix: boss names follow the app language, and the boss list loses its garbled entry |
| B4 | "0" headlines before any search, an empty "Desired vs Achieved" card | `1c36f631` fix: no "0" headline before a search, and no empty "Desired vs Achieved" card |
| B5 | Nothing says a saved build was computed with other game data | `f1dd4f4b` fix: a build saved with other game data now says so on its card and when loaded |

### B1 — Save dialog keyboard — FIXED (`58356c01`)

- **Seen:** `SAVE-DIALOG textfield #10 focused=false text='Cra 110 · Distance'` / `#11 focused=true text=''`; Esc on the root left
  `modal=SaveBuild`; Enter in the name and Cmd+Enter in the note submitted nothing (`modal=SaveBuild saved=0`). After saving a build and
  starting another with the same class / level / focus, `suggested='Cra 110 · Distance' taken=[cra 110 · distance]`: the dialog
  opened with Save disabled and "Another build already uses this name" showing before a key was pressed. The Edit dialog had the same
  focus bug (`#12 focused=true`: its tag input, the last field).
- **Cause:** every `SearchField` asked for the focus when composed, so in a form the LAST field won; no key handler existed anywhere in
  the GUI; `suggestedSaveName()` returned the generated name, identical for every build of a class / level / focus.
- **Fix:** `SearchField` takes the focus only when asked (`autoFocus`: a picker's only field, a form's first one), and a field that
  takes it starts with its text selected, so typing replaces a pre-filled name (a plain `BasicTextField(String)` leaves the cursor at
  position 0, in front of it). The shared `Scrim` closes any modal on Esc and holds the focus from the start, so Esc also works in a
  confirm dialog. In the Save and Edit dialogs, Enter in the name field and Ctrl/Cmd+Enter anywhere do what the highlighted button
  does (Update for a loaded build, nothing while it is disabled); Enter also renames in the folder / tag dialogs.
  `suggestedSaveName()` is made unique against the library ("Cra 110 · Distance (2)").
- **Tests:** `ModalKeyboardUiTest` (17), `BuildSearchModelSuggestedNameTest` (5).

### B2 — Boss picker — FIXED (`08307847`)

- **Seen:** the English picker showed French names ("Abribus le Protecteur", "Agonie, Nécromancienne livide") sorted by their English
  names, opened on `` !@#dh`~ ``, and stopped at 120 of the 225 bosses.
- **Cause:** the picker, `RequestPanel.BossCard` (name and family) and the turns-to-kill line read `name.fr`, "because the English names
  are lowercased" (none is; the one all-lowercase name was the junk row); id 5295 was boss-tier through `monster-overlay.json`; the picker
  list was capped with `take(120)`.
- **"Cire Momore" ×3 are not duplicates:** ids 5416 / 5417 / 5418 are three monsters of one dungeon boss, at levels 58 / 73 / 233 with
  2 623 / 4 223 / 336 424 HP and 500 / 550 / 600 flat resistance (67 / 70 / 73 %). They are kept, listed together lowest level first, and told
  apart by the level on their picker row.
- **Fix:** names and families follow the app language (`BossRoster.kt`: boss-tier only, sorted by the displayed name, level as the
  tiebreak; search still matches both languages); no cap; a family that only repeats the boss's name (16 bosses) is not repeated under it.
  Data: `5295` is removed from `monster-overlay.json` (225 entries left) and `monsters.json` is regenerated with
  `./gradlew :bdata-extractor:run` against the local 1.93.1.62 client. The only difference is that row (no rank, so it moves to the regular
  monsters as the extractor sorts them); every other artifact the extractor rewrites came out byte-identical, and `WakfuData.VERSION` is
  unchanged.
- **Tests:** `BossRosterTest` (13, five of them data tests on the embedded bestiary: no unreadable boss name in any language, the only shared
  name is the explained "Cire Momore", every boss once, sorted in both languages), `BossNamesUiTest` (8).

### B4 — Headline noise — FIXED (`1c36f631`)

- **Seen:** `0` / "Requested mastery" (Most Masteries), `0` / "Expected damage" (Max Damage) and `0 %` over an empty meter (Precision) before any
  search; a "Desired vs Achieved" card holding only its title in a Max Damage result (that mode starts without target rows).
- **Cause:** `MatchHero` printed its number whatever the state; `DesiredVsAchieved` had no empty case.
- **Fix:** until a build exists the headline is a dash (`—`, regular weight, faint colour) under its usual label, with no `%` and no meter; it
  also covers the first seconds of a search. A build that really scores 0 still reads 0. The card is shown only when there are target rows.
- **Follow-up N7 fixed:** the Mastery Summary hides the requested metric and its header number when no mastery was requested.
- **Tests:** `HeadlineNoiseUiTest` (10).

### B5 — Stale-data cue — FIXED (`f1dd4f4b`)

- **Seen:** `HistoryEntry.dataVersion` is stored with every save and was never read.
- **Fix:** a My Builds card whose `dataVersion` differs from `WakfuData.VERSION` shows "Saved with game data X — re-run the search to update" /
  "Enregistré avec les données de jeu X — relance la recherche pour mettre à jour"; a loaded (or imported) build shows the same note under the
  headline of the stats column (`UiState.staleDataVersion`, part of the parked result so it follows the build across a mode switch and is cleared
  by the next search). Nothing is blocked and no stored build is rewritten. Saving or exporting a build that still holds the old result keeps its
  original stamp: stamping it with the current version would relabel old numbers as current, and its card would stop saying so.
- **Tests:** `BuildSearchModelStaleDataTest` (6), `StaleDataCueUiTest` (6).

## Backlog status (from the audit)

| # | Area | Finding | Evidence / lead |
|---|---|---|---|
| B1 (fixed) | Save dialog | **Fixed in 1.13.0** (`58356c01`). Was: the focus landed on the note, Esc did not close the dialog, Enter did not submit, and a suggested name could already be taken. | `SAVE-DIALOG textfield #10 focused=false text='Cra 110 · Distance'` / `#11 focused=true text=''`; `AFTER ESC on the root: modal=SaveBuild` (before). |
| B2 (fixed) | Boss picker | **Fixed in 1.13.0** (`08307847`). Was: French names in the EN UI, a junk entry `` !@#dh`~ `` (id 5295, level 238, rank 1), "Cire Momore" 3× (three distinct monsters, kept). | `monsters.json`: 226 boss-tier rows (225 now). |
| B3 (fixed) | i18n leftovers | **Fixed:** `fix(gui): the last English-only labels are translated`. History/library/filter/validation class names reuse `CharacterClass.label(lang)` from #250; spell costs and debuff lines use Tr, short levels read Niv. in FR; sublimations use localized rarity labels, passive stats use characteristic labels. `GuiLabelsTest` and `ResultLabelsUiTest` lock EN/FR. Was: Class names are the capitalised enum names in both languages (`TopBar.displayName()`, `HistoryEntry.classDisplayName()`); hard-coded English fragments "AP", "WP", "Lv", "res"; raw enum names `EPIC` / `NORMAL` (sublimation rarity) and `BLOCK_PERCENTAGE` (passive flat stats). | `StatsPanel` rotation card (`"… AP, −… res"`, `"→ …% res after debuffs"`, `"… AP)"`), `CompareScreen` / `ClassSpellsPanel` (`"$it AP"`, `"$it WP"`), `PaperdollPanel` / `Modals` / `StatsPanel` (`sub.rarity.name`), `StatsPanel.PassivesResult` (`it.key.name`), `PaperdollPanel` / `Modals` (`"Lv …"`). |
| B4 (fixed) | Headline noise | **Fixed in 1.13.0** (`1c36f631`). Was: "0 Requested mastery" / "0 Expected damage" / "0 %" before any search, and an empty "Desired vs Achieved" card in Max Damage. | `MatchHero` rendered its headline number even with no build; `DesiredVsAchieved` had no empty state. |
| B5 (fixed) | Stale data | **Fixed in 1.13.0** (`f1dd4f4b`). Was: nothing told the player that a saved build was computed with older game data. | `HistoryEntry.dataVersion` was stored but never compared with `WakfuData.VERSION`. |
| B6 (fixed) | Item picker | **Fixed:** `4eb493af` — `fix: the item picker lists every matching item, with a count and a slot filter`. Every matching item is reachable, the count is shown, and slots can be filtered. Test: `ItemPickerUiTest`. Was: it shows the first 60 of 2 783 equippable items (level 110, no minimum level; 1 257 with min 80) with no count and no slot filter. | `Modals.kt` item picker; 7 899 distinct items in `equipments.json`. |
| B7 (fixed) | Saved boss builds forget the boss | **Fixed as a side effect of F3** (the boss is now saved and restored). Builds saved before 1.12.1 carry no boss and still load as a manual scenario. | `BOSS after reload of a boss build: selectedBoss=null bossElement=null` (before). |
| B8 (fixed) | Persistence | **Fixed:** `c5bea4ac` — `feat: remember the request being edited between launches`. The active mode’s prepared request is restored from the workspace, including class, levels, targets, rarities, duration and scenario. Tests: `BuildSearchModelWorkspaceTest`, `WorkspacePersistenceTest`. Was: nothing is remembered between launches except the language, the library sort / grouping, the tag registry and the "check optimality" switch. | `LibraryPreferences`: class, level, targets, rarities, duration… are lost on restart. |
| B9 (fixed) | Issue #128 residue | **Fixed:** `fix(gui): widened stats reflow into two columns without truncating labels or values`. Before: no two-column layout existed; at 300 dp the HP and distance-mastery labels were ellipsized in both modes and languages. Mastery, target and other-stat lists now use two columns from 432 dp of panel width (360 dp inside cards); values have their own line in narrow cells, labels wrap. Checked at 300/360/460 dp plus both sides of the switch and repeated resizing, EN/FR, full result cards. `StatsPanelLayoutUiTest`. Was: The stats column can reflow into two columns when it is widened (it is resizable up to 460 dp). | `AppShell` `statsWidth` coerce range; not re-checked after the F6 changes (they touch the request column only). |

B8 also includes the persistence follow-ups `e4855ee3` — `fix: the remembered workspace survives a quit, a slow disk and a loaded build`
and `8cc21a51` — `fix: a quit while the remembered request is still being read keeps the edit made meanwhile`.
`BuildSearchModelWorkspaceTest` covers shutdown flushing, late reads, edits made during restore and loading a saved build.

## Found while fixing

| # | Finding | Lead |
|---|---|---|
| N1 (fixed) | The paperdoll's "No item here improves the requested stats" explanation never shows after a **finished** search: it is gated on `Phase.Idle`, and a finished search is `Phase.Done`. (It only showed after a Stop, which now ends in `Done`.) **Fixed:** `fix(gui): the empty-slot explanation shows after a finished search`. The generic hint appears only after a completed, non-stopped most-masteries search; precision and damage have different objectives. Sublimation conditions remain visible in every mode. Test: `DollSlotsTest`. | `PaperdollPanel`, `emptyHints` filter. |
| N2 (fixed) | "View this build as damage" keeps the previous mode's `match` (the mastery score or the % match) as the "Expected damage" headline; only the rotation and the per-position breakdown are recomputed. **Fixed:** `fix(gui): viewing a build as damage shows its expected damage in the headline`. The view reuses the search result scorer, including target penalties, and refreshes the achieved stats. Test: `BuildSearchModelDamageViewTest`. | `BuildSearchModel.viewCurrentBuildAsMaxDamage`. Probed: after the view `match` is still 1500 (the old score) while the rotation totals 113.4. |
| N3 (fixed) | A stopped Max Damage search has no per-position damage breakdown: it is only computed once the stream completes. **Fixed:** `fix(gui): a stopped Max Damage search keeps its per-position damage breakdown`. Stop computes the same detail off the UI thread from the original search request and kept build; a newer search cancels it. Test: `BuildSearchModelStoppedDamageTest` (kept-build detail, a blocked late-result race, and returning to a parked stopped result). | `search()` completion path vs `cancel()`. |
| N4 (fixed) | "Could not save / import / duplicate build" banners still print the raw exception message (file paths). | **Fixed:** `3c730ec3` — `fix(gui): save, import and duplicate errors are explained in the player's language`. Localized retry banners; invalid imports explain how to check the clipboard. Technical details go to the log. Test: `BuildSearchModelLibraryErrorsTest`. |
| N5 (fixed) | The CLI still prints the raw precision score (`248.5% match found so far`); the 100 % cap of F4 is GUI-only. **Fixed:** `fix(cli): the precision match never reads above 100%`. The existing display helper now lives in `common-lib`; the GUI delegates to it and the CLI formats precision progress through it. Raw scores and other modes keep their meaning. Tests: `CliMatchDisplayTest`, `MatchDisplayTest`. | `autobuilder/Main.kt`. |
| N6 (fixed) | The boss picker listed only the first 120 of the 225 bosses (nothing said so): every boss after "M" needed a typed search. **Fixed with B2.** | `take(120)` in `BossPickerModal`. |
| N7 (fixed) | A Max Damage result's Mastery Summary opens on "Requested mastery 0" (and a trailing `0` in its header) when no mastery was requested. **Fixed:** `fix(gui): a max-damage result without a mastery request no longer shows "Requested mastery 0"`. Metric, hint and header number hidden without a mastery row; requested rows (including 0) unchanged. Test: `MasterySummaryUiTest` (EN/FR). | `StatsPanel.MasterySummary` always shows the metric. |
| N8 (fixed) | "Save as new" on a loaded build leaves the build's own name in the box, and only the OTHER builds' names are refused, so two builds can end up with the same name (the library and Compare then show two identical titles). | **Fixed:** `fix(gui): "Save as new" suggests a free name and never duplicates an existing build's name`. The copy action opens name entry with the first free suffix; all names are refused for a copy, including the active one, in the dialog and model. Update still accepts its own name. Tests: `ModalKeyboardUiTest`, `BuildSearchModelSuggestedNameTest`. |
| N9 (fixed) | 17 of the 18 tests of `BuildSearchModelLibraryTest` never run: they are written `= runBlocking { … }`, so they return their last assertion, and JUnit ignores a `@Test` method that returns a value. With `: Unit` all 18 run and pass. | **Fixed:** `f13d43ba` — `test: restore library coverage and make fake searches deterministic`. Explicit `Unit` returns and a controlled coroutine scheduler restore all 18 original tests; an added scheduler/startup test brings `BuildSearchModelLibraryTest` to 19 passing tests. No further code change was needed in this follow-up. |
| N10 (fixed) | The "Stop at 100% match" / "Arrêter à 100%" switch is ignored: the GUI passes `stopWhenBuildMatch`, but no engine code reads it, so a precision search never stops at its first 100 % build. | **Fixed:** `f55bc987` — `fix: "Stop at 100% match" stops a precision search at its first 100 % build`. Precision checks every solution before throttling and stops CP-SAT at the first 100 % capped match. That build is returned without an optimality flag. Default/off and other modes are unchanged. Test: `StopAtMatchTest`. |

## B3 follow-up scan

The save/import/duplicate error handlers are now localized (N4, `3c730ec3` —
`fix(gui): save, import and duplicate errors are explained in the player's language`; `BuildSearchModelLibraryErrorsTest`).
The stat-icon fallback glyphs are localized too (`f5057562` — `fix(gui): stat icon fallbacks follow the player's language`;
`StatGlyphLanguageTest` covers the asset inventory and changing the displayed fallback with the language).

The remaining intentional English fallbacks are historical release-note text when no FR translation exists and unknown skill
names in `skillLabel`. Game-data names/descriptions also fall back to the other language when one translation is missing.
App branding and unit symbols (`s`, `%`, `×`) are language-independent. Raw class/rarity/monster accessibility names and the
hard-coded "Sublimations" tooltip heading were fixed in B3.
