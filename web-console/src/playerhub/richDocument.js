// Apache-2.0. Shared, restricted document schema for the local changelog prototype.
// Legacy Markdown remains readable; new edits store a document, never executable HTML.
const inline = text => String(text).split(/(\*\*[^*]+\*\*)/g).filter(Boolean).map(part =>
  part.startsWith('**') && part.endsWith('**')
    ? { type:'text', text:part.slice(2,-2), marks:[{type:'bold'}] }
    : { type:'text', text:part });
export function legacyDocument(body = '') {
  const content = [], lines = body.split('\n');
  for (let i=0;i<lines.length;i++) {
    const line = lines[i];
    if (!line.trim()) continue;
    if (/^#{1,3} /.test(line)) { content.push({type:'heading',attrs:{level:3},content:inline(line.replace(/^#{1,3} /,''))}); continue; }
    if (/^[-*] /.test(line)) {
      const items=[];
      while(i<lines.length && /^[-*] /.test(lines[i])) { items.push({type:'listItem',content:[{type:'paragraph',content:inline(lines[i].slice(2))}]}); i++; }
      i--; content.push({type:'bulletList',content:items}); continue;
    }
    content.push({type:'paragraph',content:inline(line)});
  }
  return {type:'doc',content:content.length?content:[{type:'paragraph'}]};
}
export function cleanDocument(document) {
  let count=0;
  function clean(node, depth=0) {
    if (!node || depth>16 || ++count>30000) return null;
    if (node.type==='text') return typeof node.text==='string' && node.text ? {type:'text',text:node.text,...(node.marks?.some(m=>m.type==='bold')?{marks:[{type:'bold'}]}:{})}:null;
    if (node.type==='hardBreak') return {type:'hardBreak'};
    if (!['doc','paragraph','heading','bulletList','listItem'].includes(node.type)) return null;
    const content=(Array.isArray(node.content)?node.content:[]).map(child=>clean(child,depth+1)).filter(Boolean);
    return {type:node.type,...(node.type==='heading'?{attrs:{level:3}}:{}),...(content.length?{content}:{})};
  }
  const result=clean(document);
  return result?.type==='doc' && result.content?.length?result:{type:'doc',content:[{type:'paragraph'}]};
}
export function entryDocument(entry) { return entry.bodyDocument ? cleanDocument(entry.bodyDocument) : legacyDocument(entry.body); }
export function documentText(node) {
  if (node.type==='text') return node.text || '';
  if (node.type==='hardBreak') return '\n';
  return (node.content||[]).map(documentText).join(['doc','bulletList','listItem'].includes(node.type)?'\n':'');
}
