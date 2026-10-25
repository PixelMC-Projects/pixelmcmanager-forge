import { useEffect, useMemo, useRef, useState } from 'react';
import { SHARED_SAVE_BYTES, storageSnapshot, worldPerformance } from './worldMetrics';
import { LONG_TICK_DAY, PerformanceHistory } from './performanceHistory';
import { detectHeapLeak, heapScenario } from './heapHealth';
import { detectTickHealth } from './tickHealth';

const HISTORY = 300;
const clamp = (value, min, max) => Math.max(min, Math.min(max, value));
const wave = n => Math.sin(n * .73) * .51 + Math.cos(n * 1.97) * .25 + Math.sin(n * 3.71) * .24;
function sample(n, at, lag = 0) {
  const mspt = 24.6 + wave(n + 40) * 2.1 + lag;
  return {
    at,
    tps: clamp(Math.min(19.96 + wave(n) * .035, 1000 / mspt), 0, 20),
    mspt,
    heap: 6.12 + ((n % 70) / 70) * .18 + wave(n + 80) * .015,
  };
}

export function useTelemetry() {
  const initial = useRef(Date.now());
  const archive = useRef(null);
  if(!archive.current){
    archive.current=new PerformanceHistory();archive.current.reset(initial.current-(2*24+6)*3600000-14*60000);
    for(let i=0;i<43200;i++){
      const ago=43199-i;let lag=0;
      for(const [center,height] of [[68,45],[1080,68],[2580,84],[10800,56],[32400,90]])lag=Math.max(lag,Math.max(0,height-Math.abs(ago-center)*9));
      archive.current.append(sample(i,initial.current-ago*1000,lag));
    }
  }
  const sequence = useRef(43200);
  const lagStep = useRef(0);
  const modeRef = useRef('normal');
  const [mode, setModeState] = useState('normal');
  const [now, setNow] = useState(initial.current);
  const [receivedAt, setReceivedAt] = useState(initial.current);
  const [history, setHistory] = useState(() => archive.current.read(299000,initial.current));
  const [startedAt,setStartedAt]=useState(archive.current.startedAt),[sessionId,setSessionId]=useState(0);
  const [longTicks,setLongTicks]=useState(()=>[...Array.from({length:20},(_,index)=>({id:`burst-${index}`,at:initial.current-25000+index*75,duration:55+index%9*4.5,tickIndex:700000+index,runId:'preview-run'})),...Array.from({length:133},(_,index)=>({id:`seed-${index}`,at:initial.current-index*11*60000-12000,duration:[63.4,124.7,258.6,88.5,341.2,50.1,100,200][index%8],tickIndex:800000-index*10000,runId:'preview-run'}))]);
  const resetSession=()=>{const at=Date.now();initial.current=at;archive.current.reset(at);sequence.current=0;lagStep.current=0;modeRef.current='normal';setModeState('normal');const first=sample(0,at);archive.current.append(first);setHistory([first]);setLongTicks([]);setStartedAt(at);setSessionId(previous=>previous+1);setReceivedAt(at);setNow(at);};
  const setMode = next => { if(next==='restart'){resetSession();return;}modeRef.current = next; lagStep.current = 0; setModeState(next); };
  useEffect(() => {
    const timer = setInterval(() => {
      const current = Date.now(); setNow(current);
      if (modeRef.current === 'offline') return;
      let lag = 0;
      if(modeRef.current==='load')lag=45;
      if(modeRef.current==='risk')lag=19;
      if (modeRef.current === 'lag') {
        lag = [4, 14, 39, 93.6, 61, 28, 12, 3][lagStep.current++] ?? 0;
        if (lagStep.current >= 8) { modeRef.current = 'normal'; setModeState('normal'); }
      }
      sequence.current++;
      const next=sample(sequence.current,current,lag);archive.current.append(next);
      setHistory(previous => [...previous.slice(-(HISTORY - 1)), next]);
      if(lag>0||sequence.current%180===0){const event={id:`${archive.current.startedAt}-${sequence.current}`,at:current-25,duration:lag>0?54+lag*2.8:[61.2,135.8,280.4][Math.floor(sequence.current/180)%3]};setLongTicks(previous=>[event,...previous.filter(row=>row.at>=current-LONG_TICK_DAY)]);}
      setReceivedAt(current);
    }, 1000);
    return () => clearInterval(timer);
  }, []);
  const latest = history[history.length - 1];
  const stale = now - receivedAt >= 2500;
  const sorted = history.map(s => s.mspt).sort((a, b) => a - b);
  // One shared world snapshot per received update. No per-row or per-detail timer.
  const liveWorlds = useMemo(() => {
    const step = Math.floor((receivedAt - initial.current) / 1000);
    const performance = worldPerformance(history, worlds);
    return worlds.map((world,index) => {
      const strong = world.strong + Math.round(Math.sin(step * .55 + index) * 4) - Math.round(Math.sin(index) * 4);
      const weak = world.weak + Math.round(Math.sin(step * .4 + index) * 2) - Math.round(Math.sin(index) * 2);
      const entities = world.entities + Math.round(Math.sin(step * .65 + index) * 7) - Math.round(Math.sin(index) * 7);
      const blockEntities = [326,52,19][index] + Math.round(Math.sin(step * .3) * 2);
      const minutes = 9 * 60 + 42 + Math.floor(step * 1.2);
      const time = index === 0 ? `第 ${128 + Math.floor(minutes / 1440)} 天 · ${String(Math.floor(minutes / 60) % 24).padStart(2,'0')}:${String(minutes % 60).padStart(2,'0')}` : world.time;
      return {...world,strong,weak,chunks:strong+weak,entities,blockEntities,time,performance:performance[index],storage:storageSnapshot(world,receivedAt,initial.current)};
    });
  },[receivedAt,history]);
  const liveWorldTotals = useMemo(() => liveWorlds.reduce((sum,world) => ({strong:sum.strong+world.strong,weak:sum.weak+world.weak,entities:sum.entities+world.entities,forced:sum.forced+world.forced,tickMs:sum.tickMs+(world.performance.tickMs??0),storageBytes:sum.storageBytes+world.storage.totalBytes}),{strong:0,weak:0,entities:0,forced:0,tickMs:0,storageBytes:0}),[liveWorlds]);
  const memory=useMemo(()=>heapScenario(receivedAt,archive.current,startedAt,mode==='memory'),[receivedAt,startedAt,mode]);
  const heapHealth=useMemo(()=>detectHeapLeak({...memory,maxHeap:13,now:receivedAt,startedAt,stale}),[memory,receivedAt,startedAt,stale]);
  const tickHealth=useMemo(()=>detectTickHealth(history,stale),[history,stale]);
  const visibleHistory=useMemo(()=>mode==='memory'?history.map(row=>({...row,heap:11.55+Math.max(0,1-(receivedAt-row.at)/360000)*1.1})):history,[history,receivedAt,mode]);
  return {
    history:visibleHistory, latest:visibleHistory.at(-1), now, receivedAt, mode, setMode, stale,heapHealth,tickHealth,heapCommitted:mode==='memory'?13:8,heapAfterGc:memory.collections.at(-1)?.after,
    previewStartedAt:initial.current,
    sessionId,longTicks,readPerformance:window=>archive.current.snapshot(window,receivedAt),
    worlds:liveWorlds, worldTotals:liveWorldTotals,
    saveBytes:liveWorldTotals.storageBytes+SHARED_SAVE_BYTES,
    startedAt,
    age: Math.max(0, Math.floor((now - receivedAt) / 1000)),
    p95: sorted[Math.floor(sorted.length * .95)],
    maximum: sorted[sorted.length - 1],
  };
}

