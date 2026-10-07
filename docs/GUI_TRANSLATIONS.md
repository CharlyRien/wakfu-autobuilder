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

## Portuguese

`Lang.PT` adds a fourth option to the existing language toggle. Its bundle has
441 keys; all 121 inline label/description call sites and the 30 skill-name
entries also carry Portuguese. Adding a language requires a `Lang` entry,
a bundle, and a required argument in the inline helper.

Official terminology sources:

- `runes.json`: Domínio elementar, Domínio de Curta Distância, Domínio de
  distância, Domínio de crítico, Domínio de costas, Domínio de Berserk,
  Domínio de cura, Bloqueio, Esquiva, and Resistência a Fogo/Água/Terra/Ar.
- `spell-passives.json`: Mercado Pacifista uses Paradas (block), Protetor do
  Rebanho uses PV, and Tique, Taque uses vontade. The class descriptions also
  supply Ladino and Huppermago.

Game-text resolution prefers PT and uses EN only when the requested text is
blank. Item, rune, sublimation and boss searches include Portuguese names;
passive searches use the selected language for both names and descriptions.
Release notes with no Portuguese text retain their existing English fallback.
Sublimation descriptions are reconstructed in Portuguese from structured
conditions and effects; records carrying only English `rawText` retain that
fallback because the dataset supplies no translated effect text.

Validation: `ktlintFormat` and `:gui-compose:test` through the shared Gradle
lock. The bundle guard automatically covers PT through `Lang.entries`. UI
layout checks cover all four languages, including the top bar at its wrapping
breakpoint; no additional layout fix is required. Game-text tests check
language selection and fallback, compare stat labels with official rune names,
and exercise item/boss searches in Spanish and Portuguese. The bulk exclusion
buttons remain translated and tested. No push or PR creation is part of this
work.
