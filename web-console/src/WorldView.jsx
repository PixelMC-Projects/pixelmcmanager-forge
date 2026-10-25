import React, { useEffect, useState } from 'react';
import { ArrowLeft, ArrowRight, ArrowUpRight, ChartLine, CloudCheck, Globe, HardDrives, MagnifyingGlass } from '@phosphor-icons/react';
import { Drawer, Status } from './App';
import { formatDate } from './telemetry';
import { formatBytes } from './worldMetrics';
import { metricTone } from './trendData';
import { dimensionCompare } from './playerSorting';

const PAGE_SIZE = 20;
export function WorldView({ telemetry: t }) {
  const [tab, setTab] = useState('worlds'), [query, setQuery] = useState(''), [page, setPage] = useState(0);
  const [selectedCode, setSelectedCode] = useState(null), [detailTab, setDetailTab] = useState('runtime');
  // Server iteration order is not a display order; keep vanilla worlds pinned.
  const worlds = [...t.worlds].sort((a,b) => dimensionCompare({worldCode:a.code}, {worldCode:b.code})), totals = t.worldTotals;
  const filtered = worlds.filter(world => `${world.name} ${world.code}`.toLocaleLowerCase().includes(query.trim().toLocaleLowerCase()));
  const pages = Math.max(1, Math.ceil(filtered.length / PAGE_SIZE)), current = Math.min(page, pages - 1), rows = filtered.slice(current * PAGE_SIZE, (current + 1) * PAGE_SIZE);
  useEffect(() => setPage(0), [query]);
  // Resolve the selected ID from each new snapshot, including while the drawer is open.
  const selected = worlds.find(world => world.code === selectedCode);
  const value = number => t.stale || !Number.isFinite(number) ? '—' : number.toLocaleString();
  const decimal = (number, digits = 2) => t.stale || !Number.isFinite(number) ? '—' : number.toFixed(digits);
  const allShare = t.latest.mspt > 0 ? totals.tickMs / t.latest.mspt * 100 : null;
  const diskAt = worlds.length ? Math.min(...worlds.map(world => world.storage.measuredAt)) : null;
  const open = code => { setDetailTab('runtime'); setSelectedCode(code); };
  return <div className="detail-scroll dimension-page">
    <div className="view-tabs" role="tablist" aria-label="世界与存档分类">{[['worlds', '世界'], ['saves', '存档与备份']].map(([id, label]) => <button key={id} role="tab" aria-selected={id === tab} className={id === tab ? 'active' : ''} onClick={() => setTab(id)}>{label}</button>)}</div>
    {tab === 'worlds' ? <>
      <div className="dimension-summary-cards">
        <section className="dashboard-card small-stat-card"><span>活动维度</span><strong>{t.stale ? '—' : worlds.length}</strong><p className="dimension-stat-note">当前参与运行的世界</p></section>
        <section className="dashboard-card small-stat-card"><span>维度 Tick 合计</span><strong>{decimal(totals.tickMs)}<small> ms</small></strong><p className="dimension-stat-note">占服务器 Tick {decimal(allShare, 1)}%</p></section>
        <section className="dashboard-card small-stat-card"><span>维度文件体积</span><strong className="dimension-storage-total">{formatBytes(totals.storageBytes)}</strong><p className="dimension-stat-note">{diskAt ? formatDate(diskAt) + ' 统计' : '尚未统计'}</p></section>
        <section className="dashboard-card small-stat-card"><span>强加载 / 弱加载区块</span><strong>{value(totals.strong)}<small> / </small>{value(totals.weak)}</strong><p className="dimension-stat-note">强制加载在列表中单独统计</p></section>
      </div>
      <div className="dimension-list-toolbar"><label className="search-input"><MagnifyingGlass size={15}/><input aria-label="搜索维度" value={query} onChange={event => setQuery(event.target.value)} placeholder="搜索维度名称或 ID…"/></label><span>{filtered.length} 个维度</span><Status tone={t.stale ? 'muted' : 'healthy'}>{t.stale ? '数据未更新' : '运行数据每秒更新'}</Status></div>
      <section className="dimension-list dimension-metrics-list" aria-label="维度列表">
        <div className="dimension-list-head"><span>维度</span><span>Tick 耗时</span><span className="dimension-share-column">耗时占比</span><span>文件体积</span><span className="dimension-chunks-column">强 / 弱加载</span><span className="dimension-entities-column">活动实体</span><span className="dimension-players-column">在线玩家</span><span className="dimension-forced-column">强制加载</span><span/></div>
        {rows.map(world => <button className="dimension-list-row" key={world.code} onClick={() => open(world.code)} aria-label={'查看维度 ' + world.name}>
          <span className="dimension-list-identity"><Globe size={22} className={'dimension-marker ' + world.code.split(':')[1]}/><span><strong>{world.name}</strong><small title={world.code}>{world.code}</small></span></span>
          <span className={'dimension-tick-value metric-tone-' + metricTone('mspt', world.performance.tickMs, t.stale)}>{decimal(world.performance.tickMs)}<small> ms</small></span>
          <span className="dimension-share-column dimension-share-value"><span>{decimal(world.performance.tickShare, 1)}%</span><i aria-hidden="true"><b style={{width: t.stale ? 0 : `${world.performance.tickShare ?? 0}%`}}/></i></span>
          <span className="dimension-disk-value" title={'文件逻辑大小 · ' + formatDate(world.storage.measuredAt) + ' 统计'}>{formatBytes(world.storage.totalBytes)}</span>
          <span className="dimension-chunks-column">{value(world.strong)}<small className="muted"> / </small>{value(world.weak)}</span>
          <span className="dimension-entities-column">{value(world.entities)}</span><span className="dimension-players-column">{value(world.players)}</span><span className="dimension-forced-column">{value(world.forced)}</span><ArrowUpRight size={14}/>
        </button>)}
        {!rows.length && <div className="empty-state"><MagnifyingGlass size={24}/><h3>没有匹配的维度</h3><p>尝试其他名称或维度 ID。</p></div>}
      </section>
      <footer className="dimension-list-footer"><span>共 {filtered.length} 个维度 · 每页 {PAGE_SIZE} 项</span><span>耗时为近 1 秒的每 Tick 平均值</span><div><button className="icon-button" aria-label="上一页维度" disabled={current <= 0} onClick={() => setPage(current - 1)}><ArrowLeft size={15}/></button><span>{current + 1} / {pages}</span><button className="icon-button" aria-label="下一页维度" disabled={current >= pages - 1} onClick={() => setPage(current + 1)}><ArrowRight size={15}/></button></div></footer>
      <div className="dimension-method-note"><ChartLine size={14}/><p>占比以服务器同窗口的 Tick 耗时为分母，维度之外仍有网络、任务等开销。文件体积为低频统计缓存，不随每秒运行数据重新计算。{t.stale && ' 当前连接中断，文件体积保留上次统计。'}</p></div>
    </> : <div className="backup-card-grid">
      <section className="dashboard-card"><header className="card-heading"><h2><HardDrives size={18}/>存档空间</h2><span className="card-tag">文件逻辑大小</span></header><div className="card-number dimension-save-size">{formatBytes(t.saveBytes)}</div><p className="quiet-note">{diskAt ? formatDate(diskAt) + ' 统计' : '尚未统计'} · 磁盘数据独立缓存</p><dl className="card-facts">{worlds.map(world => <div key={world.code}><dt>{world.name}</dt><dd>{formatBytes(world.storage.totalBytes)}</dd></div>)}<div><dt>玩家与共享资料</dt><dd>{formatBytes(t.saveBytes-totals.storageBytes)}</dd></div></dl><p className="quiet-note">维度分项互不重复，不将主世界目录下的其他维度或玩家数据再次计入。</p></section>
      <section className="dashboard-card"><header className="card-heading"><h2><CloudCheck size={18}/>后台数据备份</h2></header><p className="quiet-note">仅备份 Manager 与称号耐久资料，不包含游戏世界或性能缓存。</p><dl className="card-facts"><div><dt>执行周期</dt><dd>每12小时</dd></div><div><dt>最近完成</dt><dd>{formatDate(t.platform.value?.lastBackup)}</dd></div><div><dt>状态</dt><dd>{t.platform.value?.maintenanceError||'无维护错误'}</dd></div></dl></section>
    </div>}
    <p className="quiet-note">主世界、下界、末地固定置顶，其余按完整维度 ID 排序。运行数据每秒更新，文件体积每 30 分钟统计。</p>
    {selectedCode && <Drawer state={{title: selected?.name || '维度详情'}} close={() => setSelectedCode(null)}>{selected ? <>
      <div className="dimension-detail-heading"><Globe size={25} className={'dimension-marker ' + selected.code.split(':')[1]}/><span><strong>{selected.name}</strong><small>{selected.code}</small></span><Status tone={t.stale ? 'muted' : 'healthy'}>{t.stale ? '未更新' : '实时更新'}</Status></div>
      <div className="view-tabs dimension-detail-tabs" role="tablist" aria-label="维度详情分类">{[['runtime', '运行状态'], ['storage', '磁盘空间']].map(([id, label]) => <button key={id} role="tab" aria-selected={id === detailTab} className={detailTab === id ? 'active' : ''} onClick={() => setDetailTab(id)}>{label}</button>)}</div>
      {detailTab === 'runtime' ? <div role="tabpanel" aria-label="维度运行状态">
        <div className="dimension-detail-stats dimension-cost-stats"><div><span>每 Tick 耗时</span><strong className={'metric-tone-' + metricTone('mspt', selected.performance.tickMs, t.stale)}>{decimal(selected.performance.tickMs)}<small> ms</small></strong></div><div><span>占服务器 Tick</span><strong>{decimal(selected.performance.tickShare, 1)}<small>%</small></strong></div></div>
        <dl className="detail-definition dimension-live-details"><div><dt>近 60 秒平均</dt><dd>{decimal(selected.performance.mean60)} ms</dd></div><div><dt>近 60 秒最高秒级均值</dt><dd>{decimal(selected.performance.maxSeconds60)} ms</dd></div><div><dt>强加载 / 弱加载区块</dt><dd>{value(selected.strong)} / {value(selected.weak)}</dd></div><div><dt>已加载区块合计</dt><dd>{value(selected.chunks)}</dd></div><div><dt>活动实体 / 方块实体</dt><dd>{value(selected.entities)} / {value(selected.blockEntities)}</dd></div><div><dt>在线玩家</dt><dd>{value(selected.players)}</dd></div><div><dt>强制加载区块</dt><dd>{value(selected.forced)}</dd></div><div><dt>世界时间</dt><dd>{t.stale ? '—' : selected.time}</dd></div><div><dt>天气</dt><dd>{t.stale ? '—' : selected.weather}</dd></div></dl>
        <p className="quiet-note">耗时是维度在主线程上的经过时间，包含其中的同步等待，不是独占 CPU 用量。近 1 秒均值与列表同步；最高值来自秒级均值，不能代替单 Tick 峰值。强加载指实体 Tick 区块，与强制加载不同。</p>
      </div> : <div role="tabpanel" aria-label="维度磁盘空间">
        <div className="dimension-disk-hero"><HardDrives size={20}/><span>维度文件体积<strong>{formatBytes(selected.storage.totalBytes)}</strong></span><Status tone="muted">缓存</Status></div>
        <dl className="detail-definition dimension-live-details"><div><dt>地形 · region</dt><dd>{formatBytes(selected.storage.terrainBytes)}</dd></div><div><dt>实体 · entities</dt><dd>{formatBytes(selected.storage.entityBytes)}</dd></div><div><dt>兴趣点 · poi</dt><dd>{formatBytes(selected.storage.poiBytes)}</dd></div><div><dt>其他维度文件</dt><dd>{formatBytes(selected.storage.otherBytes)}</dd></div><div><dt>地形区域文件</dt><dd>{selected.storage.regionFileCount} 个</dd></div><div><dt>统计时间</dt><dd>{formatDate(selected.storage.measuredAt)}</dd></div><div><dt>更新周期</dt><dd>每 30 分钟</dd></div></dl>
        <div className="dimension-storage-path"><span>维度目录</span><code>{selected.storage.path}</code></div><p className="quiet-note">展示文件逻辑大小，可能与文件系统实际分配空间不同。只计入本维度文件，排除子维度和共享资料。每 30 分钟在后台限速统计，不在游戏线程或每次网页刷新时递归扫盘。</p>{t.stale && <p className="quiet-note">连接中断；此处保留上次统计缓存，恢复连接后等待新的磁盘统计。</p>}
      </div>}
    </> : <p className="quiet-note">该维度已不在最新数据中，请关闭详情并重新选择。</p>}</Drawer>}
  </div>;
}
