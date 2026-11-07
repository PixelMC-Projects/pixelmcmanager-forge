// PixelMC-Hub · Apache-2.0. Actual shared player article, including the editable body slot.
import React from 'react';
import { CalendarDays, Pin } from './icons.jsx';
import { formatTime, formatVersion } from './model.js';
import { entryDocument } from './richDocument.js';
import CategoryIcon, { categoryArt } from './CategoryIcon.jsx';
import './article.css';

function DocumentNode({node}) {
  if(node.type==='text') return node.marks?.some(m=>m.type==='bold')?<strong>{node.text}</strong>:node.text;
  if(node.type==='hardBreak') return <br/>;
  const Tag={doc:React.Fragment,paragraph:'p',heading:'h3',bulletList:'ul',listItem:'li'}[node.type];
  if(!Tag) return null;
  return <Tag>{node.content?.length?node.content.map((n,i)=><DocumentNode key={i} node={n}/>):node.type==='paragraph'?<br/>:null}</Tag>;
}
export function UpdateBody({ body = '', bodyDocument }) {
  return <div className="hub-update-body"><DocumentNode node={entryDocument({body,bodyDocument})}/></div>;
}
export default function ChangelogArticle({ entry, packName, children, bodySlot }) {
  if (!entry) return null;
  const art=categoryArt[entry.category] || categoryArt['功能更新'];
  return <article className="hub-update-article">
    <header className="hub-update-cover">
      <div className="hub-update-kicker"><span className="hub-update-version">{formatVersion(entry)}</span><span>{entry.category}</span>{entry.pinned && <span className="hub-update-pin"><Pin size={12}/>置顶</span>}</div>
      <span className="hub-update-art" data-category={entry.category} aria-hidden="true" style={{'--art-ink':art.color,'--art-wash':art.wash}}><span/><CategoryIcon category={entry.category} size={34}/></span>
      <h2>{entry.title || '填写更新标题'}</h2>
      {entry.summary && <p>{entry.summary}</p>}
      <div className="hub-update-meta"><span><CalendarDays size={13}/>{formatTime(entry.publishedAt)}</span><span>{entry.packName ? entry.packName+(entry.packVersion || '') : packName}</span></div>
    </header>
    <div className="hub-update-copy">{bodySlot ?? <UpdateBody body={entry.body} bodyDocument={entry.bodyDocument}/>}</div>
    {children}
  </article>;
}
