# Per-push CI test shards

Measured and verified on 2026-10-06, based on `origin/main` at `42651c6d`.

| Matrix shard | Autobuilder classes | Local test time | Estimated CI test time |
| --- | --- | ---: | ---: |
| most-masteries | MostMasteriesCertificateTest | 237 s | 7.9–11.9 min |
| solver | WakfuBuildSolverTest | 137 s | 4.6–6.9 min |
| floors-soft | ZeroTargetRowsTest, MaxDamageSoftCertificateTest | 160 s | 5.3–8.0 min |
| medium | MaxDamageTargetAwareCertificateTest, DofusPourpreLevelScalingTest, PerElementRowFoldTest, SoundnessReviewAdversarialTest, RuneChoicePruningTest | 132 s | 4.4–6.6 min |
| remaining | All other classes, plus every other module's tests | ~74 s | ~2.5–3.7 min |

Estimates use the supplied per-class timings and 2–3× CI slowdown; add runner setup,
Gradle provisioning and compilation. The remaining estimate includes 40 s of autobuilder,
26 s of GUI and 8 s of bdata tests; other modules add small unmeasured test costs.
Five jobs leave headroom around the indivisible 237 s class: expected critical path is
roughly 10–15 minutes, subject to confirmation by the maintainer's PR CI run.
Each runner uses the existing single test JVM configuration; no parallel forks were added.

The named shards run `:autobuilder:test -PciTestShard=<name>`; `remaining` runs
`test -PciTestShard=remaining`. The latter excludes only the nine explicitly assigned
classes, so future tests and modules automatically enter the catch-all. An absent property
keeps ordinary `test` unchanged; an unknown shard name fails configuration.

The matrix runs on pushes and reusable `workflow_call`, including deploy. `fail-fast: false`
lets every shard finish. The single aggregate job `build` needs the matrix, runs with
`always()`, and succeeds only when the matrix result is `success`; failures, cancellations
and skips cannot pass it. `slow-tests` and `release-note-scripts` are unchanged.

## Coverage proof

The per-shard class listing (kept in the body of the pull request that introduced the shards) came
from actual JUnit discovery via Gradle `--test-dry-run`, not source filename guesses or task-only `--dry-run`.
The unfiltered baseline discovered **122 classes / 1,028 tests**. The shard totals were:

| Shard | Classes | Discovered tests |
| --- | ---: | ---: |
| most-masteries | 1 | 20 |
| solver | 1 | 223 |
| floors-soft | 2 | 48 |
| medium | 5 | 54 |
| remaining | 113 | 683 |

**Union check passed for both classes and test identities: missing 0, extra 0, duplicates 0.**
The catch-all includes 44 autobuilder, 54 GUI, 7 bdata, 5 common-lib, and one class each
from equipments-extractor, spells-extractor and zenith-builder.

Reproduce sequentially through the shared lock on the maintainer's Mac:

1. Run `test --test-dry-run` without a shard property and snapshot all modules'
   `build/test-results/test/TEST-*.xml`.
2. Run each named shard's command above with `--test-dry-run`, snapshotting autobuilder's
   XML after each run. Run the remaining shard last and snapshot every module's XML.
3. Read each XML `testcase`; collect `(module, classname)` and `(module, classname, name)`.
   For each identity set, count occurrences across shard snapshots and assert every count
   is one and the keys equal the baseline set. Restrict named-shard snapshots to autobuilder
   so other modules' XML left by the baseline is not counted again.

All six discovery runs passed. Compilation, `ktlintFormat`, `ktlintCheck`, and YAML structure checks passed;
no test bodies were executed by these discovery runs. The PR CI run is the runtime proof.

## Compilation decision

Keep `gradle/actions/setup-gradle@v6` in every shard and compile independently.
Local measurements used the mandatory shared Gradle lock with `--heavy`, no waiting:

- Empty worktree build directories: `testClasses ktlintFormat` took **50.93 s** wall time
  (includes formatting, so this is an upper bound on local compile preparation).
- Warm `testClasses --profile`: **3.86 s**, all 30 actionable tasks up to date.
- Candidate archive of all modules' compiled classes, resources, jars and root `.gradle`
  state: **258,551,567 bytes (247 MiB)**; **6.43 s** to pack and **2.11 s** to extract locally.
  This measures local artifact cost, not GitHub upload/download or portable Gradle reuse.

A shared compile job must finish before any shard starts. Independent compilation overlaps
across runners, so sharing cannot remove compilation from the longest shard's critical path;
it instead adds artifact transfer and a serial barrier, potentially waiting for GUI compilation
that the longest shard does not need. Independent compilation spends more total runner CPU,
but is simpler and fits the wall-time target. Revisit with actual Linux CI data if compilation
becomes dominant; these Mac numbers do not establish Linux transfer or cache-hit timings.

No test code, budgets, seeds, tags, JVM args or nightly allocation changed. No changeset was
added for this `ci:` commit; the maintainer's PR should use `no-changeset`. No push or PR creation.
