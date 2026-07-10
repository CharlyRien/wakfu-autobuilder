# Code-review follow-ups — solver-choosable-sublimations branch (2026-07-01)

Deferred findings from a **max-effort `/code-review`** of the sublimation work (branch
`claude/optimistic-lewin-03113f`). The confirmed correctness bugs and the two *safe* architecture items were
fixed on that branch; what remains here is the larger refactors + cleanup, kept for a later, separately-reviewed
pass.

> ⚠️ **This is provably-optimal solver code.** A mistake in the objective / pruning silently breaks the
> "proven optimum" guarantee without crashing. For every item below: keep the diff small, re-run the **full**
> suite (`./gradlew test`, incl. the soundness/optimum-preserving tests in `WakfuBuildSolverTest`), and for any
> objective/model change do a **cool-machine deterministic-time perf re-verify** (wall-clock is thermal-noisy —
> see `docs/SOLVER_PERFORMANCE.md`). Line numbers below are as of 2026-07-01 and will drift — anchor on symbols.

## Already fixed on this branch (context — do NOT redo)

| ID | What | Where |
|----|------|-------|
| A1 | objective↔display/selection dropped scenario-gated / best-element / per-element sublimation DI | `BuildSpellDamage.resolveStats` + `SpellRotationOptimizer.scoreSpells` (thread `damageScenario`) |
| A2 | pre-sealed history saves failed to decode → silently dropped | `SublimationEffectLenientSerializer` (`common-lib/Sublimation.kt`) |
| A3 | domination pruned the survivability-floor optimum | `dominationShape` adds HP + resistances when `scenario.survivabilityFloor` |
| A4 | `Double` products feeding hard CP-SAT bounds lost 2⁵³ precision | `clampedProductQuotient` (BigInteger) |
| B6 | `GeneticAlgorithmResult` → `SolverResult` | 9 files + docs (package stays `genetic`) |
| B2a | experiment **config** lifted out of the engine | `MaxDamageExperiments.kt` |
| B5 | reviewed → intentionally **not** changed | see [Decided](#decided--no-change) |

---

## Architecture (high value, large — one PR each)

### B1 — split `WakfuBuildSolver.kt` (5,481 lines)

One `object` holding a ~2,830-line `private class StatBuilder` (`WakfuBuildSolver.kt:2265`), an ~890-line
`perHitDamageScore` (`:3548`), plus domination, rune fold, sublimation folding, domain tracking and solution
decoding. Every concern shares one file, so any change forces the whole context and merge conflicts are near-certain.

- **Target shape:** extract cohesive units — `SublimationModelBuilder`, `RuneModelBuilder`,
  `DamageObjectiveBuilder`/`PerHitDamageFold`, `DominationFilter`, `DomainTracker` — leaving `WakfuBuildSolver`
  a thin orchestrator.
- **Feasibility:** `StatBuilder` is already a *standalone* `private class` (not `inner`), so moving it to its own
  file is mechanically clean — but it references many object-level `private` consts/types/helpers (`Term`,
  `RandomEntry`, `SkillTerms`, `PowerTable`, `DomainTracker`, `RuneModel`, `SublimationModel`, `DominationShape`,
  `mulRange`, `scenarioMasteryStats`, `effectiveStatForDomination`, `clampedProductQuotient`, the `DAMAGE_*` /
  `ELEMENTARY_*` constants). Each becomes an `internal`-visibility change. **Compiler-gated** (won't build until
  every ref resolves) but a large diff.
- **Effort/risk:** high effort, low *correctness* risk (compiler + tests gate it), but big review surface. Do the
  `StatBuilder` file-move first, then peel sub-builders out incrementally.

**DONE — core split (2026-07-04).** `WakfuBuildSolver.kt` had grown to **8,435 lines** (the certifier work
landed on top). `StatBuilder` (~5,500 lines: the whole CP-SAT model + damage objective + certifier) and
`DomainTracker` moved verbatim into **`StatBuilder.kt`**; the leaf CP-SAT model types (`Term` + its range
helpers, `RandomEntry`, `RuneModel`, `SublimationModel`, `SkillTerms`, `PowerTable`) into **`SolverModel.kt`**;
the 31 solver bound/scale constants de-nested to top level. `WakfuBuildSolver.kt` is now ~2,800 lines (the
object shell: `optimize`/`buildModel`/`maxDamageCertificate` + test seams). Every move is byte-identical —
full `:autobuilder:test` green (incl. all soundness / certifier / optimum-preserving locks), ktlint clean.
StatBuilder still references ~18 object helpers by import (the mastery-list constants + a few pure funs) — a
small transitional coupling.

**DONE — object sub-domains peeled out (2026-07-04).** The build-time model-construction concerns that lived in
the `WakfuBuildSolver` object are now their own files: **`DominationFilter.kt`** (the pure item-domination
relation + pool filter), **`RuneModelBuilder.kt`** (`createRuneModel` + `relevantRuneStats`), and
**`SublimationModelBuilder.kt`** (the modelability predicates + `createSublimationModel` + epic/relic carrier
gating). The object shell (`optimize`/`buildModel` + test seams + the shared `scenarioGateMatches` /
`clampedProductQuotient` / math helpers) is **~2,180 lines, down from the original 8,435**. Each extraction is a
verbatim move (visibility widened to `internal` where called cross-file; a shared helper caught mid-cluster left
in place), full `:autobuilder:test` green + ktlint clean at every step.

**DONE — the deeper StatBuilder internals too (2026-07-04).** The extract-collaborator refactor turned out clean
via Kotlin **extension functions**: an `internal fun StatBuilder.x()` in another file reads StatBuilder's
`model` / `tracker` / term maps by bare name through the receiver, so no call-site state threading — just move
the methods and widen the members they touch to `internal` (compiler-driven). Extracted:
**`MaxDamageCertifier.kt`** (~2,700 lines: certifyLedger / certifyAllCellsFast / exactForCells / certifierWorlds
/ certifyMaxPerHitAtAp(Pass) / certifyExplainAtAp + the Frontier / DenseDp / CertWorld DP structures) and
**`DamageObjective.kt`** (perTurnDamageScore / perHitDamageScore + the product/rotation bounds). Each is a
verbatim move — full `:autobuilder:test` green (every certifier ==CP-SAT / soundness / oracle lock), ktlint
clean. **StatBuilder.kt is now ~2,150 lines** (the model + stat-resolution + sublimation-term core), down from
5,344 after the file move and 8,435 originally. What remains in StatBuilder is genuinely one cohesive concern
(turn the vars into stats, terms and constraints); the sublimation-term sub-cluster could be peeled next but the
big wins (certifier, objective) are banked.

### B2b — move the experiment **methods** out of the production hot path

`MaxDamageExperimentConfig` config is already extracted (B2a), but the research/A-B *code* still lives inside
`StatBuilder` and its objective loop:

- `certifyMaxPerHitAtAp` (`:3988`, ~230 lines), `innerFrontierPrototype` (`:4216`, ~130 lines — writes only
  `System.err`, **no production/test consumer**), `maxPerHitProductBound` (`:3745`), `maxRotRawForElement` (`:3692`).
- `System.getenv("WAKFU_MAX_DAMAGE_*")` hooks at `:3464`, `:3467`, `:3474`, `:3779`, `:4075` — some inside
  `perTurnDamageScore`'s per-element loop.

All are gated off in production (`MaxDamageExperimentConfig.DEFAULT`). **Blocked on B1** (they use `StatBuilder`
state). After B1: relocate to a test-source `MaxDamageCertifier`/experiments harness behind a narrow seam.
`innerFrontierPrototype` is a likely straight **delete** — but confirm with the maintainer first, it may be
deliberately *banked research* (cf. the `test: bank …` commits + the Chaos "kept for a future fast model" note).

**SUPERSEDED / RE-SCOPED (2026-07-04).** The premise shifted: `certifyMaxPerHitAtAp` is no longer experiment
code — the P2–P6 work made it **the production certificate** (it backs the "proven optimal" badge via
`certifyLedger`/`maxDamageCertificate`), so it must STAY. `maxPerHitProductBound` / `maxRotRawForElement` are
sound bounds now gated by `MaxDamageExperimentConfig` flags (A/B knobs), not dead. What remains genuinely
experiment-only in the hot loop is the small set of `System.getenv("WAKFU_MAX_DAMAGE_*")` in-model probe blocks
(`_INNER_FRONTIER` → `innerFrontierPrototype`, `_DEBUG_JOINT` → `apCapacitatedProbe`, and the now-redundant
in-model `_CERTIFIER` probe that predates the standalone certifier API). The clean "relocate to a test-source
harness" is still **blocked** — those probes call `StatBuilder` instance state, and that state isn't extractable
until the sub-builders are (the deferred deep half of B1). `innerFrontierPrototype` was the prototype for the
fast pass that HAS since shipped (the two-tier fast certifier), so it is now dead research — a delete candidate,
pending the maintainer's "banked research" call.

**DONE (2026-07-04, maintainer approved the deletion).** Removed the three env-gated in-model probe blocks from
`perTurnDamageScore`'s hot loop (`WAKFU_MAX_DAMAGE_INNER_FRONTIER` / `_DEBUG_JOINT` / the redundant in-model
`_CERTIFIER` diagnostic) and the two dead research functions they were the only callers of —
`innerFrontierPrototype` (superseded by the shipped fast certifier) and `apCapacitatedProbe`. `certifyMaxPerHitAtAp`
and the sound `maxPerHitProductBound` / `maxRotRawForElement` bounds STAY (production / experiment-config-gated).
StatBuilder.kt −~130 lines; behaviour-identical (full `:autobuilder:test` green — the blocks were `getenv`-gated
and never ran in production/tests). The broader "relocate the remaining experiment knobs to a test harness" stays
blocked on extracting the sub-builders out of StatBuilder.

