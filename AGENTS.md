# AGENTS.md

This repository is a NeoForge 1.21.1 / Java 21 server-side mod project.

Hard rules:
- Keep mod id as pixelmcmanager.
- Keep Java 21 compatibility.
- Do not require client installation.
- Do not add gameplay content, blocks, items, entities, GUI, Mixin, or networking unless explicitly requested.
- Keep player stats in the active world save root under pixelmcmanager/player_stats.json.
- Keep total online time based on real wall-clock milliseconds, not ticks.
- Keep stopserver and maintenance states independent; stopserver join rejection has priority.
- Store audit history in the active world save root under pixelmcmanager/audit.jsonl.
- Keep command permission levels configurable as OP levels 0-4, with dangerous commands defaulting to OP 4.
- Keep /pixelmcmanager save responsible for stats checkpoint, stats save, audit flush confirmation, config save, and server world save request.
- Keep /pixelmcmanager stats read-only for audit and based on in-memory stats with a light online-player checkpoint before reading.
- Keep automatic announcements disabled by default, chat-only, sequential, reload-aware, and low overhead; do not format messages every tick.
- Prefer small, reviewable changes.
- Always run a Gradle build before claiming success.

GitHub release packaging:
- Publish one consolidated release per Manager version, containing every supported loader/Minecraft JAR.
- The canonical release repository is PixelMC-Projects/pixelmcmanager-forge, including Fabric binaries; keep source repositories/branches separate.
- Release title: PixelMC Manager <mod_version>. Release tag: v<mod_version>.
- Do not create a release for each loader/Minecraft branch. Source tags may remain per target without their own release entries.
- Include shared service/supervisor archives once, a SOURCE.json mapping every artifact to its repository/commit, and one complete SHA256SUMS.
- Update README download links to the consolidated release; keep exact compatibility and artifact filenames in the release notes.

Release notes must include a "兼容版本与附件" section.
List each supported Minecraft version and the exact jar asset name there.
Do not use a single "适用环境" section as the only compatibility description, because the same mod version may later include multiple Minecraft versions.

## Plugin web UI

Before creating or modifying plugin pages, read `web-console/docs/插件页面风格规范.md`. Reuse its MemorialTitles component references and record acceptance evidence against its checklist. Keep subsequent user decisions in that standard. Core Manager dashboard patterns are not the plugin settings baseline. Do not claim unverified cases or prototype-only APIs as production-ready.
