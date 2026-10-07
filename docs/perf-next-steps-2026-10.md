# Perf next steps — measure-first pass on v38 / data 1.93 (2026-10-02)

Scope: backlog §E (E0 baseline-first, E1 leads) of `docs/perf-review-backlog.md`, on the committed tip
`830bfeef` (release 1.11.0, `CERTIFIER_VERSION` 38, `WakfuData.VERSION` 1.93.1.62). Goal: find what still costs
users time (first build, final build, proof / "proven within X%" badge, badge tightness) and rank the next
mathematical / programmatic levers, validating the top ones with cheap prototypes. **Every prototype lives behind a
default-OFF test seam in this worktree; nothing here is a production change.**

Machine: Apple M5, 10 cores (4P + 6E), 16 GB. "4-core laptop" = the same JVM restricted with
`-XX:ActiveProcessorCount=4` (CP-SAT workers = cores − 1 = 3, certifier threads, DP chunk workers all follow) and
the shipped GUI heap `-Xmx3g` (`gui-compose/conveyor.conf`).

---

## TL;DR

1. **The biggest user-visible problems on a 4-core laptop are COVERAGE, not raw speed.** With the GUI's default
   120 s budget, only the level-110 most-masteries default request is proven. Every max-damage request carrying the
   GUI's default target rows ends with **no proof and no badge** (the AP-cell certificate bails in ~6 ms on any HP /
   resistance / dodge row — even a 0-valued one — because those rows put non-damage rune variables into the model;
   this one is universal: same result on the full 10-core machine), and the level-245 most-masteries default request
   ends with **no proof and no badge** (the MM certificate bails on the RANGE row and on 0-valued rows; on 10 cores
   CP-SAT proves it in 17.7 s, so that gap is low-core only). Free max-damage requests stop at "proven within 1.1 % / 1.6 %": the E8
   construct rescue is gated off by the maximized "distance mastery 1" row — un-gated, it constructs the proven
   optimum 2.7 s after the search at 245 (it still fails at 110 after 269 s).
2. **An exact, ~40-line change makes the most-masteries certificate ~10× faster:** Pareto-pruning each DP stage's
   option list (cross-item slot options, skill-point allocations, knapsack aggregates) is EXACT by monotonicity and
   cuts the S2 full tier from **78.2 s → 8.1 s** (with primitive maps; 4.6 M → 45 k states), quick tier
   **35.6 s → 7.9 s**; on the 4-core profile the GUI's quick → full chain goes **207 s → 19 s**. Bit-identical bound
   (S2 + a 128-case random-pool fuzz, with and without the tightening seams), all CI soundness locks green.
3. **Two cheap, sound tightenings take the S2 badge from +29.56 % to +18.25 %** (lead 1 = start-of-combat crit priced
   inside the DP: −4.1 pt; porting the soft certificate's exact normal-sub packing — signed DI riders, raw CC — :
   −7.3 pt more). For HARD-leg results (what production actually returns on reachable targets — S2 is reachable on
   1.93 data), a targets-met-only collapse takes the badge from **+42.1 % → +23.6 %** (with the packing fix).
   Three other suspects measured NULL (block-gate rounding, the ramp sub riding outside the 10-slot cap, finer CC/HP
   grids — the latter now cheap to test thanks to P2).

Probe legend: **P1** primitive DP maps · **P2** exact stage-option Pareto pruning · **P3** build ring/weapon pair
options once per proof (not prototyped) · **P4** E8 construct gate · **T1** start-of-combat crit dim (assume-CC
worlds) · **T2** exact block dim · **T3** targets-met-only collapse (hard-leg bound) · **T4** ramp sub inside the
10-slot knapsack · **T5** exact normal-sub packing (signed DI, raw CC) · **T6** finer CC / HP grids after P2.

---

## 1. Baseline (E0) — production path, 4-core profile

Harness: `PerfBaselineE0Test` (`autobuilder/src/test/.../PerfBaselineE0Test.kt`), production entry
`WakfuBestBuildFinderAlgorithm.run` (`tuning == null`), then the post-search proof exactly as the GUI chains it
(max-damage: `proveMaxDamageOptimality` → `constructMaxDamageProvenOptimum` on ProvenWithin; most-masteries:
quick → full tier when CP-SAT ended non-OPTIMAL). CRA, runes + subs, max rarity EPIC, 120 s budget.

```shell
WAKFU_E0=1 WAKFU_E0_FIXTURES=MM110,MM245,MD110,MD245,MD110F,MD245F,S2,S4 WAKFU_E0_SECONDS=120 \
  WAKFU_TEST_MAX_HEAP=3g WAKFU_TEST_JVM_ARGS="-XX:ActiveProcessorCount=4" \
  ./gradlew --no-daemon :autobuilder:test --tests '*PerfBaselineE0Test*' --rerun
```

Fixtures: **GUI default rows** = AP 11 / MP 4 / RANGE 4 / CC 25 / distance-mastery 1 / HP 2000 / wind-res 0 /
dodge 0 (`UiState.defaultTargetValues`). **F** = only the distance-mastery row (max-damage "free" request).
**S2** = distance mastery + AP16/MP8/CC100/HP12000 (MM); **S4** = the same targets in max-damage.

| fixture | first build | last improvement | search end | CP-SAT proven | badge (time after search end) |
|---|---|---|---|---|---|
| MM110 (GUI default) | 0.10 s | 7.1 s | 20.6 s | **yes** | — |
| MM245 (GUI default) | 0.01 s | 76.6 s | 121.2 s | no | **Unavailable** (+1.0 s) — MM cert bails on the RANGE / 0-valued rows |
| MD110 (GUI default) | 0.04 s | 28.0 s | 120.3 s | no | **Unavailable** (+6 ms) — AP-cell cert shape bail |
| MD245 (GUI default) | 0.01 s | 71.5 s | 120.9 s | no | **Unavailable** (+6 ms) — same |
| MD110F (free) | 0.004 s | 68.9 s | 120.1 s | no | ProvenWithin **1.10 %** (+5 ms, warm-up hid it); E8 construct **skipped** (gate) |
| MD245F (free) | 0.008 s | 82.8 s | 120.7 s | no | ProvenWithin **1.64 %** (+6 ms); E8 construct **skipped** (gate) |
| S2 (MM frontier) | 0.006 s | 81.2 s | **82.9 s** | **yes** (hard leg) | — |
| S4 (MD frontier) | 0.01 s | 72.5 s | 121.0 s | no | **Unavailable** (+8 ms) — AP-cell cert bail (HP row) |

GUI warm-up (`WakfuBuildSolver.warmUp`, natives already cached): **183 ms**.

Reading:
- **First builds are instant everywhere** (greedy warm start, 4-100 ms). The search itself keeps improving until
  70-83 s at 245 on 3 workers and does not prove anything but MM110 / S2 within 120 s.
- **Data 1.93 made both frontier fixtures reachable**: S2's hard leg proves OPTIMAL in 83 s (hard optimum
  61 760 000 002 856, i.e. score 6 176) and S4's hard leg meets its targets. So in production neither S2 nor S4 reaches
  the SOFT fallback any more; the "S2 +29.56 %" soft badge is a stand-in for truly-unreachable requests, and the
  badge users actually see on these shapes is the hard-leg one (see §4.3, T3).
- The proof machinery is fast where it applies (E10 warm-up hides the AP-cell certificate behind the search); the
  failures are **coverage bails**, not latency (§4.5).

### 1.1 Same fixtures on the full 10-core machine (production default: 9 CP-SAT workers, `-Xmx3g`)

| fixture | first build | last improvement | search end | CP-SAT proven | badge |
|---|---|---|---|---|---|
| MM245 (GUI default) | 0.12 s | 17.7 s | **17.7 s** | **yes** (score 10 993) | — |
| MD110 (GUI default) | 0.06 s | 32.3 s | 120.7 s | no | **Unavailable** (+10 ms) |
| MD245 (GUI default) | 0.02 s | 94.5 s | 121.6 s | no | **Unavailable** (+11 ms) |
| MD245F (free) | 0.01 s | 86.5 s | **92.1 s** | **yes — certificate early stop** (20 811 420) | ProvenOptimal |