### B2c — tighten the AP-cell certifier for the new sublimation types

**Status 2026-07-02 (in flight, uncommitted):** the certifier was found INERT on the shipped catalog (three
production always-bails: the Unraveling conversion, `keptSubs > subCap` — 30 damage-relevant choosable subs vs 10
slots — and a latent pure-crit/pure-AP item skip that UNDER-counted cells). All three fixed via world-split
passes (`certifyMaxPerHitAtApPass(convTaken, critSecret, …)`): Unraveling = force-taken pass with every pre-sub
critM source folded to mastery; Critical Secret = force-taken pass with every pre-combat critM source zeroed;
per-stat-step subs (Featherweight) attributed to their sub via `subDerivedVars` (slot-consuming transitions, not
free constants); budget subs charged against sub slots at harvest (`minSubsToCover`). Locked by `==`-vs-CP-SAT
tests (conversion-decides pool; 3-way epic competition incl. the Critical-Secret shape) + the coupling `≥` panel
+ a `@slow` catalog no-bail test. **Measured (lvl-245 fixture, incumbent 16,892,160):** the `certifierCellCap`
experiment feeds the cells back as model constraints and CP-SAT's bound snaps EXACTLY to the certificate —
trajectory 26.5M (no caps) → 21.03M → 19.95M at the binding AP-16 cell. Cells ≤12 already fall below the
incumbent (certificate-eliminated, zero solving). Remaining to certify cells 13–16 by comparison alone:
−3.0/−6.4/−12.7/−15.3%. Cutoff probes at 300 s/8w cannot close these cells (bound stays pinned at the
certificate) — the certificate is strictly stronger than CP-SAT branching here, so tightening it is the ONLY path.

