import manuscript from './fortunes.json';
import {DEMO_DATE,DEMO_NOW} from './model';
export const STORAGE_KEY='pixelmc-dailyfortunes-prototype-v1';
export function makeDemo(){
  const fortunes=manuscript.fortunes.map(f=>({...f,weight:1,enabled:true,createdAt:Date.parse('2026-10-26T10:00:00+08:00'),revision:1}));
  const names=['zhan_cpu','NANA','Akira','Bamboo','Cedar','Dawn','Echo','Frost','Haru','Iris','Jade','Kai','Leaf','Maple','Mica','Mira','Nova','Olive','Orion','Pine','Rin','Sora','Tori','Willow','Yuki','山眠','青禾','竹间'];
  const profiles=names.map((name,i)=>({id:`df000000-0000-4000-a000-${String(i+1).padStart(12,'0')}`,name,online:i<6,head:[6,8,0,1,12,14][i%6]}));
  const records=[];
  for(const [i,p] of profiles.entries())for(let day=1;day<=56;day++){
    const date=new Date(Date.UTC(2026,8,day)).toISOString().slice(0,10);
    if((day+i*3)%7===0||day<(i%8)+3)continue;
    const id=(day*7+i*11)%60+1;
    const state=date===DEMO_DATE?(i%3===0?'unread':i%3===1?'loose':'archived'):'archived';
    records.push({id:`${p.id}:${date}`,player:p.id,date,fortuneId:id,state,at:Date.parse(date+'T'+String(8+i%12).padStart(2,'0')+':24:00+08:00'),snapshot:structuredClone(fortunes[id-1])});
  }
  const settings={enabled:true,timezone:'Asia/Shanghai',recovery:true,archive:true,hover:true};
  const audit=[
    {id:'seed-1',at:DEMO_NOW-1200000,action:'修改签文',subject:'第 01 签 · 灯认归人',actor:'管理员',summary:'更新签诗与解签',seed:true,changes:[{field:'title',label:'题名',before:'归人',after:'灯认归人'}]},
    {id:'seed-2',at:DEMO_NOW-3600000,action:'补领签笺',subject:'NANA',actor:'玩家自助',summary:'补领 2026/10/25 原签，结果未变',seed:true,changes:[]},
    {id:'seed-3',at:DEMO_NOW-86400000,action:'修改设置',subject:'每日刷新时区',actor:'管理员',summary:'使用北京时间，每日 0 点刷新',seed:true,changes:[{field:'timezone',label:'刷新时区',before:'UTC',after:'Asia/Shanghai'}]},
    {id:'seed-4',at:DEMO_NOW-90000000,action:'调整概率',subject:'全部签文',actor:'管理员',summary:'六十签等概率，每签权重为 1',seed:true,changes:[]},
  ];
  return {schema:1,fortunes,profiles,records,settings,audit};
}
export function readDemo(){try{const saved=JSON.parse(localStorage.getItem(STORAGE_KEY));if(saved?.schema===1&&Array.isArray(saved.fortunes)&&saved.fortunes.length&&Array.isArray(saved.profiles)&&Array.isArray(saved.records)&&Array.isArray(saved.audit)&&saved.settings)return saved;}catch{}return makeDemo();}
export function profileStats(p,records){const rows=records.filter(r=>r.player===p.id);return {...p,total:rows.length,archived:rows.filter(r=>r.state==='archived').length,unread:rows.filter(r=>r.state==='unread').length,latest:rows.at(-1)?.date||'',latestAt:rows.length?Math.max(...rows.map(r=>r.at)):null};}
