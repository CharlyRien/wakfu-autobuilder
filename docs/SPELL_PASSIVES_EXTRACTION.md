# Class-Passives Extraction — Handoff

> **✅ Shipped.** Implemented (`bdata-extractor` → `PassiveCatalog`, Lot 4). Resource files now use FIXED names (PR #167). Kept as the extraction howto.

Companion to `SPELL_CAST_LIMITS_EXTRACTION.md`. Same toolchain (`jac3km4/wakfu-bdata`), same install
(`/Applications/Ankama/Wakfu`, gamedata `1.91.1.54`). Produced on 2026-06-15.

## 1. Deliverable

`autobuilder/src/main/resources/spell-passives.json` — a JSON array, one entry per **player
class passive** (332 entries, breeds `{1–16,18,19}`, `passive==2`), sorted by `breedId` then `spellId`.
Offline baked artifact, parallel to `equipments.json` / `spells.json` / `spell-cast-limits.json`.
**Not wired into Kotlin.**

Per-entry shape:
```jsonc
{
  "spellId": 6989, "name": "Ligne", "breedId": 1, "class": "FECA",
  "description": "Initially, the Range of Fire spells is not variable…",
  "effectIds": [299772],                  // raw top-level effect ids from the spell record (table 66)
  "declaredEffects": [                     // resolvable declarative effects in the passive's OWN effect tree
    { "actionId": 160, "kind": "stat", "characteristic": "RANGE",
      "base": 1, "inc": 0, "conditional": false, "criterion": null,
      "durationBase": -1, "permanent": true }
  ],
  "flatBuildStats": { "RANGE": 1 },        // CONSERVATIVE rollup: permanent + unconditional + flat + positive stat gains
  "appliedStateIds": [],                   // states this passive applies (action 304) — NOT resolved (see §5)
  "hasConditionalEffects": false, "hasStateEffects": false,
  "hasScriptedEffects": false, "hasTriggeredEffects": false,
  "fullyDeclarative": true                 // true ⇒ flatBuildStats fully represents the passive (no state/script/trigger/condition)
}
```

## 2. Pipeline

1. **Catalogue.** Player passives are the spell-table (66) records with `passive == 2` and a player
   `breed_id`. The binary flag is **more reliable than the encyclopedia scraper's heuristic** (`spells.json`
   classifies "no AP cost" as PASSIVE → 308; the binary flag → 332). `name` (FR) and `description` are
   joined from `spells.json` by `spellId` (binary id == encyclopedia id, proven in the
   cast-limits work).
2. **Effects.** Each passive record carries `effect_ids` into the **StaticEffect table (TYPE_ID 68)**,
   dumped the same way (`dump_doc::<StaticEffect>` in `main.rs`; 173 805 records). Each `StaticEffect`
   gives `action_id`, `params: f32[]`, `effect_criterion`, `duration_base`, `ends_at_end_of_turn`, and a
   `parent_id` (effects form a tree; children are parented to their parent effect's `effect_id`).
3. **action_id → Characteristic.** The CDN `actions.json` (71 actions, downloaded from
   `wakfu.cdn.ankama.com/gamedata/1.91.1.54/actions.json`) carries a `[#charac CODE]` token in each
   action's description (e.g. action 41 → `MP`, 80 → `RES_IN_PERCENT`, 122 → `DMG_FIRE_PERCENT`). Those
   codes map 1:1 to the project's `Characteristic` enum. "Perte"/"Deboost" actions are negative; "Gain"/
   "Boost" positive. Element-count masteries (1068/1069) use `params[2]` for the N-random-element variant.
   Value = `params[0]` (flat base) `+ params[1] * level` (per-level/per-stack increment — **ambiguous**, see §5).
4. **Traversal — strict.** A passive's own effects = its `effect_ids` **plus their `parent_id`
   descendants only**. We deliberately do **not** follow the `triggers_*` lists, nor "all effects whose
   `parent_id == spellId`". (Both over-collect: a passive's effect tree references shared combat-proc and
   same-id *state* effects that are not the passive's permanent bonus — see §4.)

## 3. The permanence discriminator (the crux)

A declarative stat effect is a **permanent build stat** iff `duration_base == -1` **and**
`ends_at_end_of_turn == false`. Finite-duration effects (`duration_base >= 0`) are transient buffs the
passive applies under some trigger/condition, not standing stats. This single field cleanly separates them:

| Passive | effect | `duration_base` | verdict |
|---|---|---|---|
| Serene Conquest | Block +50 | `-1` | permanent ✓ (in `flatBuildStats`) |
| Cunning Fang | Block +20 | `-1` | permanent ✓ |
| Mineral Protection | Res +100 | `1` (`ends_eot`) | transient ✗ (only `declaredEffects`, `permanent:false`) |
| Pillar | Res +120/90/60/30 | `1` | transient ✗ |
| Murderer | WP +1 | `0` (on-kill) | transient ✗ |

`flatBuildStats` = stat effects that are **permanent + unconditional (`criterion` empty) + flat
(`inc == 0`) + positive**. Everything else (transient, conditional, scaling, negative) stays in
`declaredEffects` with its flags, never in the rollup.

## 4. Validation

- **flatBuildStats (18 passives): all description-consistent.** Every rollup matches its encyclopedia
  text — e.g. Crobak Vision "gains range" → `RANGE 2`; Animal Sacrifice / Memory / Canine Energy "gains
  max WP" → `WAKFU_POINT 3/6/3`; the "Force of Will" passives (Untouchable Scout, Great Chain of Being,
  Relentless, Quadramental Absorption) → `WILLPOWER 20`; Cunning Fang / Serene Conquest → `BLOCK_PERCENTAGE
  20/50`; Masked Gaze / Tailing → `MOVEMENT_POINT 1`. The 3 armor passives recovered via the action-39/40
  fix below also match — Compréhension du Wakfu "improves the Armors a Feca gives" → `GIVEN_/RECEIVED_ARMOR_PERCENTAGE
  100`, Armure unique → `RECEIVED_ARMOR_PERCENTAGE 100`, Pillage → `RECEIVED_ARMOR_PERCENTAGE 30`.
