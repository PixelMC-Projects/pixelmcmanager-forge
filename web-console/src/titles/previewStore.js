import { playerExamples } from '../telemetry';
const copy=value=>structuredClone(value), now=Date.now(), day=86400000;
const descriptions=['纪念共同走过的第一段旅程。','从这里出发，前往下一个故事。','献给把想象变为方块的建造者。','一次值得收藏的相遇。','献给走遍山海的旅人。','谢谢你让这里成为家。'];
const names=['山海同行者·第一季','启程之约','匠心独运','不期而遇','远行的旅人','小镇筑梦师'];
let titles=names.map((name,i)=>({id:'preview_title_'+i,name:{segments:[{text:name,color:[0x96cbbb,0xceb58c,0xb5a3d4,0xc0c1c6,0x91b4d3,0xd1abbd][i],bold:false,italic:false,underline:false,strike:false}],gradients:[]},description:descriptions[i],category:i===2||i===5?'建造':'纪念',source:'香草纪元 2.7.1',acquisition:'',order:0,icon:i,visible:i!==3,issuable:i!==0,revision:1,createdAt:now-(30-i*3)*day}));
const extraNames=['Akira','Bamboo','Cedar','Dawn','Echo','Frost','Haru','Iris','Jade','Kai','Leaf','Mica','Nova','Olive','Pine','Rin','Sora','Tori'];
let profiles=[...playerExamples,...extraNames.map((name,i)=>({id:`00000000-0000-4000-a000-${String(i+1).padStart(12,'0')}`,name,head:[0,1,6,8,12,14][i%6]}))].map((p,i)=>({...p,online:i<12,joinedAt:now-(35-i)*day,lastSeen:now-i*7200000,avatarVersion:1,equipped:i%3===0?titles[i%6].id:'',owned:[{title:titles[i%6].id,acquiredAt:now-(20-i%16)*day,publicCollection:i%2===0,publicDisplay:true}],receipts:[]}));
let settings={head:true,chat:true,tab:true,headScale:1,headOffset:.3,chatPrefix:'[{title}] ',retentionDays:30,packName:'香草纪元 2.7.1',packStartedOn:'2026-09-26',reward:titles[1].id};
let history=Array.from({length:12},(_,i)=>({id:i+1,action:['GRANT','TITLE_SAVE','SETTINGS','REVOKE'][i%4],target:i%4===0?profiles[i%24].id:'',title:i%4!==2?titles[i%6].id:'',actor:'console',at:now-i*3600000,reason:['开档纪念称号发放','调整称号说明','更新展示设置','修正误发称号'][i%4]}));
let backups=[{file:'preview-20261024-120000.json',modified:now-7200000,bytes:18720,checksum:true}];
function log(action,title='',target='',reason='本地实例操作'){history.unshift({id:Date.now(),action,title,target,reason,actor:'console',at:Date.now()});}
function page(rows,search){const p=Math.max(0,Number(search.get('page')||0)),size=Number(search.get('size')||20);return {rows:copy(rows.slice(p*size,p*size+size)),page:p,size,total:rows.length};}
const byName=(a,b)=>a.name.localeCompare(b.name,'zh-CN-u-co-pinyin',{sensitivity:'base'});
const holders=id=>profiles.filter(p=>p.owned.some(o=>o.title===id));
export function titleSnapshot(){return copy({titles,settings,health:{database:true,version:'0.3.6 · 本地示例',players:profiles.length,lastSuccess:Date.now()},identities:Object.fromEntries(profiles.map(p=>[p.id,p.name])),holderCounts:Object.fromEntries(titles.map(t=>[t.id,holders(t.id).length]))});}
export function titleSummary(){return {titles:titles.length,players:profiles.length};}
export async function previewApi(path,body,options={}){
  if(options.signal?.aborted)throw new DOMException('Aborted','AbortError');
  const url=new URL(path,'http://local-preview'),route=url.pathname,q=url.searchParams;
  if(route==='/state')return titleSnapshot();
  if(route==='/players')return page(profiles.filter(p=>(q.get('online')!=='true'||p.online)&&`${p.name} ${p.id}`.toLowerCase().includes((q.get('q')||'').toLowerCase())).sort((a,b)=>Number(b.online)-Number(a.online)||byName(a,b)).map(p=>({...p,ownedCount:p.owned.length})),q);
  if(route.startsWith('/players/')){const p=profiles.find(p=>p.id===route.split('/').at(-1));if(!p)throw new Error('玩家资料不存在。');return copy({profile:p,owned:p.owned,receipts:p.receipts,online:p.online,equipped:p.equipped,avatarVersion:1});}
  if(route==='/title-holders')return page(holders(q.get('title')).sort(byName).map(p=>({...p,acquiredAt:p.owned.find(o=>o.title===q.get('title')).acquiredAt})),q);
  if(route==='/titles'){
    const old=titles.find(t=>t.id===body.title.id);
    if(old&&old.revision!==body.expectedRevision)throw new Error('这条称号已变动，请重新打开后编辑。');
    const title=copy({...body.title,id:old?.id||'preview_title_'+Date.now(),createdAt:old?.createdAt||Date.now(),revision:(old?.revision||0)+1});
    titles=old?titles.map(t=>t.id===old.id?title:t):[...titles,title]; log('TITLE_SAVE',title.id);return {title:copy(title)};
  }
  if(route==='/settings'){if(JSON.stringify(settings)!==JSON.stringify(body.expected))throw new Error('设置已发生变化，请重新载入。');settings=copy(body.settings);log('SETTINGS');return {settings:copy(settings)};}
  if(route==='/grants'){
    if(!titles.some(t=>t.id===body.title))throw new Error('称号不存在。');let count=0;
    for(const id of body.players){const p=profiles.find(p=>p.id===id);if(!p)continue;const owned=p.owned.some(o=>o.title===body.title);
      if(body.mode==='grant'&&!owned){p.owned.push({title:body.title,acquiredAt:Date.now(),publicCollection:false,publicDisplay:true});count++;}
      if(body.mode==='revoke'&&owned){p.owned=p.owned.filter(o=>o.title!==body.title);if(p.equipped===body.title)p.equipped='';count++;}
      log(body.mode==='grant'?'GRANT':'REVOKE',body.title,id,body.reason);
    }return {count};
  }
  if(route==='/title-delete/preview'){const title=titles.find(t=>t.id===body.title);if(!title)throw new Error('称号不存在。');return copy({name:title.name,stamp:title.revision,holders:holders(title.id).length,equipped:profiles.filter(p=>p.equipped===title.id).length,receipts:0,reward:settings.reward===title.id});}
  if(route==='/title-delete/confirm'){const title=titles.find(t=>t.id===body.title);if(!title||title.revision!==body.stamp||title.name.segments.map(s=>s.text).join('')!==body.confirmName)throw new Error('称号或确认文字已变动，请重新核对。');const count=holders(title.id).length;profiles.forEach(p=>{p.owned=p.owned.filter(o=>o.title!==title.id);if(p.equipped===title.id)p.equipped='';});titles=titles.filter(t=>t.id!==title.id);if(settings.reward===title.id)settings.reward='';log('TITLE_DELETE','', '',`本地示例删除「${body.confirmName}」`);return {removedHolders:count};}
  if(route==='/history')return page(history.filter(h=>(!q.get('player')||h.target===q.get('player'))&&(!q.get('action')||q.get('action').split(',').includes(h.action))&&JSON.stringify(h).toLowerCase().includes((q.get('q')||'').toLowerCase())).sort((a,b)=>b.at-a.at),q);
  if(/^\/history\/[^/]+\/changes$/.test(route))return {rows:[]};
  if(route==='/backups')return copy(backups);
  if(route==='/backup'){const file='preview-'+Date.now()+'.json';backups.unshift({file,modified:Date.now(),bytes:18720,checksum:true});return {file};}
  if(route==='/backup-preview')return {createdAt:now,titles:titles.length,players:profiles.length,owned:profiles.reduce((n,p)=>n+p.owned.length,0),audit:history.length,admins:0,conflicts:0};
  if(route==='/definitions'||route.startsWith('/backups/'))return new Response(JSON.stringify({preview:true,titles,settings},null,2),{headers:{'Content-Type':'application/json'}});
  throw new Error('此操作尚未接入本地实例；不会向服务器发送请求。');
}
