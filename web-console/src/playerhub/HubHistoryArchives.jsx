import React, { useEffect, useRef, useState } from 'react';
import { Archive, ArrowSquareOut, Check, MagnifyingGlass, PencilSimple, Trash, Users } from '@phosphor-icons/react';
import { Confirm, Empty, ErrorNotice, IconButton, Modal, Pagination, Select, useDebounced } from '../titles/ui.jsx';
import { SortLabel } from '../titles/SortLabel.jsx';
import { PlayerAvatar } from '../PlayerAvatar.jsx';
import { useCommunityRevision, useCommunityResource, useCommunityCatalogue } from './communityData.js';
import './hub-history-archives.css';

const PAGE_SIZE = 20;
const NOTE_LIMIT = 1200;
const SUPPLEMENT_LIMIT = 600;
const timeFormat = new Intl.DateTimeFormat('zh-CN', {
  timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit',
  hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
});

function time(value) {
  if (value == null || value === '' || value === 0) return '—';
  const date = new Date(value);
  return Number.isFinite(date.getTime()) ? timeFormat.format(date) : '—';
}

function PlayerFace({ player, large = false }) {
  return <span className={`pha-player-face ${large ? 'is-large' : ''}`}><PlayerAvatar player={{ ...player, id: player.playerId }} /></span>;
}

function editRevision(record, edit) {
  if (!record || !edit) return undefined;
  if (edit.kind === 'note') return record.note.revision;
  if (edit.kind === 'collections') return record.collections.revision;
  return record.supplements.find(item => item.id === edit.id)?.revision;
}