**Round 3 (same day):** Light Weapons Expert II split on the weapon axis (`weaponsRestricted` world: the sub
allowed only with weapons ∈ {empty, one-handed} — locked by a dedicated `==` weapon-tradeoff test) → AP-16 cell
19,947,390 → 19,720,800. A diagnostic ablation hook (`WAKFU_MAX_DAMAGE_CERT_EXCLUDE_SUBS`, audits only) shows
**Featherweight is the remaining fantasy: ablating it alone gives 17,031,600 (−13.6%) and drops cells 13–15
below the incumbent.** Two sound aggregate MP bounds were tried and did NOT bite (removed): a per-cell AP↔MP
Lagrangian and an exact slot-aware item-MP-by-item-AP knapsack — because the pool really can reach ~8 MP at
item-AP 10 (AP and MP live on different slots); the coupling CP-SAT sees is that MP-carrying items cost MASTERY.

**Round 4 (same day): the 3-D Pareto frontier landed.** Frontier points are now (di, graw, mp) triples and
MP-sourced ramps are valued PER POINT (`mpFreeMax` + the point's own item MP); pure-MP items join `itemEquips`
(they had NO damage stat, so they never became DP options — an under-count the new `==` MP-tradeoff lock
caught, along with the silent non-modelability of STATIC_CONDITIONAL subs with a null condition in two test
fixtures). Measured: AP-13/14/15/16 → 17,124,975 / 17,778,420 / 19,016,625 / 19,123,200 (gaps vs the
16,892,160 incumbent: 1.4/5.2/12.6/13.2%). The FW ablation ceiling (−13.6%) was NOT fully recovered because
much of the ramp's value is honestly reachable MP (base + skill + sub MP ≈ 6 before items). Remaining
looseness to chase, in order: sub MP granted free in `mpFreeMax` even when the sub isn't taken (move it onto
`rawSub.mp` so e.g. Swiftness II's MP arrives only WITH its −10 DI), skill MP likewise (needs an mp axis in
`branchCells`), then the residual "everything maxed simultaneously" slack at AP-15/16. ⚠️ The 3-D frontier
multiplied the all-cells audit to ~18 min at lvl-245 (was ~4 min) — production wiring now REQUIRES per-cell/
per-world parallelization (embarrassingly parallel) and probably a frontier-size cap study. An AP-pinned
certcap solve now computes ONLY its own cell (÷17 per cutoff probe) — the production pattern in miniature.

