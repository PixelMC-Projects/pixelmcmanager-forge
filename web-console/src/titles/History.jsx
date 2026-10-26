import React, { useEffect, useState } from 'react';
import { ArrowUUpLeft, CheckCircle, ClockCounterClockwise, MagnifyingGlass, WarningCircle } from '@phosphor-icons/react';
import { api, qs, textOf, time } from './api';
import { Confirm, Empty, ErrorNotice, Modal, Pagination, useDebounced, useRemote } from './ui';

export const actions = { TITLE_SAVE: '编辑称号', TITLE_DELETE: '删除称号', GRANT: '发放称号', REVOKE: '撤销称号', EQUIP: '切换佩戴', PRIVACY: '修改隐私', DISPLAY_POSITION: '修改显示位置', SETTINGS: '修改设置', RELOAD: '导入配置', ADMIN_GRANT: '授予管理员', ADMIN_REVOKE: '撤销管理员', IDENTITY_MIGRATE: '迁移玩家身份', PLAYER_DELETE: '清理玩家资料', UNDO: '撤回改动' };

function valueOf(delta, value, state) {
  const parts = delta.key.split('|');
  if (value == null) return ['O','E','R'].includes(parts[0]) ? '无' : '不存在';
  if (typeof value === 'boolean') return parts[0] === 'H' ? (value ? '已清理' : '可见') : parts.at(-1) === 'issuable' ? (value ? '未绝版' : '已绝版') : (value ? '开启' : '关闭');
  if (parts[0] === 'E') return textOf(state.titles.find(t => t.id === value)) || '原称号资料';
  if (parts[0] === 'P') return value.name;
  if (parts[0] === 'O' && typeof value === 'object') return '已持有 · ' + time(value.acquiredAt);
  if (parts[0] === 'R') return '已领取 · ' + time(value.claimedAt);
  if (value.segments) return value.segments.map(s => s.text).join('');
  if (value.name?.segments) return value.name.segments.map(s => s.text).join('');
  return String(value || '空');
}

function ChangeBody({ unit, state }) {
  const titleRecord = unit.changes.find(delta => delta.key.startsWith('T|') && delta.key.split('|').length === 2);
  if (titleRecord && unit.changes.length > 1) {
    const value = titleRecord.before || titleRecord.after;
    const counts = Object.fromEntries(['O','E','R'].map(kind => [kind, unit.changes.filter(delta => delta.key.startsWith(kind+'|')).length]));
    return <p className="change-bundle">称号「{textOf(value)}」及其关联记录：收藏 {counts.O} 条 · 佩戴 {counts.E} 条 · 领奖 {counts.R} 条{unit.changes.some(delta=>delta.key==='S|reward')?' · 首登奖励设置':''}。</p>;
  }

  if (unit.changes.some(d => d.key.startsWith('P|'))) {
    const counts = Object.fromEntries(['P','O','E','R','H','V'].map(kind => [kind, unit.changes.filter(d => d.key.startsWith(kind + '|') && (kind !== 'V' || d.before != null || d.after != null)).length]));
    return <p className="change-bundle">仅处理该玩家：{[['P','档案'],['O','收藏'],['E','佩戴'],['R','领奖'],['H','历史'],['V','头像']].filter(([kind])=>counts[kind]).map(([kind,label])=>label+' '+counts[kind]).join(' · ')}。其他玩家和其他设置保持当前状态。</p>;
  }
  return <div className="change-values">{unit.changes.map((delta,index)=><div className="change-value-pair" key={index}><div><span>改动前</span><p>{valueOf(delta,delta.before,state)}</p></div><div><span>改动后</span><p>{valueOf(delta,delta.after,state)}</p></div></div>)}</div>;
}

