#!/usr/bin/env bash
set -euo pipefail
umask 077
root="${PIXELMC_GAME_ROOT:-/data}"
stage="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
(cd "$stage" && sha256sum --check SHA256SUMS)
if [[ "${1:-}" == '--check-payload' ]]; then echo '[Manager UI staging] Future coordinator payload verified; no request or runtime probe.'; exit 0; fi
if [[ -f "$stage/applied.txt" && -f "$root/pixelmcmanager/deploy-titles-ui-0.4.2-20261026/applied.txt" ]]; then exit 0; fi
gson="$root/libraries/com/google/code/gson/gson/2.10/gson-2.10.jar"
if [[ -f "$gson" ]] && java -Xms16m -Xmx64m -cp "$stage/classes:$gson" ManagerUiReadiness "$root/config/pixelmcmanager-bridge.json"; then exit 0; fi
nonce="$(cat /proc/sys/kernel/random/uuid)"
mkdir -p "$stage/responses"
printf '{"nonce":"%s","createdAt":%s}\n' "$nonce" "$(date +%s)" > "$stage/.startup-request.tmp"
mv -- "$stage/.startup-request.tmp" "$stage/startup-request.json"
echo '[Manager UI staging] Waiting for the host to prepare the web backend for this new game startup.'
for ((attempt=0; attempt<75; attempt++)); do
  if [[ -f "$stage/responses/$nonce" ]]; then
    read -r result < "$stage/responses/$nonce"
    if [[ "$result" == 'ready' ]]; then echo '[Manager UI staging] Web backend is ready; continuing pending startup hooks.'; else echo '[Manager UI staging] Web preparation was deferred; incompatible updates remain staged.'; fi
    exit 0
  fi
  sleep 2
done
echo '[Manager UI staging] Host preparation timed out; incompatible updates remain staged.'