**PROBE VERDICT (closes the strategy question):** cutoff probes (`objective ≥ incumbent+1`, certcap, 300 s,
8 workers) on cells 13–16 end UNKNOWN with `bestBound` EXACTLY equal to the certificate after ~1–1.5M branches
— CP-SAT branching contributes ZERO bound progress beyond the certificate, even at a 1.4% relative gap, and
found no better build in 4 consecutive attempts (the incumbent is almost certainly the optimum). ⇒ The proof
endgame is certificate-only: push cert(a) ≤ incumbent per residual cell; probes are useful only as
better-build finders, not as proof closers, on this model (re-confirmed later even at a 0.85% band).

**Round 5 (same day): MP honesty — cells 13 and 14 fall.** The free MP pool (`mpFreeMax` was 7) decomposed
into base 3 + skill 1 + Swiftness II 1 + ring-top-2 2, all granted WITHOUT their costs. Now: Swiftness's MP
rides its own transition Raw (arrives only WITH its −10 DI; ramps ordered last so they see accumulated sub
MP), the Movement Points major is a priced axis in `branchCells` (competes for the pool), and MP-carrying
rings are explicit ring options (their (graw, mp) tradeoff on the frontier; excluded from the plain top-2
pool). Epic/relic sub MP stays free but bounded to 2 (none exists today). The strengthened `==` locks assert
CP-SAT `OPTIMAL` status (they silently accepted FEASIBLE values before) and cover the Swiftness arbitration;
they also documented that normal subs need DISTINCT ≥3-socket carriers (binds on tiny pools; the certifier
deliberately over-counts carriers — vacuous on the real catalog, 14 carriers vs 10 slots, but a thin
un-modeled coupling in principle, like rune/sub SOCKET requirements vs best-stat items generally).
Measured: cells 13/14 → 15,898,650 / 16,470,990 — **BELOW the incumbent, eliminated**; cells 15/16 →
17,199,750 / 17,036,580. 13 of 21 cells now close by certificate comparison, 0–4 by reachability
arithmetic, cell 5 by a trivial probe; only 15/16 remain.

**Iteration 6 — the loop in action:** a no-cutoff certcap hunt at cell 16 then found a BETTER build,
16,892,160 → **16,909,590**, in only 22k branches (the caps double as a search accelerator; the earlier
six "no-better-build" probe results were budget-truncated). A cell-15 hunt found nothing above its own
cellmax (~16.57M — so cell 15's certificate carries the loosest internal gap, 3.8%). The optimum is
bracketed to **[16,909,590, 17,199,750]** (1.7% globally; 0.75% at cell 16). The socket-carrier cut was
then RULED OUT BY DATA: the top-mastery item in every real slot carries 4 sockets (only
emblem/mount/pet/off-hand tops lack sockets, and those never have any), and the top items also carry the
MP (De Nyl = top-mastery amulet + 1 MP), so the fantasy's small Featherweight credit is data-plausible.
**Next tool: certificate-state PROVENANCE** — dump which items/subs/rune choices compose the winning
17.04M/17.20M states; if constructible ⇒ it's a real better build (feed it to the hunt); if not ⇒ the
violated constraint is, by construction, the next certificate cut. Also immediately shippable without
further tightening: a "proven within X%" badge — the bracket costs only the certificate, on any core count.

**Iteration 7 — provenance landed and delivered its first verdict.** Explain mode snapshots the frontier
after every DP stage and backtracks the winning point to NAMED choices (`certifierExplainForTest`,
`WAKFU_MAX_DAMAGE_CERT_EXPLAIN=<cell>`; two tool bugs fixed en route: rarity-sub options must not carry
dEpic/dRelic since `applyRaritySub` never consumes the item budget, and arithmetic tie-labels can misname
a take — Steadfast vs Elemental Concentration at high c). Cell 16's 17,036,580 state, fully named, was
then value-checked by proving the micro-pool of exactly those items: **14,598,870 OPTIMAL — the state is
fantasy, not a hidden build.** The COST dimensions are exact (the named items carry precisely the claimed
crit 38 / AP 8); the VALUE side is not: L'Avie (zero sheet mastery) is credited graw +231,345 (~477
mastery-equivalents, far beyond its 4 rune slots) and the Souvenir pair +315,250 likewise. ⇒ **The next
certificate cut is a bounded bug hunt: per-item graw in `perCarrierContribution`/`rawOptions` exceeds the
model's own per-item terms — suspect the rune fold on items without the dual-rune split. Fixture: L'Avie
and Souvenir ancestral.** Closing it should drop cells 15/16 below the incumbent — the full certificate
proof.

