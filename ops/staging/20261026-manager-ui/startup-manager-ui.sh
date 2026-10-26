#!/usr/bin/env bash
set -euo pipefail
root="${PIXELMC_GAME_ROOT:-/data}"
stage="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
(cd "$stage" && sha256sum --check SHA256SUMS)
if [[ "${1:-}" == '--check-payload' ]]; then bash "$stage/activate-manager-ui.sh" --check-payload; exit 0; fi
gson="$root/libraries/com/google/code/gson/gson/2.10/gson-2.10.jar"
if [[ ! -f "$gson" ]] || ! java -Xms16m -Xmx64m -cp "$stage/classes:$gson" ManagerUiReadiness "$root/config/pixelmcmanager-bridge.json"; then
 echo '[Manager UI staging] Restart the Manager web service first; this game start retains previous versions.'; exit 0
fi
bash "$stage/activate-manager-ui.sh"
if [[ -f "$stage/applied.txt" && -f "$root/pixelmcmanager/deploy-titles-ui-0.4.2-20261026/activate-titles-ui.sh" ]]; then bash "$root/pixelmcmanager/deploy-titles-ui-0.4.2-20261026/activate-titles-ui.sh"; fi
