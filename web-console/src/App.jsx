import React, { useEffect, useRef, useState } from 'react';
import { ArrowCounterClockwise, ArrowRight, ArrowUpRight, BookBookmark, CaretDown, ChartLine, Check, Clock, Cube, Database, GearSix, Globe, House, Info, Lightning, List, Memory, Moon, Pause, Play, Robot, Sun, Users, WarningCircle, X } from '@phosphor-icons/react';
import { Trend } from './Trend';
import { formatDate, formatPlayTime, uptime, useTelemetry } from './telemetry';
import { DetailView } from './views';
import { Sidebar } from './Sidebar';
import { Dashboard } from './Dashboard';
import { TitlesWorkspace } from './TitlesWorkspace';
import { FortuneWorkspace } from './FortuneWorkspace';
import { PlayerDetail } from './PlayerDetail';
import { ExtensionDirectory, ExtensionDetails } from './Extensions.jsx';
import { latencyTone } from './trendData';

const navigation = [
  { id: 'overview', name: '总览', icon: House },
  { id: 'performance', name: '性能', icon: ChartLine },
  { id: 'players', name: '玩家', icon: Users },
  { id: 'worlds', name: '世界与存档', icon: Globe },
  { id: 'management', name: '服务器管理', icon: SlidersIcon },
  { id: 'titles', name: '称号管理', icon: BookBookmark },
];
function SlidersIcon(props) { return <GearSix {...props}/>; }
const names = Object.fromEntries([...navigation, {id:'titles',name:'称号档案'}, {id:'title-players',name:'称号 · 玩家管理'}, {id:'title-activity',name:'称号 · 操作记录'}, {id:'title-settings',name:'称号设置'}, {id:'fortunes',name:'签文档案'}, {id:'fortune-players',name:'玩家签历'}, {id:'fortune-activity',name:'操作记录'}, {id:'fortune-settings',name:'抽签设置'}, { id: 'settings', name: '系统设置' }].map(p => [p.id, p.name]));
const getPage = () => Object.hasOwn(names, location.hash.slice(2)) ? location.hash.slice(2) : 'overview';
function readPreferences() { try { return { theme: 'dark', titles: true, fortunes:true, ...JSON.parse(localStorage.getItem('pixelmc-preview-preferences') || '{}') }; } catch { return { theme: 'dark', titles: true, fortunes:true }; } }

export function Status({ tone = 'healthy', children, className = '' }) { return <span className={`status ${tone} ${className}`}><span className="status-dot" aria-hidden="true"/>{children}</span>; }
export function Hint({ title, children }) {
  const [open, setOpen] = useState(false);
  return <span className="hint"><button className="hint-button" aria-label={'关于'+title} aria-expanded={open} onClick={() => setOpen(v => !v)} onBlur={() => setOpen(false)}><Info size={14}/></button><span role="tooltip" className={open ? 'hint-text is-open' : 'hint-text'}>{children}</span></span>;
}
export function Toggle({ label, description, checked, change, disabled = false }) { return <div className="setting-row"><div><strong>{label}</strong>{description && <p>{description}</p>}</div><button type="button" className="toggle" role="switch" aria-label={label} aria-checked={checked} disabled={disabled} onClick={() => change(!checked)}><span/></button></div>; }

