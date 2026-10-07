# Picker unification

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
