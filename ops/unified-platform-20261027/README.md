# Unified storage migration

中文：用于旧版 Manager、MemorialTitles、DailyFortunes 三个数据库迁入一个新空库。**先停止游戏和网页，完整备份 SQL、配置及网页数据目录。不要把原库连接地址直接改成新库。** 数据库可以保持运行，迁移工具不会关闭或启动任何服务。

`migrate_storage.py` 使用数据库容器中的 MariaDB 客户端和私密连接文件，不需要在命令行传密码。准备一个私密 `plan.json`：

```json
{
  "format": 1,
  "transport": {
    "kind": "docker",
    "container": "your-database-container",
    "defaultsFile": "/data/secrets/admin-client.cnf"
  },
  "sources": [
    {"schema": "pixelmc_manager", "prefix": "pm_"},
    {"schema": "memorialtitles", "prefix": "mt_"},
    {"schema": "dailyfortunes", "prefix": "df_"}
  ],
  "target": "pixelmc_platform",
  "reportPath": "/private-backup/migration-report.json"
}
```

先由管理员创建空目标库和只访问该库的应用账号，再执行：

```sh
python3 migrate_storage.py inspect plan.json
python3 migrate_storage.py copy plan.json --confirm MIGRATE-EMPTY-UNIFIED
```

工具保留原表结构、外键、自增计数和全部原字段；逐表比较行数及指纹，拒绝非空目标。失败目标保留供检查，原库不删除、不覆盖；不要直接重复执行或删除失败目标中的部分表。

复制原网页 `data/` 到新的数据目录。在新的服务配置中设置 `storageMode: "unified"`，`database` 指向目标库，移除两个旧插件连接字段。保留账号、owners 和桥接凭据，执行：

```sh
java -jar service.jar platform-init manager.json
python3 migrate_storage.py verify plan.json --after-platform-init
```

确认原记录、全局玩家、账号和头像完整后，再切换正式配置。依次确认数据库、手动启动网页并检查连接、最后手动启动游戏服。保留原数据库、原数据目录、旧程序和原 MCSManager 配置供回退；新库开始接收业务写入后不可直接切回旧库。

`deploy.py` 是配套的显式阶段工具，需要私密 `--site` 文件提供目录、容器、MCSManager 实例身份及本地 daemon 配置位置，并需要经过校验的部署 payload。它只提供 `stage`、`backup`、`migrate`、`activate`，不发送停服、启动或重启请求。`start-game.sh` 仅在迁移、激活及已认证后台都就绪后运行原游戏命令，不拉起其他服务。请勿把私密 site、plan、配置、数据库或备份提交到仓库。

## English

Migrate the three legacy stores into **a newly provisioned empty database**. Stop the game and web writers, back up SQL and durable files, then run `inspect` and `copy` with a private plan. Source databases remain intact; populated targets, changed source data, broken foreign keys and sequence mismatches are rejected.

Copy the old application data into a separate directory, configure unified storage, run `platform-init`, and verify all original columns again. Account hashes, avatars, bridge credentials and business receipts must be retained. Verify the database, manually start and check the web service, then manually start the game. Keep the original databases/files for rollback; do not switch back after accepting new writes without reconciling them.

The optional deployment helper requires private site configuration and a verified payload. Its phases never start, stop or restart services. Keep all runtime configuration, migration reports and backups private.
