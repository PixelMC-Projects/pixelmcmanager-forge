import React, { useEffect, useReducer } from 'react';
import { Books, Sailboat, ChatCircleText, SlidersHorizontal, Users } from '@phosphor-icons/react';
import { prefix, subscribePreview } from '@wenzhou-preview/managerPreview.js';
import { listSessions } from './data.js';
import Knowledge from './Knowledge.jsx';
import Records from './Records.jsx';
import Service from './Service.jsx';
import './workspace.css';

function Workspace({page,preferences,toast,setUnsaved}) {
  const [,refresh]=useReducer(n=>n+1,0);
  useEffect(()=>subscribePreview(key=>{if (!key?.startsWith(prefix+'audit:')) refresh();}),[]);
  return <div className="titles-module wza-module" data-theme={preferences.theme}>
    {page==='wenzhou-service'?<Service toast={toast} setUnsaved={setUnsaved}/>
      :page==='wenzhou-knowledge'?<Knowledge/>:<Records key={page} people={page==='wenzhou-players'} sessions={listSessions()} toast={toast}/>}
  </div>;
}
function Details({navigate}) {
  return <><h2>问舟AI</h2><p className="detail-intro">管理问舟访问权限、历史会话和服务设置。</p>
    <div className="inline-actions"><button className="button primary" onClick={()=>navigate('wenzhou-players')}>进入扩展</button>
      <button className="button secondary" onClick={()=>navigate('wenzhou-service')}>扩展设置</button></div></>;
}
export const wenzhouPlugin = {
  name:'WenzhouAI',label:'问舟AI',icon:Sailboat,preference:'wenzhou',connected:false,
  description:'问舟访问权限、历史会话和模型服务',defaultPage:'wenzhou-players',playerHref:'/?page=wenzhou',
  nav:[['wenzhou-players','玩家权限',Users],['wenzhou-chats','聊天记录',ChatCircleText],['wenzhou-knowledge','知识记录',Books],['wenzhou-service','服务管理',SlidersHorizontal]],
  descriptions:{'wenzhou-players':'管理问舟访问权限，查看玩家的历史会话。','wenzhou-chats':'查阅全服会话与问答记录。','wenzhou-knowledge':'查看问舟保存的玩法笔记和证据来源。','wenzhou-service':'配置模型服务与额度设置。'},
  Component:Workspace, Details,
};
