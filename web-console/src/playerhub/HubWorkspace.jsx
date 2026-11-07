import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Archive, CaretRight, ChatCircleText, Eye, EyeSlash, FileText, Heart, MagnifyingGlass, PencilSimple, Plus, PushPin, Rows, Trash } from '@phosphor-icons/react';
import { Modal, Confirm as SharedConfirm, Switch, Select, IconButton, Pagination, Empty, ErrorNotice, useDebounced } from '../titles/ui.jsx';
import { SortLabel } from '../titles/SortLabel.jsx';
import { PlayerAvatar } from '../PlayerAvatar.jsx';
import { usePlatform } from '../Platform.jsx';
import { newId } from '../transport.js';
import { api, failure, query, useHubResource, useHubPacks } from './api.js';
import { statusNames, categories, formatTime, formatVersion, packLabel, orderedPacks, freshDraft } from './model.js';
import ArticleEditor from './ArticleEditor.jsx';
import CategoryIcon from './CategoryIcon.jsx';
import './hub-workspace.css';

const count = value => value ?? '—';
const Confirm = props => <SharedConfirm {...props} subtitle="PlayerHub"/>;
function ResourceNotice({ resource }) {
  return <><ErrorNotice error={resource.error ? failure(resource.error) : ''} retry={resource.reload}/>{resource.loading && <p className="ph-loading" role="status">{resource.data ? '正在刷新…' : '正在读取…'}</p>}</>;
}
function Face({ id, name }) { return <span className="ph-player-face"><PlayerAvatar player={{ id, name }}/></span>; }
function PackMore({ state, context }) {
  const resource = context.packResource;
  return resource.hasMore || resource.error ? <div className="ph-pack-more"><ErrorNotice error={resource.error ? failure(resource.error) : ''} retry={resource.retry}/><button type="button" className="button quiet" disabled={resource.loading} onClick={resource.error ? resource.retry : resource.load}>{resource.loading ? '正在读取整合包…' : '加载更多整合包'}<small>已显示 {state.packs.length} / {count(state.packsTotal)}</small></button></div> : null;
}
function useDirty(dirty, setUnsaved) {
  useEffect(() => { setUnsaved?.(dirty); return () => setUnsaved?.(false); }, [dirty, setUnsaved]);
  useEffect(() => { const warn = event => { if (dirty) { event.preventDefault(); event.returnValue = ''; } }; addEventListener('beforeunload', warn); return () => removeEventListener('beforeunload', warn); }, [dirty]);
}