So: the MM245 gap (no proof, no badge) is a **low-core** problem (17.7 s to OPTIMAL on 9 workers vs nothing in 120 s
on 3), the max-damage default-row gap is **universal** (no proof and no badge even on 10 cores), and the free MD245
optimum the 4-core run left at "within 1.64 %" is exactly the 20 811 420 that P4's un-gated E8 construct reaches 2.7 s
after a 4-core search (§4.6).

---

## 2. Where the time goes (hot spots)

### 2.1 Most-masteries certificate (`MostMasteriesCertificate.bound`) — the MM badge

Instrument: `WAKFU_MM_STAGE_PROBE=1` (per-stage `in/opts/transitions/out/ms`, seam in `step()`), S2, quick tier,
10 cores. Per world (stage time sum / transitions), baseline boxed `HashMap<Long, Long>`:

| world | stage ms | transitions | final states |
|---|---|---|---|
| main (no cap sub) | 2 838 | 0.70 G | 322 k |
| assume Inflexibilité (AP ≤ 10) | 3 089 | 0.77 G | — |
| assume Constance (CC ≤ 10) | 4 388 | 1.07 G | 339 k |
| assume Mesure III (CC ≤ 50) | **16 191** | **3.69 G** | **1.45 M** |
| total wall (incl. option building, domination, collapse) | **35 559 ms** | 6.2 G | 2.37 M |

