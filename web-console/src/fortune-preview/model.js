export const GRADES=['上上签','上吉签','中平签','小凶签','下下签'];
export const GRADE_KEYS=['supreme','lucky','balanced','caution','adverse'];
export const DEMO_DATE='2026-10-26';
export const DEMO_NOW=Date.parse('2026-10-26T19:42:00+08:00');
export const collator=new Intl.Collator('zh-CN-u-co-pinyin',{sensitivity:'base',numeric:true});
export const equal=(a,b)=>JSON.stringify(a)===JSON.stringify(b);
export const participates=f=>f.enabled!==false&&Number(f.weight)>0;
export const totalWeight=list=>list.reduce((n,f)=>n+(participates(f)?Number(f.weight):0),0);
export const probability=(f,list)=>totalWeight(list)?(participates(f)?Number(f.weight):0)/totalWeight(list)*100:0;
export const dateTime=value=>new Intl.DateTimeFormat('zh-CN',{timeZone:'Asia/Shanghai',year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',hour12:false}).format(new Date(value));
export const numLabel=n=>String(n).padStart(2,'0');
export function sortFortunes(rows,{key='grade',direction='asc'}={}){
  const sign=direction==='asc'?1:-1;
  return [...rows].sort((a,b)=>{
    let comparison=key==='title'?collator.compare(a.title,b.title):key==='grade'?GRADES.indexOf(a.grade)-GRADES.indexOf(b.grade):key==='probability'?(participates(a)?a.weight:0)-(participates(b)?b.weight:0):Number(a[key])-Number(b[key]);
    return comparison*sign||collator.compare(a.title,b.title)||a.id-b.id;
  });
}
export function sortProfiles(rows,{key='status',direction='asc'}={}){
  const sign=direction==='asc'?1:-1;
  return [...rows].sort((a,b)=>{
    if(key==='status')return (Number(b.online)-Number(a.online))*sign||collator.compare(a.name,b.name)||a.id.localeCompare(b.id);
    const comparison=key==='name'?collator.compare(a.name,b.name):key==='latest'?a.latest.localeCompare(b.latest):Number(a[key])-Number(b[key]);
    return comparison*sign||collator.compare(a.name,b.name)||a.id.localeCompare(b.id);
  });
}
export function monthCells(year,month){
  const offset=new Date(Date.UTC(year,month-1,1)).getUTCDay();
  const days=new Date(Date.UTC(year,month,0)).getUTCDate();
  const length=Math.max(35,Math.ceil((offset+days)/7)*7);
  return Array.from({length},(_,i)=>{const d=new Date(Date.UTC(year,month-1,1-offset+i));return {date:d.toISOString().slice(0,10),day:d.getUTCDate(),inMonth:d.getUTCMonth()===month-1};});
}
export function nextMonth(value,step){const [y,m]=value.split('-').map(Number),d=new Date(Date.UTC(y,m-1+step,1));return d.toISOString().slice(0,7);}
export function applySelectiveUndo(current,changes){
  const conflicts=changes.filter(c=>!equal(current[c.field],c.after)).map(c=>c.label||c.field);
  if(conflicts.length)return {ok:false,conflicts};
  return {ok:true,value:{...current,...Object.fromEntries(changes.map(c=>[c.field,structuredClone(c.before)]))}};
}
export function validateFortune(f,list){
  if(!f.title.trim())return '请填写签文题名。';
  if(f.poem.length!==4||f.poem.some(line=>!line.trim()))return '签诗需要四句，每句都请填写。';
  if(f.poem.some(line=>Array.from(line).length>24))return '单句过长，请控制在 24 字以内。';
  if(!f.interpretation.trim())return '请填写解签。';
  if(!Number.isFinite(Number(f.weight))||f.weight<0||f.weight>10000)return '权重请填写 0–10000 之间的数值。';
  if(totalWeight([...list.filter(x=>x.id!==f.id),f])<=0)return '至少保留一支权重大于 0 的可抽签文。';
  return '';
}
