import React, { useEffect, useRef, useState } from 'react';
import { ArrowSquareOut, Archive, CaretRight, Check, ChatCircleText, Eye, EyeSlash, FileText, Heart, List, LockKey, MagnifyingGlass, PencilSimple, Plus, PushPin, Rows, Trash, Users } from '@phosphor-icons/react';
import { Modal, Confirm, Switch, Select, IconButton, Pagination, Empty } from '../titles/ui.jsx';
import { SortLabel } from '../titles/SortLabel.jsx';
import { useChangelog, dispatch } from '@hub-prototype/store.js';
import { PEOPLE, DEMO_PLAYER, formatTime, formatVersion, packLabel, orderedPacks } from '@hub-prototype/model.js';
import ArticleEditor from './ArticleEditor.jsx';
import CategoryIcon from '@hub-prototype/CategoryIcon.jsx';
import PlayerAvatar2D from '@hub-prototype/PlayerAvatar2D.jsx';
import { defaultHead } from '../playerIdentity.js';
import './hub-workspace.css';

const statusNames = { published:'已发布', draft:'草稿', withdrawn:'已撤下' };
const categories = ['功能更新','体验优化','问题修复','整合包更新','通知'];
const action = (value, toast, message) => {
  try { const result = dispatch(value); if (message) toast(message); return result; }
  catch (error) { toast(error.message); return null; }
};

function PlayerFace({id}) {
  const defaults={'demo-lin':2,'demo-moon':7,'demo-north':12};
  return <PlayerAvatar2D name={PEOPLE[id] || '玩家'} skin={id===DEMO_PLAYER.uuid?import.meta.env.VITE_PREVIEW_SKIN:null} fallback={import.meta.env.BASE_URL+'default-heads/'+(defaults[id]??defaultHead(id))+'.png'}/>;
}

function Comments({ entry, state, toast }) {
  const [filter, setFilter] = useState('all'), [replies, setReplies] = useState({}), [deleting, setDeleting] = useState(null);
  const comments = state.comments.filter(c=>c.entryId===entry.id).sort((a,b)=>new Date(b.createdAt)-new Date(a.createdAt));
  const shown = comments.filter(c=>filter==='all' || !c.replies.length);
  return <>
    <div className="view-tabs ph-comment-tabs" role="tablist" aria-label="留言分类">{[['all','全部留言',comments.length],['pending','未回复',comments.filter(c=>!c.replies.length).length]].map(([id,label,count])=><button key={id} className={filter===id?'active':''} role="tab" aria-selected={filter===id} onClick={()=>setFilter(id)}>{label}<small className="tab-count">{count}</small></button>)}</div>
    {shown.length ? shown.map(c=><section className="ph-comment" key={c.id}>
      <header><PlayerFace id={c.playerId}/><div><strong>{PEOPLE[c.playerId] || '玩家'}</strong><time>{formatTime(c.createdAt)}</time></div>{!c.replies.length&&<span className="ph-status pending">待回复</span>}<IconButton icon={Trash} label={'删除 '+(PEOPLE[c.playerId]||'玩家')+' 的留言'} onClick={()=>setDeleting(c)}/></header>
      <p>{c.text}</p>
      {c.replies.map(r=><div className="ph-admin-reply" key={r.id}><div><span>管理员回复</span><time>{formatTime(r.createdAt)}</time></div><p>{r.text}</p></div>)}
      <form className="ph-reply-form" onSubmit={e=>{e.preventDefault();if(action({type:'reply',commentId:c.id,text:replies[c.id]||''},toast,'回复已发送，仅该玩家可见'))setReplies(v=>({...v,[c.id]:''}));}}><textarea aria-label={'回复 '+(PEOPLE[c.playerId]||'玩家')} placeholder="回复这位玩家…" maxLength={2000} rows={2} value={replies[c.id]||''} onChange={e=>setReplies(v=>({...v,[c.id]:e.target.value}))}/><button className="button secondary" disabled={!replies[c.id]?.trim()}>发送回复</button></form>
    </section>) : <Empty icon={ChatCircleText} title={filter==='pending'?'所有留言都已回复':'还没有留言'}>玩家在 Hub 留言后会显示在这里。</Empty>}
    {deleting&&<Confirm title="删除这条留言？" danger label="删除留言" cancel={()=>setDeleting(null)} confirm={()=>{action({type:'deleteComment',commentId:deleting.id,admin:true},toast,'留言及回复已删除');setDeleting(null);}}><p>将删除 {PEOPLE[deleting.playerId]} 的留言及其 {deleting.replies.length} 条回复。更新正文和点赞不受影响。</p></Confirm>}
  </>;
}

