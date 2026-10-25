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
- 通过扩展接入 MemorialTitles 等项目，统一管理入口。

### 使用

适用于 Minecraft **1.20.1 / Forge**，只需服务端安装。配置文件为 `config/pixelmcmanager.json`。

网页后台需单独运行 [Manager Service](manager-service/README.md)，使用 MariaDB 保存数据；游戏停服时后台仍可访问。

## English

Server monitoring, player records, and everyday administration in one place. Use the mod on its own or connect it to an independently running web service.

### Features

- Monitor live TPS, MSPT, memory, players, and dimensions.
- Keep playtime totals, login counts, login history, and offline player profiles.
- Customize first-join greetings, returning-player messages, and rotating announcements with colors and gradients.
- Schedule shutdowns, maintenance, and empty-server restarts, including restarts after mod-list changes.
- Archive completed modpacks and browse records from earlier seasons.
- Bring extensions such as MemorialTitles into the same dashboard.

### Usage

For Minecraft **1.20.1 / Forge**. Install on the server only. Configuration: `config/pixelmcmanager.json`.

Run [Manager Service](manager-service/README.md) separately for the web dashboard and MariaDB-backed storage. The dashboard stays available while the game server is stopped.

---

原作者 / Original author: [zcpu · PixelMC](https://github.com/zcpu954861).

[AGPL-3.0-only](LICENSE) · [Minecraft linking permission](LICENSE-EXCEPTION.md).
保留版权与署名；分发修改版或使用修改版提供联网服务时，按许可证提供对应源码。
Retain copyright and attribution; provide corresponding source when distributing modifications or offering modified software over a network, as required by the license.
