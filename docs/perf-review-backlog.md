# Perf & soundness review backlog — 2026-07-06

Source: multi-agent adversarial review of the two commits
`f2d6994d` (*feat: prove optimality for constrained max-damage builds via hard-constraint targets*) and
`7e6c3253` (*feat: certify optimality for required-target max-damage builds, and speed up proving*),
plus a performance scouting pass over the solver and the certifier.
Branch at review time: `docs/denouement-feasibility`. Full solver test suite was green at HEAD.

**Overall verdict:** both commits are sound in design and their key claims were verified
(raw-proxy/ledger comparison is mathematically sound; "consumer-only, no CERTIFIER_VERSION bump" is
correct; the ring `take(2)` fix and the self-disabling AM-GM cut tighten in the sound direction;
`hardConstraints` defaults false and reaches only the three max-damage call sites). The items below are
the follow-up work: one confirmed soundness hole (A1 ✅ done), one proven test gap (A2 ✅ done), a set of
behavioral issues (A3–A8, **all shipped 2026-07-06**), the **certifier perf backlog (B1–B8, all ✅ done
2026-07-06/07)**, the **solver perf backlog (C1–C8, all code-verified 2026-07-07 — the remaining open work,
with an ordered roadmap in §C)**, and tiny hygiene items (D). See each item's `✅ DONE` / `VERIFIED` block.

---

## 0. How to use this file (read this first)

- **Work ONE item at a time.** Respect the `Depends on` lines. Statuses:
  - `CONFIRMED` — verified by 3 independent reviewers AND re-checked by hand; implement directly.
  - `PROVEN` — demonstrated empirically (a mutation run); implement directly.
  - `PLAUSIBLE` — reported by one reviewer, **not** verified. Your FIRST step is to re-verify the claim
    in the code; if the claim is wrong, record why here (edit this file) and stop — do not "fix" it.
- **Never weaken an existing test or assertion to make an item pass.**
- Before finishing any item: `./gradlew ktlintFormat`, then
  `./gradlew :autobuilder:test --tests '*WakfuBuildSolverTest'` (add `:gui-compose:test` if you touched
  `BuildSearchModel` or anything in `gui-compose`). CI runs `./gradlew test`.
- Line numbers below are approximate (`~L`) — locate by the quoted identifier, not the number.
- Key files (all under `autobuilder/src/main/kotlin/me/chosante/autobuilder/genetic/` unless noted):
  - `wakfu/WakfuBuildSolver.kt` — CP-SAT model build + `optimize()` + certificate orchestration.
  - `wakfu/MaxDamageCertifier.kt` — the certificate ledger DP (`certifyLedger` → fast pass
    `certifyAllCellsFast` + exact pass `exactForCells`).
  - `wakfu/MaxDamageSearch.kt` — max-damage orchestration: phases, probes, `optimizeHardThenSoft`,
    `proveOptimality`, the certificate cache (`MaxDamageCertificateCache`).
  - `wakfu/StatBuilder.kt` — stat chains, `reachableSumDomain`, `requiredActualStat`,
    `addRequiredTargetHardConstraints`.
  - `wakfu/DominationFilter.kt` — per-slot domination pre-filter (`dominationShape`,
    `filterDominatedPool`).
  - `wakfu/DamageObjective.kt`, `wakfu/MaxDamageExperiments.kt`, `SolverResult.kt`.
  - Tests: `autobuilder/src/test/kotlin/me/chosante/autobuilder/genetic/wakfu/WakfuBuildSolverTest.kt`.

### Non-negotiable invariants (violating any of these is an automatic revert)

1. **The certificate must never under-count a cell.** Every pass is a sound UPPER bound; when in doubt
   it must BAIL (`Long.MAX_VALUE`). Test locks assert `>=`, never `==` (except pinned exactness locks
   that already exist).
2. **Bump `CERTIFIER_VERSION`** (`WakfuBuildSolver.kt`) on ANY change to a certifier pass (fast pass,
   exact pass, orchestrator, scaling, world/sub enumeration) — it keys the per-cell bound cache.
   Consumer-only changes (how a finished ledger is compared/consumed) do not need a bump. Each B item
   below says explicitly whether it needs the bump.
3. **A "proven optimal" badge may only be shown when it is actually true** for the pool the user asked
   about — CP-SAT `OPTIMAL` on a *reduced* pool is NOT a proof unless the reduction is
   optimum-preserving for the active constraint shape (this is exactly item A1).
4. **Engine tests must pin `SolverTuning`** (deterministic time, fixed seed, 1 worker) or they flake.
   Note: `tuning != null` also DISABLES domination (`applyDomination = tuning == null` in
   `optimize()`), so domination interactions need unit-level tests (see A1/A2).
5. **Measure perf with `deterministic_time`** (and fixed seed / 1 worker), never wall-clock — thermal
   noise makes wall-clock lie. Protocol details: `docs/SOLVER_PERFORMANCE.md` §0.
6. Kotlin official style, ktlint enforced. Don't commit unless asked.

### Already REJECTED ideas — do NOT implement, do NOT re-propose

(One-line reasons; sources: `docs/SOLVER_PERFORMANCE.md` §4, `docs/max-damage-subs-provability-investigation.md`,
`docs/CERTIFICATE_PROD_PLAN.md`.)

- **certcap** (injecting certifier per-cell ceilings as CP-SAT bound caps): floors the dual bound ABOVE
  the incumbent ⇒ CP-SAT can never reach `OPTIMAL`. The dormant flag stays off.
- **Certificate-driven early stop of the search**: fires only where CP-SAT already proves in seconds.
- **AP-slice / per-cell memo of the CP-SAT solve**: measured no-ops (presolve already derives them).
- **perApRotRawCut**: perturbs the search without helping.
- **Naive ceiling-skip of boss element probes** (skip when proxy ceiling < leader): UNSOUND — winner is
  re-scored by `sequencedScore` which diverges ~0.6% from the proxy. Any element elimination needs an
  epsilon margin design (deliberately not scheduled here).
- **MP-target / defensive-stat axes in the certificate**: built, measured loose at scale, REVERTED —
  superseded by the hard-constraints solve. Generalizing the certificate to resistance/HP/lock is
  structurally impossible.
- **P1.2 n-dominance sweep in the exact DP**: byte-identical but slower; premise fails.
- **Knobs already measured as regressions** on the lvl-110 free solve: `numFullSubsolvers=8`,
  `detectLinearizedProduct` (det 981 vs 460 baseline), both together, division-floor removal,
  dGrawCutoff, per-probe read-set domination.
- Expecting the hard-constraint solve to prove **range-binding** shapes (`--po` targets): measured
  stuck; that shape needs C6 or the (deprioritized, high-risk) range certifier.

### Suggested execution order

| Order | Item | Status / why |
|---|---|---|
| — | ~~A1, A2~~ | ✅ DONE 2026-07-06 (committed `726215bf`) — wrong-badge hole + test gap. |
| — | ~~A3–A8~~ | ✅ DONE 2026-07-06 (committed `c8c0452a`). See each item's `✅ DONE` block. |
| — | ~~B1–B8~~ | ✅ ALL DONE (2026-07-06/07). See each B item's `✅ DONE` block. |
| — | ~~D2~~ | ✅ DONE (shipped with A8.3). |
| — | ~~C1, C2, C3~~ | ✅ DONE 2026-07-07 (committed `8eb21d7c`) — reachableSumDomain aggregation, hard-leg pre-screen, per-element domination memo. |
| — | ~~C4~~ | ✅ HARNESS DONE (committed `26a3f0e8`); measurement deferred to a quiet machine. C5 DROPPED. |
| — | ~~C8 (1)~~ | ✅ DONE — most-masteries reachable objective-product bound (sound, ~10³–10⁵× box tightening). |
| Deferred | C6, C7, C8(2)/(3) | **C7 ✅ CLOSED 2026-07-09: re-implemented from the staged derivation (sound, guards green, fires on the engineered pool) — MEASURED-INERT on the flagship shapes (self-disables; byte-identical A/B), stays OFF.** C6 not started (run C4 screen first). C8(2) measured-NO / (3) blocked. See each item's block in §C. |
| Anytime | D1, D3, D4 | Tiny hygiene — D1 CLI wording, D3 nightly-test flake, D4 doc note. |

---

## A. Correctness / soundness (do these first)

### A1 — ✅ DONE (2026-07-06): resistance-target hard-first could award a WRONG "proven optimal" badge

- **Shipped fix (both parts):**
  1. **Part 1 (domination feeders)** — `dominationShape` (max-damage branch, `DominationFilter.kt`) now adds
     the resistance feeder block (`ELEMENTARY_RESISTANCES` + generic `RESISTANCE_ELEMENTARY` + `RANDOM_RESISTANCES`)
     to `compared` **whenever a resistance target is present** (conditional, so the default-request domination win
     is untouched). HP needs no analogue (single characteristic, no feeder folding — documented inline).
  2. **Part 2 (prefilter gate)** — `MaxDamageSearch.proveOptimality` now returns `Unavailable` for any request that
     trips `needsItemPrefilter` (now `internal`), placed **before** the `isOptimal` short-circuit. Key correction
     to the plan below: the certificate path also runs through `buildModel`, which **re-applies the prefilter**, so
     "fall through to the certificate" would compare against an under-counted (reduced-pool) ledger — still unsound.
     The sound response is to withhold the badge outright (invariant: when in doubt, no badge). Certifying
     prefiltered shapes soundly would need the certificate over the FULL pool — a certifier-pool change (VERSION
     bump + measurement), left as future work.
- **Tests (all non-vacuous, mutation-verified):** `domination keeps a generic-resistance item when a resistance
  target is present, drops it otherwise` (both directions on `filterDominatedPool`); `hard-constrained solve with
  domination keeps the only item meeting a generic-resistance target` (end-to-end, level-1 char so skills can't
  supply the resistance; new `hardConstraints`/`selectedEquipmentIds` seam on `maxDamageSolveForTest`);
  `proveOptimality withholds the badge for a prefiltered (multi-element resistance) request`. Neutering either fix
  fails its test. Full `:autobuilder:test` + ktlint green. No `CERTIFIER_VERSION` bump (consumer-side + domination
  compared-set only).

- **Status:** CONFIRMED (3/3 independent verifiers + manual re-check of `DominationFilter.kt`).
- **Files:** `wakfu/DominationFilter.kt` (the max-damage `compared` set, ~L130–155),
  `wakfu/WakfuBuildSolver.kt` (`prefilterRelevantEquipments` / `needsItemPrefilter`, ~L283–293 and
  `buildModel` ~L539–551), `wakfu/MaxDamageSearch.kt` (`proveOptimality` `isOptimal` short-circuit,
  ~L293).
- **Problem (the full chain, every link verified):**
  1. `proveOptimality` short-circuits `if (result.isOptimal) return ProvenOptimal` — no certificate
     cross-check. `isOptimal` = CP-SAT `OPTIMAL` over whatever pool the model was built on.
  2. Production solves (`tuning == null`) apply per-slot domination, and aggregate
     `RESISTANCE_ELEMENTARY` targets additionally trip `needsItemPrefilter`
     (`resistanceElementsWanted.size > 1`) → `prefilterRelevantEquipments`, whose own kdoc calls it a
     top-N-per-stat HEURISTIC that trades global optimality.
  3. The max-damage `compared` set adds `params.targetStats.map { it.characteristic }` (the target
     chars themselves) but **NOT the resistance feeder stats** — generic
     `Characteristic.RESISTANCE_ELEMENTARY` and `RANDOM_RESISTANCES` are added **only** when
     `scenario.survivabilityFloor && minEffectiveHp > 0`.
  4. But `StatBuilder.requiredActualStat` folds specific + generic + random resistance into the
     constrained "actual" value. So an item whose resistance is carried as generic/random lines is
     0-vs-0 on every compared characteristic and gets dominated away by a higher-mastery item — even
     when it is the ONLY item that lets the build meet the hard resistance target.
  5. Result: the hard model either proves `OPTIMAL` on a pool from which the true constrained optimum
     was pruned (**wrong badge**, invariant 3 violated) or goes falsely `INFEASIBLE` (**silent soft
     fallback**, losing exactly the improvement `f2d6994d` shipped, with a wrong "was infeasible" log).
  6. No existing test can catch this: the new tests pin `SolverTuning`, which disables domination.
- **Fix (two parts, both required):**
  1. In `dominationShape` (max-damage branch), whenever any required target characteristic is a
     resistance stat (any of the four `RESISTANCE_ELEMENTARY_*` after `TargetStats` expansion, or the
     aggregate), add the same feeder block already used for the survivability floor:
     `addAll(ELEMENTARY_RESISTANCES); add(Characteristic.RESISTANCE_ELEMENTARY); addAll(RANDOM_RESISTANCES)`.
     Keep it conditional (only when a resistance target exists) so the domination win on default
     requests is untouched.
  2. Neutralize the prefilter on the proof path. Preferred minimal form: when
     `needsItemPrefilter(...)` is true AND the solve runs with `hardConstraints = true`, either (a)
     extend the prefilter's kept-stats so it keeps top-N per resistance feeder stat as well (then it is
     still a heuristic — so ALSO do (b)), or (b) thread a `poolWasReduced` boolean out of `buildModel`
     and, in `MaxDamageSearch`, refuse the `isOptimal → ProvenOptimal` short-circuit when the prefilter
     fired (fall through to the certificate/`ProvenWithin`/`Unavailable` logic). (b) alone is
     sufficient and sound; (a) alone is NOT sufficient.
  3. While in there, audit the same reasoning for HP targets: HP is carried as a single characteristic
     on items (no feeder folding), so HP needs no compared-set change — write that down as a comment
     next to the resistance block so the next reader knows it was considered.
