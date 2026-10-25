# 构建 / Building

使用 JDK 17（`JAVA_HOME`）。只构建服务端模组：
Use JDK 17 (`JAVA_HOME`). To build the server mod:

```sh
./gradlew build
```

常驻网页后台还需要 Node.js 22 / npm，并复用 MemorialTitles 仓库中的共享业务代码。请将两个仓库放在同一目录：
The independent web service also needs Node.js 22 / npm and shared code from MemorialTitles. Clone both repositories side by side:

```text
projects/
  pixelmcmanager-forge/
  MemorialTitles/
```

```sh
./gradlew managerServiceJar
```

其他布局可用 `-PmemorialTitlesDir=/path/to/MemorialTitles`。Gradle 自动获取 JDBC 和锁定的前端依赖；不需要复制数据库、运行配置、`libs/` 或本地缓存。
For a different layout set `-PmemorialTitlesDir=/path/to/MemorialTitles`. Gradle resolves JDBC and locked frontend packages; no database, runtime configuration, `libs/` or local cache needs copying.

Windows 使用 `gradlew.bat`。模组产物位于 `build/libs/`，网页服务位于 `build/manager-service/`。
On Windows use `gradlew.bat`. Mod output: `build/libs/`. Web service output: `build/manager-service/`.
