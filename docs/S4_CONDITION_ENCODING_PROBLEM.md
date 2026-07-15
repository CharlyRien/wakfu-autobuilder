# S4 — Conditional sublimations block CP-SAT from proving the max-damage soft leg

**Handoff document (2026-07-15).** Self-contained problem statement for whoever picks up the
condition-encoding work. Everything below is measured, not conjectured.

---

## 1. TL;DR

A max-damage search whose **required targets are unreachable** (e.g. AP16 / MP8 / CC100 / HP12000)
falls back to the **soft penalized objective** (`damage × power6(target-shortfall)`). On that leg,
**CP-SAT no longer reaches OPTIMAL**, so the "proven optimal" badge is withheld.

We proved by isolation that the wall is **the reified conditions of the solver-choosable
sublimations**, nothing else:

| configuration (lvl-245 CRA, real parallel portfolio, all cores) | result |
|---|---|
| soft leg, **no sublimations** | **OPTIMAL** in det 253 / ~3.5 min |
| soft leg, subs **without the 16 conditional ones** | **OPTIMAL** in **49 s**, obj = 17 702 078 146 500 |
| soft leg, **full subs** (with conditions), 15 min wall | **FEASIBLE**, dual 2.14×, never proves |
| soft leg, full subs, deterministic 8-worker, det 6000 (2h27) | **FEASIBLE**, dual 3.17×, never proves |

Two consequences:
1. The **bilinear objective, the stacking, and the carrier matching are all fine** — CP-SAT proves
   through them in under a minute. Only the **condition reification** breaks provability.
2. On this shape the conditional subs **do not even improve the optimum** — the no-conditional-subs
   proof lands on `17 702 078 146 500`, the *exact* incumbent the 2h27 full-subs run had already
   found but could not prove. So **the optimum was reached all along; only the proof was missing.**

This is a **provability regression** introduced by the (semantically correct) condition-timing fix
`39532d15` (2026-07-14), which added `firstTurnStat` reified variables. Before it, sub-heavy soft
requests could prove; now the badge may be withheld. The fix must NOT be reverted (it corrects a
real Ravage×Neutralité bug — see `docs/` and the memory note); the goal is a **lighter condition
encoding** (or a search-side world split) that proves without changing the semantics.

---

## 2. Reproduce

The probe is env-gated in `MostMasteriesPerfExperimentTest.manual campaign-2 baseline matrix S1-S2-S3`.
Always `./gradlew --stop` first (the daemon freezes its env snapshot) and run with an 8 GB heap.

```sh
./gradlew --stop
# A — full subs, REAL parallel portfolio (production path, tuning=null), 15 min wall:
WAKFU_MM_C2_BASELINE=1 WAKFU_MM_C2_BASELINE_FIXTURES=S4 WAKFU_MM_C2_PROD=1 \
  WAKFU_MM_C2_WALL_SECONDS=900 WAKFU_TEST_MAX_HEAP=8g \
  ./gradlew :autobuilder:test --tests '*MostMasteriesPerfExperimentTest*' --rerun-tasks
#   → FEASIBLE, bestBound ≈ 37.9T (2.14×), optimal=false

# B — subs WITHOUT the conditional ones, same real-parallel path:
WAKFU_MM_C2_BASELINE=1 WAKFU_MM_C2_BASELINE_FIXTURES=S4 WAKFU_MM_C2_PROD=1 \
  WAKFU_MM_C2_NOCONDSUBS=1 WAKFU_MM_C2_WALL_SECONDS=900 WAKFU_TEST_MAX_HEAP=8g \
  ./gradlew :autobuilder:test --tests '*MostMasteriesPerfExperimentTest*' --rerun-tasks
#   → OPTIMAL in 49 s, rawObjective == bestBound == 17 702 078 146 500
```

Read the verdict from the `MM_PERF_AB SUMMARY shape=S4-...` line (`status`, `rawObjective`,
`bestBound`, `optimal`). The env toggles live in the test:
- `WAKFU_MM_C2_PROD=1` → `tuning = null` in `solve()` → the **production wall-clock parallel
  portfolio** (`if (tuning == null)` branch, `WakfuBuildSolver.kt` ~L2905). **Do NOT use the
  deterministic path for provability questions** — a non-null `SolverTuning` with
  `maxDeterministicTime` runs the workers *interleaved for reproducibility*, i.e. on ~1 physical
  core, so it dramatically under-represents what real parallelism proves. Every earlier
  "multi-worker" S4 measurement in `docs/MOST_MASTERIES_PERF_PLAN.md` §9.0–9.5 was this deterministic
  artifact.
