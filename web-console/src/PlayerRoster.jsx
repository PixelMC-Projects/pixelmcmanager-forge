import React, { useState,useEffect } from 'react';
import { ArrowUpRight, ArrowsDownUp, CaretDown, CaretUp, MagnifyingGlass, Robot } from '@phosphor-icons/react';
import { formatPlayTime } from './telemetry';
import { sortPlayers } from './playerSorting';
import { latencyTone } from './trendData';
import { PlayerAvatar } from './PlayerAvatar';

import {useResource} from './transport';

export function PlayerRoster({telemetry:t,inspect}) {
  const [tab,setTab]=useState('all'),[query,setQuery]=useState('');
  const [sort,setSort]=useState({key:'status',direction:'asc'});
  const [page,setPage]=useState(0);
  useEffect(()=>setPage(0),[tab,query,sort.key,sort.direction,t.sessionId]);
  const all=tab==='all';
  const remote=useResource(tab==='fakes'?null:t.platform.scope('/manager/players?'+new URLSearchParams({q:query,sort:sort.key,direction:sort.direction,online:tab==='humans'?'true':'',page,size:50})),2000);
  const fakes=t.players.filter(p=>p.fake);
  const source=tab==='fakes'?fakes:(remote.data?.rows||[]).map(p=>({...p,online:formatPlayTime(p.sessionMillis)}));
  const rows=tab==='fakes'?sortPlayers(source.filter(p=>`${p.name} ${p.id}`.toLowerCase().includes(query.toLowerCase())),sort.key,sort.direction):source;
  const columns=[['name','玩家'],...(all?[['status','在线状态']]:[]),['world','所在维度'],['ping','延迟'],['session','本次在线'],['totalOnlineMillis','总在线时长'],['joinCount','总登录次数']];
  const sortHint=key=>key==='status'?(sort.key===key?(sort.direction==='asc'?'当前在线优先，点击离线优先':'当前离线优先，点击在线优先'):'点击在线优先')+'；组内始终按 A–Z':sort.key===key?(sort.direction==='asc'?'当前升序，点击降序':'当前降序，点击升序'):'点击升序排序';
  return <div className="detail-scroll"><div className="detail-inner">
    <div className="view-toolbar">
      <div className="view-tabs" role="tablist" aria-label="玩家类型">{[['all',`全部玩家 · ${t.totals?.players??0}`],['humans',`在线玩家 · ${t.population.online}`],['fakes',`假人 · ${fakes.length}`]].map(([id,label])=><button key={id} role="tab" aria-selected={tab===id} className={tab===id?'active':''} onClick={()=>{setTab(id);setQuery('');setSort({key:id==='all'?'status':'name',direction:'asc'});}}>{label}</button>)}</div>
      <label className="search-input"><MagnifyingGlass size={15}/><input aria-label="搜索玩家" placeholder="搜索玩家名或 UUID…" value={query} onChange={e=>setQuery(e.target.value)}/></label>
    </div>
    <section className={'detail-section roster-section'+(all?' all-player-roster':'')} aria-label={all?'全部玩家列表':tab==='humans'?'在线玩家列表':'假人列表'}>
      <div className="roster-head">{columns.map(([key,label])=><span key={key} className={'roster-column-'+key}><button type="button" className="roster-sort-button" aria-label={'按'+label+'排序'} aria-pressed={sort.key===key} title={sortHint(key)} onClick={()=>setSort(previous=>({key,direction:previous.key===key&&previous.direction==='asc'?'desc':'asc'}))}>{label}{sort.key===key?(sort.direction==='asc'?<CaretUp size={11}/>:<CaretDown size={11}/>):<ArrowsDownUp size={11}/>}</button></span>)}<span/></div>
      {rows.map(player=><button className="roster-row" key={player.id} onClick={()=>inspect({type:'player',title:'玩家详情',player})}>
        <span className="roster-identity">{player.fake?<span className="robot-avatar"><Robot size={20}/></span>:<PlayerAvatar player={player}/>}<span className="roster-identity-copy"><strong>{player.name}</strong><small className="mono" title={player.id}>{player.id}</small></span></span>
        {all&&<span className="roster-column-status"><span className={'player-presence '+(t.stale&&!t.stopped&&!t.historical?'unknown':player.isOnline?'online':'offline')} role="img" aria-label={t.stale&&!t.stopped&&!t.historical?'状态未更新':player.isOnline?'在线':'离线'} title={t.stale&&!t.stopped&&!t.historical?'状态未更新':player.isOnline?'在线':'离线'}/></span>}
        <span className="roster-column-world">{t.stale||!player.isOnline?'—':player.world}</span>
        <span className={'roster-column-ping mono metric-tone-'+latencyTone(player.ping,player.fake||!player.isOnline||t.stale)}>{player.fake||!player.isOnline||t.stale?'—':player.ping+' ms'}</span>
        <span className="roster-column-session">{t.stale||!player.isOnline?'—':player.online}</span>
        <span className="roster-column-totalOnlineMillis">{formatPlayTime(player.totalOnlineMillis)}</span>
        <span className="roster-column-joinCount">{player.joinCount??'—'}</span><ArrowUpRight size={14}/>
      </button>)}
      {!rows.length&&<div className="empty-state"><MagnifyingGlass size={24} weight="light"/><h3>没有匹配的结果</h3><p>尝试其他玩家名或 UUID。</p></div>}
    </section>
    <p className="quiet-note">{remote.error|| (tab==='fakes'?'假人不计入真人档案或累计登录。':'当前整合包的累计统计；成功入服后才登记，离线头像保留。')}</p>
    {tab!=='fakes'&&<div className="dimension-list-footer"><span>共 {remote.data?.total??0} 人 · 每页50人</span><div><button className="button secondary" disabled={page===0} onClick={()=>setPage(n=>n-1)}>上一页</button><span>{page+1}</span><button className="button secondary" disabled={(page+1)*50>=(remote.data?.total??0)} onClick={()=>setPage(n=>n+1)}>下一页</button></div></div>}
  </div></div>;
}