**Iteration 8 — the fantasy killed: cert(16) == incumbent, exactly.** Iteration 7's "inflated per-item
graw / rune fold" hypothesis was **wrong**: L'Avie's 477 mastery-equivalents are 345 (its
`MASTERY_ELEMENTARY_ONE_RANDOM_ELEMENT` line, which the sheet check had missed) + 132 rune — the per-item
claims were model-consistent all along. The tool that broke the case was an **axis-level diff**: the
verification test now also runs the streaming solver on the micro pool and prints the proven build +
resolved axes (`PROVENANCE_BUILD`), and the diff was surgical — ap 16=16, crit 85=85, **Δgraw = 315,250 =
exactly the Souvenir-pair claim**, ΔD = 12 = exactly the phantom Featherweight, and the solved build wore
**zero rings**. Three structural defects, all fixed, each with a `==`-CP-SAT lock:
1. **Negative-AP items rode cells for free** — `perCarrierContribution` takes the optimistic per-term max,
   so a negative coefficient contributes 0 (variable at domain-min = "don't equip it"): a Souvenir
   ancestral (−1 max-AP) was hosted at no cost, certifying a real AP-14 build into the AP-16 cell with
   cell-16's hits multiplier. Cost dimensions now use `perCarrierExactValue` (raw coefficient sum) and the
   DP charges negatives exactly: stored coordinates = real + offset (offsets sum each slot's worst-case
   negative), transitions keep 2·offset headroom (a +2-AP amulet may precede the −1-AP rings and come back
   down), the exact band is enforced at harvest only, and cost cells pack **biased** components — the
   mixed-radix packing decodes wrongly on a negative low digit (crit −10 / ap 0 read back as ap −1 /
   crit +8 under floor semantics).
2. **Same-name ring pairs** — the model forbids two same-`fr`-name rings (a Wakfu rule); the certifier's
   ring stage happily paired Mythic+Legendary siblings (first Souvenir ×2, then, once negatives were
   charged, Anneau Chuchotis ×2 — 6% over). The per-cost-cell top-2 is now distinct-name and every pair
   enumeration (plain×plain, mp×plain, mp×mp — stage and backtrack alike) refuses same-name partners.
3. **Crit clamp cells (two pre-existing holes exposed by exactness)** — totals below 0 clamp to an
   effective 0: the c = 0 cell now owns every state with arithmetic total ≤ 0 and charges no budget subs
   (demanding subs to "climb up to zero" unreachable-skipped an Epaulectriques-style −10-crit build worn
   for its mastery). And item+skill crit can arithmetically exceed the 100 cap **bundled on best-mastery
   gear** (the per-slot maxes + skills reach ~110 at 245): those builds were pruned above `critItemHigh`
   at every enumerated c — a silent UNDER-count, i.e. a void proof. The enumeration now extends to
   `cEnumMax ≈ critConst + maxItemCrit + maxSkillCrit`, valuing over-cap cells at `cEff = min(c, critCap)`.
**Result: cert(15) = 16,603,625 < incumbent 16,909,590 (eliminated); cert(16) = 16,909,590 — EQUAL to the
incumbent, i.e. the certificate is tight and the DP's winning state is the incumbent itself.**

**The complete ledger (2026-07-03) — the lvl-245 optimum is proven by certificate alone.** Exact negative
charging also made cells BELOW the AP constant real (AP 4 = base 6 with the −1-max-AP pair), so the
`apHigh < 0` bail was relaxed to the true arithmetic floor (−apOff) and every cell audited:

| AP cell | certificate | | AP cell | certificate |
|---|---|---|---|---|
| 0 | infeasible (< −apOff) | | 9  | 11,720,310 |
| 1 | 0 | | 10 | 12,440,665 |
| 2 | 1,623,600 | | 11 | 13,699,800 |
| 3 | 2,719,010 | | 12 | 14,420,250 |
| 4 | 3,440,030 | | 13 | 15,506,475 |
| 5 | 6,308,940 | | 14 | 15,980,430 |
| 6 | 7,826,280 | | 15 | 16,603,625 |
| 7 | 8,744,670 | | **16** | **16,909,590 = the incumbent, tight** |
| 8 | 10,222,080 | | 17–20 | 0 (arithmetically infeasible — the old positive certs were phantom Souvenir AP) |

Every cell's certificate is ≤ 16,909,590 and cell 16's equals it ⇒ **the CRA-245 fire runes+subs
max-damage optimum is 16,909,590, proven machine-independently by the certificate — no CP-SAT branching,
no probes, identical on 1 core or 16.** (Cells 13/14 also DROPPED vs the pre-fix ledger — the phantom had
inflated them too.) Cost note: exactness roughly tripled the per-cell DP (~9 min/cell serial at 245 —
wider crit dimension + over-cap cells); the banked per-cell×world parallelization recovers it, and two
cheap tightenings exist (stage-aware remaining-negative headroom instead of uniform 2·off; skip over-cap
cells when the pool cannot reach them).