- `WAKFU_MM_C2_NOCONDSUBS=1` → filters `sub.condition != null` out of the sublimation list.
- `WAKFU_MM_C2_NOSUBS=1` / `WAKFU_MM_C2_NORUNES=1` — the other isolation toggles.

---

## 3. Where the conditions are encoded

The whole reification chain is in **`autobuilder/.../genetic/wakfu/SublimationTerms.kt`**:

- **`appliesVar(sub)`** (~L197): for a non-forced sub with a supported condition, it posts
  `subVar ≤ reifyCondition(cond)` — i.e. the sub can only be *chosen* when its condition holds.
- **`reifyCondition(cond)`** (~L257): dispatches on `subConditionSpec(cond, level)`:
  - `StatBound` → `reifyStatBound` (the common case — a stat sum vs a threshold),
  - `NoOffhandOrTwoHanded` → a pick-sum ≤ 0,
  - `AlwaysApplies` → constant 1 (unsupported types stay optimistically on).
- **`reifyStatBound(spec, tag)`** (~L271): reads `firstTurnStat` when `spec.firstTurn` (only
  `SECONDARY_MASTERIES_AT_MOST` today — the 07-14 fix), else `preCombatStat`, then:
  - `AT_MOST` → `reifyLe(value, n, tag)`
  - `AT_LEAST` → `reifyGe(value, n, tag)`
  - `EXACT` → `and(reifyLe, reifyGe)`
- **`reifyLe` / `reifyGe`** (~L218/L229): the actual hardness — a fresh bool `b` with the
  **indicator pair**
  ```
  value ≤ n      onlyEnforceIf(b)
  value ≥ n + 1  onlyEnforceIf(b.not())
  ```
  This is a full two-sided reification (b ⟺ value ≤ n). `value` is itself a build-dependent
  `IntVar` (a stat total that gathers items + runes + skills + permanent subs, and — under
  `firstTurn` — start-of-combat FLAT-sub terms via `StatBuilder.firstTurnStat` /
  `startOfCombatFlatSubTermsByStat`).

Semantics + the `firstTurn` flag live in **`SublimationSemantics.kt`**
(`SubConditionSpec.StatBound`, `ConditionComparison`, `SUPPORTED_SUB_CONDITIONS`). The scalar
re-scorer (`FindClosestBuildFromInputScoring.kt`, `subConditionHolds`) evaluates the SAME spec with
integer math — any encoding change must keep the two in lockstep (there is a differential lock:
`SublimationConditionTest`, `SublimationPreCombatConditionTest`).

### The 16 solver-choosable conditional subs, by type
```
 4  SECONDARY_MASTERIES_AT_MOST   (Neutralité/Prétention/Ambition III, Inflexibilité II — firstTurn=true)
 2  CRIT_AT_MOST                  (Constance, Mesure III)
 2  CRIT_AT_LEAST                 (Dénouement is CRIT_AT_LEAST 40 → a conversion sub)
 1  AP_AT_MOST                    (Inflexibilité)
 1  CRITICAL_MASTERY_AT_MOST      (Secret critique)
 1  BLOCK_AT_LEAST                (Mesure)
 1  RANGE_AT_LEAST / 1 RANGE_AT_MOST / 1 AP_ODD / 1 DODGE_LT_PCT_OF_LEVEL / 1 NO_OFFHAND_OR_TWO_HANDED
```

---

## 4. Why CP-SAT can't prove through this

- Each `reifyLe`/`reifyGe` is an **indicator (big-M-style) constraint**. Its LP relaxation is weak:
  the fractional `b` lets `value` sit on both sides of the threshold at once, so the relaxation does
  not exclude the switching point. With **16 of them**, the search must effectively branch the
  power-set of conditions to certify the dual — the objective is nonlinear (`value` feeds mastery →
  `D·Graw` → penalty), so the disjunctions never collapse in presolve.
- The `firstTurn` reads (07-14 fix) make `value` a **larger sum** (pre-combat + start-of-combat flat
  sub terms, each subVar-gated), widening the reified expression's domain and adding gated products —
  strictly more indicator surface than the pre-fix `preCombatStat` read.
- Empirically the dual bound *does* descend under real parallelism (56→38T in 15 min) but does not
  reach the incumbent — consistent with "weak relaxation, huge branch tree," not "no bound exists."

---

## 5. Soundness / correctness constraints on any fix

