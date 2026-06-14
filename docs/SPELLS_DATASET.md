# Class-spells dataset

How the per-class spell data (`autobuilder/src/main/resources/spells-v<VERSION>.json`) is produced and
used. Design rationale lives in `docs/SPELLS_AND_COMBO_RESEARCH.md` (research branch); this file
documents the shipped pipeline.

## Why

The max-damage / boss auto-element search can recommend an element that is optimal on paper but
*unplayable* for the class (e.g. "play Water" to a Cra that has no Water spells). It also can't show a
spell's real damage when comparing same-class builds. Both need actual per-class spell data — element,
AP cost, range, base damage — which **no official machine-readable API exposes** (Ankama's gamedata CDN
403s on spells; WakForge's dump omits the elementary attack spells). So we scrape the **Ankama
encyclopedia**, the authoritative source.

## Pipeline (`spells-extractor` module)

1. **`EncyclopediaClient`** — the encyclopedia bounces a bare request (302) through
   `account.ankama.com/sso-redirect`, which sets a session cookie. We replicate a browser: a built-in
   `java.net.http.HttpClient` with a `CookieManager` (captures/replays the cookie) + `NORMAL`
   redirects + a desktop-Chrome `User-Agent`, primed once on the encyclopedia root. Per-request
   throttle, exponential-backoff retries, and a **resumable** on-disk page cache
   (`spells-extractor/.cache/`, git-ignored) so a re-run only fetches what's missing.
2. **`SpellScraper`** — pure regex HTML→data (no HTML library). The class listing
   (`/classes/<id>-<slug>`) yields `(id, name, icon)` stubs; each spell's detail page yields element /
   AP / range / base+crit damage / area / line-of-sight.
3. **`Main`** — crawls all 18 classes, enriches FR names from the FR class pages, and writes
   `spells-v<VERSION>.json`. Every numeric field is **nullable**; a field that is *expected but
   unreadable* is recorded in `Spell.missingFields` — **no value is ever invented** — and the run
   prints a coverage report.

Re-generate after a data bump: `./gradlew :spells-extractor:run` (optionally `--args="<version>"`).
Keep `VERSION` in `autobuilder/Main.kt` in sync (the resource is loaded as `spells-v$VERSION.json`).

## Coverage (v1.91.1.54)

- **710 spells across all 18 classes.**
- **264 with complete numeric damage** (element + base/crit damage + AP + range + area).
- **41 flagged** in `missingFields`, none invented:
  - **24** are **LIGHT / STASIS** spells (Eliotrope / Foggernaut / Huppermage etc.) — real elements
    with no mastery in the 4-element model. The base hit is captured; the element token is preserved as
    e.g. `element(LIGHT)` so the gap is explicit. (Modelling Light/Stasis masteries is future work.)
  - **17** standard-element partials — mostly self/touch damage spells the page shows **no range** for
    (AP is captured), plus a few passives that merely mention an element.

## Consuming it

- **`SpellCatalog`** (`autobuilder/domain`) — lazily loads the dataset; exposes `forClass`,
  `damageSpells`, and `playableElements(clazz)` (the elements a class can actually deal damage in,
  **derived from the real kit**). `playableElements` is the gate the boss / max-damage auto-element
  search should consult — verified by test: Cra = {Fire, Earth, Air}, **not Water**.
- **`SpellDamage`** (`common-lib`) — reusable, OR-Tools-free expected-damage formula: feed it a `Spell`
  + a build's resolved characteristic totals.
- **`BuildSpellDamage`** (`autobuilder/domain`) — bridges the two: resolves a `BuildCombination`'s
  actual totals and returns a spell's expected hit, for the comparison view.