**The fast-proof plan (next major work item — precompute is NOT the strategy).** Users search arbitrary
shapes (level/class/element/forced/boss), so the proof must be fast on user hardware, not shipped as
static data. The current implementation runs **~2,300 nearly-identical DP passes** (21 AP cells × ~110
crit values), yet the expensive slot/sub/skill convolutions barely depend on (cell, c): the AP axis is
already a state dimension (the target only selects the harvest band) and c enters only through
`graw = (400+c)·m + 5c·K`. Restructure:
1. **One shared 4-D frontier DP** `(di, m, critM, mp)` per world (~6 worlds, parallel across cores) +
   cheap per-(cell, c) harvests. 4-D Pareto dominance is sound for every c at once (graw is monotone in
   m and critM for c ≥ 0), so the ×2,300 redundancy disappears; expected tens of seconds serial.
2. **Two-tier**: the shared pass must relax the (c, cell)-exact conditional-sub gates (∃-style — a sound
   over-count, a few % loose). It eliminates the ~18 easy cells; the EXISTING exact per-cell DP
   (unchanged, battle-tested) confirms only the 1–3 near-incumbent cells.
3. **Stage-aware negative headroom** — apply negative-capable slots first so later stages run at the
   exact band width (kills the 2·off axis blowup), and **dense arrays** instead of
   `HashMap<Long, Frontier>` in the hot loops (classically 3–5×).
Target: a full machine-independent proof of an arbitrary request in **~1–4 minutes on a laptop**, with
an asynchronous badge ("proving…" → "proven optimal"). Forced items become slot pins (which SHRINK the
DP). Precompute demotes to an optional cache for default shapes. The `==`-vs-proven-CP-SAT locks and the
`≥`-soundness panel are the safety net for the rewrite; the exact DP is kept as tier 2, not replaced.

**Progress (2026-07-03, branch `claude/optimistic-lewin-03113f`, `docs/CERTIFICATE_PROD_PLAN.md`):**
- P0.1 lvl-110 oracle banked; P0.2 `WAKFU_MAX_DAMAGE_CERT_STATS` DP-size instrumentation shipped.
- P1.1 **stage-aware headroom** shipped (commit `perf: stage-aware headroom …`): item slots reordered
  biggest-negative-first with per-stage `apUpper`/`critUpper`; DP states ↓ ~1.8× (unrestricted) to
  ~2.5× (weapons-restricted), lvl-110 all cells + lvl-245 cells 13–16 byte-identical.
- P1.2 **n-dominance sweep** attempted and **REVERTED**: sound + byte-identical but nearly inert — the
  `n` axis counts transition subs, each adding *positive* di/graw (ramps trade −DI for +MP, shielded by
  the `mp` axis), so a higher-`n` state is rarely dominated by a lower-`n` one. It removed ~0.001 % of
  dp keys / ~2.3 % of points and *regressed* wall-clock via its O(P²) per-stage sweep. Do not retry as
  specified; the real lever is P1.3 dense storage.

### B3 — single source of truth for sublimation semantics

The CP-SAT objective (`buildSublimationTerms` `:4834`, `perHitDamageScore` `:3548`) and the re-scorers
(`FindClosestBuildFromInputScoring.sublimationFixedContributions`, `FindMostMasteriesFromInputScoring`,
`FindMaxDamageScoring`, and now `BuildSpellDamage`) are **independent transcriptions** of the same rules
(~45 "mirror the solver" comments). This is the fault line that produced **A1** — each new DI/mastery sub must be
credited in *four* places or they drift.

- **Proposed:** one `SublimationContribution` that yields a `stat → magnitude + gate` description from
  `(Sublimation, resolved pre-combat stats, scenario)`; the solver turns it into `Term`s, each scorer sums ints.
- **Caveat (why it's hard, not a quick win):** the objective emits `IntVar` constraints while the scorers do
  scalar int math — they're **two engines by necessity** (a deliberate, test-locked mirror). Only the *decision
  logic* can be shared, not the arithmetic. And "tests still green" does **not** fully prove a CP-SAT model
  refactor is optimum-preserving — needs targeted before/after equivalence checks. Related: `docs/MAX_DAMAGE_PROVABLE_OPTIMUM.md`.

**DONE — the shareable decision predicates (2026-07-04, behaviour-identical, full `./gradlew test` green).** New
`SublimationSemantics.kt` is the single source of truth for the *value-free* decisions both engines were
transcribing independently: (1) `SUPPORTED_SUB_CONDITIONS` — the one set of static conditions the solver reifies
(was duplicated byte-for-byte as the scorer's `SCORE_SUPPORTED_SUB_CONDITIONS`, kept in sync only by a comment —
the literal A1 fault line); and (2) `scenarioGateMatchesCore(gate, mode, scenario, level, wantedElements)` — the
one "does this scenario gate fire" decision. `WakfuBuildSolver.scenarioGateMatches` is now a thin `params`-shaped
adapter over it and the scorer's identical `subScenarioGateMatches` is deleted. Verified equivalent by cases (the
solver always supplies a non-null scenario, so its dropped `scenario == null` branch was dead) and by the
`==CP-SAT` / proven-optimum / P6.1 fuzz suite; `SublimationSemanticsTest` locks the shared gate directly. Net −75
lines of duplicated logic.

