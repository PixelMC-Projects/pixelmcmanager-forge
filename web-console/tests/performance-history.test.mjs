import test from 'node:test';
import assert from 'node:assert/strict';
import { LONG_TICK_DAY,PerformanceHistory,longTickRows,plotSamples,sampleStatistics,compactLongTicks } from '../src/performanceHistory.js';
import { detectHeapLeak,heapScenario } from '../src/heapHealth.js';
import { detectTickHealth } from '../src/tickHealth.js';
const row=(at,mspt=25,tps=20)=>({at,mspt,tps,heap:6});
test('only consecutive real tick sequence IDs in the strict 50–100ms band compact',()=>{
  const event=(tickIndex,duration=70,runId='a')=>({id:String(tickIndex),tickIndex,duration,runId,at:tickIndex*100});
  const source=[event(10,60),event(9,90),event(7,70),event(6,100),event(5,65),event(4,65,'b'),{id:'unknown',at:300,duration:60}];
  const grouped=compactLongTicks(source);
  assert.deepEqual(grouped.map(group=>group.count),[2,1,1,1,1,1]);
  assert.equal(grouped[0].minimum,60);assert.equal(grouped[0].maximum,90);assert.equal(grouped[0].at,900);assert.equal(grouped[0].endAt,1000);
  assert.equal(grouped.reduce((sum,group)=>sum+group.count,0),source.length);assert.equal(source[0].count,undefined);
});

test('history is bounded, chronological, and never exposes samples from before a new server run',()=>{
  const history=new PerformanceHistory(3);history.reset(0);
  for(let i=0;i<6;i++)history.append(row(i*1000));
  assert.deepEqual(history.read(10000,5000).map(sample=>sample.at),[3000,4000,5000]);
  history.reset(10000);assert.equal(history.size,0);assert.equal(history.append(row(9000)),false);history.append(row(10000));
  assert.deepEqual(history.read(12*3600000,11000).map(sample=>sample.at),[10000]);
  history.append(row(10000,30));assert.equal(history.size,1);assert.equal(history.read(10000,10000)[0].mspt,30);
});
test('long plots retain independent MSPT peaks and TPS troughs at their actual timestamps',()=>{
  const raw=Array.from({length:43200},(_,i)=>row(i*1000,i===12345?800:25,i===21000?4:20));
  const reduced=plotSamples(raw);assert.ok(reduced.length<=960);assert.ok(reduced.includes(raw[12345]));assert.ok(reduced.includes(raw[21000]));assert.equal(reduced[0],raw[0]);assert.equal(reduced.at(-1),raw.at(-1));
  assert.equal(sampleStatistics(raw).maximum,800);assert.equal(sampleStatistics(raw).count,43200);
});
test('statistics use raw samples instead of min/max plot buckets',()=>{
  const raw=Array.from({length:2000},(_,i)=>row(i*1000,i===999?1000:20));
  assert.equal(sampleStatistics(raw).p95,20);assert.equal(sampleStatistics(raw).slow,1);assert.equal(sampleStatistics(raw).meanMspt,20.49);
});
test('long tick filters are strict and respect both the 24-hour cutoff and session start',()=>{
  const now=2*LONG_TICK_DAY,events=[{id:'old',at:now-LONG_TICK_DAY-1,duration:500},... [50,50.1,100,100.1,200,200.1].map((duration,i)=>({id:String(i),at:now-i*1000,duration}))];
  assert.equal(longTickRows(events,50,now,0).length,5);assert.equal(longTickRows(events,100,now,0).length,3);assert.equal(longTickRows(events,200,now,0).length,1);
  assert.deepEqual(longTickRows(events,50,now,now),[]);
});
test('normal Java sawtooth growth is not classified as a memory leak',()=>{
  const now=1000000,samples=Array.from({length:601},(_,i)=>({at:now-(600-i)*1000,heap:8+(i%70)/70})),collections=[480,320,160,0].map(ago=>({at:now-ago*1000,before:9,after:8}));
  assert.equal(detectHeapLeak({samples,collections,now,maxHeap:13}).state,'normal');
});
test('rising post-GC occupancy close to the limit raises suspicion, not a proven-leak claim',()=>{
  const now=1000000,scenario=heapScenario(now,null,0,true);
  const result=detectHeapLeak({...scenario,now,maxHeap:13});assert.equal(result.state,'suspected');assert.match(result.reason,/不等于已确认/);
  assert.equal(detectHeapLeak({...scenario,now,maxHeap:30}).state,'normal');
});
test('heap health never reports green for missing, stale or pre-restart evidence',()=>{
  const now=1000000,scenario=heapScenario(now,null,0,true);
  assert.equal(detectHeapLeak({...scenario,now,maxHeap:13,stale:true}).state,'stale');
  assert.equal(detectHeapLeak({...scenario,now,maxHeap:13,startedAt:now-10000}).state,'observing');
  assert.equal(detectHeapLeak({samples:[],collections:[],now,maxHeap:13}).state,'observing');
});
test('tick health requires sustained slow ticks plus TPS loss, not isolated spikes',()=>{
  const normal=Array.from({length:20},(_,i)=>row(i*1000));
  assert.equal(detectTickHealth(normal).state,'safe');
  assert.equal(detectTickHealth([...normal,row(20000,40)]).state,'risk');
  assert.equal(detectTickHealth([...normal,row(20000,50)]).state,'risk');
  assert.equal(detectTickHealth([...normal,row(20000,100,10)]).state,'risk');
  const slow=Array.from({length:10},(_,i)=>row(i*1000,60,16));assert.equal(detectTickHealth(slow).state,'insufficient');
  assert.equal(detectTickHealth(slow.slice(0,9)).state,'risk');
  assert.equal(detectTickHealth(slow.map(sample=>({...sample,tps:20}))).state,'risk');
  assert.equal(detectTickHealth(slow,true).state,'stale');
});
