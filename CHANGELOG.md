# Changelog

## [1.4.0](https://github.com/CharlyRien/wakfu-autobuilder/compare/wakfu-autobuilder-1.3.0...wakfu-autobuilder-1.4.0) (2026-06-14)


### Features

* **gui:** import/export builds via the clipboard ([76af733](https://github.com/CharlyRien/wakfu-autobuilder/commit/76af7336a3a0cdc29cc08ca0930d6454cdecc6b3))
* **runes:** surface the rune enchantment level and round-trip runes through save/clipboard ([313d750](https://github.com/CharlyRien/wakfu-autobuilder/commit/313d750894c4cb739d602ef05a3d9ceff0f5a754))


### Bug Fixes

* **enchant:** cap rune/shard level by item level, not character level ([3691874](https://github.com/CharlyRien/wakfu-autobuilder/commit/36918746573f88e1504d8416a5ebf80e3e3b6dbe))
* **engine:** leave one CPU core free during CP-SAT search ([11eb0d0](https://github.com/CharlyRien/wakfu-autobuilder/commit/11eb0d01b65cca31f3b83e8a7f67d0e615292c38))
* **gui:** keep the search progress bar alive during a search ([1a6bbe2](https://github.com/CharlyRien/wakfu-autobuilder/commit/1a6bbe28fd867a246bacd1fdc68374d043e27b55))

## [1.3.0](https://github.com/CharlyRien/wakfu-autobuilder/compare/wakfu-autobuilder-1.2.0...wakfu-autobuilder-1.3.0) (2026-06-13)


### Features

* allow duplication of build ([fdffc37](https://github.com/CharlyRien/wakfu-autobuilder/commit/fdffc37cc2d1351e1997d2e75f570b861bb606af))
* **gui:** organize the build library (cards · sort · filter · group · tags · folders) ([5aaf898](https://github.com/CharlyRien/wakfu-autobuilder/commit/5aaf898008c2a2fe464399cdff69ff3447105256))


### Bug Fixes

* **gui:** keep priority-meter clicks bound to their row across add/remove ([#147](https://github.com/CharlyRien/wakfu-autobuilder/issues/147)) ([c663eb2](https://github.com/CharlyRien/wakfu-autobuilder/commit/c663eb2785f5ffc9f97a26d123dc5cc83762fde1))
* **levels:** reject min &gt; max level instead of returning a nonsense build ([d0f6cd7](https://github.com/CharlyRien/wakfu-autobuilder/commit/d0f6cd739adfeeb2cd8142245331d4f4710fc736))
* **test:** eliminate BuildSearchModelE2ETest race condition on CI ([#144](https://github.com/CharlyRien/wakfu-autobuilder/issues/144)) ([2178b51](https://github.com/CharlyRien/wakfu-autobuilder/commit/2178b51f8e1c85db3ae5ec4e24d97f8095598910))

## [1.2.0](https://github.com/CharlyRien/wakfu-autobuilder/compare/wakfu-autobuilder-1.1.0...wakfu-autobuilder-1.2.0) (2026-06-11)


### Features

* **engine:** overshoot tie-breaker for most-masteries (re [#126](https://github.com/CharlyRien/wakfu-autobuilder/issues/126)) ([#132](https://github.com/CharlyRien/wakfu-autobuilder/issues/132)) ([3801579](https://github.com/CharlyRien/wakfu-autobuilder/commit/3801579834dee5db7a161bb5027430c33dcadd73))
* **gui:** in-app "What's new" dialog fed by the release-please changelog ([a7c5f13](https://github.com/CharlyRien/wakfu-autobuilder/commit/a7c5f133bbfb773c03cdeb804b9768a9e1c9aaf0))
* runes (enchantments) — Phase 1 (engine socket-fill + tooltip) ([#133](https://github.com/CharlyRien/wakfu-autobuilder/issues/133)) ([db0a563](https://github.com/CharlyRien/wakfu-autobuilder/commit/db0a563c42d27a874ede8b81e522be6cc5f6c237))


### Bug Fixes

* **startup:** eliminate the macOS startup freeze via an extract-once OR-Tools native cache ([#136](https://github.com/CharlyRien/wakfu-autobuilder/issues/136)) ([ae87c3f](https://github.com/CharlyRien/wakfu-autobuilder/commit/ae87c3f099df60b38d7f2f9c8caafd3814a264eb))
