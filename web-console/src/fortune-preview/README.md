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
