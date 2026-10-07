# Picker unification

The baseline and original migration below are historical. The UX revision at the end supersedes
automatic hiding of additive choices and the reverted boss-hiding change.

## Step 0: baseline

Inspected `377cf5fe` (the ready `codex/picker-unification` worktree, 1.16.0), before edits.
The June REF-1 section is stale; this table records the actual code.

| Picker | (a) Already chosen hidden, immediate removal | (b) Search focused on open | (c) Several picks, explicit completion |
| --- | --- | --- | --- |
| Stats | Yes, by characteristic | Yes | Yes, Done |
| Forced/excluded items | Yes, union of forced/excluded French names; every rarity variant | Yes, once catalog loaded | Yes, Done |
| Sublimations | Yes, union of forced/excluded French names | Yes | Yes, Done |
| Passives | Yes, forced French names | Yes | Yes, Done; model rejects picks above slot cap |
| Boss | No, current boss remains listed | Yes | Single choice; model closes on pick |
| Item runes | Intentionally visible with counts and +/− | Yes | Existing multi-socket transaction, Save/Cancel |

The item and boss lists already have no `take(n)` cap. All four additive callbacks already keep the modal open.
The rune picker edits the whole item's socket allocation, not one socket: repeated copies of a rune are legal,
and selected rows must remain reachable to remove them. Applying additive-picker hiding would break this feature.

## Shared contract

`PickerScaffold` owns the card/title, focused search field, canonical-key selection exclusion, lazy list,
empty state, optional match count and Done footer. Its optional header, footer and list-content slots preserve
catalog-specific layouts without duplicating the shell. Selection comes from caller state, so rejected picks
never disappear speculatively. Lazy row identity is separate from selection identity: item variants have
unique equipment IDs but all variants of an already selected French name disappear together.

Per-picker code retains query matching, localized sorting, rows and domain callbacks:

- Stats retain sections and two-column tiles.
- Items retain loading, count, equippable-only and slot filters, requirements and stat gates.
- Sublimations retain rarity chips, rarity ordering and row labels. Epic/relic bulk exclusion buttons
  remain in the request panel where they already lived.
- Passives retain class catalog and model slot cap.
- Bosses retain boss roster, four-language matching and immediate single-choice confirmation.
- Runes retain colours, double badge, socket counts, repeated picks, removal and Save/Cancel transaction.

## Commits and skipped steps

1. `refactor(gui): the six pickers share one picker scaffold` — migration with existing behavior preserved.
2. `fix(gui): every picker hides what you already chose` — hide the currently selected boss by ID;
   additive pickers already met the criterion, rune rows retain the editing exception described above.

Steps 3 and 4 require no commits: focus and additive multi-selection already work everywhere applicable.
Boss selection stays single-choice because one search targets one boss. No new picked-this-opening counter:
existing item/sub/passive match counts and rune socket count are retained in this behavior-preserving refactor.

## Tests and strings

No existing UI test needs adaptation. `PickerScaffoldUiTest` adds coverage for all six search fields,
empty results after immediate typing, both item flows' forced/excluded union and rarity variants,
two consecutive picks with immediate disappearance and Done for all four additive catalogs,
and repeated rune selection/removal and the double badge.

No new translation keys or bundle text: all EN/FR/ES/PT strings are reused, including Done and empty states.
GUI only; no engine changes. Commits remain local; no push or PR.

Commit 1 validation: `ktlintFormat` and full `:gui-compose:test` passed through the shared Gradle lock
(474 tests, 2 skipped, no failures), including `TranslationBundlesTest`. Existing UI tests are unchanged.

## UX revision — maintainer decision, 2026-10-07

Continued from `02feed40` (maintainer revert of boss hiding) on the existing published branch.
No rebase, push, or new PR. The reverted changeset had already been removed.

### Additive pickers

`0549b2a1` — `feat(gui): pickers show what you already chose, and a click removes it`