export const playerExamples = [
  { id: '8d3852fb-491a-4e3f-9a81-219644608912', name: 'zhan_cpu', head: 6, ping: 36, world: '主世界', online: '2小时14分钟' },
  { id: '04b8c927-c8cb-435d-9cc9-01e30f288e13', name: 'ovo_Jiang', head: 1, ping: 52, world: '下界', online: '1小时36分钟' },
  { id: '6bfe8d03-451b-4eea-85b1-ddfe147a4ac9', name: 'Kira', head: 12, ping: 28, world: '主世界', online: '47分钟' },
  { id: '357f8e76-261e-45d3-aa01-fd52bda11cb4', name: 'Aster', head: 0, ping: 45, world: '末地', online: '35分钟' },
  { id: '8e05ac11-06ce-4557-8ee0-a9f12e67832c', name: 'Lumen', head: 8, ping: 61, world: '主世界', online: '22分钟' },
  { id: '79ca1708-eb0a-4332-bdc1-124e964d7ad3', name: 'Nori', head: 14, ping: 32, world: '主世界', online: '18分钟' },
].map((player,i)=>({...player,totalOnlineMillis:([286,164,208,92,74,36][i]*60+[14,26,39,18,7,42][i])*60000,joinCount:[83,47,62,29,22,14][i]}));

