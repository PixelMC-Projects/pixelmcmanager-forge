// SPDX-License-Identifier: Apache-2.0
// Shared administration presentation; data authority is supplied by the caller.
import React, { useEffect, useId, useRef, useState } from 'react';
import { ArrowSquareOut, ArrowsDownUp, BookBookmark, MagnifyingGlass, PencilSimple, Rows, Users } from '@phosphor-icons/react';
import { Confirm, Empty, ErrorNotice, IconButton, Modal, Pagination, Select, useDebounced } from '../titles/ui.jsx';
import { SortLabel } from '../titles/SortLabel.jsx';
import { PlayerAvatar } from '../PlayerAvatar.jsx';
import { useCommunityRevision, useCommunityResource } from './communityData.js';
import ProfileBackdrop from '@hub-community-art/ProfileBackdrop.jsx';
import './hub-player-cards.css';

const PAGE_SIZE = 20;
const dateTime = new Intl.DateTimeFormat('zh-CN', {
  timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit',
  hour: '2-digit', minute: '2-digit', hour12: false,
});

function timestamp(value) {
  const at = typeof value === 'number' ? value : Date.parse(value);
  return Number.isFinite(at) && at > 0 ? at : null;
}

function formatTime(value) {
  const at = timestamp(value);
  return at === null ? '—' : dateTime.format(at);
}

function editableValues(profile) {
  return {
    bio: profile.bio ?? '',
    background: profile.background,
    profilePattern: profile.profilePattern || 'classic',
    featuredTitleId: profile.featuredTitleId ?? null,
    featuredFortuneDate: profile.featuredFortuneDate ?? null,
    featuredMemoryId: profile.featuredMemoryId ?? null,
  };
}

function selections(profile, options) {
  return {
    background: options.backgrounds.find(item => item.id === profile.background),
    pattern: options.patterns.find(item => item.id === profile.profilePattern),
    title: options.titles.find(item => item.id === profile.featuredTitleId),
    fortune: options.fortunes.find(item => item.date === profile.featuredFortuneDate),
    memory: options.memories.find(item => item.id === profile.featuredMemoryId),
  };
}

function PlayerFace({ player, large = false }) {
  return <span className={`player-avatar hpc-player-face ${large ? 'large-avatar' : ''}`}><PlayerAvatar player={{ ...player, id: player.playerId }} /></span>;
}

function ProfilePreview({ player, draft, options }) {
  const selected = selections(draft, options);
  return <section className="hpc-preview-section" aria-label="玩家名片实时预览">
    <div className="hpc-section-heading"><h3>名片预览</h3><span>当前草稿</span></div>
    <div className="hpc-mini-card" style={{ '--hpc-profile-color': selected.background?.color || '#c8d6ec' }}>
      <div className="hpc-mini-cover">
        <ProfileBackdrop pattern={draft.profilePattern} compact />
        <PlayerFace player={player} large />
        <div className="hpc-mini-identity"><strong>{player.name}</strong><span>{selected.background?.name} · {selected.pattern?.name}</span></div>
      </div>
      <div className="hpc-mini-body">
        <p className={draft.bio.trim() ? 'hpc-mini-quote' : 'hpc-mini-quote is-empty'}>{draft.bio.trim() || '尚未填写语录'}</p>
        <div className="hpc-mini-collections">
          <div><span>精选称号</span><strong className={`hpc-title-color ${selected.title?.style || ''}`}>{selected.title?.name || '不展示'}</strong></div>
          <div><span>精选签笺</span><strong>{selected.fortune?.title || '不展示'}</strong></div>
        </div>
        <div className="hpc-mini-memory"><BookBookmark size={14} /><span>{selected.memory ? `${selected.memory.name} ${selected.memory.version}` : '不展示精选纪念'}</span></div>
      </div>
    </div>
  </section>;
}

