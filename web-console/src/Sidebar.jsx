import React, {useEffect,useRef,useState} from 'react';
import {BookBookmark, BookmarkSimple, CaretDown, ChartLine, ClockCounterClockwise, Cube, GearSix, Globe, House, Moon, Sun, Users} from '@phosphor-icons/react';
import {PlayerAvatar} from './PlayerAvatar';
import {usePlatform} from './Platform';
const primary=[['overview','总览',House],['performance','性能',ChartLine],['players','玩家',Users],['worlds','世界与存档',Globe],['management','服务器管理',GearSix]];
const children=[['titles','称号档案',BookmarkSimple],['title-players','玩家管理',Users],['title-activity','操作记录',ClockCounterClockwise],['title-settings','称号设置',GearSix]];
export function Sidebar({page,navigate,preferences,setPreferences,closeMenu}) {
  const platform=usePlatform();
  const isTitle=page==='titles'||page.startsWith('title-');
  const [expanded,setExpanded]=useState(true);
  const previousModule=useRef(isTitle);
  const [brandMotion,setBrandMotion]=useState('');
  useEffect(()=>{if(previousModule.current!==isTitle){previousModule.current=isTitle;setBrandMotion(isTitle?'module-enter':'module-exit');}},[isTitle]);
  useEffect(()=>{if(isTitle)setExpanded(true);},[page]);
  const item=([id,name,Icon],child=false)=><button key={id} className={`nav-item ${child?'nav-child':''} ${page===id?'selected':''}`} aria-label={name} aria-current={page===id?'page':undefined} onClick={()=>navigate(id)}><Icon size={child?15:18}/><span>{name}</span></button>;
  return <aside className="sidebar" aria-label="主导航"><a className={`brand ${isTitle?'has-module':''} ${brandMotion}`} href="#/overview" onClick={closeMenu} aria-label={isTitle?'PixelMC Manager · MemorialTitles':'PixelMC Manager'}><Cube size={23} weight="duotone"/><span className="brand-copy"><span className="brand-name">PixelMC Manager</span><span className="brand-module" data-label="MemorialTitles" aria-hidden={!isTitle}>MemorialTitles</span></span></a><nav>{primary.map(row=>item(row))}{preferences.titles&&<div className="title-nav-group"><button className={`nav-item module-nav ${isTitle?'module-active':''}`} aria-label="称号管理" aria-expanded={expanded} aria-controls="title-subnavigation" onClick={()=>setExpanded(value=>!value)}><BookBookmark size={18}/><span>称号管理</span><CaretDown size={13} className={`group-chevron ${expanded?'expanded':''}`}/></button>{expanded&&<div className="title-subnavigation" id="title-subnavigation">{children.map(row=>item(row,true))}</div>}</div>}</nav><div className="sidebar-bottom">{item(['settings','系统设置',GearSix])}<button className="nav-item theme-toggle" aria-label={preferences.theme==='dark'?'切换浅色外观':'切换深色外观'} onClick={()=>setPreferences(p=>({...p,theme:p.theme==='dark'?'light':'dark'}))}>{preferences.theme==='dark'?<Moon size={18}/>:<Sun size={18}/>}<span>{preferences.theme==='dark'?'深色外观':'浅色外观'}</span><span className="theme-keys">切换</span></button><button className="account" onClick={()=>navigate('settings')} aria-label="管理员账户与设置"><PlayerAvatar player={{id:platform.session.player,name:platform.session.username}}/><span><strong>{platform.session.username}</strong><small>管理员</small></span><CaretDown size={12}/></button></div></aside>;
}
