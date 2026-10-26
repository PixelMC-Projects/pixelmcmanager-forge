# Manager 0.5.2 integration evidence

Production enters AuthRoot. DailyFortunes lives in src/fortune, uses same-origin authenticated /api/dailyfortunes routes and does not import preview fixtures. Production Vite rejects preview dependencies. The isolated fortune-preview entry remains available.

The optional backend is disabled in old configuration. Enabling it requires a distinct MariaDB schema and account. Manager embeds only the pure Java17 FortuneBackend package and default definitions; no DailyFortunes Forge/client class or H2 driver is shipped. Both web and admitted game RPC use that authority. Web recovery queues delivery, rather than claiming physical items were delivered.

Checks: 63 Java logic/admission/lifecycle assertions; 94 isolated SQL/HTTP assertions; 59 frontend checks; 8 restart supervisor checks. Test DB uses loopback 43317 with randomly named schemas, removed after tests. Browser UI uses separately named local SQL schemas and an explicitly synthetic test player; this is not production gameplay validation. Actual UI checks covered weight save/probability, three settings tabs, full backup/SHA-256 preview, and shared dark/light layouts. Screenshots are in web-console/screenshots/dailyfortunes-production-20261026.

The local JVM needs invocation-only JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=D:\Gradle\.tmp because of an existing Windows loopback issue. No global setting was changed. npm ci encountered an existing esbuild file lock; npm install restored the same locked dependencies, and the build skipped only installManagerWeb. All build/test tasks subsequently passed.

Not yet verified: real narrow-screen DailyFortunes rendering, all long-name combinations, or production receipt delivery/client rendering. The new DailyFortunes mod is not part of this Manager release or production staging. Manager CLI backup/restore still covers Manager and MemorialTitles; DailyFortunes has its own backup UI, explicitly documented.
