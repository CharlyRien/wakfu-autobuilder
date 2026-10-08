# Class-spells dataset

The fixed-name `autobuilder/src/main/resources/spells.json` contains 710 encyclopedia-selected spell
records across all 18 classes. `WakfuData.VERSION` pins game data; the bdata step needs the local client.
The detailed field comparison, evidence, correction list and fingerprints live in
[spell-metadata-comparison.md](official-sources-2/spell-metadata-comparison.md).

## Sources

| Field | Source |
|---|---|
| Roster/id, class, category | Ankama encyclopedia |
| AP, MP, WP, range min/max | Local client's Spell table 66, default sheet |
| Class-resource costs | Spell 66 base_cast_parameters, resource-specific sign conventions; ids named through the client characteristic enum |
| ES/PT spell names | Client i18n namespace 3, literal UTF-8, all present ids |
| FR spell names | Client for 13 reviewed misaligned/obsolete listing names; encyclopedia otherwise |
| EN spell names | Encyclopedia; exact client parity required before writing |
| Damage element | Encyclopedia damage line; unsupported LIGHT/STASIS tokens remain explicit |
| Max-level base/crit damage | Encyclopedia rendered anchor |
| Area, LOS, icon, descriptions, resistance-debuff metadata | Encyclopedia |
| Cast limits/cooldown | Client Spell 66 → spell-cast-limits.json |
| Per-level damage formula | Spell 66 → StaticEffect 68 → spell-damage.json, selected/calibrated by the encyclopedia anchor |
| Existing runtime description translations | spell-i18n.json, client namespace 4 (unchanged overlay; spells.json descriptions stay untouched) |

707/710 ids exist in the current client. **5150 Refreshment, 5089 Crazy Scheme and 5123 Bloody Blade**
retain the original record because neither Spell nor any client name bundle contains them. Missing
numeric fields stay null, never zero. Unknown/unreadable fields remain in `missingFields`; successful
client AP/range reads remove only their resolved markers. Other metadata is never guessed.

## Regeneration

1. `spells-extractor`: resumable encyclopedia scrape with cached pages, browser cookies/redirects and
   retries. The class listing selects ids; detail pages supply rendered damage anchors and other fields.
   The output is the intermediate encyclopedia catalog.
2. `bdata-extractor`: `buildSpellMetadata` overlays the reviewed client fields in `spells.json`, then
   builds the usual cast-limit, localization and damage-scaling side tables. Full positional records
   pass the size guard. AP/range/MP/WP use floor(base + increment × max_level); all current increments
   are zero. Resource parameters use distinct sign conventions: -200 HUPPERMAGE_RESOURCE means a 200-breeze spend;
   +3 SP is a three-Stasis-Point cost (the cast validator requires enough SP). Unknown
   resource cost conventions fail pending review. Conditional cast variants are not simulated by this default-sheet merge.
3. The GUI asset task extracts spell icons and display-only LIGHT/STASIS damage icons from gui.jar.

`./scripts/update-game-data.sh [install]` runs this order automatically. To repeat only the metadata
merge after a scrape, run `:bdata-extractor:run --args="--spell-metadata-only [install]"`.
An intentional source/data change needs `BDATA_FORCE_WRITE=1`; ordinary reproduction blocks semantic
oracle drift. `SpellMetadataReproductionTest` compares the entire committed catalog after regeneration
and locks the in-game Light Arrow / Wall of Energy examples. New EN differences fail pending review;
FR replacements are an explicit reviewed id set. CI skips local-client reproduction when no install exists.

## Element evidence and engine safety

Spell table position 30 is the **spellbook branch**, not the damage element. Light Arrow is WATER-branch
but has LIGHT damage action **1083**; Stasis damage is action **917**. Standard damage actions are 2 FIRE,
3 EARTH, 4 WATER, 5 AIR. Walking effects and parent descendants gives an exact singleton match on only
**250/286** damage anchors (240 standard, 10 LIGHT); other effects are indirect or conditional/multiple,
including Foggernaut Stasis variants. The encyclopedia's element is therefore retained. Reproduce the
diagnostic using `--spell-elements-audit`; it writes no game artifact.

