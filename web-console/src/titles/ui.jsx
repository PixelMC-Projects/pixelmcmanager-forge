import React, { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { ArrowLeft, ArrowRight, CaretDown, Check, WarningCircle, X } from '@phosphor-icons/react';
export function IconButton({ icon: Icon, label, ...props }) { return <button className="icon-button" aria-label={label} title={label} {...props}><Icon size={17} /></button>; }
export function ErrorNotice({ error, retry }) { return error ? <div role="alert" className="error-notice"><WarningCircle size={17} /><span>{typeof error === 'string' ? error : error.message}</span>{retry && <button className="button quiet" onClick={retry}>重试</button>}</div> : null; }
export function Empty({ icon: Icon, title = '暂无资料', children }) { return <div className="empty-space"><div className="empty-content">{Icon && <Icon size={29} weight="light" className="empty-icon" />}<h2>{title}</h2><p>{children}</p></div></div>; }
export function Pagination({ page, size = 20, total, onPage, children }) { const pages = Math.max(1, Math.ceil(total / size)); return <footer className="list-footer"><span>共 {total} 项</span><span className="footer-note">{children}</span><div className="pagination"><IconButton icon={ArrowLeft} label="上一页" disabled={page <= 0} onClick={() => onPage(page - 1)} /><span className="mono">{page + 1} / {pages}</span><IconButton icon={ArrowRight} label="下一页" disabled={page >= pages - 1} onClick={() => onPage(page + 1)} /></div></footer>; }
export function Switch({ label, description, value, onChange, disabled }) { return <div className="switch-row"><div><strong>{label}</strong>{description && <p>{description}</p>}</div><button type="button" role="switch" aria-label={label} aria-checked={!!value} disabled={disabled} className="switch" onClick={() => onChange(!value)}><span /></button></div>; }
const modalStack = [];
export function Modal({ title, subtitle, close, children, footer, wide = false, drawer = true, busy = false }) {
  const ref = useRef(null);
  const latest = useRef({ close, busy }); latest.current = { close, busy };
  const headingId = React.useId();
  useEffect(() => {
    const previous = document.activeElement;
    const panel = ref.current;
    modalStack.push(panel);
    (panel.querySelector('input:not(:disabled),textarea:not(:disabled)') || panel.querySelector('button:not(:disabled)'))?.focus();
    const key = event => {
      if (modalStack.at(-1) !== panel) return;
      if (event.key === 'Escape') { event.preventDefault(); if (!latest.current.busy) latest.current.close(); }
      if (event.key === 'Tab') {
        const selector = 'button:not(:disabled),input:not(:disabled),textarea:not(:disabled),select:not(:disabled),a[href],[tabindex="0"]';
        const items = [...ref.current.querySelectorAll(selector),...document.querySelectorAll('.themed-popup button:not(:disabled),.themed-popup input:not(:disabled),.themed-popup [tabindex="0"]')].filter(el => el.getClientRects().length && el.tabIndex >= 0);
        if (!items.length) { event.preventDefault(); return; }
        if (event.shiftKey && document.activeElement === items[0]) { event.preventDefault(); items.at(-1).focus(); }
        else if (!event.shiftKey && document.activeElement === items.at(-1)) { event.preventDefault(); items[0].focus(); }
      }
    };
    document.addEventListener('keydown', key);
    return () => {
      document.removeEventListener('keydown', key);
      const index = modalStack.indexOf(panel);
      if (index >= 0) modalStack.splice(index, 1);
      if (previous?.isConnected) previous.focus();
    };
  }, []);
  return <div className={`overlay ${drawer ? '' : 'center-overlay'}`} onMouseDown={event => { if (event.target === event.currentTarget && !busy) close(); }}><section ref={ref} className={`editor ${drawer ? '' : 'dialog-card'} ${wide ? 'wide-editor' : ''}`} role="dialog" aria-modal="true" aria-labelledby={headingId}><header className="editor-header"><div><span className="eyebrow">{subtitle || 'MemorialTitles'}</span><h2 id={headingId}>{title}</h2></div><IconButton icon={X} label="关闭面板" disabled={busy} onClick={close} /></header>{children}{footer && <footer className="editor-footer">{footer}</footer>}</section></div>;
}
export function Confirm({ title, children, confirm, cancel, busy, danger = false, label = '确认' }) { return <Modal title={title} close={cancel} drawer={false} busy={busy} footer={<><button className="button secondary" disabled={busy} onClick={cancel}>取消</button><button className={`button ${danger ? 'danger' : 'primary'}`} disabled={busy} onClick={confirm}>{busy ? '正在处理…' : label}</button></>}><div className="editor-body confirm-copy">{children}</div></Modal>; }
export function useFloating(open, close, width) {
  const anchor = useRef(null), panel = useRef(null), [position, setPosition] = useState({});
  const latest = useRef(close); latest.current = close;
  useLayoutEffect(() => {
    if (!open) return;
    const place = () => {
      const rect = anchor.current.getBoundingClientRect(), w = Math.min(width || rect.width, innerWidth - 24);
      const height = panel.current?.offsetHeight || 260;
      const below = rect.bottom + 7, above = rect.top - height - 7;
      setPosition({ width: w, left: Math.max(12, Math.min(rect.left, innerWidth-w-12)), top: below+height <= innerHeight-12 ? below : Math.max(12, above), maxHeight: innerHeight-24 });
    };
    place();
    const outside = event => { if (!anchor.current?.contains(event.target) && !panel.current?.contains(event.target)) latest.current(); };
    const escape = event => { if (event.key === 'Escape') { event.preventDefault(); event.stopImmediatePropagation(); latest.current(); } };
    document.addEventListener('pointerdown', outside, true); document.addEventListener('keydown', escape, true);
    window.addEventListener('resize', place); window.addEventListener('scroll', place, true);
    return () => { document.removeEventListener('pointerdown', outside, true); document.removeEventListener('keydown', escape, true); window.removeEventListener('resize', place); window.removeEventListener('scroll', place, true); };
  }, [open, width]);
  return { anchor, panel, position };
}
export function Select({ label, value, onChange, options, placeholder = '请选择', editable = false, disabled = false }) {
  const [open, setOpen] = useState(false), [active, setActive] = useState(0);
  const listId = React.useId(), floating = useFloating(open, () => setOpen(false));
  const selected = options.find(option => option.value === value);
  const shown = editable && open ? options.filter(option => String(option.label).toLocaleLowerCase().includes(String(value).toLocaleLowerCase())) : options;
  useEffect(() => { if (disabled) setOpen(false); }, [disabled]);
  const toggle = () => { setActive(Math.max(0, shown.findIndex(option => option.value === value))); setOpen(v => !v); };
  const choose = option => { onChange(option.value); setOpen(false); };
  const keyboard = event => {
    if (['ArrowDown','ArrowUp','Home','End'].includes(event.key)) {
      if (!open && editable && ['Home','End'].includes(event.key)) return;
      event.preventDefault(); setOpen(true);
      setActive(i => event.key === 'Home' ? 0 : event.key === 'End' ? shown.length-1 : Math.max(0, Math.min(shown.length-1, i+(event.key === 'ArrowDown' ? 1 : -1))));
    } else if (event.key === 'Enter' && open) { event.preventDefault(); if (shown[active]) choose(shown[active]); else setOpen(false); }
    else if (event.key === 'Tab') setOpen(false);
  };
  return <div className="custom-select" ref={floating.anchor}>
    {editable ? <div className="combo-input"><input role="combobox" aria-label={label} aria-expanded={open} aria-controls={listId} aria-autocomplete="list" aria-activedescendant={open && shown[active] ? listId+'-'+active : undefined} value={value} maxLength={128} disabled={disabled} placeholder={placeholder} onFocus={() => { setActive(0); setOpen(true); }} onChange={e => { onChange(e.target.value); setActive(0); setOpen(true); }} onKeyDown={keyboard} /><button type="button" aria-label={'展开'+label} disabled={disabled} onClick={toggle}><CaretDown size={13}/></button></div> : <button type="button" role="combobox" aria-label={label} aria-expanded={open} aria-controls={listId} aria-activedescendant={open && shown[active] ? listId+'-'+active : undefined} className="select-trigger" disabled={disabled} onClick={toggle} onKeyDown={keyboard}><span className={selected ? '' : 'muted'}>{selected?.label || placeholder}</span><CaretDown size={13}/></button>}
    {open && createPortal(<div ref={floating.panel} className="themed-popup select-options" style={floating.position} role="listbox" id={listId} aria-label={label+'选项'} onMouseDown={e => e.preventDefault()}>{shown.map((option,i) => <button type="button" role="option" tabIndex={-1} aria-selected={option.value === value} disabled={option.disabled} id={listId+'-'+i} key={option.value} className={i === active ? 'highlighted' : ''} onPointerMove={() => setActive(i)} onClick={() => choose(option)}><span>{option.label}</span>{option.value === value && <Check size={13}/>}</button>)}{!shown.length && <div className="combo-empty">输入即可创建新分类</div>}</div>, document.querySelector('.titles-module'))}
  </div>;
}
function toHsv(color) {
  const r = ((color>>16)&255)/255, g = ((color>>8)&255)/255, b = (color&255)/255, max = Math.max(r,g,b), min = Math.min(r,g,b), delta = max-min;
  let h = delta === 0 ? 0 : max === r ? ((g-b)/delta)%6 : max === g ? (b-r)/delta+2 : (r-g)/delta+4;
  return { h: (h*60+360)%360, s: max ? delta/max : 0, v: max };
}
function fromHsv({h,s,v}) {
  const chroma = v*s, x = chroma*(1-Math.abs((h/60)%2-1)), m = v-chroma;
  const values = h<60 ? [chroma,x,0] : h<120 ? [x,chroma,0] : h<180 ? [0,chroma,x] : h<240 ? [0,x,chroma] : h<300 ? [x,0,chroma] : [chroma,0,x];
  return values.reduce((color,n) => (color<<8)|Math.round((n+m)*255),0);
}
export function ColorField({ label, value, onChange }) {
  const color = value ?? 0xffffff, formatted = '#' + color.toString(16).padStart(6,'0');
  const [input,setInput] = useState(formatted), [open,setOpen] = useState(false), [hsv,setHsv] = useState(() => toHsv(color));
  const floating = useFloating(open, () => setOpen(false), 280);
  useEffect(() => { setInput(formatted); setHsv(old => { const next = toHsv(color); return {...next,h: next.s === 0 ? old.h : next.h}; }); },[formatted]);
  const valid = /^#?[a-fA-F0-9]{6}$/.test(input);
  const update = patch => { const next = {...hsv,...patch}; setHsv(next); onChange(fromHsv(next)); };
  const plane = event => { const rect = event.currentTarget.getBoundingClientRect(); update({s: Math.max(0,Math.min(1,(event.clientX-rect.left)/rect.width)),v: Math.max(0,Math.min(1,1-(event.clientY-rect.top)/rect.height))}); };
  return <div className="color-field"><span className="field-label">{label}</span><div ref={floating.anchor} className={`color-control ${valid ? '' : 'invalid-color'}`}><button type="button" aria-label={label+'选色器'} aria-expanded={open} className="color-swatch" onClick={() => setOpen(v => !v)}><span style={{background:formatted}}/></button><input className="mono color-hex" aria-label={label+'HEX'} value={input} pattern="#?[a-fA-F0-9]{6}" required onChange={event => { setInput(event.target.value); if (/^#?[a-fA-F0-9]{6}$/.test(event.target.value)) onChange(parseInt(event.target.value.replace('#',''),16)); }}/></div><div className="rgb-inputs">{[['R',16],['G',8],['B',0]].map(([channel,shift]) => <span key={channel}>{channel}<input type="number" min="0" max="255" step="1" required aria-label={label+' '+channel} value={(color>>shift)&255} onChange={event => { const n=Number(event.target.value); if(Number.isInteger(n)&&n>=0&&n<=255) onChange((color&~(255<<shift))|(n<<shift)); }}/></span>)}</div>{!valid && <span className="field-help error-text">请输入六位十六进制颜色。</span>}
    {open && createPortal(<div ref={floating.panel} className="themed-popup color-picker" style={floating.position} role="group" aria-label={label+'调色板'}><div className="picker-heading"><span>{label}</span><button type="button" aria-label="收起取色器" onClick={() => { setOpen(false); floating.anchor.current.querySelector('button').focus(); }}><X size={14}/></button></div><div className="saturation-plane" role="slider" tabIndex={0} aria-label={label+'饱和度与明度'} aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.round(hsv.s*100)} aria-valuetext={`饱和度${Math.round(hsv.s*100)}%，明度${Math.round(hsv.v*100)}%`} style={{backgroundColor:`hsl(${hsv.h} 100% 50%)`}} onPointerDown={event => { event.currentTarget.setPointerCapture(event.pointerId); plane(event); }} onPointerMove={event => { if(event.currentTarget.hasPointerCapture(event.pointerId)) plane(event); }} onKeyDown={event => {if(['ArrowLeft','ArrowRight','ArrowUp','ArrowDown'].includes(event.key)){event.preventDefault();update(event.key==='ArrowLeft'||event.key==='ArrowRight'?{s:Math.max(0,Math.min(1,hsv.s+(event.key==='ArrowRight'?.01:-.01)))}:{v:Math.max(0,Math.min(1,hsv.v+(event.key==='ArrowUp'?.01:-.01)))});}}}><span style={{left:`${hsv.s*100}%`,top:`${(1-hsv.v)*100}%`}}/></div><input className="hue-slider" type="range" min="0" max="359" aria-label={label+'色相'} value={Math.round(hsv.h)} onChange={event => update({h:Number(event.target.value)})}/><div className="picker-value"><span style={{background:formatted}}/><strong className="mono">{formatted}</strong><span>RGB</span></div><div className="color-presets">{[0xffffff,0xffaa00,0xf87171,0x86efac,0x67e8f9,0xa49ae3,0xc084fc,0x000000].map(preset => <button type="button" key={preset} aria-label={'选择颜色 #'+preset.toString(16).padStart(6,'0')} style={{background:'#'+preset.toString(16).padStart(6,'0')}} onClick={() => onChange(preset)}/>)}</div></div>,document.querySelector('.titles-module'))}
  </div>;
}
export function useDebounced(value, delay = 220) { const [result, setResult] = useState(value); useEffect(() => { const timer = setTimeout(() => setResult(value), delay); return () => clearTimeout(timer); }, [value, delay]); return result; }
export function useRemote(load, deps) { const [state, setState] = useState({ data: null, loading: true, error: null }); const [nonce, setNonce] = useState(0); useEffect(() => { let active = true; setState(s => ({ ...s, loading: true, error: null })); Promise.resolve().then(load).then(data => { if (active) setState({ data, loading: false, error: null }); }, error => { if (active) setState(s => ({ ...s, loading: false, error })); }); return () => { active = false; }; }, [...deps, nonce]); return { ...state, reload: () => setNonce(n => n + 1) }; }
