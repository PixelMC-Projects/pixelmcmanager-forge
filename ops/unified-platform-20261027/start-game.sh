#!/usr/bin/env bash
set -euo pipefail
root="${PIXELMC_GAME_ROOT:-/data}"
stage="$root/pixelmcmanager/unified-20261027"
cd "$root"
[[ -f "$stage/migration-complete.json" ]] || { echo '[PixelMC unified] Migration has not been verified.' >&2; exit 1; }
[[ -f "$stage/activation-complete.json" ]] || { echo '[PixelMC unified] Activation has not been completed.' >&2; exit 1; }
sha256sum --check "$stage/active.sha256"
gson="$root/libraries/com/google/code/gson/gson/2.10/gson-2.10.jar"
java -Xms16m -Xmx64m -cp "$stage/classes:$gson" UnifiedReadiness "$root/config/pixelmcmanager-bridge.json"
exec "$@"
