import React,{useEffect,useRef,useState} from 'react';
import {ArrowRight,BookBookmark,BookmarkSimple,CalendarDots,CaretDown,ChartLine,Check,ClockCounterClockwise,Cube,Flask,GearSix,Globe,House,List,Moon,Scroll,Sun,Users,X} from '@phosphor-icons/react';
import {TitlesWorkspace} from '../TitlesWorkspace';
import {Dashboard} from '../Dashboard';
import {DetailView, SettingsView} from '../views';
import {registerPreviewExtension} from './mockPlatform';
import '../platform.css';
import {Drawer} from '../App';
import {PlayerDetail} from '../PlayerDetail';
import {ExtensionDirectory,ExtensionDetails} from '../Extensions';
import {useTelemetry} from './mockTelemetry';
import {PlayerAvatar} from './Avatar';
import {FortuneArchive,PlayerCalendars,OperationHistory,FortuneSettings} from './pages';
import {readDemo,STORAGE_KEY} from './fixtures';
import {Modal} from '../titles/ui';
import {PluginSubtitle} from './PluginSubtitle';

const core=[['overview','总览',House],['performance','性能',ChartLine],['players','玩家',Users],['worlds','世界与存档',Globe],['management','服务器管理',GearSix]];
const titleNav=[['titles','称号档案',BookmarkSimple],['title-players','玩家管理',Users],['title-activity','操作记录',ClockCounterClockwise],['title-settings','称号设置',GearSix]];
const fortuneNav=[['fortunes','签文档案',Scroll],['fortune-players','玩家签历',CalendarDots],['fortune-activity','操作记录',ClockCounterClockwise],['fortune-settings','抽签设置',GearSix]];
const names=Object.fromEntries([...core,...titleNav,...fortuneNav,['settings','系统设置']]);
const moduleOf=page=>page==='titles'||page.startsWith('title-')?'MemorialTitles':page==='fortunes'||page.startsWith('fortune-')?'DailyFortunes':'';
const getPage=()=>names[location.hash.slice(2)]?location.hash.slice(2):'fortunes';
const subtitles={fortunes:'整理签诗与解签，配置每一支签的抽取机会。','fortune-players':'按日收藏，查看玩家各自走过的时日。','fortune-activity':'追溯签文、概率与设置的每一次变动。','fortune-settings':'管理每日抽签、遗失补领与展示方式。',titles:'创建、整理与编辑服务器称号。','title-players':'查看玩家收藏，管理称号归属。','title-activity':'查阅称号的发放、撤销与编辑记录。','title-settings':'称号展示、首登奖励与数据备份。'};
const preferencesKey = 'pixelmc-manager-preview-preferences-v1';
function readPreferences() {
  try {
    const saved = JSON.parse(localStorage.getItem(preferencesKey) || '{}');
    const theme = saved?.theme || localStorage.getItem('dailyfortunes-preview-theme');
    return { theme: theme === 'light' ? 'light' : 'dark', titles: saved?.titles !== false, fortunes: saved?.fortunes !== false, hub: saved?.hub !== false, wenzhou: saved?.wenzhou !== false };
  } catch { return { theme: 'dark', titles: true, fortunes: true, hub: true }; }
}
function PreviewSidebar({page,navigate,theme,setTheme,close,extensions,preferences}){
  const extension = extensions.find(plugin => plugin.nav.some(row => row[0] === page));
  const activeModule = extension?.name || moduleOf(page);
  const [openPlugins, setOpenPlugins] = useState(() => ({[extension?.name || extensions[0]?.name]:true}));
  useEffect(() => {
    if (extension) setOpenPlugins(current => ({...current,[extension.name]:true}));
  }, [extension]);
  const [titleOpen,setTitleOpen]=useState(activeModule==='MemorialTitles'),[fortuneOpen,setFortuneOpen]=useState(!extensions.length);
  useEffect(()=>{if(activeModule==='MemorialTitles')setTitleOpen(true);if(activeModule==='DailyFortunes')setFortuneOpen(true);},[activeModule]);
  const item=([id,name,Icon],child=false)=><button key={id} className={'nav-item '+(child?'nav-child ':'')+(page===id?'selected':'')} aria-current={page===id?'page':undefined} onClick={()=>navigate(id)}><Icon size={child?15:18}/><span>{name}</span></button>;
  const group=(label,Icon,open,setOpen,items,module)=><div key={module} className="title-nav-group"><button className={'nav-item module-nav '+(activeModule===module?'module-active':'')} aria-label={label} aria-expanded={open} onClick={()=>setOpen(!open)}><Icon size={18}/><span>{label}</span><CaretDown size={13} className={'group-chevron '+(open?'expanded':'')}/></button>{open&&<div className="title-subnavigation">{items.map(row=>item(row,true))}</div>}</div>;
  return <aside className="sidebar" aria-label="主导航"><a className={`brand ${activeModule?'has-module':''} `} aria-label={activeModule?`PixelMC Manager · ${activeModule}`:"PixelMC Manager"} href="#/overview" onClick={e=>{e.preventDefault();navigate('overview');close();}}><Cube size={23} weight="duotone"/><span className="brand-copy"><span className="brand-name">PixelMC Manager</span><PluginSubtitle name={activeModule}/></span></a><nav>{core.map(row=>item(row))}{preferences.titles&&group('称号管理',BookBookmark,titleOpen,setTitleOpen,titleNav,'MemorialTitles')}{preferences.fortunes&&group('抽签管理',Scroll,fortuneOpen,setFortuneOpen,fortuneNav,'DailyFortunes')}{extensions.filter(plugin => preferences[plugin.preference || 'hub'] !== false).map(plugin =>
    group(plugin.label, plugin.icon, openPlugins[plugin.name], open => setOpenPlugins(current => ({...current,[plugin.name]:open})), plugin.nav, plugin.name))}</nav><div className="sidebar-bottom">{item(['settings','系统设置',GearSix])}<button className="nav-item theme-toggle" aria-label={theme==='dark'?'切换浅色外观':'切换深色外观'} onClick={()=>setTheme(theme==='dark'?'light':'dark')}>{theme==='dark'?<Moon size={18}/>:<Sun size={18}/>}<span>{theme==='dark'?'深色外观':'浅色外观'}</span><span className="theme-keys">切换</span></button><div className="account"><PlayerAvatar player={{id:'df000000-0000-4000-a000-000000000001',name:'本地管理员',head:6}}/><span><strong>本地管理员</strong><small>预览工作空间</small></span></div></div></aside>;
}
const noPlugins = [];
export function PreviewApp({extension:primary, plugins=noPlugins}){
  const extensions = primary ? [primary,...plugins] : plugins;
  const pageNames = {...names,...Object.fromEntries(extensions.flatMap(plugin => plugin.nav))};
  const currentPage=()=>pageNames[location.hash.slice(2)]?location.hash.slice(2):(primary?.defaultPage||'fortunes');
  const telemetry=useTelemetry();
  const platform=telemetry.platform;
  useEffect(()=>{[primary,...plugins].filter(Boolean).forEach(registerPreviewExtension);},[primary,plugins]);
  const [data,setData]=useState(readDemo),[page,setPage]=useState(currentPage),[preferences,setPreferences]=useState(readPreferences);
  const [menu,setMenu]=useState(false),[notice,setNotice]=useState(''),[coreDrawer,setCoreDrawer]=useState(null),[unsaved,setUnsaved]=useState(false),[pending,setPending]=useState('');
  const timer=useRef();
  const toast=message=>{clearTimeout(timer.current);setNotice(message);timer.current=setTimeout(()=>setNotice(''),4500);};
  useEffect(()=>{try{localStorage.setItem(STORAGE_KEY,JSON.stringify(data));}catch{toast('浏览器存储空间不足，本次修改仅保留到页面关闭。');}},[data]);
  useEffect(()=>{try{localStorage.setItem('dailyfortunes-preview-theme',preferences.theme);localStorage.setItem(preferencesKey,JSON.stringify(preferences));}catch{toast('外观偏好仅保留到页面关闭。');}},[preferences]);
  useEffect(()=>{setCoreDrawer(null);},[platform.archive]);
  const routeState = useRef();
  routeState.current = {page,unsaved};
  useEffect(()=>{const change=()=>{const next=currentPage(); if(routeState.current.unsaved && next!==routeState.current.page){history.replaceState(history.state,'','#/'+routeState.current.page);setPending(next);return;}setPage(next);setMenu(false);setCoreDrawer(null);};addEventListener('hashchange',change);if(!location.hash)location.hash='/'+(primary?.defaultPage||'fortunes');return()=>removeEventListener('hashchange',change);},[]);
  useEffect(()=>()=>clearTimeout(timer.current),[]);
  const go=id=>{location.hash='/'+id;setPage(id);setMenu(false);setCoreDrawer(null);};
  const navigate=id=>{if(unsaved&&id!==page){setPending(id);return;}go(id);};
  const inspectionPlugin = extensions.find(plugin => plugin.name.toLowerCase() === coreDrawer?.extensionId);
  const ExtensionInfo = inspectionPlugin?.Details;
  const context={data,setData,toast,setUnsaved};
  const coreContext={telemetry,preferences,setPreferences,navigate,inspect:setCoreDrawer,toast};
  const extension = extensions.find(plugin => plugin.nav.some(row => row[0] === page)) || primary;
  const extensionPage=!!extension?.nav.some(row=>row[0]===page);
  const module=extensionPage?extension.name:moduleOf(page),fortune=module==='DailyFortunes';
  return <div className={'app fortune-preview-app '+(menu?'mobile-menu-open':'')} data-theme={preferences.theme}>
    <PreviewSidebar extensions={extensions} preferences={preferences} page={page} navigate={navigate} theme={preferences.theme} setTheme={theme=>setPreferences(p=>({...p,theme}))} close={()=>setMenu(false)}/>
    {menu&&<button className="sidebar-scrim" aria-label="关闭导航" onClick={()=>setMenu(false)}/>}
    <main className="main-panel"><header className="topbar"><button className="icon-button mobile-menu-button" aria-label="展开导航" onClick={()=>setMenu(!menu)}><List size={20}/></button><span className="breadcrumb">工作空间<ArrowRight size={12}/>{module&&<>{extensionPage?extension.label:module==='DailyFortunes'?'抽签管理':'称号管理'}<ArrowRight size={12}/></>}<span>{pageNames[page]}</span></span><div className="topbar-right"><span className="fp-preview-label"><Flask size={13}/>本地预览 · {module?(page==='hub-rankings'?'玩家排行 · 统计范围见榜单':'全局插件'):platform.archive?'历史档案 · 只读':'模拟数据'}</span><select className="archive-selector" aria-label="查看数据档案" title={module?(page==='hub-rankings'?'排行按各榜的统计范围展示':'插件使用全局数据，请在插件页面内选择整合包'):'切换整合包统计档案'} disabled={Boolean(module)} value={platform.archive} onChange={event=>platform.chooseArchive(event.target.value)}><option value="">当前整合包</option>{platform.value?.archives?.filter(archive=>archive.status!=='active').map(archive=><option key={archive.id} value={archive.id}>{archive.name} {archive.version} · 已归档</option>)}</select>{extension?<a className="ph-view-player" href={extension.playerHref || "/?page=updates"} target="_blank" rel="noreferrer">打开玩家端<ArrowRight size={12}/></a>:<span className="fp-demo-date">2026 / 10 / 26</span>}</div></header>
      <div className="page-intro"><div><div className="title-line"><h1>{pageNames[page]}</h1>{module&&<span className="server-name">{module}</span>}</div><p className="page-description">{extension?.descriptions[page]||subtitles[page]||(page==='overview'?([telemetry.pack?.name||'尚未绑定整合包',telemetry.pack?.version].filter(Boolean).join(' ')+' · 本地模拟工作空间'):'沿用已有 Manager 页面，所有操作仅在本地演示。')}</p></div><span className="fp-local-state"><span/>仅保存于此浏览器</span></div>
      {telemetry.historical&&!module&&<div className="stale-notice" role="status"><span>{telemetry.pack.name} {telemetry.pack.version} · 只读历史快照 · 本地模拟数据</span><button onClick={()=>platform.chooseArchive('')}>返回当前整合包<ArrowRight size={12}/></button></div>}
      {extensionPage?<extension.Component page={page} preferences={preferences} toast={toast} setUnsaved={setUnsaved}/>:fortune?<div className="titles-module fortunes-module" data-theme={preferences.theme}>{page==='fortunes'?<FortuneArchive {...context}/>:page==='fortune-players'?<PlayerCalendars {...context}/>:page==='fortune-activity'?<OperationHistory {...context}/>:<FortuneSettings {...context}/>}</div>:module==='MemorialTitles'?<TitlesWorkspace page={page} {...coreContext} setUnsaved={setUnsaved}/>:page==='overview'?<Dashboard {...coreContext}/>:page==='settings'?<SettingsView {...coreContext}/>:<DetailView page={page} {...coreContext}/>}
    </main>
    {coreDrawer&&<Drawer state={coreDrawer} close={()=>setCoreDrawer(null)}>{coreDrawer.type==='player'?<PlayerDetail player={coreDrawer.player} telemetry={telemetry}/>:coreDrawer.type==='extensions'?<ExtensionDirectory {...coreContext}/>:coreDrawer.type==='extension'?(ExtensionInfo?<ExtensionInfo navigate={navigate}/>:coreDrawer.extensionId==='dailyfortunes'?<><h2>DailyFortunes</h2><p className='detail-intro'>每日一签与永久签历 · 本地预览</p><div className='extension-capabilities'>{['签文档案','玩家签历','操作记录','抽签设置'].map(label=><span key={label}>{label}</span>)}</div><p className='quiet-note'>六十支签文与模拟玩家收藏。游戏和数据库均未连接。</p><button className='button primary' onClick={()=>navigate('fortunes')}>进入抽签管理</button></>:<ExtensionDetails extensionId={coreDrawer.extensionId} {...coreContext}/>):<p>此项仅用于本地页面演示。</p>}</Drawer>}
    {pending&&<div className="titles-module fp-dialog-scope" data-theme={preferences.theme}><Modal title="离开未保存的设置？" subtitle="本地预览" drawer={false} close={()=>setPending('')} footer={<><button className="button secondary" onClick={()=>setPending('')}>继续编辑</button><button className="button primary" onClick={()=>{setUnsaved(false);go(pending);setPending('');}}>放弃并离开</button></>}><div className="editor-body">当前修改尚未保存。</div></Modal></div>}
    {notice&&<div className="toast" role="status"><Check size={16}/><span>{notice}</span><button className="icon-button" aria-label="关闭提示" onClick={()=>setNotice('')}><X size={14}/></button></div>}
  </div>;
}
