# Most-masteries optimality certificate — design plan

Status: **DESIGN (not started)**. Author context: post-PR-#190 (max-damage certificate campaign closed:
badge lands with the result, search stops on proof). This plan opens the same capability for the
**most-masteries** mode — the last big time-to-proven number in the product.

---

## 0. Why

Max-damage end-to-end is done (66 s proven at lvl-245, early stop, 13 s repeats). Most-masteries at
lvl-245 still takes **113–120 s to a proven optimum** (SOLVER_PERFORMANCE §7 profile, p1/l1,
1w+interleave), and has **no early-stop lever**: no certificate exists for the mode, so CP-SAT must
close its own dual bound — the solver knobs that could speed that are all evidence-settled OFF.
The greedy warm start masks the *first-build* wait (~0.15 s) but not the *proof* wait.

Goal: an independent, sound upper bound on the most-masteries objective, plugged into the SAME
early-stop / warm-up / cache plumbing max-damage uses — so `--duration` becomes a cap here too.

## 1. Step 0 — measurement gates (GO/NO-GO before any code)

The campaign only pays if CP-SAT finds the *optimum build* long before it *proves* it. Measure at 245
(F5 shape, production p1/l1 + domination, 1w+interleave):

- **M1 — time-to-incumbent-equals-optimum vs time-to-OPTIMAL.** If the optimum is typically in hand at
  ~20–40 s and the remaining ~80–100 s is pure dual-closing, the early stop wins big (113 s → ~40 s).
  If CP-SAT only reaches the optimum near the end, a certificate can only award a badge, not time.
- **M2 — the bound's own cost budget.** The bound must compute in ≪ the time it saves (target: ≤ 5 s
  at 245, overlapped with the search like the max-damage warm-up).
- **M3 — expected tightness.** On small pools where CP-SAT proves fast, compare a prototype bound to
  the known optimum: a bound that is > ~2–3 % loose will rarely award the badge (the incumbent must
  REACH it) and the campaign should stop at ProvenWithin-style reporting or be redesigned.

## 2. The objective algebra (pinned — the design foundation)

From `FindMostMasteriesFromInputScoring` + `buildMostMasteriesObjective`:

```
score = (Σ_requested weight_c · achieved_c) × diFactor / penaltyFactor
penaltyFactor = (100 / successPct)^6  ≥ 1,  == 1  ⟺  every required target fully met
diFactor      = (1 + DI/100)-shaped (the damage-blind fix: mastery × (1 + DI/100))
```

**Key property (the whole design leans on it):** `penaltyFactor ≥ 1` ⇒ for every build,
`score ≤ unpenalizedScore`. So a sound upper bound `U ≥ max unpenalized score over all builds`
certifies ANY incumbent that (a) fully meets every required target (penalty == 1 exactly — reuse
`fullyMeetsRequiredTargets` / the hard-leg provenance flag) and (b) has `score ≥ U`.
Incumbents that miss a target are honest-`Unavailable`, exactly like max-damage's target gate.

## 3. Bound design — a ONE-CELL max-damage fast tier

The unpenalized objective is `masteryScore × diFactor` — structurally the max-damage per-hit product
`graw × (100 + DI)` with the crit/AP machinery deleted. The bound is therefore a stripped-down reuse
of the existing, battle-tested certifier machinery:

- **Frontier DP over (mastery, DI):** one Pareto frontier per slot stage (items → weapons combo →
  ring singles+pairs → skills → staged subs), `Raw.m := Σ weight_c · value_c` folded per item ONCE
  (no crit segments, no AP cells, no c-loop — a single pass, not 21 × 6 × ~100).
- **Family budgets (v15) apply verbatim:** pure-mastery and pure-DI unconditional subs are sorted-prefix
  budgets at harvest; the `budgetMax` split enumeration is reused as-is.
- **Runes:** the single-type rune fold is already objective-shape-agnostic — reuse; bail on the same
  forced-rune / secondary-cap>0 shapes the max-damage certifier bails on.
