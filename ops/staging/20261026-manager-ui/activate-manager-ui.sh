#!/usr/bin/env bash
set -euo pipefail
umask 077
root="${PIXELMC_GAME_ROOT:-/data}"
stage="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd "$root"
(cd "$stage" && sha256sum --check SHA256SUMS)
if [[ "${1:-}" == '--check-payload' ]]; then echo '[Manager UI staging] Payload verified; no runtime probe or activation.'; exit 0; fi
if [[ -f "$stage/applied.txt" ]]; then exit 0; fi
if [[ ! -f "$root/pixelmcmanager/deploy-reminder-0.4.1-20261025/applied.txt" ]] || ! sha256sum --check "$stage/expected-future.sha256"; then
 echo '[Manager UI staging] Previous reminder stage or expected Manager is not ready; update postponed.'; exit 0
fi
shopt -s nullglob
managers=(mods/pixelmcmanager*.jar)
if [[ ${#managers[@]} -ne 1 || "${managers[0]}" != 'mods/pixelmcmanager-forge-1.20.1-0.5.1.jar' ]]; then echo '[Manager UI staging] Unexpected Manager inventory; update postponed.'; exit 0; fi
backup="$stage/before-activation"
mkdir -p "$backup"
cp -p -- "${managers[0]}" "$backup/"
rollback() {
 trap - ERR INT TERM
 set +e
 rm -f -- mods/pixelmcmanager-forge-1.20.1-0.5.2.jar mods/.manager-ui-0.5.2.new
 cp -p -- "$backup/pixelmcmanager-forge-1.20.1-0.5.1.jar" mods/
 if sha256sum --check "$stage/expected-future.sha256"; then echo '[Manager UI staging] Previous Manager restored; update postponed.'; exit 0; fi
 echo '[Manager UI staging] Rollback unverified; game startup stopped for review.' >&2; exit 1
}
trap rollback ERR INT TERM
cp -- "$stage/pixelmcmanager-forge-1.20.1-0.5.2.jar" mods/.manager-ui-0.5.2.new
chmod --reference="${managers[0]}" mods/.manager-ui-0.5.2.new
rm -- "${managers[0]}"
mv -- mods/.manager-ui-0.5.2.new mods/pixelmcmanager-forge-1.20.1-0.5.2.jar
sha256sum --check "$stage/expected-new.sha256"
date -u +%FT%TZ > "$stage/.applied.tmp"
mv -- "$stage/.applied.tmp" "$stage/applied.txt"
trap - ERR INT TERM
echo '[Manager UI staging] Manager 0.5.2 applied before startup; client sync remains server-only excluded.'
