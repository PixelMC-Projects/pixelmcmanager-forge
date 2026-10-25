export const TIME_WINDOWS=[{value:5*60000,label:'5 分钟'},{value:30*60000,label:'30 分钟'},{value:3600000,label:'1 小时'},{value:6*3600000,label:'6 小时'},{value:12*3600000,label:'12 小时'}];
export const LONG_TICK_DAY=24*3600000;
export function plotSamples(raw,buckets=240) {
  if(raw.length<=1200)return raw;
  const size=Math.ceil(raw.length/buckets),selected=new Map();
  for(let start=0;start<raw.length;start+=size){const end=Math.min(raw.length,start+size);let maximum=raw[start],minimum=raw[start];for(let i=start+1;i<end;i++){if(raw[i].mspt>maximum.mspt)maximum=raw[i];if(raw[i].tps<minimum.tps)minimum=raw[i];}for(const sample of [raw[start],maximum,minimum,raw[end-1]])selected.set(sample.at,sample);}
  return [...selected.values()].sort((a,b)=>a.at-b.at);
}
export function sampleStatistics(raw) {
  if(!raw.length)return {count:0,meanTps:NaN,minTps:NaN,meanMspt:NaN,p95:NaN,maximum:NaN,slow:0};
  const sorted=raw.map(row=>row.mspt).sort((a,b)=>a-b);
  return {count:raw.length,meanTps:raw.reduce((sum,row)=>sum+row.tps,0)/raw.length,minTps:raw.reduce((minimum,row)=>Math.min(minimum,row.tps),Infinity),meanMspt:raw.reduce((sum,row)=>sum+row.mspt,0)/raw.length,p95:sorted[Math.ceil(sorted.length*.95)-1],maximum:sorted.at(-1),slow:raw.filter(row=>row.mspt>50).length};
}
export class PerformanceHistory {
  constructor(capacity=43201){this.capacity=capacity;this.at=new Float64Array(capacity);this.tps=new Float64Array(capacity);this.mspt=new Float64Array(capacity);this.heap=new Float64Array(capacity);this.reset(0);}
  reset(startedAt){this.startedAt=startedAt;this.size=0;this.head=0;}
  append(sample){
    if(sample.at<this.startedAt||!Number.isFinite(sample.at)||!Number.isFinite(sample.tps)||!Number.isFinite(sample.mspt))return false;
    let index=this.head;
    if(this.size){const last=(this.head-1+this.capacity)%this.capacity;if(sample.at<this.at[last])return false;if(sample.at===this.at[last])index=last;}
    this.at[index]=sample.at;this.tps[index]=sample.tps;this.mspt[index]=sample.mspt;this.heap[index]=sample.heap;
    if(index===this.head){this.head=(this.head+1)%this.capacity;this.size=Math.min(this.capacity,this.size+1);}return true;
  }
  read(window,now){const minimum=Math.max(this.startedAt,now-window),rows=[];for(let offset=0;offset<this.size;offset++){const index=(this.head-this.size+offset+this.capacity)%this.capacity,at=this.at[index];if(at>=minimum&&at<=now)rows.push({at,tps:this.tps[index],mspt:this.mspt[index],heap:this.heap[index]});}return rows;}
  snapshot(window,now){const raw=this.read(window,now);return {plot:plotSamples(raw),stats:sampleStatistics(raw),firstAt:raw[0]?.at??now,lastAt:raw.at(-1)?.at??now};}
}
export function longTickRows(events,threshold,now,startedAt){const minimum=Math.max(startedAt,now-LONG_TICK_DAY);return events.filter(event=>event.at>=minimum&&event.at<=now&&event.duration>threshold).sort((a,b)=>b.at-a.at||String(b.id).localeCompare(String(a.id)));}
export function compactLongTicks(rows) {
  const groups=[];
  for(const event of rows){
    const eligible=event.duration>50&&event.duration<100&&Number.isSafeInteger(event.tickIndex)&&event.runId!=null,previous=groups.at(-1);
    if(eligible&&previous?.eligible&&event.runId===previous.runId&&event.tickIndex===previous.firstTick-1){previous.count++;previous.at=event.at;previous.firstTick=event.tickIndex;previous.minimum=Math.min(previous.minimum,event.duration);previous.maximum=Math.max(previous.maximum,event.duration);}
    else groups.push({...event,count:1,endAt:event.at,firstTick:event.tickIndex,minimum:event.duration,maximum:event.duration,eligible});
  }
  return groups;
}
