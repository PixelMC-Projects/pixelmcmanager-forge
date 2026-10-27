# PixelMC Manager Service

[中文](#中文) · [English](#english)

## 中文

独立运行的服务器管理后台。游戏停服时仍可查看玩家档案、整合包历史、称号与每日一签。

### 部署

使用 **Java 17 + MariaDB**，可交给 MCSManager / Docker 托管。

1. 新建一个平台数据库和专用账号。将服务包中的后台 JAR 命名为 `service.jar`，运行 `java -jar service.jar init-config manager.json`。
2. 填写 `database`、`bind`、`port`、`publicUrl`。新配置默认 `storageMode: "unified"`；各插件共用该数据库，无需分别建库。对外访问时使用 `0.0.0.0` 并开放网页端口。
3. 把自选密码存入私密文本文件，执行 `java -jar service.jar init-admin manager.json <用户名> <玩家UUID> <密码文件>`。
4. 运行 `java -jar service.jar instance manager.json minecraft`，将生成的 `data/credentials/minecraft-bridge.json` 复制为游戏服的 `config/pixelmcmanager-bridge.json`。容器之间的 `baseUrl` 应使用互通的地址。
5. 运行 `java -jar service.jar run manager.json`，正常启动安装了对应 Forge / Fabric Manager 的游戏服，在网页绑定当前整合包和存档。
6. 需要时开启 `titlesEnabled` / `fortunesEnabled`，安装加载器兼容的 [MemorialTitles](https://github.com/PixelMC-Projects/MemorialTitles) / [DailyFortunes](https://github.com/PixelMC-Projects/DailyFortunes)，按其 README 选择 Manager 模式。后台控制台输入 `stop` 正常结束。

Manager 维护统一玩家身份、账号和头像；插件在各自命名空间保存资料。称号与签历使用全局数据，游玩统计和登录历史按整合包归档。仅清理某插件的玩家资料不会删除全局玩家或头像。

### 升级与备份

**旧版三个独立数据库不能只改连接地址。** 先停止游戏与后台、完整备份，把原有 `pm_*`、`mt_*`、`df_*` 表无损复制到新的空平台数据库，再配置 `storageMode: "unified"` 和新 `database`，移除旧的两个插件数据库字段。复制原 `data/` 后，执行 `java -jar service.jar platform-init manager.json` 回填全局身份。该操作不模拟登录、不补发奖励或签，不删除原数据库。保留原库和原数据目录供回退。

统一模式的网页备份入口与 `java -jar service.jar backup manager.json` 都创建完整平台 ZIP，包含已注册插件、玩家资料、账号、头像和桥接凭据，每12小时自动备份；性能缓存不进入备份。`backupRetentionDays` 为默认保留天数，现有称号页的保留设置也会交由平台保存。妥善保管备份，不要放入公开仓库。

恢复前停止所有写入方，准备**空数据库和空数据目录**，执行 `java -jar service.jar restore manager.json <备份.zip>`；需要同目录的 `.sha256`。逐表校验，拒绝覆盖已有资料。

[扩展存储接口](../docs/platform-storage-api.md) · [源码构建](../BUILDING.md)

## English

An independent server dashboard. Player records, archived modpacks, titles and fortunes remain accessible while Minecraft is stopped.

### Deployment

Requires **Java 17 and MariaDB**; MCSManager / Docker can supervise it.

1. Create one platform database and dedicated user. Name the backend JAR `service.jar` and run `java -jar service.jar init-config manager.json`.
2. Set `database`, `bind`, `port` and `publicUrl`. New configurations use `storageMode: "unified"`; extensions share that database. Bind to `0.0.0.0` and expose the web port for external access.
3. Save a chosen password in a private text file, then run `java -jar service.jar init-admin manager.json <username> <player-UUID> <password-file>`.
4. Run `java -jar service.jar instance manager.json minecraft`. Copy `data/credentials/minecraft-bridge.json` to the game's `config/pixelmcmanager-bridge.json`. Its `baseUrl` must be reachable from the game container.
5. Run `java -jar service.jar run manager.json`, start the matching Forge / Fabric Manager mod, and bind the current modpack/world in the dashboard.
6. Optionally enable `titlesEnabled` / `fortunesEnabled` and install loader-compatible MemorialTitles / DailyFortunes builds in Manager mode. Enter `stop` in the backend console to shut it down.

Manager owns shared player identities, accounts and avatars. Extensions store namespaced data. Titles and fortune collections are global; playtime and login history retain their modpack archive scope. Plugin-local cleanup preserves the shared identity and avatar.

### Upgrades and backups

**Do not migrate the old separate stores by merely changing their URLs.** Stop writers, back up everything, copy existing `pm_*`, `mt_*` and `df_*` tables losslessly into a new empty platform database, configure `storageMode: "unified"`, and remove the old plugin DB fields. Copy the previous `data/` directory, then run `java -jar service.jar platform-init manager.json`. It backfills identities without simulating logins, issuing rewards or deleting source databases. Preserve the originals for rollback.

In unified mode, all web backup actions and `java -jar service.jar backup manager.json` produce a complete platform ZIP with registered extension data, accounts, avatars and bridge credentials. Automatic backups run every 12 hours; telemetry is excluded. `backupRetentionDays` supplies the default retention, and the existing title retention control delegates to platform policy. Keep backups private.

To restore, stop all writers and prepare an **empty database and empty data directory**. Run `java -jar service.jar restore manager.json <backup.zip>` with its adjacent `.sha256` file. Tables are verified and existing data is never overwritten.

[Extension storage API](../docs/platform-storage-api.md) · [Building](../BUILDING.md)

---

原作者 / Original author: [zcpu · PixelMC](https://github.com/zcpu954861).
[AGPL-3.0-only](../LICENSE) · [Minecraft linking permission](../LICENSE-EXCEPTION.md).
保留署名；分发修改版或以修改版提供联网服务时按许可证提供对应源码。
Retain attribution and provide corresponding source as required by the license.