function EntryEditor({ entry, state, close, toast, startTab = 'edit' }) {
  const fresh = state.entries.find(e=>e.id===entry?.id);
  const initial = useRef(entry || {id:null,title:'',summary:'',packName:state.packs.find(p=>p.current).shortName,packVersion:state.packs.find(p=>p.current).version,updateVersion:'',body:'',category:'功能更新',packId:state.packs.find(p=>p.current).id,pinned:false});
  const [draft,setDraft] = useState(()=>structuredClone(initial.current)), [baseline,setBaseline] = useState(()=>JSON.stringify(initial.current)), [tab,setTab]=useState(startTab), [discard,setDiscard]=useState(false), [deleteOpen,setDeleteOpen]=useState(false), [deleteTitle,setDeleteTitle]=useState('');
  const dirty = JSON.stringify(draft)!==baseline;
  const patch = value=>setDraft(d=>({...d,...value}));
  const requestClose=()=>dirty?setDiscard(true):close();
  useEffect(()=>{const warn=e=>{if(dirty){e.preventDefault();e.returnValue='';}};addEventListener('beforeunload',warn);return()=>removeEventListener('beforeunload',warn);},[dirty]);
  const save = publish => {
    const next=action({type:'saveEntry',value:draft,publish},toast,publish?'更新日志已发布':fresh?.status==='published'?'更新日志已保存':'草稿已保存');
    if(next) { setBaseline(JSON.stringify(draft)); close(); }
  };
  const comments=state.comments.filter(c=>c.entryId===draft.id);
  return <>
    <Modal title={entry?'更新日志详情':'新建更新日志'} subtitle="PlayerHub" close={requestClose} wide footer={<><span className="ph-editor-save-status">{dirty?'有未保存的修改':entry?statusNames[fresh?.status]:'填写内容后保存'}</span>{fresh&&<button className="button ph-delete-entry" onClick={()=>{setDeleteTitle('');setDeleteOpen(true);}}><Trash size={13}/>删除该条</button>}<button className="button secondary" onClick={requestClose}>关闭</button>{tab==='edit'&&<><button className="button secondary" disabled={!draft.title.trim()||!draft.body.trim()||(!dirty&&!!entry)} onClick={()=>save(false)}>{fresh?.status==='published'?'保存修改':'保存草稿'}</button>{fresh?.status!=='published'&&<button className="button primary" disabled={!draft.title.trim()||!draft.body.trim()} onClick={()=>save(true)}>发布日志</button>}</>}</>}>
      <div className="editor-tabs view-tabs" role="tablist" aria-label="更新日志详情分类">{[['edit','编辑内容'],['comments','留言',comments.length],['likes','点赞',fresh?.likes.length||0]].map(([id,label,count])=><button key={id} role="tab" aria-selected={tab===id} className={tab===id?'active':''} onClick={()=>setTab(id)}>{label}{count!==undefined&&<small className="tab-count">{count}</small>}</button>)}</div>
      <div className="editor-body ph-editor-body">
        <div className="form-group" hidden={tab!=='edit'}>
          <label><span>标题<span className="ph-required">*</span></span><input aria-label="更新标题" value={draft.title} maxLength={80} placeholder="这次更新带来了什么？" onChange={e=>patch({title:e.target.value})}/></label>
          <label>所属整合包<Select label="所属整合包" value={draft.packId} options={state.packs.map(p=>({value:p.id,label:packLabel(p)+(p.current?'（当前）':'')}))} onChange={packId=>{const pack=state.packs.find(p=>p.id===packId);patch({packId,packName:pack.shortName,packVersion:pack.version});}}/></label>
          <div className="ph-version-fields"><label>整合包名称<input aria-label="版本标识中的整合包名称" maxLength={40} value={draft.packName} placeholder="香草纪元" onChange={e=>patch({packName:e.target.value})}/></label><label>整合包版本<input aria-label="整合包版本" maxLength={32} value={draft.packVersion} placeholder="2.7.1" onChange={e=>patch({packVersion:e.target.value})}/></label><label>本次更新版本<input aria-label="更新版本号" maxLength={32} value={draft.updateVersion} placeholder="1.0" onChange={e=>patch({updateVersion:e.target.value})}/></label></div>
          <p className="ph-version-preview">显示为 <strong>{formatVersion(draft)}</strong></p>
          <label>更新分类<Select label="更新分类" value={draft.category} options={categories.map(c=>({value:c,label:c}))} onChange={category=>patch({category})}/></label>
          <label>摘要<span className="field-help">用于列表展示，选填</span><input aria-label="更新摘要" maxLength={160} value={draft.summary} placeholder="一句话概括这次变化" onChange={e=>patch({summary:e.target.value})}/></label>
          <div><div className="ph-body-label"><span>更新内容 <span className="ph-required">*</span></span><small className={draft.body.length>20000?'ph-length-error':''}>{draft.body.length} / 20000</small></div><ArticleEditor draft={draft} packName={packLabel(state.packs.find(p=>p.id===draft.packId))} onChange={patch}/></div>
          <Switch label="置顶这条更新" description="在所属整合包的更新列表中优先展示。" value={draft.pinned} onChange={pinned=>patch({pinned})}/>
          {fresh?.status==='published'&&<p className="field-help">保存修改后保留原有点赞、留言与发布时间。</p>}
        </div>{tab==='comments'?<Comments entry={draft} state={state} toast={toast}/>:tab==='likes'?<div><div className="ph-likers-heading"><Heart size={16}/><span>{fresh?.likes.length||0} 位玩家为这次更新点赞</span></div>{fresh?.likes.length?[...fresh.likes].sort((a,b)=>(new Date(fresh.likeTimes?.[b]).getTime()||0)-(new Date(fresh.likeTimes?.[a]).getTime()||0)).map(id=><div className="ph-liker" key={id}><PlayerFace id={id}/><div><strong>{PEOPLE[id]||'玩家'}</strong><time dateTime={fresh.likeTimes?.[id] || undefined}>{fresh.likeTimes?.[id]?formatTime(fresh.likeTimes[id]):'时间未记录'}</time></div><Heart size={14}/></div>):<Empty icon={Heart} title="还没有点赞">玩家的支持会记录在这里。</Empty>}</div>:null}
      </div>
    </Modal>
    {deleteOpen&&fresh&&<Modal title="删除这条更新日志？" subtitle="PlayerHub" drawer={false} close={()=>setDeleteOpen(false)} footer={<><button className="button secondary" onClick={()=>setDeleteOpen(false)}>取消</button><button className="button ph-delete-entry" disabled={deleteTitle!==fresh.title} onClick={()=>{if(action({type:'deleteEntry',entryId:fresh.id,confirmTitle:deleteTitle},toast,'更新日志及其互动记录已删除'))close();}}>确认删除</button></>}><div className="editor-body confirm-copy ph-delete-confirm"><p>将删除正文、点赞、留言及回复，删除后无法恢复。</p><p>请输入已保存的日志标题以确认：</p><strong>{fresh.title}</strong><div className="form-group"><label><input aria-label="输入日志标题确认删除" value={deleteTitle} onChange={e=>setDeleteTitle(e.target.value)} placeholder="完整输入上方标题" autoComplete="off" spellCheck={false}/></label></div></div></Modal>}
    {discard&&<Confirm title="放弃未保存的修改？" cancel={()=>setDiscard(false)} confirm={close} label="放弃并关闭"><p>当前编辑内容还没有保存。</p></Confirm>}
  </>;
}