- **Random-element lines (the mode's own hard part):** items granting "mastery of N random elements"
  are element-ASSIGNED by the scorer's exact B&B. Sound v1: credit every random line at its FULL value
  toward the requested elements (an over-count; never an under-count). Tighten in v2 only if M3 shows
  the slack costs badges (per-item option enumeration like the ring pairs, or the B&B's own per-item
  upper bound).
- **Conditional / structured subs:** identical policy — exact per-state gates where the max-damage
  certifier has them, BAIL (withhold the badge) on anything doubtful. Reuse `certifierWorlds`-style
  splitting ONLY if a most-masteries-choosable conversion sub exists (check: likely not — then no
  worlds at all, one pass total).
- **What does NOT exist here:** AP cells, rotation table, crit bands, pre-combat windows, CS/conv
  worlds (probably), the exact tier, tier-1.5, segment skips. Expected bound cost: **~1 s at 245**
  (one fast-pass-shaped sweep) — comfortably inside M2.

### Soundness invariant (unchanged from max-damage, verbatim)

The certificate must NEVER under-count. Every simplification must be an over-count (credit more, gate
less); when in doubt, bail (`Long.MAX_VALUE` → no badge). A separate `MM_CERTIFIER_VERSION` constant
keys its cache entries; bump on ANY change.

## 4. Integration — reuse, not reinvention

- **Cache:** the same `MaxDamageCertificateCache` pattern (key = shape, value = the single bound +
  bail flag); the disk layer is optional v2 (the bound is ~1 s — caching matters less than for
  max-damage's minutes).
- **Warm-up + early stop:** mirror `MaxDamageSearch`'s plumbing in the most-masteries flow
  (`WakfuBestBuildFinderAlgorithm`/solver path): launch the bound off the first streamed result;
  `maybeStopSearchProven` gates = targets fully met (hard-leg flag or scorer re-check) + score ≥ U +
  the under-count self-check analog. NOTE: most-masteries has no per-element phases — the plumbing is
  strictly simpler than max-damage's.
- **Badge UX:** the GUI `ProofState` machinery is mode-agnostic already; the CLI prints the same line.
- **E8-style construction: OUT OF SCOPE v1.** If the incumbent falls short of U there is no construct
  path (no provenance backtrack in v1) — the search just keeps running to its duration, exactly like
  today. (v2 candidate if M1 shows incumbents often stall below a tight U.)

## 5. Validation protocol (locks before any production wiring)

1. **Exact-equality lock on small pools:** bound == pinned CP-SAT optimum on deterministic fixtures
   (the coupling-test pattern; give the pools spare carriers — the carrier-blindness gotcha applies
   here too).
2. **Fuzz lock:** seeded random pools (reuse `fuzzScenario`'s generator with most-masteries params):
   `bound ≥ pinned CP-SAT optimum` on every seed; a healthy fraction must be EQUAL (tightness).
3. **Oracle:** bank the 245 F5-shape bound value; drift = intentional-or-regression gate.
4. **Early-stop lock (slow):** production-path search with a generous budget must stop early AND
   proven; a targets-unmeetable request must NOT stop early and must not badge.
5. **Non-regression:** the full CI suite + the max-damage slow locks untouched.

## 6. Rollout phases

- **P0 (measure):** M1/M2/M3 harness + numbers. GO/NO-GO decision recorded here.
- **P1 (bound):** the one-pass frontier bound + locks 1–3. No production wiring.
- **P2 (wire):** warm-up + early stop + badge + lock 4. `MM_CERTIFIER_VERSION = 1`.
- **P3 (tighten, only if needed):** random-element per-item options; construct path; disk cache.

## 7. Open questions (resolve in P0/P1)

- Exact `diFactor` form in the SOLVER objective vs the SCORER (percent rounding divergences? the
  max-damage proxy-vs-display lesson says: compare in ONE unit system, define the proxy first).
- Does any most-masteries-choosable sub require world-splitting (conversion / Critical-Secret
  analogs)? If yes, enumerate like max-damage; if no, single pass.
- Weight normalization: `TargetStats.weight` scaling must match the solver's integer scaling exactly
  (define the bound in the solver's objective units).
- The lexicographic overshoot tie-breaker (#126) is a SECONDARY objective — the certificate bounds the
  primary only; the badge claim must be worded accordingly (optimal primary score, not unique build).
- Precision mode: explicitly out of scope (different objective shape; revisit only on demand).
