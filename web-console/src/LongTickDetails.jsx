import React, { useEffect, useMemo, useState } from 'react';
import { ArrowLeft, ArrowRight, Clock } from '@phosphor-icons/react';
import { Drawer, Status } from './App';
import { SlidingSegments } from './SlidingSegments';
import {useResource} from './transport';

const filters=[50,100,200].map(value=>({value,label:`> ${value} ms`})),pageSize=20;
const date=new Intl.DateTimeFormat('zh-CN',{timeZone:'Asia/Shanghai',year:'numeric',month:'2-digit',day:'2-digit'});
const time=new Intl.DateTimeFormat('zh-CN',{timeZone:'Asia/Shanghai',hour:'2-digit',minute:'2-digit',second:'2-digit',fractionalSecondDigits:3,hour12:false});
export function LongTickDetails({telemetry:t,close}) {
  const [threshold,setThreshold]=useState(50),[page,setPage]=useState(0);
  const remote=useResource(t.platform.scope('/manager/long-ticks?'+new URLSearchParams({threshold,page})),5000);
  useEffect(()=>setPage(0),[threshold,t.sessionId]);
  const total=remote.data?.total??0,events=remote.data?.events??0,pages=Math.max(1,Math.ceil(total/pageSize)),current=Math.min(page,pages-1),rows=remote.data?.rows||[];
  return <Drawer wide state={{title:'长tick详情'}} close={close}><div className="long-tick-intro"><span><Clock size={15}/>近 24 小时 · 本次开服</span><Status tone={t.stale?'muted':'healthy'}>{t.stale?'未更新':'每秒更新'}</Status></div><SlidingSegments label="长tick耗时阈值" options={filters} value={threshold} onChange={setThreshold} className="long-tick-filters"/>{remote.error&&<p className="form-error" role="alert">{remote.error}</p>}<div className="long-tick-result-count"><strong>{events.toLocaleString()}</strong><span>次异常 · {total} 条记录 · 耗时大于 {threshold} ms</span></div><div className="long-tick-list" aria-label="异常tick记录"><div className="long-tick-list-head"><span>发生时间 · 北京时间</span><span>耗时 / 范围</span></div>{rows.map(event=><div className="long-tick-list-row" key={event.id}><time dateTime={new Date(event.at).toISOString()}><span>{date.format(event.at)}</span><strong>{time.format(event.at)}</strong>{event.count>1&&<small className="long-tick-consecutive" title={'结束于 '+time.format(event.endAt)}>（连续{event.count}tick）</small>}</time><span className={'long-tick-duration '+(event.duration>200?'metric-tone-critical':event.duration>100?'metric-tone-hot':'metric-tone-caution')}>{event.count>1?event.minimum.toFixed(1)+'–'+event.maximum.toFixed(1):event.duration.toFixed(1)}<small> ms</small></span></div>)}{!rows.length&&<p className="quiet-note long-tick-empty">此范围内暂无大于 {threshold} ms 的 tick。</p>}</div><footer className="long-tick-pagination"><span>{total?`${current*pageSize+1}–${current*pageSize+rows.length} / ${total}`:'0 条'}</span><div><button className="icon-button" aria-label="上一页长tick" disabled={current<=0} onClick={()=>setPage(current-1)}><ArrowLeft size={14}/></button><span>{current+1} / {pages}</span><button className="icon-button" aria-label="下一页长tick" disabled={current>=pages-1} onClick={()=>setPage(current+1)}><ArrowRight size={14}/></button></div></footer><p className="quiet-note">按游戏上报的真实单个 tick 统计，不是秒级平均样本。50–100 ms 的连续 tick 按 tick 序号压缩展示，次数保留；100 ms 及以上不合并。重启后清空旧记录，只显示本次开服与最近 24 小时的交集；断流时保留已收到的记录。</p></Drawer>;
}
