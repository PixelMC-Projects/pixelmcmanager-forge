# PlayerHub 更新日志服务契约（API 1）

此模块由 Manager 统一平台持久化，命名空间 `playerhub`。当前本地服务与模组版本为 `0.8.0-dev`，更新日志首次接入版本为 `0.7.0-dev`。仅在 `storageMode=unified` 启用；不维护第二份玩家目录，身份使用 `pm_profiles`，排除名单与全局删除墓碑沿用平台规则。

## 能力、通知与错误

`POST /bridge/hello` 和 `/bridge/capabilities` 返回 `playerHubApi:1`（旧存储模式为 0）。现有 hello、snapshot 响应及 `/api/manager/bootstrap` / SSE bootstrap 带 `playerHubRevision`，格式为服务启动随机 epoch 加递增计数。取得该值只读内存；没有新增轮询。持久写入成功后递增，重复的目标点赞、相同 operationId 的重试不制造变化。服务重启更换 epoch。

桥接请求沿用实例凭据，玩家 RPC 固定为：

```json
{"extension":"playerhub","op":"playerHub","playerProof":"UUID","data":{"args":["UUID","snapshot",{}]}}
```

参数 UUID 必须与 playerProof 及该实例有效入服集合匹配。只允许下表列出的玩家动作，没有可透传的管理操作。成功信封为 `{result:{...},healthy:true,revision:"epoch:counter"}`；失败为 `{error:"可展示的中文错误",healthy:boolean,revision:"epoch:counter"}`，不返回 SQL、堆栈或管理快照。结果中的 `revision` 是通知令牌；条目、整合包和设置的数字 `revision` 用于写冲突检查，两者用途不同。

所有时间戳为 epoch 毫秒；草稿 `publishedAt:null` 和旧文档 `bodyDocument:null` 明确保留。界面按北京时间展示，点赞精确到分钟。

权威不可见错误短语固定为 `记录不存在或不可见`（隐藏、撤下、删除、未知条目）和 `更新日志入口暂未开放`。临时故障为 `更新日志数据暂不可用，请稍后重试`。前两者用于清除已撤销内容；临时故障保留缓存并标注过期。隐藏包的 list 返回空页；停用入口的 snapshot 保留设置、返回空包列表及空日志页。

## 通用 DTO 与边界

`pack`：`id,instanceId,name,shortName,version,current,visible,revision,createdAt,entriesCount,commentsCount`。短名从完整名称第一个 ` · `、`：` 或 `: ` 前取得，最多 40 字符；分组身份始终使用完整名称。管理端的 count 含全部状态，玩家端只计已发布条目和该玩家自己的留言。

`entrySummary`：`id,packId,packName,packVersion,updateVersion,title,summary,category,pinned,status,revision,createdAt,updatedAt,publishedAt,likesCount,commentsCount,liked`。玩家 `commentsCount` 只计本人线程，`likesCount` 是总支持数。详情增加 `body,bodyDocument`。

`comment`：`id,entryId,authorId,authorName,text,createdAt,replies,replyCount,repliesPage,repliesSize:20,repliesHasMore`。`reply`：`id,commentId,text,createdAt,adminName`。玩家只能读自己的线程及对应管理员回复，不返回其他作者、点赞名册或管理权限。

日志默认每页 12，管理列表可设 size 1–50；留言每页 12，点赞每页 20，回复每线程每页 20。回复 page 0 是最新窗口，窗口内按时间正序，增大页号读取更早窗口。最大页号 100000。整合包每页 100，必须使用 total/hasMore 继续读取；不会静默截断总量。

snapshot：`{settings,packs,packsTotal,packsPage:0,packsHasMore,selectedPackId,list,revision}`，仅预载首个包的首篇列表摘要，不预载正文或全部互动。通用列表页为 `{rows,total,page,size,revision}`，包页另有 `hasMore`；管理日志页另有 `counts:{all,published,draft,withdrawn}`。分类计数只受整合包范围影响，不受搜索、当前分类或当前页影响。 玩家日志页（含 snapshot.list）另带 `latestId`，为当前包在实例/可见/已发布范围内、不受搜索影响的默认首条 ID：按 pinned DESC、COALESCE(publishedAt,updatedAt) DESC、id ASC；无条目为空串。标题总数使用 `counts.published`，分页匹配数使用 `total`，最新标记按 ID 比较，不能用搜索结果首条代替。管理列表保持原形。

设置：`updatesEnabled,likesEnabled,commentsEnabled,commentLimit,commentCooldown,revision`。默认全部开启、长度 500、间隔 10 秒；长度范围 50–2000，间隔范围 0–300。暂停新留言仍保留历史，管理员可以继续回复。

## 玩家动作

| action | payload | result |
| --- | --- | --- |
| snapshot | `{}` | snapshot |
| packs | `{page}` | 包分页 |
| list | `{packId,page,search}` | 已发布摘要分页，固定 12 条，置顶优先 |
| read | `{id,commentsPage,replyPages?:{commentUUID:page}}` | `{entry,comments,revision}`，replyPages 最多 12 项 |
| like | `{id,liked:boolean}` | `{id,liked,likesCount,revision}` |
| comment | `{id,text,operationId:UUID}` | 最新 read 结果 |
| deleteComment | `{id,commentId}` | 最新 read 结果 |

