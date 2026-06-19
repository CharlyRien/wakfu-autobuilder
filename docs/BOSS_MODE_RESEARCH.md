# Boss Mode — Research & Design

Status: **research / design only** (no implementation). Author pass: 2026-06-14.

> ⚠️ **Superseded data pipeline (2026-06-19).** The `monsters-extractor` module and the MethodWakfu/Fandom
> scrape described below have been **removed**. `monsters.json` (fixed name, no `-v<version>` suffix) is now
> produced by the **`bdata-extractor`**, decoded from the local game client's Monster table (42) + i18n names,
> with boss-tier `rank` and icon `gfx` carried by the committed `monster-overlay.json`. The design/scoring
> narrative below still holds; only the data *source* changed. See `AGENTS.md` §5 and `CONTRIBUTING.md`.

> **Vision.** Pick a boss → get the best **max-damage** build for the player's class against it.
> Later: richer inputs (orientation / "de dos", distance, position…). This doc answers three
> questions: (1) does this plug into the max-damage work already on `feat/max-damage-mode`?
> (2) does a community/official boss-stats dataset exist, and how machine-readable is it?
> (3) what's a concrete, phased design?

---

## 0. Implementation status (shipped on `feat/boss-mode`)

Boss mode is **implemented** on top of the max-damage engine, following the phased plan below:

- **Data pipeline (`monsters-extractor`).** Crawls the MethodWakfu Reborn bestiary **REST API**
  (`/api/bestiary` — plain JSON, cleaner than the `_payload.json` devalue route), cross-references the
  Fandom `MonsterCard` template for provenance, and writes `autobuilder/.../monsters-v<version>.json`.
  **715 monsters (226 bosses)** ingested. Of the 111 bosses MethodWakfu cannot serve (HTTP 500
  resolving their drops — `"Unknown item: …"`, the website itself 500s), the extractor's **Fandom
  fallback** recovers the ones Fandom documents at a matching level (flat, signed resistances —
  validated same scale): **1 today** (Tofu Dominant). The remaining **110** (38 rank-2 dimension
  golems + 72 rank-4 endgame "Dominant" bosses) exist on no reachable source at a matching level —
  MethodWakfu detail 0/111, Fandom 1/111 level-aligned, wakfu.wiki.gg 403, official encyclopedia 403
  and never exposed resistances — so they are **listed by id/name/level, never faked**.
- **Engine.** `Monster` model (`common-lib`); `BossScenario.flatResistanceToPercent` (G3) keeping
  weaknesses **signed** (G2); `Monster→DamageScenario` mapping over the full **4-element profile** (G1);
  `BossSearch` runs the max-damage search **once per element** and keeps the best (auto-element), with
  expected-damage / element-choice / hits-to-kill helpers; `FindMaxDamageScoring` now caps resistance
  only from above so a weakness raises damage.
- **CLI.** `--boss "<nom FR>"` + `--boss-element auto|fire|water|earth|air` + `--boss-difficulty <×HP>`,
  with a boss report (resistance profile, chosen element, hits-to-kill). **GUI.** A searchable boss
  picker, a read-only resistance profile, an Auto/per-element selector, a difficulty (HP ×) stepper,
  and a hits-to-kill result card; EN/FR i18n.

### Dungeon difficulty (Stasis level / steles)

**What difficulty scales — verified.** Per Ankama's [Stasis Dungeons Revamp devblog](https://www.wakfu.com/en/mmorpg/news/devblog/tickets/879338-devblog-stasis-dungeons-revamp):
cranking dungeon difficulty up scales the boss's **HP** (heavily), Force of Will, Final Damage, and
possibly its masteries/initiative/lock/dodge — i.e. the boss's *durability and offense*. **Its
elemental resistances stay the same across difficulty levels** ("Resists will always be the same").
(Party-size scaling multiplies HP *and* resist uniformly — 6p 100% → 4p 75% HP / 85% resist — and
steles add bespoke per-turn `+resist`/mechanic modifiers, but neither is a static per-level value.)

