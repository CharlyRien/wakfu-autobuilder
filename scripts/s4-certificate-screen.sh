#!/usr/bin/env bash
# Fast iteration harness for the test-side S4 soft max-damage certificate.
#
#   ./scripts/s4-certificate-screen.sh [coarse|di|cc|hp|adaptive|fine|all] [plain|cert]
#
# `plain` runs only the current base/plain winner and is deliberately UNSOUND: use it to rank
# ideas, never as a badge/certificate. `cert` executes every sound world/arm. The targeted grids
# refine only one axis; `adaptive cert` is the normal sound gate and `fine cert` a calibration lock.
# Set `WAKFU_S4_REQUIRE_CONDITIONAL=1` with `adaptive cert` to run the complete hybrid union:
# the banked CP-SAT no-condition optimum plus conditional-only DP refinements of contenders.
set -euo pipefail
cd "$(dirname "$0")/.."

profile="${1:-coarse}"
mode="${2:-plain}"

case "$profile" in
    coarse|di|cc|hp|adaptive|fine|all) ;;
    *) echo "usage: $0 [coarse|di|cc|hp|adaptive|fine|all] [plain|cert]" >&2; exit 2 ;;
esac
case "$mode" in
    plain|cert) ;;
    *) echo "usage: $0 [coarse|di|cc|hp|adaptive|fine|all] [plain|cert]" >&2; exit 2 ;;
esac

if [[ "$profile" == "adaptive" && "$mode" != "cert" ]]; then
    echo "adaptive is already an all-world sound certificate; use: $0 adaptive cert" >&2
    exit 2
fi

env_args=(
    "WAKFU_S4_PROTO=1"
    "WAKFU_S4_GRID_PROFILE=$profile"
    "WAKFU_S4_CC_LAMBDA=${WAKFU_S4_CC_LAMBDA:-6000}"
    "WAKFU_S4_CC_BAND=${WAKFU_S4_CC_BAND:-5}"
    "WAKFU_S4_COUPLE_NEG_ITEMS=${WAKFU_S4_COUPLE_NEG_ITEMS:-1}"
    "WAKFU_S4_NET_NEG_ITEMS=${WAKFU_S4_NET_NEG_ITEMS:-1}"
    "WAKFU_S4_EXACT_NORMAL_SUBS=${WAKFU_S4_EXACT_NORMAL_SUBS:-1}"
    "WAKFU_S4_FOLD_ITEM_MAX_AP=${WAKFU_S4_FOLD_ITEM_MAX_AP:-1}"
    "WAKFU_S4_FOLD_MAX_MP=${WAKFU_S4_FOLD_MAX_MP:-1}"
    "WAKFU_S4_SPLIT_LIGHT_WEAPON=${WAKFU_S4_SPLIT_LIGHT_WEAPON:-1}"
    "WAKFU_S4_ORACLE=${WAKFU_S4_ORACLE:-17702078146500}"
    "WAKFU_TEST_MAX_HEAP=${WAKFU_TEST_MAX_HEAP:-8g}"
    "WAKFU_S4_TIMINGS=${WAKFU_S4_TIMINGS:-1}"
)

if [[ "$mode" == "plain" ]]; then
    env_args+=("WAKFU_S4_DIAG_BASE_PLAIN=1")
    echo "==> S4 $profile screen: BASE/PLAIN ONLY — DIAGNOSTIC, NOT A SOUND CERTIFICATE"
else
    echo "==> S4 $profile screen: all worlds/arms — sound certificate"
fi

# `cleanTest` invalidates only test outputs. Kotlin/Java compilation remains cached, unlike the
# historical `--rerun-tasks` command which rebuilt every dependency for each environment change.
env "${env_args[@]}" \
    ./gradlew --console=plain :autobuilder:cleanTest :autobuilder:test \
    --tests '*MaxDamageSoftBoundPrototypeTest*frontier*' --no-daemon
