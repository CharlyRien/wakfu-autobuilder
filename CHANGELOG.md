# Changelog

## [1.10.0](https://github.com/CharlyRien/wakfu-autobuilder/compare/wakfu-autobuilder-1.9.1...wakfu-autobuilder-1.10.0) (2026-07-12)


### Features

* consider Lucky Charm items (Porte-bonheur) in the pet slot ([#188](https://github.com/CharlyRien/wakfu-autobuilder/issues/188)) ([8ba6b46](https://github.com/CharlyRien/wakfu-autobuilder/commit/8ba6b464e077fdf110269e5aee466372d53d5310))


### Bug Fixes

* Zenith export — whiten runes, tier-correct sublimation levels, epic/relic slots ([741d2f1](https://github.com/CharlyRien/wakfu-autobuilder/commit/741d2f1d971a87df03cc1aadae6c2d0c7ad9dba8))


### Performance Improvements

* encoding sweep verdicts + M3 dual-cut seam (MM campaign 7.1-7.7) ([2c5857f](https://github.com/CharlyRien/wakfu-autobuilder/commit/2c5857fdacb6835ab74380f10fbf12a932add505))
* MM encoding A/B seams + certifier harvest index (campaign skeleton) ([d6cfee5](https://github.com/CharlyRien/wakfu-autobuilder/commit/d6cfee5412984657f33163ab7b588441c4f8e526))

## [1.9.1](https://github.com/CharlyRien/wakfu-autobuilder/compare/wakfu-autobuilder-1.9.0...wakfu-autobuilder-1.9.1) (2026-07-11)


### Bug Fixes

* unreachable-target max-damage searches fall back to the soft model again (greedy emission mislabeling) ([168b4b7](https://github.com/CharlyRien/wakfu-autobuilder/commit/168b4b72bfc502ab11130df616cd8e02e40fd213))


### Performance Improvements

* most-masteries meets your targets AND proves twice as fast (hard-constraints leg) ([c41e025](https://github.com/CharlyRien/wakfu-autobuilder/commit/c41e025d259ddab8a12085d3fa78356e275c296f))

## [1.9.0](https://github.com/CharlyRien/wakfu-autobuilder/compare/wakfu-autobuilder-1.8.0...wakfu-autobuilder-1.9.0) (2026-07-11)


### Features

* 20 more sublimations the solver can pick on its own (27 → 47) ([36cfd55](https://github.com/CharlyRien/wakfu-autobuilder/commit/36cfd557c598d0977955371b057b4a102572674c))
* fill every rune socket in most-masteries, proving runes-on builds in ~1s ([540673f](https://github.com/CharlyRien/wakfu-autobuilder/commit/540673fb6cb62bffdefd0866c93f1bd742da7ba7))
* GUI quality-of-life — smarter pickers, sublimation filters, empty-slot explanations, live proof progress ([50ac8a4](https://github.com/CharlyRien/wakfu-autobuilder/commit/50ac8a42bdc9f52bee7a7481b256ded3f2d098ea))
* max-damage shows its first build instantly (greedy warm start) ([6f14cc8](https://github.com/CharlyRien/wakfu-autobuilder/commit/6f14cc860f76ebdfbea1a1b085a18971f1a21d71))
* most-masteries shows its first build instantly (greedy warm start) ([677cee0](https://github.com/CharlyRien/wakfu-autobuilder/commit/677cee0a647961ba56fd6c450aef2a3d3b43a6cf))
* sublimations now stack — multiple copies of a cumulable sublimation, up to the true in-game caps (10 normal + 1 epic + 1 relic) ([38fe9c0](https://github.com/CharlyRien/wakfu-autobuilder/commit/38fe9c0c4f2b00b23c1fa4afc14254430200672a))
* surface the "all gold" rune assumption next to the Zenith actions ([09fd22d](https://github.com/CharlyRien/wakfu-autobuilder/commit/09fd22d174470fd5b8307084fdf19e48e71428db))
* validate the search request up front and show all errors in a pop-up ([7d4c7b8](https://github.com/CharlyRien/wakfu-autobuilder/commit/7d4c7b879a4cf29ce5f26e7084ab3852b9d32a63))


### Bug Fixes

* a blank search duration now means 10 minutes, not 20 seconds ([b720dfe](https://github.com/CharlyRien/wakfu-autobuilder/commit/b720dfe1ac6f6c3f05376827a41340ffc7a992eb))
* saved builds now capture the full search request ([6f7ea73](https://github.com/CharlyRien/wakfu-autobuilder/commit/6f7ea73a87b0f84089a0e2e2e0004226de957b79))
* throttle solver progress emissions and lock precision reachable domains ([6ecd125](https://github.com/CharlyRien/wakfu-autobuilder/commit/6ecd1258db88c03f44849670743c1cb4a9e51487))
* Zenith export could put both rings on the same side ([179b02b](https://github.com/CharlyRien/wakfu-autobuilder/commit/179b02b9e8b86dadebee5a4ecfb0c9c47e711d16))


### Performance Improvements

* skip provably-dominated items per slot in the solver search ([1aa2fca](https://github.com/CharlyRien/wakfu-autobuilder/commit/1aa2fcae054ece469bb1e19fff018fe90052adc7))
* the max-damage 'proven optimal' badge now lands with the result ([6ee555d](https://github.com/CharlyRien/wakfu-autobuilder/commit/6ee555dd88def33428e75afa27c324372529bfa8))
* the search stops the moment its result is proven optimal ([c7928db](https://github.com/CharlyRien/wakfu-autobuilder/commit/c7928dba263fb18dbcd19dfabae3a211cec1f312))

## [1.8.0](https://github.com/CharlyRien/wakfu-autobuilder/compare/wakfu-autobuilder-1.7.0...wakfu-autobuilder-1.8.0) (2026-06-24)


### Features

* most-masteries maximizes Damage Inflicted, not just raw mastery ([b6a9a02](https://github.com/CharlyRien/wakfu-autobuilder/commit/b6a9a020ebc95601c21cf40cdedb94d3a22305ac))
* per-build-accurate spell damage with back/berserk scenario breakdowns ([622f29b](https://github.com/CharlyRien/wakfu-autobuilder/commit/622f29bc18d2274f32985dbe15546ebd05635275))
* per-spell damage view, up-to-4-build comparison, and translated spell/passive/sublimation text ([968916d](https://github.com/CharlyRien/wakfu-autobuilder/commit/968916dd5efc2531562484fc75b82cf1ad18cb4f))


### Bug Fixes

* max-damage boss search streams instantly and proves the optimum with runes & sublimations ([a05fea0](https://github.com/CharlyRien/wakfu-autobuilder/commit/a05fea0f1db51fc90a0ac7bd83dcd7565c27a071))

## [1.7.0](https://github.com/CharlyRien/wakfu-autobuilder/compare/wakfu-autobuilder-1.6.0...wakfu-autobuilder-1.7.0) (2026-06-23)


### Features

* **gui:** item-type icons on empty equipment slots ([a535997](https://github.com/CharlyRien/wakfu-autobuilder/commit/a535997f64364bbba7ab05e11b18b5ca2a4aedb1))
* **max-damage:** provably-optimal builds for single, boss & multi-element searches ([8923894](https://github.com/CharlyRien/wakfu-autobuilder/commit/89238940fa66176eca358b372c1ef8e3e35e4754))
* **runes:** a normal sublimation keeps the item's full rune set, and the socket pattern is always drawn ([54761dc](https://github.com/CharlyRien/wakfu-autobuilder/commit/54761dc694e5efa6d065a1bc2937a5d30a9b64d0))

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
