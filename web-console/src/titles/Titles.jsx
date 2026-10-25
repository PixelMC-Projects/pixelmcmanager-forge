import React, { useEffect, useState } from 'react';
import { BookmarkSimple, Check, FunnelSimple, MagnifyingGlass, Plus, SlidersHorizontal, Trash } from '@phosphor-icons/react';
import { api, creationDate, pinyin, RichName, textOf } from './api';
import { Empty, ErrorNotice, IconButton, Modal, Pagination, useRemote } from './ui';
import { TitleEditor } from './TitleEditor';

function DeleteTitleDialog({ title, writable, close, done }) {
  const loaded = useRemote(() => api('/title-delete/preview', { title: title.id }), [title.id]);
  const [step, setStep] = useState(0), [name, setName] = useState(''), [busy, setBusy] = useState(false), [error, setError] = useState(null);
  const preview = loaded.data, plainName = preview?.name.segments.map(part => part.text).join('');
  const reload = () => { setStep(0); setName(''); setError(null); loaded.reload(); };
  const submit = async event => {
    event.preventDefault();
    if (!preview || name !== plainName || !writable || busy) return;
    setBusy(true); setError(null);
    try { const result = await api('/title-delete/confirm', { title: title.id, stamp: preview.stamp, confirmName: name }); await done(result.removedHolders); }
    catch (err) { setError(err); } finally { setBusy(false); }
  };
  return <Modal title={step ? '再次确认删除称号' : '删除称号'} subtitle="称号档案" close={close} drawer={false} busy={busy} footer={<><button className="button secondary" disabled={busy} onClick={close}>取消</button>{step ? <button className="button danger" form="delete-title-form" type="submit" disabled={busy || !writable || name !== plainName}>{busy ? '正在删除…' : '确认删除称号'}</button> : <button className="button danger" disabled={!preview || !!loaded.error || !writable} onClick={() => setStep(1)}>继续确认</button>}</>}>
    <div className="editor-body"><ErrorNotice error={loaded.error} retry={reload}/><ErrorNotice error={error} retry={reload}/>{preview ? <>
      <div className="delete-title-preview"><BookmarkSimple size={20}/><RichName value={preview.name}/></div>
      {step ? <form id="delete-title-form" onSubmit={submit} className="form-group"><p className="confirm-copy">将删除这个称号，并从 {preview.holders} 名玩家的收藏中移除。</p><label>输入完整称号名称确认<input autoFocus autoComplete="off" spellCheck={false} maxLength={160} value={name} onChange={event=>setName(event.target.value)} placeholder={plainName}/></label></form> : <><dl className="detail-list full-details delete-counts"><div><dt>持有玩家</dt><dd>{preview.holders} 人</dd></div><div><dt>当前佩戴</dt><dd>{preview.equipped} 人 · 同时卸下</dd></div><div><dt>首登领取记录</dt><dd>{preview.receipts} 条 · 同时清除</dd></div></dl>{preview.reward && <p className="title-delete-reward">这个称号正用作首登奖励，删除后会同时关闭这项奖励。</p>}</>}
      <p className="field-help">所有持有者会收到移除提示。离线消息保留到下次进服满10秒后发送。</p><p className="field-help">可在操作记录中撤回此次删除；相关资料存在后续变动时，会说明冲突。</p>
    </> : !loaded.error && <p className="loading-copy">正在核对称号和受影响的玩家…</p>}</div>
  </Modal>;
}