`PickerScaffold` now owns checked/highlighted selection rows, toggle routing, optional hiding,
the wrapped Chosen strip (maximum 96 dp, scrollable), removable chips, and the shared hide checkbox.
The selection strip is independent of search/slot/rarity filters, and remains reachable with hidden choices.
Items keep distinct row IDs and one French-name selection across rarity variants. Both states are badged;
a pick in the opposite mode delegates to the existing mutually exclusive pin actions and shows a toast.
Sublimation choices reflect the current forced/excluded mode; each uses the corresponding existing removal action.
Passives show used/available slots; unchosen rows disable at capacity while selected rows stay removable.
The existing request-panel removal actions also handle chip removal and stat removal, with no new domain removal methods.

`LibraryPreferences` persists the shared Boolean at `pickerHideChosen` under the existing
`me/chosante/wakfu-autobuilder` Java Preferences node. `UiState.pickerHideChosen` is loaded on model creation;
`setPickerHideChosen` updates both state and persistence. Default and read-failure fallback: false.

UI tests cover all additive modes' row and chip removal, item variants and forced/excluded moves,
hidden choices across catalogs and fresh preference instances, and disabled full-loadout passive rows.
The existing hide-only tests now explicitly enable the option, since it is no longer the default.
`BuildSearchModelE2ETest` also checks both move notifications through the real model;
`LibraryPreferencesTest` checks defaults, round trips and the no-store fallback.

### New strings

| Key | EN | FR | ES | PT |
| --- | --- | --- | --- | --- |
| `PICKER_CHOSEN_COUNT` | Chosen (%d) | Choisis (%d) | Elegidos (%d) | Escolhidos (%d) |
| `PICKER_HIDE_CHOSEN` | Hide chosen | Masquer les choix | Ocultar elegidos | Ocultar escolhidos |
| `PICKER_REMOVE_CHOSEN` | Remove %s | Retirer %s | Quitar %s | Remover %s |
| `PICKER_FORCED` | Forced | Imposé | Impuesto | Imposto |
| `PICKER_EXCLUDED` | Excluded | Exclu | Excluido | Excluído |
| `PICKER_MOVED_TO_FORCED` | %s moved to forced items | %s fait maintenant partie de tes objets imposés | %s pasa a tus objetos impuestos | %s agora faz parte dos seus itens impostos |
| `PICKER_MOVED_TO_EXCLUDED` | %s moved to excluded items | %s fait maintenant partie de tes objets exclus | %s pasa a tus objetos excluidos | %s agora faz parte dos seus itens excluídos |
| `PICKER_CURRENT_BOSS` | Current boss: %s | Boss actuel : %s | Jefe actual: %s | Chefe atual: %s |

Additive UX validation: `ktlintFormat` and full `:gui-compose:test` passed through the shared lock
(479 tests, 2 skipped, no failures), including translation and changeset guards.

### Boss picker

`feat(gui): the boss picker highlights and scrolls to the current boss`

The boss stays selectable and visible. `PickerScaffold` owns its shared checked/highlighted row,
radio-button semantics and initial scrolling by canonical key via `rememberLazyListState`.
The initial index is consumed once per opening, only when search is empty; later query edits do not
jump back. The domain header supplies Current boss with the localized name; it stays above the list.
Namesakes retain individual IDs, and the existing pick callback replaces the boss and closes the modal.
The rune editor remains unchanged: it passes no additive selection policy and retains +/− and Save/Cancel.

`BossPickerSelectionUiTest` covers all four languages, visibility of a current boss at the roster's end,
the persistent current-boss label, selection semantics, searching from a late scroll position,
no jump on clearing a search, three namesake bosses and replacement/reopening by ID.
Optional local test captures (`WAKFU_PICKER_CAPTURE_DIR`) allow visual review of items, full passives and bosses.

Both new commits include GUI changesets in EN/FR. The replaced boss-hiding changeset was removed by
the maintainer before this task. No engine changes, branch rebase, push, or new PR. No requested work omitted.

Final validation: `ktlintFormat` and the complete `:gui-compose:test` suite passed through the shared lock
(481 tests, 2 skipped, 0 failures/errors), including `TranslationBundlesTest` and `ChangeFragmentsTest`.
Reviewed captures of selected item variants, a full passive loadout and the current boss in Portuguese:
wrapped chips, bounded lists and completion controls fit the existing modal styling.
`git fetch origin` confirmed no new `origin/main` commits to merge; the published branch was never rebased.