function Thread({ comment, entryId, context, reload }) {
  const [text, setText] = useState(''), [busy, setBusy] = useState(false), [deleting, setDeleting] = useState(false), [error, setError] = useState('');
  const [older, setOlder] = useState([]), [olderPage, setOlderPage] = useState(0), [olderBusy, setOlderBusy] = useState(false);
  const operation = useRef(null);
  useEffect(() => { setOlder([]); setOlderPage(0); }, [comment.replyCount, comment.replies?.[0]?.id]);
  const replies = [...older, ...(comment.replies || [])].filter((reply, i, all) => all.findIndex(value => value.id === reply.id) === i).sort((a, b) => a.createdAt - b.createdAt);
  const send = async event => {
    event.preventDefault(); if (busy || !text.trim()) return;
    if (operation.current?.text !== text) operation.current = { text, id: newId() };
    setBusy(true); setError('');
    try { await context.mutate('/comment/reply', { id: entryId, commentId: comment.id, text, operationId: operation.current.id }); setText(''); operation.current = null; context.toast('回复已发送，仅该玩家可见'); reload(); }
    catch (e) { setError(failure(e)); } finally { setBusy(false); }
  };
  const loadOlder = async () => {
    setOlderBusy(true); setError('');
    try { const next = await api(query('/replies', { id: entryId, commentId: comment.id, page: olderPage + 1 })); setOlder(values => [...next.rows, ...values]); setOlderPage(value => value + 1); }
    catch (e) { setError(failure(e)); } finally { setOlderBusy(false); }
  };
  return <section className="ph-comment">
    <header><Face id={comment.authorId} name={comment.authorName}/><div><strong>{comment.authorName || '玩家'}</strong><time>{formatTime(comment.createdAt)}</time></div>{!comment.replyCount && <span className="ph-status pending">待回复</span>}<IconButton icon={Trash} label={'删除 ' + (comment.authorName || '玩家') + ' 的留言'} disabled={!context.writable || busy} onClick={() => setDeleting(true)}/></header>
    <p>{comment.text}</p>
    {replies.length < comment.replyCount && <button className="button quiet ph-load-older" disabled={olderBusy} onClick={loadOlder}>{olderBusy ? '正在读取…' : `查看更早回复（${comment.replyCount - replies.length}）`}</button>}
    {replies.map(reply => <div className="ph-admin-reply" key={reply.id}><div><span>管理员回复</span><time>{formatTime(reply.createdAt)}</time></div><p>{reply.text}</p></div>)}
    <ErrorNotice error={error}/>
    <form className="ph-reply-form" onSubmit={send}><textarea aria-label={'回复 ' + (comment.authorName || '玩家')} placeholder="回复这位玩家…" maxLength={2000} rows={2} value={text} disabled={busy || !context.writable} onChange={event => setText(event.target.value)}/><button className="button secondary" disabled={!text.trim() || busy || !context.writable}>{busy ? '正在发送…' : '发送回复'}</button></form>
    {deleting && <Confirm title="删除这条留言？" danger label="删除留言" busy={busy} cancel={() => setDeleting(false)} confirm={async () => { setBusy(true); setError(''); try { await context.mutate('/comment/delete', { id: entryId, commentId: comment.id }); context.toast('留言及回复已删除'); setDeleting(false); reload(); } catch (e) { setError(failure(e)); setDeleting(false); } finally { setBusy(false); } }}><p>将删除 {comment.authorName} 的留言及其 {comment.replyCount ?? comment.replies?.length ?? 0} 条回复。更新正文和点赞不受影响。</p></Confirm>}
  </section>;
}

function Comments({ detail, filter, setFilter, setPage, context, reload, entryId }) {
  return <>
    <div className="view-tabs ph-comment-tabs" role="tablist" aria-label="留言分类">{[['all', '全部留言'], ['pending', '未回复']].map(([id, label]) => <button key={id} className={filter === id ? 'active' : ''} role="tab" aria-selected={filter === id} onClick={() => { setFilter(id); setPage(0); }}>{label}<small className="tab-count">{count(detail?.counts?.[id])}</small></button>)}</div>
    {detail?.rows.length ? detail.rows.map(comment => <Thread key={comment.id} comment={comment} entryId={entryId} context={context} reload={reload}/>) : <Empty icon={ChatCircleText} title={filter === 'pending' ? '所有留言都已回复' : '还没有留言'}>玩家在 Hub 留言后会显示在这里。</Empty>}
    {detail && <Pagination page={detail.page} size={detail.size} total={detail.total} onPage={setPage}/>}
  </>;
}

