# Changelog

## [1.6.0](https://github.com/CharlyRien/wakfu-autobuilder/compare/wakfu-autobuilder-1.5.0...wakfu-autobuilder-1.6.0) (2026-06-21)


### Features

* **assets:** extract GUI icons from the local client (gui.jar), not wakassets ([d2b00f6](https://github.com/CharlyRien/wakfu-autobuilder/commit/d2b00f68abfc4efb24714f39891a8f7ce9b635d5))
* **assets:** source itemTypes, runes, and HUD stat icons from the client gui.jar ([54f19ba](https://github.com/CharlyRien/wakfu-autobuilder/commit/54f19babde37cf1d92b8f723f1a882cdde54288d))
* **boss/gui:** boss icons in the picker rows + boss card (Lot 0 GUI) ([b4aaeb1](https://github.com/CharlyRien/wakfu-autobuilder/commit/b4aaeb1d0549e80519fa3fb291e74db63dc5b9a4))
* **boss/gui:** boss picker — target a boss, auto-fill its resistances (Lot 0 GUI) ([148fb9a](https://github.com/CharlyRien/wakfu-autobuilder/commit/148fb9a41c7cc1dfcecb7e5a35520096c29f32e0))
* **boss/gui:** boss-only picker; drop the redundant element/resistance inputs ([8607bf3](https://github.com/CharlyRien/wakfu-autobuilder/commit/8607bf3a2bb0280b6ffa427875528e46a897de97))
* **damage:** bi-element CP-SAT objective (Lot 2 M1) ([5c220a8](https://github.com/CharlyRien/wakfu-autobuilder/commit/5c220a89a0f46ee8804b7ca06694e832c846dc4d))
* **damage:** bi-element search — enumeration, honest scoring & display (Lot 2 M2 + 2e-f) ([9afd3b5](https://github.com/CharlyRien/wakfu-autobuilder/commit/9afd3b53fa2ce312ccaa6bff916dc45b5af2d42d))
* **damage:** coherence floor — survivability soft-floor + role presets (Lot 5) ([405fdea](https://github.com/CharlyRien/wakfu-autobuilder/commit/405fdea8af84cfd0fd74ad333b92ed58d54916e2))
* **damage:** single-target per-turn cast cap (fold maxCastPerTarget) ([ca71fb6](https://github.com/CharlyRien/wakfu-autobuilder/commit/ca71fb679e2ddd75a369ab92281c9c502c084b83))
* **data:** source monsters & sublimations from bdata + CDN (1.92.1.58) ([34e60e6](https://github.com/CharlyRien/wakfu-autobuilder/commit/34e60e6b7d2838e567fc0f8dd709b9340a24f4b7))
* **data:** source runes.json from the CDN items.json (not hand-maintained) ([7403cb4](https://github.com/CharlyRien/wakfu-autobuilder/commit/7403cb42d189ba4c6af2a9d9ca43f6f59b9c759c))
* **gui:** fit all 14 paperdoll slots on screen with uniform cards ([a86951c](https://github.com/CharlyRien/wakfu-autobuilder/commit/a86951c424e24c1ab8d92d750936454ae9bc868a))
* **gui:** persist the chosen UI language across launches ([ae4c3e2](https://github.com/CharlyRien/wakfu-autobuilder/commit/ae4c3e24165792768f395d5185a7592560537844))
* **passives:** player passive loadout — solver fold + GUI display with icons (Lot 4) ([42f3b1d](https://github.com/CharlyRien/wakfu-autobuilder/commit/42f3b1d0b4c7a28db53da1483a9cbee440865dcc))
* **rotation:** bound spell rotations by real per-turn cast limits + extract WP cost ([9397d8e](https://github.com/CharlyRien/wakfu-autobuilder/commit/9397d8e0d68278a419c0cc4ad27058bb45039333))
* **spells:** level-aware spell damage from bdata (per-level formula) ([5a74aad](https://github.com/CharlyRien/wakfu-autobuilder/commit/5a74aad582ce0c200dce12146b17ef4fbe2f5918))
* **subli:** export sublimations to Zenith (socketed like runes) ([2ee29b5](https://github.com/CharlyRien/wakfu-autobuilder/commit/2ee29b54d1773fecd16fe28d1614098b9c3244fb))
* **subli:** model sublimations in the CP-SAT solver + CLI flags ([a80c811](https://github.com/CharlyRien/wakfu-autobuilder/commit/a80c811819dff612c6d5e3038b2d71d97ffbf5be))
* **subli:** per-item forced runes + GUI polish (searchable sub picker, panel reorg) ([c047809](https://github.com/CharlyRien/wakfu-autobuilder/commit/c047809f1f295386710a943c3213c8f28657b32b))
* **subli:** per-sublimation max-stack from the bdata State table (67) ([a9f395a](https://github.com/CharlyRien/wakfu-autobuilder/commit/a9f395a243d18c35c4a6a5f1c7c3c44588a1ec44))
* **subli:** research + generate the sublimations data resource (Lot 3) ([2e6b1e5](https://github.com/CharlyRien/wakfu-autobuilder/commit/2e6b1e5ec18eda9140bc44873cc55da548480291))
* **subli:** tie sublimations to carrier items, surface them in the GUI, cap the sheet ([977dc8c](https://github.com/CharlyRien/wakfu-autobuilder/commit/977dc8cc02943e6331da5dc253e81adc8f5c24b2))


### Bug Fixes

* **cli:** graceful message when search emits no build ([757d176](https://github.com/CharlyRien/wakfu-autobuilder/commit/757d176ef009acdad7c77615eac9aadc765298a4))
* **damage:** gate max-damage probing + millisecond solver time budget ([5207d3e](https://github.com/CharlyRien/wakfu-autobuilder/commit/5207d3e33819002cd7b5495a83d0d58397857264))
* **gui:** show the % match meter only in precision mode ([6974cba](https://github.com/CharlyRien/wakfu-autobuilder/commit/6974cbaa35d6bbc0f6854e268364a4d2d5d0c00f))

## [1.5.0](https://github.com/CharlyRien/wakfu-autobuilder/compare/wakfu-autobuilder-1.4.0...wakfu-autobuilder-1.5.0) (2026-06-16)


### Features

* **boss:** CLI boss mode — pick a boss, auto-pick the best element ([489c164](https://github.com/CharlyRien/wakfu-autobuilder/commit/489c164724db0ed6d040e6b514714be9da21d81e))
* **gui:** class (breed) artwork — breedId mapping, assets, resolver ([207e102](https://github.com/CharlyRien/wakfu-autobuilder/commit/207e102518cd7a495ff1300590dc34b7cbd60b47))
* **gui:** show app and game-data versions in the footer ([320ccd4](https://github.com/CharlyRien/wakfu-autobuilder/commit/320ccd4e212b88e0088aab1345f7be2cf43b7efb))
* **gui:** TopBar — polish, responsive Direction-B wrap, search loader ([b4810af](https://github.com/CharlyRien/wakfu-autobuilder/commit/b4810af9138c3292017d296749603dcecdcc235b))


### Bug Fixes

* **gui:** build-card "Charger" button no longer truncates ([df1a87e](https://github.com/CharlyRien/wakfu-autobuilder/commit/df1a87eb11738b61736fb0167aba75add4ee3af0))

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
