import React, { useEffect, useId, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { EditorContent, useEditor, useEditorState } from '@tiptap/react';
import { Extension, Mark, Node, mergeAttributes } from '@tiptap/core';
import StarterKit from '@tiptap/starter-kit';
import { Color, TextStyle } from '@tiptap/extension-text-style';
import { Plugin } from '@tiptap/pm/state';
import { closeHistory } from '@tiptap/pm/history';
import { ArrowCounterClockwise, ArrowClockwise, CaretDown, Info } from '@phosphor-icons/react';
import { messagePlaceholders } from './messageFormat';
import { gradientColors, messageDocument, normalizeColor, serializeMessage } from './richMessage';
import { MessageColorField } from './MessageColorField';

const Placeholder=Node.create({
  name:'messagePlaceholder',inline:true,group:'inline',atom:true,selectable:true,
  addOptions(){return {allowed:[],values:()=>({})};},
  addAttributes(){return {token:{default:'',rendered:false}};},
  parseHTML(){return [{tag:'span[data-message-placeholder]',getAttrs:element=>this.options.allowed.includes(element.getAttribute('data-message-placeholder'))?{token:element.getAttribute('data-message-placeholder')}:false}];},
  renderHTML({node,HTMLAttributes}){const token=node.attrs.token,example=this.options.values()[token.slice(1,-1)];return ['span',mergeAttributes(HTMLAttributes,{'data-message-placeholder':token,class:'message-placeholder-node',contenteditable:'false',title:token}),String(example??token)];},
  renderText({node}){return node.attrs.token;},
});
const Obfuscated=Mark.create({name:'obfuscated',parseHTML(){return [{tag:'span[data-message-obfuscated]'}];},renderHTML(){return ['span',{'data-message-obfuscated':'',class:'message-obfuscated'},0];}});
const BoundedContent=Extension.create({name:'boundedMessage',addProseMirrorPlugins(){return [new Plugin({filterTransaction:transaction=>!transaction.docChanged||transaction.doc.content.size<=6000})];}});

export function MessageEditor({label,value,onChange,scope='global',values}) {
  const inputId=useId(),host=useRef(null),emitted=useRef(value),callbacks=useRef({onChange,values});callbacks.current={onChange,values};
  const [color,setColor]=useState('#e4e4e7'),[from,setFrom]=useState('#64d8ff'),[to,setTo]=useState('#f7d774'),[picker,setPicker]=useState(null),[notice,setNotice]=useState(0);
  useEffect(()=>{if(!notice)return;const timer=setTimeout(()=>setNotice(0),2700);return()=>clearTimeout(timer);},[notice]);
  const editor=useEditor({
    extensions:[StarterKit.configure({blockquote:false,heading:false,bulletList:false,orderedList:false,listItem:false,listKeymap:false,code:false,codeBlock:false,link:false,horizontalRule:false,trailingNode:false,undoRedo:{depth:100,newGroupDelay:500}}),TextStyle,Color,Obfuscated,Placeholder.configure({allowed:messagePlaceholders(scope).map(item=>item.token),values:()=>callbacks.current.values||{}}),BoundedContent],
    content:messageDocument(value,scope),enableInputRules:false,enablePasteRules:false,
    editorProps:{attributes:{id:inputId,role:'textbox','aria-label':label,'aria-multiline':'true',class:'message-rich-input',spellcheck:'false'}},
    onUpdate:({editor:current})=>{const next=serializeMessage(current.getJSON());emitted.current=next;callbacks.current.onChange(next);},
  });
  const ui=useEditorState({editor,selector:({editor:current})=>({bold:current.isActive('bold'),italic:current.isActive('italic'),underline:current.isActive('underline'),strike:current.isActive('strike'),obfuscated:current.isActive('obfuscated'),color:current.getAttributes('textStyle').color,selected:!current.state.selection.empty,canUndo:current.can().undo(),canRedo:current.can().redo()})})||{};
  useEffect(()=>{if(editor&&value!==emitted.current){emitted.current=value;editor.commands.setContent(messageDocument(value,scope),{emitUpdate:false});}},[editor,value,scope]);
  useEffect(()=>{setColor(normalizeColor(ui.color)||'#e4e4e7');},[ui.color]);
  const boundary=()=>{if(editor&&!editor.isDestroyed)editor.view.dispatch(closeHistory(editor.state.tr));};
  const run=command=>{if(!editor)return;boundary();command(editor.chain().focus()).run();boundary();};
  const applyColor=next=>{setColor(next);if(editor)editor.commands.setColor(next);};
  const insertPlaceholder=token=>run(chain=>chain.command(({tr,state})=>{const marks=tr.storedMarks??tr.selection.$from.marks();tr.replaceSelectionWith(state.schema.nodes.messagePlaceholder.create({token},null,marks),false);return true;}));
  const applyGradient=()=>{
    if(!editor||editor.state.selection.empty){setNotice(Date.now());return;}
    const {from:start,to:end}=editor.state.selection,ranges=[];
    editor.state.doc.nodesBetween(start,end,(node,position)=>{
      if(node.isText){let at=Math.max(start,position);const text=node.text.slice(at-position,Math.min(end,position+node.nodeSize)-position);for(const point of Array.from(text)){ranges.push([at,at+point.length]);at+=point.length;}}
      else if(node.type.name==='messagePlaceholder')ranges.push([position,position+node.nodeSize]);
    });
    if(!ranges.length){setNotice(Date.now());return;}
    boundary();const transaction=editor.state.tr,colors=gradientColors(ranges.length,from,to);
    ranges.forEach(([a,b],index)=>transaction.addMark(a,b,editor.schema.marks.textStyle.create({color:colors[index]})));
    editor.view.dispatch(transaction);boundary();editor.commands.focus();
  };
  const root=host.current?.closest('.app');
  return <div className="message-editor message-rich-editor" ref={host}>
    <div className="message-editor-heading"><label htmlFor={inputId}>{label}</label><span>{ui.selected?'已选中文字，可应用格式':'当前格式用于接下来输入的文字'}</span></div>
    <div className="message-format-toolbar" aria-label="文本格式">{[['bold','toggleBold','粗体'],['italic','toggleItalic','斜体'],['underline','toggleUnderline','下划线'],['strike','toggleStrike','删除线'],['obfuscated','toggleMark','乱码']].map(([key,command,name])=><button key={key} type="button" className={'button secondary'+(ui[key]?' active-format':'')} aria-pressed={!!ui[key]} onClick={()=>run(chain=>key==='obfuscated'?chain.toggleMark('obfuscated'):chain[command]())}>{name}</button>)}<button type="button" className="button secondary" onClick={()=>run(chain=>chain.unsetAllMarks())}>清除格式</button><span className="message-toolbar-separator"/><button type="button" className="icon-button" aria-label="撤回文本修改" title="撤回 Ctrl+Z" disabled={!ui.canUndo} onClick={()=>editor.chain().focus().undo().run()}><ArrowCounterClockwise size={16}/></button><button type="button" className="icon-button" aria-label="重做文本修改" title="重做 Ctrl+Shift+Z / Ctrl+Y" disabled={!ui.canRedo} onClick={()=>editor.chain().focus().redo().run()}><ArrowClockwise size={16}/></button></div>
    <div className="message-color-tools rich-color-toolbar"><MessageColorField label="文字颜色" value={color} onChange={applyColor} open={picker==='color'} onOpen={()=>setPicker('color')} onClose={()=>setPicker(null)} onStart={boundary} onEnd={boundary}/><MessageColorField label="渐变起色" value={from} onChange={setFrom} open={picker==='from'} onOpen={()=>setPicker('from')} onClose={()=>setPicker(null)}/><MessageColorField label="渐变末色" value={to} onChange={setTo} open={picker==='to'} onOpen={()=>setPicker('to')} onClose={()=>setPicker(null)}/><button type="button" className={'button secondary'+(!ui.selected?' needs-selection':'')} onClick={applyGradient}>应用渐变</button></div>
    <EditorContent editor={editor}/>
    <div className="rich-editor-help"><span>选中文字可分别改色或应用渐变；占位符作为一个整体着色。</span><span>Ctrl+Z 撤回</span></div>
    <details className="message-placeholder-tools"><summary><span>占位符</span><small>{messagePlaceholders(scope).length} 个</small><CaretDown size={14}/></summary><div>{messagePlaceholders(scope).map(item=><button type="button" key={item.token} title={item.label} aria-label={'插入'+item.label+' '+item.token} onClick={()=>insertPlaceholder(item.token)}><code>{item.token}</code><small>{item.label}</small></button>)}</div></details>
    <p className="quiet-note">框内直接编辑最终样式。带虚线的内容是占位符，发送时自动替换；乱码效果在此仅作示意。</p>
    {!!notice&&root&&createPortal(<div key={notice} className="editor-top-notice" role="status" aria-live="polite"><Info size={16}/><span>未选区无法添加渐变</span></div>,root)}
  </div>;
}