function EntryEditor({ entryId, state, context, close, startTab = 'edit' }) {
  const [commentsPage, setCommentsPage] = useState(0), [likesPage, setLikesPage] = useState(0), [filter, setFilter] = useState('all');
  const remote = useHubResource(entryId ? query('/entry', { id: entryId, commentsPage, likesPage, commentsFilter: filter }) : null, context.revision);
  const [draft, setDraft] = useState(() => entryId ? null : freshDraft(state)), [baseline, setBaseline] = useState(() => entryId ? '' : JSON.stringify(freshDraft(state)));
  const [tab, setTab] = useState(startTab), [discard, setDiscard] = useState(false), [reloadRequested, setReloadRequested] = useState(false), [deleteOpen, setDeleteOpen] = useState(false), [deleteTitle, setDeleteTitle] = useState(''), [busy, setBusy] = useState(false), [error, setError] = useState(''), [resetKey, setResetKey] = useState(0);
  const initialised = useRef(false);
  useEffect(() => { if (remote.data && !initialised.current) { initialised.current = true; setDraft(structuredClone(remote.data.entry)); setBaseline(JSON.stringify(remote.data.entry)); } }, [remote.data]);
  const dirty = !!draft && JSON.stringify(draft) !== baseline;
  useDirty(dirty, context.setUnsaved);
  const writable = context.writable && !remote.error;
  const fresh = remote.data?.entry, conflict = !!fresh && !!draft && fresh.revision !== draft.revision;
  const patch = value => setDraft(valueNow => ({ ...valueNow, ...value }));
  const requestClose = () => { if (busy) return; dirty ? setDiscard(true) : close(); };
  const reloadLatest = () => { if (fresh) { setDraft(structuredClone(fresh)); setBaseline(JSON.stringify(fresh)); setResetKey(value => value + 1); setError(''); } setReloadRequested(false); remote.reload(); };
  const save = async publish => {
    if (!draft || busy) return; setBusy(true); setError('');
    try { await context.mutate('/entry/save', { ...draft, expectedRevision: draft.revision || 0, publish }); context.toast(publish ? '更新日志已发布' : fresh?.status === 'published' ? '更新日志已保存' : '草稿已保存'); close(); }
    catch (e) { setError(failure(e)); if (e.status === 409) remote.reload(); } finally { setBusy(false); }
  };
  const valid = draft?.title.trim() && draft?.body.trim() && draft.body.length <= 20000 && !!draft.packId;
  return <>
    <Modal title={entryId ? '更新日志详情' : '新建更新日志'} subtitle="PlayerHub" close={requestClose} wide busy={busy} footer={<><span className="ph-editor-save-status">{dirty ? '有未保存的修改' : fresh ? statusNames[fresh.status] : '填写内容后保存'}</span>{fresh && <button className="button ph-delete-entry" disabled={busy || !writable} onClick={() => { setDeleteTitle(''); setDeleteOpen(true); }}><Trash size={13}/>删除该条</button>}<button className="button secondary" disabled={busy} onClick={requestClose}>关闭</button>{tab === 'edit' && draft && <><button className="button secondary" disabled={!valid || busy || !writable || conflict || (!dirty && !!entryId)} onClick={() => save(false)}>{fresh?.status === 'published' ? '保存修改' : '保存草稿'}</button>{fresh?.status !== 'published' && <button className="button primary" disabled={!valid || busy || !writable || conflict} onClick={() => save(true)}>发布日志</button>}</>}</>}>
      <div className="editor-tabs view-tabs" role="tablist" aria-label="更新日志详情分类">{[['edit', '编辑内容'], ['comments', '留言', fresh?.commentsCount], ['likes', '点赞', fresh?.likesCount]].map(([id, label, number]) => <button key={id} role="tab" aria-selected={tab === id} className={tab === id ? 'active' : ''} disabled={!entryId && id !== 'edit'} onClick={() => setTab(id)}>{label}{id !== 'edit' && <small className="tab-count">{entryId ? count(number) : 0}</small>}</button>)}</div>
      <div className="editor-body ph-editor-body" aria-busy={remote.loading || busy}>
        <ResourceNotice resource={remote}/><ErrorNotice error={error}/>
        {conflict && <div className="error-notice" role="alert"><span>这条日志已被其他操作修改。当前草稿已保留，请读取最新内容后重新编辑。</span><button className="button quiet" onClick={() => dirty ? setReloadRequested(true) : reloadLatest()}>读取最新内容</button></div>}
        {draft && <div className="form-group" hidden={tab !== 'edit'}>
          <label><span>标题<span className="ph-required">*</span></span><input aria-label="更新标题" value={draft.title} maxLength={80} placeholder="这次更新带来了什么？" disabled={busy || !writable} onChange={event => patch({ title: event.target.value })}/></label>
          <label>所属整合包<Select label="所属整合包" value={draft.packId} disabled={busy || !writable} options={orderedPacks(state).map(pack => ({ value: pack.id, label: packLabel(pack) + (pack.current ? '（当前）' : '') }))} onChange={packId => { const pack = state.packs.find(pack => pack.id === packId); patch({ packId, packName: pack.shortName || pack.name, packVersion: pack.version }); }}/></label>
          <PackMore state={state} context={context}/>
          <div className="ph-version-fields"><label>整合包名称<input aria-label="版本标识中的整合包名称" maxLength={40} value={draft.packName} disabled={busy || !writable} onChange={event => patch({ packName: event.target.value })}/></label><label>整合包版本<input aria-label="整合包版本" maxLength={32} value={draft.packVersion} disabled={busy || !writable} onChange={event => patch({ packVersion: event.target.value })}/></label><label>本次更新版本<input aria-label="更新版本号" maxLength={32} value={draft.updateVersion} disabled={busy || !writable} onChange={event => patch({ updateVersion: event.target.value })}/></label></div>
          <p className="ph-version-preview">显示为 <strong>{formatVersion(draft)}</strong></p>
          <label>更新分类<Select label="更新分类" value={draft.category} disabled={busy || !writable} options={categories.map(category => ({ value: category, label: category }))} onChange={category => patch({ category })}/></label>
          <label>摘要<span className="field-help">用于列表展示，选填</span><input aria-label="更新摘要" maxLength={160} value={draft.summary} disabled={busy || !writable} placeholder="一句话概括这次变化" onChange={event => patch({ summary: event.target.value })}/></label>
          <div><div className="ph-body-label"><span>更新内容 <span className="ph-required">*</span></span><small className={draft.body.length > 20000 ? 'ph-length-error' : ''}>{draft.body.length} / 20000</small></div><ArticleEditor key={resetKey} draft={draft} packName={packLabel(state.packs.find(pack => pack.id === draft.packId))} onChange={patch} disabled={busy || !writable}/></div>
          <Switch label="置顶这条更新" description="在所属整合包的更新列表中优先展示。" value={draft.pinned} disabled={busy || !writable} onChange={pinned => patch({ pinned })}/>
          {fresh?.status === 'published' && <p className="field-help">保存修改后保留原有点赞、留言与发布时间。</p>}
        </div>}
        {tab === 'comments' && remote.data && <Comments detail={remote.data.comments} filter={filter} setFilter={setFilter} setPage={setCommentsPage} context={{...context,writable}} reload={remote.reload} entryId={entryId}/>}
        {tab === 'likes' && remote.data && <div><div className="ph-likers-heading"><Heart size={16}/><span>{remote.data.likes.total} 位玩家为这次更新点赞</span></div>{remote.data.likes.rows.length ? remote.data.likes.rows.map(like => <div className="ph-liker" key={like.playerId}><Face id={like.playerId} name={like.name}/><div><strong>{like.name || '玩家'}</strong><time dateTime={like.createdAt == null ? undefined : new Date(like.createdAt).toISOString()}>{like.createdAt == null ? '时间未记录' : formatTime(like.createdAt)}</time></div><Heart size={14}/></div>) : <Empty icon={Heart} title="还没有点赞">玩家的支持会记录在这里。</Empty>}<Pagination page={remote.data.likes.page} size={remote.data.likes.size} total={remote.data.likes.total} onPage={setLikesPage}/></div>}
      </div>
    </Modal>
    {deleteOpen && fresh && <Modal title="删除这条更新日志？" subtitle="PlayerHub" drawer={false} busy={busy} close={() => setDeleteOpen(false)} footer={<><button className="button secondary" disabled={busy} onClick={() => setDeleteOpen(false)}>取消</button><button className="button ph-delete-entry" disabled={deleteTitle !== fresh.title || busy || !writable} onClick={async () => { setBusy(true); setError(''); try { await context.mutate('/entry/delete', { id: fresh.id, confirmTitle: deleteTitle, expectedRevision: fresh.revision }); context.toast('更新日志及其互动记录已删除'); close(); } catch (e) { setError(failure(e)); setDeleteOpen(false); remote.reload(); } finally { setBusy(false); } }}>确认删除</button></>}><div className="editor-body confirm-copy ph-delete-confirm"><p>将删除正文、点赞、留言及回复，删除后无法恢复。</p><p>请输入已保存的日志标题以确认：</p><strong>{fresh.title}</strong><div className="form-group"><label><input aria-label="输入日志标题确认删除" value={deleteTitle} disabled={busy} onChange={event => setDeleteTitle(event.target.value)} placeholder="完整输入上方标题" autoComplete="off" spellCheck={false}/></label></div></div></Modal>}
    {discard && <Confirm title="放弃未保存的修改？" cancel={() => setDiscard(false)} confirm={close} label="放弃并关闭"><p>当前编辑内容还没有保存。</p></Confirm>}
    {reloadRequested && <Confirm title="放弃草稿并读取最新内容？" cancel={() => setReloadRequested(false)} confirm={reloadLatest} label="读取最新内容"><p>当前草稿会被服务器已保存的内容替换。</p></Confirm>}
  </>;
}

