#!/usr/bin/env bash
#
# Regenerate every embedded Wakfu game-data artifact after the game client updates.
#
#   ./scripts/update-game-data.sh [path-to-wakfu-install]
#       (default install: /Applications/Ankama/Wakfu)
#
# What it does, in order:
#   1. Equipments (Ankama CDN) — also AUTO-DETECTS the latest data version.
#   2. Bumps the single source of truth: common-lib WakfuData.VERSION.
#   3. Spells (Ankama encyclopedia).
#   4. bdata artifacts (local game binaries): cast-limits, passives, sublimations (effects from bdata +
#      metadata from the CDN items.json), sublimation-stacking, and monsters (local Monster table — no more
#      MethodWakfu/Fandom scrape, no more WakForge sublimation pipeline).
#   5. Item / spell / monster icons.
#
# Resource files have FIXED names (equipments.json, spells.json, …) — a version bump no longer renames
# files or leaves stale ones behind. This is MAINTAINER-LOCAL: the bdata step decodes the local game
# binaries and cannot run in CI. See CONTRIBUTING.md "Updating the game data".
#
set -euo pipefail
cd "$(dirname "$0")/.."

WAKFU_INSTALL="${1:-/Applications/Ankama/Wakfu}"
VERSION_FILE="common-lib/src/main/kotlin/me/chosante/common/WakfuData.kt"

echo "==> [1/5] Equipments (Ankama CDN — detects the latest data version)…"
equip_out="$(./gradlew -q --console=plain :equipments-extractor:run 2>&1)" || { echo "$equip_out"; exit 1; }
echo "$equip_out"
new_version="$(printf '%s\n' "$equip_out" | sed -n 's/.*Latest Wakfu data version (Ankama CDN): \([0-9.]\{1,\}\).*/\1/p' | head -1)"
[ -n "$new_version" ] || { echo "ERROR: could not parse the version from equipments-extractor output."; exit 1; }
echo "    Detected latest version: $new_version"

echo "==> [2/5] Bumping WakfuData.VERSION -> $new_version (the single source of truth)…"
perl -i -pe "s/(const val VERSION: String = \")[0-9.]+(\")/\${1}${new_version}\${2}/" "$VERSION_FILE"

echo "==> [3/5] Spells (encyclopedia)…"
./gradlew --console=plain :spells-extractor:run

echo "==> [4/5] bdata artifacts + sublimations + monsters (local game binaries at: $WAKFU_INSTALL)…"
# bdata reads the freshly-written spells.json (for passive/cast-limit names), so it MUST run after step 3.
# It decodes cast-limits + passives + the Monster table (42) + the State/StaticEffect tables, and fetches the
# CDN items.json + actions.json for sublimation metadata + action semantics. It produces spell-cast-limits.json,
# spell-passives.json, spell-damage.json (per-level damage formula anchored on the encyclopedia spells.json,
# so it MUST run after step 3), monsters.json, sublimations.json, sublimation-stacking.json and runes.json
# (CDN items.json itemTypeId 811 shards). The Monster layout is
# auto-derived from the client bytecode (SchemaGenerator), so version drift needs no hand-RE; only boss-tier
# rank comes from the committed monster-overlay.json. Replaces the MethodWakfu/Fandom scrape AND the WakForge
# sublimation pipeline. BDATA_FORCE_WRITE=1 accepts the (expected) data changes; the diff is printed first.
BDATA_FORCE_WRITE=1 ./gradlew --console=plain :bdata-extractor:run --args="$WAKFU_INSTALL $new_version"

echo "==> [5/5] Icons (item / spell / monster)…"
./gradlew --console=plain :gui-compose:generateAssets

cat <<EOF

✅ Game data regenerated for version $new_version.

Next:
  • git status / git diff   — review the regenerated resources + the WakfuData.VERSION bump
  • ./gradlew test          — verify nothing broke
  • commit when happy
EOF
