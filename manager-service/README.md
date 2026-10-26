# PixelMC Manager Service

[中文](#中文) · [English](#english)

## 中文

PixelMC Manager 的常驻网页后台。游戏服关闭时，仍可访问玩家档案、历史整合包和称号管理。

### 功能

- 集中展示实时性能、玩家与维度数据。
- 管理整合包档案、备份和可选扩展。
- 支持用户名密码登录，游戏服启动后自动恢复连接。

### 使用

使用 Java 17 和 MariaDB，可直接运行或交由 MCSManager / Docker 托管。游戏端可使用对应版本的 Forge 或 Fabric PixelMC Manager，MemorialTitles 为可选扩展。

### 部署

1. 准备 MariaDB 的 Manager 数据库和专用账号。把后台 JAR 放在独立运行目录，以下命令统一将它命名为 `service.jar`。
2. 执行 `java -jar service.jar init-config manager.json`，填写 `database` 的连接地址、用户名和密码。`bind`、`port` 决定监听地址；`publicUrl` 填实际网页访问地址。需要直接从外部访问时，设 `bind` 为 `0.0.0.0` 并开放对应端口。
3. 将自选密码写入私密文本文件，然后执行 `java -jar service.jar init-admin manager.json <用户名> <管理员玩家UUID> <密码文件>`，创建网页管理员。
4. 执行 `java -jar service.jar instance manager.json minecraft` 注册游戏实例。将生成的 `data/credentials/minecraft-bridge.json` 复制到游戏服的 `config/pixelmcmanager-bridge.json`；`baseUrl` 必须是游戏进程能够访问的后台地址，容器之间使用互通的容器网络地址。
5. 执行 `java -jar service.jar run manager.json` 启动后台，再启动装有 Manager 模组的游戏服。浏览器访问 `publicUrl` 登录，首次连接后在后台绑定当前整合包/存档。
6. 启用称号扩展时，准备称号数据库并填写 `titlesDatabase`，设置 `titlesEnabled: true`；安装 [MemorialTitles](https://github.com/PixelMC-Projects/MemorialTitles)，按其 README 配置 Manager 模式。停止后台可在控制台输入 `stop`，MCSManager 也使用此停止命令。
7. DailyFortunes 是可选扩展，旧配置默认关闭。需要时在同一 MariaDB 服务新建独立 `dailyfortunes` 数据库和专用账号，填写 `fortunesDatabase`，设置 `fortunesEnabled: true`。禁止填称号或 Manager 数据库。游戏端的 DailyFortunes 使用 Manager 模式，共用这一权威数据库；本服务包不附其新模组 JAR。网页签文、权重、签历、单项撤回和补领均走正式鉴权接口，补领先持久化排队，玩家上线后由游戏确认发放。

DailyFortunes 数据配置页提供独立完整备份、SHA-256 验证/下载和签文配置合并导入。已有签号更新，未列出的定义保留；历史签历保留抽取时的版本。后台 `backup` / `restore` 命令的范围仍是 Manager 与 MemorialTitles；DailyFortunes 必须单独备份，不能把 Manager 备份当作已包含抽签数据。关闭扩展只隐藏和停止其服务，不删除独立数据库。

恢复 DailyFortunes 时先停止相关写入方，新建独立空库，将目标库的 `{ "url": "jdbc:mariadb://127.0.0.1:3306/dailyfortunes_restore", "user": "dailyfortunes", "password": "填写目标库密码" }` 存入私密 `restore-db.json`，然后执行：

```sh
java -cp service.jar cn.pixelmc.dailyfortunes.service.FortuneRestore backup.json restore-db.json RESTORE-EMPTY-DAILYFORTUNES
```

此入口先校验 SHA-256 与结构，只允许空的玩家、签历和审计库；事务恢复，拒绝覆盖已有收藏。不要将密码放在命令行。网页不提供整库覆盖按钮。

配置、凭据、头像和备份保存在运行目录，后续升级保留这些数据。源码构建见 [BUILDING.md](../BUILDING.md)。

## English

The independently running backend for PixelMC Manager. Keep player records, past modpacks, and title management available while the game server is offline.

### Features

- Bring live performance, player, and dimension data into one dashboard.
- Manage modpack archives, backups, and optional extensions.
- Sign in with a username and password; reconnect automatically when the game server starts.

### Usage

Run with Java 17 and MariaDB, directly or through MCSManager / Docker. Connect the matching Forge or Fabric PixelMC Manager mod; MemorialTitles is optional.

### Deployment

1. Create a Manager database and dedicated MariaDB user. Put the backend JAR in a separate runtime directory; the commands below call it `service.jar`.
2. Run `java -jar service.jar init-config manager.json`. Set the connection URL, user and password in `database`, plus `bind`, `port` and the actual browser-facing `publicUrl`. For direct external access, bind to `0.0.0.0` and expose that port.
3. Save your chosen password in a private text file, then run `java -jar service.jar init-admin manager.json <username> <admin-player-UUID> <password-file>` to create a web administrator.
4. Run `java -jar service.jar instance manager.json minecraft`. Copy the generated `data/credentials/minecraft-bridge.json` to the game server's `config/pixelmcmanager-bridge.json`. Its `baseUrl` must be reachable by the game process; containers should use their shared network address.
5. Run `java -jar service.jar run manager.json`, then start the game with the Manager mod installed. Sign in at `publicUrl` and bind the current modpack/world after its first connection.
6. To enable titles, configure a title database in `titlesDatabase`, set `titlesEnabled: true`, install [MemorialTitles](https://github.com/PixelMC-Projects/MemorialTitles), and follow its Manager-mode setup. Enter `stop` to shut down the backend; use the same stop command in MCSManager.
7. DailyFortunes is optional and disabled in existing configurations. Create a separate `dailyfortunes` database/user on the MariaDB service, configure `fortunesDatabase`, then set `fortunesEnabled: true`. Do not reuse the title or Manager database. The game mod uses Manager mode and the same authority; its new mod JAR is not included here. Web recovery queues durable delivery and waits for the game to acknowledge it.

DailyFortunes has its own full backup, SHA-256 verification/download and configuration merge import. Existing IDs are updated, omitted definitions are retained, and historical draw snapshots stay fixed. Manager CLI `backup` / `restore` still covers Manager and MemorialTitles only; back up DailyFortunes separately from its data page. Disabling the optional extension does not delete its database.

For offline DailyFortunes recovery, stop its writers and create a separate empty database. Save the destination `{url,user,password}` in a private `restore-db.json`, then run `java -cp service.jar cn.pixelmc.dailyfortunes.service.FortuneRestore backup.json restore-db.json RESTORE-EMPTY-DAILYFORTUNES`. The tool verifies SHA-256 and structure, restores transactionally, and refuses an existing player/calendar/audit database. Passwords stay out of command arguments; the web UI cannot overwrite a full database.

Keep runtime configuration, credentials, avatars and backups when upgrading. For source builds, see [BUILDING.md](../BUILDING.md).

---

原作者 / Original author: [zcpu · PixelMC](https://github.com/zcpu954861).

[AGPL-3.0-only](../LICENSE) · [Minecraft linking permission](../LICENSE-EXCEPTION.md).
保留版权与署名；分发修改版或使用修改版提供联网服务时，按许可证提供对应源码。
Retain copyright and attribution; provide corresponding source when distributing modifications or offering modified software over a network, as required by the license.

## Shared backend source versions

Manager service 0.5.2 is built from [Forge v0.5.2](https://github.com/PixelMC-Projects/pixelmcmanager-forge/tree/v0.5.2), [MemorialTitles 0bc729f](https://github.com/PixelMC-Projects/MemorialTitles/tree/0bc729f3030c5d0a0df6c8442a561c3a9cc8685a), and [DailyFortunes 9e5b6e6](https://github.com/PixelMC-Projects/DailyFortunes/tree/9e5b6e6c14fa4cede433eadc126b3abe172333f0). Forge and Fabric releases reuse the exact same service/supervisor ZIP bytes. The new DailyFortunes game-mod JAR is not included in this release.
