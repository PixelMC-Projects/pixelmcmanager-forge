import React from 'react';
import { createRoot } from 'react-dom/client';
import { BookOpen, FileText, GearSix, Trophy, IdentificationCard, Archive } from '@phosphor-icons/react';
import '@fontsource-variable/inter';
import '../styles.css';
import '../layout.css';
import '../playerRoster.css';
import '../extensions.css';
import '../titles/module.css';
import '../fortune-preview/preview.css';
import '../listChrome.css';
import { wenzhouPlugin } from '../wenzhou-preview/Workspace.jsx';
import { PreviewApp } from '../fortune-preview/PreviewApp.jsx';
import { HubWorkspace } from './HubWorkspace.jsx';
import { HubRankingsPreview } from './HubRankings.jsx';
import { HubPlayerCards } from './HubPlayerCards.jsx';
import { HubHistoryArchives } from './HubHistoryArchives.jsx';
function HubPreviewWorkspace(props) {
  if (props.page === 'hub-players') return <HubPlayerCards {...props}/>;
  if (props.page === 'hub-history') return <HubHistoryArchives {...props}/>;
  if (props.page === 'hub-rankings') return <HubRankingsPreview {...props}/>;
  return <HubWorkspace {...props}/>;
}
const extension = {
  name:'PlayerHub', label:'手册管理', icon:BookOpen, defaultPage:'hub-updates',
  nav:[['hub-players','玩家名片',IdentificationCard],['hub-history','历史存档',Archive],['hub-updates','更新日志',FileText],['hub-rankings','玩家排行',Trophy],['hub-settings','Hub设置',GearSix]],
  descriptions:{'hub-players':'查看与编辑玩家公开展示的资料和精选收藏。','hub-history':'按整合包管理玩家的旅途回忆、补记与纪念展示。','hub-updates':'记录整合包的每次变化，管理玩家的点赞与私密反馈。','hub-settings':'配置玩家手册的页面入口与互动方式。','hub-rankings':'查看玩家的美食品鉴、签笺收藏与累计在线时长。'},
  Component:HubPreviewWorkspace,
};
const plugins = [wenzhouPlugin];
createRoot(document.getElementById('root')).render(<React.StrictMode><PreviewApp extension={extension} plugins={plugins}/></React.StrictMode>);