**Impact on the optimiser — none.** The only **build-dependent** term in the damage formula is the
target's resistance (via `(1 − Res%/100)`); HP, the boss's offense, FoW etc. don't appear. Since
resistances are constant across difficulty, **difficulty does not change which build is optimal, nor
the auto-element choice** (a uniform resist scale — party size — is monotonic and preserves the
element ordering too). It only changes **how many hits the build needs** (more HP → more hits).

**How it's modeled.** The data sources expose a *single* HP per monster, not a per-Stasis-level curve,
so difficulty is modeled as an **HP multiplier** that feeds only `hitsToKill` (`--boss-difficulty`,
GUI "Difficulty (HP ×)"); resistances are deliberately left untouched. Recommendation: a "dungeon
level" input is **worth having for the hits-to-kill readout only** — it must *not* be wired into the
optimisation, and the UI says so.

Everything below is the original research/design. Note: single-target / area mastery (old G6) is
**not modeled** — Ankama removed those masteries from the game in 2023, so there is nothing to model;
boss mechanics (G9) remain out of scope.

---

## 0bis. TL;DR

- **Compatibility: strong / "green".** Boss mode is essentially a thin layer that **auto-fills the
  existing `DamageScenario`** (from the boss's element resistances, plus user-chosen orientation /
  range / berserk / heal) and then runs the existing **`FIND_BUILD_WITH_MAX_DAMAGE`** objective. The
  `DamageScenario` model was deliberately built so that everything boss-derived (resistance,
  orientation multiplier, base hit) is a *constant* factor, and only `ΣMastery / DI / crit` move with
  the build — which is exactly the seam boss mode plugs into. **Almost no engine change is required**
  for a first version.
- **Main model gaps** (see §2.3): the scenario stores **one** element + **one** `targetResistancePercent`,
  but a boss has a **4-element resistance profile**; resistance is **clamped to 0..90** so **negative
  resistance (weaknesses) is lost**; there is **no boss HP / barrier / armor** (fine for *ranking*
  builds, needed for "hits-to-kill" display and realism); **single-target vs area** mastery is
  unmodeled (already a known max-damage limitation, no data source); and there is **no Light/Stasis**
  element, **no boss mechanics** (phases, dynamic resistance, immunities), and **no class→element**
  knowledge to drive automatic element choice.
- **Data: there is NO official machine-readable monster dataset.** Monsters are **not** in Ankama's
  gamedata CDN (confirmed: corvo-astral mirrors the whole CDN — actions/items/recipes/resources/
  states… — and ships **no** monster file; a dev-forum thread notes Ankama deliberately withholds
  monster data). Viable community sources, best→worst for our needs:
  1. **MethodWakfu "Reborn" bestiary** (`db.methodwakfu.com/bestiary`, ~825 creatures) — server-side
     HTML, shows **flat resist + computed %** per element, level, HP. **Scrape required (no public
     API).** Best coverage/quality; small longevity risk (it's a revived fansite).
  2. **Wakfu Fandom wiki `MonsterCard` template** (`wakfu.fandom.com`) — **clean MediaWiki API**
     (`action=parse&prop=wikitext`), structured numeric fields (`level/hp/ap/mp/{fire,water,earth,air}resist`).
     Easiest to ingest; **coverage/freshness is community-edited and uneven**, newest bosses may lag.
  3. **Official in-game / web bestiary** (`wakfu.com/.../encyclopedia/monsters`) — authoritative,
     but anti-bot (HTTP 403 to scrapers) and **historically does not publish elemental resistances**.
- **Recommended approach:** ship a **small hand-curated `monsters-vX.json`** (marquee dungeon bosses)
  baked into `autobuilder` resources exactly like `equipments`/`runes`; add a `Monster` model in
  `common-lib`; map boss→`DamageScenario`; expose `--boss` (CLI) and a boss-picker (GUI). **Automate
  ingestion later** (Fandom API first, MethodWakfu scrape to fill gaps).

---

## 1. What's already on `feat/max-damage-mode` (the foundation)

Read via `git show feat/max-damage-mode:<path>` (the branch is checked out in another worktree).

### 1.1 `DamageScenario` (`autobuilder/.../domain/DamageScenario.kt`)
The fixed attack scenario the max-damage mode optimizes for:

```kotlin
enum class SpellElement(val masteryCharacteristic: Characteristic)   // FIRE/WATER/EARTH/AIR
enum class RangeBand(val masteryCharacteristic: Characteristic)      // MELEE / DISTANCE
enum class Orientation(val multiplierPercent: Int, val grantsRearMastery: Boolean)
                                                                    // FACE 100 / SIDE 110 / BACK 125(+rear)

data class DamageScenario(
    val element: SpellElement = FIRE,
    val rangeBand: RangeBand = DISTANCE,
    val orientation: Orientation = BACK,
    val berserk: Boolean = false,           // caster ≤ 50% HP → berserk mastery
    val healing: Boolean = false,           // attack is a heal → healing mastery
    val critCapPercent: Int = 100,          // usable crit-rate ceiling
    val targetResistancePercent: Int = 0,   // ALREADY-reduced %, capped 0..90
    val baseDamage: Int = 100,              // scales the displayed expected-damage number
)
```

The KDoc states the key property explicitly: *"for a fixed scenario most terms are build-independent
constants — only `ΣMastery`, `% Damage Inflicted` and the crit rate move with the build."* So
`orientation`, `targetResistancePercent`, `baseDamage` **scale every build equally** → they change the
displayed number, **not which build is optimal**.

### 1.2 The objective (`FindMaxDamageScoring.kt`)
Implements Wakfu's exact per-hit formula (cross-checked against wakfucalc / wakfu wiki, see
`docs/ENCHANTMENTS_PLAN.md` §8):