function PackArchives({ state, toast, choose }) {
  return <div className="content-area"><div className="ph-archive-intro"><Archive size={18}/><div><strong>保留每一段旅程的更新</strong><p>管理员始终可查看历史；开启玩家可见后，Hub 才会提供该整合包的切换入口。</p></div></div><div className="table-heading ph-pack-grid"><span>整合包</span><span>日志</span><span>留言</span><span>玩家可见</span><span>操作</span></div><div className="title-rows">{orderedPacks(state).map(pack=>{const entries=state.entries.filter(e=>e.packId===pack.id);return <div className="title-row ph-pack-grid" key={pack.id}><span className="ph-pack-name"><Archive size={19}/><span><strong>{packLabel(pack)}</strong><small>{pack.current?'当前整合包':'历史整合包 · 数据保留'}</small></span></span><span>{entries.length}<small className="ph-muted"> 条</small></span><span>{state.comments.filter(c=>entries.some(e=>e.id===c.entryId)).length}</span><div className="ph-pack-visibility"><button className="switch" role="switch" aria-label={packLabel(pack)+'的玩家可见性'} aria-checked={pack.visible} onClick={()=>action({type:'packVisibility',packId:pack.id,value:!pack.visible},toast,pack.visible?'已向玩家隐藏，历史记录保留':'该整合包的已发布日志已向玩家开放')}><span/></button><span>{pack.visible?'已开放':'仅管理员'}</span></div><button className="button quiet" onClick={()=>choose(pack.id)}>查看日志<CaretRight size={12}/></button></div>;})}</div></div>;
}

