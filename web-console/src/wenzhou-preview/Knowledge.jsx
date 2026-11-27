import React, {useState} from 'react';
import {Books, MagnifyingGlass, Rows} from '@phosphor-icons/react';
import {Empty, Modal, Pagination} from '../titles/ui.jsx';
import {SortLabel} from '../titles/SortLabel.jsx';
import {compareValue} from '../titles/sorting.js';
import {formatTime} from './data.js';

const size = 50;
const records = [
  {name:'米纳斯奶酪', id:'braziliandelight:minas_cheese', pack:'示例整合包', facet:'获取',
    summary:'烹饪锅中的奶制品加工顺序', detail:'在农夫乐事烹饪锅中，先制作牛奶锅，再加工为重奶油锅，最后制成奶酪锅。这条笔记记录了工序顺序。',
    keywords:['米纳斯奶酪','奶酪锅','重奶油锅','牛奶锅'], sources:['烹饪锅配方'], created:'2026-11-19T08:15:00+08:00', updated:'2026-11-21T10:51:00+08:00'},
  {name:'附魔金苹果', id:'minecraft:enchanted_golden_apple', pack:'示例整合包', facet:'掉落',
    summary:'古城宝箱包含附魔金苹果候选', detail:'古城宝箱的战利品池可以抽到附魔金苹果。只找到表中条目，不能把这个条件写成每个宝箱必出。',
    keywords:['附魔金苹果','古城','宝箱'],sources:['minecraft:chests/ancient_city'], created:'2026-11-20T20:10:00+08:00', updated:'2026-11-20T20:10:00+08:00'},
  {name:'火把', id:'minecraft:torch', pack:'示例整合包', facet:'获取', summary:'煤炭或木炭均可用于制作火把',
    detail:'在同一列中，将煤炭或木炭放在木棍上方，一次得到四支火把。背包里的合成栏也可以使用。',keywords:['火把','煤炭','木炭'],sources:['minecraft:torch'],created:'2026-11-18T14:32:00+08:00',updated:'2026-11-19T09:08:00+08:00'},
].map(row=>({...row,created:Date.parse(row.created),updated:Date.parse(row.updated)}));

export default function Knowledge() {
  const [query,setQuery] = useState('');
  const [sort,setSort] = useState({key:'created',direction:'desc'});
  const [index,setIndex] = useState(0);
  const [compact,setCompact] = useState(false);
  const [selected,setSelected] = useState(null);
  const term = query.trim().toLowerCase();
  const matches = records.filter(n=>[n.name,n.id,n.pack,n.summary,...n.keywords].some(t=>t.toLowerCase().includes(term)))
    .sort((a,b)=>compareValue(a[sort.key],b[sort.key],sort.direction) || a.id.localeCompare(b.id));
  return <>
    <div className="view-bar"><div className="view-tabs"><button className="active">全部知识<small className="tab-count">{records.length}</small></button></div>
      <div className="view-actions"><button className="button quiet" aria-label="切换列表密度" aria-pressed={compact} onClick={()=>setCompact(!compact)}><Rows size={14}/>{compact?'紧凑':'舒适'}</button></div></div>
    <div className="content-area" data-compact={compact}><div className="list-search-row"><MagnifyingGlass size={15}/><input aria-label="搜索知识记录" placeholder="搜索名称、注册 ID 或关键词…" value={query} onChange={e=>{setQuery(e.target.value);setIndex(0);}}/></div>
      <div className="table-heading wza-grid wza-knowledge">{[['name','知识摘要'],['pack','整合包'],['created','首次保存'],['updated','最近更新']].map(([field,label])=>
        <span key={field}><SortLabel label={label} field={field} sort={sort} onChange={next=>{setSort(next);setIndex(0);}}/></span>)}</div>
      <div className="scrolling-rows">{matches.slice(index*size,(index+1)*size).map(n=><button key={n.id} className="player-row wza-grid wza-knowledge wza-record" onClick={()=>setSelected(n)}>
        <span className="player-identity"><strong>{n.name}</strong><small title={n.summary}>{n.summary}</small></span><span>{n.pack}</span><time>{formatTime(n.created)}</time><time>{formatTime(n.updated)}</time>
      </button>)}{!matches.length&&<Empty icon={Books} title="没有匹配的知识记录"/>}</div></div>
    <Pagination page={index} size={size} total={matches.length} onPage={setIndex}>每页 {size} 项 · 北京时间</Pagination>
    {selected&&<Modal wide title={selected.name} subtitle="WenzhouAI · 知识记录" close={()=>setSelected(null)}>
      <div className="editor-body"><section className="wza-turn"><h3>知识正文</h3><p>{selected.detail}</p></section>
      <dl className="detail-definition">{[['整合包',selected.pack],['注册 ID',selected.id],['类别',selected.facet],['首次保存',formatTime(selected.created)],['最近更新',formatTime(selected.updated)],['关键词',selected.keywords.join('、')],['证据来源',selected.sources.join('、')]].map(([label,value])=><div key={label}><dt>{label}</dt><dd>{value}</dd></div>)}</dl></div>
    </Modal>}
  </>;
}
