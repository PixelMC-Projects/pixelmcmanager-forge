# Neoforge 1.21.1 port validation

Game version0.6.0; Java21; Minecraft1.21.1. Forge authority: `6b27a14adc0317143f1dec68e019a1430974e5c0`. Fabric lifecycle adaptation begins with the existing official Fabric commit `0728f34`; NeoForge delegates the same lifecycle operations through its event bus.

All 39 game Java sources are enumerated in [source-port-manifest.json](source-port-manifest.json). It records normalized SHA256 per source against the authoritative Forge baseline. `ManagerBridgeApi` is unchanged. No functionality is replaced with stubs, synchronous database calls, simulated telemetry or a second player directory.

Retained contracts:

- Independent Java17 Manager Service0.6.0.web.2, bridge protocol1, namespace storage manifests, callbacks, admitted-player RPC proof, avatars and shared global player identity.
- Local world-root player stats/audit/checkpoints, wall-clock playtime, fake-player exclusion and bounded asynchronous disk/network workers.
- Configurable OP0-4 commands, greetings/announcements/formatting, save/checkpoint/audit semantics, separate maintenance/stop states and restart supervision.
- Current-run telemetry/history retention, archive binding/finalization and reconnect behavior. Backend restart does not create a new game run.
- JOIN admission runs only after stop/restart/maintenance gates. Fabric assigns an early event phase and NeoForge HIGHEST priority; disconnect always clears session admission.

## Build and service regression

`gradlew clean build --max-workers=2` passes. Game checks:15 real loopback RPC admission assertions,9 spool assertions,7 shutdown assertions and35 restart assertions. Python restart-host checks:8 pass, including actual standalone process restart with the original arguments.

The exact official service ZIP was rerun on Java17 with a fresh isolated MariaDB:102 SQL/HTTP/lifecycle checks and59 unified-platform checks pass. This covers the latest Boolean/numeric0/1 presence fix, disconnected filtering, shared identities/namespaces, migrations and backup/restore. The official service remains unchanged.

## Packaged runtime evidence

- `neoforge-1.21.1-combined-client`: PASS; installable JAR dedicated server startup; real TPS/MSPT/heap/world telemetry and archive binding; all installed extensions registered and healthy in unified storage; READY for the isolated packaged NeoForge client on127.0.0.1:25610; real packaged modded client admitted; both extension rows share its platform UUID; modded client logout persists playtime and durable login event; normal game stop finalizes data while web stays available.
- `neoforge-1.21.1-core`: PASS; installable JAR dedicated server startup; real TPS/MSPT/heap/world telemetry and archive binding; real protocol login admitted with shared player identity and extension registration; logout persists wall-clock totals and one durable login event; maintenance-rejected player creates no Manager profile; service restart reconnects without changing current game run; same UUID reconnect settles old session exactly once; normal game stop finalizes data while web stays available.

The1.21 protocol test library initially omitted the normal configuration ping/pong response. The test client now sends standard pong(id); authentication, mod admission and server policies are unchanged. Fabric/Manager-only flows use an isolated offline protocol client; the NeoForge three-mod combination uses a real isolated NeoForge client loading all three formal JARs. All use loopback server/database and unique test schemas. No production runtime, credentials or actual user client is read or modified. Interactive graphics and authenticated account gameplay are outside Manager's server-only test scope; extension client/render checks belong to their release records.

## Exact distribution

- Game JAR: `pixelmcmanager-neoforge-1.21.1-0.6.0.jar`, class major 65, SHA256 `6ccfd510efcf2a39514ab93bb038f796159fd04b6ab8ce52374d4c4791e0ae4e`.
- Shared service: `pixelmc-manager-service-0.6.0.web.2.zip`, SHA256 `a285efc26f30db6a14242eec6f2ea0fe446f0eb3562c946815eb0d31fc810184`.
- Separate restart supervisor: `pixelmcmanager-restart-supervisor-0.6.0.zip`, SHA256 `aeaa47f24039e9799a32ba94305707a52f6ae96b2ca65e70561dd926c7ff40fc`.

JAR includes AGPLv3, original-author NOTICE and Minecraft linking permission. Existing Forge1.20.1 and Fabric26.x game code is unchanged; their default READMEs only gain the cross-version support table.
