# Sublimation stacking + slot-cap — design plan

Status: **SHIPPED** (2026-07-10). Phases 1–3 landed on `docs/denouement-feasibility` (`600d512a` stacking + cap,
`6068ee21` Zenith export + ring-side race, `7f4e2874` forced-sub stacking). The solver stacks cumulable subs —
forced or choosable — the max-damage certificate keeps the "proven optimal" badge for stacked builds, and the
export/save paths carry the copies (live-confirmed on zenithwakfu.com). Two real modeling gaps flagged by the
maintainer, confirmed in code + against the game's documented mechanic. §§1–7 are the original plan; §8 is the
open-items ledger (now resolved); §9 is the Phase 1 log; **§10 is the Phase 2+3 log** (what shipped).

---

## 1. The two bugs

### Bug 1 — the slot cap conflates normal + epic + relic
`MAX_SUBLIMATIONS = 10` (`WakfuBuildSolver.kt:110`) is applied to the **sum of all** sub booleans:

```kotlin
// SublimationModelBuilder.kt:140
addLessOrEqual(LinearExpr.sum(subVars.values.toTypedArray()), MAX_SUBLIMATIONS)
```

The real rule is **10 normal + 1 epic + 1 relic = 12** (web-confirmed, §2). Epic/relic are already
gated to ≤1 each via item rarity (`gateSublimationsOnCarrierItems`, `SublimationModelBuilder.kt:136-137`),
so the total cap should apply to **normal** subs only. As-is, an equipped epic sub (e.g. Anatomie) eats one
of the 10 slots → only 9 normal fit → the 10th carrier is forced sub-less (this is why the belt shows no
sublimation in the reported Iop-245 build). Today there are **0 solver-choosable relic subs**, so the relic
half is currently moot — but the model is still wrong and should be fixed for correctness + future data.

### Bug 2 — cumulable self-stacking is not modeled (the big one)
`sublimation-stacking.json` (230 entries, **144 cumulable**; per-sub `max_level` + `is_cumulable`) is decoded
by `bdata-extractor` but **never loaded by the engine** — no reader in `autobuilder`/`gui-compose`/`common-lib`.
Each sublimation is a single 0/1 boolean (`SublimationModelBuilder.kt:127`), so the same sub can never be
socketed twice. **All 31 solver-choosable NORMAL subs are cumulable.** The true in-game optimum stacks strong
subs (2× Carnage, 2× Critique Berserk, 2× Influence, …) across carriers; the solver can't represent that, so
every subs-on build is suboptimal vs. the game. The max-damage certificate proves optimality *of this
under-powered model* — so even a "proven optimal" badge understates the real optimum.

**Measured (2026-07-09, CLI, this machine):** the exact Iop-245 request (fire/distance/back/berserk, EPIC,
subs) returns `Proven optimal (certificate)` but the certificate took **~698 s (11.6 min)** — ~8× the 84.5 s
CRA-fire baseline in `docs/`/memory (back+berserk widens the objective). **Proof-time is therefore a
first-class constraint for this work** (see §5, §7).

---

## 2. The confirmed game mechanic

Sources (2026-07-09):
- **WAKFU official forum — *Sublimations : fonctionnement et subtilités*** (the authority; maintainer-downloaded copy):
  https://www.wakfu.com/fr/forum/455-guides/420126-guide-sublimations-fonctionnement-subtilites — cumul rule
  (line 151), epic/relic+regular slot (line 152), socketable slots (line 148), per-sub `Cumul max` + `Scaling` fields.
- MethodWakfu — *L'Enchantement*: https://methodwakfu.com/optimisation/enchantement/ (rarity → level framing).
- wakfu.guide — *Les Sublimations*: https://wakfu.guide/sublimations/ (*"10 classiques, 1 épique et 1 relique"*).

Rules (per the official forum — **supersedes an earlier level-accumulation guess**):
1. **Cumulable subs scale PROPORTIONALLY per copy** — line 151: *"en augmenter proportionnellement les effets …
   Cicatrisation donne 10% PV max … cumulable 3 fois max … 3 items = 30% PV max."* So `k` copies = `k ×` the
   single-copy value, capped at the sub's **`Cumul max`** copy count. (This is NOT "evaluate base+inc at an
   accumulated level"; for the base-0 damage subs the two coincide, but the forum's proportional rule is the
   authority. A few subs carry a special `Scaling` note for non-trivial per-copy growth — capture or force-input.)