function PackArchives({ state, context, choose }) {
  const [busy, setBusy] = useState(''), [error, setError] = useState('');
  const toggle = async pack => { if (busy) return; setBusy(pack.id); setError(''); try { await context.mutate('/pack/visibility', { packId: pack.id, visible: !pack.visible, expectedRevision: pack.revision }); context.toast(pack.visible ? '已向玩家隐藏，历史记录保留' : '该整合包的已发布日志已向玩家开放'); } catch (e) { setError(failure(e)); } finally { setBusy(''); } };
  return <div className="content-area"><ErrorNotice error={error}/><div className="ph-archive-intro"><Archive size={18}/><div><strong>保留每一段旅程的更新</strong><p>管理员始终可查看历史；开启玩家可见后，Hub 才会提供该整合包的切换入口。</p></div></div><div className="table-heading ph-pack-grid"><span>整合包</span><span>日志</span><span>留言</span><span>玩家可见</span><span>操作</span></div><div className="title-rows">{orderedPacks(state).map(pack => <div className="title-row ph-pack-grid" key={pack.id}><span className="ph-pack-name"><Archive size={19}/><span><strong>{packLabel(pack)}</strong><small>{pack.current ? '当前整合包' : '历史整合包 · 数据保留'}</small></span></span><span>{count(pack.entriesCount)}<small className="ph-muted"> 条</small></span><span>{count(pack.commentsCount)}</span><div className="ph-pack-visibility"><button className="switch" role="switch" aria-label={packLabel(pack) + '的玩家可见性'} disabled={!!busy || !context.writable} aria-checked={pack.visible} onClick={() => toggle(pack)}><span/></button><span>{pack.visible ? '已开放' : '仅管理员'}</span></div><button className="button quiet" onClick={() => choose(pack.id)}>查看日志<CaretRight size={12}/></button></div>)}</div><PackMore state={state} context={context}/></div>;
}