1. **Do not change the game semantics.** `AT_MOST` conditions on secondary masteries / crit read the
   **first-turn** sheet (after start-of-combat FLAT sub effects); other conditions read pre-combat.
   This is the 07-14 fix and it is correct (`SublimationPreCombatConditionTest` locks it). A lighter
   encoding must reproduce the same feasible set.
2. **Scalar mirror parity.** `FindClosestBuildFromInputScoring.subConditionHolds` must agree with the
   model on every build (differential tests exist). If you split into worlds, the re-scorer still has
   to evaluate the actually-chosen world's build correctly.
3. **A sub never feeds its own condition** — the reification is acyclic by construction
   (`preCombatStat`/`firstTurnStat` exclude the conditional sub's own start-of-combat effects). Keep
   that acyclicity.
4. **Certifier stays sound.** The certificate (`MostMasteriesCertificate` / `MaxDamageCertifier`) is a
   separate proof authority; it already handles conditions via world splits and is unaffected by a
   search-side change, but if you alter the choosable set or the semantics, re-run its locks and bump
   `CERTIFIER_VERSION`.

---

## 6. Candidate directions (unranked — for the next agent to evaluate)

- **A. Search-side WORLD SPLIT (mirrors the certificate).** Solve one CP-SAT model per *condition
  world* where each condition is a **constant** (assumed held / not held), so no indicator remains —
  each world proves in ~50 s like the no-cond case; the answer is the max over worlds. The naive
  power-set is 2^16, so the leverage is in **pruning worlds**: the no-cond proof gives a strong
  incumbent+bound (17.70T), and a world only needs solving if its conditional subs *could* beat it —
  a bound the certificate's per-sub contribution estimate can supply. On S4 no world beats no-cond, so
  it would prove almost immediately. Watch: worlds where an AT_MOST condition forces a stat low
  interact with the objective (low crit ⇒ low damage), so most are self-defeating and prunable.
- **B. Tighter indicator encoding.** Replace the two-sided `reifyLe`/`reifyGe` with a one-sided
  implication where only one direction is load-bearing (the sub is *gated* by `subVar ≤ b`, so only
  `b ⟹ value ≤ n` matters for feasibility; `¬b ⟹ value ≥ n+1` may be droppable when `b` is only ever
  read through `subVar ≤ b`). Fewer/《looser reverse indicators can tighten the LP without changing the
  feasible set. Verify against the scalar mirror + the differential locks.
- **C. Lazily add conditions.** Solve no-cond first (proven, 49 s). Then, per conditional sub, test
  whether adding just that sub (its condition reified) can raise the objective above the no-cond
  optimum; skip the ones that can't. Only the survivors enter a combined solve. This is B&B over
  conditions with the no-cond optimum as the incumbent — likely proves S4 in ~1 min.
- **D. Presolve / linearization knobs.** The max-damage path already sets `linearizationLevel = 2` and
  `maxPresolveIterations = 3` (`WakfuBuildSolver.kt` ~L2904/L2918). Try higher presolve iterations or
  `numSearchWorkers` portfolios tuned for indicator-heavy models. Cheapest to try; least certain.

**Gate for any fix:** the full-subs S4 soft leg reaches `status=OPTIMAL` with the *same* objective the
no-cond run proved (17 702 078 146 500 on this shape — the conditional subs must not be dropped, only
encoded provably), in a wall comparable to the badge budget (target ≤ ~1 min real-parallel like the
no-cond case). Keep every `SublimationConditionTest` / `SublimationPreCombatConditionTest` /
`MostMasteriesCertificateTest` lock green.

---

## 7. Pointers

- Full measurement log: `docs/MOST_MASTERIES_PERF_PLAN.md` §9.10–§9.12.
- Reification: `SublimationTerms.kt` (`appliesVar`, `reifyCondition`, `reifyStatBound`,
  `reifyLe`/`reifyGe`). Semantics: `SublimationSemantics.kt`. First-turn read:
  `StatBuilder.firstTurnStat` / `startOfCombatFlatSubTermsByStat`, and `SublimationTerms.
  buildStartOfCombatFlatSubTerms`.
- Production solve branch: `WakfuBuildSolver.optimize` → the `if (tuning == null)` portfolio path.
- Scalar mirror: `FindClosestBuildFromInputScoring.subConditionHolds`.
- Regression origin: commit `39532d15` (condition-timing fix — correct, do not revert).
- The test harness + all env toggles: `MostMasteriesPerfExperimentTest` (`mdFrontierShape` = the S4
  fixture; `solve()` = the `tuning==null` switch).
