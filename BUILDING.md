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

Manager service 0.5.2 is built from [Forge v0.5.2](https://github.com/PixelMC-Projects/pixelmcmanager-forge/tree/v0.5.2), [MemorialTitles 0bc729f](https://github.com/PixelMC-Projects/MemorialTitles/tree/0bc729f3030c5d0a0df6c8442a561c3a9cc8685a), and [DailyFortunes 9e5b6e6](https://github.com/PixelMC-Projects/DailyFortunes/tree/9e5b6e6c14fa4cede433eadc126b3abe172333f0). Forge and Fabric releases reuse the exact same service/supervisor ZIP bytes. The new DailyFortunes game-mod JAR is not included in this release.
