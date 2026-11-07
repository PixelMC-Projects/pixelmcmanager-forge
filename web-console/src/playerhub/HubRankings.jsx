// SPDX-License-Identifier: Apache-2.0
import React from 'react';
import { ArrowClockwise, ArrowLeft, ArrowRight, Clock, Trophy, WarningCircle } from '@phosphor-icons/react';
import { BOARD_DEFINITIONS } from '@hub-leaderboards-contract';
import { Empty, IconButton, Pagination } from '../titles/ui.jsx';
import './hub-rankings.css';

const number = new Intl.NumberFormat('zh-CN');
const date = new Intl.DateTimeFormat('zh-CN', { timeZone:'Asia/Shanghai', year:'numeric', month:'2-digit', day:'2-digit', hour:'2-digit', minute:'2-digit', hour12:false });
export function formatRankingValue(value, unit) {
  if (value === null || value === undefined) return '—';
  if (unit === 'ms') {
    const minutes = Math.floor(value / 60000), hours = Math.floor(minutes / 60);
    return `${number.format(hours)} 小时 ${minutes % 60} 分钟`;
  }
  return `${number.format(value)} 种`;
}
function RankChange({ delta, available }) {
  if (!available || !Number.isSafeInteger(delta) || delta === 0) return null;
  const up = delta > 0, label = `较上次排行${up ? '上升' : '下降'} ${number.format(Math.abs(delta))} 位`;
  return <span className={'ph-rankings-change '+(up ? 'is-up' : 'is-down')} role="img" aria-label={label} title={label}><span aria-hidden="true">{up ? '↑' : '↓'}{number.format(Math.abs(delta))}</span></span>;
}
function UnknownPagination() {
  return <footer className="list-footer"><span>共 — 项</span><span className="footer-note"/><div className="pagination"><IconButton icon={ArrowLeft} label="上一页" disabled/><span className="mono">— / —</span><IconButton icon={ArrowRight} label="下一页" disabled/></div></footer>;
}
const messages = {
  loading:['正在读取排行', '读取完成后会显示当前榜单。'],
  empty:['还没有排行记录', '有符合条件的玩家记录后，会显示在这里。'],
  unsupported:['暂不支持此排行', '当前服务尚未提供此排行，请在服务支持后重新读取。'],
  unavailable:['排行暂不可用', '数据来源暂未连接，请稍后刷新。'],
  error:['排行读取失败', '请检查连接后重新读取。'],
};
export function HubRankings({ preferences, board, onBoard, page, onPage, resource, counts = {}, reload, Avatar, previewControls }) {
  const definition = BOARD_DEFINITIONS.find(item => item.id === board), data = resource.data;
  const status = resource.status || 'loading', loading = status === 'loading';
  const rows = data?.rows || [], hasRows = rows.length > 0, total = data?.total ?? null;
  const comparisonAvailable = typeof data?.revision === 'string' && !!data.revision.trim() && typeof data.comparisonRevision === 'string' && !!data.comparisonRevision.trim() && data.comparisonRevision !== data.revision;
  const scope = definition.scope === 'archive' ? '本存档' : '全局收藏';
  const note = messages[status] || messages.error;
  const notice = resource.error || (status === 'loading' ? '正在刷新排行…' : status === 'unavailable' ? '排行暂不可用。' : '排行读取失败。');
  const changeBoard = id => { onBoard(id); onPage(0); };
  const tabKeys = event => {
    if (!['ArrowLeft','ArrowRight','Home','End'].includes(event.key)) return;
    event.preventDefault();
    const index = BOARD_DEFINITIONS.findIndex(item => item.id === board);
    const next = event.key === 'Home' ? 0 : event.key === 'End' ? 2 : (index + (event.key === 'ArrowRight' ? 1 : 2)) % 3;
    changeBoard(BOARD_DEFINITIONS[next].id);
    event.currentTarget.parentElement.querySelectorAll('[role="tab"]')[next]?.focus();
  };
  return <div className="titles-module playerhub-module ph-rankings-module" data-theme={preferences.theme}>
    <div className="view-bar"><div className="view-tabs" role="tablist" aria-label="排行分类">{BOARD_DEFINITIONS.map(item => <button key={item.id} id={'ranking-tab-'+item.id} type="button" role="tab" aria-selected={board === item.id} aria-controls="ranking-panel" tabIndex={board === item.id ? 0 : -1} className={board === item.id ? 'active' : ''} onKeyDown={tabKeys} onClick={() => changeBoard(item.id)}>{item.label}<small className="tab-count">{counts[item.id] ?? '—'}</small></button>)}</div><button type="button" className="button quiet ph-rankings-refresh" disabled={loading} onClick={reload}><ArrowClockwise size={14}/>刷新</button></div>
    <div className="ph-rankings-caption"><span>{scope}<span className="ph-rankings-dot">·</span>{definition.metricLabel}</span><span>{data?.updatedAt != null ? <><Clock size={12}/><time dateTime={new Date(data.updatedAt).toISOString()}>更新于 {date.format(data.updatedAt)}</time><small>北京时间</small></> : '更新时间未确认'}</span></div>
    {previewControls}
    <section className="ph-rankings-content" id="ranking-panel" role="tabpanel" aria-labelledby={'ranking-tab-'+board} aria-busy={loading}>
      {hasRows && status !== 'ready' && <div className={'ph-rankings-notice '+(status === 'loading' ? '' : 'is-warning')} role={status === 'loading' ? 'status' : 'alert'}><WarningCircle size={15}/><span>{notice} 以下为上次成功读取的结果。</span></div>}
      <div className="ph-rankings-table" role="table" aria-label={definition.label+'排行'} aria-rowcount={total === null ? -1 : total+1}>
        <div className="table-heading ph-rankings-grid" role="row"><span role="columnheader">名次</span><span role="columnheader">玩家</span><span role="columnheader">{definition.metricLabel}</span></div>
        <div className="ph-rankings-rows" role="rowgroup">{hasRows ? rows.map((row, index) => <div key={row.playerId} className="ph-rankings-grid ph-rankings-row" role="row" aria-rowindex={page * (data?.size || 20) + index + 2}><span role="cell" className={'ph-rankings-rank '+(row.rank <= 3 ? 'is-leading' : '')}><span className="ph-rankings-position">{row.rank}</span><RankChange delta={row.rankDelta} available={comparisonAvailable}/></span><span role="cell" className="ph-rankings-player"><span className="ph-rankings-avatar"><Avatar row={row}/></span><span className="ph-rankings-name" title={row.name}>{row.name}</span></span><span role="cell" className="ph-rankings-value">{formatRankingValue(row.value, definition.unit)}</span></div>) : <div role="row" className="ph-rankings-empty"><div role="cell" aria-colspan={3}><Empty icon={Trophy} title={note[0]}>{resource.error || note[1]}</Empty></div></div>}</div>
      </div>
      {total === null ? <UnknownPagination/> : <Pagination page={page} size={data?.size || 20} total={total} onPage={onPage}>{status === 'ready' ? '同值并列，后续名次顺延' : ''}</Pagination>}
    </section>
  </div>;
}