export const onlinePlayerExamples = [...playerExamples, ...['Akira','Bamboo','Cedar','Dawn','Echo','Frost'].map((name,i)=>({
  id:`00000000-0000-4000-a000-${String(i+1).padStart(12,'0')}`,
  name, totalOnlineMillis:((14+i*9)*60+12+i*3)*60000, joinCount:7+i*5, head:[0,1,6,8,12,14][i], ping:[30,74,75,149,150,301][i], world:i===1?'下界':'主世界', online:`${16-i*2}分钟`,
}))].map(player=>({...player,isOnline:true,avatarUrl:`/heads/${player.head}.png`,avatarVersion:1}));

// Registered human profiles remain available after logout. Local fixtures only.
export const allPlayerExamples = [...onlinePlayerExamples, ...['Birch','Maple','Mira','Orion','Willow','Yuki'].map((name,i)=>({
  id:`10000000-0000-4000-a000-${String(i+1).padStart(12,'0')}`,name,head:[8,12,0,14,6,1][i],
  isOnline:false,avatarUrl:`/heads/${[8,12,0,14,6,1][i]}.png`,avatarVersion:1,
  world:null,ping:null,online:null,totalOnlineMillis:(38+i*23)*3600000,joinCount:11+i*7,
  lastSeen:Date.UTC(2026,9,23,12-i,30),lastLoginAt:Date.UTC(2026,9,23,10-i,15),
}))];

export const worlds = [
  { name: '主世界', code: 'minecraft:overworld', chunks: 892, strong: 728, weak: 164, forced: 48, entities: 615, players: 9, time: '第 128 天 · 09:42', weather: '晴朗', tickWeight: .77,
    storage:{path:'world/',terrainBytes:3820*1024**2,entityBytes:123*1024**2,poiBytes:41*1024**2,otherBytes:10*1024**2,regionFileCount:128}},
  { name: '下界', code: 'minecraft:the_nether', chunks: 248, strong: 196, weak: 52, forced: 16, entities: 172, players: 2, time: '—', weather: '—', tickWeight: .17,
    storage:{path:'world/DIM-1/',terrainBytes:604*1024**2,entityBytes:28*1024**2,poiBytes:6*1024**2,otherBytes:2*1024**2,regionFileCount:29}},
  { name: '末地', code: 'minecraft:the_end', chunks: 108, strong: 72, weak: 36, forced: 0, entities: 76, players: 1, time: '—', weather: '—', tickWeight: .06,
    storage:{path:'world/DIM1/',terrainBytes:251*1024**2,entityBytes:7*1024**2,poiBytes:1*1024**2,otherBytes:1*1024**2,regionFileCount:12}},
];
export const worldTotals = worlds.reduce((total, world) => ({strong:total.strong+world.strong,weak:total.weak+world.weak}),{strong:0,weak:0});
export const population = {online:12,limit:20};
const date = new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false });
export const formatDate = value => date.format(new Date(value));
export function uptime(value) {
  const seconds = Math.max(0, Math.floor(value / 1000));
  return { days: Math.floor(seconds / 86400), hours: String(Math.floor(seconds / 3600) % 24).padStart(2, '0'), minutes: String(Math.floor(seconds / 60) % 60).padStart(2, '0'), seconds: String(seconds % 60).padStart(2, '0') };
}

export function formatPlayTime(milliseconds) {
  if (!Number.isFinite(milliseconds) || milliseconds < 0) return '—';
  const minutes = Math.floor(milliseconds / 60000), hours = Math.floor(minutes / 60);
  return hours ? `${hours}小时${minutes % 60}分钟` : `${minutes}分钟`;
}
