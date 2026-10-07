# Spell metadata comparison — 2026-10-07

Original PR #265 outcome: **documented; no source switched** (superseded by the follow-up below). All 710 committed spell records were compared with the
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

## Original PR #265 source fingerprints (SHA-256)

- committed spells.json: `493a7c5cdae7b57694d281c47090cf26d0ab9b397a5c1c8fc6bf658fcf6bcf8c`
- client lib/wakfu-client.jar: `e3eb8b9b6a1fa0d42d2f06f0af3e841ed783b2998f5c81d3cd8312294106d3cd`
- client contents/bdata/66.jar: `9e47655eca77a8820fb5bcbbe900015a6f7f0d7301fa7a488a6692d2aeff9603`
- client contents/i18n/i18n_fr.jar: `12cab5e8b0959cf4cbafa0b675a77da4305a3a45777454239c1cee53f0ba3530`
- client contents/i18n/i18n_en.jar: `a9a077c6805fbfc9caa28d1b398b1c1ec95e3df035f7f3853ace9d82e2d11830`
- client contents/i18n/i18n_es.jar: `7a1e3247388f5e771e31f2ea1e4db7a2c7dc48d4108b71dae04789e7966ce7c8`
- client contents/i18n/i18n_pt.jar: `e6f6a6ecbbd81a86c6235f6781e16a4f8b6c3c848b7239809baa80e461975020`

## Follow-up: in-game resolution (2026-10-07), part 1

Field 30 is the **spell's elemental branch**: Spell's protected I16 at position 30 flows through the
binary getter → spell-definition setter → levelled-spell getter. Its consumers group the spellbook
into an element-keyed multimap, filter support spells and sort by branch; the tooltip also uses it
for `elementsUsedIconURL` / `spellDescription.element`. It is not an effect's damage element and does
not establish which rune a hit generates. Light Arrow 5594 is in the WATER branch (2), yet its effects
348635/348411 use damage action **1083**, LIGHT. The maintainer confirmed 6 AP + 200 quadramental
breeze, range 2–5 and the sun damage icon in game. Wall of Energy 5576: 0 AP + 150 breeze, range 1–3.
The positional schema now calls field 30 `spell_branch` to avoid repeating the wrong interpretation.