export function Titles({ context }) {
  const { state, writable, refresh, toast, density, setDensity } = context;
  const [search,setSearch] = useState(''), [tab,setTab] = useState(0), [visibility,setVisibility] = useState('all'), [page,setPage] = useState(0);
  const [popover,setPopover] = useState(''), [sort,setSort] = useState('created'), [editor,setEditor] = useState(null);
  const [deleting, setDeleting] = useState(null);
  useEffect(() => setPage(0), [search,tab,visibility,sort]);
  useEffect(() => {
    const click = event => { if(!event.target.closest('.popover-anchor')) setPopover(''); };
    const key = event => { if(event.key==='Escape') setPopover(''); };
    document.addEventListener('pointerdown',click); document.addEventListener('keydown',key);
    return () => { document.removeEventListener('pointerdown',click); document.removeEventListener('keydown',key); };
  },[]);
  const list = state.titles.filter(t => `${textOf(t)} ${t.category} ${t.source}`.toLocaleLowerCase().includes(search.trim().toLocaleLowerCase()))
    .filter(t => tab===0 || t.issuable===(tab===1)).filter(t => visibility==='all' || t.visible===(visibility==='public'))
    .sort((a,b) => sort==='name' ? pinyin.compare(textOf(a),textOf(b)) || b.createdAt-a.createdAt : b.createdAt-a.createdAt || pinyin.compare(textOf(a),textOf(b)));
  const current = Math.min(page,Math.max(0,Math.ceil(list.length/20)-1)), rows = list.slice(current*20,current*20+20);
  return <><div className="view-bar"><div className="view-tabs" role="tablist" aria-label="称号分类">{['全部称号','未绝版','已绝版'].map((label,i) => <button key={label} role="tab" aria-selected={tab===i} className={tab===i?'active':''} onClick={() => setTab(i)}>{label}</button>)}</div><div className="view-actions">
    <div className="popover-anchor"><button className="button quiet" aria-label="筛选" aria-expanded={popover==='filter'} onClick={() => setPopover(p=>p==='filter'?'':'filter')}><FunnelSimple size={15}/><span>筛选</span>{visibility!=='all' && <span className="filter-dot"/>}</button>{popover==='filter' && <div className="popover"><span className="popover-label">图鉴可见性</span>{[['all','全部'],['public','公开'],['hidden','隐藏']].map(([value,label]) => <button key={value} onClick={() => {setVisibility(value);setPopover('');}}><span>{label}</span>{visibility===value && <Check size={14}/>}</button>)}</div>}</div>
    <div className="popover-anchor"><button className="button quiet" aria-label="显示" aria-expanded={popover==='display'} onClick={() => setPopover(p=>p==='display'?'':'display')}><SlidersHorizontal size={15}/><span>显示</span></button>{popover==='display' && <div className="popover"><span className="popover-label">列表密度</span>{[['comfortable','舒适'],['compact','紧凑']].map(([value,label]) => <button key={value} onClick={() => setDensity(value)}><span>{label}</span>{density===value && <Check size={14}/>}</button>)}<span className="popover-label">排序方式</span>{[['created','添加时间 · 新到旧'],['name','名称拼音 A–Z']].map(([value,label]) => <button key={value} onClick={() => {setSort(value);setPopover('');}}><span>{label}</span>{sort===value && <Check size={14}/>}</button>)}</div>}</div>
    <button className="button primary create-button" disabled={!writable} onClick={() => setEditor('new')}><Plus size={15}/><span>新建称号</span></button>
  </div></div><div className="content-area"><div className="list-search-row"><MagnifyingGlass size={15}/><input aria-label="搜索称号" value={search} placeholder="搜索名称、分类或来源…" onChange={event => setSearch(event.target.value)}/><span className="search-hint">{visibility==='all'?'':`图鉴：${visibility==='public'?'公开':'隐藏'}`}</span></div>
    <div className="table-heading titles-grid title-actions-grid"><span>称号</span><span className="row-holders">拥有者</span><span className="row-category">分类</span><span className="row-source">来源</span><span className="row-retired">绝版状态</span><span className="row-visibility">图鉴可见性</span><span className="row-actions">操作</span></div><div className="title-rows">{rows.map(title => <div className="title-row titles-grid title-actions-grid" key={title.id} onClick={() => setEditor(title)}><button className="title-cell title-open" aria-label={'查看称号详情 '+textOf(title)}><BookmarkSimple size={18} weight="light"/><span className="title-cell-copy"><span className="row-name"><RichName value={title.name}/></span><time className="row-date" dateTime={title.createdAt ? new Date(title.createdAt).toISOString() : undefined}>{creationDate(title.createdAt)}</time></span></button><span className="row-holders">{state.holderCounts?.[title.id] || 0}<small>人</small></span><span className="row-category">{title.category || '—'}</span><span className="row-source">{title.source || '—'}</span><span className={`row-retired ${title.issuable?'muted':'retired-gold'}`}>{title.issuable?'未绝版':'已绝版'}</span><span className="row-visibility">{title.visible?'公开':'隐藏'}</span><IconButton icon={Trash} label={'删除称号 '+textOf(title)} className="icon-button title-delete-button" disabled={!writable} onClick={event => {event.stopPropagation();setDeleting(title);}}/></div>)}{!rows.length && <Empty icon={BookmarkSimple} title={state.titles.length?'没有匹配的称号':'还没有称号'}>{state.titles.length?'调整搜索、绝版状态或图鉴筛选。':'从一个称号开始，留住一段共同的经历。'}</Empty>}</div>
  </div><Pagination page={current} total={list.length} onPage={setPage}>按{sort==='name'?'名称拼音':'添加时间'}排序 · 编辑不改变创建日期</Pagination>
  {editor && <TitleEditor key={editor.id || 'new'} title={editor==='new'?null:editor} writable={writable} settings={state.settings} categories={state.titles.map(t=>t.category)} close={() => setEditor(null)} saved={async () => {await refresh();setEditor(null);toast('称号已保存');}}/>}
  {deleting && <DeleteTitleDialog title={deleting} writable={writable} close={()=>setDeleting(null)} done={async count=>{await refresh();setDeleting(null);toast(`称号已删除，已清除 ${count} 名玩家持有，可在操作记录中撤回`);}}/>}</>;
}