**DONE — the condition decision too (2026-07-04, model-preserving, full `./gradlew test` green).** The last
transcribed mirror: scalar `subConditionHolds` and reified `reifyCondition` each hand-wrote the same 13-case
`when(type)` mapping a condition to `(stat, comparison, threshold)`. Extracted `subConditionSpec(cond, level) →
SubConditionSpec` (StatBound / NoOffhandOrTwoHanded / AlwaysApplies) in `SublimationSemantics.kt`; each engine now
evaluates the SAME spec its own way (scorer = int math, solver = reify into `IntVar`). **Model-preserving by
construction**: the reifier still reifies a single-stat bound directly on its `preCombatStat` and only sums for the
multi-stat secondary-masteries case — identical variables / bounds / names / tags, so the CP-SAT model is
byte-identical (zero var added/removed). `DODGE_LT_PCT_OF_LEVEL`'s strict `<` is normalized once to the
integer-equivalent `AT_MOST` bound, so both engines provably share that threshold. `SublimationSemanticsTest` locks
`subConditionSpec`'s modeled types == `SUPPORTED_SUB_CONDITIONS`. The `==CP-SAT` / proven-optimum guards ARE the
before/after equivalence check the caveat asks for, and they pass.

> **Genuinely irreducible (left as two engines, by the caveat — do NOT try to merge):** the per-stat-step /
> percent ROUNDING arithmetic (`StatBuilder.tPercent` reified vs `applyPercent` scalar) and the random-element
> assignment — these emit `IntVar` constraints vs scalar ints, so only the *shape* (already shared via flags) can
> be, not the math. The strongest-element test could share a tiny predicate (low value); left for now.

### B4 — unify the per-family sublimation effect representation

`buildSublimationTerms` (`:4834`) is a per-family `if`-ladder (CONVERSION → `bestElementConcentration` →
`perStatStep` → generic `effects`), mirrored by the scorer ladder + `isModelableSublimation` + `dominationShape`
+ the domain fields on `Sublimation`. Adding the next family (e.g. Critical Secret, per-element DI — see
`AGENTS.md` §5 on the conservative choosable set) touches **5 lockstep sites**.

- **Proposed:** make `Sublimation` carry one ordered `List<SublimationEffect>` where conversion/concentration/
  per-stat-step are `SublimationEffect` subtypes with their own gate; solver + scorer each walk the one list with
  a per-subtype handler → a new family is one subtype + two handlers. Couples with **B3** and **B5**.

**DONE — the representation half (2026-07-04, behaviour-identical, full `./gradlew test` green).** The three
structured bonuses (`conversion` / `perStatStep` / `bestElementConcentration`) are no longer separate nullable
`Sublimation` fields — they are `SublimationEffect` variants (`Conversion` / `PerStatStep` /
`BestElementConcentration`) living inside the one `effects` list, alongside a nested sealed `StatEffect`
(`characteristic` + `magnitudeAtLevel`, implemented by `Flat`/`PercentOfLevel`). The removed fields survive as
**computed accessors** (`Sublimation.conversion` = `filterIsInstance` over `effects`), so all ~20 consumers read
them unchanged; every `effects` iteration that reads a stat filters to `StatEffect` (the structured variants are
consumed only via the accessors — the data only ever held stat effects there before). `sublimations.json` was
migrated in place (subs 5077/5449/7088), the bdata extractor emits the new shape, and
`SublimationReproductionTest` still builds 232/232 subs with the same 47-choosable set (a future regen is a zero
diff). Old saved builds fold their legacy top-level fields into `effects` on load via
`SublimationLegacyFieldsSerializer` (locked by `SublimationSerializationTest`).

> **Still open (the B3-coupled half):** the per-family `if`-ladder in `buildSublimationTerms` / the scorer /
> `isModelableSublimation` / `dominationShape` still exists — it now dispatches off the accessors instead of the
> removed fields. Collapsing those lockstep ladders into ONE per-subtype handler shared by solver + scorer is **B3**
> (the decision-logic source of truth), which this refactor is the prerequisite for.

---

## Cleanup / duplication (mechanical, low risk — good first PRs)