function MemberMemory({ archive, player, revision, toast, setUnsaved, close, api }) {
  const [supplementPage, setSupplementPage] = useState(0);
  const resource = useCommunityResource(`${player.playerId}/${archive.id}/${supplementPage}`, signal => api.memory(player.playerId, archive.id, supplementPage, signal), revision);
  const [loaded, setLoaded] = useState({ data: null, error: null });
  const [optionsBusy, setOptionsBusy] = useState(false);
  const [optionsError, setOptionsError] = useState(null);
  const optionsPage = useRef(0);
  const optionsGeneration = useRef(0);
  const alive = useRef(true);
  useEffect(() => { alive.current = true; return () => { alive.current = false; }; }, []);
  const [edit, setEdit] = useState(null);
  const [draft, setDraft] = useState('');
  const [selection, setSelection] = useState({ titleEventId: null, fortuneEventId: null });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const [deleting, setDeleting] = useState(null);
  const [confirmClose, setConfirmClose] = useState(false);
  const record = loaded.data;
  const candidates = { data: record?.candidates || [], error: optionsError };
  const items = candidates.data;
  const collectionDraft = edit?.kind === 'collections';
  const dirty = Boolean(edit && (collectionDraft
    ? selection.titleEventId !== edit.selection.titleEventId || selection.fortuneEventId !== edit.selection.fortuneEventId
    : draft !== edit.text));
  const limit = edit?.kind === 'supplement' ? SUPPLEMENT_LIMIT : NOTE_LIMIT;
  const valid = collectionDraft || Boolean(draft.trim() && draft.length <= limit);
  const changedElsewhere = Boolean(edit && editRevision(record, edit) !== edit.expectedRevision);

  function reload() { resource.reload(); }
  useEffect(() => {
    if (resource.error) setLoaded(previous => ({ ...previous, ...([401,403,404].includes(resource.error.status) ? { data: null } : {}), error: resource.error }));
    else if (resource.data && !busy) { setLoaded({ data: resource.data, error: null }); optionsPage.current = 0; optionsGeneration.current++; setOptionsError(null); }
  }, [resource.data, resource.error]);
  async function loadOptions() {
    if (optionsBusy) return;
    const generation = optionsGeneration.current;
    setOptionsBusy(true); setOptionsError(null);
    try {
      const next = await api.memoryOptions(player.playerId, archive.id, optionsPage.current + 1);
      if (!alive.current || generation !== optionsGeneration.current) return;
      optionsPage.current++;
      setLoaded(previous => ({ ...previous, data: { ...previous.data, candidates: [...(previous.data?.candidates || []), ...next.rows], candidatesTotal: next.total, candidatesHasMore: next.hasMore } }));
    } catch (error) { if (alive.current) setOptionsError(error); }
    finally { if (alive.current) setOptionsBusy(false); }
  }
  useEffect(() => { setUnsaved?.(dirty || busy); }, [dirty, busy, setUnsaved]);
  useEffect(() => () => setUnsaved?.(false), [setUnsaved]);
  useEffect(() => {
    if (!dirty) return;
    const leave = event => { event.preventDefault(); event.returnValue = ''; };
    window.addEventListener('beforeunload', leave);
    return () => window.removeEventListener('beforeunload', leave);
  }, [dirty]);

  function beginEdit(kind, supplement) {
    setError(null);
    if (kind === 'collections') {
      const current = { ...record.collections.selection };
      setSelection(current);
      setEdit({ kind, selection: current, expectedRevision: record.collections.revision });
    } else {
      const source = supplement || record.note;
      setDraft(source.text || '');
      setEdit({ kind, id: supplement?.id, text: source.text || '', expectedRevision: source.revision });
    }
  }

  function cancelEdit() {
    setEdit(null);
    setError(null);
  }

  function requestClose() {
    if (busy) return;
    if (dirty) setConfirmClose(true);
    else close();
  }

  async function save() {
    if (!edit || busy || !dirty || !valid || loaded.error || (collectionDraft && candidates.error)) return;
    setBusy(true);
    setError(null);
    try {
      const target = { playerId: player.playerId, archiveId: archive.id, expectedRevision: edit.expectedRevision };
      let next;
      if (edit.kind === 'collections') next = await api.saveCollections({ ...target, selection });
      else if (edit.kind === 'note') next = await api.saveNote({ ...target, text: draft });
      else next = await api.saveSupplement({ ...target, supplementId: edit.id, text: draft });
      if (!alive.current) return;
      setSupplementPage(0); optionsPage.current = 0; optionsGeneration.current++;
      setLoaded({ data: next, error: null });
      setEdit(null);
      toast?.(edit.kind === 'collections' ? '展示选择已保存' : edit.kind === 'note' ? '回忆已保存' : '补记已保存');
    } catch (problem) { if (alive.current) setError(problem); }
    finally { if (alive.current) setBusy(false); }
  }

  async function remove() {
    if (!deleting || busy) return;
    setBusy(true);
    setError(null);
    try {
      const target = { playerId: player.playerId, archiveId: archive.id, expectedRevision: deleting.revision };
      const next = deleting.kind === 'note'
        ? await api.deleteNote(target)
        : await api.deleteSupplement({ ...target, supplementId: deleting.id });
      if (!alive.current) return;
      setSupplementPage(0); optionsPage.current = 0; optionsGeneration.current++;
      setLoaded({ data: next, error: null });
      toast?.(deleting.kind === 'note' ? '回忆原文已删除' : '补记已删除');
      setDeleting(null);
    } catch (problem) { if (alive.current) { setError(problem); setDeleting(null); } }
    finally { if (alive.current) setBusy(false); }
  }

  const selectedTitle = record?.collections.items?.find(item => item.kind === 'title') || items.find(item => item.eventId === record?.collections.selection.titleEventId);
  const selectedFortune = record?.collections.items?.find(item => item.kind === 'fortune') || items.find(item => item.eventId === record?.collections.selection.fortuneEventId);
  const optionsFor = kind => [{ value: '', label: '留空' }, ...[...items, ...(record?.collections.items || [])].filter((item, index, all) => item.kind === kind && all.findIndex(value => value.eventId === item.eventId) === index).map(item => ({
    value: item.eventId,
    label: `${kind === 'title' ? item.item.name : item.item.title} · ${time(item.obtainedAt).split(' ')[0]}`,
  }))];
  const noteEditing = edit?.kind === 'note';

  return <>
    <Modal title={<span className="pha-dialog-identity"><PlayerFace player={player} large /><span>{player.name}</span></span>}
      subtitle={`${archive.name} ${archive.version || ''} · 旅途资料`} close={requestClose} wide busy={busy}
      footer={<>
        <span className="pha-save-status" role="status">{dirty ? '有未保存的修改' : edit ? '当前内容已保存' : '管理员编辑'}</span>
        {edit ? <>
          <button type="button" className="button secondary" disabled={busy} onClick={cancelEdit}>取消编辑</button>
          <button type="button" className="button primary" disabled={busy || !dirty || !valid || Boolean(loaded.error) || (collectionDraft && Boolean(candidates.error))} onClick={save}><Check size={13} />{busy ? '正在保存…' : '保存修改'}</button>
        </> : <button type="button" className="button secondary" disabled={busy} onClick={requestClose}>关闭</button>}
      </>}>
      <div className="editor-body pha-editor-body">
        <div className="pha-member-meta"><span className="mono">{player.playerId}</span><span>{archive.start} — {archive.end || '至今'}</span></div>
        <ErrorNotice error={loaded.error} retry={reload} />
        {resource.loading && <p className="ph-loading" role="status">{record ? '正在刷新…' : '正在读取旅途资料…'}</p>}
        <ErrorNotice error={error} />
        {changedElsewhere && <p className="pha-change-notice" role="status">内容已在其他页面更新，当前草稿已保留。</p>}
        {record && <>
          <section className="pha-detail-section">
            <div className="pha-section-heading"><h3>展示选择</h3>
              {!collectionDraft && <button type="button" className="button quiet" disabled={busy || Boolean(edit) || Boolean(candidates.error)} onClick={() => beginEdit('collections')}><PencilSimple size={13} />编辑展示</button>}
            </div>
            <ErrorNotice error={candidates.error} />
            {collectionDraft ? <div className="form-group pha-collection-fields">
              <label><span>展示称号</span><Select label="展示称号" value={selection.titleEventId || ''} onChange={value => setSelection(previous => ({ ...previous, titleEventId: value || null }))} options={optionsFor('title')} disabled={busy} /></label>
              <label><span>展示签笺</span><Select label="展示签笺" value={selection.fortuneEventId || ''} onChange={value => setSelection(previous => ({ ...previous, fortuneEventId: value || null }))} options={optionsFor('fortune')} disabled={busy} /></label>
              <p className="field-help">候选来自这位玩家在本整合包日期范围内的获得记录。</p>
              {record.candidatesHasMore && <button type="button" className="button quiet" disabled={optionsBusy || busy} onClick={loadOptions}>{optionsBusy ? '正在读取…' : '加载更多获得记录'}<small> {items.length} / {record.candidatesTotal}</small></button>}
            </div> : <dl className="pha-selected-items">
              <div><dt>称号</dt><dd>{selectedTitle?.item.name || '未选择'}{selectedTitle && <small>{time(selectedTitle.obtainedAt)} 获得</small>}</dd></div>
              <div><dt>签笺</dt><dd>{selectedFortune?.item.title || '未选择'}{selectedFortune && <small>{selectedFortune.item.grade} · {time(selectedFortune.obtainedAt)} 获得</small>}</dd></div>
            </dl>}
          </section>

          <section className="pha-detail-section">
            <div className="pha-section-heading"><h3>回忆原文</h3><div className="pha-section-actions">
              {!noteEditing && <button type="button" className="button quiet" disabled={busy || Boolean(edit)} onClick={() => beginEdit('note')}><PencilSimple size={13} />编辑回忆</button>}
              {record.note.text && <IconButton icon={Trash} label="删除回忆原文" title={undefined} disabled={busy || Boolean(edit)} onClick={() => setDeleting({ kind: 'note', text: record.note.text, revision: record.note.revision })} />}
            </div></div>
            {noteEditing ? <div className="form-group">
              <label><span className="pha-input-label">回忆内容<small>{draft.length} / {NOTE_LIMIT}</small></span><textarea aria-label="回忆内容" value={draft} rows={7} maxLength={NOTE_LIMIT} disabled={busy} onChange={event => setDraft(event.target.value)} /></label>
            </div> : <>
              <p className={`pha-memory-text ${record.note.text ? '' : 'is-empty'}`}>{record.note.text || (record.note.deleted ? '回忆原文已删除。' : '尚未写下回忆。')}</p>
              {record.note.updatedAt && <span className="pha-note-date">最近修改 {time(record.note.updatedAt)}</span>}
            </>}
          </section>

          <section className="pha-detail-section">
            <div className="pha-section-heading"><h3>补记<small>{record.supplementsTotal ?? record.supplements.length}</small></h3></div>
            {record.supplements.length ? <div className="pha-supplements">{record.supplements.map(supplement => {
              const active = edit?.kind === 'supplement' && edit.id === supplement.id;
              return <article className="pha-supplement" key={supplement.id}>
                <header><time>{time(supplement.createdAt)}</time><div className="pha-section-actions">
                  {!active && <IconButton icon={PencilSimple} label={`编辑 ${time(supplement.createdAt)} 的补记`} title={undefined} disabled={busy || Boolean(edit)} onClick={() => beginEdit('supplement', supplement)} />}
                  <IconButton icon={Trash} label={`删除 ${time(supplement.createdAt)} 的补记`} title={undefined} disabled={busy || Boolean(edit)} onClick={() => setDeleting({ kind: 'supplement', id: supplement.id, text: supplement.text, revision: supplement.revision })} />
                </div></header>
                {active ? <div className="form-group"><label><span className="pha-input-label">补记内容<small>{draft.length} / {SUPPLEMENT_LIMIT}</small></span><textarea aria-label="补记内容" value={draft} rows={4} maxLength={SUPPLEMENT_LIMIT} disabled={busy} onChange={event => setDraft(event.target.value)} /></label></div> : <p className="pha-memory-text">{supplement.text}</p>}
                {supplement.updatedAt && supplement.updatedAt !== supplement.createdAt && <span className="pha-note-date">最近修改 {time(supplement.updatedAt)}</span>}
              </article>;
            })}</div> : <p className="pha-empty-copy">这段旅途还没有补记。</p>}
            {record.supplementsTotal > 20 && <Pagination page={record.supplementsPage || 0} size={20} total={record.supplementsTotal} onPage={page => { if (!edit && !busy) setSupplementPage(page); }}>编辑完成后可切换补记页</Pagination>}
          </section>
        </>}
      </div>
    </Modal>
    {confirmClose && <Confirm title="放弃未保存的修改？" subtitle="旅途资料" label="放弃修改" cancel={() => setConfirmClose(false)} confirm={() => { setEdit(null); setConfirmClose(false); close(); }}>
      <p>关闭 {player.name} 的旅途资料并放弃当前草稿。</p>
    </Confirm>}
    {deleting && <Confirm title={deleting.kind === 'note' ? '删除这段回忆？' : '删除这条补记？'} subtitle={`${player.name} · ${archive.name}`} danger
      label={deleting.kind === 'note' ? '删除回忆' : '删除补记'} busy={busy} cancel={() => setDeleting(null)} confirm={remove}>
      <p>删除 {player.name} 在 {archive.name} 的{deleting.kind === 'note' ? '回忆原文' : '这条补记'}。</p>
      <p className="pha-delete-excerpt">{deleting.text.length > 140 ? deleting.text.slice(0, 140) + '…' : deleting.text}</p>
    </Confirm>}
  </>;
}