Full tier (block dim, the GUI's second pass): **78.2 s**, 4.6 M states. The GUI chains quick + full = ~114 s at
10 cores (and far more on 4 cores: the chunked stage advance runs on cores − 1 workers).

Findings:
- **Stage options are never Pareto-pruned across items or across skill allocations.** Each single slot applies
  ~3-4 k (item × rune-composition) options pruned only *per item*; `skills-Strength` applies **1 722** point
  allocations (only `distinct()`), the normal-sub knapsack emits 223 aggregates. Most of them are strictly dominated
  (a cheaper allocation that spends fewer points, a rune composition beaten by another item's) and each one spawns
  dominated states that every later stage re-expands — `skills-Strength` alone is 1.4 G transitions in the Mesure III
  world.
- **Boxed `HashMap<Long, Long>` + `Long?` returns.** Every transition boxes the probed key and allocates on insert;
  `jstack` caught the hot loop inside `HashMap$TreeNode.find` — Long.hashCode XOR-folds the packed fields onto each
  other, so the maps **treeify** (≥ 8 keys per bin). Measured cost: ~1.4-1.9× (§4.1).
- **Outside the stages**: with the DP fixed, ~6 s of the remaining 7.9 s quick tier is option construction repeated
  per world — 6.6 M ring-pair `Opt`s (+ per-pair prune) and 0.44 M weapon pairs, × 4 worlds.

### 2.2 Max-damage
- AP-cell certifier: already primitive (`DenseDp`, packed frontiers), fast tier ~3 s at 110 (6 worlds × 0.9-2.0 s
  with `WAKFU_MAX_DAMAGE_CERT_TIMING=1`), hidden behind the search by E10. Settled as add-call-volume-bound
  (`maxdamage-perf-current-bottleneck`); not re-opened.
- The user-visible max-damage problem is the **shape-level bail** on default target rows (§4.5) and the E8 gate
  (§4.6), not certifier speed.
- `MaxDamageSoftCertificate` shares the MM DP's structure (boxed maps, unpruned stage options); with 1.93 the S4
  frontier is reachable, so its pipeline (oracle + DP cascade, 1-3 min) now only serves truly unreachable targets.

### 2.3 CP-SAT on low cores
3 workers do not prove any 245 shape in 120 s (E0). Solver knobs are settled dead (`SOLVER_PERFORMANCE.md` §6
final verdict, MM plan §8); the established lever for low-core proofs is a certificate — hence §4's focus on making
the certificates applicable, fast and tight.

---

## 3. Ranked ideas

Score = expected user-visible gain × confidence ÷ cost. "Settled?" = how it differs from the do-not-retry list.

| # | idea | gain (measured / expected) | conf. | cost | soundness | settled? |
|---|---|---|---|---|---|---|
| 1 | **P2 — exact Pareto pruning of every MM DP stage's option list** | **measured** S2 full 78.2 → 8.1 s (with P1), quick 35.6 → 7.9 s; states 4.6 M → 45 k; bound bit-identical | high | S (~40 LOC) | exact: transitions + fold monotone in every dim (argument §4.2); 4 CI locks green with seam on | new — never pruned across items/skills; *not* the max-damage "n-dominance" (state dominance along the sub-count axis, ~2 % pruned) |
| 2 | **T1 + T5 MM tightening** (SOC crit priced in assume-CC worlds; exact signed-DI / raw-CC normal-sub packing) | **measured** S2 soft badge +29.56 % → **+18.25 %** | high (bound), med (needs dedicated fuzz) | S-M (~80 LOC) | over-count preserved (§4.3); CI locks green with seams on; CERTIFIER bump | new — T5 is the soft cert's shipped `exactNormalSubPacking`, never ported to MM; T1 = sibling lead 1 |
| 3 | **T3 targets-met collapse for HARD-leg results** | **measured** S2 hard-leg badge +42.1 % → +32.2 % (T3), **+23.6 %** (T3 + T5) | high | S (collapse filter + a result flag) | a targets-met build's over-counted reads meet every target ⇒ never filtered; seeded lock 24/24 | new (consumer-level) — the M3 lesson "bound the targets-constrained problem" was never applied to the production comparison |
| 4 | **Coverage: AP-cell certificate on HP / resistance / dodge rows** | every GUI-default max-damage request goes Unavailable → badge | med (tightness depends on whether targets bind) | M | treat non-damage rune types as damage-neutral options (over-count) + don't model runes for 0-valued rows; CERTIFIER bump | new — the bail is the rune-stat `allowed` check, not the (settled) target-axes question |
| 5 | **Coverage: MM certificate on RANGE + 0-valued rows** | GUI-default MM245 on 4 cores gets a badge; **measured upper estimate ≤ +12.1 %** | med-high | S-M (RANGE dim = the AP/MP pattern, affordable after #1) | weight-0 rows add 0 to the fold (exact skip); a RANGE dim over-counts like AP/MP | new |
| 6 | **E8 construct gate = required targets only** | **measured** free MD245 (mastery row): "within 1.64 %" → constructed ProvenOptimal in 2.7 s; MD110: no gain (269 s, fails) | high | XS (one predicate) + a wall cap on the fallback | construct only returns builds reaching the proven bound | new |
| 7 | **E10-for-MM**: start the (incumbent-free) MM certificate alongside the search | badge at search end instead of +8-16 s (after #1) / +114 s today | high | S (orchestration, single-flight memo by params) | no bound change | mirrors shipped E10 (max-damage) |
| 8 | P1 — primitive open-addressing maps in both certificate DPs | measured ×1.4 (quick) / ×1.9 (full) alone; +16 % on top of #1 | high | S | exact (container swap) | max-damage's DenseDp port (~6 %) was a different, frontier-bound DP |
| 9 | P3 — build ring / weapon pair options once per proof | ~6 of the 7.9 s left after #1+#8 | med | S | exact | — |
| 10 | P2 on `MaxDamageSoftCertificate` | likely ≥ 5× DP wall | med | M | exact for GLOBAL reads only — per-band / target-cell / below-target region reads need dominance restricted to the same region | — |
| 11 | Solver model: drop 0-valued and statically-met required rows (and their rune variables) | smaller CP-SAT model on every GUI-default request; at 245 the HP 2000 row is met by base HP 2 500 (only 4 items in the data carry −HP, all ≤ lvl 50), so dropping it also restores `maxDamageRuneChoiceCollapse` and the AP-cell certificate there | low-med (unmeasured A/B) | S | a row whose reachable LOWER bound already meets the target is a redundant constraint; a 0-valued row has weight 0 | not a knob — a model reduction; needs the 1w+interleave det A/B before shipping |

Leads checked and **falsified** (cheap, documented so nobody repeats them):
- **T2 — exact block dim (BLOCK_STEP 1)**: main-world bound unchanged (84.996 T). The binding path opens Mesure's
  BLOCK ≥ 40 gate with real Luck block points (BLOCK_PERCENTAGE 20) — not rounding phantoms.
- **T4 — pack the ramp carrier (Poids Plume III) inside the 10-slot knapsack**: the binding path did carry 10 knapsack
  subs + the flagged ramp sub (11 normal subs), but enforcing the shared cap leaves the bound unchanged (an
  equivalent path substitutes — the documented ensemble behavior).
- **T6 — finer grids, now affordable after P2** (on top of T1 + T5): CC step 1 → main world 80.090 T unchanged
  (assume worlds tighten — Mesure III 55.4 → 49.1 T — but they no longer bind; 67 k states, 8 s); HP step 25 → main
  unchanged (27-44 s per world); CC 1 + HP 25 together runs out of the 6 GB test heap. Re-confirms the grid
  invariance of MM plan §8.14.1 at v38 — the remaining residual is not bucket loss.

---

## 4. Probe results

All MM probes on S2 (lvl-245 CRA, distance mastery + AP16/MP8/CC100/HP12000, runes + subs, domination pool) unless
stated; references: soft optimum 67 728 953 322 880 (re-banked v38/1.93), hard optimum 61 760 000 002 856 (E0,
production hard leg CP-SAT OPTIMAL, converted to soft units).

### 4.1 P1 — primitive DP maps (`LongLongMaxMap`, seam `primitiveDp` / `WAKFU_CERT_PRIMITIVE_DP=1`)
Same JVM, 10 cores, bit-identical bounds asserted:

| tier | boxed HashMap | primitive | P2 only (boxed) | P1 + P2 |
|---|---|---|---|---|
| quick | 35 559 ms | 22 174-25 939 ms | 9 386 ms | **7 930 ms** |
| full | 78 228 ms | 40 622 ms | — | **8 091 ms** |
| quick, **4-core profile** (`ActiveProcessorCount=4`, `-Xmx3g`) | 53 741 ms | — | — | **8 681 ms** (6.2×) |
| full, **4-core profile** | 153 607 ms | — | — | **10 326 ms** (14.9×) |

On the 4-core laptop profile the GUI's quick → full chain drops from **207 s to 19 s** (or ~10 s if the now-equal-cost
full tier runs alone); the boxed full tier fits the shipped 3 GB heap (no OOM) but is the slowest piece of the whole
MM badge path.

### 4.2 P2 — exact stage-option Pareto pruning (seam `pruneStageOptions` / `WAKFU_MM_PRUNE_STAGE_OPTS=1`)
Per-stage options before → after (main world, quick tier): AMULET 3 175 → 83, BOOTS 3 549 → 73, HELMET 3 924 → 69,
CHEST 3 186 → 46, PETS 112 → 3, EMBLEM 164 → 6, subs-normal 223 → 5, skills-Strength 1 722 → 41, Luck 21 → 1,
Major 8 → 1, Intelligence 62 → 1. Final states per world: main 322 k → 858, Mesure III 1.45 M → 34 k.
Bound and core bit-identical in both tiers (87 747 187 749 999 / 11 885). Bit-identity fuzz
(`manual P2 pruning is bit-identical on random seeded pools`, `WAKFU_P2_FUZZ=1`, no CP-SAT): 8 random pools (1-2 items
per slot incl. 1H/off-hand/2H, epic/relic items, signed AP and −MAX_AP lines, block, 0-2 sockets, the real sub
catalog and runes) × 4 target shapes (none / AP+HP / MP+CC / all four) × quick+full tier × with/without the T3
filter = **128/128 bit-identical** folded and core bounds.

**Why it is exact.** Option `o'` dominates `o` (same flags: epic/relic/requires-*/ramp/capKind/block requirement;
every additive field ≥, LOW assume-world fields ≤, M ≥). Then for any state `s`, `o'` is applicable iff `o` is, and
`applyOne(s, o')` is ≥ `applyOne(s, o)` on every over-counted dim and ≤ on the LOW dims, with ≥ M (saturating adds,
`ceilDiv`, the %HP scaling are all monotone). Every later transition (incl. the BLOCK_AT_LEAST gate, which only
gets easier with more block) and the collapse fold (core, penalty bucket, world-B subsets, assume-world filters,
T3's targets-met filter) are monotone in the same order, so the dominated option's descendants can never beat the
dominating one's: the global max is unchanged. The strict rule (`dominates && !reverse`) keeps stat-equal options
with distinct provenance, so binding-path reconstruction still works (verified, §4.4).

### 4.3 MM tightness — T1, T3, T5 (and the null T2/T4)
Per-world bounds (`PerfProbeMmTightnessTest`, P1 + P2 on, ~2 s per world read):

| arm | main | Inflexibilité | Constance | Mesure III | **overall** | vs ref |
|---|---|---|---|---|---|---|
| baseline (v38) | 84.996 T | 65.646 T | 46.884 T | **87.747 T** | 87.747 T | soft **+29.56 %** |
| T1 (SOC crit dim) | 84.996 T | 65.646 T | 29.719 T | 57.171 T | 84.996 T | soft **+25.50 %** |
| T2 (block step 1) | 84.996 T | = | = | = | 87.747 T | +29.56 % (null) |
| T4 (ramp sub in 10-cap) | 84.996 T | = | = | = | 87.747 T | +29.56 % (null) |
| T5 (exact packing) | 80.090 T | 64.831 T | 46.257 T | 82.692 T | 82.692 T | soft +22.09 % |
| **T1 + T5** | **80.090 T** | 64.831 T | 29.719 T | 55.383 T | **80.090 T** | soft **+18.25 %** |
| T3 (hard-leg bound) | 81.620 T | 0 | 0 | 69.010 T | 81.620 T | hard **+32.16 %** (today: soft bound vs hard optimum = **+42.08 %**) |
| **T3 + T5** (± T1) | **76.320 T** | 0 | 0 | ≤ 69.010 T | **76.320 T** | hard **+23.58 %** |

- **T1** (`socCritDim` / `WAKFU_MM_SOC_DIM=1`): in an assume-CC world the fold credited the capped crit as
  `threshold + own + outsideReadMax(CRITICAL_HIT)` — the SOC crit of *every* choosable non-epic sub at max copies
  (≈ +42 crit, budget-free). T1 tracks the SOC crit of the subs a path actually carries in a 6-bit saturating dim
  (cap `ceil((target − threshold)/ccStep)` buckets; saturating there is exact for `min(read, target)`), knapsack
  included; only the never-staged subs, ramps and passives stay in the constant. Sound: the dim over-counts the
  carried SOC crit (UP-rounded), every staged sub's SOC line feeds it, world-B subs' lines ride `extra`.
- **T5** (`exactKnapsack` / `WAKFU_MM_EXACT_KNAPSACK=1`): a port of the soft certificate's shipped
  `exactNormalSubPacking`. The knapsack keeps DI signed (offset-stored; Vélocité/Swiftness-style −10 DI riders
  now pay) and CC raw (one `ceil` at the output instead of one per copy — the binding path claimed CC 100 with
  "+50 CC from subs", built from +3/+9 CC subs each inflated to a full 10-CC bucket). Sound: per-subset sums are
  exact; the main DP floors DI at 0 (over-count) and the fold clamps DI at the solver's −50 floor; a negative
  rider is priced only for a CARRIED sub, and an inert (condition-failed) carrier is covered by the without-it
  path, which keeps a slot free and dominates it.
- **T3** (`bound(targetsMetOnly = true)`): keeps only collapse states whose (over-counted) reads meet every
  required target, i.e. bounds the HARD leg's feasible set — which is exactly what a hard-leg `isOptimal` and its
  badge mean. Today `proveMostMasteriesQuality` compares a converted hard-leg incumbent against the SOFT bound,
  which includes target-missing builds the hard leg deliberately excludes — structurally loose by at least the
  soft/hard optimum gap (S2: 67.73 T vs 61.76 T = +9.7 % even for a perfect soft bound). Assume worlds whose capped
  constant cannot reach a target vanish (Inflexibilité AP ≤ 10, Constance CC ≤ 10). Production wiring needs a
  `SolverResult` flag "hard-leg emission" (the max-damage sibling `maxDamageHardConstraintsMet` exists).

Soundness evidence (fast locks first): the four CI locks of `MostMasteriesCertificateTest` (3-seed soft fuzz,
conditional-sub A1/A2, hard-leg stamping, the 8-fixture pre-release review set incl. `soc-crit` and `block-only`)
pass with **all** seams on (`WAKFU_CERT_PRIMITIVE_DP WAKFU_MM_PRUNE_STAGE_OPTS WAKFU_MM_SOC_DIM
WAKFU_MM_EXACT_KNAPSACK WAKFU_MM_RAMP_IN_KNAPSACK`), several of them tight (bound == incumbent). New T3 lock
(`manual T3 targets-met bound covers the pinned hard-leg optimum on seeded pools`): 24/24 seeds × target shapes,
T3 ≥ pinned CP-SAT hard optimum. Caveat: those seeded targets are easily met (T3 == soft there); before shipping,
add binding-target fixtures and a negative-DI-rider / SOC-crit-carrier fixture.

Relation to the sibling's two leads (MM plan §8.16): lead 1 (price start-of-combat crit in the DP) = T1, measured
−4.06 pt exactly as predicted (the main world becomes binding at 84.996 T). Lead 2 (bisect the main world's v37→v38
+14.9 %) was answered by a binding-path read instead of a bisect: the v38 main world's binding path is a plausible
build whose remaining phantoms are knapsack-level (per-copy CC ceil, free negative-DI riders) — both pre-date v38;
T5 removes 4.9 T of the main world's 84.996 T (−5.8 %); the Mesure gate opening (DI +10) is honest (T2 null).

### 4.4 Binding-path read (provenance harness, now ~15 s with P2)
`WAKFU_MM_M3V2_PATH=1` + seams: the main-world binding path is a plausible build (rings Soeur d'âme corrompue +
Souvenir ancestral, La Mobbaguette + Dagues Tylo, Luck BLOCK 20 + CC 20 opening Mesure's gate, Major AP/MP/DI, %HP
61) whose phantoms were the per-copy CC ceil and the free negative-DI riders inside the knapsack (fixed by T5) — the
block gate and the 11th sub were real or substitutable (T2/T4 null).

### 4.5 Max-damage default rows → shape-level bail (diagnosis harness `WAKFU_E0_DIAG=1`)
`WakfuBuildSolver.maxDamageCertificate` at level 110, one row at a time:

| rows | result |
|---|---|
| distance-mastery only ("free") | certifies, max cell 1 657 830 (3.0 s) |
| AP 11 / MP 4 / RANGE 4 / CC 25 (each alone) | certifies (2.8 s) |
| **HP 2000** alone, **wind-res 0** alone, **dodge 0** alone | **all 21 cells bailed** (~130 ms) |

Cause: `relevantRuneStats` models a rune variable for every target row (0-valued included); HP / resistance / dodge
runes switch off `maxDamageRuneChoiceCollapse`, and `certifyMaxPerHitAtApPass` bails when a rune stat is outside
`{range-band mastery, critical mastery}` (`MaxDamageCertifier.kt` "Bail on rune models we cannot mirror").
Consequence: every max-damage request with the GUI's default rows (and S4) gets `Unavailable` on any machine where
CP-SAT does not prove in-budget. Fix sketch (not prototyped): (a) skip 0-valued rows in `relevantRuneStats`
(no objective weight, trivially met; also shrinks the CP-SAT model); (b) in the certifier, treat non-damage rune
types as damage-neutral options (a socket holding an HP rune contributes no damage — crediting the best damage rune
instead over-counts, sound). Expected tightness: the AP-cell certificate is target-blind except AP; MD245's default
incumbent (18.23 M) is 11 % below the free one (20.48 M), so where RANGE/MP bind the badge would read ~"within
14 %" — still better than nothing, and ProvenOptimal where the rows do not bind.

### 4.6 E8 construct gate (probe P4)
`dpConstructProvenOptimum` returns null whenever `targetStats.any { target > 0 }` — which includes a MAXIMIZED
mastery row (the GUI's "distance mastery 1"), ignored by the max-damage objective. Probe: the same construct on the
mastery-row-free (objective-identical) params, with E0's 4-core incumbents (`WAKFU_P4=1`):

| level | incumbent | cold ledger (3 threads) | max cell | construct | result |
|---|---|---|---|---|---|
| 245 | 20 475 270 | 16.2 s | 20 811 420 | **2.7 s** | **success — constructed proxy 20 811 420 = the bound ⇒ ProvenOptimal** |
| 110 | 1 595 415 | 8.7 s | 1 634 835 | 269 s | fail (restricted re-solve short; det-300 full-pool fallback exhausted) |

So the one-predicate fix (`isRequiredMostMasteriesTarget() && target > 0`) turns the 4-core free 245 request from
"proven within 1.64 %" into the proven optimum ~3 s after the search (the ledger is already warm from E10). At 110 the
rescue costs ~4.5 min of async single-thread CPU and fails — the fallback should get a wall cap / cancellation hook
before the gate is relaxed (it already runs off the UI thread).

### 4.7 MM coverage estimate for the GUI default at 245
Upper estimate of a RANGE-aware badge: the T3 (+T1+T5) core bound with the RANGE row and the 0-valued rows dropped
(a relaxation ⇒ ≥ the RANGE-aware bound) vs the E0 4-core hard-leg incumbent core (10 469): **11 737 ⇒ ≤ +12.1 %**
(13.3 s per tier with P1+P2). Dropping the 0-valued rows is exact (weight 0 ⇒ no fold contribution); RANGE needs a
small saturating dim (target 4 ⇒ 3 bits), now affordable.

---

## 5. Recommended next actions

1. **Ship P2 (+ P1) in `MostMasteriesCertificate`** — exact, ~10× on the badge DP, bit-identical on S2; run the full
   `MostMasteriesCertificateTest` locks + the oracle harness, bump `CERTIFIER_VERSION` for traceability. Then fold the
   quick/full two-tier into one full pass (the full tier now costs what the quick tier did) and add E10-for-MM
   (start it with the soft fallback / after N s without a proof). Expected: MM badge at search end instead of
   +114 s (10 cores) / several minutes (4 cores).
2. **Ship the MM tightness trio T5 → T1 → T3** (in that order, one seam + one verdict per commit, each with a
   dedicated binding fixture in the CI lock): S2 soft badge +29.6 % → +18.3 %, hard-leg badge +42.1 % → +23.6 %.
3. **Close the coverage holes found by E0** (cheapest first): E8 gate on required targets only (one predicate);
   MM weight-0 rows (exact skip) + a RANGE dim; AP-cell certificate damage-neutral non-damage runes + no runes for
   0-valued rows. These turn "no badge at all" into a badge for the GUI's default requests.

Shipping notes for #1 (P2/P1): keep `paretoPrune` + `applyOneRaw` as the only code paths (drop the boxed
`applySequential`), keep `prune()`'s strictness rule (provenance-safe), cap the skyline at ~200 k options (the
ring-pair list stays per-pair pruned), and port the same two changes to `MaxDamageSoftCertificate` only after
restricting its dominance to same-region options for the per-band / target-cell / below-target reads. Locks: the
four `MostMasteriesCertificateTest` CI locks (already green under the seams) + the P2 bit-identity fuzz.

---

## 6. What is in this worktree (uncommitted, all default-OFF)

- `LongLongMaxMap.kt` (P1) + `primitiveDp` seams in both certificates (`applyOneRaw` refactor keeps one source of
  truth for the transition).
- `MostMasteriesCertificate.kt`: `pruneStageOptions` (P2), `socCritDim` (T1, with its own `socStep` so the 6-bit
  field stays sound at any CC grid), `blockStepOverride` + 6-bit block field (T2), `targetsMetOnly` (T3),
  `rampInKnapsack` (T4), `exactKnapsack` (T5), `stageProbe` / `dominanceProbe` instruments. All env-initialized
  (`WAKFU_CERT_PRIMITIVE_DP`, `WAKFU_MM_PRUNE_STAGE_OPTS`, `WAKFU_MM_SOC_DIM`, `WAKFU_MM_RAMP_IN_KNAPSACK`,
  `WAKFU_MM_EXACT_KNAPSACK`, `WAKFU_MM_STAGE_PROBE`, `WAKFU_MM_DOMINANCE_PROBE`) so existing locks can run under them.
  With every seam off the code is bit-identical to the tip (S2 off-arm bound 87 747 187 749 999 reproduced; the six
  `MaxDamageSoftCertificateTest` CI tests pass).
- `autobuilder/build.gradle.kts`: `WAKFU_TEST_JVM_ARGS` hook (test JVM args, e.g. `-XX:ActiveProcessorCount=4`).
- Harnesses: `PerfBaselineE0Test` (E0), `PerfProbeCertDpTest` (P1/P2 A/B, E0 bail diagnosis, P4),
  `PerfProbeMmTightnessTest` (T1-T6 per-world reads — arms `t1..t5`, `c1`, `h25` combine by substring —, T3 seeded
  lock, P2 bit-identity fuzz, GUI coverage estimate).

Commands (each in a fresh `--no-daemon` JVM; println lands in the JUnit XML):

```shell
# P1/P2 A/B (arms: off | on | on+p2 | off+p2)
WAKFU_P1=1 WAKFU_P1_WHAT=mmQuick,mmFull WAKFU_P1_ARMS=on+p2,on,off WAKFU_TEST_MAX_HEAP=6g [WAKFU_MM_STAGE_PROBE=1] \
  ./gradlew --no-daemon :autobuilder:test --tests '*PerfProbeCertDpTest*MM certificate*' --rerun
# tightness probes
WAKFU_TPROBE=1 WAKFU_TPROBE_WHAT=base,t1,t5,t1t5,t3,t3t5 WAKFU_CERT_PRIMITIVE_DP=1 WAKFU_MM_PRUNE_STAGE_OPTS=1 \
  WAKFU_TEST_MAX_HEAP=6g ./gradlew --no-daemon :autobuilder:test --tests '*PerfProbeMmTightnessTest*tightness*' --rerun
# soundness under all seams
WAKFU_CERT_PRIMITIVE_DP=1 WAKFU_MM_PRUNE_STAGE_OPTS=1 WAKFU_MM_SOC_DIM=1 WAKFU_MM_EXACT_KNAPSACK=1 \
  WAKFU_TEST_MAX_HEAP=6g ./gradlew --no-daemon :autobuilder:test --tests '*MostMasteriesCertificateTest*' --rerun
WAKFU_TPROBE_LOCK=1 WAKFU_TPROBE_GUI=1 [same seams] ./gradlew --no-daemon :autobuilder:test --tests '*PerfProbeMmTightnessTest*' --rerun
# max-damage default-row bail bisect
WAKFU_E0_DIAG=1 WAKFU_TEST_MAX_HEAP=6g ./gradlew --no-daemon :autobuilder:test --tests '*PerfProbeCertDpTest*bail*' --rerun
# P2 bit-identity fuzz (add WAKFU_MM_SOC_DIM=1 WAKFU_MM_EXACT_KNAPSACK=1 to fuzz under the tightening seams)
WAKFU_P2_FUZZ=1 WAKFU_TEST_MAX_HEAP=6g ./gradlew --no-daemon :autobuilder:test --tests '*PerfProbeMmTightnessTest*bit-identical*' --rerun
# 4-core certificate timing (shipped GUI heap)
WAKFU_P1=1 WAKFU_P1_WHAT=mmQuick,mmFull WAKFU_P1_ARMS=on+p2,off WAKFU_TEST_MAX_HEAP=3g \
  WAKFU_TEST_JVM_ARGS="-XX:ActiveProcessorCount=4" ./gradlew --no-daemon :autobuilder:test --tests '*PerfProbeCertDpTest*MM certificate*' --rerun
# E8 construct probe
WAKFU_P4=1 WAKFU_TEST_MAX_HEAP=6g ./gradlew --no-daemon :autobuilder:test --tests '*PerfProbeCertDpTest*P4*' --rerun
```

---

## 7. Not measured / caveats

- 4-core numbers are a simulation (`ActiveProcessorCount=4` on an M5 — the OS still schedules on P-cores); one run
  per fixture (wall-clock, production path), so treat "last improvement" times as ±20 %.
- P1/P2 timings are 10-core wall, same JVM, single runs per arm (the DP is deterministic; JIT warm-up favours later
  arms, which here are the slower baselines — conservative for the claimed speedups).
- The soft certificate (`MaxDamageSoftCertificate`) P1/P2 A/B was not run (time budget); its P2 transfer needs the
  region-read audit noted in §3 #10.
- The AP-cell certificate coverage fix (§4.5) and the RANGE dim (§4.7) are estimated, not prototyped; idea #11 (drop
  0-valued / statically-met rows from the CP-SAT model) needs its 1w+interleave det A/B.
- The T3 seeded lock's targets are easily met on its pools (T3 == soft there): it checks soundness, not the
  binding-target regime — add binding fixtures before shipping T3.
- P3 (ring/weapon pair options built once per proof) and E10-for-MM are reasoned from the stage profile, not
  prototyped.
- Total measurement wall ≈ 1 h 45 min (E0 4-core 14 min, E0 10-core 6 min, P1/P2/T-probes ~25 min, locks/fuzz ~20 min,
  P4 5 min, 4-core cert timing 4 min); never two timed runs at once; only the other session's idle daemon was running.
- No production default was changed and nothing was committed.

---

## 8. Re-baseline on v50 (main + B1 + A1, 2026-10-03)

Scope: the E0 of §1 / §1.1 re-run on `9a698f2d` = main `27fc907e` (PR #210, `CERTIFIER_VERSION` 48) + the B1 fix
(`a8528ec7`, v49) + the A1 fix (v50), i.e. what main becomes once PR #212 merges. `WakfuData.VERSION` is unchanged
(1.93.1.62) and the searched CP-SAT models are unchanged (the only solver-model diff since v38 is the floor of the
SOFT penalty table, which hard legs never use). So every delta below is the v39–v50 certificate / orchestration wave —
penalty floor (39), ~7× faster MM bound (40), tighter MM badge (41–43), default-row max-damage badge (44), E8 gate +
60 s cap, MM bound computed in the search's tail (plan §8.19), RANGE / 0-valued rows (45–47), relaxed aux world (48),
the B1 / A1 soundness fixes (49 / 50) — plus run-to-run noise.

Protocol as §1: Apple M5 (10 cores = 4P + 6E, 16 GB); "4-core" = `-XX:ActiveProcessorCount=4 -Xmx3g`, "10-core" = no CPU
restriction, `-Xmx3g`; CRA, runes + subs, EPIC, 120 s budget; the eight §1 fixtures, **one run per fixture** (so "last
improvement" is ±20 % and CP-SAT proof times are heavy-tailed — repeated for the three most-masteries fixtures only,
§8.2), each started with cold certificate caches (a session's first request of that shape); the shared Gradle lock
held exclusively by the measuring run, desktop background apps not controlled.

**Harness** — `PerfBaselineE0Test` (default OFF: `WAKFU_E0=1`, `@Tag("manual")`), adapted to follow what
`BuildSearchModel` runs after a search:
- *max-damage*: `proveMaxDamageOptimality`; on ProvenWithin the badge is already on screen and
  `constructMaxDamageProvenOptimum` (E8: `isFreeMaxDamageShape`, 60 s wall cap) runs behind it; then, if it built
  nothing, the silent `refineMaxDamageOptimality` (`WAKFU_E0_REFINE=1`, capped at 25 min). Reported: time to the badge
  after the search end, E8 outcome + time, time to ProvenOptimal.
- *most-masteries*: the quality bound is computed in the search's tail (`MostMasteriesBoundCache`, one full-tier pass
  started `max(30 s, budget − 30 s)` = 90 s into a 120 s search, on ONE DP thread while CP-SAT runs). A monitor thread
  timestamps when that compute starts / lands; `proveMostMasteriesQuality` is then called at the search end and timed
  (only when CP-SAT ended non-OPTIMAL, like the GUI).
- New env knobs: `WAKFU_E0_RUNES_SUBS=0`, `WAKFU_E0_REFINE_CAP_S`, `WAKFU_E0_SECONDS` as a comma list,
  `WAKFU_E0_REPS`, `WAKFU_E0_LOG` (live log); fixtures `SAC230` (production request of the known minutes-long
  refinement shape) and `SAC230R` (the same shape routed into the soft-leg branch like `MaxDamageSoftCertificateTest`,
  §8.4) run only when named.

### 8.1 Tables

**4-core, runes + subs** (the §1 table; v38 value in brackets):

| fixture | first build | last improvement | search end | CP-SAT proven | badge (time after search end) |
|---|---|---|---|---|---|
| MM110 (GUI default) | 0.10 s (0.10) | 32.6 s (7.1) | 35.0 s (20.6) | **yes** (yes) | — |
| MM245 (GUI default) | 0.010 s (0.01) | 66.4 s (76.6) | **70.3 s** (121.2) | **yes** (no) | — (was **Unavailable**) |
| MD110 (GUI default) | 0.03 s (0.04) | 34.4 s (28.0) | 120.3 s (120.3) | no (no) | ProvenWithin **9.19 %** (+0.15 s) (was Unavailable) |
| MD245 (GUI default) | 0.008 s (0.01) | 65.4 s (71.5) | 121.0 s (120.9) | no (no) | ProvenWithin **13.57 %** (+0.90 s) (was Unavailable) |
| MD110F (free) | 0.004 s (0.004) | 67.9 s (68.9) | 119.8 s (120.1) | no (no) | ProvenWithin **0.89 %** (+2 ms) (1.10 %); E8 construct **ran 60.4 s (the cap) and failed** (was skipped) |
| MD245F (free) | 0.006 s (0.008) | 48.5 s (82.8) | **48.5 s** (120.7) | **yes**† (no) | **ProvenOptimal** (+2 ms) (was ProvenWithin 1.64 %) |
| S2 (MM frontier) | 0.007 s (0.006) | 46.0 s (81.2) | **46.0 s** (82.9) | **yes** hard leg (yes) | — |
| S4 (MD frontier) | 0.008 s (0.01) | 38.4 s (72.5) | 120.8 s (121.0) | no (no) | ProvenWithin **16.29 %** (+0.83 s) (was Unavailable) |

How the max-damage rows read:
- † "proven" here is the certificate's, not CP-SAT's own: the last improvement is the ledger ceiling itself (proxy
  20 811 420) one tick before the final `isOptimal` emission, i.e. the E10 warm-up ledger's argmax cell was E8-constructed
  **inside the search** (`maybeConstructProvenOptimum`), which ends the search proven; the post-search call is a no-op.
- The E10 certificate warm-up (started on the first incumbent, ONE certifier thread while CP-SAT runs) landed at 41.7 s
  (MD110), 117.0 s (MD245: 3 s before the end, so its badge waits 0.9 s), 85.8 s (MD110F), 79.4 s (S4); the prove call
  after the end then costs 2 ms – 0.9 s.
- The GUI-default and frontier rows all print the SAME bound as the free request: 20 811 420 at 245 (the free optimum
  MD245F constructs; back-computed from the three badges: MD245 18 325 500 × 1.135654, S4 17 895 630 × 1.162933,
  10-core MD245 19 000 000 × 1.095338) and 1 612 935 at 110 (MD110F 1 598 700 × 1.008904, MD110 1 477 155 × 1.09192).
  "9.19 % / 13.57 % / 16.29 %" is therefore (free optimum ÷ incumbent − 1): it contains the cost of AP 11 / MP 4 /
  RANGE 4 / CC 25 / HP 2000 (or of the frontier rows), not only the search gap. And the incumbent is noisy: the same
  request reads 9.19 % on 4 cores and 13.91 % on 10 cores at 110 (incumbents 1 477 155 vs 1 416 020), 13.57 % vs 9.53 %
  at 245 (18 325 500 vs 19 000 000) — the 9-worker run is not better, and ±4 % of incumbent noise moves the badge by ±4 pts.
- `refineMaxDamageOptimality` returned null in ≤ 2 ms on every ProvenWithin fixture: all four are hard-leg or free
  results, and the refinement only applies to soft-leg (target-missing) incumbents (§8.4).

**10-core, runes + subs** (the §1.1 table; v38 in brackets):

| fixture | first build | last improvement | search end | CP-SAT proven | badge |
|---|---|---|---|---|---|
| MM245 (GUI default) | 0.12 s (0.12) | 11.9 s (17.7) | **11.9 s** (17.7) | yes (yes) | — |
| MD110 (GUI default) | 0.04 s (0.06) | 40.1 s (32.3) | 120.4 s (120.7) | no (no) | ProvenWithin **13.91 %** (+0.21 s) (was Unavailable) |
| MD245 (GUI default) | 0.011 s (0.02) | 115.3 s (94.5) | 121.1 s (121.6) | no (no) | ProvenWithin **9.53 %** (+1.14 s) (was Unavailable) |
| MD245F (free) | 0.007 s (0.01) | 75.9 s (86.5) | **76.1 s** (92.1) | yes (yes) | ProvenOptimal (in-search construct; v38: certificate early stop) |

**4-core, runes + subs OFF** (new; all eight fixtures, 120 s budget):

| fixture | first build | last improvement | search end | CP-SAT proven | badge |
|---|---|---|---|---|---|
| MM110 | 0.10 s | 1.8 s | 1.8 s | yes | — |
| MM245 | 0.010 s | 4.0 s | 4.0 s | yes | — |
| MD110 | 0.026 s | 1.0 s | 1.0 s | yes | ProvenOptimal |
| MD245 | 0.009 s | 11.7 s | 25.4 s | yes | ProvenOptimal |
| MD110F | 0.003 s | 1.6 s | 1.6 s | yes | ProvenOptimal |
| MD245F | 0.006 s | 2.6 s | 2.6 s | yes | ProvenOptimal |
| S2 | 0.006 s | 98.1 s | 120.0 s | no | ProvenWithin **120.03 %** (bound landed 0.16 s after its 90 s start; +1 ms) |
| S4 | 0.008 s | 120.0 s | 120.0 s | no | **Unavailable** (+0.92 s; soft-leg union beyond the 25 % useful floor) |

Without runes + subs every request except the two frontier shapes is solved to proven optimality in 1–25 s (MD245F
2.6 s vs 48.5 s with them; MM245 4.0 s vs 70.3 s; optimum core 6 003 vs 10 993): in these fixtures **runes +
sublimations are the whole difficulty**. The two exceptions are the frontier shapes: without sublimations their
hard leg yields no build (`hardMet` / `mmHardMet` false: AP 16 / MP 8 / CC 100 / HP 12000 are apparently out of
reach), so the search is the soft fallback, spends the full budget, and ends on a "within 120 %" badge (S2) or none (S4).

### 8.2 GUI warm-up and CP-SAT timing noise

- `WakfuBuildSolver.warmUp()` (first engine call of a fresh JVM, natives cached in
  `~/Library/Caches/WakfuAutobuilder/ortools-native`): ten fresh JVMs (4-core, 10-core and 2-core profiles alike) read
  170 / 171 / 172 / 180 / 188 / 198 / 213 / 213 / 216 / 235 ms — **median 193 ms**, v38 183 ms: unchanged within noise.
  The cold-natives case (first launch after an OR-Tools bump, macOS code-sign validation of the freshly extracted
  dylibs) was not re-measured.
- **CP-SAT proof times on 3 workers are heavy-tailed**, so one run per fixture says little. Same hard-leg models, 4-core,
  runes + subs, 120 s; the §8.1 run plus three in-JVM repetitions (`WAKFU_E0_REPS=3`) plus the 60 s run of §8.3:
  MM110 proven at 35.0 / 26.3 / 45.5 / 17.2 s (v38: 20.6 s); S2 at 46.0 / 58.8 / 55.6 s, 49.5 s in the 60 s run, and
  **un-proven at 120 s** in the fifth run (v38: 82.9 s); MM245 proven in **1 of 4** runs (70.3 s), un-proven in the
  other three (v38: un-proven in its one run). The §8.1 rows "MM245 70.3 s, S2 46.0 s" are therefore the lucky end of a
  distribution, not a speed-up. What did change is what an un-proven most-masteries search is worth: the search-tail
  bound gives a badge at +0 ms — MM245 ProvenWithin **11.55 / 10.75 / 11.81 %** (incumbent core 10 522 / 10 598 /
  10 497 = 95.5–96.4 % of the optimum 10 993; bound 90.0 s → 108.4–108.9 s, i.e. 18.4–18.8 s, landed 12.3–12.7 s
  before the end), S2 ProvenWithin **24.44 %** (incumbent 61.33 T vs 61.76 T; bound 90.05 s → 119.33 s = **29.3 s**,
  landed only **2.2 s** before the end) — where v38 printed Unavailable (MM245, +1.0 s).

### 8.3 Badge path of an un-proven most-masteries search (2-core profile)

In the §8.1 run all three 4-core MM fixtures ended CP-SAT-proven, so the search-tail bound did not run there (§8.2 has
the un-proven 4-core runs). To exercise it deliberately at the production budget the same two requests were run with
`-XX:ActiveProcessorCount=2` (CP-SAT: 1 worker, which does not prove in 120 s; bound: 1 DP thread beside it):

| fixture (2-core, 120 s) | search end | incumbent vs optimum | bound start → landed (compute) | landed relative to search end | badge call after the end |
|---|---|---|---|---|---|
| MM245 (GUI default) | 121.4 s | core 5 705 vs 10 993 (52 %) | 90.1 s → 102.4 s (**12.3 s**) | **−19.0 s** | 0 ms — ProvenWithin **105.7 %** |
| S2 (frontier) | 121.2 s | 22.9 T vs 61.76 T (37 %) | 90.0 s → 110.3 s (**20.3 s**) | **−10.9 s** | 0 ms — ProvenWithin **233.1 %** |

The E10-for-MM wiring does what plan §8.19 designed: on a 2-core machine the badge is on screen the instant the search
ends. What the badge says there is the problem: the 1-worker incumbent is half the optimum and the bound reports it.
The bounds themselves are the tight ones of plan §8.18 / §8.20: 117.0 T for the GUI-default request vs its proven
optimum 109.6 T (+6.8 %), 76.3 T for S2's targets-met read vs 61.76 T (+23.6 %); the "105.7 % / 233.1 %" is the
incumbent's gap.

Same question on the 4-core profile with a shorter budget (60 s, so CP-SAT of the MM requests is still short of its
proof; the user-set duration field allows it) — and the max-damage counterpart, where the badge needs the E10 ledger:

| fixture (4-core, 60 s) | search end | incumbent | warm-up start → landed | landed vs search end | badge call after the end |
|---|---|---|---|---|---|
| MM245 (GUI default) | 61.5 s | core 10 107 (92 % of 10 993) | 30.1 s → 47.6 s (**17.4 s** on one DP thread) | **−14.0 s** | 0 ms — ProvenWithin **16.13 %** |
| S2 (frontier) | 49.5 s | — | started 30.0 s, cancelled | — | CP-SAT proved OPTIMAL at 49.5 s (no badge needed) |
| MD245 (GUI default) | 61.3 s | 18 133 125 | ledger 6.9 s → 76.5 s (**69.6 s**) | **+15.2 s** | **+16.1 s** — ProvenWithin 14.77 % |
| MD245F (free) | 56.7 s | 20 811 420 (the optimum) | in-search construct at 56.6 s | — | ProvenOptimal |

So the max-damage badge is "instant" only because the 120 s budget is longer than the level-245 ledger (70–105 s from the
first incumbent on 4 cores: landed at 117.0 s in the 120 s run, 76.5 s in the 60 s run): with a 60 s duration the badge
arrives 16 s after the search. The MM bound (12–20 s on one thread) landed before the search end in every measured case
(60 s on 4 cores: −14.0 s; 120 s on 2 cores: −19.0 / −10.9 s).

### 8.4 Silent refinement to ProvenOptimal (sacrieur 230, AP 16 / MP 8)

**In production this shape no longer reaches the refinement.** `SAC230` — the request of
`MaxDamageSoftCertificateTest` `sacrieur230-apmp`, searched through `run` (4-core, 120 s) — ends with the HARD leg
meeting AP 16 / MP 8 (`hardMet`), so its post-search chain is the hard-leg AP-cell ledger, like every required-row
request of §8.1: first build 0.17 s, last improvement 98.3 s, search end 120.9 s (incumbent 23 361 780), ProvenWithin
**13.40 %** at +0.52 s (ledger landed at 79.4 s), E8 gated, `refineMaxDamageOptimality` null in 3 ms. The soft-leg proof
and its minutes-long silent refinement run in production only for requests whose hard leg yields no build, i.e.
unreachable targets (S2 / S4 without runes + sublimations take that path, §8.1; with them the frontier requests are
reachable on data 1.93). v36's "sacrieur230: +1.881 % in 62.7 s, ProvenOptimal in 19.6 min" was measured on 1.92 data
and through the test's routing.

**The machinery itself, routed** (`SAC230R`: the test's routing — the no-condition oracle's proven optimum, here
23 585 040 000 000 after 10–12 s, stamped on an EMPTY build so `proveMaxDamageOptimality` takes the soft-leg branch;
clock zero = the search end; `-Xmx3g`, refinement capped at 25 min; the oracle's CP-SAT workers stay 8, the proof's and
the refinement's follow the profile: 4 and 8):

| step (after the search end) | 4-core | 10-core |
|---|---|---|
| soft-leg proof = **first badge** | **+240.0 s** — ProvenWithin **1.18 %** | **+212.3 s** — ProvenWithin **1.18 %** |
| stage starts of that proof | `relaxedProbe` 0 s, `noConditionDp+region` 4.9 s, `coarse` 10.9 s, `primaryRefinement` / `secondarySupportPass` / `lambdaAutoCalibration` 83.3 s, `capFreeResplit` / `oracleJoin` 240.0 s | same stages at 0.02 / 4.5 / 9.8 / 74.0 / 212.3 s |
| E8 construct | 5 ms, null (gate: required rows) | 4 ms, null |
| refinement: no-condition oracle anchor | 105.9 s (4 workers) | 26.9 s (8 workers) |
| refinement: per-carrier closure | running at the cap (+1 740 s) | running at the cap (+1 712 s) |
| **ProvenOptimal** | **not reached by the 25-min cap** | **not reached by the 25-min cap** |

The first pass ends on the oracle join (`capFreeResplit` / `oracleJoin` is the last stage on both profiles; 240.0 s =
the oracle's whole budget on 4 cores), so on a 4-core laptop the user watches the "verifying" spinner for 4 min, then a
"within 1.18 %" badge with the refining cue for at least 25 more minutes. On 10 cores the same chain also overran the
cap: the per-carrier closure had been running for 24.5 min when the cap cancelled it (`carrierClosureStrict` is
logged 0.4 s after the cap, i.e. on the unwind). For comparison, the v36 gate (`S4_CONDITION_ENCODING_PROBLEM.md`, 1.92
data, 10 cores) read +1.881 % at 62.7 s and ProvenOptimal after 19.6 min of refinement; 1.93 data and the v37–v50
certifier waves differ from that run, and the v36 code was not re-run on 1.93, so the slower first badge (212 s vs
62.7 s) and the missing ProvenOptimal inside 25 min are measured here but not attributable.

### 8.5 v38 → v50: what moved for the user

| fixture | v38 | v50 | delta that matters |
|---|---|---|---|
| **4-core** MM110 | proven at 20.6 s | 35.0 s (reps 26.3 / 45.5 / 17.2) | none: same model, CP-SAT noise |
| MM245 | no proof, **no badge** (Unavailable) | proven in 1 of 4 runs (70.3 s); the other three **ProvenWithin 10.7–11.8 % at +0 ms** | coverage hole closed |
| MD110 | Unavailable | ProvenWithin **9.19 %** (+0.15 s) | badge |
| MD245 | Unavailable | ProvenWithin **13.57 %** (+0.90 s) | badge |
| MD110F | within 1.10 % (+5 ms), E8 skipped | within 0.89 % (+2 ms); E8 **ran 60.4 s and failed** | −0.2 pt (incumbent noise); +60 s of futile "still proving" |
| MD245F | within 1.64 % at 120.7 s | **ProvenOptimal at 48.5 s** | −72 s and closed |
| S2 | proven at 82.9 s | 46.0 s (reps 58.8 / 55.6; 1 of 5 un-proven → 24.44 %) | none: CP-SAT noise; a badge exists now |
| S4 | Unavailable | ProvenWithin **16.29 %** (+0.83 s) | badge |
| **10-core** MM245 | proven at 17.7 s | 11.9 s | none (noise class) |
| MD110 | Unavailable | ProvenWithin **13.91 %** (+0.21 s) | badge |
| MD245 | Unavailable | ProvenWithin **9.53 %** (+1.14 s) | badge |
| MD245F | ProvenOptimal at 92.1 s (certificate early stop) | 76.1 s (in-search construct) | −16 s |
| warm-up | 183 ms | 193 ms (median of ten JVMs, 170–235) | none |

First builds (≤ 0.17 s) and last-improvement times are unchanged within the ±20 % noise. The v38 "Unavailable" column
is gone: at the 120 s budget with runes + subs, every request that ends un-proven now carries a badge ≤ 1.2 s after the
search end (most-masteries: +0 ms, also on 2 cores). What did NOT change: every max-damage request with a required row,
and the free level-110 one, still spends the whole 120 s budget, and none of them reaches ProvenOptimal.

### 8.6 What still costs users time (ranked by user impact; facts from the runs above)

1. **No tight guarantee on a required-row max-damage request** (the GUI-default max-damage rows, S4, sacrieur 230): the
   badge reads 9.2–16.3 % on 4 cores (9.5–13.9 % on 10 cores; SAC230 13.40 %) and nothing runs behind it that could
   close it — E8 is gated off by required rows (`gate=false`, 1–3 ms), the refinement is soft-leg only (null in ≤ 3
   ms), CP-SAT does not prove in 120 s on 3 or 9 workers. The bound is the FREE request's optimum, so the number is
   (free optimum ÷ incumbent − 1): the cost of the targets and the search gap in one figure, and it swings ±4 pts
   between two runs of the same request (9.19 vs 13.91 % at 110, 13.57 vs 9.53 % at 245). A target-aware bound (AP / MP
   / range / crit / HP axes — the open item of `CERTIFICATE_PROD_PLAN.md` P5.4) is what would make it informative.
2. **The full 120 s is spent where nothing proves** — every required-row max-damage fixture and the free level-110 one
   end at 119.8–121.1 s with the last improvement at 34–98 s (MD245 on 10 cores: 115 s); only the free level-245
   flagship stops early (48.5 s / 76.1 s, certificate + in-search construct). Runes + sublimations are the whole
   difficulty in these fixtures: without them the same requests are proven in 1–25 s (§8.1).
3. **Low-core most-masteries.** With 1 CP-SAT worker (2 cores) the 120 s answer is half the optimum (MM245 core 5 705 vs
   10 993; S2 22.9 T vs 61.76 T) — the badge reports it on time (bound landed 11–19 s before the end) as "within 105.7 % /
   233.1 %", but nothing improves the build. On 4 cores CP-SAT proves MM245 in only 1 of 4 runs; the other runs end at
   95.5–96.4 % of the optimum with a 10.7–11.8 % badge; S2 is un-proven in 1 of 5 runs (24.44 %, bound landed only 2.2 s
   before the end: 29.3 s of one-thread compute against a 30 s lead).
4. **The soft-leg path (unreachable targets): the first badge itself, then a refinement that does not finish.** Routed
   sacrieur 230: the first verdict arrives 240.0 s (4 cores) / 212.3 s (10 cores) after the search end — the first pass
   ends on its oracle join (v36 notes: 62.7 s on 1.92 data) — and the refinement then runs ≥ 25 min on both profiles
   without reaching ProvenOptimal (v36 notes: 19.6 min on 10 cores). Without runes + subs the frontier requests run the
   whole budget on the soft fallback and end on "within 120 %" (S2) or no badge (S4). Production reaches this path only
   for unreachable targets.
5. **Free max-damage at level 110**: "within 0.89 %" never closes (bound 1 612 935 vs incumbent 1 598 700), and the E8
   construct burns its whole 60 s cap (the "still proving" cue with it) before giving up.
6. **Badge latency with a shorter duration at level 245**: the max-damage ledger needs 70–105 s from the first
   incumbent on 4 cores, so with a 60 s duration the badge arrives 16.1 s after the search (120 s: +0.9 s).

### 8.7 Not measured / caveats

- 4-core / 2-core numbers are a simulation (`-XX:ActiveProcessorCount` on the 10-core M5; the OS still schedules on
  P-cores). One run per fixture, except MM110 / MM245 / S2 (§8.2); "last improvement" ±20 %; the same request's
  incumbent varies ±4 %, which moves a required-row badge by ±4 pts.
- The certificate disk cache is OFF in the harness (the apps enable it: a repeated request reads its badge from disk);
  every "badge after the end" figure is a first request. Natives were cached (cold natives not measured).
- Level 200 (named in E0's spec, absent from the v38 table) and the badge-tightness matrix were not measured; the 10-core
  set is the four §1.1 fixtures; the 2-core and 60 s runs cover MM245 / S2 (+ MD245 / MD245F at 60 s) only.
- The harness reproduces `BuildSearchModel`'s calls, not the Compose app (no rendering, no `scenarioBreakdown`).
- `SAC230R` is a routed scenario (the test's empty-build trick), not a production flow: it measures the refinement
  machinery, not a request a user can currently reach with sublimations on. Neither profile reached ProvenOptimal inside
  the 25-min cap, so the time to ProvenOptimal of that shape on v50 / data 1.93 is only bounded from below (> 25 min of
  refinement); the oracle that stands for the search always uses 8 workers.

Commands (every run through the shared lock: `gradle-locked.sh --heavy :autobuilder:test --tests '*PerfBaselineE0Test*'
--rerun`, `LC_ALL=C.UTF-8`, `WAKFU_E0=1 WAKFU_E0_LOG=<file> WAKFU_TEST_MAX_HEAP=3g`):

```shell
# 4-core, the §1 table (add WAKFU_E0_RUNES_SUBS=0 for the OFF variant)
WAKFU_E0_FIXTURES=MM110,MM245,MD110,MD245,MD110F,MD245F,S2,S4 WAKFU_E0_REFINE=1 WAKFU_TEST_JVM_ARGS="-XX:ActiveProcessorCount=4"
# 10-core (no JVM arg) / 2-core (ActiveProcessorCount=2): WAKFU_E0_FIXTURES=MM245,MD110,MD245,MD245F / MM245,S2
# 4-core short budget: WAKFU_E0_FIXTURES=MM245,S2,MD245,MD245F WAKFU_E0_SECONDS=60
# CP-SAT noise: WAKFU_E0_FIXTURES=MM110,MM245,S2 WAKFU_E0_REPS=3
# refinement: WAKFU_E0_FIXTURES=SAC230 (production) / SAC230R (routed) WAKFU_E0_REFINE=1 WAKFU_E0_REFINE_CAP_S=1500
```
