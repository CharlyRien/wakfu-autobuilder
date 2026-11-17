# Sublimation trigger timing — client audit (2026-11-17)

Follow-up to "Dénouement before Neutralité" (`fix/denouement-before-neutrality`). Question: does the engine miss other
"effect lands before / after a condition is tested" cases? Read-only audit of the local client.

- **Client:** `F:\Wakfu`, `sentry.properties` `release=1.93.1+81`. **`WakfuData.VERSION` = `1.93.1.62`** (the CDN
  `gamedata/config.json` still serves `1.93.1.62`). The build numbers differ, but `bdata-extractor --sublimations-only`
  reproduces the committed `sublimations.json` **exactly** (236 entries, `testedAt` included). All 35 `bdata-extractor`
  tests pass with `WAKFU_INSTALL=F:/Wakfu`, none skipped. Nothing was bumped or regenerated.
- **Sources:** State (67) → StaticEffect (68) via the extractor's own dump (`WAKFU_SUB_TIMING_DEBUG=all`). Tooltips:
  i18n `content.33.<effectId>`. Passives: `spell-passives.json` (Spell 66). Bytecode: `lib/wakfu-client.jar`, scanned
  structurally (constant pool, `sipush`, raw switch keys).

## 1. Triggers

The triggers come from the `triggers_*` lists of the 1,425 StaticEffect records of the 236 sublimations. Ids below 1000 are
execution events: an action id or an element hit. **Ids of 1000 and above are combat moments.**

| id | slot | subs (choosable) | meaning | evidence |
|---|---|---|---|---|
| 1028 | not_related | 75 (Dénouement, Mesure I/II/III, Constance, Inflexibilité I/II, Furie I/II, Volonté de fer, Secret critique, Force herculéenne, Ravage III, Ravage secondaire II, Évasion III, Interception III, Poids plume III, Concentration élémentaire, Anatomie, Abnégation, Connaissance du placement) | **start of combat**, once | tooltips: "Au lancement du combat, si…" (238166, 238187, 309428, 353136); "En début de combat :" (283212, 283405, 352962) |
| 1001 | not_related | 55 (Neutralité III, Ambition III, Prétention III; Carnage III, Influence vitale III with 1002) | **start of turn** | tooltip 317914: "Au début du premier tour, si les maîtrises secondaires sont <= 0" |
| 1002 | not_related | 20 | end of turn (HP ≥ 90 % re-checks paired with 1001) | hypothesis: the pairing with 1001 |
| 1003 | not_related | 25 (Expert des armes légères III) | re-test of a slot / equipment criterion | hypothesis: LWE's `HasWeaponType` criterion |
| 1004, 1005, 1010, 1015, 1016, 1018, 1024, 1025, 1032, 1037, 1048 | various | none choosable | combat events (AP / MP return, critical, end of an effect…) | none read; no modelled sub carries them |
| 2, 1, 5–11, 60–246, 2130–2263 | before_computation / after_execution… | Mesure, Mesure III, Furie I/II, Anatomie, Destruction III, Brûlure… (the per-hit part) | per-hit / per-action events (2 = damage computed; 5–8 = fire / water / air / earth hits; 9 / 11 = the per-element sub guards) | data pattern |

**Order inside one phase:** not found. In the client bytecode no class holds 1001 and 1028 together, whether as a constant,
a `sipush` or a switch key. Only `ffJ` defines `1000–1003` (four bare `int` constants, never referenced). The fight logic
that orders same-trigger effects is not in the client jar, so **the order is a hypothesis**:
- 1028 runs before 1001. The tooltips and the in-game Ravage × Neutralité test agree.
- Inside 1028 there are two readings:
  - The engine's own conventions take "condition first" for crit: a start-of-combat +crit does not feed `CRIT_AT_MOST`.
  - For Inflexibilité II they take "effects first": Ravage's masteries break it.

## 2. Pairs (effect × condition reading the same stat)

Engine sheets:
- **pre-combat:** base + items + runes + skills + permanent subs. Tested at 1028.
- **first-turn:** pre-combat + the 1028 lines of FLAT subs + conversions. Tested at 1001, and by Inflexibilité II.
- **out-of-combat:** pre-combat + passives. Read by the item stat gates.

The solver, the scorer and every certificate read the same sheets; they never disagree with each other. Classes:
(i) changes a build or a score on realistic requests, (ii) theoretical or unconfirmed, (iii) no gap.