```
dmg   = Base × (1 + ΣMastery/100) × Orientation × Crit × (1 + ΣDI/100) × (1 − Res%/100)
E[dmg]= (1−p)·dmg(noncrit) + p·dmg(crit)     // p = crit rate; a crit also adds critical mastery
```

`ΣMastery` sums the masteries that apply in the scenario: spell-element mastery (generic elemental
folded in) + distance/melee + rear (back only) + optional berserk/healing; `+ critical` only on a crit.
Required AP/MP/range/… targets are enforced with the same `(100/success%)^6` shortfall penalty as the
most-masteries scorer.

### 1.3 Wiring already in place
- **CLI** (`Main.kt`): `--computation-mode max-damage` plus scenario flags `--scenario-element`,
  `--scenario-range`, `--scenario-orientation`, `--scenario-berserk`, `--scenario-healing`,
  `--crit-cap`, `--enemy-resistance`, `--base-damage`. Target stats become optional in this mode
  (they act only as hard AP/MP/range constraints).
- **Params**: `WakfuBestBuildParams` carries `damageScenario`; `WakfuBuildSolver`/CP-SAT consume it.
- **GUI**: `UiState.scenario: DamageScenario`; `RequestPanel.DamageScenarioCard` already renders
  element / range / orientation segmented rows, berserk/heal toggles, and crit-cap / enemy-resistance
  number fields, with i18n `Tr.SCENARIO_*` keys.

**This means the entire "attack scenario → optimal build" path already exists.** Boss mode adds a
*source* for the scenario values and a *selection UX*, not a new engine.

---

## 2. Compatibility verdict & model gaps

### 2.1 Verdict: **boss mode branches cleanly onto the max-damage work.**
A selected boss reduces to:

```
Boss + (user orientation/range/berserk/heal) + (player class & level)
      → choose attack element
      → DamageScenario(element, rangeBand, orientation, …, targetResistancePercent = boss.resPct[element])
      → existing FIND_BUILD_WITH_MAX_DAMAGE search
```

Everything boss-derived is already a *constant* in the formula, so the optimizer needs **no new math**.
Boss mode is largely **additive** and sits **above** the engine.

### 2.2 The one structural mismatch: element selection
The scenario optimizes **one** element with **one** resistance. A boss has **four** resistances and a
class may have **several** usable elements. "Best build for the class vs this boss" therefore implies
**picking the attack element** that maximizes damage = (achievable `ΣMastery` for that element) ×
`(1 − res%/element)`.