function Updates({ state, context }) {
  const [tab, setTab] = useState('all'), [packId, setPackId] = useState(state.selectedPackId || state.packs.find(pack => pack.current)?.id || ''), [search, setSearch] = useState(''), [density, setDensity] = useState('comfortable'), [page, setPage] = useState(0), [sort, setSort] = useState({ key: 'time', direction: 'desc' }), [editor, setEditor] = useState(null), [withdrawing, setWithdrawing] = useState(null), [busy, setBusy] = useState(''), [error, setError] = useState('');
  const debouncedSearch = useDebounced(search);
  const resource = useHubResource(tab === 'archives' ? null : query('/entries', { packId, status: tab, search: debouncedSearch.trim(), sort: sort.key, direction: sort.direction, page, size: 12 }), context.revision);
  const data = resource.data;
  const changeTab = value => { setTab(value); setPage(0); };
  const listMutation = async (path, entry, payload, message) => { if (busy) return; setBusy(entry.id); setError(''); try { await context.mutate(path, { id: entry.id, expectedRevision: entry.revision, ...payload }); context.toast(message); resource.reload(); return true; } catch (e) { setError(failure(e)); if (e.status === 409) resource.reload(); return false; } finally { setBusy(''); } };
  const writable = context.writable && !resource.error && !resource.loading;
  return <div className="ph-list-workspace" data-density={density}>
    <div className="view-bar"><div className="view-tabs" role="tablist" aria-label="更新日志分类">{[['all', '全部日志'], ['published', '已发布'], ['draft', '草稿'], ['withdrawn', '已撤下'], ['archives', '整合包归档']].map(([id, label]) => <button key={id} role="tab" aria-selected={tab === id} className={tab === id ? 'active' : ''} onClick={() => changeTab(id)}>{label}<small className="tab-count">{id === 'archives' ? count(state.packsTotal ?? state.packs.length) : count(data?.counts?.[id])}</small></button>)}</div><div className="view-actions">{tab !== 'archives' && <><div className="ph-pack-select"><Select label="筛选整合包" value={packId} options={[{ value: '', label: '全部整合包' }, ...orderedPacks(state).map(pack => ({ value: pack.id, label: packLabel(pack) }))]} onChange={value => { setPackId(value); setPage(0); }}/></div><button className="button quiet" aria-label="切换列表密度" aria-pressed={density === 'compact'} onClick={() => setDensity(value => value === 'comfortable' ? 'compact' : 'comfortable')}><Rows size={15}/>{density === 'compact' ? '紧凑' : '舒适'}</button></>}<PackMore state={state} context={context}/><button className="button primary" disabled={!context.writable || !state.packs.length} onClick={() => setEditor({ id: null, tab: 'edit' })}><Plus size={14}/>新建日志</button></div></div>
    {tab === 'archives' ? <PackArchives state={state} context={context} choose={id => { setPackId(id); setTab('all'); setSearch(''); setPage(0); }}/> : <div className="content-area"><ResourceNotice resource={resource}/><ErrorNotice error={error}/><div className="list-search-row"><MagnifyingGlass size={15}/><input aria-label="搜索管理端更新日志" placeholder="搜索标题或版本…" value={search} onChange={event => { setSearch(event.target.value); setPage(0); }}/><span className="ph-search-note">发布时间 · 北京时间</span></div><div className="table-heading ph-log-grid"><SortLabel label="更新日志" field="title" sort={sort} onChange={value => { setSort(value); setPage(0); }}/><span>更新版本</span><span>类型</span><span>发布状态</span><SortLabel label="点赞" field="likes" sort={sort} onChange={value => { setSort(value); setPage(0); }}/><SortLabel label="留言" field="comments" sort={sort} onChange={value => { setSort(value); setPage(0); }}/><SortLabel label="发布时间" field="time" sort={sort} onChange={value => { setSort(value); setPage(0); }}/><span>操作</span></div><div className="title-rows" aria-busy={resource.loading}>{data?.rows.length ? data.rows.map(entry => { const pack = state.packs.find(pack => pack.id === entry.packId); return <div className="title-row ph-log-grid" key={entry.id}>
      <button className="ph-entry-name" aria-label={'编辑 ' + entry.title} onClick={() => setEditor({ id: entry.id, tab: 'edit' })}><CategoryIcon category={entry.category} size={19}/><span><strong>{entry.title}{entry.pinned && <PushPin size={12}/>}</strong><small>{[entry.packName || pack?.shortName || pack?.name, entry.packVersion].filter(Boolean).join(' ')}</small></span></button>
      <span className="ph-version-value">{entry.updateVersion || '—'}</span><span className="ph-category-value">{entry.category}</span><span className="ph-cell-lines"><span className={'ph-status ' + entry.status}><i/>{statusNames[entry.status]}</span>{pack?.visible === false && <small>整合包已隐藏</small>}</span>
      <button className="ph-count-button" aria-label={entry.title + '的点赞'} onClick={() => setEditor({ id: entry.id, tab: 'likes' })}><Heart size={13}/>{count(entry.likesCount)}</button><button className="ph-count-button" aria-label={entry.title + '的留言'} onClick={() => setEditor({ id: entry.id, tab: 'comments' })}><ChatCircleText size={13}/>{count(entry.commentsCount)}</button><time className="ph-time">{formatTime(entry.publishedAt)}</time>
      <div className="ph-row-actions"><IconButton title={undefined} icon={PencilSimple} label={'编辑内容 ' + entry.title} onClick={() => setEditor({ id: entry.id, tab: 'edit' })}/><IconButton title={undefined} icon={PushPin} label={(entry.pinned ? '取消置顶 ' : '置顶 ') + entry.title} aria-pressed={entry.pinned} disabled={!!busy || !writable} onClick={() => listMutation('/entry/pin', entry, { pinned: !entry.pinned }, entry.pinned ? '已取消置顶' : '已置顶')}/>{entry.status === 'published' ? <IconButton title={undefined} icon={EyeSlash} label={'撤下 ' + entry.title} disabled={!!busy || !writable} onClick={() => setWithdrawing(entry)}/> : <IconButton title={undefined} icon={Eye} label={'发布 ' + entry.title} disabled={!!busy || !writable} onClick={() => listMutation('/entry/status', entry, { status: 'published' }, '更新日志已发布')}/>}</div>
    </div>; }) : !resource.loading && !resource.error ? <Empty icon={FileText} title="没有匹配的更新日志">调整搜索条件，或创建一条新日志。</Empty> : null}</div>{data && <Pagination page={data.page} size={data.size} total={data.total} onPage={setPage}/>}</div>}
    {editor && <EntryEditor key={editor.id || 'new'} entryId={editor.id} startTab={editor.tab} state={state} context={context} close={() => setEditor(null)}/>}
    {withdrawing && <Confirm title="撤下这条更新日志？" busy={!!busy} cancel={() => setWithdrawing(null)} confirm={async () => { if (await listMutation('/entry/status', withdrawing, { status: 'withdrawn' }, '已撤下，正文和互动记录保留')) setWithdrawing(null); }} label="撤下日志"><p>玩家将无法查看「{withdrawing.title}」。已有点赞、留言和回复会保留，你可以随时重新发布。</p><ErrorNotice error={error}/></Confirm>}
  </div>;
}