function Updates({ state, toast }) {
  const [tab,setTab]=useState('all'),[packId,setPackId]=useState(state.packs.find(p=>p.current).id),[search,setSearch]=useState(''),[density,setDensity]=useState('comfortable'),[page,setPage]=useState(0),[sort,setSort]=useState({key:'time',direction:'desc'}),[editor,setEditor]=useState(null),[withdrawing,setWithdrawing]=useState(null);
  const scoped=state.entries.filter(e=>!packId||e.packId===packId);
  const filtered=scoped.filter(e=>(tab==='all'||e.status===tab)&&`${e.title} ${formatVersion(e)}`.toLowerCase().includes(search.trim().toLowerCase())).sort((a,b)=>{
    const pinned=Number(b.pinned)-Number(a.pinned);if(pinned)return pinned;
    const values=key=> key==='likes'?[a.likes.length,b.likes.length]:key==='comments'?[state.comments.filter(c=>c.entryId===a.id).length,state.comments.filter(c=>c.entryId===b.id).length]:key==='time'?[new Date(a.publishedAt||a.updatedAt).getTime(),new Date(b.publishedAt||b.updatedAt).getTime()]:[a.title,b.title];
    const [av,bv]=values(sort.key);return (typeof av==='number'?av-bv:String(av).localeCompare(String(bv),'zh-CN'))*(sort.direction==='asc'?1:-1)||a.id.localeCompare(b.id);
  });
  useEffect(()=>setPage(0),[tab,packId,search,sort]);
  const rows=filtered.slice(page*12,page*12+12);
  return <div className="ph-list-workspace" data-density={density}>
    <div className="view-bar"><div className="view-tabs" role="tablist" aria-label="更新日志分类">{[['all','全部日志',scoped.length],['published','已发布',scoped.filter(e=>e.status==='published').length],['draft','草稿',scoped.filter(e=>e.status==='draft').length],['withdrawn','已撤下',scoped.filter(e=>e.status==='withdrawn').length],['archives','整合包归档',state.packs.length]].map(([id,label,count])=><button key={id} role="tab" aria-selected={tab===id} className={tab===id?'active':''} onClick={()=>setTab(id)}>{label}<small className="tab-count">{count}</small></button>)}</div><div className="view-actions">{tab!=='archives'&&<><div className="ph-pack-select"><Select label="筛选整合包" value={packId} options={[{value:'',label:'全部整合包'},...orderedPacks(state).map(p=>({value:p.id,label:packLabel(p)}))]} onChange={setPackId}/></div><button className="button quiet" aria-label="切换列表密度" aria-pressed={density==='compact'} onClick={()=>setDensity(d=>d==='comfortable'?'compact':'comfortable')}><Rows size={15}/>{density==='compact'?'紧凑':'舒适'}</button></>}<button className="button primary" onClick={()=>setEditor({entry:null,tab:'edit'})}><Plus size={14}/>新建日志</button></div></div>
    {tab==='archives'?<PackArchives state={state} toast={toast} choose={id=>{setPackId(id);setTab('all');setSearch('');}}/>:<div className="content-area"><div className="list-search-row"><MagnifyingGlass size={15}/><input aria-label="搜索管理端更新日志" placeholder="搜索标题或版本…" value={search} onChange={e=>setSearch(e.target.value)}/><span className="ph-search-note">发布时间 · 北京时间</span></div><div className="table-heading ph-log-grid"><SortLabel label="更新日志" field="title" sort={sort} onChange={setSort}/><span>更新版本</span><span>类型</span><span>发布状态</span><SortLabel label="点赞" field="likes" sort={sort} onChange={setSort}/><SortLabel label="留言" field="comments" sort={sort} onChange={setSort}/><SortLabel label="发布时间" field="time" sort={sort} onChange={setSort}/><span>操作</span></div><div className="title-rows">{rows.length?rows.map(e=>{
      const comments=state.comments.filter(c=>c.entryId===e.id),pending=comments.filter(c=>!c.replies.length).length,pack=state.packs.find(p=>p.id===e.packId);
      return <div className="title-row ph-log-grid" key={e.id}>
        <button className="ph-entry-name" aria-label={'编辑 '+e.title} onClick={()=>setEditor({entry:e,tab:'edit'})}><CategoryIcon category={e.category} size={19}/><span><strong>{e.title}{e.pinned&&<PushPin size={12}/>}</strong><small>{[e.packName||pack.shortName,e.packVersion].filter(Boolean).join(' ')}</small></span></button>
        <span className="ph-version-value">{e.updateVersion || '—'}</span><span className="ph-category-value">{e.category}</span>
        <span className="ph-cell-lines"><span className={'ph-status '+e.status}><i/>{statusNames[e.status]}</span>{!pack.visible&&<small>整合包已隐藏</small>}</span>
        <button className="ph-count-button" aria-label={e.title+'的点赞'} onClick={()=>setEditor({entry:e,tab:'likes'})}><Heart size={13}/>{e.likes.length}</button>
        <button className="ph-count-button" aria-label={e.title+'的留言'} onClick={()=>setEditor({entry:e,tab:'comments'})}><ChatCircleText size={13}/>{comments.length}{pending>0&&<span className="ph-pending-dot" title={pending+'条待回复'}/>}</button>
        <time className="ph-time">{formatTime(e.publishedAt)}</time>
        <div className="ph-row-actions"><IconButton title={undefined} icon={PencilSimple} label={'编辑内容 '+e.title} onClick={()=>setEditor({entry:e,tab:'edit'})}/><IconButton title={undefined} icon={PushPin} label={(e.pinned?'取消置顶 ':'置顶 ')+e.title} aria-pressed={e.pinned} onClick={()=>action({type:'pin',entryId:e.id},toast,e.pinned?'已取消置顶':'已置顶')}/>{e.status==='published'?<IconButton title={undefined} icon={EyeSlash} label={'撤下 '+e.title} onClick={()=>setWithdrawing(e)}/>:<IconButton title={undefined} icon={Eye} label={'发布 '+e.title} onClick={()=>action({type:'status',entryId:e.id,value:'published'},toast,'更新日志已发布')}/>}</div>
      </div>;
    }):<Empty icon={FileText} title="没有匹配的更新日志">调整搜索条件，或创建一条新日志。</Empty>}</div><Pagination page={page} size={12} total={filtered.length} onPage={setPage}/></div>}
    {editor&&<EntryEditor key={editor.entry?.id||'new'} entry={editor.entry} startTab={editor.tab} state={state} toast={toast} close={()=>setEditor(null)}/>}
    {withdrawing&&<Confirm title="撤下这条更新日志？" cancel={()=>setWithdrawing(null)} confirm={()=>{action({type:'status',entryId:withdrawing.id,value:'withdrawn'},toast,'已撤下，正文和互动记录保留');setWithdrawing(null);}} label="撤下日志"><p>玩家将无法查看「{withdrawing.title}」。已有点赞、留言和回复会保留，你可以随时重新发布。</p></Confirm>}
  </div>;
}

