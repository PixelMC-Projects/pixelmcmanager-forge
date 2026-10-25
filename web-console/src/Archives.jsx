import React, {useEffect, useRef, useState} from 'react';
import {Archive, ArrowRight, CalendarBlank, CaretDown, CaretLeft, CaretRight, Check, Clock, Cube, Database, FileArrowUp, FileText, Info, ShieldCheck, Stack} from '@phosphor-icons/react';
import {usePlatform} from './Platform';
import {request} from './transport';
import './settings.css';

function PanelHeader({icon:Icon, title, description, children}) {
  return <header className="data-panel-heading"><div className="data-panel-label"><Icon size={17}/><h2>{title}</h2></div>{description && <p>{description}</p>}{children}</header>;
}
function InstanceSelect({instances, value, change}) {
  const [open, setOpen] = useState(false), ref = useRef(null);
  useEffect(() => {
    if (!open) return;
    const outside = e => {if (!ref.current?.contains(e.target)) setOpen(false);};
    const escape = e => {if (e.key === 'Escape') {setOpen(false);ref.current?.querySelector('button')?.focus();}};
    document.addEventListener('pointerdown', outside);document.addEventListener('keydown', escape);
    return () => {document.removeEventListener('pointerdown', outside);document.removeEventListener('keydown', escape);};
  }, [open]);
  if (instances.length < 2) return <span className="instance-chip"><Database size={13}/>{instances[0]?.id || '尚无实例'}</span>;
  return <div className="data-instance-select" ref={ref}><button className="instance-chip" type="button" aria-label="选择游戏实例" aria-expanded={open} onClick={() => setOpen(v => !v)}><Database size={13}/>{value}<CaretDown size={12}/></button>{open && <div className="data-instance-options">{instances.map(i => <button type="button" key={i.id} onClick={() => {change(i.id);setOpen(false);}}>{i.id}{i.id === value && <Check size={13}/>}</button>)}</div>}</div>;
}
const civilDate = value => new Intl.DateTimeFormat('sv-SE', {timeZone:'Asia/Shanghai'}).format(new Date(value));
export function Archives({toast}) {
  const p = usePlatform();
  const [name, setName] = useState(''), [version, setVersion] = useState(''), [date, setDate] = useState(() => civilDate(Date.now()));
  const [busy, setBusy] = useState(false), [preview, setPreview] = useState(null), [fileName, setFileName] = useState(''), [historyPage, setHistoryPage] = useState(0);
  const current = p.value?.archives?.find(a => a.status === 'active');
  const instance = p.value?.instances?.find(i => i.id === p.instance), archives = p.value?.archives || [];
  const pageCount = Math.max(1, Math.ceil(archives.length / 4)), page = Math.min(historyPage, pageCount - 1), fileInput = useRef(null);
  useEffect(() => {setPreview(null);setFileName('');setHistoryPage(0);if (fileInput.current) fileInput.current.value = '';}, [p.instance, current?.id]);
  const action = async fn => {setBusy(true);try {await fn();p.refresh();} catch(e) {toast(e.message);} finally {setBusy(false);}};
  const importFile = e => {
    const file = e.target.files[0];e.target.value = '';
    if (!file) return;
    setPreview(null);setFileName(file.name);
    action(async () => {
      if (file.size > 8*1024*1024) throw new Error('文件超过 8 MiB');
      const data = JSON.parse(await file.text());
      const report = await request('/manager/import/preview', {archiveId:current.id, file:data});
      setPreview({data, report, archive:current.id});
    });
  };
  return <div className="data-settings">
    <div className="settings-group-heading"><div><h2>整合包与档案</h2><p>管理当前旅程，或回看已经结束的整合包。</p></div><InstanceSelect instances={p.value?.instances || []} value={p.instance} change={p.chooseInstance}/></div>
    <div className="pack-settings-grid">
      <section className="data-panel current-pack-panel">
        <PanelHeader icon={Cube} title="当前整合包"><span className="data-badge">活动档案</span></PanelHeader>
        {current ? <>
          <div className="active-pack-summary"><div className="pack-symbol"><Cube size={27} weight="light"/></div><div className="active-pack-copy"><h3>{current.name}<span className="pack-version">{current.version || '未标注版本'}</span></h3><span><CalendarBlank size={14}/>{current.startedOn} 开始</span></div></div>
          <p className="data-panel-description">游戏数据持续写入此档案。切换查看历史，不会改变当前的数据归属。</p>
          <footer className="data-panel-footer archive-action"><span><Info size={14}/>{current.finalized ? '最终同步已完成，可以结束本季。' : '正常停服并完成最终同步后可归档。'}</span><button className="button secondary" disabled={busy || !current.finalized} onClick={() => {if (window.confirm('归档后数据将只读。确认已正常停服、完成最终同步，并结束本季？')) action(async () => {await request('/manager/archive', {id:current.id});p.chooseArchive(current.id);toast('已归档');});}}><Archive size={14}/>归档整合包</button></footer>
        </> : <form className="new-pack-form" onSubmit={e => {e.preventDefault();action(async () => {await request('/manager/archives', {instanceId:p.instance, worldId:instance?.worldId, name, version, startedOn:date, active:true});setName('');setVersion('');p.chooseArchive('');toast('活动整合包已建立');});}}>
          <label>整合包名称<input required maxLength={256} placeholder="例如：香草纪元" value={name} onChange={e => setName(e.target.value)}/></label>
          <div className="new-pack-fields"><label>版本<input maxLength={128} placeholder="例如：2.7.1" value={version} onChange={e => setVersion(e.target.value)}/></label><label>开始日期<input required type="date" value={date} onChange={e => setDate(e.target.value)}/></label></div>
          <div className="new-pack-submit"><span>{instance?.worldId ? '新数据将写入此档案。' : '游戏桥接接入后即可创建。'}</span><button className="button primary" disabled={busy || !instance?.worldId}>创建整合包<ArrowRight size={13}/></button></div>
        </form>}
      </section>
      <section className="data-panel pack-history-panel">
        <PanelHeader icon={Stack} title="整合包档案"><span className="data-count">{archives.length} 个档案</span></PanelHeader>
        <div className="pack-history-list">{archives.slice(page*4, page*4+4).map(a => <div className="pack-history-row" key={a.id}><span className="history-pack-symbol"><Archive size={18}/></span><div className="history-pack-copy"><strong>{a.name} <span>{a.version}</span></strong><small>{a.startedOn}{a.endedAt ? ' — '+civilDate(a.endedAt) : ' 起'}</small></div><span className={'data-badge '+(a.status === 'active' ? '' : 'neutral')}>{a.status === 'active' ? '当前' : a.status === 'archived' ? '已归档' : '待导入'}</span><button className="history-open" aria-label={'查看'+a.name+'档案'} onClick={() => p.chooseArchive(a.status === 'active' ? '' : a.id)}><ArrowRight size={16}/></button></div>)}{!archives.length && <div className="data-empty"><Archive size={23}/><span>还没有整合包档案</span><small>创建当前整合包后，会在这里保留记录。</small></div>}</div>
        <footer className="data-panel-footer history-footer"><span>{p.archive ? '当前正在查看历史档案' : '历史档案只读，实时同步保持不变'}</span>{pageCount > 1 && <div className="data-pagination"><button aria-label="上一页档案" disabled={page === 0} onClick={() => setHistoryPage(page-1)}><CaretLeft size={13}/></button><span>{page+1} / {pageCount}</span><button aria-label="下一页档案" disabled={page === pageCount-1} onClick={() => setHistoryPage(page+1)}><CaretRight size={13}/></button></div>}</footer>
      </section>
    </div>
    <div className="settings-group-heading maintenance-heading"><div><h2>数据维护</h2><p>迁移已有统计，或为长期保存的数据创建备份。</p></div></div>
    <div className="maintenance-settings-grid">
      <section className="data-panel migration-panel">
        <PanelHeader icon={FileArrowUp} title="导入旧统计" description="从旧存档导入玩家累计在线时长与登录次数。"/>
        <div className="migration-source"><FileText size={16}/><span>player_stats.json<small>旧存档 / pixelmcmanager</small></span><span className="data-file-limit">JSON · 最大 8 MiB</span></div>
        <p className="data-panel-description">仅限空的活动档案。保留原文件；已有数据不会叠加，也不会补造历史登录记录。</p>
        {preview && <div className="migration-preview"><dl><div><dt>玩家</dt><dd>{preview.report.players}</dd></div><div><dt>累计登录</dt><dd>{preview.report.joinCount}</dd></div><div><dt>导入状态</dt><dd>{preview.report.alreadyImported ? '已导入' : '待确认'}</dd></div></dl><button className="button primary" disabled={busy || preview.report.alreadyImported || preview.archive !== current?.id} onClick={() => action(async () => {await request('/manager/import/confirm', {archiveId:preview.archive, file:preview.data, checksum:preview.report.checksum});setPreview(null);setFileName('');toast('迁移完成');})}>{preview.report.alreadyImported ? '无需重复导入' : '确认导入'}</button></div>}
        <footer className="data-panel-footer"><span className="import-filename" title={fileName}>{fileName || '先选择文件，预览后再确认导入'}</span><input ref={fileInput} className="data-file-input" type="file" accept=".json,application/json" disabled={busy || !current} onChange={importFile}/><button className="button secondary" disabled={busy || !current} onClick={() => fileInput.current?.click()}><FileArrowUp size={14}/>{busy ? '正在处理…' : '选择文件'}</button></footer>
      </section>
      <section className="data-panel retention-panel">
        <PanelHeader icon={ShieldCheck} title="保留与备份" description="性能数据定时清理，玩家与整合包资料长期保留。"/>
        <dl className="retention-list"><div><dt><span className="retention-key"/>TPS / MSPT 历史</dt><dd>本次运行 · 12 小时</dd></div><div><dt><span className="retention-key"/>长 Tick 记录</dt><dd>本次运行 · 24 小时</dd></div><div><dt><span className="retention-key durable"/>玩家、登录、整合包与称号</dt><dd>长期保留</dd></div></dl>
        <p className="data-panel-description backup-cadence"><Clock size={14}/>每 12 小时自动备份，性能缓存不纳入。异地上传使用独立限速任务。</p>
        <footer className="data-panel-footer"><span>新开服后重新记录性能历史</span><button className="button secondary" disabled={busy} onClick={() => action(async () => {const r=await request('/manager/backup', {});toast('本地备份完成：'+r.file);})}><Database size={14}/>{busy ? '正在处理…' : '立即备份'}</button></footer>
      </section>
    </div>
  </div>;
}