function ScenarioMenu({ telemetry }) {
  const [open, setOpen] = useState(false), ref = useRef(null);
  useEffect(() => {
    if (!open) return;
    const outside = event => { if (!ref.current?.contains(event.target)) setOpen(false); };
    const key = event => { if (event.key === 'Escape') { setOpen(false); event.stopPropagation(); } };
    document.addEventListener('pointerdown', outside); document.addEventListener('keydown', key);
    ref.current?.querySelector('[role=menuitemradio]')?.focus();
    return () => { document.removeEventListener('pointerdown', outside); document.removeEventListener('keydown', key); };
  }, [open]);
  const options = [{ id: 'normal', icon: Play, name: '正常运行', note: '持续更新实时数据' }, { id: 'lag', icon: Lightning, name: '模拟短时卡顿', note: '8秒后自动恢复' }, { id: 'offline', icon: Pause, name: '模拟数据中断', note: '查看数据过期的状态' }, {id:'restart',icon:ArrowCounterClockwise,name:'模拟重新开服',note:'清空本地性能历史，不操作服务器'}, {id:'memory',icon:Memory,name:'模拟堆内存压力',note:'只展示风险状态，不分配额外内存'}, {id:'risk',icon:ChartLine,name:'模拟性能风险',note:'持续约44ms，仅演示黄色状态'}, {id:'load',icon:Lightning,name:'模拟持续掉刻',note:'约10秒后展示性能不足'}];
  return <div className="scenario-anchor" ref={ref}><button className="preview-control" aria-label="切换演示场景" aria-haspopup="menu" aria-expanded={open} onClick={() => setOpen(v => !v)}><span className="preview-label">模拟数据</span><CaretDown size={12}/></button>{open && <div className="scenario-menu" role="menu" aria-label="演示场景"><div className="menu-caption">仅影响本地实例</div>{options.map(option => <button key={option.id} role="menuitemradio" aria-checked={telemetry.mode === option.id} onClick={() => { telemetry.setMode(option.id); setOpen(false); }}><option.icon size={16}/><span><strong>{option.name}</strong><small>{option.note}</small></span>{telemetry.mode === option.id && <Check size={14}/>}</button>)}</div>}</div>;
}

export function Drawer({ state, close, children, wide = false }) {
  const ref = useRef(null);
  useEffect(() => {
    const previous = document.activeElement;
    ref.current?.querySelector('button')?.focus();
    const onKey = event => {
      if (event.key === 'Escape') close();
      if (event.key === 'Tab') {
        const items = [...(ref.current?.querySelectorAll('button:not(:disabled),a[href],input,textarea,[contenteditable="true"],[tabindex="0"]') || [])].filter(item=>item.getClientRects().length);
        if (!items.length) return;
        if (event.shiftKey && document.activeElement === items[0]) { event.preventDefault(); items.at(-1).focus(); }
        else if (!event.shiftKey && document.activeElement === items.at(-1)) { event.preventDefault(); items[0].focus(); }
      }
    };
    document.addEventListener('keydown', onKey);
    return () => { document.removeEventListener('keydown', onKey); previous?.focus(); };
  }, [state.type,state.extensionId]);
  return <div className="drawer-overlay" onMouseDown={e => { if (e.target === e.currentTarget) close(); }}><section ref={ref} className={'drawer'+(wide?' message-settings-drawer':'')} role="dialog" aria-modal="true" aria-labelledby="drawer-title"><header><div><span className="eyebrow">详情</span><h2 id="drawer-title">{state.title}</h2></div><button className="icon-button" aria-label="关闭详情" onClick={close}><X size={19}/></button></header><div className="drawer-content">{children}</div><footer><button className="button secondary" onClick={close}>关闭</button></footer></section></div>;
}

function DrawerContents({ state, telemetry: t, preferences, navigate, inspect }) {
  if(state.type==='extensions')return <ExtensionDirectory preferences={preferences} inspect={inspect}/>;
  if(state.type==='extension')return <ExtensionDetails extensionId={state.extensionId} telemetry={t} preferences={preferences} navigate={navigate} inspect={inspect}/>;
  if (state.type === 'player') return <PlayerDetail player={state.player} telemetry={t}/>;
  if (state.type === 'title') return <><div className="title-detail-name">{state.item.name}</div><p className="detail-intro">{state.item.description}</p><dl className="detail-definition"><div><dt>持有者</dt><dd>{state.item.holders} 人</dd></div><div><dt>来源</dt><dd>香草纪元 2.7.1</dd></div><div><dt>图鉴可见性</dt><dd>公开</dd></div></dl><p className="quiet-note">独立称号模块的只读接入示例。</p></>;
  return null;
}