- **Phase-1 answer (no engine change):** orchestrate **one solve per candidate element** and keep the
  best. Candidates ≤ 4 (or fewer if class-restricted). Cost = N× a normal search; acceptable, and the
  per-element solves are independent (parallelizable).
- A single CP-SAT model that maximizes `max` over elements is possible but materially harder; **not**
  recommended for v1.

### 2.3 Concrete model gaps (what's missing for a faithful boss mode)

| # | Gap | Impact | Fix / where |
|---|-----|--------|-------------|
| G1 | **Per-element resistance profile.** Scenario has one `element` + one `targetResistancePercent`. | Can't represent a boss; can't auto-pick element. | Store `Monster.resistances{fire,water,earth,air}`; boss layer emits one `DamageScenario` per element. **No core engine change.** |
| G2 | **Negative resistance clamped.** `targetResistancePercent.coerceIn(0,90)` floors weaknesses at 0. | Within one element: cosmetic (constant). **Across elements (auto-pick): wrong** — a −20% weakness should beat a +30% resist but is clamped equal-ish. | Relax the lower bound to allow negative values (the formula `(1 − res/100)` already handles it; the in-game min is around −something but ≤0 is the point). |
| G3 | **Flat-resist → %.** Sources give **flat** resist (e.g. 800); engine wants **%**. | Need conversion when ingesting. | `resPct = floor((1 − 0.8^(flat/100)) × 100)`, cap 90 (matches WakForge `calcElemResistancePercentage`; verified: 800→83%, 500→67%). Store flat in data, convert in mapping. |
| G4 | **No boss HP.** | Fine for *ranking* (HP doesn't affect the per-hit formula). Needed for **"hits / turns to kill"** display, which is much of boss-mode's value. | Add `Monster.hp`; compute `ceil(hp / E[dmg])` for display only. |
| G5 | **No barrier / armor / block / fixed-damage.** Formula §8 has `(… + FixedDmg − Barrier) × Block`. | Build-independent → **doesn't change ranking**; only the displayed number / hits-to-kill realism. | Optional later: `Monster.barrier`, `Monster.armorPercent`; thread into the displayed number only. |
| G6 | **Single-target vs area mastery.** | Already a **known** max-damage limitation (no quantifiable data source; actionId-400 is a valueless marker, WakForge doesn't model it either). Boss fights are usually single-target → real builds want it. | Out of scope until a data source appears; document the caveat in the UI. |
| G7 | **Only 4 elements (no Light/Stasis).** `SpellElement` ∈ {FIRE,WATER,EARTH,AIR}. | Xelor (stasis) and light-dealers can't be modeled vs bosses' light/stasis resist. | Extend `SpellElement` + masteries if/when those classes are targeted. Most bosses' core profile is the 4 elements. |
| G8 | **No class→element knowledge.** `Character` has `clazz` but not its usable spell elements. | Auto-element would try all 4 even if the class/build is mono-element. | Add a `CharacterClass → Set<SpellElement>` map (or let the user constrain). Phase-4 nicety; v1 can just try all 4. |
| G9 | **Static snapshot only — no boss mechanics.** No phases, dynamic resistance, immunities, reflect, elemental shields, applied debuffs (res reduction). | Real fights differ from the snapshot. | Explicitly out of scope; offer a manual "resistance debuff" slider (subtract N from boss res) as a pragmatic stand-in. |
| G10 | **Boss level unused.** | Not needed by the damage formula. | Use only for UX (filtering, suggested character level, display). |

**Bottom line:** G1/G2/G3 are the only items that touch the engine/scenario, and they are small
(a data model + a relaxed clamp + a conversion). Everything else is display-only or future scope.

---

## 3. Data sources for boss/monster stats

### 3.1 Official Ankama data — **monsters are withheld**
- Ankama publishes gamedata JSON at `https://wakfu.cdn.ankama.com/gamedata/<version>/<type>.json`
  (version from `config.json`). Types include `items`, `actions`, `recipes*`, `resources`,
  `collectibleResources`, `equipmentItemTypes`, `itemProperties`, `states`, `jobsItems`… **There is
  no `monsters`/`mobs` type.** Confirmed two ways: the dev-forum "JSON DATA" thread enumerates the
  exposed types (no monsters; a note that Ankama doesn't want to leak monster data), and
  **corvo-astral**, which mirrors the entire CDN, ships **no** monster file in `data/raw/cdn/`.
- The **official web/in-game bestiary** (`wakfu.com/en/mmorpg/encyclopedia/monsters`, the encyclopedia
  added in 1.90) is authoritative and has filters, **but**: it is anti-bot (returns **HTTP 403** to
  scripted fetches) and **historically does not surface per-element resistances** (level, family,
  drops, locations — yes; resistances — no). Not a practical machine-readable source.

### 3.2 MethodWakfu "Reborn" bestiary — **best data, scrape required**
- `https://db.methodwakfu.com/bestiary` — **~825 creatures**. Each entry page
  (e.g. `…/bestiary/5165`) renders **server-side HTML** with: **level, HP, and all four elemental
  resistances as flat value + computed %**. Verified live: *Aguabrial* — Lv 200, HP 170 000,
  Water 83% (800), Air/Earth/Fire 67% (500). The flat+% pairing matches our formula exactly (G3).
- **Machine-readability:** medium. **No public API** found; data is in the HTML (server-rendered, so
  a straightforward HTML parse works — no headless browser needed). FR-localized labels.
- **Freshness/longevity:** it's a **revived fansite** ("MethodWakfu Reborn"; an "End of MethodWakfu"
  forum thread predates the revival). Live and reasonably current today, but treat continuity as a
  risk → snapshot/bake the data, don't fetch at runtime.

### 3.3 Wakfu Fandom wiki `MonsterCard` template — **cleanest to ingest**
- `https://wakfu.fandom.com` exposes a standard **MediaWiki API**. The `Template:MonsterCard` carries
  numeric fields: `level, xp, hp, ap, mp, wp, ini, dodge, lock, capture, family,
  {fire,water,earth,air}resist, {fire,water,earth,air}attack`, …
- **Verified working queries** (no auth):
  - List monsters: `…/api.php?action=query&list=embeddedin&eititle=Template:MonsterCard&eilimit=500&format=json`
    (paginates via `eicontinue`).
  - Fetch one: `…/api.php?action=parse&page=Gobball_(Monster)&prop=wikitext&format=json` →
    `level=10, hp=84, ap=5, mp=4, earthresist=8, fireresist=4, waterresist=4, airresist=-7` (note
    **negative** air resist — exactly the weakness case in G2).
- **Machine-readability:** **high** — JSON API, structured template, trivial to parse.
- **Caveats:** community-edited → **coverage and freshness vary** (newest endgame bosses may be
  missing/incomplete); resistance **scale convention differs** from MethodWakfu (these low-level
  values look like raw **%**, whereas endgame uses flat) — the importer must detect/normalize per the
  monster's era, or prefer one source as canonical.

### 3.4 Other / rejected
- **`wakfu.wiki.gg`** (the newer wiki): has a `Category:Monster` but the API category query came back
  empty and there's no consistent monster-stat template yet — not usable today.
- **Vertylo/wakassets** (already used for item icons): assets + CDN-derived JSON; **no monster stats**.
- **eight04/wakfu-autobuild**, **pgossa/wakfu-stuffer**: other autobuilders — maximize a damage factor
  but **carry no boss/monster dataset** (good prior art, not a data source).
- **No existing build tool (WakForge, Zenith) has boss/enemy targeting** — boss mode would be novel.

### 3.5 How we'd ingest (mirrors the existing items/runes pipeline)
The app already bakes data at build time (`equipments-vX.json`, `runes-vX.json`) and loads it lazily
from the classpath — boss data should follow the same pattern:

1. **Phase 1 — curated seed:** hand-author `monsters-vX.json` (~20–40 marquee dungeon end-bosses)
   from the wiki / MethodWakfu / in-game. Highest quality, zero scraping risk, ships immediately.
2. **Phase 3 — automation:** a small extractor (new `monsters-extractor` module, or a task in the
   existing `equipments-extractor`) pulls the **Fandom API** first (clean), falls back to/augments
   with a **MethodWakfu HTML scrape** for endgame coverage, normalizes resistance scale (G3), and
   writes `monsters-vX.json`. Add a verification/diff step (counts, sane ranges) like a data bump.

---

## 4. Proposed design

### 4.1 Domain (`common-lib`)
Per existing convention (serializable DTOs live in `common-lib`, not `gui-compose`):

```kotlin
@Serializable
data class Monster(
    val id: Int,
    val name: I18nText,                       // fr/en/es/pt, like Equipment.name
    val level: Int,
    val hp: Int,
    val family: String? = null,
    // Stored as FLAT resistance per element (source of truth); % derived at mapping time.
    val resistances: Map<SpellElement, Int>,  // fire/water/earth/air flat res
    // Optional, display-only (G5): val barrier: Int = 0, val armorPercent: Int = 0,
)
```

Baked resource `autobuilder/src/main/resources/monsters-v<VERSION>.json`, loaded lazily in
`WakfuBestBuildFinderAlgorithm` alongside `equipments`/`runes`.

> `SpellElement` currently lives in `autobuilder/.../domain`. For `Monster` to reference it from
> `common-lib`, either move `SpellElement` (and the flat→% helper) into `common-lib`, or key
> `resistances` by a `common-lib` element enum. Minor refactor, no behavior change.

### 4.2 Boss → `DamageScenario` mapping (new, thin)

```kotlin
fun DamageScenario.forBoss(monster: Monster, element: SpellElement) = copy(
    element = element,
    targetResistancePercent = flatResToPercent(monster.resistances[element] ?: 0),  // G3; may be <0 (G2)
)
fun flatResToPercent(flat: Int): Int =
    floor((1 - 0.8.pow(flat / 100.0)) * 100).toInt().coerceAtMost(90)   // allow negative (weakness)
```

**Element choice:**
- `element = AUTO` → run the search once per candidate element (4, or class-restricted via G8) and keep
  the highest expected damage. Independent solves → run in parallel.
- `element = <fixed>` → single solve, current behavior.

### 4.3 Input surface

**CLI** (`autobuilder`):
- `--boss "<nom FR>"` — selects a boss by French name (consistent with `--forced-items`/`--excluded-items`
  matching). Implies `--computation-mode max-damage` and auto-fills `targetResistancePercent`.
- `--boss-element auto|fire|water|earth|air` (default `auto`).
- Existing `--scenario-orientation/-range/-berserk/-healing/--crit-cap/--base-damage` remain as
  user inputs/overrides; `--enemy-resistance` becomes an override of the boss-derived value.
- New optional `--resistance-debuff <n>` (subtract flat res, stand-in for in-fight debuffs, G9).

**GUI** (`gui-compose`):
- A **Boss** entry point — cleanest as a sub-toggle inside the existing max-damage mode ("Custom
  scenario" ↔ "Boss"), reusing `DamageScenarioCard`.
- **Boss picker card:** searchable dropdown (name/level/family filter) over the catalog. On select:
  resistance profile shows **read-only** (4 elements, flat + %), with the chosen/auto attack element
  highlighted; orientation / range / berserk / heal / crit-cap stay user-editable.
- **Element control:** Auto vs explicit element segmented row.
- **Stats panel additions:** boss resistance profile, the **attack element actually chosen**, and a
  **"hits / turns to kill"** figure from `Monster.hp` and the build's `E[dmg]` (G4). Clear caveat
  badge that single-target/area and boss mechanics aren't modeled (G6/G9).
- i18n: new `Tr.BOSS_*` keys (EN/FR), per the hand-written `Tr` enum.

### 4.4 Where the orchestration lives
Auto-element = N searches + pick-best. Keep it **out of the CP-SAT model**: a small coordinator (CLI
in `Main.kt`, GUI in `BuildSearchModel`) launches the per-element searches and selects the winner. The
GUI already runs searches off-thread and collects a `Flow`; the coordinator runs N of those and keeps
the max (progress = aggregate).

---

## 5. Phased plan

- **Phase 0 — research/design** *(this doc)*. ✅
- **Phase 1 — domain + curated data + CLI.**
  `Monster` model (`common-lib`); curated `monsters-vX.json` (~20–40 marquee bosses); `forBoss`
  mapping + `flatResToPercent`; **engine touch-ups G2 (allow negative resistance) and G3 (conversion)**;
  CLI `--boss` / `--boss-element` (fixed element; auto can land here or 1.5). Tests: mapping +
  resistance conversion (use deterministic `SolverTuning` per the engine-test-determinism note).
- **Phase 1.5 — auto-element.** Coordinator that solves per element and keeps the best (CLI first).
- **Phase 2 — GUI boss mode.** Boss picker, read-only resistance profile, element control,
  "hits-to-kill" display, i18n, caveat badges.
- **Phase 3 — data automation.** `monsters-extractor` (Fandom API → MethodWakfu scrape fallback),
  scale normalization, verification/diff; bump alongside the Wakfu data version.
- **Phase 4 — depth (stretch).** Class→element restriction (G8), Light/Stasis (G7), barrier/armor in
  the displayed number (G5), manual resistance-debuff slider (G9), and — only if a data source
  emerges — single-target/area mastery (G6).

---

## 6. Open questions for the maintainer
1. **Element policy:** auto-pick the best element by default, or always require the user to choose
   (matches "build for *my* element")? (Leaning: `auto` default, overridable.)
2. **Canonical data source** once automated: Fandom (clean, patchier) vs MethodWakfu (richer, scrape,
   longevity risk) vs curated-only. (Leaning: curated core + Fandom auto-expansion, MethodWakfu to
   fill endgame.)
3. **Scope of "boss":** only dungeon end-bosses, or the full bestiary (~825)? Bigger catalog = more
   ingestion + bigger UI list.
4. **"Hits-to-kill" realism:** include barrier/armor (G5) and a debuff slider (G9), or keep the number
   intentionally simple with a caveat?

---

## 7. Sources
- Ankama gamedata CDN config: <https://wakfu.cdn.ankama.com/gamedata/config.json>
- Dev forum, exposed JSON types / monster data withheld:
  <https://www.wakfu.com/en/forum/332-development/236779-json-data> ·
  <https://www.wakfu.com/en/forum/332-development/240723-can-i-find-latest-json-data>
- corvo-astral (mirrors the full CDN; no monster file): <https://github.com/Markkop/corvo-astral>
- Official bestiary (anti-bot, no resistances): <https://www.wakfu.com/en/mmorpg/encyclopedia/monsters>
- MethodWakfu Reborn bestiary (flat+% resist, HP, level; scrape): <https://db.methodwakfu.com/bestiary>
  (example entry: <https://db.methodwakfu.com/bestiary/5165>)
- Wakfu Fandom wiki `MonsterCard` (MediaWiki API, structured fields):
  <https://wakfu.fandom.com/wiki/Gobball_(Monster)> ·
  API: `https://wakfu.fandom.com/api.php?action=parse&page=Gobball_(Monster)&prop=wikitext&format=json`
- Damage formula references (already in `docs/ENCHANTMENTS_PLAN.md` §8):
  <https://wakfu.wiki.gg/wiki/Damage> · <https://sites.google.com/view/wakfucalc/en/guides/formulas>
- Resistance % formula (matches WakForge `calcElemResistancePercentage`): `Res% = floor((1 − 0.8^(flat/100))×100)`, cap 90.
- Prior-art autobuilders (no boss data): <https://github.com/eight04/wakfu-autobuild> ·
  <https://github.com/pgossa/wakfu-stuffer> · WakForge <https://wakforge.org/> · Zenith <https://www.zenithwakfu.com/>
</content>