- **Actions 39/40 (`Gain`/`Perte : charac passée en paramètre`)** select their characteristic via
  `params[4]` (an Ankama charac id), not a `[#charac]` token. They are resolved through
  `ActionCatalog.CHARAC_ID` (120→given armor, 121→received armor; class-resource ids like 101/117/123 stay
  `characteristic: null` — visible in `declaredEffects` but never invented into `flatBuildStats`). Before
  this they were silently dropped, which is why the 3 armor rollups above were missing.
- **Exact-value oracle (wiki.gg, renders the in-game tooltip):** Serene Conquest → **"50% Block"** =
  `BLOCK_PERCENTAGE 50` exactly (and its "-2 max SP" = the negative WP effect, correctly kept out of the
  rollup). Magnitudes elsewhere are all sane (WP 3–6, Block 20–50, Range 1–2, Willpower 10–20).
- **Before the duration filter** the rollup had 30 entries incl. obvious false positives (Mineral
  Protection Res 100 "for 1 turn", Pillar Res 300 "when adjacent", Murderer WP "on kill"). The
  `duration_base == -1` rule removed exactly those. This is why the filter exists.

## 5. Coverage & limitations (read before using)

Of 332 passives: **18** have permanent `flatBuildStats`; **91** have ≥1 resolvable `declaredEffect`
(stat/damage); **241 are pure script/state** (no declarative effect at all). This reflects Wakfu reality:
**most class passives are mechanic/conditional, not stat sticks.** Specifically:

- **`hasStateEffects` (90 passives)** — apply a state (action 304); the state's own stats live under the
  *state id* in table 68 and are **not resolved here** (`appliedStateIds` lists them). Some permanent
  stats granted via a permanently-applied state are therefore **not** in `flatBuildStats` (honest
  undercount, flagged — never a wrong value). A *conditionally*-applied state sets `hasConditionalEffects`.
- **`hasTriggeredEffects` (177)** — have reactive procs (on-hit/on-kill/turn-start…). Not expanded
  (expanding them over-collects, §4). **Most passive damage/mastery bonuses are conditional and live
  here** → 0 passives expose a *permanent flat mastery*; the direct damage procs are captured as
  `kind:"damage"` in `declaredEffects` (conditional).
- **`hasScriptedEffects` (145)** — use combat-script action ids absent from `actions.json`; opaque (no
  declarative meaning to extract).
- **`inc` / value scaling** — `params[1]` is a per-level *or* per-state-stack increment (ambiguous: e.g.
  "The Way of the Bow" dodge `[0,500]` cannot be per-character-level). `flatBuildStats` therefore includes
  only `inc == 0` effects; scaling permanent effects are in `declaredEffects` with `inc` recorded but
  excluded from the flat rollup.
- **Effect target (self vs enemy)** is not resolved; the rollup only takes positive "Gain/Boost" actions,
  which on passives are self-buffs in practice, but verify before trusting negatives in `declaredEffects`.

**Bottom line:** `flatBuildStats` is a small, validated, safe-to-integrate set of permanent passive stats.
`declaredEffects` + flags is the faithful, auditable layer. Resolving state-borne and conditional/triggered
bonuses (incl. most passive damage modifiers) reliably needs Wakfu's trigger/state model — a deeper
follow-up, deliberately not attempted here to avoid the over-collection errors documented in §4.

## 6. Reproduce

**Canonical (in-repo, pure-JVM):** `./gradlew :bdata-extractor:run`. The `bdata-extractor` Kotlin module
decodes Spell (66) + StaticEffect (68) from the local install, fetches `actions.json`, applies exactly
the pipeline above (player-breed filter, strict `effect_ids`+`parent_id` walk, `action_id→Characteristic`
via the `[#charac]` tokens, `duration_base==-1 && !ends_at_end_of_turn` permanence), and **regenerates
this artifact** — self-verifying against the committed JSON (semantically identical, 332 entries) and via
a per-record size guard. Logic lives in `bdata-extractor/src/main/kotlin/.../{BinaryDecoder,Tables,ActionCatalog,Extract,Main}.kt`.

The original bootstrap (no longer needed) used the `jac3km4/wakfu-bdata` Rust crate:
```sh
# main.rs dumped Spell (66) + StaticEffect (68); a Python post-processor produced the first artifact.
cargo run --release --bin main -- /Applications/Ankama/Wakfu
curl -O https://wakfu.cdn.ankama.com/gamedata/1.91.1.54/actions.json
```
