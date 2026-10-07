# CPU allowance

`WakfuBestBuildParams.computeBudget` captures an immutable logical-core allowance for each operation. The CLI supplies it with `--threads N` (1 through the machine’s core count, all cores by default). Production hardware reads live only in `ComputeBudget.availableCores`; deterministic test tunings retain their explicit workers.

Let H be the machine’s logical cores, N the requested allowance, M the maximum JVM heap. All rows preserve their existing margins and memory limits.

| Production site | Before | After |
| --- | --- | --- |
| `LongLongMaxMap.defaultWorkers` (standalone default) | H − 1 | maximum budget’s chunkWorkers = H − 1 |
| `MostMasteriesCertificate.bound` default stage provider | LongLongMaxMap.defaultWorkers | request budget’s chunkWorkers = N − 1 |
| `MostMasteriesBoundCache.stageWorkers` | 1 during search, otherwise H − 1 | 1 during search, otherwise N − 1 |
| `MaxDamageSoftCertificate.Geometry.apply` | H − 1, at most 8 chunks; <2 means serial | N − 1, same 8-chunk limit and serial path |
| `MaxDamageSearch.proveSoftLegQuality` oracle | clamp(H − 2, 4, 8) | min(N, clamp(N − 2, 4, 8)) |
| `MaxDamageSearch.refineSoftLegQuality` union oracle | clamp(H − 2, 4, 8) | same budget oracle formula |
| `MaxDamageSearch.refineSoftLegQuality` refinement workers | clamp(H − 2, 4, 8) | same budget oracle formula |
| `MaxDamageSearch.runProbeBatch` | max(1, H − 1) shared by probes | max(1, N − 1), same probePlan allocation |
| `MaxDamageSearch.streamProbeBatch` | max(1, H − 1) shared by probes | max(1, N − 1), same probePlan allocation |
| `WakfuBuildSolver.certifierDefaultThreads` | heap formula(M,H): reserve 2 GiB, 1.25 GiB/worker, cap min(6,max(1,H−1)) | identical formula(M,N) |
| `WakfuBuildSolver.certifierTier15Threads` | reserve 1.5 GiB, 0.4 GiB/worker, cap min(6,max(1,H−1)) | identical formula(M,N) |
| `WakfuBuildSolver.certifierFastWorldThreads` | reserve 1.5 GiB, 0.6 GiB/worker, cap min(5,max(1,H−1)) | identical formula(M,N) |
| `MaxDamageSearch` background certificate provider and proof default | tier-specific default counts | same formulas using captured request budget |
| `WakfuBuildSolver.maxDamageCertificate` tier provider | max(caller threads, tier-specific default) | same, capped at N; supplied providers also capped |
| `WakfuBuildSolver.dpConstructProvenOptimum` certificate / fallback certificate | default exact threads | default exact threads for request budget |
| `WakfuBuildSolver` production CP-SAT solve | explicit probe workers or max(1,H−1) | same derivation from N, capped at N |
| `WakfuBuildSolver.warmUp` | 2 | min(2,N) |
| `MaxDamageSoftCertificate` lazy oracle warm-up | warmUp’s default 2 | warmUp(request budget), min(2,N) |

DP bounds, objective arithmetic, cache fingerprints and certificate versions are unchanged. Both proof caches normalize away the compute allowance: completed bounds are reusable across budgets. No `CERTIFIER_VERSION` bump (work count only), and no `ENGINE_RESULTS_VERSION` bump (parallel CP-SAT results already vary across machines).

With Maximum, formulas exactly match the previous behavior on machines with at least four logical cores. The old soft-oracle minimum of four workers oversubscribed one-, two- and three-core hosts; the new cap removes that exception. A one-core warm-up similarly uses one worker. This is the necessary edge-case exception to simultaneously preserve legacy formulas and enforce N workers.

The allowance governs engine workers at each site, rather than the JVM’s incidental UI, GC and coroutine threads. A zero chunk-worker value keeps the existing sequential DP execution.
