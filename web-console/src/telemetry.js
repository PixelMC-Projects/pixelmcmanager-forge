import {useEffect,useMemo,useRef,useState} from 'react';
import {usePlatform} from './Platform';
import {request} from './transport';
import {PerformanceHistory} from './performanceHistory';
import {detectHeapLeak} from './heapHealth';
import {detectTickHealth} from './tickHealth';
export function useTelemetry(){
  const platform=usePlatform(),s=platform.value?.state||{},run=s.runId||'',[now,setNow]=useState(Date.now()),[revision,setRevision]=useState(0);
  const archive=useRef(new PerformanceHistory()),lastRun=useRef('');
  useEffect(()=>{const id=setInterval(()=>setNow(Date.now()),1000);return()=>clearInterval(id);},[]);
  useEffect(()=>{let active=true;archive.current.reset(s.startedAt||0);lastRun.current=run;setRevision(n=>n+1);if(run&&!s.historical)request(platform.scope('/manager/performance?window=43200000')).then(rows=>{if(active){const merged=[...rows,...archive.current.read(43200000,Date.now())].sort((a,b)=>a.at-b.at);const fresh=new PerformanceHistory();fresh.reset(s.startedAt||0);for(const row of merged)fresh.append(row);archive.current=fresh;setRevision(n=>n+1);}}).catch(()=>{});return()=>{active=false;};},[run,s.historical,platform.archive,platform.instance]);
  useEffect(()=>{if(s.metric&&run===lastRun.current){archive.current.append({...s.metric,at:s.at});setRevision(n=>n+1);}},[s.at,run]);
  const receivedAt=s.at||s.receivedAt||0,stale=!!platform.error||!s.connected||s.historical||now-receivedAt>5000;
  const history=useMemo(()=>archive.current.read(300000,Math.max(receivedAt,now)),[revision,receivedAt,now]);
  const latest=s.metric||{tps:NaN,mspt:NaN,heap:NaN},worlds=(s.worlds||[]).map(w=>({...w,performance:{tickMs:null,tickShare:null,...w.performance,tickShare:w.performance?.share??w.performance?.tickShare},storage:{totalBytes:null,measuredAt:w.storage?.sampledAt,...w.storage}}));
  const worldTotals=worlds.reduce((a,w)=>({strong:a.strong+(w.strong||0),weak:a.weak+(w.weak||0),entities:a.entities+(w.entities||0),forced:a.forced+(w.forced||0),tickMs:a.tickMs+(w.performance.tickMs||0),storageBytes:a.storageBytes+(w.storage.totalBytes||0)}),{strong:0,weak:0,entities:0,forced:0,tickMs:0,storageBytes:0});
  const players=(s.players||[]).map(p=>({...p,isOnline:true,online:formatPlayTime(p.sessionMillis),avatarUrl:p.avatarUrl||'/api/manager/avatars/'+p.id+'.png?v='+(p.avatarVersion??0),world:p.world}));
  const stats=archive.current.snapshot(300000,receivedAt).stats;
  return {...s,platform,history,latest,now,receivedAt,stale,worlds,worldTotals,players,startedAt:s.startedAt||now,previewStartedAt:s.startedAt||now,sessionId:run,age:receivedAt?Math.max(0,Math.floor((now-receivedAt)/1000)):0,mode:'live',setMode:()=>{},maxHeap:s.maxHeap??NaN,heapCommitted:s.heapCommitted??NaN,
    heapAfterGc:s.collections?.at(-1)?.after,heapHealth:detectHeapLeak({samples:archive.current.read(600000,receivedAt),collections:s.collections||[],maxHeap:s.maxHeap,now:receivedAt,startedAt:s.startedAt,stale}),tickHealth:detectTickHealth(history,stale),
    p95:stats.p95,maximum:stats.maximum,longTicks:[],readPerformance:window=>archive.current.snapshot(window,receivedAt),population:{online:players.filter(p=>!p.fake).length,limit:s.limit??0}};
}
const date=new Intl.DateTimeFormat('zh-CN',{timeZone:'Asia/Shanghai',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',hour12:false});
export const formatDate=value=>Number.isFinite(value)&&value>0?date.format(new Date(value)):'—';
export function uptime(value){const seconds=Number.isFinite(value)?Math.max(0,Math.floor(value/1000)):0;return {days:Math.floor(seconds/86400),hours:String(Math.floor(seconds/3600)%24).padStart(2,'0'),minutes:String(Math.floor(seconds/60)%60).padStart(2,'0'),seconds:String(seconds%60).padStart(2,'0')};}
export function formatPlayTime(ms){if(!Number.isFinite(ms)||ms<0)return '—';const minutes=Math.floor(ms/60000),hours=Math.floor(minutes/60);return hours?`${hours}小时${minutes%60}分钟`:`${minutes}分钟`;}