export function App() {
  const telemetry = useTelemetry();
  const platform=telemetry.platform;
  const [preferences, setPreferences] = useState(readPreferences);
  const [page, setPage] = useState(getPage), [mobileMenu, setMobileMenu] = useState(false), [drawer, setDrawer] = useState(null), [notice, setNotice] = useState('');
  const toastTimer = useRef(null);
  const [titleDirty,setTitleDirty]=useState(false),[pendingPage,setPendingPage]=useState(null);
  const titlePage=page==='titles'||page.startsWith('title-');
  const fortunePage=page==='fortunes'||page.startsWith('fortune-');
  useEffect(() => { localStorage.setItem('pixelmc-preview-preferences', JSON.stringify(preferences)); }, [preferences]);
  useEffect(() => { const change = () => { const next=getPage();if(titleDirty&&next!==page){history.replaceState(null,'','#/'+page);setPendingPage(next);return;}setPage(next);setMobileMenu(false);setDrawer(null); }; window.addEventListener('hashchange', change); return () => window.removeEventListener('hashchange', change); }, [titleDirty,page]);
  useEffect(() => { if (titlePage && !preferences.titles) { location.hash = '/overview'; setPage('overview'); } }, [preferences.titles, page]);
  useEffect(() => { if (fortunePage && !preferences.fortunes) { location.hash='/overview';setPage('overview'); } },[preferences.fortunes,page]);
  useEffect(() => () => clearTimeout(toastTimer.current), []);
  const doNavigate = id => { location.hash = '/'+id; setPage(id); setMobileMenu(false); setDrawer(null); };
  const navigate = id => { if(titleDirty && id!==page){setPendingPage(id);return;} doNavigate(id); };
  const toast = text => { clearTimeout(toastTimer.current); setNotice(text); toastTimer.current = setTimeout(() => setNotice(''), 4500); };
  const view = titlePage && !preferences.titles ? 'overview' : page;
  const visiblePreferences={...preferences,titles:preferences.titles&&!!platform.value?.extensions?.some(e=>e.id==='memorialtitles'),fortunes:preferences.fortunes&&!!platform.value?.extensions?.some(e=>e.id==='dailyfortunes')};
  const context = { telemetry, preferences:visiblePreferences, setPreferences, navigate, inspect: setDrawer, toast };
  const subtitle = { performance: '从 Tick 到内存，查看运行细节。', players: '当前在线玩家与服务器登记档案。', worlds: '维度、区块与存档概况。', management: '关服预约、维护、重启与聊天公告。', titles: '创建、整理与编辑服务器称号。', 'title-players':'查看玩家收藏，管理称号归属。', 'title-activity':'查阅称号的发放、撤销与编辑记录。', 'title-settings':'称号展示、首登奖励与数据备份。', fortunes:'整理签诗与解签，配置每一支签的抽取机会。', 'fortune-players':'按日收藏，查看玩家各自走过的时日。', 'fortune-activity':'追溯签文、概率与设置的每一次变动。', 'fortune-settings':'管理每日抽签、遗失补领与展示方式。', settings: '界面偏好、整合包档案与数据维护。' };
  return <div className={`app integrated-plugins ${mobileMenu ? 'mobile-menu-open' : ''}`} data-theme={preferences.theme}>
    <Sidebar page={view} navigate={navigate} preferences={visiblePreferences} setPreferences={setPreferences} closeMenu={() => setMobileMenu(false)}/>
    {mobileMenu && <button className="sidebar-scrim" aria-label="关闭导航" onClick={() => setMobileMenu(false)}/>}
    <main className="main-panel"><header className="topbar"><button className="icon-button mobile-menu-button" aria-label="展开导航" onClick={() => setMobileMenu(v => !v)}><List size={20}/></button><span className="breadcrumb">工作空间<ArrowRight size={12}/><span>{names[view]}</span></span><div className="topbar-right"><span className="local-preview">{platform.archive?'只读档案':'独立后台'}</span><select aria-label="查看数据档案" className="archive-selector" value={platform.archive} onChange={e=>platform.chooseArchive(e.target.value)}><option value="">当前整合包</option>{platform.value?.archives?.filter(a=>a.status!=='active').map(a=><option key={a.id} value={a.id}>{a.name} {a.version} · {a.status==='archived'?'已归档':'待导入'}</option>)}</select><button className="button secondary" onClick={platform.logout}>退出</button></div></header>
      <div className="page-intro"><div><div className="title-line"><h1>{names[view]}</h1>{(titlePage||fortunePage) && <span className="server-name">{fortunePage?"DailyFortunes":"MemorialTitles"}</span>}{view === 'overview' && <span className="server-name">{telemetry.pack?.name||'尚未绑定整合包'} {telemetry.pack?.version||''}</span>}</div>{view !== 'overview' && <p className="page-description">{subtitle[view]}</p>}</div><div className="live-status"><Status tone={telemetry.stale ? 'offline' : telemetry.mode === 'lag' ? 'warning' : 'healthy'}>{!telemetry.runId&&!telemetry.historical?'等待接入':telemetry.historical?'历史档案':telemetry.stopped?'游戏已停止':telemetry.stale?'数据中断':'运行中'}</Status><span className="freshness">{!telemetry.runId&&!telemetry.historical?'下次游戏服重启后连接':telemetry.historical?'只读':telemetry.stopped?'后台独立运行':telemetry.stale?`${telemetry.age} 秒未更新`:'实时更新'}</span></div></div>
      {telemetry.stale && <div className="stale-notice" role="status"><WarningCircle size={16}/><span>{!telemetry.runId&&!telemetry.historical?'网页已运行，等待游戏桥接在下次重启时接入。':telemetry.historical?'正在查看归档数据，游戏操作不可用。':telemetry.stopped?'游戏已停止，网页与称号数据库继续运行。':platform.error||'游戏未连接，实时数值暂不可用。'}</span><button onClick={platform.refresh}>重新连接<ArrowRight size={12}/></button></div>}
      {(telemetry.lostFrames>0||telemetry.totals?.counterWarnings>0)&&<div className="stale-notice" role="status"><WarningCircle size={16}/><span>{telemetry.lostFrames>0?'采集缓冲曾溢出，部分性能样本缺失；游戏继续运行。':'检测到统计回档，后台保留较高累计值。请核对世界检查点，未自动叠加或覆盖数据。'}</span></div>}
      {view === 'overview' ? <Dashboard {...context}/> : titlePage && visiblePreferences.titles ? <TitlesWorkspace page={view} {...context} setUnsaved={setTitleDirty}/> : fortunePage && visiblePreferences.fortunes ? <FortuneWorkspace page={view} {...context} setUnsaved={setTitleDirty}/> : (titlePage||fortunePage) ? <p className="quiet-note">该扩展未启用。请在插件与扩展中查看注册状态。</p> : <DetailView page={view} {...context}/>}
    </main>
    {drawer && <Drawer state={drawer} close={() => setDrawer(null)}><DrawerContents state={drawer} telemetry={telemetry} preferences={preferences} navigate={navigate} inspect={setDrawer}/></Drawer>}
    {pendingPage && <Drawer state={{title:'放弃未保存的插件设置？'}} close={()=>setPendingPage(null)}><p className="detail-intro">当前修改尚未保存，离开后将丢弃这些修改。</p><div className="inline-actions"><button className="button secondary" onClick={()=>setPendingPage(null)}>继续编辑</button><button className="button primary" onClick={()=>{setTitleDirty(false);doNavigate(pendingPage);setPendingPage(null);}}>放弃并离开</button></div></Drawer>}
    {notice && <div className="toast" role="status"><Check size={16}/><span>{notice}</span><button className="icon-button" aria-label="关闭提示" onClick={() => setNotice('')}><X size={14}/></button></div>}
  </div>;
}