The damage element lives on **StaticEffect (68)'s action id**. Structural discovery starts at the
FIRE/WATER/EARTH/AIR/STASIS/LIGHT/SUPPORT/PHYSICAL enum, reads constructor codes (never ordinal), then
finds the action registry through its `Dommage : …` strings and its constructor's enum argument:
2 FIRE, 3 EARTH, 4 WATER, 5 AIR, **917 STASIS**, **1083 LIGHT**, 1 PHYSICAL. The pinned CDN
[actions.json](https://wakfu.cdn.ankama.com/gamedata/1.93.1.62/actions.json) supplies the named LIGHT
registration and `[el6]` description; it omits the basic four and Stasis, whose evidence is bytecode.
Names observed in this client only (diagnostic breadcrumbs, never lookup keys): aNt's field erI/getter
cwt → bPU → fyV.hn/gtR → fyH → fyI; spellbook bhb/bPN/bPP; enum eVi; registry eVk; damage effect eXv.

Comparison follows each spell's effect_ids and parent_id descendants, with cycles deduplicated.
Of **286** encyclopedia records with baseDamage, **285** exist in the client (5123 absent):
**250/286** have exactly one damage element and it equals the encyclopedia (**240/265** standard
four-element records, **10/21** LIGHT). Twenty records have additional damage elements (7311 FIRE +
PHYSICAL, 921 WATER + AIR, 925 EARTH + WATER, 749 WATER + LIGHT; plus 15 Foggernaut elemental spells with STASIS variants and Hypertension
6918 LIGHT + STASIS); the encyclopedia anchor selects the
expected element in the first four, but cannot uniquely select the Foggernaut variants. Sixteen have no direct damage action in this traversal (indirect state,
script, passive or absent). Anchor-matched normal effects reproduce a singleton element on **248/286**.
There are **0 STASIS damage anchors** in the committed roster: no claim of Stasis agreement can be made.
The registry identifies it, but the encyclopedia does not provide a damage row to compare here.

**Decision: retain every encyclopedia element.** Neither direct nor anchor-based extraction reproduces
the whole damage roster. No unexplained difference is silently treated as an encyclopedia error.
The full comparison is `spell-damage-elements.csv`; reproduce through
`bdata-extractor --spell-elements-audit [install]` (diagnostic CSV; writes no game artifact).
`SpellElementAuditTest` locks the Light Arrow counterexample and whole-roster agreement count.

### Light scaling evidence (for the display-only follow-up)

The same registered damage effect constructs the client's damage computation with the LIGHT enum.
In that computation, LIGHT chooses the caster's **highest elemental damage mastery** and sets the
resistance element to that same element. STASIS chooses the same best mastery but the target's
**lowest elemental resistance** independently. The helper iterates the elemental enum, skips LIGHT
and STASIS, skips entries without a mastery, and retains the largest actual mastery; equal values keep
the first in enum order (FIRE, WATER, EARTH, AIR). Observed breadcrumbs: eXv → faw.fSB → eYG.a(PJ)
(best mastery), eYG.b(PJ) (least resistance). These names are evidence for this fingerprint only.
The public enum maps each standard element to its own DMG_*_PERCENT characteristic; this is not the
otherwise-present LIGHT_MASTERY characteristic. The normal secondary, crit and damage-inflicted
terms are then applied by the damage computation. This is client code evidence, not an inferred rule
from field 30. Adding Light to the engine would require explicit best-mastery selection, coupled target
resistance selection, resource/cooldown budgets and renewed soundness proofs for the AP-cell bounds.

## Part 2: reviewed source migration

`buildSpellMetadata` overlays only 707 present ids; **5150, 5089 and 5123 retain their original unknown
values/names**. No table or i18n record exists for them. The encyclopedia continues selecting all 710
ids and their category, element, damage anchors, area, LOS, icon, description and debuff metadata.
The existing `spell-i18n.json` localized description overlay at runtime is unchanged; the description
in `spells.json` is untouched. Runtime names now respect the reviewed names in `spells.json` rather
than wholesale overwriting FR/EN from the side table.

| Field | Changed records | Conflicting known values | Filled unknown values |
|---|---:|---:|---:|
| AP | 348 | 43 | 305 |
| range min | 373 | 31 | 342 |
| range max | 421 | 79 | 342 |
| ES name | 691 | 691 | 0 |
| PT name | 690 | 690 | 0 |
| FR name | 13 | 13 | 0 |
| EN name | 0 | 0 | 0 |

All 707 present records also get MP and WP (including explicit zero); **4 have a positive MP cost**,
**103 a positive WP cost**. Ten Huppermage spells carry additional spends in `base_cast_parameters`:
key **111**, resolved structurally from the characteristic enum to `HUPPERMAGE_RESOURCE`, has negative
base values: 50/100/150/200 breeze. Three Foggernaut entries have **positive** key 123 (`SP`) values: Stasis Point COSTS, not signed gains:
6918 Hypertension 2, 6920 Ambush 1, 6922 Stasis Flux 3. The sign convention differs from Huppermage's.
The client's `sp` tooltip key reads this amount unchanged, and the cast validator checks the amount
is <= current SP. Plain i18n keys `SP` / `SPDescription` say Stasis Points cast special spells. Observed
breadcrumbs: fyH.f → fcP.e; bPQ's `sp` branch → fyS.r(SP); enum id 123 (SP). No universal sign
rule is assumed: unknown additional resource conventions fail pending review. All 13 resource costs
are displayed (10 breeze, 3 SP). All current increments are zero; the builder
still evaluates floor(base + inc × max_level), including resource parameters. Conditional `_51` cast
parameter variants are deliberately not simulated: these are the default spell-sheet costs/ranges.

### The 13 French corrections (English unchanged)

The scrape's French listing name disagrees with its id's English name and spell behavior (many labels
were shifted between neighboring ids). The client supplies the id-correct localized name. These ids
alone are reviewed for replacement; an unreviewed FR difference stays untouched, and any future EN
difference fails extraction pending review.

| Id | Before | Client / retained English | Evidence for correction |
|---|---|---|---|
| 4720 | Téléportono | Gueule de bois / Worn-Out | Heal/armor/barrel repel; not a teleport |
| 6845 | Ebriété | Pandanlku / Pandiniuras | MP/range active ally buff, not Merry state |
| 7064 | Dynamite | Kaboom / Kaboom | Id's EN and client FR agree; obsolete FR title in scraped prose |
| 5030 | Furie sanguinaire | Attirance / Attraction | Description attracts a target |
| 5041 | Projection | Cinétose / Motion Sickness | Description triggers damage on movement |
| 5043 | Attirance | Transposition / Transposition | Description switches positions |
| 5044 | Transposition | Sacrifice / Sacrifice | Description intercepts allies' damage |
| 5045 | Sacrifice | Armure sanguine / Sanguine Armor | Description stabilizes and grants armor |
| 5047 | Armure sanguine | Bain de sang / Blood Bath | Id's EN and client FR agree; distinct vulnerability spell |
| 7211 | Entaille | Coagulation / Coagulation | Description raises armor cap and shares armor |
| 4604 | Invisibility | Invisibilité / Invisibility | Untranslated EN copied into FR |
| 6942 | Tacticien | Bidouillage / Tinkering | Turret gains effects at expense of damage |
| 7084 | Esprit masqué | Entrechoquement / Clashing | Description deals collisions; not a double summon |

### Engine-visible change and 0-AP safety

Of the **265** spells with a supported element and readable damage, AP changes only on **7077 Poursuite
2 → 0** (real cost 2 MP) and **6937 Activation null → 0** (client passive). Poursuite is newly excluded
from the AP-only rotation; Activation was already excluded for unknown AP. The other seven damage
records gaining 0 AP are unsupported LIGHT: Flair 6279, Force sage 6282, Exploupée 933, Embuscade 5122,
Roues chaudes 6940, Présages violents 7187, Flétrissement 7195. These are client passives (passive=2),
not free active attacks, and remain outside the element-supported engine. The encyclopedia categories
and damage are retained rather than silently changing the roster. Flair carries no default WP/MP cost
in the client: its apparent AP 1 was prose misread by the scrape. No other AP 0 damage record is adopted.

`bestRotation` requires scored AP >= 1; `baseThroughputTable` (solver AND certificate) requires cost >= 1.
Thus even a spell with a finite cast cap and a WP/MP/class-resource spend contributes **no free damage**.
The safety choice is **exclusion**, retaining the existing guards, locked on real Poursuite/Flair and
synthetic bounded/unbounded MP/WP-only spells. Positive-AP spells still use the existing AP-only
approximation; general MP/WP/resource modelling is a separate change. **CERTIFIER_VERSION 58 → 59,
ENGINE_RESULTS_VERSION 3 → 4** invalidate bounds and flag old saved searches; history comments and the
pair lock are updated.

The class-spells panel now includes active utility spells too and shows MP/WP/breeze alongside AP and
range. Breeze and Stasis Point expenditure conventions are tested separately. Default AP/range remain separate from conditional overrides. Reproduce after an encyclopedia
scrape with `bdata-extractor --spell-metadata-only [install]` (or the full extractor, already wired after
the scrape in `update-game-data.sh`). `BDATA_FORCE_WRITE=1` is needed for intentional oracle changes;
the whole-catalog `SpellMetadataReproductionTest` requires exact reproduction on subsequent runs.

### Follow-up source fingerprints (SHA-256)

These installed jars differ from the original report fingerprints; the follow-up independently decodes
and compares the current install, rather than assuming the old binaries. All AP/range/name difference
counts and the three absent ids above were rechecked. CDN actions remain pinned to 1.93.1.62.

- `lib/wakfu-client.jar`: `7dd7003aeb0bfb0dc7ee94b6c1a579c5d8fc93e35dd4ba416a0275925f15bef5`
- `contents/bdata/66.jar`: `1a40731645620caa2d6376ac83f12e88f79319ae96fdd5fb056a3f061897fb8d`
- `contents/bdata/68.jar`: `f84b4e2722f08186d5ad86801ee31ae4dc3b8c496fee7b8f0748a175b2b72bd1`
- `contents/i18n/i18n_fr.jar`: `23fcf99e9312e692c052591840139592bb7a22a9a5a34b2a5a980186795c9bf9`
- `contents/i18n/i18n_en.jar`: `c153f9bec6e383ccc343f8202af58971fcc5bf8b9d5bbcbed9866473bb7c0251`
- `contents/i18n/i18n_es.jar`: `a8f1f86971f803f7315972f516f938464437bd8d9a04d63da9e9c1d99329a2da`
- `contents/i18n/i18n_pt.jar`: `8eefc6908cbee973fa481bbc9402c3fc9bf653a1625f4a1ce9ce01ab711e7bb9`
- Pinned CDN actions.json: `7937aee6ec35c891c0c273283baa8db5af987ec2c66a0a8bc6dc07e5c29702df`

## Part 3: Light damage display only

The class-spells panel reads the retained `element(LIGHT)` token through a GUI-only enum, displays the
client's `miscellaneous/elements/LIGHT.tga` sun icon and the encyclopedia-anchored `baseDamageAt(level)`.
Even with a build selected it shows a **base hit**, not a fabricated mastery-scaled expected hit, and a
four-language note says the damage search does not count Light spells yet. The same presentation can
handle a future STASIS token with its official icon, but there is no current STASIS damage anchor.
`SpellElement` still has exactly four entries, `hasDamage` remains false for Light and the solver/
certifier/rotation catalog remains unchanged by this display patch. UI tests lock Light Arrow at level
100 with a loaded build, its icon/note and engine exclusion. The extraction task reproduces both icons.
The scaling evidence and requirements for a future engine addition are recorded in part 1 above.