function HubSettings({ state, toast, setUnsaved }) {
  const [tab,setTab]=useState('general'),[draft,setDraft]=useState(()=>({...state.settings})),[baseline,setBaseline]=useState(()=>JSON.stringify(state.settings));
  const dirty=JSON.stringify(draft)!==baseline,patch=v=>setDraft(d=>({...d,...v}));
  useEffect(()=>{setUnsaved(dirty);return()=>setUnsaved(false);},[dirty,setUnsaved]);
  useEffect(()=>{const warn=e=>{if(dirty){e.preventDefault();e.returnValue='';}};addEventListener('beforeunload',warn);return()=>removeEventListener('beforeunload',warn);},[dirty]);
  return <><div className="view-bar"><div className="view-tabs" role="tablist" aria-label="Hub设置分类">{[['general','基础设置'],['interaction','互动设置']].map(([id,label])=><button role="tab" aria-selected={tab===id} key={id} className={tab===id?'active':''} onClick={()=>setTab(id)}>{label}</button>)}</div></div><div className="settings-scroll"><form className="settings-content" onSubmit={e=>{e.preventDefault();const next=action({type:'settings',value:draft},toast,'Hub 设置已保存');if(next){setDraft(next.settings);setBaseline(JSON.stringify(next.settings));}}}>
    {tab==='general'?<><section className="settings-section"><h2>页面入口</h2><p className="section-description">控制玩家手册中的更新日志入口。</p><div className="form-group"><Switch label="展示更新日志" description="关闭后，玩家手册不显示该入口。管理端的记录与互动继续保留。" value={draft.updatesEnabled} onChange={updatesEnabled=>patch({updatesEnabled})}/></div></section><section className="settings-section"><h2>整合包列表</h2><p className="section-description">按最新整合包优先排列，可一键隐藏或展示该整合包的全部更新日志。</p><div className="ph-settings-packs">{orderedPacks(state).map(pack=><div className="ph-settings-pack" key={pack.id}><Archive size={19}/><div><strong>{packLabel(pack)}{pack.current&&<small>当前</small>}</strong><span>{state.entries.filter(e=>e.packId===pack.id).length} 条更新日志 · {pack.visible?'玩家可见':'已隐藏'}</span></div><button type="button" className="button secondary" aria-label={(pack.visible?'隐藏 ':'展示 ')+packLabel(pack)+' 的全部更新日志'} onClick={()=>action({type:'packVisibility',packId:pack.id,value:!pack.visible},toast,pack.visible?'已隐藏该整合包的全部更新日志':'已恢复该整合包的已发布日志')} >{pack.visible?<EyeSlash size={14}/>:<Eye size={14}/>} {pack.visible?'全部隐藏':'重新展示'}</button></div>)}</div></section></>:<><section className="settings-section"><h2>点赞与留言</h2><p className="section-description">让玩家表达支持，也为问题与建议保留私密的沟通空间。</p><div className="form-group"><Switch label="允许点赞" description="每位玩家对每条日志最多一个赞，可以取消。" value={draft.likesEnabled} onChange={likesEnabled=>patch({likesEnabled})}/><Switch label="接收新留言" description="暂停后仍可查看历史对话，管理员也可以继续回复。" value={draft.commentsEnabled} onChange={commentsEnabled=>patch({commentsEnabled})}/></div></section><section className="settings-section"><h2>留言限制</h2><p className="section-description">控制单条长度与连续发送间隔。</p><div className="form-columns ph-number-fields"><label>每条留言字数上限<input aria-label="留言字数上限" type="number" required min={50} max={2000} step={1} value={draft.commentLimit} onChange={e=>patch({commentLimit:e.target.value})}/><span className="field-help">50–2000 字</span></label><label>发送间隔（秒）<input aria-label="留言发送间隔" type="number" required min={0} max={300} step={1} value={draft.commentCooldown} onChange={e=>patch({commentCooldown:e.target.value})}/><span className="field-help">0–300 秒</span></label></div></section></>}
    <div className="settings-save"><span>{dirty?'有未保存的修改':'当前设置已保存'}</span><button className="button primary" disabled={!dirty}>保存设置</button></div>
  </form></div></>;
}
export function HubWorkspace({ page, preferences, toast, setUnsaved }) {
  const state=useChangelog();
  return <div className="titles-module playerhub-module" data-theme={preferences.theme}>{page==='hub-settings'?<HubSettings state={state} toast={toast} setUnsaved={setUnsaved}/>:<Updates state={state} toast={toast}/>}</div>;
}