`liked` 必须是显式布尔目标值。评论 operationId 按“玩家 + ID”唯一，绑定动作与规范化文本摘要；重复请求只返回已持久化结果，改变内容重用 ID 会冲突。删除后的 operationId 保留去重墓碑，重试不会重建线程。长度、冷却时间、身份、包可见性和发布状态由服务事务检查。

## 管理 HTTP

沿用 Manager 网页账户、管理员鉴权、Origin 与 CSRF。前缀 `/api/playerhub`。GET 只读；写入仅 POST。验证错误 400，越权 403，修订号/状态冲突 409，服务不可用 503。旧存储模式返回 409，旧服务无这些接口，可由前端明确提示升级。

| GET 路径 | 查询参数 | result |
| --- | --- | --- |
| /state | 无 | snapshot |
| /packs | page | 包分页 |
| /entries | packId,status,search,sort,direction,page,size | 摘要分页与分类总量 |
| /entry | id,commentsPage,commentsFilter,likesPage | `{entry,comments,likes,revision}` |
| /replies | id,commentId,page | 回复分页 |

`status=all|published|draft|withdrawn`，`sort=title|time|likes|comments`，`direction=asc|desc`；无论指定哪列，置顶始终优先。`commentsFilter=all|pending`，pending 指没有管理员回复的线程，`comments.counts` 始终提供当前条目下 `{all,pending}` 总量。

| POST 路径 | payload |
| --- | --- |
| /entry/save | 条目平面字段，`id` 新建省略或 null；`expectedRevision:0` 新建；`publish:boolean` |
| /entry/status | id,status（published 或 withdrawn）,expectedRevision |
| /entry/pin | id,pinned:boolean,expectedRevision |
| /entry/delete | id,confirmTitle,expectedRevision |
| /settings | 设置平面字段加 expectedRevision |
| /pack/visibility | packId,visible:boolean,expectedRevision |
| /comment/reply | id（条目 ID）,commentId,text,operationId:UUID |
| /comment/delete | id,commentId |

save/status/pin 返回 `{entry,revision}`；settings 返回 `{settings,revision}`；visibility 返回 `{pack,revision}`；delete 返回 `{ok:true,revision}`；留言操作返回最新详情。管理员回复也支持 operationId 去重。删除日志必须逐字匹配已持久化标题及数字 revision；同一事务删除正文、点赞、留言、回复和去重记录。撤下、隐藏均不删除数据。

## 文档、包身份和平台所有权

正文纯文本/旧 Markdown `body` 最长 20000 UTF-16 字符。可附 `bodyDocument`，只接受 ProseMirror `doc/paragraph/heading(level:3)/bulletList/listItem/hardBreak/text`，文本 marks 只允许 `bold`。禁止未知节点、属性、链接、图片和 HTML。最多深度 16、4000 节点、160000 UTF-8 字节；保存时从受限文档重建 body。管理回复最长 2000 字符。玩家详情最大为 12 个线程，各 20 回复，传输端使用 updates 专用 2MiB 界限及有界分片；其它 Hub 模块的界限不扩大。

包 UUID 从“实例 ID + 换行 + Manager 完整包名”稳定派生，独立于世界 UUID 和版本。启动、hello 和管理档案创建/归档时同步 Manager 元数据；周期 snapshot 不查询包数据。同名换世界或升级版本继续一个分组，单篇 packName/packVersion/updateVersion 保留历史快照。切到不同完整包名时旧分组默认隐藏；历史内容始终供管理员查看，可单独重新开放。

原生清单注册以下八张持久表：`ph_settings,ph_packs,ph_instances,ph_entries,ph_likes,ph_comments,ph_replies,ph_operations`。PlatformStore 仅允许该命名空间拥有此固定白名单。游戏侧注册的 playerhub 空存储元数据不能覆盖原生清单。完整格式 3 备份自动含每张表的 schema 和 rows；空库恢复使用现有逐表指纹校验。没有上传目录或额外资产文件。

清单的 playerTables 声明点赞、留言、回复所有者/管理员及 operation 操作者/线程所有者。全局玩家删除先预览、再原子清理并写恢复项；恢复沿用平台修订号与定义签名，日志 ID/数字修订号也纳入签名，避免已删或修改日志上的个人记录被强制恢复。插件内删日志不删除玩家身份。

## 隔离验证

运行 `python tools/run-player-hub-updates-tests.py`。启动器拒绝占用中的 127.0.0.1:43320，在 `build/hub-updates-tests/<随机目录>` 新建 datadir，仅绑定回环，仅终止自己创建的进程。测试创建随机 schema 与空恢复 schema，结束时只删除这两库；不读部署配置、不复用已有数据库。

`playerHubUpdatesServiceTest` 通过真实 Manager HTTP、账户/CSRF、桥接入服证明及 MariaDB 验证：文档/null、发布状态、分页/搜索/计数、点赞与操作去重、留言隐私/管理员回复、冷却/长度/功能开关、包身份与历史可见性、全局删除恢复、完整备份与空库恢复。

可选 `PM_HUB_VISUAL_HOLD=1` 在 assertions 后保留 15 分钟隔离网页 fixture，路径写入 build.log；fixture.json 仅含新建测试账户，关闭/stopFile 后清除。编译、隔离 HTTP/DB 与浏览器验证不等于真实 Minecraft 客户端或生产验收。
