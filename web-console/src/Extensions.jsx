import React, { useState } from 'react';
import { ArrowUpRight, BookBookmark, CaretLeft, CaretRight, Plug, PuzzlePiece } from '@phosphor-icons/react';
import { Status } from './App';
import { useRegisteredExtensions } from './extensionRegistry';
import { extensionPage } from './extensionPagination';

function ExtensionPager({pagination,change,label='扩展'}) {
  return <nav className="extension-pagination" aria-label={label+'分页'}><button className="icon-button" aria-label={'上一页'+label} disabled={pagination.page===0} onClick={()=>change(pagination.page-1)}><CaretLeft size={13}/></button><span className="mono" aria-live="polite">{pagination.page+1} / {pagination.pages}</span><button className="icon-button" aria-label={'下一页'+label} disabled={pagination.page===pagination.pages-1} onClick={()=>change(pagination.page+1)}><CaretRight size={13}/></button></nav>;
}

function ExtensionEntry({extension,open}) {
  return <button className="extension-entry" onClick={()=>open(extension.id)} aria-label={'查看扩展 '+extension.name}>
    <span className="extension-mark"><BookBookmark size={20}/></span>
    <span className="extension-copy"><strong>{extension.name}</strong><small>{extension.summary?.()||extension.description}</small></span>
    <Status tone={extension.visible?'healthy':'muted'}>{extension.visible?'已接入':'入口已隐藏'}</Status><ArrowUpRight size={13}/>
  </button>;
}

export function ExtensionsCard({telemetry:t,preferences,inspect,navigate,extensions:providedExtensions}) {
  const [page,setPage]=useState(0);
  const registered=useRegisteredExtensions(preferences);
  const extensions=providedExtensions??registered,visible=extensions.filter(extension=>extension.visible);
  const pagination=extensionPage(visible,page,3);
  const open=id=>inspect({type:'extension',title:'扩展详情',extensionId:id});
  return <section className="dashboard-card services-card extensions-card">
    <header className="card-heading"><h2><PuzzlePiece size={17}/>插件与扩展</h2><span className="card-tag">{extensions.length} 个已接入</span></header>
    <div className="extension-entries">{pagination.rows.map(extension=><ExtensionEntry key={extension.id} extension={extension} open={open}/>)}{!visible.length&&<div className="extensions-empty"><span>暂无显示的扩展</span><button className="text-link" onClick={()=>navigate('settings')}>前往设置<ArrowUpRight size={12}/></button></div>}</div>
    <footer className="extensions-footer"><span className="extension-bridge"><Plug size={14}/><span>游戏连接</span><Status tone={t.stale?'muted':'healthy'}>{t.stale?'连接中断':'已连接'}</Status></span><ExtensionPager pagination={pagination} change={setPage}/><button className="text-link" onClick={()=>inspect({type:'extensions',title:'插件与扩展'})}>查看全部<ArrowUpRight size={12}/></button></footer>
  </section>;
}

export function ExtensionDirectory({preferences,inspect}) {
  const [page,setPage]=useState(0);
  const extensions=useRegisteredExtensions(preferences),pagination=extensionPage(extensions,page,8);
  return <><p className="detail-intro">已接入 {extensions.length} 个扩展，当前显示 {extensions.filter(e=>e.visible).length} 个。</p><div className="extension-directory">{pagination.rows.map(extension=><ExtensionEntry key={extension.id} extension={extension} open={id=>inspect({type:'extension',title:'扩展详情',extensionId:id})}/>)}</div><div className="extension-directory-footer"><span>共 {extensions.length} 个扩展</span><ExtensionPager pagination={pagination} change={setPage} label="扩展列表"/></div><p className="quiet-note">扩展保留自己的数据与权限，游戏关闭时仍可管理后台资料。</p></>;
}

export function ExtensionDetails({extensionId,telemetry:t,preferences,navigate,inspect}) {
  const extension=useRegisteredExtensions(preferences).find(e=>e.id===extensionId);
  if(!extension)return <p className="detail-intro">此扩展尚未接入当前后台。</p>;
  const summary={titles:extension.titles??0,players:extension.players??0};
  return <>
    <div className="extension-detail-identity"><span className="extension-mark"><BookBookmark size={22}/></span><span><strong>{extension.name}</strong><small>{extension.label}</small></span><Status tone={extension.visible?'healthy':'muted'}>{extension.visible?'已接入':'入口已隐藏'}</Status></div>
    <p className="detail-intro">{extension.description}</p>
    <div className="extension-capabilities" aria-label="扩展能力">{extension.capabilities.map(capability=><span key={capability}>{capability}</span>)}</div>
    <dl className="detail-definition">{extensionId==="memorialtitles"&&<div><dt>称号档案</dt><dd>{summary.titles} 个</dd></div>}{extensionId==="memorialtitles"&&<div><dt>玩家档案</dt><dd>{summary.players} 份</dd></div>}<div><dt>游戏连接</dt><dd><Status tone={t.stale?'muted':'healthy'}>{t.stale?'连接中断':'已连接'}</Status></dd></div></dl>
    {t.stale&&<p className="quiet-note">游戏数据未更新，保留已登记的扩展与历史资料。</p>}
    <section className="extension-dependencies"><h3>数据与备份</h3><dl className="detail-definition"><div><dt>{extensionId==="dailyfortunes"?"抽签数据库":"称号数据库"}</dt><dd>{extension.healthy?'已连接':'暂不可用'}</dd></div><div><dt>数据库类型</dt><dd>MariaDB</dd></div><div><dt>本地备份</dt><dd>{extensionId==="dailyfortunes"?"插件数据页单独管理":"每 12 小时"}</dd></div><div><dt>性能缓存</dt><dd>不进入备份</dd></div><div><dt>最近备份</dt><dd>{extensionId==='dailyfortunes'?'请在抽签设置的数据配置中查看':t.platform.value?.lastBackup?new Date(t.platform.value.lastBackup).toLocaleString():'暂无记录'}</dd></div></dl></section>
    <div className="inline-actions"><button className="button primary" onClick={()=>navigate(extension.visible?extension.page:'settings')}>{extension.visible?'进入扩展':'前往显示设置'}<ArrowUpRight size={13}/></button>{extension.visible&&<button className="button secondary" onClick={()=>navigate(extensionId==='dailyfortunes'?'fortune-settings':'title-settings')}>扩展设置</button>}<button className="text-link" onClick={()=>inspect({type:'extensions',title:'插件与扩展'})}>全部扩展</button></div>
    <p className="quiet-note">界面可用性、扩展数据服务和游戏实时连接是独立状态。</p>
  </>;
}
