# Spell damage anchor investigation — 2026-10-08

**Outcome: documented exception; no source switched.** A general structural selector does not reproduce
all the committed formulas. More decisively, one previously matched normal formula (Obliteration,
6476) is absent from the installed client's reachable damage effects. The brief's all-spells reproduction
gate therefore fails. `SpellDamageScalingBuilder`, `spells.json`, `spell-damage.json`, engine inputs,
`CERTIFIER_VERSION` and `ENGINE_RESULTS_VERSION` are unchanged. No push or PR is part of this work.
This establishes a limit of direct effect selection on this snapshot, not that reproducing Ankama's
whole tooltip/runtime renderer is impossible forever.

## Oracle and structural decode

The actual oracle has **286 scalings: 263 `matched=true`, 23 fallback**, rather than the older
248/41 estimate in the audit/brief. `matched` describes the **normal** hit only. A critical formula
can still be an anchored approximation on a matched spell. All 285 present client records have
`max_level=245`, agreeing with their oracle cap; 5123 Bloody Blade is absent.

The opt-in probe uses the repository's JVM decoder and the local `/Applications/Ankama/Wakfu` install:

- The table-type enum is found by ITEM/SPELL/STATE/STATIC_EFFECT/MONSTER constant-name anchors and
  the explicit STATIC_EFFECT constructor id, 68. No obfuscated name is a discovery key.
- `SchemaGenerator` independently derives StaticEffect's complete **57-field** bytecode layout;
  every field type agrees with `Tables.STATIC_EFFECT_SCHEMA`, or the probe fails.
- Every Spell (4,143), StaticEffect (178,344), and State (6,129) record passes both the indexed byte-size
  guard and leading-id equality. Spell/State retain the existing positional schemas, not a claim of
  new bytecode-derived semantics for those tables.
- The graph starts at Spell `effect_ids` in table order, walks global `parent_id` children in record
  order, and deduplicates cycles, exactly as the current builder. Depth, order and every ancestor id
  are retained. Damage candidates use 1 PHYSICAL, 2 FIRE, 3 EARTH, 4 WATER, 5 AIR, 917 STASIS,
  1083 LIGHT, with at least two params. 917 is included for research; production still excludes it.
- There are **1,381 candidates** (693 normal, 688 critical) among 8,943 reachable effects. All 57
  StaticEffect fields are dumped, including fields the production selector ignores. Floats are
  promoted to exact doubles before serialization: `0.23999999463558197` must not become `0.24`.

The current numeric-parent walker also reaches 34 STATE, 5 ITEM_EQUIP and 2 ITEM_USE rows alongside
8,902 SPELL rows; parent ids are not globally typed keys. Two damage candidates are ITEM_USE rows.
These are preserved to measure the existing builder's graph faithfully, not silently filtered out.
A future traversal redesign must review namespaces separately.

## Selection signals and exact agreement

Each selector uses only client order/metadata/formulas. It does **not** use the encyclopedia's damage
number or damage element. Normal and critical are selected independently (`critical_state.trim()`);
all ties retain traversal order. A row is exact only if its params reproduce **base/inc**, with the
same cap; “both” also requires **critBase/critInc**. Equality at level 245 alone is insufficient.
The following denominators are the **263 previously matched spells**, not all 286 scalings.

| Client-only selection rule | Normal exact | Normal mismatch | Both exact | Either mismatch |
|---|---:|---:|---:|---:|
| First damage candidate | 239 | 24 | 209 | 54 |
| First candidate displayed in spell description | 240 | 23 | 210 | 53 |
| First candidate without a criterion | 152 | 111 | 132 | 131 |
| First displayed candidate without a criterion | 151 | 112 | 132 | 131 |
| Shallowest candidate | 229 | 34 | 202 | 61 |
| Smallest evaluated hit at the cap | 220 | 43 | 192 | 71 |
| Largest evaluated hit at the cap | 198 | 65 | 174 | 89 |
| Prefer the action corresponding to the spellbook branch, then first | 240 | 23 | 210 | 53 |

Branch prioritization is just a rejected experiment: **field 30 is the spellbook branch, not damage
element**. Light Arrow remains WATER-branch/LIGHT-damage. See the
[metadata investigation](../official-sources-2/spell-metadata-comparison.md).

No inspected field acts as a universal primary-hit flag. In this candidate set `_31=0`, level bands
are uniformly 0..32767, delay/duration are zero, probability is uniformly 100 + 0×level, and the six
main trigger arrays are empty. Those cannot select a base hit. Group ancestors can still carry
conditions, resource computations or triggers; an empty **leaf** criterion does not make the hit
unconditional. Visibility, area/target flags and effect properties vary, but counterexamples defeat
visibility or unconditionality as general rules:

- **630 Anatomy:** the oracle is the conditional `target HP > 80` variant (5 + 0.6×level), while an
  unconditional 3 + 0.36×level is also displayed. “No criterion” selects the wrong hit.
- **935 Earthquake:** the displayed candidate is [0,1]; the oracle [2,0.24] is a **hidden** child,
  next to hidden higher tiers. Display is not a primary-hit marker.
- **2044 Three Cards:** the matching [6,0.66] displayed row has criterion **`False`**; the first
  hidden candidate is [0,1]. Excluding non-executing descriptive rows loses the oracle.
- **7067 Fracture:** the only normal damage effect is hidden. A displayed-only selector finds none.

### Every normal mismatch of the displayed selector

The complete [displayed-mismatches.csv](evidence/displayed-mismatches.csv) records **76 failing hit
rows across 53 spells**: 23 normal and 53 critical, including selected pairs, matching effect ids and
an explicit reason for each. Thirty critical failures are existing anchored approximations with no
exact critical effect; Obliteration's old critical formula is absent too. The remaining 22 critical
failures select a different displayed variant or lose a hidden effect.

| Spell | Why the first displayed normal effect differs |
|---|---|
| 925 Bramble | First is a WATER descendant [0,0.37]; oracle is later EARTH [3,0.36] |
| 935 Earthquake | Displayed [0,1]; oracle [2,0.24] is hidden among six tiers |
| 2017 Refinement | First requires state 8552 and uses [8.75,1.05]; later [7,0.84] matches |
| 4594 Forceful Blow | First [2,0.28]; later [4,0.56] has a target-state condition |
| 4692 Pulsation | Portal-nearby [2,0.24] precedes the no-portal [3,0.36] variant |
| 4701 Unleashed Blade | Portal variant [7,0.98] precedes no-portal [5,0.70] |
| 4703 Light My Fire | [3,0.30] and [3,0.39] precede the enemy variant [3,0.33] |
| 4710 Milk Wave | [10.5,1.47] precedes [7,0.98]; both leaves say IsEnemy, ancestors differ |
| 4777 Super Iop Punch | [0,1] precedes state/deck-gated [4,0.56] variants |
| 4778 Celestial Sword | HP >= 65 variant [2.5,0.325] precedes [2,0.26] |
| 4781 Rocknoceros | [0,1] precedes state/deck-gated [4,0.48] variants |
| 4788 Pounding | [0,1] precedes state/deck-gated [6,0.78] variants |
| 4809 Storm Arrow | [4,0.48] precedes [3,0.36], with equal leaf criteria but different ancestors |
| 4814 Destructive Arrow | [10,1.40] precedes [9,1.17], with equal leaf criteria but different ancestors |
| 4819 Explosive Arrow | State/Sharpening-enhanced [7,0.77] precedes default [5,0.55] |
| 4822 Burning Arrow | Multiple target/state variants precede default [2,0.24] |
| 5036 Smashes | Armor-dependent [4,0.48] precedes nested [5,0.70] |
| 6258 Rupture | [4,0.56] precedes [2,0.28], both with empty leaf criteria |
| 6463 Barbed Fire | [2,0.24] precedes several variants, including matching [4,0.48] |
| 6476 Obliteration | **No current candidate has the old [4,0.44] formula** |
| 7067 Fracture | Oracle [3,0.36] is hidden; no displayed normal candidate |
| 7317 Whip | Summon-enhanced [8,1.08] precedes [4,0.54] |
| 7321 Feather Tornado | [3,0.36] precedes [3,0.33], with equal leaf criteria |

Pairs in prose are rounded for readability; machine-readable evidence keeps their exact float values.
The first-candidate rule has one additional normal failure, **2014 Cutting**: hidden summon-enhanced
[12,1.64] precedes displayed [3,0.42]. It succeeds on Fracture, so its failure set differs from the
visibility rule. The [comparison.csv](evidence/comparison.csv) lists every spell under every rule.

### Hard blocker: the missing oracle formula

For **262/263** matched normal spells, some current candidate still carries the exact oracle pair.
For **6476 Obliteration**, none does. Oracle normal [4,0.4399999976158142] and critical
[5,0.550000011920929] are absent; current normal variants include [4,0.5199999809265137],
[3,0.36000001430511475] and [0,1]. Selecting any of them changes damage. This is a snapshot mismatch;
we do not declare the client or encyclopedia “wrong” without a reviewed gameplay/data change.

Even the **existing anchored builder** reproduces only **285/286 complete oracle records** when rerun
against this install: it changes Obliteration to `matched=false`, normal [0,111/245], critical
[0,139/245]. These still reproduce 111/139 at level 245, so the old max-level-only test passes.
At level 20 the old oracle gives **12/16**, while the new fallback would give **9/11**. This demonstrates
why both exact formula reproduction and the immutable oracle are required; no regeneration was accepted.

## Fallback inspection

