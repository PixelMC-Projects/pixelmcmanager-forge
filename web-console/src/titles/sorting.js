const collator=new Intl.Collator('zh-CN-u-co-pinyin',{sensitivity:'base',numeric:true});
export const plainTitle=value=>value?.name?.segments?.map(s=>s.text).join('')||'';
export function compareValue(a,b,direction='asc'){
  const missing=v=>v==null||v==='';
  if(missing(a)||missing(b))return missing(a)===missing(b)?0:missing(a)?1:-1;
  return (direction==='desc'?-1:1)*(typeof a==='number'&&typeof b==='number'?a-b:collator.compare(String(a),String(b)));
}
export function sortTitleRows(rows,sort,holders={}){
 const value=t=>({name:plainTitle(t),created:t.createdAt,holders:holders[t.id]||0,category:t.category,source:t.source,retired:t.issuable?0:1,visible:t.visible?0:1}[sort.key]);
 return [...rows].sort((a,b)=>compareValue(value(a),value(b),sort.direction)||collator.compare(plainTitle(a),plainTitle(b))||String(a.id).localeCompare(String(b.id)));
}
export function sortTitlePlayers(rows,sort,titles=[]){
 const titleMap=new Map(titles.map(t=>[t.id,plainTitle(t)]));
 const value=p=>({name:p.name,ownedCount:p.ownedCount,equipped:titleMap.get(p.equipped)||'',lastSeen:p.lastSeen}[sort.key]);
 return [...rows].sort((a,b)=>{
   const n=sort.key==='status'?(sort.direction==='desc'?1:-1)*(Number(a.online)-Number(b.online)):compareValue(value(a),value(b),sort.direction);
   return n||collator.compare(a.name,b.name)||String(a.id).localeCompare(String(b.id));
 });
}
