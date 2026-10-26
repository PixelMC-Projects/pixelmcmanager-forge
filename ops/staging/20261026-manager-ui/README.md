# Manager / Titles future-start staging · 2026-10-26

These are the exact, host-bound deployment helpers used for this staging operation. They contain no credentials. They deliberately abort on changed instance bindings, existing staging directories, unexpected payloads or modified baselines; they are not generic installers to rerun blindly.

Release code is immutable Forge `v0.5.2` / `961cde16accdbe72601a4662ad99afcaf8e995f5`. The game JAR SHA-256 is `71bd6bc6d915df392c8223c961d2d793a9bd6d0a6b0ec6ebe229a7a7fe518a27`; the service JAR is `4ce5d55f90a0bb9c079e996acce259175ca1bacc6e68f7fa277a74e3ffeddf82`. This later operations commit does not change that release/tag or its attachments.

## Installed flow

- Game stage: `/home/rhymc/dockerserver/mc-server-1/pixelmcmanager/deploy-manager-ui-0.5.2-20261026`.
- Web stage: `/home/rhymc/dockerserverdata/pixelmc-manager/deployment/manager-ui-0.5.2-20261026`.
- Titles stage: `/home/rhymc/dockerserver/mc-server-1/pixelmcmanager/deploy-titles-ui-0.4.2-20261026`.
- Host coordinator: `/opt/pixelmcmanager/coordinate-manager-ui-0.5.2.py`.
- Waiting listener: `pixelmc-manager-ui-startup-0.5.2.path`; worker: `pixelmc-manager-ui-startup-0.5.2.service`.

At the next normal game startup, `coordinate-startup.sh` runs before the existing reminder hook. If the authenticated backend is already ready it continues immediately. Otherwise it atomically writes a new startup nonce; the host path listener handles that future request. The host verifies a new game container and the absence of a running Forge JVM, checks the exact MCSM bindings, and makes at most one normal web preparation request for that nonce. It restarts the running web instance, or opens it if stopped, then waits for authenticated API readiness. It never restarts the game or database. Daemon credentials remain exclusively on the host.

The future web launcher performs a native full backup before switching to the staged service, retaining all original JVM arguments. A failed backup retains the original service. Once the backend is ready, the existing reminder and FramedBlocks hooks run, then Manager 0.5.2 and MemorialTitles 0.4.2-dev activate. This removes the need for a separate manual web restart. Failures and timeouts leave incompatible updates staged. Payload-integrity or unverified rollback failures stop that startup for inspection.

The listener was installed in `active/waiting`; its worker had never executed, with no startup request, activation marker, production backup or readiness probe created during staging. The old `pixelmc-deploy-framed-20261026.service` automatic empty-server executor is inactive; all its payloads and startup hooks remain intact. Actual activation is not claimed. DailyFortunes is not deployed or enabled. Local client old mods are untouched.

## Evidence

The release passed 63 Java checks, 97 isolated Manager SQL/HTTP/lifecycle checks, 59 frontend checks, and 8 supervisor checks. Deployment fixtures passed 7 activation/rollback/argument scenarios and `coordinator-local-test.py` passed 8 future-request scenarios without real Docker/MCSM calls. Server static payload checks passed; game/web PIDs and start times plus active mods, AutoModpack configuration/live manifest and original web JAR stayed unchanged. Private `staging-report.json` files are stored in both stage directories; they are not committed.

## Cancel / rollback

Before activation, disable the waiting listener with `systemctl disable --now pixelmc-manager-ui-startup-0.5.2.path`. The worker should still be inactive. Restore the game activator from `activate-manager.before-staging.sh` in the Manager game stage, and restore only the original web future start command from its private `web-instance.before.json` using MCSM. Keep the previous reminder and FramedBlocks payloads. `activate-manager.before-coordination.sh` removes only the new coordinator while retaining the later UI startup hook, so it is not a complete cancellation.

Actual activation saves old game files under each stage's `before-activation/`; the web stage records `pre-upgrade-backup-complete.txt` only after a successful native backup. After activation, stop affected writers normally and use the verified pre-upgrade backup with matching binaries for any database downgrade. Do not overwrite existing player data or assume a JAR-only downgrade reverses a schema migration. Inspect `coordination-report.json` and `responses/<nonce>` for future coordination outcomes; these files contain no credentials.

## Immutable web-only correction

The future web payload now uses `pixelmc-manager-service-0.5.2-web.1.jar` (SHA-256 `d13c8c8e4817f9b55e56f53eb5070eee63b1d71a6a28a26c4e8399a584b1d1e6`). It fixes DailyFortunes avatars to 32px in lists and 40px in profiles. All 619 Java classes and every non-web resource are byte-identical to the original service above; only web resources and manifest version differ. The immutable original v0.5.2 release is retained. `update-web-patch.py` updates only the unactivated web payload/launcher/checksums after checking that the future worker has never run. The prior state is retained in web stage `before-web-patch/`. Final packaged-service browser checks measured exactly 32x32 and 40x40 in dark/light themes against the isolated real SQL backend.