- **Verify / acceptance:**
  - New unit test on `filterDominatedPool` (no `SolverTuning` involvement): pool of two same-slot items
    — `A {fire mastery 500}`, `B {RESISTANCE_ELEMENTARY (generic) 50, fire mastery 200}` — with a
    required `RESISTANCE_ELEMENTARY` (or fire-resistance) target in `params.targetStats`: assert B
    SURVIVES the filter. Same pool without the resistance target: assert B is dominated (proves the
    fix is correctly conditional).
  - New test: hard-constrained solve over a hand pool where the ONLY way to meet a resistance target is
    a generic-resist item (call `buildModel`/`optimize` with `applyDomination = true` explicitly, pinned
    tuning otherwise): assert the returned build contains that item and meets the target via
    `computeCharacteristicsValues`.
  - A2's binding-target tests must still pass. Full suite green.
- **Do NOT:** globally disable domination or the prefilter (that throws away the measured ~2.8×
  default-path win); do not add ALL characteristics to `compared`.

### A2 — ✅ DONE (2026-07-06): the three new tests don't lock hard-constraint enforcement

- **Shipped:** two mutation-killing tests added —
  `max-damage hard-constraints enforce a binding AP target` (base AP 6, target 8 reachable ONLY via `ApTax`,
  which costs 1500 mastery ⇒ the constrained optimum drops `FreeDmg`; asserts item membership, AP ≥ 8, isOptimal)
  and `max-damage hard-constraints enforce an aggregate resistance target as the min of four elements`.
  **Deviation from the spec, with reason:** the resistance test uses the capacity-1 AMULET slot, NOT RING — a RING
  (capacity 2) lets the solver equip BOTH rings so `AllRes` is always in the build, making the min-of-four
  assertion vacuous. It also runs at LEVEL 1 so skill points (Intelligence resistance 10/pt, Major branch) can't
  supply the 40 resistance and mask the item requirement (a level-50 char satisfied the target from skills and
  kept the max-mastery item — the same skill confound bit A1's end-to-end test).
- **Acceptance met:** re-ran the mutation (`addRequiredTargetHardConstraints`'s `addGreaterOrEqual` guarded by
  `if (false)`): BOTH new tests fail; restored; full `:autobuilder:test` + ktlint green.
- **Optional fuzz follow-up — DEFERRED (not done):** the existing fuzz (`fuzzScenario` ~L7382) feeds the CERTIFIER
  fuzz (`certifierExactAndFastCellObjectivesForTest`), not the hard-then-soft SOLVE path, so asserting "hard-leg
  winners meet every target / ProvenOptimal ⇒ fullyMeetsRequiredTargets" needs a SEPARATE solve-driven fuzz (25
  extra full `MaxDamageSearch.run`s + unreachable-target flake risk). Left for a future pass; the two
  deterministic locks above cover the core semantics.

- **Status:** PROVEN by mutation — with `addRequiredTargetHardConstraints` neutered (constraints not
  added), all three new tests still pass: `max-damage hard-constraints-first proves a vacuous AP target
  directly` (~L6051), `…falls back to soft when the target is unreachable` (~L6077), `max-damage
  proveOptimality certifies a required-target request when the target is met` (~L6013). Root causes:
  test 1's target is non-binding by construction, test 2 only asserts `isNotEmpty`, test 3 is driven by
  the `fullyMeetsRequiredTargets` guard.
- **Files:** `WakfuBuildSolverTest.kt` (add tests near ~L6051), `wakfu/StatBuilder.kt`
  (`requiredActualStat` min-of-four, ~L1543; `addRequiredTargetHardConstraints`, ~L1564).
- **Fix — add two tests (specs were reviewer-designed to kill the exact mutation):**
  1. **Binding AP target.** Pool: AMULET `FreeDmg {MASTERY_ELEMENTARY_FIRE 2000}`, AMULET
     `ApTax {MASTERY_ELEMENTARY_FIRE 500, ACTION_POINT +2}`, BELT `BeltM {MASTERY_ELEMENTARY_FIRE 500}`.
     Params: `fireMaxDamageParams(50)` + `targetStats = TargetStats(listOf(TargetStat(ACTION_POINT, 8)))`
     (base AP 6 ⇒ only `ApTax` reaches 8, and it costs 1500 mastery ⇒ the target BINDS). Pinned tuning
     (`numSearchWorkers=1, randomSeed=1, maxDeterministicTime=30.0`). Assert: (i) the build contains
     `ApTax` and NOT `FreeDmg`; (ii) `computeCharacteristicsValues(best)[ACTION_POINT] >= 8`;
     (iii) `best.isOptimal` is true.
  2. **Aggregate resistance min-of-four.** Pool: RING `FireOnly {RESISTANCE_ELEMENTARY_FIRE 100,
     fire mastery 800}` vs RING `AllRes {RESISTANCE_ELEMENTARY 50, fire mastery 200}`; target
     `RESISTANCE_ELEMENTARY 40`. Only the min-of-four semantics force `AllRes`; a drift to
     folded-aggregate/sum/any-element semantics picks `FireOnly` and must fail the test. Assert all
     four per-element actuals ≥ 40 on the returned build.
- **Acceptance:** re-run the mutation (make `addRequiredTargetHardConstraints` a no-op locally —
  do not commit it): at least one new test MUST fail. Restore, suite green.
