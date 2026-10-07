# GUI translations

`Tr` is the typed key list. Add every new key to all UTF-8 bundles in
`gui-compose/src/main/resources/i18n/`. `TranslationBundlesTest` iterates
`Lang.entries` and rejects missing/orphan keys, blank values, and mismatched
formatter tokens. EN and FR were ported from `main` without changing their text.

Game names are independent of the bundles: `I18nText.localized(lang)` resolves
items, runes, sublimations, spells, passives, bosses and achievement names.
Skill-tree labels come from `skillLabel`, keyed by the English domain names
(including the strength branch's `HP`), rather than from `Tr`.

## Spanish contribution

The completed bundle contains 441 keys: 439 current `main` keys and two bulk
sublimation exclusion labels. Compared with the contributor's 337-key bundle,
105 keys were added and the retired `PROVEN_WITHIN` key was removed.
Existing translations were corrected to use the client's terminology.

Terminology sources are the committed first-party game data:

- `autobuilder/src/main/resources/runes.json`: Dominio elemental, Dominio de
  melé, Dominio distancia, Dominio crítico, Dominio espalda, Dominio berserker,
  Dominio cura, Placaje, Esquiva and the four Resistencia al/a la … labels.
- `autobuilder/src/main/resources/spell-passives.json` and `sublimations.json`:
  Anticipación, PdV, curas and daños infligidos.

The bulk exclusion buttons are retained. `SublimationBulkExclusionTest` checks
partial exclusion, all-name deduplication, restoring a rarity, unrelated
exclusions, and preserving imposed sublimations. Names remain keyed in French
as required by the engine.

Validation: `ktlintFormat` and the full `:gui-compose:test` suite pass. Request
and stats layout tests iterate all languages and check actual text line widths,
wrapping and ellipses. `LanguageTopBarUiTest` covers the wrapped and single-row
bars. Spanish needs no further layout change. Existing UI tests now use the
active language instead of assuming that every language other than EN is FR.
