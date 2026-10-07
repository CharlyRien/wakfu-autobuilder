# Spell metadata comparison — 2026-10-07

Outcome: **documented; no source switched**. All 710 committed spell records were compared with the
installed 1.93.1 client and pinned CDN version 1.93.1.62. Three ids are absent, and several fields differ.
Adopting the differing AP/range/element values would change engine results, requiring the separate reviewed
change and version bumps excluded by this brief. The scrape and damage anchor remain untouched.

## Structural decode

The JVM classfile scan selected the table-type enum by constant names ITEM/SPELL/STATE/STATIC_EFFECT/MONSTER,
read the explicit SPELL constructor id (66), and followed references to that constant in binary-data
implementors' `int typeId()` methods. It did not select any obfuscated class or field name. Of the two
candidate layouts, one decoded the first 50 entries exactly; the other failed the size/id checks. The
selected layout then decoded **all 4,143 records**, each with its indexed byte size and id checked.
Protected instance fields in classfile order supply the wire schema, including the generic Signature
attributes of the three trailing HashMaps and their nested structs. Unlike the production SchemaGenerator
(which rejects HashMap fields), this investigation’s classfile reader resolves HashMap key/value signatures too.

The typed positions agree with the complete existing Tables.SPELL_SCHEMA: id=0 (I32), gfx=2 (I32),
max_level=3 (I16), breed=4 (I16), AP base/inc=19/20 (F32), range max base/inc=25/26 (F32), range min
base/inc=27/28 (F32), element=30 (I16). No obfuscated field identifier supplies semantic meaning.
Element names/codes come from the enum holding FIRE/WATER/EARTH/AIR/STASIS/LIGHT/SUPPORT/PHYSICAL:
explicit codes FIRE=1, WATER=2, EARTH=3, AIR=4, STASIS=5, LIGHT=6, SUPPORT=9, PHYSICAL=0.
Only the four supported damage elements map to SpellElement; the others map to null, as the existing domain requires.
Names are literal UTF-8 `content.3.<spellId>` from each original (not cleaned) i18n property bundle,
using the already-established spell-name namespace of the official spell-i18n pipeline.

AP and ranges were evaluated as floor(base + increment * max_level), matching the audit's candidate;
**all 707 overlapping records have zero AP/range increments**, so rounding/level choice introduces no ambiguity.
These are the default table values, not a simulation of conditional cast overrides; conditional variants
remain in the trailing maps/criteria and are another reason not to silently replace the current engine inputs.

## Results

There are 710 unique committed ids; 707 exist in the client. Missing: **5150 Refreshment (Pandawa),
5089 Crazy Scheme (Sram), 5123 Bloody Blade (Sram)**. The missing ids have no name in any of the four
client bundles. They are recorded as missing records, not counted again as field differences.

| Field | Compared present records | Differences | Of which old value was non-null |
|---|---:|---:|---:|
| id | 707 | 0 | 0 |
| name.fr | 707 | 13 | 13 |
| name.en | 707 | 0 | 0 |
| name.es | 707 | 691 | 691 |
| name.pt | 707 | 690 | 690 |
| element | 707 | 20 | 10 |
| apCost | 707 | 348 | 43 |
| rangeMin | 707 | 373 | 31 |
| rangeMax | 707 | 421 | 79 |
| iconId | 707 | 14 | 14 |

Null differs from zero; changing null into a known value is not exact reproduction. For numeric targets,
the non-null column separates actual conflicting known values from filled metadata gaps. For names,
counts include exact whitespace/case/UTF-8 differences; no normalization was used to force parity.
The complete list of differences is [spell-metadata-differences.csv](spell-metadata-differences.csv)
(JSON cells preserve nulls and strings). The 707 present ids and English names match exactly, but neither
covers the three absent records; there is no whole-catalog identity/name reproduction to adopt.

Examples (committed → client):

