# Elemental rune booking in the max-damage collapse

2026-10-03 · Wakfu data 1.93.1.62 · `CERTIFIER_VERSION` 52 → 53.

The max-damage rune collapse stored each carrier's best M-feeding rune under the scenario's range-mastery
key. `StatBuilder.baseTermsFor` uses that key as the actual stat, so an elemental rune contributed to distance
or melee mastery. The damage sum was unchanged, but the Neutralité family's secondary-mastery condition
charged elemental runes against its budget. A damage-neutral HP=0 row enabled the general rune fold and
restored the real optimum.

The collapsed M choice now uses the rune's own characteristic. A single choice still substitutes the equip
variable; the critical-mastery alternative still uses one swap bool, with `extraTerms` suppressing the default
under that same characteristic and `suppressedBy` suppressing it in the exported build. The certifier accepts
the actual scenario-mastery keys and removes the actual default contribution when splitting the crit option.

The helmet fixture also exposed a smaller gap after fixing the key: 2,194,930 versus 2,198,020. A secondary
default is not a safe substitute for a lower-valued elemental rune when the secondary read is capped: the
elemental choice frees budget for skill points. These carriers retain explicit single-type picks, including
the smaller secondary and crit choices; carriers with a dominating elemental default retain the compact
collapse. The certifier detects explicit-pick carriers separately from equip-var aliases, so the two forms
can coexist without removing an aliased item's own stats from the certificate's base terms.

World N's category terms now put an elemental default in E rather than D. For a default contribution r,
`capRead(E+r,D,K,O) = capRead(E,D+r,K,O) + r`: the positive damage term is identical, but the secondary charge
is removed. The swapped option has the same final category totals as before. Thus every source's best World N
option can only rise; normal-world damage values are unchanged. Version 53 invalidates the old cached bounds.
Retaining explicit alternatives likewise preserves the previous options and can only raise World N's best
per-carrier valuation; the normal worlds still prune dominated damage options to the same values.

`RuneChoiceCollapseTest` locks the CRA-230 fire/distance/face reproduction with Neutralité III: cape
(fire 1000, four sockets, level 230) + socketless boots (fire 1000), and the variant adding a level-200 helmet
(fire 1000, rear −120, four sockets). It compares the free MASTERY_DISTANCE=1 request with the same request plus
HP=0, requires Neutralité selected, and checks every feasible AP cell against the exact, fast and tier-1.5
certificate bounds. A separate doubled-crit fixture checks the elemental-default suppression, four-rune export
and the certifier's normal-world split.

This change is based on `origin/main` at `e4cb857c`. Its target-aware certificate and
statless Epic/Relic carrier fixes are preserved. The rune mirror also retains RANGE-only carriers
while distinguishing explicit rune picks from equip-variable aliases.

## Validation on origin/main

- `./gradlew ktlintFormat :autobuilder:test`: passed, 478 tests (76 skipped), zero failures/errors,
  40 classes. The two `RuneChoiceCollapseTest` tests passed, as did all 14 target-aware certificate locks.
- Both CRA-230 fixture variants prove OPTIMAL, agree with the general fold, and select Neutralité III:
  1,540,880 for cape + boots, 2,198,020 with the signed-secondary helmet.
- `./gradlew :autobuilder:slowTest --tests '*lvl-245 fast certifier ledger*'`: passed. All 21 AP cells
  are identical to the existing data-version bank; no cell fell and no re-bank was needed. The largest
  cell remains 17,766,150 at AP=16.
- `./gradlew :gui-compose:test --tests '*ChangeFragmentsTest*'`: all five tests passed.
- A final `./gradlew ktlintFormat` followed by `./gradlew ktlintCheck`: passed.

## Deterministic flagship measurements

The existing `manual max-damage experiment ab` harness uses the full eligible Epic pool, CRA,
fire/distance/face, free targets, runes + all choosable sublimations, domination enabled, the default
experiment config, one worker, seed 1 and `interleaveSearch=true`. Each checkpoint is a fresh solve
with `maxDeterministicTime` 30 or 120 and a 3,000-second wall safety limit. Before is `e4cb857c`
(certifier 52); after is that revision plus this fix (certifier 53). Both source snapshots are isolated
from the shared working tree. Wall-clock comparisons are omitted because other tests shared the host.

All three repeats are byte-identical after normalizing the repeat index, including objective, best
bound, status and actual deterministic time. Every solve reaches its deterministic limit. Raw points:
[rune-choice-collapse-bench.csv](rune-choice-collapse-bench.csv).

| Level | Deterministic budget | Before objective | After objective | Before bound | After bound |
|---|---:|---:|---:|---:|---:|
| 110 | 30 | 1,166,175 | 1,045,725 | 2,294,025 | 3,274,050 |
| 110 | 120 | 1,425,690 | 1,277,865 | 2,281,980 | 3,018,915 |
| 245 | 30 | 5,540,250 | 8,037,000 | 28,212,360 | 33,285,600 |
| 245 | 120 | 15,367,120 | 12,528,125 | 27,609,360 | 33,282,920 |

All statuses are FEASIBLE. The corrected model retains more valid rune choices, and the benchmark
shows a reproducible search cost: at 120 deterministic seconds the incumbent is 10.37% lower at
level 110 and 18.47% lower at level 245; the native dual bounds are looser at every checkpoint.
At the shorter 30-second checkpoint level 245 gains 45.07%. These are incomplete searches of the
corrected feasible space; the tiny CI reproductions prove the restored optimum exactly. Parallel
host load affects elapsed time, but the repeated deterministic trajectories agree.

Reproduce either snapshot with the existing harness (set `WAKFU_EXP_AB_LEVEL=245` for the other fixture):

```sh
WAKFU_EXP_AB=1 WAKFU_EXP_AB_VARIANTS=baseline WAKFU_EXP_AB_LEVEL=110 \
WAKFU_EXP_AB_DET=30,120 WAKFU_EXP_AB_REPEATS=3 \
./gradlew :autobuilder:test --tests '*manual max-damage experiment ab' --rerun-tasks
```
