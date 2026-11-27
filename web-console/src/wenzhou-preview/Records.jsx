import React, { useLayoutEffect, useRef, useState } from 'react';
import { ArrowLeft, ArrowSquareOut, ChatCircleText, MagnifyingGlass, Rows, Users } from '@phosphor-icons/react';
import { Empty, Modal, Pagination, Switch } from '../titles/ui.jsx';
import {QuotaCells, PlayerQuota} from '@wenzhou-admin/wenzhou/Quota.jsx';
import {PlayerTerraAccess} from '@wenzhou-admin/wenzhou/Terra.jsx';
import {terraPreviewRequest, terraPreviewRevision} from './terraPreview.js';
import { SortLabel } from '../titles/SortLabel.jsx';
import { compareValue } from '../titles/sorting.js';
import { PlayerAvatar } from '../fortune-preview/Avatar.jsx';
import { usageDisplay } from '@wenzhou-preview/usage.js';
import { grantKey, writeValue } from '@wenzhou-preview/managerPreview.js';
import {getPreviewQuota,updatePreviewQuota} from '@wenzhou-preview/quotaPreview.js';
import { answerLines, formatTime, getGrant, logRead, players } from './data.js';

function playerReceipts(sessions) {
  const byPlayer = new Map();
  for (const session of sessions) {
    const receipts = byPlayer.get(session.playerId) || [];
    for (const turn of session.turns) {
      for (const receipt of turn.usageReceipts || []) receipts.push({...receipt,at:receipt.at ?? turn.createdAt});
    }
    byPlayer.set(session.playerId, receipts);
  }
  return byPlayer;
}
const defaultPlayerSort = {key:'grant', direction:'asc'};
// ICU's Chinese pinyin index boundaries let Latin and Han names share one A–Z list.
const pinyinInitials = [...'ABCDEFGHJKLMNOPQRSTWXYZ'];
function nameInitial(name) {
  if (!/^\p{Script=Han}/u.test(name)) return name.charAt(0).toUpperCase();
  return pinyinInitials.findLast(letter => compareValue(name, '\uFDD0' + letter) >= 0) || name.charAt(0);
}
function Tabs({items,value,select,label}) {
  return <div className="view-tabs" role="tablist" aria-label={label}>{items.map(([id,name,count])=>
    <button key={id} role="tab" aria-selected={value===id} className={value===id?'active':''} onClick={()=>select(id)}>
      {name}{count!==undefined&&<small className="tab-count">{count}</small>}
    </button>)}</div>;
}
function Identity({player}) {
  return <><PlayerAvatar player={{id:player.playerId,name:player.name}}/>
    <span className="player-identity"><strong><span className={'status-dot '+(player.isOnline?'online':'')} role="img" aria-label={player.isOnline?'在线':'离线'}/>{player.name}</strong>
      <small className="mono player-uuid" title={player.playerId}>{player.playerId}</small></span></>;
}

function PlayerDetail({player, quota, sessions, hidden, onOpen, tab, setTab, toast}) {
  const granted=getGrant(player);
  const own=sessions.filter(row=>row.playerId===player.playerId).sort((a,b)=>b.updated-a.updated);
  function changeGrant(value) {
    try { writeValue(grantKey(player.playerId),value); toast(value?'已授予问舟访问权限':'已撤销问舟访问权限'); }
    catch { toast('浏览器未能保存权限修改。'); }
  }
  return <>
    <div className="view-bar" hidden={hidden}><Tabs label="玩家详情" value={tab} select={setTab} items={[['access','访问权限'],['history','历史会话',own.length]]}/></div>
    <div className="editor-body" hidden={hidden}>{tab==='access'?<>
      <div className="wza-player player-link"><Identity player={player}/></div>
      <Switch label="允许访问问舟" value={granted} onChange={changeGrant} description="授权后，玩家手册左侧将出现问舟入口。"/>
      <PlayerTerraAccess api={terraPreviewRequest} playerId={player.playerId} granted={granted}
        revision={terraPreviewRevision(player.playerId)+'/'+granted} toast={toast}/>
      <PlayerQuota quota={quota} toast={toast} mutate={(operation, body)=>updatePreviewQuota(player.playerId, operation, body)}/>
    </>:own.length?own.map(row=><button key={row.id} className="wza-history" onClick={event=>onOpen(row,event.currentTarget)}>
      <ChatCircleText size={18}/><span><strong>{row.title}</strong><small>{formatTime(row.updated)} · {row.count} 轮问答</small></span><ArrowSquareOut size={14}/>
    </button>):<Empty title="暂无历史会话" icon={ChatCircleText}/>}</div>
  </>;
}