- AP: 4774 Roly-Poly 2 → 0 (the client charges 2 WP); 7077 Pursuit 2 → 0 (2 MP);
  6279 Sniff and 6282 Wise Strength 1 → 0. The latter two are marked ACTIVE with damage in the committed
  scrape even though the client has passive records. The scraper flattens info HTML and guesses the leading
  number as AP (`AP_RANGE`, `AP_RANGE_SINGLE`, `AP_ONLY`), so WP/MP and prose numbers can masquerade as AP.
  This shows errors in the scrape, not permission to change engine values in this batch.
- Range: 4812 Static Arrow 1–1 → 5–8; 4810 Retreat Arrow max 1 → 4;
  6842 Flurry 1–1 → 0–0. Some spells have conditional variants; this comparison uses the base sheet.
- Element: 6957 Hunter’s Instinct FIRE → null (SUPPORT=9, passive); 6973 Wave null → WATER;
  5561 Resonance null → FIRE. Damage-line element and whole-spell element are not necessarily the
  same concept for utility spells/passives. The audit's proposed raw-field replacement must resolve that
  semantic distinction in a separate engine-data review.
- Icon: 5041 Motion Sickness 883 → 2100; 4583 Lethal Attack 2417 → 2421;
  4585 Perfidious Attack 2421 → 2417. These cannot all be claimed as proven encyclopedia errors without
  reviewing source pages/variants, so the icon field stays on its current source too.
- FR name: 4720 Téléportono → Gueule de bois, 6845 Ebriété → Pandanlku.
  ES/PT: 4769 “Blazing Arrow” → “Flecha Ardiente” / “Flecha Ardente”. Most committed ES/PT names
  are English copies; the current runtime already replaces available names through spell-i18n.json.

`spells.json` contains **no maxLevel field** (nor any filled levelRequired field), so that proposed field
has no committed oracle. Every overlapping Spell table record has max_level=245. The separate existing
spell-damage.json has 286 caps, of which 285 have client records and all 285 equal 245 (zero differences);
its one absent id is 5123. It already takes this cap from bdata. Adding maxLevel to Spell/spells.json would
be a new schema field, not an identical-value source substitution; it was not added.

## Current source split

- spells.json: encyclopedia-selected ids/roster, scraped metadata and rendered max-level damage anchors.
- spell-i18n.json: official client names/descriptions for available ids; SpellCatalog already joins these.
- spell-cast-limits.json: official client cast limits/cooldown/WP, joined by id.
- spell-damage.json: official per-level formula/cap, still selected/calibrated by the encyclopedia damage anchor.

No damage number, resource, domain field, scorer, engine or certificate version was changed by this investigation.
No BDATA_FORCE_WRITE or encyclopedia network refresh was used; the comparison is against the committed oracle.

## Source fingerprints (SHA-256)

- committed spells.json: `493a7c5cdae7b57694d281c47090cf26d0ab9b397a5c1c8fc6bf658fcf6bcf8c`
- client lib/wakfu-client.jar: `e3eb8b9b6a1fa0d42d2f06f0af3e841ed783b2998f5c81d3cd8312294106d3cd`
- client contents/bdata/66.jar: `9e47655eca77a8820fb5bcbbe900015a6f7f0d7301fa7a488a6692d2aeff9603`
- client contents/i18n/i18n_fr.jar: `12cab5e8b0959cf4cbafa0b675a77da4305a3a45777454239c1cee53f0ba3530`
- client contents/i18n/i18n_en.jar: `a9a077c6805fbfc9caa28d1b398b1c1ec95e3df035f7f3853ace9d82e2d11830`
- client contents/i18n/i18n_es.jar: `7a1e3247388f5e771e31f2ea1e4db7a2c7dc48d4108b71dae04789e7966ce7c8`
- client contents/i18n/i18n_pt.jar: `e6f6a6ecbbd81a86c6235f6781e16a4f8b6c3c848b7239809baa80e461975020`
