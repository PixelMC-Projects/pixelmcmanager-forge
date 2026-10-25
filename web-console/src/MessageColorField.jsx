import React, { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { X } from '@phosphor-icons/react';

const clamp=value=>Math.max(0,Math.min(1,value));
const valid=value=>/^#[a-f0-9]{6}$/i.test(value);
function toHsv(hex) {
  const color=parseInt(hex.slice(1),16),r=((color>>16)&255)/255,g=((color>>8)&255)/255,b=(color&255)/255,max=Math.max(r,g,b),delta=max-Math.min(r,g,b);
  const h=!delta?0:max===r?((g-b)/delta)%6:max===g?(b-r)/delta+2:(r-g)/delta+4;
  return {h:(h*60+360)%360,s:max?delta/max:0,v:max};
}
function fromHsv({h,s,v}) {
  const c=v*s,x=c*(1-Math.abs((h/60)%2-1)),m=v-c,values=h<60?[c,x,0]:h<120?[x,c,0]:h<180?[0,c,x]:h<240?[0,x,c]:h<300?[x,0,c]:[c,0,x];
  return '#'+values.map(value=>Math.round((value+m)*255).toString(16).padStart(2,'0')).join('');
}
export function MessageColorField({label,value,onChange,open,onOpen,onClose,onStart=()=>{},onEnd=()=>{}}) {
  const anchor=useRef(null),panel=useRef(null),[position,setPosition]=useState({}),[input,setInput]=useState(value),[hsv,setHsv]=useState(()=>toHsv(value));
  const latest=useRef({onClose,onEnd});latest.current={onClose,onEnd};
  useEffect(()=>{setInput(value);if(valid(value))setHsv(previous=>{const next=toHsv(value);return {...next,h:next.s===0?previous.h:next.h};});},[value]);
  useLayoutEffect(()=>{
    if(!open)return;
    const place=()=>{const rect=anchor.current.getBoundingClientRect(),width=Math.min(272,innerWidth-24),height=panel.current?.offsetHeight||365;setPosition({width,left:Math.max(12,Math.min(rect.left,innerWidth-width-12)),top:rect.bottom+height+10<innerHeight?rect.bottom+7:Math.max(12,rect.top-height-7),maxHeight:innerHeight-24});};
    const close=()=>{latest.current.onEnd();latest.current.onClose();};
    const outside=event=>{if(!anchor.current?.contains(event.target)&&!panel.current?.contains(event.target))close();};
    const escape=event=>{if(event.key==='Escape'){event.preventDefault();event.stopImmediatePropagation();close();anchor.current.querySelector('button')?.focus();}};
    place();document.addEventListener('pointerdown',outside,true);document.addEventListener('keydown',escape,true);window.addEventListener('resize',place);window.addEventListener('scroll',place,true);
    return()=>{document.removeEventListener('pointerdown',outside,true);document.removeEventListener('keydown',escape,true);window.removeEventListener('resize',place);window.removeEventListener('scroll',place,true);};
  },[open]);
  const update=patch=>{const next={...hsv,...patch};setHsv(next);onChange(fromHsv(next));};
  const plane=event=>{const rect=event.currentTarget.getBoundingClientRect();update({s:clamp((event.clientX-rect.left)/rect.width),v:clamp(1-(event.clientY-rect.top)/rect.height)});};
  const portal=anchor.current?.closest('.drawer');
  return <div className="message-color-field" ref={anchor}><span>{label}</span><div className={'message-color-input'+(!valid(input)?' invalid-color':'')}>
    <button type="button" className="message-color-swatch" aria-label={label+'取色器'} aria-expanded={open} onClick={()=>{onStart();open?onClose():onOpen();}}><i style={{backgroundColor:value}}/></button>
    <input aria-label={label+' HEX'} value={input} maxLength={7} spellCheck={false} onFocus={onStart} onBlur={()=>{if(!valid(input))setInput(value);onEnd();}} onChange={event=>{setInput(event.target.value);if(valid(event.target.value))onChange(event.target.value.toLowerCase());}}/>
  </div>{open&&portal&&createPortal(<div ref={panel} className="message-color-popup" style={position} role="group" aria-label={label+'调色板'}>
    <header><strong>{label}</strong><button type="button" className="icon-button" aria-label="收起取色器" onClick={()=>{onEnd();onClose();anchor.current.querySelector('button')?.focus();}}><X size={14}/></button></header>
    <div className="message-saturation-plane" style={{backgroundColor:`hsl(${hsv.h} 100% 50%)`}} role="slider" tabIndex={0} aria-label={label+'饱和度和明度'} aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.round(hsv.s*100)} aria-valuetext={`饱和度${Math.round(hsv.s*100)}%，明度${Math.round(hsv.v*100)}%`} onPointerDown={event=>{onStart();event.currentTarget.setPointerCapture(event.pointerId);plane(event);}} onPointerMove={event=>{if(event.currentTarget.hasPointerCapture(event.pointerId))plane(event);}} onPointerUp={onEnd} onKeyDown={event=>{if(['ArrowLeft','ArrowRight','ArrowUp','ArrowDown'].includes(event.key)){event.preventDefault();update(event.key==='ArrowLeft'||event.key==='ArrowRight'?{s:clamp(hsv.s+(event.key==='ArrowRight'?.01:-.01))}:{v:clamp(hsv.v+(event.key==='ArrowUp'?.01:-.01))});}}}><i style={{left:`${hsv.s*100}%`,top:`${(1-hsv.v)*100}%`}}/></div>
    <input className="message-hue-slider" type="range" min="0" max="359" aria-label={label+'色相'} value={Math.round(hsv.h)} onPointerDown={onStart} onPointerUp={onEnd} onChange={event=>update({h:Number(event.target.value)})}/>
    <div className="message-picker-value"><i style={{backgroundColor:value}}/><code>{value.toUpperCase()}</code><span>RGB</span></div>
    <div className="message-picker-rgb">{[['R',16],['G',8],['B',0]].map(([channel,shift])=><label key={channel}>{channel}<input aria-label={label+' '+channel} type="number" min="0" max="255" value={(parseInt(value.slice(1),16)>>shift)&255} onFocus={onStart} onBlur={onEnd} onChange={event=>{const n=Number(event.target.value);if(Number.isInteger(n)&&n>=0&&n<=255){const rgb=(parseInt(value.slice(1),16)&~(255<<shift))|(n<<shift);onChange('#'+rgb.toString(16).padStart(6,'0'));}}}/></label>)}</div>
    <div className="message-color-presets">{['#ffffff','#ffaa00','#f87171','#86efac','#67e8f9','#a49ae3','#c084fc','#000000'].map(color=><button key={color} type="button" aria-label={'选择颜色 '+color} title={color} style={{backgroundColor:color}} onClick={()=>{onStart();onChange(color);onEnd();}}/>)}</div>
  </div>,portal)}</div>;
}