- **Optional follow-up (small, valuable):** extend `fuzzScenario` (~L7133) so ~half the seeded
  iterations draw one required target (AP/MP at `base + rng.nextInt(4)`, or aggregate resistance near
  the pool's reachable min-of-four) and assert: hard-leg winners meet every target via
  `computeCharacteristicsValues`; `ProvenOptimal` ⇒ `fullyMeetsRequiredTargets`. Keep the added fuzz
  runtime bounded (reuse existing iteration counts).

### A3 — ✅ DONE (2026-07-06): two-leg solve can burn up to 2× the per-phase budget

- **Shipped:** `optimizeHardThenSoft` (`MaxDamageSearch.kt`) now marks a monotonic start before the hard leg and,
  on the `!hardYieldedBuild` fallback, gives the soft leg only `(searchDuration − elapsed).coerceAtLeast(50ms)`
  via `params.copy(searchDuration = remaining)` — so the two legs together stay within the caller's budget. The
  A8.2 log reword ships in the same block. The principled `CpSolverStatus`-based distinction of INFEASIBLE vs
  timeout is left for a follow-up (it rides on A4's status/plumbing); the budget re-slice is sound on its own.
- **Verdict:** sub-claims 1 & 2 **CONFIRMED**; sub-claim 3 (silent GUI) **REFUTED as stated**.
  - **(1) same budget — CONFIRMED.** `optimizeHardThenSoft` passes the same `params` to both legs
    (`MaxDamageSearch.kt:540` hard, `:547-548` soft); it becomes each solve's cap at
    `WakfuBuildSolver.kt:1749` (`maxTimeInSeconds = searchDuration…coerceAtLeast(0.05)`). Worst case
    ≈ `phaseBudget` (hard, exhausted) + `phaseBudget` (soft). Note the budget entering
    `optimizeHardThenSoft` is already `phaseBudget = searchDuration / activePhases`
    (`MaxDamageSearch.kt:123`, `:172`), so on the single-phase path the two legs together can reach the
    full user `searchDuration`.
  - **(2) fallback on "emitted nothing" — CONFIRMED.** `MaxDamageSearch.kt:538-549` falls to soft on
    `!hardYieldedBuild`, which cannot distinguish *provably INFEASIBLE* from *feasible-but-no-incumbent-in-a-short-slice*.
  - **(3) silent GUI — REFUTED.** A time-based ticker in `BuildSearchModel.kt:767-776` drives the bar
    from elapsed wall-clock independent of solver emissions, so it does **not** freeze. It is, however,
    **miscalibrated**: it uses `params.searchDuration` (the full user budget), unaware the engine splits
    that into a phase budget and can then run two legs — so the bar's pacing is wrong, and the headline
    mastery/build meter shows nothing until the first build emits.
- **Implementable — YES (small, self-contained).** Entirely within `optimizeHardThenSoft`
  (`MaxDamageSearch.kt:527-552`): capture a monotonic mark before the hard collect; after the hard leg
  finishes with `!hardYieldedBuild`, compute
  `remaining = (params.searchDuration − elapsed).coerceAtLeast(50.milliseconds)` and pass
  `params.copy(searchDuration = remaining)` to the soft `optimize`. One variable threaded, no signature
  changes; 50 ms matches the solver's own `0.05` floor. Composes with C2 (which removes most doomed hard
  legs) and A5 (which removes the doomed AP-probe legs).
  - **Interaction caveat (record, don't block):** flooring the soft leg to 50 ms shortens the "always
    return the closest build" safety net; if sub-claim 2's false-trigger fires (feasible hard model, no
    incumbent in slice) the floored soft leg may also find nothing. The principled cure for (2) is to
    read `CpSolverStatus.INFEASIBLE` instead of inferring it from "emitted nothing" — but that needs the
    solver status surfaced out of `optimize`/`executeSolverAndEmitResults` (larger change; overlaps A8.2's
    reword and can be folded with A4's status plumbing). Ship the budget re-slice first; treat the
    status-surfacing as a follow-up.
  - **Progress heartbeat — feasible but unnecessary for the GUI** (the ticker already moves); only the
    CLI (no ticker) would benefit. Deprioritize.
- **Acceptance:** deterministic budget-split unit test on `optimizeHardThenSoft` (assert the soft leg's
  effective `searchDuration` ≤ the requested total minus the hard-leg elapsed), or a wall-clock-bounded
  test (total ≤ 1.3× requested) on an unreachable-target request.

### A4 — ✅ DONE (2026-07-06): cancelling an INFEASIBLE hard-leg solve leaves a zombie native solve

- **Shipped:** the structural fix in `WakfuBuildSolver.optimize`'s `callbackFlow`. The blocking solve now runs in a
  `launch(Dispatchers.IO)` child job (was `withContext` — which reached `close()`/`awaitClose` only after the solve
  already returned), `executeSolverAndEmitResults` gained an `onSolverReady: (CpSolver) -> Unit` hook that hands the
  freshly-created solver to an `AtomicReference<CpSolver?>` in the flow, and `awaitClose { solverHandle.get()?.stopSearch(); job.cancel() }`
  stops the native solve from the teardown thread. Confirmed `CpSolver.stopSearch()` is `public synchronized void`
  in the pinned OR-Tools `9.15.6755` binding (thread-safe). Applies to ALL modes (shared path). `withContext` import
  removed. Full `:autobuilder:test` + `:gui-compose:test` green (the restructure is transparent to consumers).
- **Verdict:** all three sub-claims **CONFIRMED**.
  - Cancellation is honoured **only** inside `onSolutionCallback` via the callback's `stopSearch()`
    (`WakfuBuildSolver.kt:1768-1776`; the guard is `if (!scope.isActive) { stopSearch(); return }`).
  - OR-Tools invokes that callback **only on a feasible solution**, so an INFEASIBLE model never reaches
    it. The blocking `solver.solve(model, cb)` (`:1803`) runs in `withContext(Dispatchers.IO)` and is not
    interruptible by coroutine cancellation; with `maxTimeInSeconds` = full user duration and
    `numSearchWorkers = cores − 1`, a cancelled infeasible solve keeps the native pool pinned to the cap.
  - `awaitClose { }` (`:436`) is **empty**; `invokeOnCancellation` appears nowhere; the `CpSolver` is a
    local `val` (`:1723`) with **no retained reference**, so nothing can stop it from another thread.
  - Applies to **all modes** — the fix lives on the shared `optimize`/`executeSolverAndEmitResults` path.
- **Implementable — YES, but STRUCTURAL (not a one-line `awaitClose` edit).** Three coordinated changes:
  1. **Retain the solver reference** so teardown can reach it (hoist/holder around the local `CpSolver`).
  2. **Use `CpSolver.stopSearch()` — the SOLVER-level method** (thread-safe, callable while `solve()`
     blocks), **not** the callback's `stopSearch()` (only meaningful inside `onSolutionCallback`).
  3. **Run the blocking solve on a launched child job**, not awaited before `close()` — otherwise
     `awaitClose { solver.stopSearch() }` only fires *after* `solve()` already returned. This reshapes the
     `withContext(Dispatchers.IO) { … }; close()` block at `:409-435` so the `callbackFlow` body suspends
     in `awaitClose` while the native solve runs. `invokeOnCancellation` is an equivalent hook with the
     same retained-ref + separate-job requirement.
  - **Pre-req to verify before relying on it:** confirm the pinned OR-Tools Java binding
    (`gradle/libs.versions.toml`) actually exposes a thread-safe `CpSolver.stopSearch()`.
- **Acceptance:** unit test — launch an intentionally INFEASIBLE hard solve with a 30 s budget, cancel
  the collecting scope after ~1 s, assert the underlying solve releases within a couple of seconds (latch
  around a `solver.solve()` wrapper, or assert prompt flow completion).

### A5 — ✅ DONE (2026-07-06): phase-2 AP probes pinned below a required AP target are doomed hard solves

- **Shipped:** extracted the window builder into a pure `internal fun MaxDamageSearch.apProbeTargets(a0, requiredApTarget)`
  that drops any probe `< requiredApTarget` (the required AP target = `targetStats.firstOrNull { ACTION_POINT && target > 0 }?.target ?: MIN_AP_TARGET`),
  and call it from the phase-2 block. **Test:** `apProbeTargets drops probes below a required AP target`
  (`WakfuBuildSolverTest`) — required AP 11 ⇒ `[12,13,14]`; no target ⇒ full window `[8,9,10,12,13,14]`. Matches the
  ticket's acceptance exactly.
- **Verdict:** **CONFIRMED** (sub-claim 3 with a nuance).
  - A phase-2 window probe pins AP via a hard **equality** `addEquality(actionPointVar(), N)`
    (`WakfuBuildSolver.kt:1482`), for each `N in (a0-3)..(a0+3)` (`MaxDamageSearch.kt:208-216`).
  - A required AP target is a required-most-masteries target, enforced under `hardConstraints=true` as
    `addGreaterOrEqual(requiredActualStat(ACTION_POINT), T)` (`StatBuilder.kt:1567`) — on the **same
    cached `IntVar`** the probe pins. So `AP == N` ∧ `AP ≥ T` with `N < T` is a static contradiction on a
    constant ⇒ provably INFEASIBLE ⇒ the doomed hard-then-soft double solve.
  - **Nuance (sub-claim 3):** phase-2 is gated on `hasResistanceDebuff` (`MaxDamageSearch.kt:89-90`), not
    a hardcoded class check — but only Sram & Sadida own such a spell, so it is *functionally*
    Sram/Sadida. And the doomed hard leg fires **only when a required target is present** (each probe
    reuses `baseParams.copy(...)` keeping `targetStats`, and `optimizeHardThenSoft` takes the hard leg
    only when `targetStats.any { isRequiredMostMasteriesTarget() }`, `:534`) — i.e. exactly the claim's
    "with an AP target" precondition. Because phase-1 also enforces `AP ≥ T`, `a0 ≥ T`, so the doomed
    probes are precisely `N ∈ {a0-3 … a0-1}` that fall below T (up to 3 per search).
- **Implementable — YES (clean; preferred = probe-planner skip).** In the window builder
  (`MaxDamageSearch.kt:~207-216`) extend the existing `.filter { it in MIN_AP_TARGET..MAX_AP_TARGET && it != a0 }`
  with `&& it >= requiredApTarget` (guarded on such a target existing) — never even constructs the doomed
  probe. Identifiers: **required AP target** =
  `baseParams.targetStats.first { it.characteristic == Characteristic.ACTION_POINT }.target`; **probe's
  pinned AP** = the loop variable → `maxDamageApTarget` (`addEquality`). They are distinct concepts on the
  same solver var — which is why `N < T` is a static contradiction. (The leg-level alternative in
  `optimizeHardThenSoft` is the C2 pre-screen generalized; the probe-planner skip is tighter/cheaper.)
- **Acceptance:** unit test on the probe plan: with required AP 11, probes 8–10 are not built / run no
  hard leg; probes 11–14 do.

### A6 — ✅ DONE (2026-07-06): `MaxDamageExperimentConfig.DEFAULT` flip leaks into precision (inert for most-masteries)

- **Shipped:** added a decoupled companion `MaxDamageExperimentConfig.NON_MAX_DAMAGE = MaxDamageExperimentConfig(sameNameRingBound = true)`
  and pass it explicitly at the precision (`WakfuBuildSolver.kt:597`) and most-masteries (`:1416`) `StatBuilder`
  constructions. This preserves today's precision behaviour byte-for-byte (`sameNameRingBound = true` is the only
  field that reaches a non-max-damage model; the damage-objective-only flags are inert) while making those modes
  independent of the max-damage `DEFAULT` — a future max-damage experiment flip can no longer perturb them. Chose
  `NON_MAX_DAMAGE` over passing `.DEFAULT` (which stays coupled to the max-damage default's definition). Existing
  precision + most-masteries deterministic optima unchanged (full suite green).
- **Verdict:** **CONFIRMED with an important refinement** — the leak is REAL for **precision**, currently
  **inert** for most-masteries.
  - `StatBuilder`'s ctor defaults `maxDamageExperiment = MaxDamageExperimentConfig.DEFAULT`
    (`StatBuilder.kt:100`); `DEFAULT = (dGrawJointBound = true, sameNameRingBound = true)`
    (`MaxDamageExperiments.kt:46`).
  - Non-max-damage sites don't pass it: **precision** `WakfuBuildSolver.kt:597` and **most-masteries**
    `WakfuBuildSolver.kt:1416` (only the max-damage site, ~`:605`, passes it explicitly).
  - `sameNameRingBound` is read in `ringOptions` (`StatBuilder.kt:295`) → `rarityAwareUpper` (`:383`) →
    `reachableSumDomain` (`:218`). Whether that `reach` is **applied** depends on `tight`
    (`DomainTracker.decl`, `:72-82`: `tight` uses `reach`, else discards it).
    - **Precision runs `tight = true`** in the production `buildModel` path (`tightDomains` defaults true,
      `:500`) ⇒ the reach is applied ⇒ **flipping `sameNameRingBound` genuinely changes precision's
      declared ring domains today.**
    - **Most-masteries runs `tight = false`** ⇒ `reachableSumDomain` still runs and reads the flag but the
      reach is discarded ⇒ **currently harmless.** (`dGrawJointBound` is read only in
      `DamageObjective.kt:101`, unreached by both non-max-damage modes ⇒ inert in both.)
- **Implementable — YES, but choose the config to PRESERVE behavior.** Pass an explicit
  `maxDamageExperiment` at `:597` and `:1416`. ⚠️ Passing `BARE` (`sameNameRingBound = false`) would
  **change the precision optimum's declared domains** (dropping the sound same-name-ring tightening) — so
  a *decouple that preserves today's behavior* must pass `.DEFAULT` explicitly (with a comment on the
  coupling), or introduce a dedicated non-experiment `sameNameRingBound` flag independent of the
  max-damage experiment. The goal: non-max-damage model construction never silently moves on a future
  max-damage `DEFAULT` flip.
- **All `StatBuilder(` call sites** (module `main`, none in tests): decl `StatBuilder.kt:88`; precision
  `WakfuBuildSolver.kt:597` (implicit); max-damage `:605` (explicit); most-masteries `:1416` (implicit).
- **Acceptance:** grep shows no non-max-damage `StatBuilder(` relying on the implicit default; existing
  precision & most-masteries deterministic optima unchanged; suite green.

### A7 — ✅ DONE (2026-07-06): solver-vs-scorer rounding decides hard feasibility at the target boundary — but only HP is affected

- **Shipped (a SOUNDER fix than the ticket's naive tolerance):** deeper analysis showed a blind `scorer ≥ target − 1`
  tolerance in `fullyMeetsRequiredTargets` is UNSOUND for the certificate path — if the HP target is *unreachable*
  the best build sits at exactly `target − 1`, and a −1 tolerance would wrongly certify it. Instead we thread the
  solver's OWN feasibility verdict: a new `SolverResult.maxDamageHardConstraintsMet` flag is set on hard-leg
  emissions (`optimizeHardThenSoft` `.copy(maxDamageHardConstraintsMet = true)`), and `proveOptimality` trusts it —
  `if (!result.maxDamageHardConstraintsMet && !fullyMeetsRequiredTargets(...)) return Unavailable`. A hard-leg result
  meets every required target by construction (solver enforced `actual ≥ target`), so the scorer's divergent
  percent-rounding can no longer deny it a deserved badge; a soft-leg result still goes through the strict scorer
  check. This is the doc's "more principled — compare against the solver's own value" option. **Test:**
  `hard-leg max-damage results carry the hard-constraints-met provenance flag` (reachable ⇒ true, unreachable ⇒
  soft-leg ⇒ false). The existing required-target `proveOptimality` locks still pass (reachable→flag makes no
  difference since the scorer already agreed; unreachable→soft-leg→flag false→strict check→Unavailable, unchanged).
- **Verdict:** **CONFIRMED as a real divergence, NARROWER than stated — HP is the only affected required target.**
  - **(1) hard constraint uses solver `tPercent` math — CONFIRMED.**
    `addRequiredTargetHardConstraints` (`StatBuilder.kt:1564-1569`) posts
    `addGreaterOrEqual(requiredActualStat(char), target)`; for a percent-affected stat that folds through
    `requiredActualStat` (`:1543`) → `actualStat` (`:1789`) which applies `tPercent` (`:698-758`, reified
    integer `value + round(value·percent/100)`).
  - **(2) `fullyMeetsRequiredTargets` uses scorer math — CONFIRMED.** `MaxDamageSearch.kt:373-386` calls
    `computeCharacteristicsValues` → `FindClosestBuildFromInputScoring.kt:320-321`
    (`(value + value*(percent/100.0)).roundToInt()`, a **floating-point** whole-expression round — a
    different rounding path). The desired-vs-achieved GUI grid uses the same `computeCharacteristicsValues`
    output, so a solver-met build can display `target-1`.
  - **(3) real but narrow — CONFIRMED/REFINED.** `isRequiredMostMasteriesTarget()`
    (`ScoreComputationMode.kt:73`) covers AP/MP/range/crit/HP/resistances/lock/dodge/… but the percent map
    is fed only by skills with `unitType == PERCENT`. Only **`Characteristic.HP`** has a required-target
    stat backed by a percent skill (Intelligence `% HP` +4%/pt, `Intelligence.kt:60-63`). **Resistance is
    a FLAT chain** (its Intelligence skill is `UnitType.FIXED`, `:72`) — so the ticket's "resistance %"
    example is wrong; AP/MP/range/crit are flat too. **HP is the sole divergent required target.**
- **Implementable — YES; use the DISPLAY-SIDE tolerance (the hard-leg `+1` option is UNSAFE).**
  - ❌ *Enforce `actual ≥ target+1` on percent chains in the hard leg* — **do NOT do this.** It changes
    the **feasible region of the real optimization** (can exclude the true optimum), and doesn't even
    align the engines because the divergence sign isn't fixed (solver may round either side).
  - ✅ *Display/decision-side tolerance* — confine the change to `fullyMeetsRequiredTargets` (and the
    grid's "met" indicator): treat a percent-affected required target (i.e. HP) as met when
    `scorer_value ≥ target − 1`, or — more principled — compare the badge decision against the solver's
    own `requiredActualStat` value rather than re-deriving via `computeCharacteristicsValues`. Touches
    only the badge/display path, never which build is chosen, so it preserves the proven-optimal guarantee.
- **Acceptance:** a search over an HP target with a `% HP` skill allocation at several levels that either
  reproduces the boundary case (then the display-side fix + a regression test) or documents "no repro on
  the real catalog." Do NOT attempt to unify the two rounding engines
  (`docs/code-review-followups.md:327-330` marks that math deliberately irreducible).

### A8 — ✅ DONE (2026-07-06): notes to fold in while touching nearby code (no dedicated PRs)

All three CONFIRMED and SHIPPED. A8.1 is a real behavioral fix; A8.2/A8.3 are wording.

- **A8.1 shipped:** `optimizeHardThenSoft`'s skip predicate now filters `target > 0`
  (`none { isRequiredMostMasteriesTarget() && it.target > 0 }`), matching `addRequiredTargetHardConstraints` — an
  all-non-positive-target request now takes the plain solve directly instead of a constraint-less "hard" leg.
- **A8.2 shipped:** the fallback log now reads "…yielded no build (infeasible or budget too small); falling back…"
  (in the A3 block).
- **A8.3 shipped:** `SolverResult.maxDamageObjective` kdoc now says the value is penalized only for soft-shortfall
  results; hard-constraint-leg results carry the plain (unpenalized) score.

- **A8.1 (behavioral) — CONFIRMED.** `optimizeHardThenSoft` gates on
  `params.targetStats.none { it.characteristic.isRequiredMostMasteriesTarget() }` (`MaxDamageSearch.kt:534`
  — **no `target > 0`**), while `addRequiredTargetHardConstraints` filters
  `isRequiredMostMasteriesTarget() && it.target > 0` (`StatBuilder.kt:1565`) and its `Boolean` return is
  **discarded** by its only caller `buildMaxDamageObjective` (`WakfuBuildSolver.kt:1504-1506`). Consequence
  (verified end-to-end): a request whose required-target stats are all `target ≤ 0` still enters the hard
  leg, which adds **zero** constraints yet uses the **plain unpenalized** `survivableScore` objective —
  and since a satisfiable model returns OPTIMAL/FEASIBLE (`:1807`), `hardYieldedBuild = true` and it
  **never falls to the soft scorer** (which penalizes negative-actual). Fix: make `:534`'s predicate match
  the constraint filter (`none { isRequiredMostMasteriesTarget() && it.target > 0 }`), or thread the
  ignored `Boolean` back so "added nothing" ⇒ take the soft (penalized) path — honoring the documented
  contract at `StatBuilder.kt:1562`.
- **A8.2 (log wording) — CONFIRMED.** The "…model was infeasible…" log (`MaxDamageSearch.kt:545-546`)
  fires on any non-OPTIMAL/FEASIBLE terminal status — including `UNKNOWN` (no solution found in a small
  per-probe budget), not just provably INFEASIBLE. Reword, e.g. "Hard-constraint max-damage model
  yielded no build (infeasible or budget too small); falling back to the soft shortfall penalty."
  (Overlaps A3's principled cure — surfacing `CpSolverStatus` — which would let this message be exact.)
- **A8.3 (kdoc) — CONFIRMED.** `SolverResult.maxDamageObjective` kdoc (`SolverResult.kt:31-33`) states
  unconditionally that required-target objectives are the PENALIZED objective; false since `f2d6994d`
  for **hard-leg** results, which carry the plain `survivableScore` (`WakfuBuildSolver.kt:1506`). Qualify
  the kdoc: hard-constraint-leg results carry the plain (unpenalized) score; only the soft-shortfall path
  is penalized.

---

## B. Certifier performance

Current measured state (verified in code/docs during the review; supersedes older notes):
exact pass ≈ **4.5–5 min/cell** at lvl 245; fast ledger ≈ **68.5 s**; end-to-end badge ≈ **5.7 min
serial at 245**, ≈ 2.75 min at 110. Per-cell×world **parallelism is already implemented, deterministic
and lock-verified** (20× parallel-equality + `threads=4` determinism locks) but **ships disabled**:
`certifierDefaultThreads()` returns 1 because one lvl-245 exact DP holds ~0.7–1 GB of frontier arrays
and a 6-world fan-out OOMs a stock ~4 GB heap.

### B1 — ✅ DONE (2026-07-06): Tier-2 flood control for required-target requests

- **Shipped:** `certifyLedger` — in the `incumbentObjective != null && !forceTier2All` branch — now confirms
  survivors from the LOOSEST `fastObj` down, one at a time, and STOPS (`break`) the exact tier the moment one
  EXACT value exceeds the incumbent (badge already lost ⇒ ProvenWithin). Remaining survivors keep their sound fast
  bound. The oracle/exactness branch (`forceTier2All` or `incumbent == null`) is untouched (still batched
  `exactForCells`), so the parallel-equality + `eliminates cells at or below the incumbent` locks are unaffected.
- **DELIBERATELY DID NOT ship mechanism (b)** (skip exact for survivors whose `fastObj` exceeds the incumbent by a
  MARGIN): it relies on the *measured* fast/exact tightness (1.011–1.019), which is legitimately loose on crit-band /
  coupling shapes — there it could flip a TRUE ProvenOptimal to ProvenWithin (a false-negative badge loss). (a) is
  verdict-preserving because it stops only on a CONFIRMED exact over-count; (b) is not. Documented inline.
- **Verdict preservation:** a ProvenOptimal request (every survivor's exact ≤ incumbent) never breaks ⇒ confirms
  every survivor exactly, identical ledger; only a lost-badge request stops early, its ProvenWithin bound stays a
  sound (slightly looser) global upper bound. Consumer-side ⇒ **no `CERTIFIER_VERSION` bump**.
- **Test:** `max-damage certifyLedger flood-controls the exact tier once the badge is lost` — a deeply-constrained
  incumbent leaves many survivors; asserts ≤ 1 cell is confirmed exactly (vs many for the no-incumbent `full` pass)
  while every cell keeps a sound bound. Mutation-verified (removing the `break` → confirms all → test fails).

<details><summary>Original plan (superseded)</summary>

- **File:** `wakfu/MaxDamageCertifier.kt` (`certifyLedger`, survivor loop ~L381–418).
- **Why:** after `7e6c3253`, a constrained incumbent's raw proxy sits far below the unconstrained
  ceilings, so elimination leaves MANY survivor cells, each paying ~5 min of exact pass — worst-case
  hours for a verdict that is already determined.
- **Mechanism:** (a) process survivors in DESCENDING `fastObj` order and STOP the exact tier the moment
  one exact cell value remains above the incumbent — the badge is mathematically lost; report
  `ProvenWithin` from `max(exact-done, fast-of-remaining)`, which is still a sound global upper bound
  (mixing fast+exact per cell is already the ledger's documented semantics). (b) Skip the exact pass
  for survivors whose `fastObj` exceeds the incumbent by more than a margin (e.g. 5% — measured
  fast/exact tightness is 1.011–1.019), since exact cannot flip those below the incumbent.
- **Soundness:** bounds only ever REPLACED by tighter-or-equal exact values or kept at fast — never
  lowered below a computed bound. Consumer-side orchestration ⇒ **no CERTIFIER_VERSION bump**.
- **Acceptance:** fuzz lock + ledger equality tests green; a constrained-request fixture showing the
  exact tier runs on ≤ 1–2 cells where it previously ran on many; verdicts unchanged on the
  pinned oracles.
</details>

### B2 — ✅ DONE (2026-07-06): Turn the built parallel exact tier ON via a memory-aware default

- **Shipped:** `certifierDefaultThreads()` now delegates to a pure `certifierThreadsForHeap(maxMemoryBytes, cores)`
  = `clamp(floor((maxMemory − 2 GiB) / 1.25 GiB), 1, min(6, cores − 1))`.
- **Deviation from the plan's example formula (reserve 2 GiB, not 1.5 GiB):** the plan's `(mem − 1.5)/1.25` yields
  **2** at `-Xmx4g`, contradicting its own acceptance ("threads must resolve to 1 there"). Reserving 2 GiB makes
  4 GiB → 1 (the safe serial default), ~5.5 GiB → 2, and it scales up from there, capped at `min(6, cores − 1)`.
- **No `CERTIFIER_VERSION` bump** (thread count changes no bound; the merge is an order-independent max, determinism
  already locked). Existing parallel-equality + determinism locks green.
- **Test:** `certifierThreadsForHeap is heap-aware, floors at 1, and respects the core cap` — pins 4 GiB→1, tiny→1,
  8 GiB→4, huge→6, and the `cores − 1` cap (2 cores→1, 4 cores→3, 1 core→1). Pure ⇒ no native dependency.

### B3 — ✅ DONE (2026-07-07, committed `ff0d5054`): Frontier struct-of-arrays footprint shrink

- **Shipped:** `Frontier` now packs its points into ONE geometrically-grown stride-3 `LongArray` (di, graw, mp
  contiguous, no per-point object) instead of `ArrayList<LongArray>` — ~24 bytes/point payload vs ~56–72 before.
  Reads go through an `inline fun forEachPoint` (hot convolution/fold loops allocate nothing) or indexed
  `di(i)/graw(i)/mp(i)`; the diagnostics-only explain path materializes via `toArrays()`.
- **BYTE-IDENTICAL:** `add()` keeps the exact semantics (reject-if-dominated → order-preserving compaction →
  append), so the point sequence every reader sees and the per-stage counts are unchanged. Verified GREEN by the
  full lock set: exactness == CP-SAT, the `fast ≥ exact ≥ CP-SAT` fuzz, parallel == serial, and the CERT_STATS
  point counts. **`CERTIFIER_VERSION` 3 → 4.** (Heap reduction not separately benchmarked — the ~2.5–3× is the
  bytes/point ratio; the byte-identical locks are the correctness guarantee.)

<details><summary>Original plan (superseded)</summary>

- **File:** `wakfu/MaxDamageCertifier.kt` (`Frontier.pts` is `ArrayList<LongArray>` of 3-long arrays,
  ~L25–60).
- **Mechanism:** parallel primitive `LongArray`s (or one packed stride-3 `LongArray`) grown
  geometrically. ~56–72 bytes/point today vs 24 bytes payload ⇒ ~2.5–3× smaller live DP (~1 GB →
  ~350–400 MB at 245), which makes B2's fan-out safe on stock heaps, plus ~1.1–1.3× serial from GC
  pressure.
- **Soundness:** results must be BYTE-IDENTICAL — any divergence is a bug caught by the equality locks
  and the fuzz lock. **CERTIFIER_VERSION bump REQUIRED** (exact/fast pass internals change).
- **Acceptance:** pinned 245 ledger oracle byte-identical; CERT_STATS states/points counts identical;
  measured heap reduction recorded here.
</details>

### B4 — ✅ DONE (2026-07-06, committed `4284eeb8`): Incumbent-independent raw-bound cache

- **Shipped:** `certifyLedger` exposes the raw parts on `CertLedger` (`fastObjectives` + `exactBailedCells`, both
  value-typed so data-class equality is unchanged). `MaxDamageCertificateCache` keys **without** the incumbent and
  stores a mutable `RawEntry` ({fast array, exact-by-cell, exact-bailed cells, shape-bail}); `certificate(incumbent)`
  reconstructs the ledger from the entry by re-running only the elimination arithmetic — no model build, no DP —
  and falls back to a recompute (merging new exacts) only when a survivor it would confirm isn't yet cached.
  Because B1 confirms survivors loosest-fast-first, the first run caches the badge-deciding highest-fast survivor,
  so re-searches are full hits. Reconstruction mirrors `certifyLedger` exactly (survivor order, B1 break, and
  `exactBailedCells` recomputed *for this incumbent*), so a full-hit ledger is byte-identical to a fresh compute.
- **No `CERTIFIER_VERSION` bump** (version stays in the key; no bound values change).
- **Tests:** reconstruction byte-identical to a fresh compute + never invokes the DP (B8 cancel probe polled 0×);
  cache stays 1 entry across incumbents (mutation-verified: disabling reconstruction makes the recompute poll the
  DP, failing the lock). Re-specified the old shape+incumbent memoization test for the incumbent-free key.

<details><summary>Original plan (superseded)</summary>

- **File:** `wakfu/MaxDamageSearch.kt` (`MaxDamageCertificateCache`, ~L600–677).
- **Why:** the cache keys on `incumbentObjective`, so every re-search (duration tweak, restart,
  improved incumbent) misses and recomputes the fast pass + surviving exact cells.
- **Mechanism:** the per-cell fast array and exact `maxPerHit` values are pure functions of
  (model, pool, scenario) — cache `{fastArray, exactByCell}` under the existing normalized key MINUS
  the incumbent; re-run only the trivial elimination arithmetic against the live incumbent; compute
  exact only for survivors not already in `exactByCell`.
- **Soundness:** cached values are the same sound bounds, only reused. **No CERTIFIER_VERSION bump**
  for the restructure itself, but the version MUST be part of the cache key (it already is — keep it).
- **Acceptance:** second identical search in one session gets its verdict near-instantly (test with an
  injected clock or by asserting no exact-pass invocations on the second run); verdicts identical.
</details>

### B5 — ✅ DONE (2026-07-07): Persist the raw-bound cache to disk

- **Shipped:** a new `MaxDamageCertificateDiskCache` (its own file) persists B4's `RawEntry` (`fastObjectives`,
  `bailed`, `exactByCell`, `exactBailed`, `tier15ByCell`) as `sha256(fingerprint).json` under the OS cache dir
  (`~/Library/Caches/WakfuAutobuilder/cert-cache/` on macOS, `%LOCALAPPDATA%` / `$XDG_CACHE_HOME` elsewhere).
  `MaxDamageCertificateCache.certificate` now, on an in-memory miss, loads the record, rebuilds the `RawEntry`
  (`RawEntry.fromRecord`), and reconstructs the ledger — so the SAME request across app restarts gets its badge
  in seconds instead of re-running the multi-minute certifier. Every non-bailed recompute persists the merged
  entry (`RawEntry.toRecord`). B6 synergy: a partial disk hit feeds `precomputedFast` into the recompute, so even
  a not-yet-cached survivor skips the tier-1 fast DP. **No `CERTIFIER_VERSION` bump** (persistence only; version
  is part of the fingerprint/key, so a bump changes the file name and old files are never read).
- **The wrong-badge trap, closed the way the note demanded — WITHOUT the full `@Serializable` graph.** Rather
  than thread `@Serializable` through `Character`/`CharacterSkills`/`TargetStats`/…, the identity is a canonical,
  injective **fingerprint string** (`MaxDamageCertificateCache.fingerprintOf`) that mirrors the in-memory key's
  `equals`: every atom is length-prefixed (prefix-free ⇒ injective), sets/maps are sorted (order-insensitive
  `equals`), lists keep order, and `CharacterSkills` is encoded by its `equals` identity (level +
  `allCharacteristicValues`) — its own `toString` is identity-based, which is exactly why a naive serialization
  was unsafe. The fingerprint includes `WakfuData.VERSION` + `CERTIFIER_VERSION`. The full fingerprint is stored
  INSIDE the file and re-checked byte-for-byte on load (defeats a SHA-256 collision), and the payload is wrapped
  `sha256(body)\n<body>` so any truncation/bit-flip is dropped. On ANY anomaly → null → recompute (always sound).
  Bailed shapes are never persisted. Writes are atomic (temp + `ATOMIC_MOVE`); the dir is capped at 128 files.
- **Disabled by default** (`directory == null`) so tests never touch the real user cache; production enables it
  once at startup via `MaxDamageSearch.enableCertificateDiskCache()` (called from the CLI `main` and the GUI
  `main`). Tests point `directory` at a `Files.createTempDirectory`.
- **Tests (all in `WakfuBuildSolverTest`):** *reconstructs a proof across a cold in-memory cache* (prime →
  restart-by-`clear()` → disk hit reconstructs byte-identical to a fresh compute, B8 probe polled 0×); *round-trips
  a record and rejects a mismatched or corrupted file* (round-trip; different fingerprint misses; corrupt file ⇒
  null); *fingerprint changes with every ledger-affecting field and ignores the normalized ones* (per-field
  injectivity + the 4 normalized fields don't perturb it); *fingerprint covers every request field (tripwire)* —
  reflection pins the exact field set of `WakfuBestBuildParams` (18) + `DamageScenario` (11) so a NEW field fails
  the build until it's encoded in the fingerprint (or normalized away in `keyFor`). Full `./gradlew test` + ktlint
  green.

### B6 — ✅ DONE (2026-07-07, piece 1): Reuse the cached fast ledger on recompute; GUI warm-up deferred

- **Shipped (piece 1 — the load-bearing part):** threaded `precomputedFast: Map<Int,Long>?` + `precomputedBailed`
  through `certifyLedger` ← `StatBuilder` fields ← `buildModel` ← `maxDamageCertificate` ← `MaxDamageCertificateCache`.
  `certifyLedger` uses the raw fast array only for bail-detection + scaling, so the cache feeds back its already-scaled
  `fastObjectives` (+ `bailedCells`) directly — a partial-hit recompute now SKIPS the tier-1 fast DP (~seconds-to-
  minutes) and only re-runs the incumbent-dependent tier-1.5 / exact confirmation. Reuse is a pure, byte-identical
  function of the shape (same key ⇒ same fast bounds), so it never changes a verdict. No `CERTIFIER_VERSION` bump
  (no bound value changes). **Test:** `certifyLedger reuses precomputed fast bounds instead of recomputing them` —
  byte-identical with the shape's own bounds, and PERTURBED bounds flow through (proving the DP is actually skipped,
  not silently recomputed).
- **Deliberately deferred (piece 2 — the GUI warm-up):** kicking `certificate(incumbent = HUGE)` on `Dispatchers.Default`
  after phase 1 to pre-warm the fast entry. Reassessed as MARGINAL given B4+B7+piece-1: B4 already full-hits the common
  re-search (a better incumbent ⇒ fewer survivors ⊆ cached ⇒ no recompute); for the COMMON single-element no-debuff
  request there is only phase 1, so a warm-up would run right before the proof with no overlap to hide behind (the plan
  itself flagged this). The niche multi-phase (Sram/Sadida) overlap win did not justify the added GUI surface + a
  concurrent-native-load risk during the search. Piece 1 already delivers the fast-DP skip whenever a recompute fires.
- **Acceptance:** verdicts identical with the fast bounds reused vs recomputed (locked); no measured regression.

<details><summary>Original plan (superseded)</summary>

- **Files:** `wakfu/MaxDamageSearch.kt` (phase-1 completion), `gui-compose` `BuildSearchModel`
  (proof job start, ~L928–940).
- **Mechanism:** the fast pass is incumbent-independent — kick it on `Dispatchers.Default` (1 thread)
  as soon as phase 1's element/pool is fixed, populating B4's cache so `proveOptimality` finds it hot.
  Schedule after phase 1 so it never competes with CP-SAT's `cores − 1` native workers.
- **Acceptance:** badge latency on a default request drops by ≈ the fast-ledger time; search wall-clock
  unchanged (measure with deterministic_time on the solve, wall-clock only for the badge).
</details>

### B7 — ✅ DONE (2026-07-07): Tier-1.5 sharpened fast pass on survivors

- **Shipped:** `certifyMaxPerHitAtApPass` gained a `fastCSegmentStep` param (default `FAST_C_SEGMENT_STEP = 8`);
  passing `1` makes every c-segment width-1, so the graw fold is EXACT per crit level instead of folded at a coarse
  segment top — a strictly tighter-or-equal, still-sound bound. New `StatBuilder.certifyCellsTier15` runs that
  sharpened pass per survivor cell (with `apTarget` pinned to the cell, so the DP explores only that cell's AP band,
  exactly as the exact pass does), serial or parallel (cell × world) over the warm caches, max over worlds. In
  `certifyLedger`'s incumbent branch it runs BEFORE the exact tier: a survivor whose (tighter) tier-1.5 bound
  already falls ≤ the incumbent is retired on it and SKIPS the ~minutes-per-cell exact DP; the rest fall through to
  B1 flood control. `cellObjectives[a] = exact ?: tier15 ?: fast` (tightest sound bound). **`CERTIFIER_VERSION`
  4 → 5.**
- **Deliberate scope deviation from the plan (safe subset):** shipped tightening (1) — the step-1 c-grid — but NOT
  (2) the ∃-AP-gate pin to `{cell}`. The single-cell call already pins the DP's AP *ceiling* to the cell (via
  `apTarget = cell`); the residual looseness is only that the ∃-gate over non-forced AP-conditional subs still
  ranges `[0, cell]` (a sound OVER-allow, never an under-count). The ∃-gate-per-cell arithmetic was the subtle part
  the plan flagged ("verify it stays an over-count"); step-1 alone already delivers survivor-clearing (locked
  below), so the narrower, higher-risk gate pin is deferred. Sound either way.
- **B4 cache coupling handled:** `CertLedger` gained `tier15Objectives` (incumbent-independent raw parts);
  `MaxDamageCertificateCache.RawEntry` caches `tier15ByCell` (accumulates like `exactByCell`) and `reconstruct`
  mirrors the tier-1.5 clear + B1 early-stop exactly, so a full-hit reconstruction stays byte-identical to a fresh
  compute. The incumbent-free reconstruction lock was re-specified: a null-incumbent cold run does not warm
  tier-1.5 (it is incumbent-survivor-scoped), so the FIRST incumbent-driven proof may recompute (merging into the
  SAME key — no new entry); a REPEAT is a full hit. Two existing behavioral tests (`eliminates cells at or below
  the incumbent`, `flood-controls…`) were re-specified: an un-confirmed survivor now carries its tier-1.5 bound
  (`exact ≤ cellObj ≤ fast`), not the raw fast bound.
- **Guards:** the fuzz lock now asserts `fast ≥ tier1.5 ≥ exact` AND `tier1.5 ≥ CP-SAT` per non-bailed cell (an
  under-count is a release-blocking wrong badge). New coupling-panel tests: `tier-1.5 sits between fast and exact
  on every cell` (with a non-vacuous "strictly tighter than fast on ≥1 cell" check) and `certifyLedger clears a
  survivor via tier-1.5 without the exact pass`. Full `:autobuilder:test` + ktlint green.
- **Not separately benchmarked** (thermal-noisy at scale); the win is the ~seconds-per-cell tier-1.5 retiring
  survivors that would otherwise each pay a ~minutes-per-cell exact DP — asymmetric enough that clearing even a few
  cells pays for itself.

<details><summary>Original plan (superseded)</summary>

- **File:** `wakfu/MaxDamageCertifier.kt` (fast pass c-grid, `FAST_C_SEGMENT_STEP = 8`, exists-gates
  ~L1325–1379; insert between elimination and `exactForCells`).
- **Mechanism:** for the handful of survivor cells, re-run the same fast machinery with c-grid step 1
  and the AP-gate arithmetic pinned to the single cell's `apTarget` (gates become exact). Every
  simplification remains a sound over-count, just tighter — kills survivors without their ~5 min exact
  runs.
- **Soundness:** same structure as the locked fast pass with tighter parameters; keep the
  `fast >= exact` fuzz assertion. **CERTIFIER_VERSION bump REQUIRED** (new pass participates in
  bounds).
- **Acceptance:** lvl-110 survivor set shrinks (measured); ledger oracle relations hold
  (`fast ≥ tier1.5 ≥ exact` per cell).
</details>

### B8 — ✅ DONE (2026-07-06, committed `35c25917`): Cooperative cancellation in the exact DP

- **Shipped:** the certifier polls a cancellation callback once per DP stage (the shared `itemStages` loop, run
  by BOTH the fast and exact passes); a cancelled stage bails with `Long.MAX_VALUE` (sound — fast pass →
  shape-level bail, exact pass → keep fast). Threaded as a `StatBuilder.certifierCancelled` field (from
  `buildModel`) rather than through 8 signatures. `maxDamageCertificate` returns null on cancel so a cancelled
  proof is Unavailable and never caches a partial ledger. `BuildSearchModel` sets an `AtomicBoolean` at every
  proof-cancel site (polled off the parallel tier's pool threads) → `proveMaxDamageOptimality` → `proveOptimality`
  → cache → certifier. No `CERTIFIER_VERSION` bump (a cancelled run yields no ledger).
- **Tests:** cancelled certificate returns null + not cached (same shape certifies/caches normally); the DP polls
  the flag per stage (mutation-verified: short-circuiting the check drops the poll count to ~1, failing the lock).

<details><summary>Original plan (superseded)</summary>

- **Files:** `wakfu/MaxDamageCertifier.kt` (per item-stage loop in the exact pass),
  `wakfu/MaxDamageSearch.kt` (`proveOptimality` consumers), `gui-compose` `BuildSearchModel` (cancels
  `proofJob` at ~L696/965/1232 but the DP doesn't check).
- **Mechanism:** check an `AtomicBoolean` once per DP STAGE (not per state); unwind by returning
  `Long.MAX_VALUE` (bail semantics — always sound) or a `Cancelled` marker; `exactForCells` calls
  `shutdownNow` on its pool; the cache must never store a cancelled/bailed result (it already skips
  bails — keep it). **No CERTIFIER_VERSION bump** if a cancelled run never produces a ledger.
- **Acceptance:** unit test — start a proof, cancel, assert the worker thread exits promptly and no
  cache entry was written.
</details>

---

## C. Solver performance

**All C1–C8 code-verified 2026-07-07** (6 parallel single-item verification agents, each reading the named
source + reporting file:line evidence). Every item's `VERIFIED` block below carries the verdict + refinements.
Headline corrections vs the original specs:
- **C3** — the domination filter is **element-DEPENDENT** (`scenarioMasteryStats`, element-gated sub stats, the
  `minimized` off-elements), so the original "compute once per search" is a **wrong-badge bug**. The sound form is
  a **per-element** memo (still kills most of the redundancy). ⚠️ soundness-critical refinement.
- **C5** — **DROP / defer.** The shipped A3 (soft-leg re-slice) + planned C2 (skip the doomed hard solve) already
  close the budget worst case C5 was for; the residual is a corner case where only first-build *latency* is at
  stake, not worth the concurrency/worker-split machinery.
- **C4** — a **measurement** task whose harness is ~90% built (`timedMaxDamageProfileForTest` + the `@Tag("manual")`
  knob matrix). `useObjectiveShavingSearch` (CP-SAT's dedicated dual-bound closer) is confirmed present in OR-Tools
  9.15 — **run it BEFORE C6**, because a pure knob that closes the `--po 4` dual gap would make C6's medium-risk
  math unnecessary.
- **C6 / C7** — both CONFIRMED, both are the **exact shape of the shipped `dGrawJointBound` / `maxRotRawForElement`
  bounds** (reuse `reachableSumDomain` + self-disabling `min`-over-grid + post-only-if-tighter), both **depend on
  C1**, and **neither needs a `CERTIFIER_VERSION` bump** (model-only objective cuts, never consumed by the certifier).

### Execution status (2026-07-07)

| Item | Status | Notes |
|---|---|---|
| **C1** | ✅ DONE (`8eb21d7c`) | `reachableSumDomain` per-variable aggregation — fixes the +41% Graw over-count. |
| **C2** | ✅ DONE (`8eb21d7c`) | Hard-leg static-infeasibility pre-screen — skips the doomed CP-SAT solve. |
| **C3** | ✅ DONE (`8eb21d7c`) | Per-element domination memo (identity + shape keyed; sound by construction). |
| **C4** | ✅ SETTLED (`26a3f0e8`+) | 5-run A/B incl. deterministic-interleave closer; **verdict: ship NO knob.** 1-worker (reproducible) is byte-identical; multi-worker "wins" flip sign across modes (faster async, same/worse interleave) = worker-race noise. Only 1-worker proof-time A/B is trustworthy here. Full arc in §6. |
| **C5** | ⛔ DROPPED | Superseded by shipped A3 + C2 — see below. |
| **C8 (1)** | ✅ DONE (`d5f220cf`) | Most-masteries reachable objective-product bound (~10³–10⁵× box tightening, sound). |
| **C7** | ⏸️ ATTEMPTED & REVERTED | The exhaustive guard caught an under-count in the crit-clamp AM-GM bound; reverted, deferred. |
| **C6** | ⏸️ DEFERRED | NOT obviated (the `probing3` obviation claim was retracted — worker-race noise); but not worth chasing for one narrow shape the certificate already backstops. |
| **C8 (2)/(3)** | ⏸️ DEFERRED | Param A/B (measurement) / greedy warm-start (blocked). |

C1–C4 delivered the clear, testable wins. C6/C7/C8 are the remaining medium-risk / separate-mode / measurement
work — each block below keeps its full verified approach for a focused follow-up. The original suggested order
was C1 → C2 → C3 → C4 → C7 → C6 → C8; C7's revert showed the bilinear-bound cuts need a rigorously-derived bound
+ a cut-firing exhaustive fixture before they can ship (even OFF).

### C1 — ✅ DONE (2026-07-07): Aggregate same-variable coefficients in `reachableSumDomain` (low-risk, small)

- **Shipped:** one-line pre-pass at the top of `reachableSumDomain` (`StatBuilder.kt`):
  `val terms = terms.groupBy { it.variable }.map { (v, ts) -> Term(v, ts.sumOf { it.coefficient }) }`. No
  `CERTIFIER_VERSION` bump (the certifier never routes the moved var through `reachableSumDomain`). **Test:** added a
  conversion-sublimation (Unraveling) shape to the `maxDamageVarBoundsForTest` soundness panel — asserts the
  tightened Graw/mastery reach stays a sound over-estimate (freely-solved value still fits). Full
  `:autobuilder:test`/`WakfuBuildSolverTest` green (soundness panel + `fast ≥ exact ≥ CP-SAT` fuzz + exhaustive
  optimum panel + Unraveling certifier-match all pass). Det-time not separately benchmarked (host thermal noise);
  the win is the tighter box unblocking sub-heavy proofs, guarded by the soundness locks.

<details><summary>Verified analysis (pre-implementation)</summary>

- **Verdict: CONFIRMED (exact, foundational).** The one conversion sub (Unraveling, stateId 5077, `MASTERY_CRITICAL
  → MASTERY_ELEMENTARY 100%`, `SublimationTerms.kt:28-36`) puts its single `moved` var into `subTermsByStat[to]`
  with `+1` and `subTermsByStat[from]` with `−1`. When those lists are concatenated (`computeDamagePreMasteryTerms`
  `StatBuilder.kt:1463-1469`; `maxPerHitProductBound`/`maxRotRawForElement` `DamageObjective.kt:503-506`,
  `damageMasteryCriticalReach` `StatBuilder.kt:1493-1501`) the `else` branch of `reachableSumDomain`
  (`StatBuilder.kt:214-217`) folds each term independently — banking `+masteryCoef·H` and `0` instead of the
  aggregated `(masteryCoef−critCoef)·moved`. Sound (an **over-count**, never under) but the loose bound blocks
  sub-heavy proofs. All weights positive at every site, so the inflation direction is consistent.
- **Fix (one line, single point):** at the top of `reachableSumDomain` (before the fold loop, ~`StatBuilder.kt:191`):
  `val terms = terms.groupBy { it.variable }.map { (v, ts) -> Term(v, ts.sumOf { it.coefficient }) }`. Exact interval
  arithmetic (`Σ coefᵢ·v = (Σ coefᵢ)·v` pointwise ⇒ reachable set unchanged, declared box tightens); a no-op for
  non-repeated variables; `scaledRange` already handles the negative-aggregate endpoint flip, so both `hi` and the
  negative-charging `lo` tighten soundly.
- **No `CERTIFIER_VERSION` bump — VERIFIED.** `reachableSumDomain` has NO call site in `MaxDamageCertifier.kt`; the
  certifier drops the moved var (`dropMoved`, filters `it.variable !in conversionMovedVars`) and models the
  conversion analytically (`convGain`/`convResidual`, exact at 100%), so it never routes `moved` through
  `reachableSumDomain` and is untouched.
- **Verify:** existing reachable-bound soundness locks + the `fast ≥ exact ≥ CP-SAT` fuzz stay green (the fix only
  tightens a sound over-count); then measure det-time (invariant 5) on the lvl-245 subs-on free solve and the
  lvl-110 fixture, record before/after here.
</details>

### C2 — ✅ DONE (2026-07-07): Hard-leg infeasibility pre-screen from tracked reaches (none-risk, small)

- **Shipped:** `addRequiredTargetHardConstraints` (`StatBuilder.kt`) now returns `staticallyInfeasible` — true iff a
  required target exceeds `tracker.of(actual).last` (a sound over-estimate) — computed in the same loop that posts
  the `actual ≥ target` constraints. Threaded `MaxDamageObjectiveVars.staticallyInfeasible` → `BuiltModel.maxDamageStaticallyInfeasible`
  → `optimize`'s `callbackFlow`, which now does `if (built.maxDamageStaticallyInfeasible) { close(); return@launch }`
  BEFORE `executeSolverAndEmitResults` — so the doomed CP-SAT solve never runs and the hard leg returns empty
  instantly; `optimizeHardThenSoft` (unchanged) falls to the soft leg exactly as after a CP-SAT INFEASIBLE.
  Composes with A3 (soft leg gets the leftover budget) and A5 (AP-probe skip). Aggregate `RESISTANCE_ELEMENTARY`
  min-var stays inert (untracked ⇒ loose fallback ⇒ sound but never flags), as designed. **Test:** `C2 hard-leg
  pre-screen flags an unreachable required target, not a reachable one` (new `maxDamageStaticallyInfeasibleForTest`
  seam, model-build only: AP 7 reachable ⇒ false, AP 99 ⇒ true). Full `WakfuBuildSolverTest` green.

<details><summary>Verified analysis (pre-implementation)</summary>

- **Verdict: CONFIRMED (sound + implementable).** `DomainTracker` (`StatBuilder.kt:36-86`) records each var's
  reachable `[lo,hi]`; `tracker.of(v).last` is the upper bound. Required-target vars route through tracked
  `tSum`/`tPercent` (`requiredActualStat` → `actualStat`, `StatBuilder.kt:1556/1802/1810`; both call
  `tracker.record`). `record` stores `reach` **regardless of `tight`**, so the bound is available at model-BUILD
  time (no solve). `reach.last` is a documented **sound over-estimate** (`StatBuilder.kt:196-197`, `:303-307`; the
  out-of-combat AP cap only lowers the true max further), so `target > reach.last ⇒ genuinely infeasible` —
  one-directional, never a false infeasible that skips a feasible hard leg.
- **Adds over shipped A3/A5:** A3 only *shortens the aftermath* (the soft leg gets the leftover budget); C2 skips
  the **doomed hard CP-SAT solve entirely** (turns a full-phase-budget INFEASIBLE proof into an O(model-build)
  static check). Composes cleanly — `optimizeHardThenSoft` already treats "hard leg emitted nothing" as the
  fallback trigger, so C2 just makes that happen instantly.
- **Plumbing (thread one Boolean, no `optimizeHardThenSoft` change):** (1) `addRequiredTargetHardConstraints`
  (`StatBuilder.kt:1577`) computes `staticallyInfeasible = requiredTargets.any { it.target > tracker.of(requiredActualStat(it.characteristic)).last }`;
  (2) surface it on `MaxDamageObjectiveVars` → (3) `BuiltModel` → (4) in `optimize`'s `callbackFlow`, if
  `built.staticallyInfeasible` then `close()` (emit nothing) **instead of** `executeSolverAndEmitResults`. The hard
  leg returns empty ⇒ A3's soft fallback fires as today.
- **Caveat (sound, narrows scope):** the aggregate `RESISTANCE_ELEMENTARY` target builds its `min`-of-four var
  WITHOUT `tracker.record`, so `tracker.of(it).last` returns the loose `STAT_ABS_MAX` fallback ⇒ that target is
  sound-but-inert (never pre-screened). AP/MP/range/crit (the stats users actually over-request) ARE tightly
  tracked and fire. To also pre-screen the aggregate, compute `ELEMENTARY_RESISTANCES.minOf { tracker.of(elementResistanceVars[it]).last }`.
- **Acceptance:** unit test — unreachable target ⇒ no hard solve invoked (count solves via a test seam), soft
  result identical to today; reachable-but-binding target ⇒ hard leg still runs (A2's binding-target tests keep
  passing).
</details>

### C3 — ✅ DONE (2026-07-07): Memoize the domination filter PER ELEMENT (low-risk, small)

- **Shipped:** a self-contained `filterDominatedPoolMemoized(basePool, shape)` in `WakfuBuildSolver` — an
  `IdentityHashMap` keyed on the basePool **object identity** (MaxDamageSearch threads the SAME pool through every
  probe; identity can never serve a wrong pool) with an inner `ConcurrentHashMap` keyed on the `DominationShape`
  **value** (which encodes the scored element via `compared`/`minimized`, so the memo is automatically PER-ELEMENT —
  the exact soundness trap the verification flagged). Bounded (`DOMINATION_MEMO_MAX_POOLS = 8`, cleared past the cap)
  so it can't grow across sessions; concurrent same-key computes are idempotent (pure filter). Collapses one
  search's ~12–20 filter runs to ~4 (one per candidate element), reused across each element's hard/soft legs + AP
  probes. Transparent — returns the identical pool, so no optimum changes. **Test:** `C3 domination memo is
  per-element (fire and water shapes filter the same pool differently)` — asserts fire/water shapes filter the same
  pool to DIFFERENT pools (never collapsed) and each matches the direct un-memoized filter. Full
  `WakfuBuildSolverTest` + `:gui-compose:test` green. (A prefiltered basePool is a fresh object each call ⇒ identity
  miss ⇒ recompute, correct but no win — fine, the multi-element-resistance prefilter case is rare.)

<details><summary>Verified analysis (pre-implementation)</summary>

- **Verdict: CONFIRMED redundancy, but the original "compute once per search / element-independent" premise is
  REFUTED — the filter is element-DEPENDENT.** `buildModel` re-runs `dominationShape` + `filterDominatedPool` (a
  per-slot O(size²) pairwise scan) on every invocation (`WakfuBuildSolver.kt:589-591`), and a boss search invokes
  it ~12–20× (up to 4 element probes + up to 6 AP probes, each ×(hard+soft) legs). BUT the max-damage `compared`
  set reads `params.damageScenario.element` three ways: `scenarioMasteryStats(scenario)` (the scored element's
  mastery, `DominationFilter.kt:138`), element-gated sub `StatEffect`s (`scenarioGateMatchesCore` gates on
  `element.name`, `:67-70`), and the `minimized` off-elements (`ELEMENT_MASTERY_CHARACTERISTICS − element.mastery`,
  `:191-195`). **Sharing one filtered pool across the 4 element probes would use the wrong dominance relation for 3
  of them ⇒ prune the true per-element optimum ⇒ wrong "proven optimal" badge or false INFEASIBLE** (the exact
  A1-class hazard).
- **Sound fix = PER-ELEMENT memo.** Compute `dominationShape` + `filterDominatedPool` once per distinct element
  param (hoist to `MaxDamageSearch` where `elementParams` is built, `~L116-129`), thread the pre-filtered pool
  into `streamProbeBatch`/`runProbeBatch`/`optimizeHardThenSoft` → `optimize` → `buildModel` (add a
  "pool-already-filtered" seam so `buildModel` skips its filter). `hardConstraints` is NOT read by the filter, so
  one entry serves both legs; the Phase-2 AP probes all pin the winner element, so all ~6 reuse one entry. Net:
  ~12–20 filter runs → ~4 (one per candidate element), soundly. Simplest: a `ConcurrentHashMap<key, pool>` per
  search request (the functions are already pure; parallel probe collectors share it safely).
- **Memo key (everything the filter reads):** the full `DamageScenario` (`element` + `rangeBand` + `orientation` +
  `berserk` + `healing` + `survivabilityFloor` + `minEffectiveHp`) + `targetStats` (the A1 dependency) +
  `scoreComputationMode` + `useSublimations` + forced items/runes/subs + the sorted pool ids + sublimation
  stateIds + `WakfuData.VERSION`. (≈ the certifier's existing `shapeKey` minus its certifier-only tokens.)
- **Acceptance:** identical pools/optima on the existing deterministic tests; a test-seam counter shows one filter
  run per distinct element (not per probe/leg); a boss (multi-element) fixture still proves the same per-element
  optima (guards against accidental cross-element sharing).
</details>

### C4 — ✅ SETTLED (2026-07-07): Parameter A/B on the knob seams — ship NO knob

- **FINAL (5 runs incl. the deterministic-interleave closer): no solver-parameter knob has a robust, reproducible
  benefit for max-damage — ship none. Do not revisit without new evidence.** The decisive facts: the only fully
  reproducible measurement (1 worker) is byte-identical `baseline`=`probing3`; the multi-worker "wins" are
  mutually contradictory across parallel modes (probing3 ~2.4× *faster* async, *identical* 1-worker, *same-or-worse*
  under `interleaveSearch`) ⇒ a worker-race artifact, not a real effect; the variance is irreducible (~10×) and
  even interleave doesn't remove it. Corollary: a solver-knob **proof-time** A/B is only trustworthy at **1 worker**
  on this codebase — chase bound/algorithm changes, not multi-worker knobs. `SOLVER_PERFORMANCE.md` §6 = full arc.

<details><summary>Earlier partial account (superseded by §6 FINAL VERDICT)</summary>

- **Measurement — 4 runs, verdict: do NOT ship any knob (`SOLVER_PERFORMANCE.md` §6 has the full arc).**
  (1) 8-worker WALL-capped single run *appeared* to show `cpModelProbingLevel=3` proving the constrained shape →
  wrong (wall noise). (2) 1-worker det-sweep: `baseline` = `probing3` = `objShaving` byte-for-byte (knobs inert for
  the single subsolver). (3) 8-worker det, 3 repeats, constrained: `probing3` looked ~2.4× faster (3/3,
  non-overlapping). (4) 8-worker det, 3 repeats, FREE solve: `probing3` = `baseline` in MEAN (~952 vs ~956) but
  with **~10× run-to-run variance** and a worse-than-baseline tail spike. **Conclusion: 8-worker CP-SAT proof-time
  is variance-DOMINATED — 3 samples can't confirm the constrained gain, and `probing3` adds free-solve tail
  latency, so it fails "≥2 wins, no regression." `objShaving` clearly regresses.** No knob shipped. The reusable
  harness: `WAKFU_MAX_DAMAGE_DETTIME=<sweep>`, `_WORKERS`, `_VARIANTS`, `_REPEATS`, `_CONSTRAINED`, `_OUT=<file>`
  (incremental output); force re-execution with `--rerun-tasks` (Gradle caches the manual test — env vars aren't
  tracked inputs). Lessons banked in §6: test at BOTH worker counts, and small-N multi-worker A/B is unreliable.


- **Shipped (harness):** `timedMaxDamageProfileForTest` gained a `hardConstraints` param, and the `@Tag("manual")`
  `manual max-damage solver-parameter matrix` test gained a **`sharedTree4`** variant and an opt-in **constrained
  hard-leg fixture** (`--pa 11 --pm 4 --po 4`, `WAKFU_MAX_DAMAGE_CONSTRAINED=1`) — the two genuine gaps the
  verification found. Every candidate knob (incl. `useObjectiveShavingSearch`, verified present in OR-Tools 9.15) is
  wired. Run procedure + the C6-gating binary question (does `objShaving` flip the constrained shape to OPTIMAL?)
  documented in `docs/SOLVER_PERFORMANCE.md` §6.
- **Measurement NOT run here (deliberate):** this dev host's `deterministic_time` swings ~2.3× under thermal
  throttling (`SOLVER_PERFORMANCE.md` §0), so any A/B verdict would be unreliable. The harness is ready for a
  thermally-stable machine; C6 is implemented regardless (self-disabling ⇒ harmless even if a knob also closes the
  gap). Results table to be appended to `docs/SOLVER_PERFORMANCE.md` §6 when run.

<details><summary>Verified analysis (pre-implementation)</summary>

- **Verdict: CONFIRMED, and the harness is ~90% built.** `timedMaxDamageProfileForTest` (`WakfuBuildSolver.kt:772-875`)
  assembles the identical production model via `buildModel`, sets arbitrary `SatParameters` on a fresh `CpSolver`,
  and returns a `MaxDamageTimedProfile` carrying `deterministicTime` + `status` + `objective` + `branches`. All the
  candidate knobs are wired as pure `solver.parameters.*` mutations (zero model change ⇒ CP-SAT proves the same
  optimum, soundness-safe by construction, per the seam's own kdoc). A `@Tag("manual")` env-gated runner
  `manual max-damage solver-parameter matrix` (`WakfuBuildSolverTest.kt:3878-3947`) ALREADY screens `objShaving`,
  `symmetry3`, `probing3`, `presolve6`, `portfolio*` variants.
- **`useObjectiveShavingSearch` is REAL in OR-Tools 9.15** (verified in-jar: `USE_OBJECTIVE_SHAVING_SEARCH_FIELD_NUMBER`
  + getters, alongside `SHAVING_SEARCH_DETERMINISTIC_TIME`/`_THRESHOLD`, `VARIABLES_SHAVING_LEVEL`). It is CP-SAT's
  dedicated dual-bound closer — **exactly** the `--po 4` failure mode C6 targets, which is why C4 runs first: if this
  knob closes the constrained dual gap cheaply, **C6 becomes unnecessary**.
- **Remaining work (measurement, not shipped code):** (a) add `sharedTreeNumWorkers` + extra/ignore-subsolver-list
  variants and the **constrained hard-leg fixture** (`--pa 11 --pm 4` shape — the existing matrix uses free solves
  only, so the constrained shape is genuinely unscreened); (b) run with MANY averaged repeats (`WAKFU_MAX_DAMAGE_REPEATS`)
  — ⚠️ this host's `deterministic_time` swings ~2.3× back-to-back under thermal throttling (`SOLVER_PERFORMANCE.md`
  §0), so treat sub-2× deltas as noise; (c) append the results table to `docs/SOLVER_PERFORMANCE.md`.
- **Ship a knob ONLY if it wins on ≥ 2 fixtures and regresses none**, gated to the max-damage production path
  (`deterministicMaxDamageSolver` / `executeSolverAndEmitResults`, currently only `linearizationLevel=2`).
- **Acceptance:** results table appended to `docs/SOLVER_PERFORMANCE.md` (win OR lose — negative results prevent
  re-testing); any shipped knob gated + a deterministic optimum-unchanged lock.
</details>
</details>

### C5 — ⛔ DROPPED (2026-07-07): Concurrent hard + soft legs with split workers

- **Verdict: DROP / defer — superseded by the shipped A3 + planned C2.** A3 (soft-leg re-slice, committed
  `c8c0452a`) already caps the two-leg total at ~1× `searchDuration`; C2 (above) skips the doomed hard solve
  entirely for statically-infeasible targets. The ONLY case C5 would still help is a hard leg that is
  statically-feasible (passes C2) yet finds **zero** incumbents within its slice and ends empty — a corner of a
  corner (CP-SAT finds *an* incumbent fast; max-damage is bound by closing the dual gap, not finding a feasible
  build — `SOLVER_PERFORMANCE.md:183`). Even then A3 already bounds the wasted time; only first-build *latency*
  is at stake. Not worth C5's buffer/cancel/replay + worker-split machinery (splitting workers also *reduces* the
  hard leg's portfolio diversity and can slow its proof). The doc's own gate ("only if the re-slice still leaves a
  visible worst case") is not met by analysis.
- **Revisit ONLY IF** profiling on the real constrained fixture (`--pa 11 --pm 4`) shows a feasible hard leg
  actually timing out empty with a user-visible late soft start. Nothing in current evidence suggests it would pay
  off. (Concurrency invariant, if ever revived: `concurrency × workersPerProbe ≤ host`, enforced in `probePlan`
  `MaxDamageSearch.kt:641-651`, locked by `MaxDamageSearchTest.kt:130-142`.)

### C6 — ⏸️ DEFERRED (2026-07-07): Target-aware (Lagrangian) joint bounds for the hard leg

> **Status: not started. (Earlier "obviated by `probing3`" claim RETRACTED — that rested on a single 8-worker run
> that turned out to be worker-race noise.)** The reproducible **1-worker det-sweep** (`SOLVER_PERFORMANCE.md` §6)
> shows `baseline` = `probing3` = `objShaving` byte-for-byte at every det checkpoint — **no cheap sound knob
> reliably closes this shape's dual gap.** So C6 is NOT obviated. BUT it's also **not worth chasing right now**:
> the shape's incumbent reaches the true optimum early (det≈400) and only the DUAL BOUND lags (~22% high at
> det=800, descending slowly) — and production (8 workers) + the AP-cell certificate + the "proven within X%"
> badge already handle a shape CP-SAT doesn't close in-budget. If this ONE narrow shape ever must show a CP-SAT
> "proven optimal", C6 is the option — but it carries the same bilinear-`reachableSumDomain` soundness traps C7
> hit, so it needs a rigorously-derived bound + a binding-target exhaustive fixture. Full approach verified below.

- **Verdict: CONFIRMED (sound by weak duality; same shape as a SHIPPED bound).** The hard leg
  (`buildMaxDamageObjective(hardConstraints=true)`, `WakfuBuildSolver.kt:1607-1610`) proves the PLAIN
  `grawLin`-derived damage score, which a joint bound can tighten. The already-shipped `maxRotRawForElement`
  (`DamageObjective.kt:437`) is **this exact Lagrangian `min`-over-grid pattern with the sign flipped** (it does
  `min_μ reachableSumDomain(grawLinTerms − μ·apTerms).last + μ·a`); C6 adds `+λ·targetTerms`, subtracts `λ·T`.
  `grawLinTerms` and the required-target terms (`prePercentTermsFor(char)`, `StatBuilder.kt:1846`) are both
  already extractable, and `reachableSumDomain` accepts the combined list (slot-aware ⇒ ring/rarity/sub coupling
  free). For any `λ ≥ 0`, `actual_t ≥ T_t ⇒ grawLin ≤ reachableSumDomain(grawLinTerms + Σλ_t·targetTerms).last − Σλ_t·T_t`
  (textbook weak duality); `min` over the grid stays an upper bound.
- **Depends on C1** (correctly flagged): C6 calls `reachableSumDomain` on a combined list where a target term and
  a grawLin term can share a variable — without C1's aggregation the bound is loose (still sound, but the
  self-disabling `min` then silently never fires). **Do C1 first.**
- **Soundness guard unique to C6:** BAIL (return no cut — always sound) for any target stat whose `actual_t` has a
  non-empty percent-skill map — notably **HP** (the one percent-affected required target, see A7) — because a
  percent chain breaks the slot decomposition, exactly as `maxPerHitProductBound` already bails on percent crit/DI
  (`DamageObjective.kt:492-493`). Self-disabling (post only if strictly `<` the declared box) mandatory.
- **No `CERTIFIER_VERSION` bump — VERIFIED** (`maxPerHitProductBound`/`maxRotRawForElement` are consumed only in
  `DamageObjective.kt`, never in `MaxDamageCertifier.kt`; C6 is a model-only objective cut, same as `dGrawJointBound`).
- **Test:** extend `max-damage experiment variants reach the exhaustive optimum on a small pool`
  (`WakfuBuildSolverTest.kt:1281`) with a NEW **binding-target + `hardConstraints=true`** variant asserting
  `≥` the exhaustive constrained optimum (model it on the `binding AP target` / `aggregate resistance` tests at
  `:6818`/`:6873`); add a "cut fired" assertion to dodge the vacuous-lock trap. **If C4 already closed the `--po 4`
  gap, skip C6** and record that here.
- **Acceptance:** the exhaustive-panel variant green with the cut provably active; measured dual-gap reduction on
  the `--po 4` repro recorded here (det-time protocol). If no measurable gain, record the negative result + remove.

### C7 — ✅ CLOSED 2026-07-09 (re-implemented sound from the staged derivation; MEASURED-INERT on the flagship — stays OFF): AM-GM joint cut for the inner crit×diff product

- **✅ RE-IMPLEMENTED 2026-07-09 exactly per the staged derivation below — this time SOUND.**
  `StatBuilder.critDiffJointReachHi(scenario, μ)` extends `damageMasteryCriticalReach` with the third
  weighted axis (raw `CRITICAL_HIT` pre-percent terms at weight μ) and carries **all three** lower-clamp
  slacks (crit at weight μ, mastery at 1, critical-mastery at 5 — the E2 `conversionMovedVars` exclusion
  applied to each); `DamageObjective.maxCritDiffProductBound` takes
  `C(μ) = min(jointReachHi(μ), μ·critCap + diffHi)` over a wide geometric μ grid around `diffHi/critCap`
  and caps `term = crit·diff` by `min_μ C(μ)²/(4μ)` (exact integers via `clampedProductQuotient`);
  applied in `perHitDamageScore` behind `critDiffJointBound` (OFF in DEFAULT), single-element only,
  self-disabling vs `tracker.of(term).last`. **Guards green, in the staged order:** (1) the engineered
  competing-pool firing fixture (crit-vs-mastery amulet clash + a negative-crit ring exercising the crit
  clamp slack + `critCapPercent = 40`) asserts the cut FIRES (via the `maxDamageCritDiffCutBoundForTest`
  seam) **and** CP-SAT still reaches the exhaustive optimum — the assertion that caught the 2026-07-07
  under-count now passes with the cut provably active; (2) the exhaustive panel (`crit-diff-joint`,
  `crit-diff+joint` variants); (3) the DEFAULT-off lock.
- **❌ MEASURED-INERT on the flagship shapes (deterministic F2 harness A/B, 1w+interleave, quiet machine).**
  lvl-245 free runes+subs: baseline vs critDiff **byte-identical** at det 200/400/800 (obj 15.82M/16.55M/16.81M,
  bound 25.95M/25.87M/23.29M — identical to the digit); lvl-110 free likewise. The debug line shows why:
  `jointU == independent, ratio = 1.0000` at both levels — the AM-GM min never beats the box, so the
  self-disable never adds the constraint. **Structural, not a tuning miss:** at real levels raw crit
  SATURATES any cap (base 3 + abundant crit gear ≫ 100) without sacrificing mastery slots, so the
  independent corner `(critCap, diffHi)` is jointly achievable and NO constant cap on `term` can be
  tighter. The residual crit×diff dual-gap looseness is the LP's **interior fractional pairing** (crit·diff
  vs the linear cuts between the corners), not the box corner — a constant bound cannot address it, and E9
  already established crit is the SMALL factor of the product. The cut fires only on scarce-crit pools
  (crit competing for the cap below saturation), i.e. engineered/low-level shapes — not production ones.
- **Disposition: the implementation STAYS, flag OFF in DEFAULT** (like `critBandDisjunction`): it is sound,
  triple-locked, zero-cost when inert (self-disabled ⇒ byte-identical model), and the honest completed
  answer to C7. Do NOT flip it on without a shape where the debug ratio drops below 1.0 AND a trajectory
  win under the canonical protocol. `WAKFU_MAX_DAMAGE_DEBUG_CRITDIFF=1` prints the ratio; the harness
  registry has the `critDiff` variant.

<details><summary>First attempt (2026-07-07, reverted) + the staged derivation the re-attempt followed</summary>

- **Attempted, then reverted — the exhaustive-optimum guard caught a real UNDER-COUNT.** Implemented
  `maxCritDiffProductBound` (mirroring the shipped `maxPerHitProductBound`) behind a `critDiffJointBound` flag,
  gated OFF in DEFAULT, with a dedicated firing+soundness test on an engineered competing pool (high-crit vs
  high-mastery amulet + a negative-crit ring). The cut FIRED as intended, but the soundness assertion failed
  (`solverBest 33.8 < exhaustive 39.0`) — my hand-derived crit-clamp slack under-counted `max(μ·crit_clamped +
  diff_clamped)` on that pool, so `C(μ)²/(4μ)` capped `term` below the true optimum. Reverted cleanly (suite green).
- **Lesson:** the inner `crit·diff` clamp interaction is genuinely subtler than the outer `D·Graw` case — the crit
  var is `clamp(raw, 0, critCap)` clamped on BOTH ends AND competes with the diff terms, and the `damageMasteryCriticalReach`
  clampSlack idiom does not transfer verbatim. **The exhaustive panel is exactly the guard that must catch this**
  (it did). Re-attempt only with a rigorously-derived, proof-checked bound + the cut-firing exhaustive fixture; do
  NOT ship without the panel passing WITH the cut provably firing. Lower priority than its uncertain incremental
  value on top of the already-shipped `dGrawJointBound` justifies.
- **Staged derivation for the re-attempt (2026-07-08 — written down so a fresh session starts from proof, not
  memory).** Both factors hide LOWER clamps, and BOTH must carry slack:
  - `crit = clamp(rawCrit, 0, critCap)`. For any build, `crit ≤ max(0, rawCrit) = rawCrit + max(0, −rawCrit)`, so
    `μ·crit + diff ≤ [μ·rawCrit + diff] + μ·max(0, −rawCritLo)` where `rawCritLo` is a sound LOWER bound of the
    raw crit reach (the slack is the exact `damageMasteryCriticalReach` clampSlack idiom, applied to crit at
    weight μ).
  - `diff = m + 5·K` with `m = clamp(preM, 0, MAX)` and `K = clamp(preK, 0, MAX)` — ALSO lower-clamped, so the
    joint hi of `μ·rawCrit + diff` must come from `damageMasteryCriticalReach` extended with a THIRD weighted axis
    (`criticalHitWeight = μ` folding the raw `CRITICAL_HIT` pre-percent terms + its own clamp slack), NOT from a
    naive `reachableSumDomain` over raw term lists (that was the reverted under-count: it priced the mastery/critM
    lower clamps at zero).
  - `C(μ) = min( reachExtended(μ) + μ·max(0, −rawCritLo), μ·critCap + diffHi )`; both components sound ⇒ min
    sound. Bound = `min over μ-grid of clampedProductQuotient(C(μ), C(μ), 4μ, box)` (exact integers — the Double
    trap is documented at `maxPerHitProductBound`), μ geometric grid around `diffHi / critCap` (the C6 lesson:
    WIDE grid, a single centre whiffs). Self-disabling vs `tracker.of(term).last`; behind `critDiffJointBound`
    (OFF) until the A/B verdict.
  - Guards, in order: (1) the engineered competing-pool fixture WITH negative-crit gear + `critCapPercent < 100`
    asserting the cut FIRES **and** exhaustive == CP-SAT; (2) the full exhaustive panel; (3) the deterministic
    harness A/B on F2 (the 245 dual gap IS the crit×diff relaxation — this is the one open lever there).

<details><summary>Verified analysis (pre-attempt)</summary>

- **Verdict: CONFIRMED, incl. the clamp subtlety.** In `perHitDamageScore` (`DamageObjective.kt:293-326`) the
  inner product is `crit · diff` where `crit = tClamp(actualStat(CRITICAL_HIT), 0, critCap)` and
  `diff = m + 5·criticalMastery` (both non-negative). The AM-GM cut `term ≤ min_μ C(μ)²/(4μ)` with
  `C(μ) = reachableSumDomain(μ·critTermsPos + diffTerms).last` is the exact shape of the shipped `maxPerHitProductBound`
  (`DamageObjective.kt:490`), self-disabling, `reachableSumDomain`-based.
- **The flagged clamp subtlety is REAL and has an in-tree fix.** Base `CRITICAL_HIT = 3` but items/subs carry
  negative crit, so `raw` crit can be negative and `crit = clamp(raw,0,critCap) ≥ raw`; computing `C(μ)` on RAW
  crit terms under-estimates (a negative `raw_crit` inflates `diff`'s room while the true `term` uses
  `crit_clamped=0`). Mitigation = **non-negative crit contributions** (drop negative-coef gear / add a clamp
  offset). The exact technique already exists: `damageMasteryCriticalReach` (`StatBuilder.kt:1483`) adds a
  `clampSlack = weight·−min(0, reach.first)` offset (`:1500`) — reuse it. Also use `clampedProductQuotient(C, C, 4·μ, best)`
  for the exact integer `C²/(4μ)` (a `Double C*C` loses precision past 2^53 and could round the bound BELOW the
  true max — the same trap `maxPerHitProductBound` warns about at `:522`).
- **Depends on C1** (title "ONLY after C1"): same shared-variable-across-lists reason as C6.
- **No `CERTIFIER_VERSION` bump — VERIFIED** (model-only objective cut).
- **Test:** same exhaustive panel (`:1281`) with a NEW fixture containing **negative-crit gear** (to exercise the
  clamp) AND a `critCapPercent` well below 100 (so the cut bites); assert the cut fired.
- **Acceptance:** panel green with the cut provably active; measured det-time on crit-capped/sub-heavy fixtures.
</details>

</details>

### C8 — ✅ SUB-STEP (1) DONE (2026-07-07); (2)/(3) deferred: Most-masteries speedups

- **(1) Reachable objective-product bound — SHIPPED.** `diAdjustedPerElementMasteryScore` now returns a **sound,
  generous reachable ceiling `coreHi`** alongside the score var and declares the score var on `[0, coreHi]` (was
  the loose `[0, MASTERY_SCORE_ABS_MAX]` = 1e8); `buildMostMasteriesObjective` passes `coreHi` as the product-box
  bound to `applyConstraintPenalty`, tightening the `objective = coreScore × multiplier` McCormick envelope on the
  required-target path. `coreHi` = `Σ each requested mastery's tracked reach × (100 + DAMAGE_DI_MAX) / 100` (branch
  A) / the per-element `min` of `(nonElem + element)` reach × the DI factor (branch B), clamped to the cap — a
  **deliberate over-estimate** (ignores slot competition + the negative-mastery penalty; an untracked component
  falls back to the loose guard ⇒ worst case is no tightening, never an under-count).
  - **The `scaledWeight`-negative trap did NOT apply here:** `diAdjustedPerElementMasteryScore` uses raw masteries,
    not per-target weights, so the reach computation never multiplies by `scaledWeight`. (The sign trap the note
    warned about lives in `totalActualScore`, a different var not touched here.)
  - **Sound + non-vacuous, verified:** all the existing exhaustive most-masteries optimum tests
    (`allValidCombinations().maxOf { FindMostMasteriesFromInputScoring.computeScore }` vs the solver) stay green —
    an under-count would cap the objective below the brute-force best (the same guard that caught C7). A debug pass
    confirmed `coreHi` lands at ~400–140,000 vs the 1e8 cap (a ~10³–10⁵× box tightening, i.e. real, not a no-op).
    `buildPowerTable`'s `maxMasteryAbs` param is unused, so `coreScoreAbsMax` only sizes the box — never the penalty
    values — so the scorer lockstep is preserved. No new soundness-lock test needed (the exhaustive tests are it).
- **(2) Param A/B — ✅ MEASURED-NO (2026-07-08). Do NOT flip.** Run under the canonical deterministic protocol
  (1 worker + `interleaveSearch` + fixed seed — the D3 seam made this possible — quiet machine, F5 shape @245 with
  runes+subs, det 600 cap): baseline `(presolve=1, linearization=1)` proves the SAME optimum (10,705) in **88.9 s**
  vs candidate `(3, 2)` in **118.2 s** — a +33 % regression, exactly the "more presolve on a looser model" risk the
  ticket flagged. Production keeps 1/1 for the non-max-damage modes. The A/B seams stay
  (`SolverTuning.maxPresolveIterationsOverride` / `linearizationLevelOverride` + the `manual most-masteries param
  ab` harness) for future param screens.
- **(3) Greedy warm-start — DEFERRED (blocked).** No whole-build greedy exists; needs a from-scratch penalty-aware
  constructor, gated OFF under `SolverTuning`, proven by a measured time-to-OPTIMAL A/B. Speculative/last.

<details><summary>Verified analysis (pre-implementation)</summary>

- **Verdict: all three sub-steps CONFIRMED as accurate + unimplemented.** Most-masteries is the ONE mode with no
  reachable-domain path — `buildMostMasteriesObjective` (`WakfuBuildSolver.kt:1500`) builds its `StatBuilder` with
  no `tight` arg ⇒ `tight=false` (the `buildModel(tightDomains=…)` flag is never consulted for it), so its
  objective sits on the loose `objectiveScore` box `[-1e14, 1e14]` (`:1700-1702`). Ordered sub-steps:
  1. **Reachable objective-product bound (§2, medium risk).** Declare the single genuine product
     `objective = coreScore × multiplier` (`applyConstraintPenalty` → `addMultiplicationEquality`, `:1703`) on a
     `tracker`-derived reachable box instead of `±1e14`. ⚠️ **The negative-`scaledWeight` trap is REAL:**
     `scaledWeight = (weight × 1000).roundToLong()` and `weight` comes from an UNCLAMPED CLI `x:y` value
     (`Main.kt:1134`), so `--action-point 11:-2` yields a negative multiplier ⇒ the naive `aLo·w .. aHi·w` domain
     inverts. Sort the two products via `min`/`max` (the sign-agnostic `[-span, expected]` pattern already in the
     precision code, `StatBuilder.kt:1002-1029`, is the template). **Requires a NEW most-masteries reachable-bound
     soundness lock** (only `maxDamageVarBoundsForTest` / `precisionVarBoundsForTest` exist today).
  2. **Param A/B (low risk).** `maxPresolveIterations=3` + `linearizationLevel=2` are currently gated `if (maxDamage)`
     (`WakfuBuildSolver.kt:1836/1850`); A/B them for most-masteries under the det-time protocol. Pure solver params
     (same optimum) — the only hazard is the perf direction (more presolve on a looser model can be slower), hence
     an A/B not a blind flip. Measurement-limited by host thermals.
  3. **Greedy warm-start hint (§1.5, speculative — LAST).** `addHint` is a pure primal hint (can't change the
     optimum), absent from the whole repo today. **Blocked:** no whole-build greedy exists (`applyGreedyRandom` is
     random-element assignment WITHIN a fixed build, not a constructor) — a penalty-aware greedy must be written
     from scratch, gated OFF under `SolverTuning` (invariant 4) and OFF when forced/excluded items shrink the pool,
     and proven by a measured time-to-OPTIMAL A/B before trusting. Do only if (1)+(2) leave a gap.
- **Acceptance:** existing most-masteries optima unchanged on deterministic tests; new reachable-bound lock for (1);
  det-time table appended to `docs/SOLVER_PERFORMANCE.md`.
</details>

---

## D. Hygiene / copy / docs (tiny, any time)

- **D1** ✅ DONE (2026-07-08) — `Main.kt` now names the actual proof authority: "Proven optimal (solver)" when
  CP-SAT closed the gap itself (the `isOptimal` short-circuit never runs the certificate), "(certificate)"
  otherwise; the pre-verdict hint dropped its parenthetical ("Checking optimality…").
- **D2** ✅ DONE (2026-07-06, shipped with A8.3) — `SolverResult.maxDamageObjective` kdoc corrected.
- **D3** ✅ DONE (2026-07-08) — two-part restructure of the red nightly `@Tag("slow")` proofs:
  (1) the two BARE free-solve tests now run the canonical deterministic protocol (`SolverTuning` gained
  `interleaveSearch`; 1 worker + interleave + fixed seed, det 600/1200) — proof-by-deadline on 8 workers was a
  worker-race lottery on oversubscribed CI. (2) the two RUNES+SUBS tests were re-specified to the production
  proof authority: since the sublimation-catalog expansion, CP-SAT's in-model dual bound structurally cannot
  close on those shapes at ANY worker count (the crit×diff LP relaxation — the certificate exists precisely for
  this), so "CP-SAT reports OPTIMAL" was permanently red, not flaky. They now assert the production guarantee —
  search → certificate badge, with the E8 construct rescue delivering the proven optimum when the incumbent fell
  short — which is deterministic in OUTCOME regardless of worker luck.
- **D4** ✅ DONE (was already shipped with A1, 2026-07-06 — this entry was stale): the resistance-feeder lesson
  lives in `docs/SOLVER_PERFORMANCE.md`'s domination section ("the `compared` set must include every FEEDER of
  a constrained stat…", with the future-edit warning).

---

## Verification epilogue (context for whoever picks this up)

- The A1 chain and its three verifier confirmations, the A2 mutation run, and the perf-scout evidence
  live in the 2026-07-06 review session (memory: `hard-constraints-commits-review`,
  `maxdamage-perf-current-bottleneck`).
- **A3–A8 were code-verified 2026-07-06** (6 parallel single-item verification agents, each reading the
  named source and reporting file:line evidence). Summary of what the verification changed vs the
  original PLAUSIBLE claims:
  - **A3** — sub-claims 1&2 confirmed; sub-claim 3 (silent GUI) REFUTED (a time-based ticker exists but
    is miscalibrated). Budget re-slice is implementable; the "emitted nothing" false-trigger needs
    `CpSolverStatus` to fix properly.
  - **A4** — all confirmed; the fix is STRUCTURAL (retained solver ref + `CpSolver.stopSearch()` +
    solve on a launched job so `awaitClose` fires mid-solve), not a one-liner. Applies to all modes.
  - **A5** — confirmed; clean probe-planner filter. Phase-2 gate is `hasResistanceDebuff` (≡ Sram/Sadida).
  - **A6** — REAL for **precision** (`tight=true`), INERT for most-masteries (`tight=false` discards the
    reach). Fix must pass `.DEFAULT` explicitly (not `BARE`) to preserve today's precision behavior.
  - **A7** — confirmed but NARROWER: **HP is the only percent-affected required target** (resistance is a
    FLAT chain, contrary to the ticket). Use the display-side tolerance; the hard-leg `+1` option is
    UNSAFE (changes the feasible region).
  - **A8** — all three confirmed; A8.1 is a real behavioral fix (predicate misalignment), A8.2/A8.3 are
    log/kdoc wording.
  - Implementation order (all low-risk except A4): A5 → A3 → A8.1 → A6 → A7 → A8.2/3 → A4.
- **A3–A8 all SHIPPED 2026-07-06** on `docs/denouement-feasibility` (see each item's `✅ DONE` block for the exact
  change). Two notable deviations from the original plan, both toward MORE soundness: A6 uses a dedicated
  `MaxDamageExperimentConfig.NON_MAX_DAMAGE` constant (not `.DEFAULT`, which stays coupled to the max-damage
  default); A7 threads the solver's own hard-leg feasibility verdict (`SolverResult.maxDamageHardConstraintsMet`)
  instead of a scorer `−1` tolerance (which is unsound for the certificate path when a target is unreachable). Two
  new tests (`apProbeTargets …`, `hard-leg … provenance flag`); full `:autobuilder:test` + `:gui-compose:test`
  green; ktlint clean. Since then: the B certifier backlog shipped in full (B1–B8), the D hygiene items all
  shipped (2026-07-08), and C closed in full — C7 re-implemented sound + measured-inert 2026-07-09 (stays OFF),
  C8(2) measured-NO. Only C8(3) (blocked on a from-scratch greedy warm-start) remains open in this file.
- Background docs: `docs/max-damage-subs-provability-investigation.md` (the log behind the two
  commits), `docs/CERTIFICATE_PROD_PLAN.md` (certifier architecture + P-phase history),
  `docs/SOLVER_PERFORMANCE.md` (measurement protocol + rejected list),
  `docs/MAX_DAMAGE_PROVABLE_OPTIMUM.md` (campaign log).