function PlayerCardEditor({ player, close, toast, setUnsaved, api, revision }) {
  const formId = useId();
  const [loaded, setLoaded] = useState({ profile: null, options: null, error: null });
  const [draft, setDraft] = useState(null);
  const [loading, setLoading] = useState(true);
  const [optionBusy, setOptionBusy] = useState(null);
  const optionPages = useRef({ titles: 0, fortunes: 0, memories: 0 });
  const generation = useRef(0);
  const alive = useRef(true);
  useEffect(() => { alive.current = true; return () => { alive.current = false; }; }, []);
  const [busy, setBusy] = useState(false);
  const [saveError, setSaveError] = useState(null);
  const [externalChange, setExternalChange] = useState(false);
  const [confirmation, setConfirmation] = useState(null);
  const dirty = Boolean(draft && loaded.profile && JSON.stringify(draft) !== JSON.stringify(editableValues(loaded.profile)));
  const current = useRef();
  current.current = { dirty, busy, revision: loaded.profile?.revision ?? null };

  const loadLatest = async (replace = true) => {
    const request = ++generation.current;
    setLoading(true); setOptionBusy(null);
    try {
      const next = await api.profile(player.playerId);
      if (generation.current !== request || current.current.busy) return;
      if (!replace && current.current.dirty) { setExternalChange(next.profile.revision !== current.current.revision); return; }
      optionPages.current = { titles: 0, fortunes: 0, memories: 0 };
      setLoaded({ ...next, error: null });
      setDraft(editableValues(next.profile));
      setSaveError(null); setExternalChange(false); setConfirmation(null);
    } catch (error) { if (generation.current === request) setLoaded(previous => ({ ...previous, ...([401,403,404].includes(error.status) ? { profile: null, options: null } : {}), error })); }
    finally { if (generation.current === request) setLoading(false); }
  };
  useEffect(() => { loadLatest(false); return () => { generation.current++; }; }, [api, player.playerId, revision]);
  const loadOptions = async kind => {
    if (optionBusy) return;
    const request = generation.current, page = optionPages.current[kind] + 1;
    setOptionBusy(kind); setSaveError(null);
    try {
      const next = await api.profileOptions(player.playerId, kind, page);
      if (request !== generation.current) return;
      optionPages.current[kind] = page;
      setLoaded(previous => ({ ...previous, options: { ...previous.options, [kind]: [...previous.options[kind], ...next.rows].filter((item, index, all) => all.findIndex(value => (kind === 'fortunes' ? value.date === item.date : value.id === item.id)) === index), [kind + 'HasMore']: next.hasMore, [kind + 'Total']: next.total } }));
    } catch (error) { if (request === generation.current) setSaveError(error); }
    finally { if (request === generation.current) setOptionBusy(null); }
  };

  useEffect(() => {
    setUnsaved?.(dirty || busy);
    return () => setUnsaved?.(false);
  }, [dirty, busy, setUnsaved]);

  useEffect(() => {
    if (!dirty) return;
    const warn = event => { event.preventDefault(); event.returnValue = ''; };
    addEventListener('beforeunload', warn);
    return () => removeEventListener('beforeunload', warn);
  }, [dirty]);

  const requestClose = () => { if (!busy) dirty ? setConfirmation('close') : close(); };
  const patch = change => { setDraft(previous => ({ ...previous, ...change })); setSaveError(null); };

  const save = async event => {
    event.preventDefault();
    if (busy || !dirty || !loaded.profile) return;
    current.current.busy = true;
    setBusy(true);
    setSaveError(null);
    try {
      const patch = Object.fromEntries(Object.entries(draft).filter(([key, value]) => value !== editableValues(loaded.profile)[key]));
      const next = await api.saveProfile({ playerId: player.playerId, patch, expectedRevision: loaded.profile.revision });
      if (!alive.current) return;
      const profile = next.profile;
      optionPages.current = { titles: 0, fortunes: 0, memories: 0 };
      setLoaded({ ...next, error: null });
      setDraft(editableValues(profile));
      setExternalChange(false);
      toast?.('玩家名片已保存');
    } catch (error) {
      if (!alive.current) return;
      setSaveError(error);
      try { setExternalChange((await api.profile(player.playerId)).profile.revision !== loaded.profile.revision); } catch { /* Keep the draft available after a read failure. */ }
    } finally {
      if (alive.current) { current.current.busy = false; setBusy(false); }
    }
  };

  const moreOptions = kind => loaded.options?.[kind + 'HasMore'] && <button type="button" className="button quiet" disabled={busy || Boolean(optionBusy)} onClick={() => loadOptions(kind)}>{optionBusy === kind ? '正在读取…' : '加载更多选项'}<small> {loaded.options[kind].length} / {loaded.options[kind + 'Total']}</small></button>;
  const optionRows = (items, label) => [{ value: '', label: '不展示' }, ...items.map(item => ({ value: label === 'fortune' ? item.date : item.id,
    label: label === 'fortune' ? `${item.title} · ${item.grade} · ${item.date}` : label === 'memory' ? `${item.name} ${item.version}` : item.name }))];

  return <>
    <Modal title="玩家名片" subtitle="PlayerHub" wide close={requestClose} busy={busy} footer={<>
      <span>{dirty ? '有未保存的修改' : '当前资料已保存'}</span>
      <button className="button secondary" disabled={busy} onClick={requestClose}>取消</button>
      <button className="button primary" form={formId} type="submit" disabled={busy || !dirty || !loaded.profile || Boolean(loaded.error)}>{busy ? '正在保存…' : '保存名片'}</button>
    </>}>
      <div className="editor-body hpc-editor-body">
        <div className="hpc-editor-identity"><PlayerFace player={player} large /><div><strong><span className={`status-dot ${player.isOnline ? 'online' : ''}`} role="img" aria-label={player.isOnline ? '在线' : '离线'} />{player.name}</strong><span className="mono">{player.playerId}</span></div></div>
        <ErrorNotice error={loaded.error} retry={() => dirty ? setConfirmation('reload') : loadLatest()} />
        <ErrorNotice error={saveError} />
        {loading && <p className="ph-loading" role="status">{draft ? '正在刷新资料…' : '正在读取资料…'}</p>}
        {externalChange && <div className="hpc-conflict" role="status"><span>此玩家资料已在另一页面更新，当前草稿已保留。</span><button className="button secondary" disabled={busy} onClick={() => dirty ? setConfirmation('reload') : loadLatest()}>载入最新资料</button></div>}
        {draft && loaded.options && <>
          <form id={formId} className="form-group hpc-editor-form" onSubmit={save}>
            <section className="hpc-form-section">
              <div className="hpc-section-heading"><h3>展示内容</h3><button type="button" className="button quiet" disabled={busy} onClick={() => patch({ bio: '', featuredTitleId: null, featuredFortuneDate: null, featuredMemoryId: null })}>清空展示内容</button></div>
              <label htmlFor={`${formId}-bio`}><span className="hpc-field-heading">个人语录<small>{draft.bio.length} / 120</small></span><textarea id={`${formId}-bio`} aria-label="个人语录" rows={3} maxLength={120} disabled={busy} value={draft.bio} placeholder="写下玩家想展示的一句话…" onChange={event => patch({ bio: event.target.value })} /></label>
              <div className="hpc-form-columns">
                <div className="field-group"><span className="field-label">背景颜色</span><Select label="背景颜色" value={draft.background} disabled={busy} options={loaded.options.backgrounds.map(item => ({ value: item.id, label: <span className="hpc-color-option"><i style={{ background: item.color }} />{item.name}</span> }))} onChange={background => patch({ background })} /></div>
                <div className="field-group"><span className="field-label">背景图案</span><Select label="背景图案" value={draft.profilePattern} disabled={busy} options={loaded.options.patterns.map(item => ({ value: item.id, label: item.name }))} onChange={profilePattern => patch({ profilePattern })} /></div>
              </div>
            </section>
            <section className="hpc-form-section">
              <div className="hpc-section-heading"><h3>精选展示</h3></div>
              <div className="field-group"><span className="field-label">精选称号</span><Select label="精选称号" value={draft.featuredTitleId ?? ''} disabled={busy} options={optionRows(loaded.options.titles, 'title')} onChange={value => patch({ featuredTitleId: value || null })} />{moreOptions('titles')}</div>
              <div className="field-group"><span className="field-label">精选签笺</span><Select label="精选签笺" value={draft.featuredFortuneDate ?? ''} disabled={busy} options={optionRows(loaded.options.fortunes, 'fortune')} onChange={value => patch({ featuredFortuneDate: value || null })} />{moreOptions('fortunes')}</div>
              <div className="field-group"><span className="field-label">精选纪念</span><Select label="精选纪念" value={draft.featuredMemoryId ?? ''} disabled={busy} options={optionRows(loaded.options.memories, 'memory')} onChange={value => patch({ featuredMemoryId: value || null })} />{moreOptions('memories')}</div>
            </section>
          </form>
          <ProfilePreview player={player} draft={draft} options={loaded.options} />
        </>}
      </div>
    </Modal>
    {confirmation && <Confirm title={confirmation === 'reload' ? '放弃草稿并载入最新资料？' : '放弃未保存的修改？'} subtitle="玩家名片" cancel={() => setConfirmation(null)} label={confirmation === 'reload' ? '放弃并载入' : '放弃并关闭'} confirm={() => confirmation === 'reload' ? loadLatest() : close()}><p>{player.name} 的当前修改尚未保存。</p></Confirm>}
  </>;
}