function HistoryDetail({ detail, context, close, name, title }) {
  const { state, revision, writable, refresh, toast } = context;
  const loaded = useRemote(() => api('/history/' + detail.id + '/changes'), [detail.id, revision]);
  const [selected,setSelected] = useState(null), [busy,setBusy] = useState(false), [error,setError] = useState(null);
  const undo = async () => { setBusy(true); setError(null); try { await api('/undo',{change:selected.id,confirm:true}); await refresh(); loaded.reload(); toast('已撤回：'+selected.label); setSelected(null); } catch(err) { setError(err); loaded.reload(); } finally { setBusy(false); } };
  return <><Modal title={actions[detail.action] || detail.action} subtitle={`操作记录 / #${detail.id}`} drawer={false} wide close={close} footer={<button className="button secondary" onClick={close}>关闭</button>}><div className="editor-body"><dl className="detail-list full-details history-detail-summary"><div><dt>时间</dt><dd>{time(detail.at)}</dd></div><div><dt>操作人</dt><dd>{name(detail.actor)}</dd></div><div><dt>目标玩家</dt><dd>{detail.target ? name(detail.target) : '—'}</dd></div><div><dt>称号</dt><dd>{title(detail.title,detail)}</dd></div><div><dt>理由 / 详情</dt><dd className="preserve-lines">{detail.reason || '—'}</dd></div></dl><section className="change-history-section"><div className="section-heading"><span>本次改动</span><span className="small-muted">逐项撤回</span></div><p className="field-help">每次只撤回选中的这一项。执行前会重新检查依赖；有后续变动时停止并说明原因。</p><ErrorNotice error={loaded.error} retry={loaded.reload}/>{loaded.loading && <p className="loading-copy">正在核对当前状态…</p>}{loaded.data?.rows.map(unit=><article className="change-card" key={unit.id}><div className="change-card-heading"><strong>{unit.label}</strong><span className={'change-status '+(unit.undone?'done':unit.allowed?'ready':'blocked')}>{unit.undone?<CheckCircle size={12}/>:unit.allowed?<ArrowUUpLeft size={12}/>:<WarningCircle size={12}/>} {unit.undone?'已撤回':unit.allowed?'可撤回':'存在后续变动'}</span></div><ChangeBody unit={unit} state={state}/>{!unit.undone && unit.conflicts.length>0 && <div className="change-conflicts" role="status"><strong>暂不能撤回</strong><ul>{unit.conflicts.map((message,index)=><li key={index}>{message}</li>)}</ul></div>}<div className="change-card-actions"><button className="button secondary" disabled={!writable || !unit.allowed || busy} onClick={()=>{setSelected(unit);setError(null);}}><ArrowUUpLeft size={13}/>撤回此项</button></div></article>)}{loaded.data && !loaded.data.rows.length && <p className="change-unavailable">这条记录没有可撤回的数据。升级前的旧记录、没有实际改动的操作，以及账号或管理授权操作，不提供网页撤回。</p>}</section></div></Modal>{selected && <Confirm title="撤回这一项改动？" label="确认撤回此项" busy={busy} cancel={()=>setSelected(null)} confirm={undo}><p className="undo-confirm-label">{selected.label}</p><ChangeBody unit={selected} state={state}/><p className="field-help">只处理上面这项改动。若确认期间状态再次变化，会停止执行。</p><ErrorNotice error={error}/></Confirm>}</>;
}

export function History({ context, player, clearPlayer }) {
  const { state, revision } = context;
  const [search, setSearch] = useState(''), [tab, setTab] = useState(0), [page, setPage] = useState(0), [detail, setDetail] = useState(null);
  const query = useDebounced(search), action = tab === 1 ? 'GRANT,REVOKE' : tab === 2 ? 'TITLE_SAVE,TITLE_DELETE,RELOAD' : '';
  useEffect(() => setPage(0), [query, tab, player]);
  const loaded = useRemote(() => api('/history' + qs({ q: query, action, player, page, size: 20 })), [query, action, player, page, revision]);
  const name = id => state.identities?.[id] || (id === 'console' ? '服务器控制台' : id === 'api:first-login' ? '首次登录奖励' : /^[0-9a-f-]{36}$/i.test(id) ? '已清理的玩家' : id || '—');
  const title = (id, row) => {
    const current = textOf(state.titles.find(t => t.id === id));
    if (current) return current;
    const prefix = '删除称号「', end = row?.reason?.lastIndexOf('」，清除') ?? -1;
    if (row?.action === 'TITLE_DELETE' && row.reason.startsWith(prefix) && end >= prefix.length) return row.reason.slice(prefix.length,end);
    return id ? '称号资料不可用' : '—';
  };
  return <><div className="view-bar"><div className="view-tabs" role="tablist" aria-label="记录分类">{['全部记录', '发放与撤销', '称号编辑'].map((label, i) => <button key={label} role="tab" aria-selected={tab === i} className={tab === i ? 'active' : ''} onClick={() => setTab(i)}>{label}<small className="tab-count">{loaded.data?.categoryCounts?.[i] ?? '—'}</small></button>)}</div>{player && <button className="button secondary" onClick={clearPlayer}>玩家：{name(player)} ×</button>}</div><div className="content-area"><div className="list-search-row"><MagnifyingGlass size={15} /><input aria-label="搜索操作记录" placeholder="搜索玩家、称号、理由…" value={search} onChange={e => setSearch(e.target.value)} /></div><ErrorNotice error={loaded.error} retry={loaded.reload} /><div className="table-heading history-grid"><span>操作</span><span>对象 / 称号</span><span>操作人</span><span>时间</span></div><div className="scrolling-rows">{loaded.data?.rows.map(row => <button className="history-row history-grid" key={row.id} onClick={() => setDetail(row)}><span className={'history-action '+(['REVOKE','PLAYER_DELETE','TITLE_DELETE'].includes(row.action) ? 'danger-text' : '')}><ClockCounterClockwise size={16} aria-hidden="true"/>{actions[row.action] || row.action}</span><span className="history-target"><strong>{row.target ? name(row.target) : title(row.title,row)}</strong><small title={row.reason}>{row.title ? title(row.title,row) : row.reason || '—'}</small></span><span>{name(row.actor)}</span><time>{time(row.at)}</time></button>)}{loaded.loading && <div className="inline-loading">正在刷新记录…</div>}{!loaded.loading && !loaded.error && !loaded.data?.rows.length && <Empty icon={ClockCounterClockwise} title="暂无匹配的操作记录">调整查询条件，或在进行管理操作后查看。</Empty>}</div></div><Pagination page={loaded.data?.page || 0} total={loaded.data?.total || 0} onPage={setPage}>记录仅管理员可查 · 打开记录可逐项撤回</Pagination>
    {detail && <HistoryDetail detail={detail} context={context} close={()=>setDetail(null)} name={name} title={title}/>}
  </>;
}
