import React, { useEffect, useRef } from 'react';
import { useEditor, EditorContent, useEditorState } from '@tiptap/react';
import StarterKit from '@tiptap/starter-kit';
import { ArrowCounterClockwise, ArrowClockwise, ListBullets, TextB, TextHThree, TextT } from '@phosphor-icons/react';
import ChangelogArticle from './ChangelogArticle.jsx';
import { entryDocument, cleanDocument, documentText } from './richDocument.js';

export default function ArticleEditor({ draft, packName, onChange, disabled = false }) {
  const change=useRef(onChange); change.current=onChange;
  const editor=useEditor({
    extensions:[StarterKit.configure({heading:{levels:[3]},blockquote:false,code:false,codeBlock:false,horizontalRule:false,italic:false,link:false,orderedList:false,strike:false,underline:false,trailingNode:false})],
    content:entryDocument(draft),
    editable:!disabled,
    enableInputRules:false,
    enablePasteRules:false,
    editorProps:{attributes:{class:'hub-update-body ph-editable-body',role:'textbox','aria-label':'更新正文','aria-multiline':'true','data-placeholder':'记录新增内容、体验优化或问题修复…'}},
    onUpdate:({editor})=>{const bodyDocument=cleanDocument(editor.getJSON());change.current({bodyDocument,body:documentText(bodyDocument)});},
  });
  useEffect(()=>{editor?.setEditable(!disabled);},[editor,disabled]);
  const state=useEditorState({editor,selector:({editor})=>({bold:editor?.isActive('bold'),heading:editor?.isActive('heading'),list:editor?.isActive('bulletList'),undo:editor?.can().undo(),redo:editor?.can().redo()})});
  const tool=(label,Icon,run,active=false,disabled=false)=><button type="button" aria-label={label} aria-pressed={active} disabled={!editor||disabled||!editor.isEditable} onMouseDown={e=>e.preventDefault()} onClick={()=>run(editor.chain().focus()).run()}><Icon size={16}/></button>;
  return <div className="ph-article-editor">
    <div className="ph-writing-tools" role="toolbar" aria-label="正文格式">
      {tool('正文',TextT,chain=>chain.setParagraph(),!state?.heading&&!state?.list)}
      {tool('小标题',TextHThree,chain=>chain.toggleHeading({level:3}),state?.heading)}
      {tool('加粗',TextB,chain=>chain.toggleBold(),state?.bold)}
      {tool('项目列表',ListBullets,chain=>chain.toggleBulletList(),state?.list)}
      <span className="ph-tool-divider"/>
      {tool('撤销',ArrowCounterClockwise,chain=>chain.undo(),false,!state?.undo)}
      {tool('重做',ArrowClockwise,chain=>chain.redo(),false,!state?.redo)}
    </div>
    <div className="ph-player-preview"><ChangelogArticle entry={draft} packName={packName} bodySlot={<EditorContent editor={editor}/>}/></div>
  </div>;
}
