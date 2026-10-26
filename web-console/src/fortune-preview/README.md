# DailyFortunes 本地预览 / Local preview

抽签管理的可交互页面原型，沿用 PixelMC Manager 与 MemorialTitles 的设计。包含签文档案、玩家签历、操作记录和抽签设置，以及六十支签文与模拟玩家数据。

Interactive prototype for DailyFortunes, following the existing Manager design. Includes fortune editing, player calendars, operation history and settings with 60 fortunes and simulated players.

```sh
cd web-console
npm ci
npm run dev:fortunes
```

打开 / Open: `http://127.0.0.1:4174/fortune-preview.html#/fortunes`

修改仅保存在本浏览器。设置页可重置示例。此入口不连接游戏服、数据库或正式 API；正式入口和构建配置保持独立。历史签文保留抽取时版本目前是预览约定。

Changes stay in this browser and can be reset in Settings. This entry uses local mocks, with no game server, database or production API connection. Production entry and build configuration remain separate. Preserving historical fortune text is a prototype assumption.

美术为本项目原创资源；签文采用已修订的第二版。/ Artwork is original to this project; the fortune text uses the revised second draft.


## 待正式合并的界面统一项（2026-10-26）

本分支已在本地预览实现，尚未部署。正式更新时按资源所属工程同步，不把网页服务更新等同于游戏 JAR 更新。

- Titles 是插件通用 UI 基准；DailyFortunes 仅签文美术、月历、等级颜色保留特殊设计。
- 两个插件的页面及详情标签统一透明底、选中文字高亮、底部细线。
- Manager 与两个插件的列名表头统一 Titles 的深浅主题底色。
- Titles 内部所有滚动区域使用细滚动条、透明轨道、无上下箭头。
- 抽签列表的搜索栏、行高、分页及侧栏标题/正文/页脚继承 Titles 的尺寸与间距。
- 保留既定圆角侧栏、插件切换拖影、紧凑预览、等级与拼音排序，以及月历右下角等级渐变。

合并时检查 Manager 网页服务和 MemorialTitles 独立网页资源；如资源嵌入 JAR，再重建对应 JAR。DailyFortunes 仍是隔离的模拟页面，不应被作为已完成的游戏模组发布。
