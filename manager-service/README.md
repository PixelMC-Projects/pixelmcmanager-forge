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

Keep runtime configuration, credentials, avatars and backups when upgrading. For source builds, see [BUILDING.md](../BUILDING.md).

---

原作者 / Original author: [zcpu · PixelMC](https://github.com/zcpu954861).

[AGPL-3.0-only](../LICENSE) · [Minecraft linking permission](../LICENSE-EXCEPTION.md).
保留版权与署名；分发修改版或使用修改版提供联网服务时，按许可证提供对应源码。
Retain copyright and attribution; provide corresponding source when distributing modifications or offering modified software over a network, as required by the license.