The probe additionally walks **33 constant state ids** referenced by action 304 in fallback graphs.
This is evidence only: it does not infer execution count, state level, probability, resource amount or
which contextual variant is the encyclopedia's representative hit.

The inherited “DoT / random / weapon-%” labels are not an exhaustive classification of this snapshot.
All 1,381 direct candidates have 100% probability; 1,379 have two params, two have six. No general
random-range/midpoint or weapon-percent fallback rule was established. Several actual failures are
indirect states, rebounds, passive descriptive rows or resource-dependent damage. It would be unjustified
to assign a midpoint or multiply by a guessed tick count to make these fit.

| Fallback spell | Client evidence and why no single safe replacement was adopted |
|---|---|
| 763 Clock | No direct damage action in the current subtree; groups and runtime state/resource logic |
| 783 Sandglass | Applies state 6745 with normal level formula [4,0.48], but direct descendants use [5,0.60]; a state graph has [0,1] and event triggers |
| 919 Poisoned Wind | Applies state 3590 with [1,0.12] normal / [1.25,0.15] critical; state damage [0,1] is event-triggered, not a fixed tick sum; oracle crit equals normal |
| 933 Explodoll | Passive; action-400 descriptive params [1,0.11,...] reproduce max-level 27, but are not a direct damage action |
| 2061 Felintuition | Glyph and state 8002; state Light [3,0.30] evaluates to 76 at 245, not oracle 74; area/runtime scaling must be understood |
| 2067 Fleahopper | Rebounds/state applications and several action-400 rows; one pair [4,0.44] yields 111, but choosing it changes the old linear slope below cap |
| 4713 Six Roses | Resource/group calculations, [0,1] and [5.2,0.624] current leaves; none matches oracle 121 |
| 4715 Lactic Acid | Carrying/group variants, [0,1] and [5.2,0.572] current leaves; none matches oracle 111 |
| 4792 Stance | Last-spell/state-dependent action 1013; descriptive action-400 [3,0.33] yields 83, not a fixed direct-hit rule |
| 4815 Piercing Arrow | Rebounds/state 7810; action-400 rows expose several damage pairs for different rebounds; first-hit selection requires renderer semantics |
| 5041 Motion Sickness | State 4801 level [1,0.10] yields 25; movement-triggered state [0,1], alongside direct variants that do not match 25 |
| 5122 Ambush | Passive descriptive action-400 [3,0.36] yields 91; adopting that formula changes the existing anchored approximation |
| 5123 Bloody Blade | No Spell record or effect graph in this client |
| 5592 Glistening Halo | State 4120 and script/group effects; state Light [0,1] requires contextual state magnitude/trigger behavior |
| 6279 Sniff | Client passive (despite retained scraped ACTIVE category); action 400 has no numeric params; oracle 2 cannot be inferred |
| 6282 Wise Strength | Client passive; event-triggered actions 1020/430/999, no direct hit; oracle 0 is not permission to invent a formula |
| 6464 Sticky Bomb | Bomb/state/script effects (1013960/1013332/1013333), delayed/grouped [0,1] leaves; no fixed replacement for 111/139 |
| 6906 Scuttle | Group calculations ending in Fire/Stasis [0,1], turret conditions; no matching fixed formula for 164/205 |
| 6916 Shebang | Action-913 SP ramp and conditional Fire/Stasis variants; representative hit depends on Stasis Points; no single leaf matches 211/264 |
| 6937 Activation | Passive action-400 row with no params; actual effect depends on turret element |
| 6940 Hot Wheels | Passive state 7688; descriptive [4,0.48] yields 121; the state dispatches Light/Stasis [0,1], with rail/event conditions |
| 7187 Violent Omens | Passive area/trigger effects (39/999), no direct damage action or scalar 32 in the graph |
| 7195 Shriveling | Passive AP-removal/distance-triggered group effects, no direct fixed formula for 6 |

A matching descriptive/state pair can recover a max-level number in some cases; it is not exact
reproduction of the **existing** base/inc pair and does not establish correct runtime semantics.
All 23 existing `matched=false` scalings stay unchanged. No new guessed number is written.

## Light / Stasis bonus

There are **21 LIGHT anchors: 11 matched, 10 fallback**. The first normal damage candidate reproduces
all 11 matched normal Light formulas exactly. This includes the Light Arrow counterexample to branch
selection; it is not a solution for the ten indirect/passive/absent fallback rows or all critical hits.
There are **zero STASIS anchors** to validate, despite 72 Stasis candidate effects in conditional
Foggernaut graphs. The previously established highest-element-mastery rules remain documented in the
metadata report; this investigation does not change element modelling or the engine.

## Answers to the audit questions

