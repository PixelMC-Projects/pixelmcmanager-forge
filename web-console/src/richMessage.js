import { messagePlaceholders, parseMessage } from './messageFormat.js';

export function normalizeColor(value) {
  if(/^#[0-9a-f]{6}$/i.test(value||'')) return value.toLowerCase();
  const rgb=String(value||'').match(/^rgba?\(\s*(\d+)[, ]+\s*(\d+)[, ]+\s*(\d+)/i);
  return rgb?'#'+rgb.slice(1,4).map(channel=>Math.max(0,Math.min(255,Number(channel))).toString(16).padStart(2,'0')).join(''):null;
}
export function messageStyleMarks(style) {
  return [...(style.color?[{type:'textStyle',attrs:{color:style.color}}]:[]),...['bold','italic','underline','strike','obfuscated'].filter(key=>style[key]).map(type=>({type}))];
}
export function messageDocument(source,scope='global') {
  const tokens=new Set(messagePlaceholders(scope).map(item=>item.token));
  return {type:'doc',content:source.split('\n').map(line=>{
    const glyphs=parseMessage(line).flatMap(run=>Array.from(run.text).map(text=>({text,style:run.style}))),content=[];
    for(let index=0;index<glyphs.length;) {
      const start=glyphs[index],candidate=start.text==='{'?glyphs.slice(index,index+64).map(glyph=>glyph.text).join('').match(/^\{[a-zA-Z0-9_]+}/)?.[0]:null;
      const marks=messageStyleMarks(start.style);
      if(candidate&&tokens.has(candidate)){content.push({type:'messagePlaceholder',attrs:{token:candidate},...(marks.length?{marks}:{})});index+=Array.from(candidate).length;continue;}
      const last=content.at(-1);
      if(last?.type==='text'&&JSON.stringify(last.marks||[])===JSON.stringify(marks))last.text+=start.text;
      else content.push({type:'text',text:start.text,...(marks.length?{marks}:{})});
      index++;
    }
    return {type:'paragraph',...(content.length?{content}:{})};
  })};
}
function styleCode(marks=[]) {
  const color=normalizeColor(marks.find(mark=>mark.type==='textStyle')?.attrs?.color);
  return '&r'+(color?'&'+color:'')+[['bold','l'],['italic','o'],['underline','n'],['strike','m'],['obfuscated','k']].filter(([type])=>marks.some(mark=>mark.type===type)).map(([,code])=>'&'+code).join('');
}
export function serializeMessage(document) {
  return (document.content||[]).map(paragraph=>{
    let output='',previous=null;
    for(const node of paragraph.content||[]) {
      if(node.type==='hardBreak'){output+='\n';previous=null;continue;}
      const prefix=styleCode(node.marks);
      if(prefix!==previous){output+=prefix;previous=prefix;}
      if(node.type==='messagePlaceholder')output+=node.attrs.token;
      else if(node.type==='text') {
        // Break literal formatting introducers with an invisible style restoration.
        // The existing Java parser has no backslash escape syntax.
        output+=Array.from(node.text).map(character=>(character==='&'||character==='<')?character+prefix:character).join('');
      }
    }
    return output;
  }).join('\n');
}

export function gradientColors(length,from,to) {
  const first=parseInt(from.slice(1),16),last=parseInt(to.slice(1),16);
  return Array.from({length},(_,index)=>{
    const ratio=length<=1?0:Math.fround(index/(length-1));let rgb=0;
    for(const shift of [16,8,0]){const a=(first>>shift)&255,b=(last>>shift)&255;rgb|=Math.round(Math.fround(a+Math.fround((b-a)*ratio)))<<shift;}
    return '#'+rgb.toString(16).padStart(6,'0');
  });
}