export function HubHistoryArchives({ preferences, toast, setUnsaved, api, revision: publishedRevision, instance = '' }) {
  const revision = useCommunityRevision(api, publishedRevision);
  const [selectedId, setSelectedId] = useState(null);
  const [presence, setPresence] = useState('all');
  const [search, setSearch] = useState('');
  const [sort, setSort] = useState({ key: 'status', direction: 'asc' });
  const [page, setPage] = useState(0);
  const [openedPlayer, setOpenedPlayer] = useState(null);
  const [detailDirty, setDetailDirty] = useState(false);
  const [pendingArchive, setPendingArchive] = useState(null);
  const onUnsaved = React.useCallback(value => { setDetailDirty(value); setUnsaved?.(value); }, [setUnsaved]);
  const rowsRef = useRef(null);
  const archivesResult = useCommunityCatalogue(`archives/${instance}`, (page, signal) => api.archives(instance, page, signal), revision);
  const archives = archivesResult.data?.rows || [];
  const selected = archives.find(archive => archive.id === selectedId) || archives[0];
  const query = useDebounced(search.trim());
  const membersResult = useCommunityResource(selected ? JSON.stringify({ instance, archiveId: selected.id, query, presence, sort, page }) : '', signal => api.players({ instance, archiveId: selected.id, search: query, presence, sort: sort.key === 'lastSeen' ? 'lastLogin' : sort.key, direction: sort.direction, page, size: PAGE_SIZE }, signal), revision);
  const counts = membersResult.data?.presenceCounts;
  const currentPage = page;
  const shown = membersResult.data?.rows || [];
  useEffect(() => { const total = membersResult.data?.total; if (total != null && page > Math.max(0, Math.ceil(total / PAGE_SIZE) - 1)) setPage(Math.max(0, Math.ceil(total / PAGE_SIZE) - 1)); }, [membersResult.data, page]);
  useEffect(() => { setSearch(''); setPresence('all'); setPage(0); setOpenedPlayer(null); }, [selected?.id]);
  useEffect(() => { rowsRef.current?.scrollTo({ top: 0 }); }, [selected?.id, presence, query, currentPage, sort.key, sort.direction]);

  return <div className="titles-module pha-module" data-theme={preferences?.theme || 'dark'}>
    <div className="pha-workspace">
      <aside className="pha-pack-list" aria-label="整合包档案">
        <div className="pha-pack-list-heading"><Archive size={15} /><span>整合包</span><small>{archivesResult.error || !archivesResult.data ? '—' : archivesResult.data.total}</small></div>
        <ErrorNotice error={archivesResult.error} retry={archivesResult.reload} />
        <div className="pha-pack-options">{archives.map(archive => <button type="button" key={archive.id} className={`pha-pack-option ${selected?.id === archive.id ? 'is-selected' : ''}`}
          aria-current={selected?.id === archive.id ? 'true' : undefined} onClick={() => { if (detailDirty && archive.id !== selected?.id) setPendingArchive(archive.id); else setSelectedId(archive.id); }}>
          <div><strong>{archive.name}</strong>{archive.isCurrent && <span className="pha-current-label">当前</span>}</div>
          <span className="pha-pack-version">{archive.version}</span><small>{archive.start} — {archive.end || '至今'}</small>
        </button>)}</div>
        {archivesResult.loading && <p className="ph-loading" role="status">正在读取整合包…</p>}
        {archivesResult.data?.hasMore && <button type="button" className="button quiet" disabled={archivesResult.loading} onClick={archivesResult.loadMore}>加载更多整合包</button>}
      </aside>

      <section className="pha-members" aria-label={selected ? `${selected.name}玩家档案` : '玩家档案'}>
        <div className="view-bar"><div className="view-tabs" role="tablist" aria-label="历史存档玩家分类">
          {[['all', '全部玩家'], ['online', '在线玩家'], ['offline', '离线玩家']].map(([id, label]) => <button key={id} type="button" role="tab" aria-selected={presence === id} className={presence === id ? 'active' : ''}
            onClick={() => { setPresence(id); setPage(0); }}>{label}<small className="tab-count">{membersResult.error || !counts ? '—' : counts[id]}</small></button>)}
        </div><button type="button" className="button quiet" aria-label="切换在线离线排序" onClick={() => { setSort({ key: 'status', direction: sort.key === 'status' && sort.direction === 'asc' ? 'desc' : 'asc' }); setPage(0); }}>
          {sort.key === 'status' ? sort.direction === 'asc' ? '在线优先' : '离线优先' : '在线排序'}
        </button></div>
        <div className="list-search-row"><MagnifyingGlass size={15} /><input aria-label="搜索历史存档玩家" placeholder="搜索玩家名或 UUID…" value={search} onChange={event => { setSearch(event.target.value); setPage(0); }} />
          {search && <button type="button" className="button quiet" onClick={() => { setSearch(''); setPage(0); }}>清除</button>}
        </div>
        <ErrorNotice error={membersResult.error} retry={membersResult.reload} />
        {membersResult.loading && <p className="ph-loading" role="status">正在读取玩家…</p>}
        <div className="table-heading pha-member-grid"><span><SortLabel label="玩家" field="name" sort={sort} onChange={value => { setSort(value); setPage(0); }} /></span>
          <span><SortLabel label="最近登录" field="lastSeen" sort={sort} onChange={value => { setSort(value); setPage(0); }} /></span><span /></div>
        <div ref={rowsRef} className="pha-member-rows">
          {shown.map(player => <div className="player-row pha-member-grid" key={player.playerId}>
            <button type="button" className="player-link" onClick={() => setOpenedPlayer(player)} aria-label={`查看 ${player.name} 的旅途资料`}>
              <PlayerFace player={player} /><span className="player-identity"><strong><span className={`status-dot ${player.isOnline === true ? 'online' : ''}`} role="img" aria-label={player.isOnline === true ? '在线' : player.isOnline === false ? '离线' : '在线状态未确认'} />{player.name}</strong>
                <small className="mono player-uuid" title={player.playerId}>{player.playerId}</small></span>
            </button><time>{time(player.lastLoginAt || player.lastSeen)}</time>
            <IconButton icon={ArrowSquareOut} label={`打开 ${player.name} 的旅途资料`} title={undefined} onClick={() => setOpenedPlayer(player)} />
          </div>)}
          {!shown.length && !membersResult.error && !membersResult.loading && !archivesResult.loading && <Empty icon={Users} title={!selected ? '暂无整合包档案' : query || presence !== 'all' ? '没有匹配的玩家' : '该整合包暂无已知玩家'}>
            {query || presence !== 'all' ? '调整玩家分类或搜索条件。' : '已在此整合包登记的玩家会显示在这里。'}
          </Empty>}
        </div>
        {membersResult.data && <Pagination page={currentPage} size={PAGE_SIZE} total={membersResult.data.total} onPage={setPage}>每页20名玩家</Pagination>}
      </section>
    </div>
    {openedPlayer && selected && <MemberMemory key={`${selected.id}/${openedPlayer.playerId}`} archive={selected} player={openedPlayer} revision={revision} api={api}
      toast={toast} setUnsaved={onUnsaved} close={() => setOpenedPlayer(null)} />}
    {pendingArchive && <Confirm title="放弃未保存的旅途资料？" subtitle="历史存档" label="放弃并切换" cancel={() => setPendingArchive(null)} confirm={() => { setDetailDirty(false); setUnsaved?.(false); setOpenedPlayer(null); setSelectedId(pendingArchive); setPendingArchive(null); }}><p>当前草稿尚未保存。</p></Confirm>}
  </div>;
}

export default HubHistoryArchives;