function HubSettings({ state, context }) {
  const [tab, setTab] = useState('general'), [draft, setDraft] = useState(() => ({ ...state.settings })), [baseline, setBaseline] = useState(() => JSON.stringify(state.settings)), [busy, setBusy] = useState(false), [packBusy, setPackBusy] = useState(''), [error, setError] = useState('');
  const dirty = JSON.stringify(draft) !== baseline, conflict = state.settings.revision !== draft.revision;
  const dirtyRef = useRef(dirty); dirtyRef.current = dirty;
  useDirty(dirty, context.setUnsaved);
  useEffect(() => { if (!dirtyRef.current) { setDraft({ ...state.settings }); setBaseline(JSON.stringify(state.settings)); } }, [state.settings]);
  const patch = value => setDraft(current => ({ ...current, ...value }));
  return <><div className="view-bar"><div className="view-tabs" role="tablist" aria-label="Hub设置分类">{[['general', '基础设置'], ['interaction', '互动设置']].map(([id, label]) => <button role="tab" aria-selected={tab === id} key={id} className={tab === id ? 'active' : ''} onClick={() => setTab(id)}>{label}</button>)}</div></div><div className="settings-scroll"><form className="settings-content" onSubmit={async event => { event.preventDefault(); if (busy) return; setBusy(true); setError(''); try { const next = await context.mutate('/settings', { ...draft, commentLimit: Number(draft.commentLimit), commentCooldown: Number(draft.commentCooldown), expectedRevision: draft.revision }); setDraft(next.settings); setBaseline(JSON.stringify(next.settings)); context.toast('Hub 设置已保存'); } catch (e) { setError(failure(e)); } finally { setBusy(false); } }}>
    <ErrorNotice error={error}/>{conflict && <div className="error-notice" role="alert"><span>服务器设置已有更新，当前草稿已保留。</span><button type="button" className="button quiet" onClick={() => { setDraft({ ...state.settings }); setBaseline(JSON.stringify(state.settings)); setError(''); }}>读取最新设置</button></div>}
    {tab === 'general' ? <><section className="settings-section"><h2>页面入口</h2><p className="section-description">控制玩家手册中的更新日志入口。</p><div className="form-group"><Switch label="展示更新日志" description="关闭后，玩家手册不显示该入口。管理端的记录与互动继续保留。" value={draft.updatesEnabled} disabled={busy || !context.writable} onChange={updatesEnabled => patch({ updatesEnabled })}/></div></section><section className="settings-section"><h2>整合包列表</h2><p className="section-description">按最新整合包优先排列，可一键隐藏或展示该整合包的全部更新日志。</p><div className="ph-settings-packs">{orderedPacks(state).map(pack => <div className="ph-settings-pack" key={pack.id}><Archive size={19}/><div><strong>{packLabel(pack)}{pack.current && <small>当前</small>}</strong><span>{count(pack.entriesCount)} 条更新日志 · {pack.visible ? '玩家可见' : '已隐藏'}</span></div><button type="button" className="button secondary" disabled={!!packBusy || !context.writable} aria-label={(pack.visible ? '隐藏 ' : '展示 ') + packLabel(pack) + ' 的全部更新日志'} onClick={async () => { setPackBusy(pack.id); setError(''); try { await context.mutate('/pack/visibility', { packId: pack.id, visible: !pack.visible, expectedRevision: pack.revision }); context.toast(pack.visible ? '已隐藏该整合包的全部更新日志' : '已恢复该整合包的已发布日志'); } catch (e) { setError(failure(e)); } finally { setPackBusy(''); } }}>{pack.visible ? <EyeSlash size={14}/> : <Eye size={14}/>} {pack.visible ? '全部隐藏' : '重新展示'}</button></div>)}</div><PackMore state={state} context={context}/></section></> : <><section className="settings-section"><h2>点赞与留言</h2><p className="section-description">让玩家表达支持，也为问题与建议保留沟通空间。</p><div className="form-group"><Switch label="允许点赞" description="每位玩家对每条日志最多一个赞，可以取消。" value={draft.likesEnabled} disabled={busy || !context.writable} onChange={likesEnabled => patch({ likesEnabled })}/><Switch label="接收新留言" description="暂停后仍可查看历史对话，管理员也可以继续回复。" value={draft.commentsEnabled} disabled={busy || !context.writable} onChange={commentsEnabled => patch({ commentsEnabled })}/></div></section><section className="settings-section"><h2>留言限制</h2><p className="section-description">控制单条长度与连续发送间隔。</p><div className="form-columns ph-number-fields"><label>每条留言字数上限<input aria-label="留言字数上限" type="number" required min={50} max={2000} step={1} disabled={busy || !context.writable} value={draft.commentLimit} onChange={event => patch({ commentLimit: event.target.value })}/><span className="field-help">50–2000 字</span></label><label>发送间隔（秒）<input aria-label="留言发送间隔" type="number" required min={0} max={300} step={1} disabled={busy || !context.writable} value={draft.commentCooldown} onChange={event => patch({ commentCooldown: event.target.value })}/><span className="field-help">0–300 秒</span></label></div></section></>}
    <div className="settings-save"><span>{dirty ? '有未保存的修改' : '当前设置已保存'}</span><button className="button primary" disabled={!dirty || busy || conflict || !context.writable}>{busy ? '正在保存…' : '保存设置'}</button></div>
  </form></div></>;
}

export function HubWorkspace({ page, preferences, toast, setUnsaved }) {
  const platform = usePlatform(), [localRevision, setLocalRevision] = useState(0);
  const revision = `${platform.value?.playerHubRevision || ''}:${localRevision}`;
  const snapshot = useHubResource('/state', revision);
  const packResource = useHubPacks(snapshot.data, revision);
  const mutate = useCallback(async (path, body) => { try { const next = await api(path, body); setLocalRevision(value => value + 1); return next; } catch (error) { if (error.status === 409) setLocalRevision(value => value + 1); throw error; } }, []);
  const context = { revision, mutate, toast, setUnsaved, packResource, writable: !!snapshot.data && !snapshot.error };
  const state = snapshot.data ? { ...snapshot.data, packs: packResource.packs } : null;
  return <div className="titles-module playerhub-module" data-theme={preferences.theme}><ResourceNotice resource={snapshot}/>{state ? page === 'hub-settings' ? <HubSettings state={state} context={context}/> : <Updates state={state} context={context}/> : !snapshot.loading && <p className="quiet-note">PlayerHub 数据当前不可用。</p>}</div>;
}