`SpellCatalog.damageSpells` / `playableElements` still expose only the four supported damage elements
(Cra = Fire/Earth/Air, never Water). `SpellRotationOptimizer.bestRotation` and `baseThroughputTable`
(the solver/certifier AP cells) exclude **every AP < 1** spell: no WP/MP/class-resource spell can become
free damage, with or without a cast cap. Poursuite's correction 2 AP → 0 AP (2 MP) newly removes it from
this AP-only model. Activation null → 0 remains excluded. Flair is client passive, LIGHT, 0 AP with no
default WP/MP spend; it was never an AP-priced damage spell. Positive-AP WP/resource spells retain the
existing AP-only approximation. Resource-aware rotations require a separate model and review.

The migration bumps **CERTIFIER_VERSION 59 / ENGINE_RESULTS_VERSION 4**, with their history and pair
lock updated. Older saved searches are obsolete and their results should be recomputed. Display-only
changes do not advance these versions further. Catalog/rotation tests and both non-slow certificate
fuzz locks cover the migration.

## Display and level scaling

The class-spells panel includes active utility spells and shows AP, positive WP/MP costs, resource
spends and ranges. Light damage cards show their level-scaled **base hit**, the official sun icon, and
a note that damage searches do not count these spells yet. Even with a build loaded they do not show a
made-up expected hit. A GUI-only element enum handles LIGHT/STASIS tokens without expanding the engine
enum; the current roster has 21 LIGHT damage anchors and **no STASIS anchor**.

The client damage computation establishes Light's game scaling: use the caster's best elemental
mastery and the target resistance of that same element. Stasis uses best mastery and separately the
lowest target elemental resistance. This supports the documented rule; this patch still displays only
the base hit. Future engine support needs the best-mastery selection, coupled resistance selection,
resource budgets and renewed solver/scorer/certificate soundness locks.

`SpellDamageScalingBuilder` keeps the max-level encyclopedia anchor exactly, selecting a matching
bdata slope where possible and a linear anchored approximation otherwise. `Spell.baseDamageAt(level)` /
`critDamageAt(level)` use floor(base + increment × min(level, cap)); they never substitute a client
rendered damage guess. `SpellDamage` and `BuildSpellDamage` scale only supported elements with resolved
build stats. There remain 264 fully readable standard-element damage records and 41 flagged records;
flags now mainly cover 21 LIGHT tokens, 10 absent base hits and 10 unconfirmed debuff targets, plus one
missing-id AP/range pair (overlapping markers, not additional records).

## Damage-anchor research (2026-10-08)

The [client damage investigation](spell-damage-client/README.md) retains the encyclopedia anchor as a
measured exception. The immutable scaling oracle contains 263 matched normal formulas and 23 fallbacks.
First displayed effect reproduces 240/263 normal formulas, only 210/263 including critical; the old
Obliteration formula is absent from the current client. Even anchored regeneration would change its
low-level slope while passing the existing max-level check. No resource, builder, damage value or
engine/certificate version was changed. The report records every mismatch, fallback evidence, Light
coverage and a reproducible diagnostic with full effect metadata.

## Local migration validation (2026-10-08)

After `git fetch origin && git rebase origin/main`, the shared Gradle wrapper ran `ktlintFormat`,
then the complete bdata-extractor (34), common-lib (34), zenith-builder (7) and gui-compose (497, 2 skipped)
suites successfully. Targeted autobuilder tests covered SpellCatalogTest, SpellRotationTest,
EngineResultsVersionTest, MaxDamageSearchTest, MaxDamageSoftCertificateTest,
MaxDamageTargetAwareCertificateTest and both non-slow WakfuBuildSolverTest certificate fuzz locks:
75 tests, 19 opt-in harness cases skipped, no failures. Both fuzz locks executed and passed. Local-client
reproduction tests ran against `/Applications/Ankama/Wakfu`, rather than being skipped for no install.