1. **A base-hit flag?** No universal discriminator was found among the 57 fields, the hierarchy,
   visibility/criteria, level bands or ordering. The concrete counterexamples above reject the proposed
   selectors. Unknown property meanings/renderer conventions remain possible future research, not
   evidence of a working selector.
2. **Does evaluating params at max_level guarantee the anchor?** Some pair reproduces it for 262
   of 263 previously matched normal spells in this install. The missing Obliteration pair prevents
   a universal guarantee. “Matched” never guaranteed the critical pair, and cap-only agreement does
   not guarantee the low-level slope.
3. **Transforms beyond floor(base+inc×level)?** The inspected graphs contain group/resource ramps,
   state applications, descriptive non-damage rows and script references; many leaves are [0,1].
   A two-param evaluator over a chosen leaf cannot reproduce these contexts. Full renderer semantics
   and their rounding/order were **not** reverse-engineered in this time-box; no universal extra
   element/area multiplier is asserted from these observations.
4. **Sum ticks / midpoint random bounds?** No validated generic rule. State triggers and rebounds
   do not provide a context-free execution count, and the inspected candidate probabilities do not
   identify a random midpoint. Preserve `matched=false` rather than guess. Some descriptive max-level
   numbers are recoverable, but a reviewed slope/source change would still be required.

The audit entry is retained as a documented exception. A follow-up needs a separately reviewed oracle
update for Obliteration and richer renderer/state/resource semantics, then exact reproduction guards
on **normal and critical formulas**, not only max-level hits. Damage changes require the certificate
and engine-results version review excluded by this brief.

## Reproduce and inspect

Only the `-I` invocation adds the research Kotlin source to the extractor's test source set. Normal
builds/CI do not compile or run this probe; no production Gradle configuration or source is changed.
Run from the `codex/spell-damage-client` worktree. Use the shared `gradle-locked.sh` path from the task
brief on this machine; `--heavy` reserves both slots for the bytecode scan.

```sh
# Replace <gate> with the brief's absolute gradle-locked.sh path.
<gate> --heavy -I docs/spell-damage-client/probe.init.gradle \
  :bdata-extractor:test --tests '*SpellDamageClientProbe'
python3 docs/spell-damage-client/analyze.py --check
# To inspect a changed snapshot, use --out /tmp/new-spell-damage-evidence (never replace the oracle).
```

The probe writes full raw spell/state graphs and fingerprints **only under the module's ignored build
folder**. `analyze.py` uses the oracle only for offline comparison. Its selectors use client data alone.
The committed evidence provides:

- [summary.json](evidence/summary.json): exact agreement counts for all eight rules.
- [comparison.csv](evidence/comparison.csv): every spell, cap, available oracle pair and rule outcome.
- [displayed-mismatches.csv](evidence/displayed-mismatches.csv): all failed hit rows and their reasons.
- [candidates.csv](evidence/candidates.csv): every candidate, order/depth/path, action, params, crit state
  and cap value; [graphs.csv](evidence/graphs.csv) also records roots and the complete traversal order.
- The raw dump of all 8,943 unique reachable effects (ancestors and non-damage rows, all 57 fields) is
  **not committed** (~2 MB). Regenerate it with `python3 docs/spell-damage-client/analyze.py --raw --out <dir>`
  after running the probe: it writes `effect-metadata.jsonl` (one compact row per effect) and
  `effect-defaults.json` (merge `defaults | row` to reconstruct every field losslessly).
- [fingerprints.txt](evidence/fingerprints.txt): SHA-256 of the client jar, all three source tables and
  the two committed spell oracles. This install matches the metadata follow-up's client/66/68 fingerprints.

## Validation

The initial scratch full-table JSON dump exceeded the default test heap; streaming one row at a time
resolved it. The retained probe emits only reachable graphs and successfully runs with the ordinary
test heap under the shared gate. This was a tooling failure, not an accepted data discrepancy.

After `git fetch origin && git rebase origin/main` (already up to date at `a2f4196b`), all validation
ran through the shared gate:

- Root `ktlintFormat`: passed, with no production file diff.
- Complete `:bdata-extractor:test`: **34 tests, zero failures/errors/skips**, including local-client
  damage, metadata and other reproduction tests.
- `:autobuilder:test --tests '*SpellCatalogTest' --tests '*SpellRotationTest' --tests '*PassiveCatalogTest'`:
  **32 tests, zero failures/errors/skips**.
- Root `ktlintCheck` with the probe init script: passed, also linting the research Kotlin source.
- Opt-in `SpellDamageClientProbe`: passed after the rebase, all schema/record guards executed.
- `python3 docs/spell-damage-client/analyze.py --check`: every evidence artifact reproduced byte for byte.
- `git diff --check`: passed; only AGENTS/docs/research evidence changed. Both production spell resources
  retain the exact SHA-256 fingerprints in the report.

No game-data regeneration, force-write, engine/certificate change, push or PR was performed.
