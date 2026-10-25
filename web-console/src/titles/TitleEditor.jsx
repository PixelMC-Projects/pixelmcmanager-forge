import React, { useEffect, useRef, useState } from 'react';
import { Plus, Trash, ArrowCounterClockwise } from '@phosphor-icons/react';
import { api, glyphs, hex, RichName, textOf } from './api';
import { ColorField, Confirm, ErrorNotice, Modal, Select, Switch } from './ui';
import { TitleHolders } from './TitleHolders';

const segment = () => ({ text: '', color: 0xffffff, bold: false, italic: false, underline: false, strike: false });
export const newTitle = () => ({ id: '', name: { segments: [segment()], gradients: [] }, description: '', source: '', acquisition: '', category: '纪念', order: 0, icon: 0, visible: true, issuable: true, revision: 0 });
function adjustGradients(groups, start, oldText, newText) {
  const a = Array.from(oldText), b = Array.from(newText);
  let prefix = 0, suffix = 0;
  while (prefix < a.length && prefix < b.length && a[prefix] === b[prefix]) prefix++;
  while (suffix < a.length - prefix && suffix < b.length - prefix && a[a.length - 1 - suffix] === b[b.length - 1 - suffix]) suffix++;
  const from = start + prefix, to = start + a.length - suffix, delta = b.length - a.length;
  return groups.map(g => ({ ...g, positions: g.positions.flatMap(i => i < from ? [i] : i >= to ? [i + delta] : []) })).filter(g => g.positions.length);
}
function validate(draft) {
  const chars = Array.from(textOf(draft));
  if (!chars.length || chars.length > 80 || draft.name.segments.length > 32) throw new Error('称号须为1–80个字符，最多32个文字段。');
  if (/[\u0000-\u001f\u007f§]/.test(chars.join(''))) throw new Error('称号不可包含控制字符或 §，请使用样式选项。');
  if (!Number.isInteger(Number(draft.icon)) || Number(draft.icon) < 0 || Number(draft.icon) > 2147483647) throw new Error('图标编号须为有效的非负整数。');
  const used = new Set();
  if (draft.name.gradients.length > 16) throw new Error('最多16组渐变。');
  for (const group of draft.name.gradients) {
    if (!group.positions.length) throw new Error('每组渐变至少选择一个文字，或删除空的渐变组。');
    for (const i of group.positions) { if (i < 0 || i >= chars.length || used.has(i)) throw new Error('渐变选字超出文字范围或与其他组重叠。'); used.add(i); }
  }
}
export function DisplayPreview({ title, settings }) {
  const prefix = settings?.chatPrefix ?? '[{title}] ';
  const parts = prefix.split('{title}');
  return <section className="preview-section"><div className="section-heading"><span>展示预览</span><span className="small-muted">网页示意</span></div><div className="name-preview"><span className="preview-label">头顶</span><div><span className="title-render" style={{ fontSize: 15 * (settings?.headScale ?? 1), marginBottom: (settings?.headOffset ?? 0.3) * 10 }}><RichName value={title.name} /></span><span className="player-name">Player</span></div></div><div className="inline-preview"><span className="preview-label">聊天</span><div>{parts.map((part, i) => <React.Fragment key={i}>{i > 0 && <RichName value={title.name} />}{part}</React.Fragment>)}<span className="player-name">&lt;Player&gt;</span> <span className="small-muted">大家好</span></div></div><div className="inline-preview"><span className="preview-label">TAB</span><div>[<RichName value={title.name} />] <span className="player-name">Player</span></div></div></section>;
}
export function TitleEditor({ title, close, saved, writable, settings, categories = [] }) {
  const initial = useRef(JSON.stringify(title || {...newTitle(),source:settings?.packName || ''}));
  const [draft, setDraft] = useState(() => JSON.parse(initial.current));
  const [tab, setTab] = useState(0), [part, setPart] = useState(0), [gradient, setGradient] = useState(0);
  const [positions, setPositions] = useState(''), [error, setError] = useState(null), [busy, setBusy] = useState(false), [discard, setDiscard] = useState(false);
  const form = useRef(null), formId = React.useId();
  const dirty = JSON.stringify(draft) !== initial.current;
  useEffect(() => { const fn = e => { if (dirty) { e.preventDefault(); e.returnValue = ''; } }; window.addEventListener('beforeunload', fn); return () => window.removeEventListener('beforeunload', fn); }, [dirty]);
  const group = draft.name.gradients[gradient];
  useEffect(() => setPositions(group ? group.positions.map(i => i + 1).join(',') : ''), [JSON.stringify(group?.positions)]);
  const set = (key, value) => setDraft(d => ({ ...d, [key]: value }));
  const updateName = fn => setDraft(d => ({ ...d, name: fn(d.name) }));
  const editPart = (key, value) => updateName(name => {
    const segments = name.segments.map((s, i) => i === part ? { ...s, [key]: value } : s);
    const start = name.segments.slice(0, part).reduce((n, s) => n + Array.from(s.text).length, 0);
    return { segments, gradients: key === 'text' ? adjustGradients(name.gradients, start, name.segments[part].text, value) : name.gradients };
  });
  const editGradient = (key, value) => updateName(name => ({ ...name, gradients: name.gradients.map((g, i) => i === gradient ? { ...g, [key]: value } : g) }));
  const removePart = () => { updateName(name => { const start = name.segments.slice(0, part).reduce((n, s) => n + Array.from(s.text).length, 0); return { segments: name.segments.filter((_, i) => i !== part), gradients: adjustGradients(name.gradients, start, name.segments[part].text, '') }; }); setPart(Math.max(0, part - 1)); };
  const requestClose = () => { if (busy) return; if (dirty) setDiscard(true); else close(); };
  const submit = async event => { event.preventDefault(); setError(null); try { validate(draft); if (!writable) throw new Error('数据库离线，当前只读。'); setBusy(true); const result = await api('/titles', { title: { ...draft, icon: Number(draft.icon) }, expectedRevision: title?.revision ?? 0 }); await saved(result.title); } catch (e) { setError(e); } finally { setBusy(false); } };
  const applyPositions = () => { try {
    const ids = new Set(), count = glyphs(draft.name).length;
    for (const section of positions.split(/[,，\s]+/).filter(Boolean)) { const m = section.match(/^(\d+)(?:-(\d+))?$/); if (!m) throw new Error('选区格式示例：1-3,5'); const a = Number(m[1]), b = Number(m[2] || m[1]); if (a < 1 || b < a || b > count) throw new Error('选区超出文字范围。'); for (let i = a; i <= b; i++) ids.add(i - 1); }
    const other = new Set(draft.name.gradients.flatMap((g, i) => i === gradient ? [] : g.positions));
    if ([...ids].some(i => other.has(i))) throw new Error('选中的文字已经属于其他渐变组。');
    editGradient('positions', [...ids].sort((a, b) => a - b)); setError(null);
  } catch (e) { setError(e); } };
  const selectedPart = draft.name.segments[part] || draft.name.segments[0];
  return <><Modal title={title ? '称号详情' : '新建称号'} subtitle="称号档案" close={requestClose} wide busy={busy} footer={<><span>{dirty ? '有未保存的修改' : tab === 3 ? '按玩家名称首字母排序' : '更改后同步所有持有者'}</span><button className="button secondary" disabled={busy} onClick={requestClose}>关闭</button>{tab !== 3 && <button className="button primary" type="submit" form={formId} disabled={busy || !writable}>{busy ? '正在保存…' : '保存并同步'}</button>}</>}>
    <div className="editor-tabs" role="tablist" aria-label="称号详情分类">{['基本资料', '文字样式', '渐变选区', ...(title ? ['持有名单'] : [])].map((name, i) => <button type="button" key={name} role="tab" aria-selected={tab === i} aria-controls={formId+'-panel'} className={tab === i ? 'active' : ''} onClick={() => setTab(i)} onKeyDown={event => {
      const total = title ? 4 : 3;
      const next = event.key === 'ArrowRight' ? (i+1)%total : event.key === 'ArrowLeft' ? (i+total-1)%total : event.key === 'Home' ? 0 : event.key === 'End' ? total-1 : null;
      if (next !== null) { event.preventDefault(); setTab(next); event.currentTarget.parentElement.children[next].focus(); }
    }}>{name}</button>)}</div>
    <div className="editor-body" id={formId+'-panel'} role="tabpanel" aria-label={['基本资料','文字样式','渐变选区','持有名单'][tab]}>{tab === 3 ? <TitleHolders title={title}/> : <><ErrorNotice error={error} /><form ref={form} id={formId} onSubmit={submit}><fieldset disabled={busy || !writable}>
      {tab === 0 && <div className="form-group">
        <label>称号名称{draft.name.segments.length === 1 ? <input value={selectedPart.text} required maxLength={160} onChange={e => editPart('text', e.target.value)} placeholder="例如：山海同行者" /> : <div className="readonly-value"><RichName value={draft.name} /><button className="sample-link" type="button" onClick={() => setTab(1)}>编辑分段文字</button></div>}</label>
        <label>称号说明<textarea rows={3} maxLength={2048} value={draft.description} onChange={e => set('description', e.target.value)} /></label>
        <div className="form-columns"><label>来源 / 整合包<input value={draft.source} maxLength={256} onChange={e => set('source', e.target.value)} /></label><div className="field-group"><span className="field-label">分类</span><Select label="分类" editable value={draft.category} onChange={value => set('category',value)} options={[...new Set(['纪念',...categories])].filter(Boolean).map(value => ({value,label:value}))} disabled={busy || !writable}/></div></div>
        <label>图标编号<input type="number" step="1" min="0" max="2147483647" value={draft.icon} onChange={e => set('icon', e.target.value)} required /></label>
        <p className="field-help">图标编号对应客户端资源包的 CustomModelData；未配置美术时使用命名牌。</p>
        <Switch label="图鉴公开" description="尚未获得该称号的玩家可以在图鉴中查看。" value={draft.visible} onChange={v => set('visible', v)} />
        <Switch label="是否绝版" description="开启后停止发放，原持有者仍可佩戴；游戏内以金色标注已绝版。" value={!draft.issuable} onChange={v => set('issuable', !v)} />
      </div>}
      {tab === 1 && <div className="form-group"><div className="segment-list">{draft.name.segments.map((s, i) => <button type="button" key={i} className={i === part ? 'selected' : ''} onClick={() => setPart(i)}>{i + 1} · {s.text.slice(0, 8) || '空文字段'}</button>)}</div>
        <div className="inline-actions"><button type="button" className="button secondary" disabled={draft.name.segments.length >= 32} onClick={() => { const next = draft.name.segments.length; updateName(n => ({ ...n, segments: [...n.segments, segment()] })); setPart(next); }}><Plus size={14} />新增文字段</button><button type="button" className="button quiet" disabled={draft.name.segments.length <= 1} onClick={removePart}><Trash size={14} />删除本段</button></div>
        <label>第 {part + 1} 段文字<input value={selectedPart.text} maxLength={160} onChange={e => editPart('text', e.target.value)} /></label><ColorField label="本段颜色" value={selectedPart.color} onChange={v => editPart('color', v)} />
        <div className="format-buttons">{[['bold', '粗体'], ['italic', '斜体'], ['underline', '下划线'], ['strike', '删除线']].map(([key, label]) => <button type="button" key={key} aria-pressed={selectedPart[key]} className={selectedPart[key] ? 'selected' : ''} onClick={() => editPart(key, !selectedPart[key])}>{label}</button>)}</div>
        <button type="button" className="button quiet reset-style" onClick={() => updateName(n => ({ ...n, segments: n.segments.map((s, i) => i === part ? { ...segment(), text: s.text } : s) }))}><ArrowCounterClockwise size={14} />重置本段样式</button>
        <p className="field-help">总计 {Array.from(textOf(draft)).length} / 80 字。修改文字时，已删除文字的渐变选择会一起移除，后面的选择自动移动。</p>
      </div>}
      {tab === 2 && <div className="form-group"><div className="segment-list">{draft.name.gradients.map((g, i) => <button type="button" key={i} className={gradient === i ? 'selected' : ''} onClick={() => setGradient(i)}>渐变 {i + 1} · {g.positions.length} 字</button>)}</div><div className="inline-actions"><button type="button" className="button secondary" disabled={draft.name.gradients.length >= 16} onClick={() => { const next = draft.name.gradients.length; updateName(n => ({ ...n, gradients: [...n.gradients, { positions: [], from: 0x67e8f9, to: 0xc084fc }] })); setGradient(next); }}><Plus size={14} />新增渐变组</button>{group && <button type="button" className="button quiet" onClick={() => { updateName(n => ({ ...n, gradients: n.gradients.filter((_, i) => i !== gradient) })); setGradient(Math.max(0, gradient - 1)); }}><Trash size={14} />删除本组</button>}</div>
        {group ? <><p className="field-help">点击参与本组渐变的文字。已属于其他组的文字不能重复选择；渐变按文字顺序从起色过渡到末色。</p><div className="glyph-selector">{glyphs(draft.name).map((char, index) => { const used = draft.name.gradients.some((g, i) => i !== gradient && g.positions.includes(index)); return <button type="button" key={index} disabled={used} aria-label={`第${index + 1}字 ${char.text}${used ? '，已属于其他渐变组' : ''}`} aria-pressed={group.positions.includes(index)} className={group.positions.includes(index) ? 'selected' : ''} onClick={() => editGradient('positions', (group.positions.includes(index) ? group.positions.filter(i => i !== index) : [...group.positions, index]).sort((a, b) => a - b))}><span style={{ color: hex(char.color) }}>{char.text === ' ' ? '␠' : char.text}</span><small>{index + 1}</small></button>; })}</div>
          <label>文字序号<div className="input-action"><input value={positions} onChange={e => setPositions(e.target.value)} placeholder="1-3,5" /><button type="button" className="button secondary" onClick={applyPositions}>应用选区</button></div></label><div className="form-columns"><ColorField label="起始颜色" value={group.from} onChange={v => editGradient('from', v)} /><ColorField label="结束颜色" value={group.to} onChange={v => editGradient('to', v)} /></div>{!group.positions.length && <p className="field-help error-text">本组尚未选择文字，保存前请选择或删除本组。</p>}</> : <p className="field-help">暂无渐变组。普通分段颜色仍然生效。</p>}
      </div>}
    </fieldset></form><DisplayPreview title={draft} settings={settings} /></>}</div>
  </Modal>{discard && <Confirm title="放弃未保存的修改？" cancel={() => setDiscard(false)} confirm={close} danger label="放弃修改">这些修改尚未保存，关闭后会丢失。</Confirm>}</>;
}