> **DONE (2026-07-04) — C1, C2, C3 all landed, behaviour-identical (full `./gradlew test` green).**
> - **C1**: `private fun Term.scaledRange(domain)` (sign-aware range) + `Term.maxContribution(domain)`
>   (allocation-free max) live next to `Term`; all 11 hand-copied sites now call them — zero raw idioms left.
> - **C2**: one `internal fun Characteristic.foldedToUsableStat()` in `StatFold.kt` replaces the three copies
>   (`effectiveStatForDomination`, `StatBuilder.effectiveStat`, `foldedForSublimation`), all deleted.
> - **C3**: `ActionCatalog.CHARAC_CODE` is now `internal`; `CharacIdCatalog.SCRIPT_NAME_TO_CHARAC` keeps its
>   deliberate 6-key subset but *derives* the values from it (`getValue` — loud on drift), no re-typed entries.

### C1 — the scaled-range idiom is copy-pasted 8+ times *(correctness-adjacent)*

`if (coef >= 0) d.first*coef .. d.last*coef else d.last*coef .. d.first*coef` (and the scalar
`if (coef >= 0) d.last*coef else d.first*coef`) appears at `WakfuBuildSolver.kt` **2339-2341, 2348, 2568,
3356-3358, 3376-3378, 3794, 3806, 3818**. These are **sign-sensitive** bounds feeding CP-SAT big-M constants —
one hand-copy with `first`/`last` transposed is an *unsound domain* and a wrong "proven optimum". `mulRange`
(`:5412`) already computes the range product. Extract `Term.scaledRange()` / `.maxContribution()` and replace all
sites with one call.

### C2 — `effectiveStatForDomination` is a third copy of the `MAX_* → usable` fold

The `MAX_ACTION_POINT/MAX_MOVEMENT_POINT/MAX_WAKFU_POINTS → AP/MP/WP` mapping exists three times:
`effectiveStatForDomination` (`WakfuBuildSolver.kt:562`), `Characteristic.foldedForSublimation`
(`FindClosestBuildFromInputScoring.kt:733`), and `StatBuilder.effectiveStat`. If Ankama adds a `MAX_*` stat, one
copy gets updated and the domination pre-filter silently pins the wrong stat. Consolidate to one shared helper.

### C3 — `CharacIdCatalog.SCRIPT_NAME_TO_CHARAC` duplicates `ActionCatalog.CHARAC_CODE`

Two script-name → `Characteristic` maps in the **same module**: `CharacIdCatalog.kt:51` (6 entries) is a subset of
`ActionCatalog.kt:55` (~30). A mapping fixed in one silently stays wrong in the other, and unmapped ids resolve to
`null` → a sublimation quietly goes forced-only with no error. Reuse `CHARAC_CODE` (widen visibility) or extract a
shared `ScriptNameCharacteristics` table.

---

## Minor / efficiency (nice-to-have)

- **H1 — double full pass in the per-element-DI most-masteries scorer.** `FindMostMasteriesFromInputScoring.kt:87`
  runs `computeCharacteristicsValues` a second full time when per-element DI weights are present, though only the
  random-element roll assignment differs from `firstPass` (`:69`). Hoist the fixed-stat fold; re-run only the
  weighted roll. Gated to the per-element-DI case, so the common path doesn't pay it.
- **H2 — `scenarioElementStrongestVar` cache key.** `WakfuBuildSolver.kt:4917` memoizes by `sub.stateId` though the
  reified "scenario element is strongest" boolean is entirely sub-independent (`bestElementStrongestCache`, `:2924`).
  Latent (1 best-element sub today); with >1 it would mint N identical vars/constraints. Key on a singleton/scenario.
- **Dead no-op guards** in the random-element handling: `if (min(count, 1) == 0) continue` and
  `.filter { min(it.count, 1) > 0 }` are always true (`count ∈ {1,2,3}`). Delete.

---

## Decided — no change

### B5 — `Sublimation`'s solver/decoder fields are decoded game facts, not misplaced policy

Finder flagged `solverChoosable`, `bestElementConcentration`, `perStatStep`, `appliesBeforeCombat`, `scenarioGate`
on the pure-domain `common-lib/Sublimation.kt` as "engine policy in the domain model." On inspection they are all
**decoded game data** (the sub's actual effect / timing), belonging on the type like `effects`/`condition`. And
`solverChoosable` is computed by the extractor (`SublimationBuilder`) and baked into `sublimations.json` — moving it
off the type would force the autobuilder to *re-derive* modelability at load, duplicating the extractor and breaking
the "decode once, bake to JSON" pipeline (`AGENTS.md` §5). **Left as-is by design.**

---

## References

- In-repo: `docs/SOLVER_PERFORMANCE.md` (optimality-preserving speedups + why wall-clock timing is unreliable),
  `docs/MAX_DAMAGE_PROVABLE_OPTIMUM.md` (the per-element enumeration proof), `AGENTS.md` §4–5 (engine + data
  pipeline).
- Produced by a `/code-review max` pass; the A-family fixes + B6/B2a that preceded this backlog are on the same
  branch.
