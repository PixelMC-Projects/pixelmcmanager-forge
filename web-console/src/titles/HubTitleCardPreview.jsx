/* Adapted from PixelMCPlayerHub src/App.jsx title-grid and src/glass-theme.css.
 * Copyright 2026 zhan_cpu / PixelMC Projects, Apache-2.0 (see public/licenses).
 * Uses original application markup/material rules. No React Bits vendor implementation is used.
 * Lucide icon nodes retain ISC; see public/licenses/Lucide-LICENSE.txt.
 */
import React, { useLayoutEffect, useRef, useState } from 'react';
import { hex, RichName, textOf } from './api';
import presets from './titlePresentationPresets.json';
import iconPaths from './hubTitleIconPaths.json';
import './hubTitleCardPreview.css';

const WIDTH = 323, HEIGHT = 158, TITLE_WIDTH = WIDTH - 111;
function Symbol({ name, size = 12, ...props }) {
  return <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" {...props}>{(iconPaths[name] || iconPaths.leaf).map(([tag, attributes], index) => React.createElement(tag, { ...attributes, key:index }))}</svg>;
}
export function HubTitleCardPreview({ title }) {
  const value = { badge:'leaf', rarity:'none', theme:'slate', ...title.presentation };
  const theme = presets.themes.find(item => item.id === value.theme) || presets.themes.at(-1);
  const rarity = presets.rarities.find(item => item.id === value.rarity) || presets.rarities[0];
  const host = useRef(null), label = useRef(null), card = useRef(null);
  const [scale, setScale] = useState(1), [fontSize, setFontSize] = useState(20);
  useLayoutEffect(() => {
    const resize = () => setScale(Math.min(1, Math.max(.3,(host.current.clientWidth - 24)/WIDTH)));
    const observer = new ResizeObserver(resize); observer.observe(host.current); resize();
    return () => observer.disconnect();
  }, []);
  useLayoutEffect(() => {
    // Same native rule: fit from20px down to11px, then clip with an ellipsis.
    const node = label.current; node.style.fontSize = '20px';
    const next = Math.max(11, Math.min(20, 20 * TITLE_WIDTH / Math.max(TITLE_WIDTH,node.scrollWidth)));
    node.style.fontSize = `${next}px`; setFontSize(next);
  }, [JSON.stringify(title.name)]);
  const reset = () => { card.current.style.transform = ''; };
  const tilt = event => {
    if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) return;
    const bounds = event.currentTarget.getBoundingClientRect();
    const x = (event.clientX - bounds.left)/bounds.width - .5, y = (event.clientY - bounds.top)/bounds.height - .5;
    card.current.style.transform = `perspective(800px) rotateX(${-y*10}deg) rotateY(${x*10}deg) scale(1.018)`;
  };
  return <div className="hub-title-preview">
    <div className="hub-title-card-stage" ref={host} style={{height:HEIGHT*scale+32}}>
      <div className="hub-title-card-scale" style={{width:WIDTH,height:HEIGHT,transform:`scale(${scale})`}}>
        <div className="hub-title-card title-tile selected" ref={card} role="img" aria-label={`${textOf(title) || '玩家称号'}，玩家称号卡片预览：已获得、已佩戴、已选中（示例状态）`} onPointerMove={tilt} onPointerLeave={reset} style={{'--tile':hex(theme.accent),'--tile-light':hex(theme.light),'--rarity':hex(rarity.color)}}>
          <div className="hub-title-card-front">
            <span className="title-emblem" aria-hidden="true"><span className="emblem-back"/><span className="emblem-front"><Symbol name={value.badge==='personal'?'user-round':value.badge} size={33}/></span></span>
            <span className="tile-copy"><span className="tile-category">{title.category || '纪念称号'}</span><span className="hub-title-rich" ref={label} title={textOf(title)} style={{fontSize,width:TITLE_WIDTH}}><RichName value={title.name}/></span>{rarity.label && <span className="tile-rarity">{rarity.label}</span>}</span>
            <span className="equipped-tag"><Symbol name="check" size={12}/>已佩戴</span>
            <span className="tile-footer"><span className="tile-date"><Symbol name="calendar-days" size={12}/>2026/11/04</span><span className="tile-quality"><Symbol name="check" size={12}/>已选中</span></span>
          </div>
        </div>
      </div>
    </div>
    <p className="field-help hub-title-preview-sample">预览状态为示例：已获得、已佩戴、已选中；获得日期为 2026/11/04。</p>
  </div>;
}
