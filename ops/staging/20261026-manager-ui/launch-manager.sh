#!/usr/bin/env bash
set -euo pipefail
umask 077
stage="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd "${PIXELMC_SERVICE_ROOT:-/data}"
command=("$@")
n=${#command[@]}
if [[ "${1:-}" == '--check-payload' ]]; then (cd "$stage" && sha256sum --check SHA256SUMS); echo '[Manager UI staging] Service payload verified; no backup or JVM executed.'; exit 0; fi
if [[ $n -lt 5 || "${command[$((n-2))]}" != 'run' || "${command[$((n-1))]}" != 'manager.json' ]]; then echo '[Manager UI staging] Unexpected service arguments.' >&2; exit 1; fi
jar_index=-1
for ((i=0; i<n-1; i++)); do if [[ "${command[$i]}" == '-jar' ]]; then jar_index=$((i+1)); break; fi; done
if [[ $jar_index -lt 0 || "${command[$jar_index]}" != 'releases/0.5.1/pixelmc-manager-service.jar' ]]; then echo '[Manager UI staging] Original service path changed; review pending update.' >&2; exit 1; fi
if ! (cd "$stage" && sha256sum --check SHA256SUMS); then echo '[Manager UI staging] Payload mismatch; retaining original service.' >&2; exec "${command[@]}"; fi
if [[ ! -f "$stage/pre-upgrade-backup-complete.txt" ]]; then
 if ! sha256sum --check "$stage/expected-current.sha256"; then echo '[Manager UI staging] Original service changed; retaining original service.' >&2; exec "${command[@]}"; fi
 backup=("${command[@]}");backup[$((n-2))]='backup'
 if ! "${backup[@]}" > "$stage/pre-upgrade-backup.log" 2>&1; then echo '[Manager UI staging] Native backup failed; retaining original service.' >&2; exec "${command[@]}"; fi
 date -u +%FT%TZ > "$stage/pre-upgrade-backup-complete.txt"
fi
command[$jar_index]="$stage/pixelmc-manager-service-0.5.2-web.1.jar"
echo '[Manager UI staging] Starting backend 0.5.2-web.1 with original JVM arguments after native backup.'
exec "${command[@]}"
