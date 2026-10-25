export function detectHeapLeak({samples,collections,maxHeap,now,startedAt=0,stale=false}) {
  if(stale)return {state:'stale',label:'未更新',reason:'未收到新数据，暂不判断内存泄漏。'};
  const points=samples.filter(row=>row.at>=Math.max(startedAt,now-600000)&&row.at<=now&&Number.isFinite(row.heap));
  const gc=collections.filter(row=>row.at>=Math.max(startedAt,now-600000)&&row.at<=now&&Number.isFinite(row.after)).sort((a,b)=>a.at-b.at);
  if(!(maxHeap>0)||points.length<60||!points.length||points.at(-1).at-points[0].at<300000||gc.length<3||gc.at(-1).at-gc[0].at<300000)return {state:'observing',label:'观察中',reason:'需要至少 5 分钟数据和多次 GC 后堆占用记录，不能把数据不足判为正常。'};
  const last=points.at(-1),recent=gc.slice(-4),rising=recent.at(-1).after-recent[0].after>=maxHeap*.03&&recent.every((row,index)=>!index||row.after>=recent[index-1].after-maxHeap*.005);
  const poorReclaim=recent.slice(-2).every(row=>Number.isFinite(row.before)&&row.before-row.after<maxHeap*.02);
  const high=last.heap/maxHeap>=.9&&recent.at(-1).after/maxHeap>=.9;
  const nearFull=points.filter(row=>row.at>=now-60000),saturated=nearFull.length>=50&&nearFull.at(-1).at-nearFull[0].at>=59000&&nearFull.every(row=>row.heap/maxHeap>=.98)&&poorReclaim;
  if((rising&&high&&poorReclaim)||saturated)return {state:'suspected',label:'疑似内存泄漏',reason:rising?'多次 GC 后的堆占用基线持续上涨，已超过堆上限的 90%，近期回收效果有限。仅为风险提示，不等于已确认泄漏。':'堆占用持续接近上限，且多次 GC 未明显释放空间。仅为风险提示，不等于已确认泄漏。'};
  return {state:'normal',label:'未发现内存泄漏',reason:'当前监测未发现持续的 GC 后占用上涨及回收无效迹象；不代表可以证明没有内存泄漏。'};
}
export function heapScenario(now,archive,startedAt,pressure=false) {
  if(pressure){
    const samples=Array.from({length:361},(_,index)=>({at:now-(360-index)*1000,heap:11.55+index/360*1.1+Math.sin(index*.1)*.015}));
    const collections=[330,220,110,0].map((ago,index)=>({at:now-ago*1000,before:11.55+index*.35+.04,after:11.55+index*.35}));
    return {samples,collections};
  }
  return {samples:archive.read(600000,now),collections:[480,320,160,0].map(ago=>({at:now-ago*1000,before:6.3,after:6.1})).filter(row=>row.at>=startedAt)};
}