2. **Slot cap = 10 normal + 1 epic + 1 relic** — 10 socketable slots (line 148: all items except costume / mount /
   pet / emblem / off-hand), and (line 152) an epic/relic ITEM hosts 1 epic/relic sub **and** 1 regular sub.

Model (best-achievable):
```
value(k)  = k · singleCopyValue          // PROPORTIONAL; singleCopyValue = the current bdata value (a Legendary/maxTier copy)
maxCopies = the sub's "Cumul max" copy cap
```
Marginals are **constant** (linear in k) → §5's top-K-over-marginal-increments stays sound (constant is a
non-increasing sequence). **`maxCopies = maxStackLevel / maxTier`** (a Legendary copy fills `maxTier` levels; the
stack caps at `maxStackLevel` levels) — **confirmed from the forum guide's `Cumul max` column, no in-game test:**
Vivacité (`+1 PA / −75 res`, an effect *identical* in the 1.72 guide and current bdata) lists `Cumul max : 1`, and
its bdata is `maxStackLevel 2 / maxTier 2` → 2/2 = 1 (NOT 2 = `maxStackLevel`, which would be a stackable +2 AP).
Every `+1 resource` sub (Vélocité/Visibilité/Dévastation) is likewise `Cumul max : 1`; MethodWakfu independently
puts Influence (`maxStackLevel 6`) at 2 Legendary copies. So `maxStackLevel` alone would **3×** the copies. Most
choosable damage subs → 2 copies (2× Influence = +18% crit; 2× Carnage = +90%-of-level elemental). **Rounding for
a non-multiple `maxStackLevel`** (e.g. Ravage 4 / tier 3): the last copy is level-capped, not full-proportional —
a per-sub detail to pin in Phase 2 (doesn't change the ~2-copies picture).

---

## 3. Data-model changes (`common-lib` + `bdata-extractor`)

The runtime `Sublimation` only stores each effect's **single-copy** value (evaluated at `level = maxTier`), so
it cannot compute stacked values today. Changes:

1. **Rename `Sublimation.maxLevel` → `maxStackLevel`** (the extractor already uses that name; it is the max
   stack *level*, e.g. 6 — not a copy count). Keep back-compat deserialization for old saved builds.
2. **Add `cumulable: Boolean`** to `Sublimation` (fold `is_cumulable` from `sublimation-stacking.json` into
   `sublimations.json` at extraction time — one runtime file, and the currently-unloaded stacking file stops
   being dead weight). `maxCopies` derives from `ceil(maxStackLevel / maxTier)` (1 when `!cumulable`).
3. **No new per-effect value data needed** — proportional scaling means `value(k) = k · (existing single-copy
   value)`, so every consumer just multiplies the effect's current magnitude by the copy count. The only new
   per-sub datum is **`maxCopies`** (the `Cumul max`), added to `Sublimation` — derived `floor(maxStackLevel /
   maxTier)` or emitted directly by the extractor once its source is confirmed (§8). Non-`StatEffect` shapes
   (conversion / perStatStep / bestElementConcentration) are **not stackable in scope** → `maxCopies = 1`, as are
   any subs carrying a special `Scaling` note (non-proportional per-copy growth) until explicitly modeled.

Consumers read copies via `Sublimation.maxCopies` + a `BuildCombination` copy count (§6); solver, scorer,
certifier and display all multiply the single-copy magnitude identically, so they can't drift. This is a
**smaller** data change than a level-formula model would have needed — no `base`/`inc`/`pct` emission.

---

## 4. Solver model (`SublimationModelBuilder` + `StatBuilder`)

Replace the per-sub 0/1 with a **copy count** for cumulable normal subs, modeled as `maxCopies` ordered
booleans so the concave value linearizes exactly:

```
for i in 1..maxCopies(sub):  b[sub,i] ∈ {0,1};  b[sub,i] ≥ b[sub,i+1]     // fill copies in order
objective += Σ_i  (value(i) − value(i−1)) · b[sub,i]                       // non-increasing marginals
```

Constraints:
- **Carrier capacity:** every normal copy needs a distinct ≥3-socket carrier →
  `Σ_{sub,i} b[sub,i]  ≤  Σ equipped carriers` (generalizes the current `Σ normalSub ≤ Σ carriers`,
  `SublimationModelBuilder.kt:148-153`).
- **Normal cap (Bug 1 fix):** `Σ_{sub,i} b[sub,i] ≤ 10`. Epic ≤1 and relic ≤1 stay item-gated. **Remove** the
  all-subs `≤ MAX_SUBLIMATIONS` line (`:140`); rename the constant `MAX_NORMAL_SUBLIMATIONS = 10`.
- Non-cumulable normal subs, and all epic/relic subs, stay single booleans (`maxCopies = 1`). Epic/relic are
  **never cumulable** in the data (verified) — no change to `gateSublimationsOnCarrierItems`.

`solutionToBuild` (`WakfuBuildSolver.kt`) assigns each *copy* to a distinct carrier for display.

---

## 5. Max-damage certifier (`MaxDamageCertifier`) — soundness-critical

The certifier computes a **sound per-AP-cell upper bound**; it must never under-count (that would award a wrong
badge). The stacking extension:

**Key insight — the sub-selection generalizes cleanly.** The certifier's optional-sub step picks the best subs
under a budget (`subCap`, `MaxDamageCertifier.kt:1516`). Because `value(k)` is **linear** (each extra copy adds
the same single-copy value → constant marginals), replace "top-K over 0/1 subs" with **top-K over the pool of
(sub, copy) units** — a cumulable sub contributes `maxCopies` identical units. Greedy/knapsack over these units
under the same budget is a sound upper bound (over-count only ever raises a UB). Touch points:
- `subCap` → the **normal** budget (10), consistent with §4's cap fix.
- The carrier-capacity guard (`Σ copies ≤ Σ carriers`; the "vacuous when ≥ carriers" reasoning at
  `MaxDamageCertifier.kt:1526-1528`) must be re-derived for copies, or **bail** if not provably vacuous.
- **AP / crit-granting stackable subs:** the per-AP-cell DP couples AP tightly. In the choosable set the
  AP/fixed subs are unstackable (`max_level == maxTier`), so this should not arise — but the certifier must
  **assert it and BAIL (`Long.MAX_VALUE`, sound) if a stackable sub grants AP or start-of-combat crit.**
- **Bump `CERTIFIER_VERSION` 8 → 9** (`WakfuBuildSolver.kt`) — invalidates the per-cell cache.

**Staging for safety (strongly recommended):**
- **Phase A:** certifier **bails → `Unavailable`** on any build that uses ≥2 copies of a sub (detect via the
  incumbent's sub multiset). Sound — it just withholds the badge. Ships stacking correctness (§3/§4/§6) with
  zero soundness risk.
- **Phase B:** the marginal-increment bound above, with the fuzz/oracle guards extended (below).

**Proof-time:** back+berserk already costs ~11.6 min. Stacking enlarges the marginal pool; measure before/after.
The existing two-tier fast pass should still prune most cells — but if Phase B regresses proof time badly, keep
Phase A (bail) as the shipped state and treat the bound as a perf project.

**Guards to extend:** the seeded-pool fuzz lock (`WakfuBuildSolverTest`: `certExact/fast ≥ pinned CP-SAT`,
`ledger ≥ true optimum`) must include **cumulable subs with copies ≥ 2**; the nightly lvl-245 ledger oracle
re-baselines under `CERTIFIER_VERSION 9`.

---

## 6. Everything else that consumes sublimations

- **`BuildCombination` storage + `isValid()`** (`StatBuilder.kt:277`, `count <= MAX_SUBLIMATIONS && epic<=1 &&
  relic<=1`): must count **normal copies ≤ 10** (& ≤ carriers), epic ≤1, relic ≤1. The build must represent
  copy counts — confirm whether `sublimations` is a per-carrier map (holds duplicates naturally) or a set
  (needs a count). *(Verify: `combination.sublimations` is read as `.values` in `Main.kt:816-819`.)*
- **Re-scorer** (`FindMaxDamageScoring`): apply `value(copyCount)` per sub — same `magnitudeAtCopies` as the
  solver, so score == objective.
- **Zenith export** (`zenith-builder`): socket each copy on its own carrier (`/shard/add` per copy). Verify the
  API accepts the same sub id on multiple items.
- **GUI paperdoll** (`gui-compose/paperdoll`): render the same sub on multiple carriers; ensure no de-dup.

---

## 7. Rollout, testing, risk

| Phase | Scope | Certifier | Risk |
|---|---|---|---|
| **1** | Bug 1 cap fix (normal ≤10, epic/relic ≤1 each) | subCap → 10 normal; **VERSION 8→9** | low, contained |
| **2** | Bug 2 stacking: data + solver + scorer + isValid + solutionToBuild + Zenith + GUI | **bails → Unavailable** on any stacked build (Phase A) | medium; badge withheld but sound |
| **3** | Certifier stacking bound (Phase B) | marginal-increment UB + **VERSION bump** + extended fuzz/oracle | high; soundness-critical |

Phase 2's certifier bail keeps the badge **sound at every step** — we never emit a wrong "proven optimal" while
the stacking bound is unproven. Phases 1 and 2 are independently shippable and already remove the bug's
user-visible damage loss; Phase 3 is a pure "get the badge back for stacked builds" follow-up.

Determinism: engine tests must pin `SolverTuning` (see `engine-test-determinism`). Perf A/B only on a quiet
machine (concurrent gradle contaminates timings).

---

## 8. Open items to verify during implementation — resolved 2026-07-10

1. **`maxCopies` rounding + non-proportional subs** — *partly settled.* `maxCopies = floor(maxStackLevel / maxTier)`
   is the socket-optimal count of FULL-value copies (§2, re-confirmed in-game by an advanced player: shards add
   their tier's levels, the family caps at `maxStackLevel`, tiers are mixable). The FLOOR deliberately drops a
   partial last copy (Ravage 4 / tier 3 → 1), so the model is conservative there — solver and certifier agree, so
   soundness holds; only a sliver of value is left on the table. **Still open:** subs with a non-proportional
   `Scaling` note. (The accessor already pins `maxCopies = 1` for conditional / conversion / ramp / best-element
   subs, which covers every structured shape.)
2. **Zenith `/shard/add` accepts the same sub on multiple carriers** — ✅ **RESOLVED.** The export loops per item,
   so two carriers yield two calls with the same `id_shard` and different `side`; nothing dedupes by shard id
   (`ZenithShardPlanTest`). Both calls return 2xx, and the maintainer **confirmed on the live builder** that both
   copies render on their carriers. (Not verifiable programmatically: `GET /builder/api/build/<link>` returns
   equipments + deck only — no `shard`/`rune`/`sublim` keys — and every shard endpoint 404s.)
3. **`combination.sublimations` holds copy counts** — ✅ **RESOLVED.** Copies are keyed by CARRIER (distinct
   `Equipment`), never by sublimation identity, so the saved shape (`Map<equipmentId, List<Sublimation>>`) round-
   trips them; locked by `HistoryMappingTest`, which also pins that `cumulable` + `maxStackLevel` survive the
   `@SerialName("maxLevel")` codec. The GUI's only `distinctBy` is in the *picker* list, which is correct.
4. **Certifier carrier-capacity for copies** — ✅ **RESOLVED as vacuous, deliberately.** The certifier is
   carrier-BLIND: it bounds `n ≤ subCap` only, never "≥3-socket carriers available". Ignoring carriers can only
   let it socket MORE copies than a real build, which raises a sound upper bound — never lowers it. (Consequence
   for tests: on a pool where carriers bind before `subCap` the certifier legitimately over-counts, so a
   `== pinned CP-SAT` coupling assertion needs more carriers than sub-units.)
5. **Proof-time after stacking** — lvl-110 runes+subs search + certificate + E8 rescue: **271 s**; the lvl-245
   fast ledger: **80 s**. **Still open:** re-measuring the back+berserk lvl-245 shape (the 698 s pre-stacking case).

---

## 9. Phase 1 implementation findings (certifier) — 2026-07-09

Phase 1's first certifier cut deviated from §5 and it mattered. Record of what was found and shipped:

- **Deviation:** `subCap` was raised to **12 total** with a harvest filter `n − epicItemFlag − relicItemFlag
  ≤ 10`. Two leaks: (a) every `take(subCap)`/`topK(subCap)` budget-and-hiding capacity (crit bands,
  `maxStartCrit` condition-hiding) silently widened 10→12; (b) the filter subtracts **item** flags, so a state
  whose increments were all normal-stage subs could charge 11–12 normal slots whenever epic/relic *items* were
  merely equipped. Net effect at lvl 110 (fire, runes+subs): cell-13 bound rose to **1,315,990** while the true
  optimum is **1,310,980** (proven: full-pool CP-SAT is INFEASIBLE above it) — the badge stuck at
  ProvenWithin 0.38% and the E8 construct could never reach the bound (soundly withheld).
- **Shipped design (= §5's original intent):** the DP's `n` dimension counts **normal-slot consumers only**
  (`subCap` base = `MAX_NORMAL_SUBLIMATIONS`; a force-taken conversion / forced plain sub charges it only when
  NORMAL rarity). Epic/relic choosable subs ride their **dedicated slots**: `applyRaritySub`/`applyRaritySubF`
  add at the **same n** (and the backtrack's rarity options carry `dN = 0`). A pure-crit/pure-AP budget sub of
  non-NORMAL rarity would be an un-modeled charge shape → **bail** (none exists today; world C's force-taken
  Critical Secret is exempt). State space returns to the pre-fix size (perf-neutral-or-better).
- **Second fantasy found while re-tightening:** `rawSub` clamped a sub's MP at ≥ 0, so **Heavy Armor I**'s
  −1 max-MP never debited the state and **Featherweight I**'s MP ramp was valued at phantom MP (+6 DI on a
  build whose real MP sits at the threshold). The clamp is now removed — exact debit; sound because non-ramp
  subs run before ramp subs, so the ramp sees the real build's MP. This alone was worth ~2.4% of loose bound.
- **Post-fix lvl-110 landscape:** argmax = cell 14 = **1,310,980 = the proven optimum** (tight); cell 13 falls
  to 1,294,025. The nightly lvl-110 proof test now passes via the E8 rescue deterministically (short 1-worker
  search → ProvenWithin → E8 fast tier reconstructs `proxy == bound`).
- **E8 hardening (WakfuBuildSolver):** `dpConstructProvenOptimum` gained a second tier — when the
  provenance-restricted re-solve misses the bound, a **full-pool feasibility fallback** (`rawScore ≥ bound`
  hard floor + stop-at-first-solution, deterministic 1-worker tuning) re-solves at the pinned argmax cell.
  Any solution it finds sits at the bound (per-cell UB), so no optimality proof is needed. A loose bound comes
  back INFEASIBLE ⇒ null ⇒ the caller keeps the incumbent — soundness unchanged.
- **`CERTIFIER_VERSION` 9 → 10**; the lvl-245 fast-ledger oracle re-banked under v10.

---

## 10. Phase 2+3 implementation findings (stacking) — 2026-07-09

Phases 2 and 3 shipped **together** (maintainer choice: keep the badge — don't bail on stacked builds).
The confirmed mechanic was re-validated by an advanced player (Time Lord, in-game): a cumulable family's
socketed level caps at `max_level` (usually 6, sometimes 4/2) and each shard adds its tier (I/II/III = 1/2/3)
levels; tiers can be MIXED to reach the cap. `maxCopies = floor(maxLevel / maxTier)` is the socket-optimal
realization (highest tier ⇒ fewest sockets to hit the cap), which is exactly what a socket-budgeted solver
wants. **`Sublimation.maxCopies`** (common-lib) is the single source of truth; it stays `1` for non-cumulable,
epic/relic, or any structured/conditional sub (conversion / per-stat-step / best-element / conditioned).

- **Data join.** `cumulable` (from `sublimation-stacking.json`) is joined onto the runtime sub at load
  (`WakfuBestBuildFinderAlgorithm.sublimations`) — the file was decoded but never read before.
- **Solver model (Phase 2, `SublimationModelBuilder`).** A k-copy cumulable NORMAL sub is its base `subVar`
  PLUS ordered copy booleans `b[1..k-1]` (`b[i] ≤ b[i-1] ≤ subVar`, "fill in order"). Each copy consumes a
  normal slot AND a carrier (folded into `Σ ≤ 10` and `Σ ≤ Σ carriers`), and adds one more single-copy value
  (`buildSublimationTerms` / `buildPermanentSubTerms` fold each copy var into the effect's stat bucket).
  `solutionToBuild` emits N carriers for a sub taken N times (`copyVars.sumOf { valueOf }`), so the GUI /
  Zenith / re-scorer see genuine duplicate placements with no downstream change.
- **FORCED subs stack too** (CERTIFIER_VERSION 13). Their base var is pinned to 1, so `b[1] ≤ base` leaves the
  copies free. Without this, forcing a cumulable sub was strictly WORSE than leaving it choosable (1 copy vs 2)
  — a trap, since forcing means "I want this sub", not "I want less of it". Only subs whose effects are actually
  modeled get copies (a forced COMBAT_CONDITIONAL sub contributes nothing, so its copies would be dead vars).
  In the certifier the forced sub SPLITS: its base copy stays in the constants (and pre-charges its normal slot
  via `subCap = 10 − forcedNormalCount`), while its `maxCopies − 1` extra copies join the OPTIONAL `keptSubs`
  pool — so the budget / crit-window machinery (`maxPermCrit`, `maxStartCrit`, the `n ≤ subCap` charge) prices
  them exactly like a choosable sub's copies. Credit fewer ⇒ a stacked forced build out-scores its own bound
  (badge silently lost); credit the base twice ⇒ an unreachable ceiling.
- **Certifier model (Phase 3, `MaxDamageCertifier`).** Stacking is re-derived independently of the model's
  copy vars: `keptSubs` DUPLICATES a cumulable sub's single-copy `Raw` `maxCopies` times, so the per-AP-cell
  DP can socket up to `maxCopies` copies (each `+value`, `+1` slot / carrier). The `n ≤ subCap` (10 normal)
  and carrier bounds cap the total; more copies only raise a sound upper bound.
- **THE bug that cost the most time — copy-var domain leak (root cause of a 2.86-million× over-count).** The
  certifier introspects the CP-SAT model's stat terms and folds any variable it does NOT recognize (not a
  carrier / sub / skill var) into the passive CONSTANTS at that variable's *tracked domain max*. Phase 2's
  new copy vars were unrecognized AND untracked (default domain `±10,000,000`), so a `+40` DI copy var folded
  into `diConst` as `40 × 10,000,000 = 400,000,000` — and the mastery shift compounded it, exploding the
  lvl-50 cell-9 bound from a tight `639,870` to `1,422,089,439,870` (`ProvenWithin(fraction ≈ 2.2e6)` — sound,
  but the badge could never be won). **Fix:** DROP the model's copy vars from every certifier term list
  (`certifierDroppedVars`, mirroring `conversionMovedVars`); their VALUE rides the KEPT base `subVar` term
  (`perSubValue` attributes it) and the `keptSubs` duplication scales it k×. Post-fix the lvl-50 cell-9 bound
  is `639,870` == the incumbent proxy — tight, sound, and the stacked build certifies **ProvenOptimal**.
- **`CERTIFIER_VERSION` 10 → 12** (11 was the first, still-broken stacking cut with the copy-var leak; 12 is
  the drop-copy-vars fix). The lvl-245 fast-ledger oracle re-banked under v12: every cell rose (stacking adds
  value at every AP), `max 17,111,280 → 17,726,310`, still ≥ the pre-stacking proven optimum `16,909,590`.
- **Guards.** The P6.1 fuzz lock's scenario generator now emits cumulable subs (~half, `maxLevel 6 / tier 3`),
  so all 25 seeds assert `certExact/fast ≥ CP-SAT` and `ledger ≥ true optimum` WITH stacking; plus dedicated
  behavioral tests (a cumulable sub stacks to its 2-copy cap across carriers; a non-cumulable sub stays single)
  and an end-to-end badge test (a build stacking a cumulable sub certifies ProvenOptimal).

### Still open (Phase 2+ / follow-up)
- **Cumulable SELF-stacking of a sub's OWN condition/scaling** — the general modeling of non-proportional
  `Scaling` notes stays out of scope; such subs are `maxCopies = 1` (single-copy) by the accessor guard.
- **Re-prove the exact lvl-245 stacking optimum** (heavy nightly) — the oracle's `LVL245_PROVEN_OPTIMUM` floor
  is still the pre-stacking value (a valid but loose lower bound; the true optimum is higher, ≤ 17,726,310).
- **Cosmetic:** rename `Sublimation.maxLevel` → `maxStackLevel` (with `@SerialName("maxLevel")`).

## 11. Perf campaign — the stacking certificate cost, and the "families" plan (2026-07-10)

### Measured cost of stacking on the certificate (all clean A/B, quiet machine, caffeinate)

| Measurement | pre-stacking `f2927acd` | v13 (naïve dup) | v14 (multiplicity + E8 fix) |
|---|---|---|---|
| lvl-110 badge proof (slowTest, 2-test invocation) | 143.5 s | 486.6 s | **424.7 s** |
| lvl-245 fast-ledger oracle | 64.1 s | 95.3 s | **80 s** |
| Real Iop-245 back+berserk CLI (60 s search) | 698 s | killed ≥ 90 min CPU, unfinished | **1 h 47 (1-thread)** |

`CERT_TIMING` decomposition (badge test, 424.7 s): tier-1 = 26 s, **tier-1.5 = 391.5 s (93 %)**, exact = 1.1 s.
At 245: tier-1 = 180 s, **tier-1.5 = 99 min (97 %)**, exact = 7.6 s — 60 (surviving-cell × world) step-1 runs,
only 27 % of segments skipped. The cost is NOT stage count (v14 fixed that): stacking legitimately widens the
DP's Pareto frontiers (richer DI × mastery × copies tradeoffs) and raises every cell bound (more survivors).

### Landed in v14 (`CERTIFIER_VERSION` 14)
1. **Multiplicity encoding** — `normalTransitionStages` collapses a cumulable sub's duplicated `keptSubs`
   entries into ONE DP stage taking `j ∈ 0..maxCopies` copies. Same reachable set (a mult > 1 sub is
   unconditional and never a ramp ⇒ constant per-copy contribution) ⇒ oracle values bit-identical.
   `keptSubs` STAYS duplicated for the slot-counting consumers (budgets / segment edges / minSubsToCover).
2. **Provenance backtrack fix** — the explain's `sub:` stage options only offered `dN = 1`, so every stacked
   transition broke the backtrack and kicked E8 onto its expensive full-pool fallback (~+180 s on the badge
   test). Now one option per copy count j with j-fold deltas.
3. **Dynamic certifier threads** (`StatBuilder.certifierThreadsProvider`) — the search-time certificate
   warm-up starts at 1 thread (never compete with CP-SAT) but the post-search proof JOINS it via the cache
   single-flight, so the whole 100-min certificate inherited 1 thread. The provider is re-read at each tier's
   start; the warm-up passes `{ if (searchDone) certifierDefaultThreads() else 1 }`. No value change, no
   version bump needed (order-independent maxes). NOTE: parallel efficiency is memory-bandwidth-bound —
   6 workers ≈ 2× wall, not 6× (each task's dpMs inflates ~3×).

### Landed after v14 — the LIVE INCUMBENT fix (the jackpot, 2026-07-10 evening)
4. **Dynamic incumbent** (`StatBuilder.certifierIncumbentProvider`) — the warm-up launches on the search's
   FIRST streamed result, so the whole certificate eliminated/skipped against that weak incumbent, even though
   the final one is known minutes before elimination runs. The provider is resolved ONCE, right after the
   tier-1 fast DP (monotone `max` with the call-time value; any feasible incumbent is sound). Measured on the
   real Iop-245 back+berserk request (cold disk cache, 10 GiB heap): survivors 10-12 → **1**, tier-1.5 segment
   skips 0-27 % → **99 %**, tier-1.5 99 min → **8.8 s**, total request **1 h 47 → 3 min 44** (search included)
   — **~3× faster than the 698 s pre-stacking baseline**, which silently paid the same weak-incumbent penalty.
   `CERT_TIMING fastMs=177s tier15Ms=8.8s exactMs=32s survivors=1`; badge = ProvenOptimal via E8 construct.

### The families plan — SHIPPED as CERTIFIER_VERSION 15 (2026-07-10, same evening) — collapse mono-axis subs into harvest BUDGETS
Insight (maintainer's): most damage-relevant cumulable subs are MONO-AXIS, and for a mono-axis unconditional
contribution the optimal k-slot selection is a sorted prefix — no DP stage needed. The certifier ALREADY does
this for pure-crit / pure-AP subs (the budget + `minSubsToCover` slot-charge machinery). Generalize to DI and
mastery:
- **DI budget** (e.g. Burn +12 scenario-element, Destruction +3 ×2) and **MASTERY budget** (e.g. Carnage ×2,
  Devastate band mastery): at harvest, free slots = `subCap − n0 − critGapSubs − apGapSubs`; enumerate
  `k_DI + k_MAST ≤ free` (≤ 11 splits) over both sorted prefix sums, take the max product term.
- **Stay DP stages:** mixed-axis subs (Heavy Armor, Swiftness — DI↔MP tradeoffs), ramps (Featherweight),
  conditional subs (Neutrality, LWE, Ambition) — per-state gates stay exact.
- Defensive zero-Raw subs are ALREADY excluded (`subEntries` only keys subs present in the damage term maps).
- Expected effect: the DP loses exactly the stages that inflate its frontiers; per-scenario transition stages
  drop from ~9-12 to ~5, and the DI×MAST combinatorics move into O(free) harvest arithmetic.
- **Validation invariant: values must be IDENTICAL** (sorted-prefix selection is exact for mono-axis subs, so
  the reachable set is unchanged) — fuzz lock `== CP-SAT`, coupling locks, and the 245 oracle must all pass
  with UNCHANGED banked values. Any shift = the encoding is wrong.
- Touch points: both passes (fast `orderedNormalF` + exact `orderedNormal`), harvest gap-charge sections,
  the provenance backtrack (`stageOptions` + the budget `cover` lines), `CERTIFIER_VERSION` 15.
- **Status: SHIPPED (v15).** Implementation exactly as planned: `diBudgetUnits` / `grawBudgetUnits` (one
  entry per copy), `diPrefix` (c-independent) + `grawBudgetPrefix(cEff)` (per harvest crit step), and
  `budgetMax` enumerating the k_DI split with endpoint k_graw (both prefixes non-decreasing ⇒ only the
  endpoints can win per k_DI). `stagedTransitions` keeps mixed-axis / ramp / conditional subs as exact DP
  stages and drops all-zero Raws outright.
- **One intentional value change:** the lvl-245 FAST oracle tightened −0.01–0.04 % per cell (re-banked).
  Staged mastery subs used to be folded at the DP segment's TOP crit (fast-tier slack); the budget prices
  them at the harvest's exact per-c fold. Still ≥ the exact optimum (locked); the EXACT tier is
  value-identical (fuzz == CP-SAT, 25 seeds, all green).
- **Measured:** lvl-110 badge slowTest 424.7 s → **108.6 s** (pre-stacking was 143.5 s — v15 beats the
  pre-stacking encoding). Real Iop-245 back+berserk CLI, STOCK heap, cold cache: **66 s total** (60 s
  search included) — `CERT_TIMING fastMs=58.5s tier15Ms=1.1s exactMs=0 survivors=1`; the certificate now
  fully overlaps the search and the badge lands with the result. Campaign: 762 s pre-stacking → 1 h 47
  (v14 serial) → 3 min 44 (dynamic providers) → **1 min 06 (v15)**.
- Also landed alongside v15: `certifierTier15Threads()` — tier-1.5 workers are memory-light (~0.4 GiB
  measured), so they get their own heap formula (6 workers on a stock 4 GiB heap, 3 on the packaged GUI's
  -Xmx3g) instead of the exact tier's 1.25 GiB/worker gate that kept production serial.