export function HubPlayerCards({ preferences, toast, setUnsaved, api, revision: publishedRevision }) {
  const revision = useCommunityRevision(api, publishedRevision);
  const [presence, setPresence] = useState('all');
  const [search, setSearch] = useState('');
  const [sort, setSort] = useState({ key: 'status', direction: 'asc' });
  const [page, setPage] = useState(0);
  const [density, setDensity] = useState('comfortable');
  const [selectedPlayer, setSelectedPlayer] = useState(null);
  const query = useDebounced(search.trim().toLocaleLowerCase());

  const resource = useCommunityResource(JSON.stringify({ query, presence, sort, page }), signal => api.players({ search: query, presence, sort: sort.key, direction: sort.direction, page, size: PAGE_SIZE }, signal), revision);
  const data = resource.data;
  useEffect(() => setPage(0), [query, presence, sort]);
  useEffect(() => { if (data && page > Math.max(0, Math.ceil(data.total / PAGE_SIZE) - 1)) setPage(Math.max(0, Math.ceil(data.total / PAGE_SIZE) - 1)); }, [data, page]);
  const counts = data?.presenceCounts;
  const visible = (data?.rows || []).map(player => ({ player, profile: player.profile, selected: {
    title: player.profile.featuredTitle, fortune: player.profile.featuredFortune, memory: player.profile.featuredMemory,
    background: data.backgrounds?.find(item => item.id === player.profile.background), pattern: data.patterns?.find(item => item.id === player.profile.profilePattern),
  } }));
  const currentPlayer = data?.rows.find(row => row.playerId === selectedPlayer?.playerId) || selectedPlayer;
  const clearSearch = () => { setSearch(''); setPresence('all'); };

  return <div className="titles-module hub-playercards-module" data-theme={preferences.theme} data-density={density}>
    <div className="view-bar"><div className="view-tabs" role="tablist" aria-label="名片玩家分类">{[['all', '全部玩家'], ['online', '在线玩家'], ['offline', '离线玩家']].map(([value, label]) => <button key={value} role="tab" aria-selected={presence === value} className={presence === value ? 'active' : ''} onClick={() => setPresence(value)}>{label}<small className="tab-count">{resource.error || !counts ? '—' : counts[value]}</small></button>)}</div><div className="view-actions">
      <button className="button quiet" aria-label="切换在线离线排序" onClick={() => setSort({ key: 'status', direction: sort.key === 'status' && sort.direction === 'asc' ? 'desc' : 'asc' })}><ArrowsDownUp size={14} />{sort.key === 'status' ? (sort.direction === 'desc' ? '离线优先' : '在线优先') : '在线排序'}</button>
      <button className="button quiet" aria-label="切换名片列表密度" aria-pressed={density === 'compact'} onClick={() => setDensity(density === 'compact' ? 'comfortable' : 'compact')}><Rows size={14} />{density === 'compact' ? '紧凑' : '舒适'}</button>
    </div></div>
    <div className="content-area">
      <div className="list-search-row"><MagnifyingGlass size={15} /><input aria-label="搜索名片玩家" value={search} placeholder="搜索玩家名或 UUID…" onChange={event => setSearch(event.target.value)} />{search && <button className="button quiet hpc-clear-search" onClick={() => setSearch('')}>清空</button>}</div>
      <ErrorNotice error={resource.error} retry={resource.reload} />
      {resource.loading && <p className="ph-loading" role="status">{data ? '正在刷新…' : '正在读取玩家…'}</p>}
      <div className="table-heading hpc-grid"><span><SortLabel label="玩家" field="name" sort={sort} onChange={setSort} /></span><span className="hpc-bio-cell"><SortLabel label="个人语录" field="bio" sort={sort} onChange={setSort} /></span><span className="hpc-collection-cell"><SortLabel label="精选收藏" field="collection" sort={sort} onChange={setSort} /></span><span className="hpc-memory-cell"><SortLabel label="精选纪念" field="memory" sort={sort} onChange={setSort} /></span><span className="hpc-login-cell"><SortLabel label="最近登录" field="lastLogin" sort={sort} onChange={setSort} /></span><span className="hpc-row-action">操作</span></div>
      <div className="scrolling-rows">
        {visible.map(row => <div className="player-row hpc-grid" key={row.player.playerId}>
          <button className="player-link" aria-label={`查看玩家名片 ${row.player.name}`} onClick={() => setSelectedPlayer(row.player)}><PlayerFace player={row.player} /><span className="player-identity"><strong><span className={`status-dot ${row.player.isOnline ? 'online' : ''}`} role="img" aria-label={row.player.isOnline ? '在线' : '离线'} /><span className="hpc-player-name">{row.player.name}</span></strong><small className="mono player-uuid" title={row.player.playerId}>{row.player.playerId}</small></span><ArrowSquareOut size={12} /></button>
          <div className="hpc-bio-cell hpc-row-copy"><span className={row.profile?.bio ? '' : 'muted'} title={row.profile?.bio}>{row.error ? '资料读取失败' : row.profile?.bio || '尚未填写'}</span><small>{[row.selected.background?.name, row.selected.pattern?.name].filter(Boolean).join(' · ') || '—'}</small></div>
          <div className="hpc-collection-cell hpc-row-copy"><span className={`hpc-title-color ${row.selected.title?.style || ''}`}>{row.selected.title?.name || '未展示称号'}</span><small>{row.selected.fortune?.title || '未展示签笺'}</small></div>
          <div className="hpc-memory-cell hpc-row-copy"><span>{row.selected.memory?.name || '未展示纪念'}</span><small>{row.selected.memory?.version || '—'}</small></div>
          <time className="hpc-login-cell" dateTime={timestamp(row.player.lastLoginAt) === null ? undefined : new Date(timestamp(row.player.lastLoginAt)).toISOString()}>{formatTime(row.player.lastLoginAt)}</time>
          <IconButton icon={PencilSimple} label={`编辑玩家名片 ${row.player.name}`} className="icon-button hpc-row-action" onClick={() => setSelectedPlayer(row.player)} />
        </div>)}
        {!visible.length && !resource.error && !resource.loading && data && <Empty icon={Users} title={query ? '没有匹配的玩家' : '暂无玩家名片'}>{query ? <button className="button quiet" onClick={clearSearch}>清除搜索与分类</button> : '玩家资料会按 UUID 分别展示。'}</Empty>}
      </div>
    </div>
    {data && <Pagination page={page} size={PAGE_SIZE} total={data.total} onPage={setPage}>每页 20 名玩家 · 北京时间</Pagination>}
    {currentPlayer && <PlayerCardEditor key={currentPlayer.playerId} player={currentPlayer} api={api} revision={revision} close={() => setSelectedPlayer(null)} toast={toast} setUnsaved={setUnsaved} />}
  </div>;
}

export default HubPlayerCards;
