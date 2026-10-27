# PixelMC Manager · Forge

[中文](#中文) · [English](#english)

## 中文

把服务器状态、玩家数据和日常管理集中到一个地方。模组可以独立使用，也可以接入常驻网页后台。

### 功能

- 实时查看 TPS、MSPT、内存、在线玩家与维度状态。
- 记录累计在线时长、登录次数和登录历史，保留离线玩家资料。
- 配置首次进服问候、回归问候和轮播公告，支持颜色与渐变。
- 预约关服、维护和空服重启，也可在模组列表变化后安排空服重启。
- 整合包结束后可归档数据，方便切换查看不同阶段的记录。
- 通过扩展接入 MemorialTitles、DailyFortunes 等项目，共用玩家库、头像与完整备份。

### 使用

适用于 Minecraft **1.20.1 / Forge**，只需服务端安装。

1. 将模组放入服务端 `mods/`，启动后生成 `config/pixelmcmanager.json`，按需修改问候、公告和管理配置。
2. 只使用模组功能时，不需要数据库或网页程序。
3. 需要网页时，先按 [Manager Service 部署流程](manager-service/README.md) 启动独立后台，再把生成的实例桥接配置放到 `config/pixelmcmanager-bridge.json`，重启游戏服接入。后台使用 MariaDB，游戏停服时仍可访问。

## English

Server monitoring, player records, and everyday administration in one place. Use the mod on its own or connect it to an independently running web service.

### Features

- Monitor live TPS, MSPT, memory, players, and dimensions.
- Keep playtime totals, login counts, login history, and offline player profiles.
- Customize first-join greetings, returning-player messages, and rotating announcements with colors and gradients.
- Schedule shutdowns, maintenance, and empty-server restarts, including restarts after mod-list changes.
- Archive completed modpacks and browse records from earlier seasons.
- Bring extensions such as MemorialTitles and DailyFortunes into one dashboard with shared player identities, avatars and complete backups.

### Usage

For Minecraft **1.20.1 / Forge**. Install on the server only.

1. Put the mod in the server's `mods/` folder. Start the server to generate `config/pixelmcmanager.json`, then configure greetings, announcements and management options.
2. Standalone mod features need no database or web service.
3. For the dashboard, follow the [Manager Service deployment steps](manager-service/README.md), copy its generated instance configuration to `config/pixelmcmanager-bridge.json`, and restart the game server. The MariaDB-backed dashboard remains available while the game is stopped.

---

原作者 / Original author: [zcpu · PixelMC](https://github.com/zcpu954861).

[AGPL-3.0-only](LICENSE) · [Minecraft linking permission](LICENSE-EXCEPTION.md).
保留版权与署名；分发修改版或使用修改版提供联网服务时，按许可证提供对应源码。
Retain copyright and attribution; provide corresponding source when distributing modifications or offering modified software over a network, as required by the license.