| # | effect (source, trigger) | condition (trigger) | client | engine | class |
|---|---|---|---|---|---|
| 1 | Dénouement conversion (1028) | Neutralité / Ambition / Prétention / Abandon `each secondary ≤ 0` (1001) | conversion lands first | first-turn sheet minus moved (this branch) | iii (fixed by the branch) |
| 2 | Ravage III masteries (1028) | Neutralité family (1001) | Ravage lands first: breaks it | counted | iii |
| 3 | Ravage III masteries (1028) | Inflexibilité II `each secondary ≤ 0` (1028) | same phase, order unknown | counted (conservative) | ii — if the condition is tested first, the engine forbids a legal combo |
| 4 | Ravage III critical mastery (1028) | Secret critique `crit mastery ≤ 0` (1028) | same phase, order unknown | NOT counted (optimistic) | ii — opposite convention to #3; Secret critique EPIC + Ravage NORMAL can coexist |
| 5 | Ravage secondaire II +crit (1028) | Constance / Mesure II / Mesure III / Dénouement crit (1028) | same phase | not counted | iii (in-game check cited in `SubConditionSpec`) |
| 6 | Ravage secondaire II +block (1028) | Mesure `block ≥ 40` (1028) | same phase | not counted | ii (pessimistic) |
| 7 | Évasion III dodge % of level (1028) | Furie `dodge < level` (1028) | same phase | not counted (optimistic) | ii — at level 245 Évasion gives 300 dodge, and if it lands first Furie is always off |
| 8 | Ambition III +crit (1001) | Dénouement / Mesure II crit (1028) | lands after | not counted | iii (locked by `ConversionBeforeTurnConditionTest`) |
| 9 | Permanent sub lines (no trigger, dur −1: Influence III, Visibilité II, Combat rapproché II, Carapace II, Dérobade continue III, Vivacité II) | every 1028 condition | on the sheet | counted (`appliesBeforeCombat`) | iii |
| 10 | **Class passives' permanent stats** (Spell 66, `duration_base = −1`): Vision du Corbac +2 range, Vision / Exécuteur +2 range, Ligne +1 range; Conquête sereine +50 block, Peau rocheuse +30 block, Crocs futés +20 block | Furie II `range ≥ 4`, Volonté de fer `range ≤ 3`, Mesure `block ≥ 40` (1028) | permanent, shown on the sheet before combat (the engine counts them for item gates for that reason) | **left out** of every sub condition (`preCombatStat` has no passive term; the certifiers assume it) | ii in the measured runs, see §3 |
| 11 | Furie II's own +1 range (1028) | itself | own effect | excluded | iii |
| 12 | Abandon II +1 range (1001) | Furie II / Volonté de fer (1028) | after | not counted | iii (Abandon is not choosable) |
| 13 | Carnage III, Influence vitale III (1001 / 1002, `HP ≥ 90 %`) | the scenario's own HP | off at ≤ 50 % HP | was credited unconditionally, berserk scenario included | **i — fixed** on `fix/hp-gated-sublimations-berserk` (`ScenarioGate.notBerserk`, CERTIFIER_VERSION 64) |
| 14 | Dénouement `crit mastery ≥ 2` | — | `GetCharac("CRITICAL_BONUS") >= 2 and FEROCITY >= 40` (238187) | ≥ 2 not read | ii: at 0 or 1 critical mastery the engine moves 0 / 1 point, the client none; ≤ 1 elemental mastery, no other effect hangs on it |
| 15 | 1028 start-of-combat lines (Abandon range, Furie II range) | item stat gates (out of combat) | the game re-checks gates on the equipped set; whether it does so in combat is unknown | out-of-combat sheet | ii |

No other conversion or stat transfer exists among the 236 subs: Dénouement is the only `CONVERSION`. The 913 ramps
(Poids plume, the Concentration / Anatomie / Abnégation per-element branches) read DI / MP lines that no other condition reads.

## 3. Reproductions (`WakfuBuildSolver.optimize` / `run`, 60 s, no runes, level 200)

- **#13 (class i).** Request: Osamodas 200, max-damage, fire / distance / face, **berserk**, passive Vision du Corbac.
  - Engine build: Ravage III, Carnage III, Vivacité II, Critique Berserk III ×2, Armure lourde II, **Influence vitale III ×2**,
    Mesure III, Brûlure III / I, with Collier des Ombres, Cartes du Monde, Plastron Ploeil, Cape Hilare…; objective 7,828,625.
  - Problem: the berserk scenario assumes ≤ 50 % HP, so Carnage's elemental mastery (45 % of level at tier 3) and Influence
    vitale's crit (+12 per tier) are off in game, yet the engine credits them.
  - Proposed fix: give their effects a "not berserk" scenario gate in the extractor, decoded from the
    `GetTotalHpInPercent >= 90` atom, as a new `ScenarioGate` field, read by solver, scorer and certificates.
  - That is a data regeneration plus a bump of `CERTIFIER_VERSION` and `ENGINE_RESULTS_VERSION`. It is not small, so it is
    not implemented here.
- **#10 (passives).** Same Osamodas request, and Steamer 200 max-damage with Conquête sereine. Each was re-solved with the
  sub's condition removed (= the client's read when the passive meets it).
  - Neither optimum takes Mesure or Furie II: Concentration élémentaire and Mesure III beat them. Steamer: identical objective
    (8,738,660).
  - Osamodas most-masteries with a 20 willpower row: no Volonté de fer either way (8,565 with the passive, 8,533 without).
  - So the gap is real but changed no measured build. It can surface as a wrong score:
    - a Volonté de fer build with 2+ range from items and a +2 range passive is credited 20 willpower the client denies;
    - a Mesure build is left out while Conquête sereine guarantees its condition.
  - Proposed fix: add the passives' flat stats to `preCombatStat` and to the scorer's pre-combat sheet. Then drop the
    certifiers' "passives are outside the read" assumptions (`MaxDamageCertifier` ~2487 / 2659,
    `MostMasteriesCertificate` 1565, the AP / crit bail in `maxDamageCertificate`).
  - This needs a `CERTIFIER_VERSION` bump (it makes builds legal) and an in-game check.

## 4. Replay

```sh
WAKFU_INSTALL=F:/Wakfu ./gradlew :bdata-extractor:test --rerun-tasks           # 35 tests, 0 skipped
./gradlew :bdata-extractor:run --args="--sublimations-only F:/Wakfu"           # "reproduces the committed oracle exactly"
WAKFU_SUB_TIMING_DEBUG=all ./gradlew -q :bdata-extractor:run --args="--sublimations-only F:/Wakfu" > timing.log
# then group the `subTiming` lines by trigger id; ignore the CRLF rewrite of sublimations.json (git checkout it).
# Tooltips: unzip contents/i18n/i18n_fr.jar; grep "^content.33.<effectId>=".
```
