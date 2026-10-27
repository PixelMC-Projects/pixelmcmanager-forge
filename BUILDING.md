# 构建 / Building

使用 JDK 17（`JAVA_HOME`）。只构建服务端模组：
Use JDK 17 (`JAVA_HOME`). To build the server mod:

```sh
./gradlew build
```

常驻网页后台还需要 Node.js 22 / npm，并复用 MemorialTitles 和 DailyFortunes 仓库中的共享业务代码。请将三个仓库放在同一目录：
The independent web service also needs Node.js 22 / npm and shared code from MemorialTitles and DailyFortunes. Clone all three repositories side by side:

```text
projects/
  pixelmcmanager-forge/
  MemorialTitles/
  DailyFortunes/
```

```sh
./gradlew managerServiceJar
```

其他布局可用 `-PmemorialTitlesDir=/path/to/MemorialTitles -PdailyFortunesDir=/path/to/DailyFortunes`。服务只编译 DailyFortunes 的纯 Java17 `service` 包和默认签文资源，不包含其 Forge/客户端代码。Gradle 自动获取 JDBC 和锁定的前端依赖；不需要复制数据库、运行配置、`libs/` 或本地缓存。
For a different layout set `-PmemorialTitlesDir=/path/to/MemorialTitles -PdailyFortunesDir=/path/to/DailyFortunes`. Only the pure Java17 DailyFortunes service package and default definitions enter the web service; its Forge/client code is excluded. Gradle resolves JDBC and locked frontend packages; no database, runtime configuration, `libs/` or local cache needs copying.

Windows 使用 `gradlew.bat`。模组产物位于 `build/libs/`，网页服务位于 `build/manager-service/`。
On Windows use `gradlew.bat`. Mod output: `build/libs/`. Web service output: `build/manager-service/`.

## Shared backend source versions

Manager Service 0.6.0 requires MemorialTitles 0.5.0-dev and DailyFortunes 0.2.0. Use the exact dependency commits listed in the release service package for reproducible builds. Both Fabric versions reuse the same Java17 service and supervisor ZIP; Fabric game extensions still need loader-compatible builds.

Manager Service 0.6.0 需要 MemorialTitles 0.5.0-dev 与 DailyFortunes 0.2.0 的共享源码。复现发行版时使用服务包内记录的精确提交。两个 Fabric 分支复用同一份 Java17 服务与托管包；游戏扩展仍需对应加载器版本。

Service-only hotfix 0.6.0.web.1 uses DailyFortunes 0.2.1 shared source. Build with `./gradlew managerServiceJar -PmanagerServiceVersion=0.6.0.web.1`. The game mod and bridge protocol stay at 0.6.0; both Fabric versions reuse this same service package without a game-code port.

仅网页修复版 0.6.0.web.1 使用 DailyFortunes 0.2.1 的共享源码。构建命令如上；游戏 Manager 和桥接协议仍为 0.6.0。两个 Fabric 版本共用此网页包，不需要改动游戏代码。
