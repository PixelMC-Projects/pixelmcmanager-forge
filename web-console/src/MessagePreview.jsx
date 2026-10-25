import React from 'react';
import { parseMessage, resolveMessage } from './messageFormat';
export function MessagePreview({text,values}) {
  return <div className="message-live-preview" aria-label="消息效果预览">{text.split('\n').map((line,lineIndex)=><div key={lineIndex}>{parseMessage(resolveMessage(line,values)).map((run,index)=><span key={index} aria-label={run.style.obfuscated?run.text:undefined} style={{color:run.style.color,fontWeight:run.style.bold?700:400,fontStyle:run.style.italic?'italic':'normal',textDecoration:[run.style.underline&&'underline',run.style.strike&&'line-through'].filter(Boolean).join(' ')||undefined}}>{run.style.obfuscated?Array.from(run.text).map(char=>char===' '?' ':'▧').join(''):run.text}</span>)}{!line&&<br/>}</div>)}</div>;
}
