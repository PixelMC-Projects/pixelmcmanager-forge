# 构建与部署 / Building and deployment

游戏模组：Minecraft 1.21.1 / NeoForge，Java 21。网页后台是独立的 Java17 进程。
Game mod: Minecraft 1.21.1 / NeoForge, Java 21. The independent web backend runs on Java17.

```sh
./gradlew clean build --max-workers=2
```

Windows 使用 `gradlew.bat`，游戏 JAR 输出在 `build/libs/`。Fabric 还需要同游戏版本的 Fabric API；Manager 本体只安装在服务端。
On Windows use `gradlew.bat`; installable game JARs are in `build/libs/`. Fabric also needs matching Fabric API. Manager is server-only.

## 常驻网页 / Independent dashboard

使用本次附件中的 `pixelmc-manager-service-0.6.0.web.2.zip`。它是现有正式网页修复包的原字节副本，含独立服务；重启托管脚本见另附的 pixelmcmanager-restart-supervisor-0.6.0.zip；模组版本及桥接协议仍为0.6.0。按[部署步骤](https://github.com/PixelMC-Projects/pixelmcmanager-forge/blob/6b27a14adc0317143f1dec68e019a1430974e5c0/manager-service/README.md)创建专用 MariaDB 平台库、初始化管理员、登记游戏实例，复制生成的桥接配置到 `config/pixelmcmanager-bridge.json`，再启动游戏服并在网页绑定存档。
Use the included `pixelmc-manager-service-0.6.0.web.2.zip`, a byte-identical copy of the latest official service package ; restart supervision is provided separately in pixelmcmanager-restart-supervisor-0.6.0.zip. Game version and bridge protocol remain0.6.0. Follow the pinned deployment guide to create a dedicated MariaDB platform schema, initialize an administrator, register an instance and copy its bridge configuration to `config/pixelmcmanager-bridge.json`. Start the game server and bind its world/archive in the dashboard.

共享玩家、账号、头像、整合包归档、命名空间扩展存储及备份沿用现有统一平台。MemorialTitles / DailyFortunes 游戏扩展必须安装相同游戏版本和加载器的 JAR，并使用各项目说明中的 Manager 模式。无后台时，Manager 的本地管理功能独立运行。
Shared players, accounts, avatars, archives, namespaced extension storage and backups retain the existing unified platform. MemorialTitles / DailyFortunes game extensions need matching loader/game JARs and Manager mode. Local Manager administration also works without a backend.

从旧分库迁移必须先备份、停止写入并迁到新的空平台库，再执行 `platform-init`；不要仅替换连接地址。更新网页时保留 `manager.json`、`data/` 和凭据。不要将运行配置、令牌或玩家数据提交到源码。
Before migrating old split databases, back up and stop writers, copy to a new empty platform schema and run `platform-init`; changing URLs alone is insufficient. Preserve `manager.json`, `data/` and credentials when updating the service. Never commit runtime credentials or player data.

## 复现服务 / Reproduce the service

服务源码固定为 Forge仓 `6b27a14adc0317143f1dec68e019a1430974e5c0`。服务包中的源码记录固定其扩展依赖；在该源码版本按原 BUILDING.md 使用 Java17 构建 `managerServiceJar -PmanagerServiceVersion=0.6.0.web.2`。不要从移动中的扩展分支静默重建旧版本服务包。
Service source is pinned to Forge commit `6b27a14adc0317143f1dec68e019a1430974e5c0`. Follow the package source manifest and its original BUILDING.md with Java17 and pinned extension commits. Do not silently rebuild the old service version from moving extension branches.

移植范围和测试证据见 [port-validation.md](docs/port-validation.md)。
See [port-validation.md](docs/port-validation.md) for source scope and validation evidence.
