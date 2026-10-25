import React, { useState } from 'react';
import { ArrowLeft, ArrowRight, Clock } from '@phosphor-icons/react';

import {useResource} from './transport';
const PAGE_SIZE = 8;
const date = new Intl.DateTimeFormat('zh-CN', {timeZone:'Asia/Shanghai',year:'numeric',month:'2-digit',day:'2-digit'});
const time = new Intl.DateTimeFormat('zh-CN', {timeZone:'Asia/Shanghai',hour:'2-digit',minute:'2-digit',second:'2-digit',hour12:false});
export function PlayerLoginHistory({ player, telemetry:t }) {
  const [page, setPage] = useState(0);
  const remote=useResource(player.fake?null:t.platform.scope('/manager/logins?'+new URLSearchParams({player:player.id,page,size:PAGE_SIZE})),5000);
  const total=remote.data?.total??0,pages=Math.max(1,Math.ceil(total/PAGE_SIZE)),current=Math.min(page,pages-1),offset=current*PAGE_SIZE,rows=remote.data?.rows||[];
  return <section className="player-login-history" aria-label="玩家登录记录">
    <header><h3><Clock size={16}/>登录记录</h3><span>{total} 条</span></header>
    <p className="quiet-note">成功进入服务器的时间 · 北京时间 · 最新在前</p>
    <ol>{rows.map(row=><li key={row.id}><time dateTime={new Date(row.at).toISOString()}><span>{date.format(row.at)}</span><strong>{time.format(row.at)}</strong></time>{row.current&&<span className="login-current-label">本次登录</span>}</li>)}</ol>
    {!rows.length&&<p className="quiet-note">暂无记录。</p>}
    <footer><span>{total ? `${offset+1}–${offset+rows.length} / ${total}` : '0 条'}</span><div><button className="icon-button" aria-label="上一页登录记录" disabled={current<=0} onClick={()=>setPage(current-1)}><ArrowLeft size={14}/></button><span>{current+1} / {pages}</span><button className="icon-button" aria-label="下一页登录记录" disabled={current>=pages-1} onClick={()=>setPage(current+1)}><ArrowRight size={14}/></button></div></footer>
    <p className="quiet-note">{remote.error||'从启用之日起记录真实成功登录时间；已有累计次数无法还原旧历史。'}</p>
  </section>;
}