function Thread({row,bodyRef}) {
  const {metrics, note} = usageDisplay(row.turns);
  return <div className="editor-body" ref={bodyRef} tabIndex={-1} aria-label="会话正文"><div className="wza-thread-meta"><span>{row.player.name}</span><span>{row.count} 轮问答</span><time>{formatTime(row.updated)}</time></div>
      <div className="wza-thread-meta wza-thread-usage" aria-label="本对话累计用量（演示估算）">
        {metrics.map(([label, value]) => <span key={label}>{label} {value}</span>)}
        <span>{note || '演示估算'}</span>
      </div>
      {row.turns.map((turn,i)=><section className="wza-turn" key={turn.id || i}>
        <h3>{row.player.name}<time>{formatTime(turn.createdAt)}</time></h3><p>{turn.question}</p>
        <h3>问舟</h3>{answerLines(turn).map((line,j)=><p key={j}>{line}</p>)}
        {turn.events?.length>0&&<details><summary>查阅过程 · {turn.events.length}</summary><ul>{turn.events.map((e,j)=><li key={j}>{e.text} {e.detail}</li>)}</ul></details>}
      </section>)}
    </div>;
}

export default function Records({people,sessions,toast}) {
  const [tab,setTab]=useState('all');
  const [compact,setCompact]=useState(false);
  const [query,setQuery]=useState('');
  const [index,setIndex]=useState(0);
  const [sort,setSort]=useState(people ? defaultPlayerSort : {key:'updated',direction:'desc'});
  const [player,setPlayer]=useState(null);
  const [playerTab,setPlayerTab]=useState('access');
  const [sessionId,setSessionId]=useState(null);
  const historyButton = useRef(null);
  const threadBody = useRef(null);
  useLayoutEffect(() => {
    if (!historyButton.current) return;
    if (sessionId) {
      threadBody.current?.focus({preventScroll:true});
    } else {
      historyButton.current.focus({preventScroll:true});
      historyButton.current = null;
    }
  }, [sessionId]);
  const size = people ? 50 : 20;
  const receipts = people ? playerReceipts(sessions) : null;
  const rows=people?players.map(p=>({id:p.playerId,player:p,initial:nameInitial(p.name),granted:getGrant(p),
    quota:getPreviewQuota(p.playerId,receipts.get(p.playerId)),
    count:sessions.filter(s=>s.playerId===p.playerId).length})):sessions;
  const online=players.filter(p=>p.isOnline).length;
  const tabs=people?[['all','全部玩家',players.length],['online','在线玩家',online],['offline','离线玩家',players.length-online]]
    :[['all','全部会话',sessions.length]];
  const source=people?rows.filter(r=>tab==='all'||r.player.isOnline===(tab==='online')):sessions;
  const search=query.trim().toLowerCase();
  const filtered=source.filter(r=>[r.player.name,r.player.playerId,r.title].some(v=>v?.toLowerCase().includes(search)));
  function value(row) {
    if (sort.key==='name') return row.player.name;
    if (sort.key==='grant') return row.granted ? 0 : 1;
    if (sort.key === 'quotaLimit') return row.quota.unlimited ? Infinity : row.quota.limitNanoYuan;
    if (sort.key === 'quotaRemaining') return row.quota.unlimited ? Infinity : row.quota.remainingNanoYuan;
    if (sort.key === 'quotaLifetime') return row.quota.lifetimeNanoYuan;
    return row[sort.key];
  }
  filtered.sort((a,b)=>{
    const byName = compareValue(a.initial,b.initial) || compareValue(a.player.name,b.player.name);
    const primary = sort.key==='name' ? (sort.direction==='asc' ? byName : -byName)
      : compareValue(value(a),value(b),sort.direction);
    return primary || byName || a.id.localeCompare(b.id);
  });
  const page=Math.min(index,Math.max(0,Math.ceil(filtered.length/size)-1));
  const selected = sessions.find(row => row.id === sessionId);
  const columns=people?[['name','玩家'],['grant','访问权限'],['count','会话数'],
      ['quotaLimit','每周限额'],['quotaRemaining','剩余额度'],['quotaLifetime','累计 API 消耗']]
    :[['title','会话'],['name','玩家'],['count','问答轮数'],['updated','最近消息']];
  function showThread(row,trigger) {
    try {
      logRead(row);
      historyButton.current = trigger ?? null;
      setSessionId(row.id);
    } catch {
      toast('无法保存本地查阅记录，请重试。');
    }
  }
  function showPlayer(p) {setPlayer(p);setPlayerTab('access');}
  return <>
    <div className="view-bar"><Tabs label={people?'玩家分类':'会话筛选'} items={tabs} value={tab} select={id=>{setTab(id);setSessionId(null);setIndex(0);}}/>
      <div className="view-actions"><button className="button quiet" aria-label="切换列表密度" aria-pressed={compact} onClick={()=>setCompact(!compact)}><Rows size={14}/>{compact?'紧凑':'舒适'}</button></div></div>
    <div className="content-area" data-compact={compact}><div className="list-search-row"><MagnifyingGlass size={15}/><input aria-label={people?'搜索玩家':'搜索会话'} value={query} placeholder={people?'搜索玩家名或 UUID…':'搜索会话标题或玩家…'} onChange={e=>{setQuery(e.target.value);setIndex(0);}}/></div>
      <div className={'table-heading wza-grid '+(people?'wza-players':'wza-chats')}>{columns.map(([field,label])=>
        <span key={field}><SortLabel label={label} field={field} sort={sort} onChange={next=>{setSort(next);setIndex(0);}}/></span>)}</div>
      <div className="scrolling-rows">{filtered.slice(page*size,(page+1)*size).map(row=><button type="button" key={row.id}
        className={'player-row wza-grid wza-record '+(people?'wza-players':'wza-chats')} onClick={()=>people?showPlayer(row.player):showThread(row)}>
        {people?<><span className="player-link"><Identity player={row.player}/></span><span className={row.granted?'wza-granted':'muted'}>{row.granted?'已授权':'未授权'}</span><span>{row.count}</span><QuotaCells quota={row.quota}/></>
          :<><strong className="wza-subject">{row.title}</strong><span>{row.player.name}</span><span>{row.count}</span><time>{formatTime(row.updated)}</time></>}
      </button>)}{!filtered.length&&<Empty icon={people?Users:ChatCircleText} title={search?'没有匹配的结果':'暂无记录'}/>}</div></div>
    <Pagination page={page} size={size} total={filtered.length} onPage={setIndex}>每页 {size} 项 · 北京时间</Pagination>
    {(player || selected) && <Modal wide
      title={selected ? selected.title : player.name}
      subtitle={selected ? 'WenzhouAI · 聊天记录' : 'WenzhouAI · 玩家权限'}
      close={() => selected ? setSessionId(null) : setPlayer(null)}
      footer={player && selected && <button className="button secondary" onClick={()=>setSessionId(null)}><ArrowLeft size={14}/>返回玩家历史</button>}>
      {player && <PlayerDetail key={player.playerId} player={player} quota={rows.find(r=>r.id===player.playerId)?.quota} sessions={sessions}
        hidden={Boolean(selected)} tab={playerTab} setTab={setPlayerTab} toast={toast} onOpen={showThread}/>}
      {selected && <Thread row={selected} bodyRef={threadBody}/>}
    </Modal>}
  </>;
}
