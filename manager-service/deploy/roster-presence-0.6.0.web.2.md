# 0.6.0.web.2 玩家在线状态修复

统一玩家库使用 LEFT JOIN / COALESCE 查询时，MariaDB 会将 online 和 counter_warning 从 BOOLEAN 转为 INTEGER。Gson 的 getAsBoolean 不把数字1当作true，导致总览、在线人数与插件名单正确，Manager玩家列表及详情却显示离线并隐藏维度、延迟和本次在线时间。

此版本在玩家响应中同时接受 JDBC 布尔值和0/1，继续由连接状态限制实时在线展示。查询、SQL排序、分页和页面布局保持原有设计。计数回退警告使用同一转换，避免发生相同误判。

真实MariaDB回归先复现统一查询分支的失败，再验证在线、离线、连接中断、在线筛选、旧直接列读取以及计数警告；59项统一平台检查、102项SQL/HTTP/生命周期检查及Gradle build通过。仅检查旧式布尔列无法覆盖这个问题。

部署时停止独立网页服务，替换service.jar后启动；保留manager.json和数据目录。游戏Manager仍为0.6.0，网页修复包共用于Forge和Fabric，无需更新游戏模组、客户端或数据库结构。回滚只需正常停止网页并恢复上一份service.jar，不还原玩家数据。

English: MariaDB projects COALESCE(boolean,FALSE) as an integer. Normalize the two roster flags explicitly so live players remain online in list/detail responses. Existing direct-boolean storage and disconnected filtering remain supported. Only replace and restart the standalone web service; no game or schema update is required.
