import test from 'node:test';
import assert from 'node:assert/strict';
import { DISK_REFRESH_MS, formatBytes, storageSnapshot, worldPerformance } from '../src/worldMetrics.js';
import { countdown, shutdownDelay, stopPhase } from '../src/managementData.js';

const definitions = [.77,.17,.06].map(tickWeight=>({tickWeight,storage:{terrainBytes:4000,entityBytes:200,poiBytes:100,otherBytes:50,regionFileCount:2}}));
test('dimension shares reserve non-world server costs, including during large stalls',()=>{
  for(const mspt of [1,25,50,150,1000]) {
    const metrics=worldPerformance([{at:10000,mspt}],definitions);
    assert.ok(metrics.every(row=>row.tickMs>=0&&row.tickShare>=0));
    const total=metrics.reduce((sum,row)=>sum+row.tickMs,0);
    assert.ok(total<mspt);
    assert.ok(metrics.reduce((sum,row)=>sum+row.tickShare,0)<100);
  }
});
test('rolling dimension statistics exclude expired spikes and preserve the source history',()=>{
  const history=Array.from({length:90},(_,i)=>({at:i*1000,mspt:i===1?1000:i===55?150:25}));
  const before=JSON.stringify(history),metrics=worldPerformance(history,definitions);
  assert.equal(metrics[0].sampleCount,60);
  assert.ok(metrics[0].maxSeconds60<150);
  assert.ok(metrics[0].maxSeconds60>80);
  assert.ok(metrics[0].mean60<metrics[0].maxSeconds60);
  assert.equal(JSON.stringify(history),before);
});
test('missing or invalid timings do not turn into healthy zero-time samples',()=>{
  assert.equal(worldPerformance([],definitions)[0].tickMs,null);
  assert.equal(worldPerformance([{at:1,mspt:NaN}],definitions)[0].tickMs,null);
  assert.equal(worldPerformance([{at:1,mspt:0}],definitions)[0].tickShare,null);
});
test('disk estimates remain fixed between half-hour snapshots and add each category once',()=>{
  assert.equal(DISK_REFRESH_MS,1800000);
  const start=10000000,first=storageSnapshot(definitions[0],start,start);
  assert.deepEqual(storageSnapshot(definitions[0],start+1000,start),first);
  assert.deepEqual(storageSnapshot(definitions[0],first.measuredAt+DISK_REFRESH_MS-1,start),first);
  const next=storageSnapshot(definitions[0],first.measuredAt+DISK_REFRESH_MS,start);
  assert.equal(first.totalBytes,4350);
  assert.equal(next.measuredAt-first.measuredAt,DISK_REFRESH_MS);
  assert.ok(next.totalBytes>first.totalBytes);
  assert.equal(definitions[0].storage.terrainBytes,4000);
});
test('storage formatting uses explicit binary units and distinguishes zero from unavailable',()=>{
  assert.equal(formatBytes(0),'0 B');assert.equal(formatBytes(1024**3),'1.00 GiB');
  assert.equal(formatBytes(-1),'—');assert.equal(formatBytes(NaN),'—');
});
test('shutdown inputs follow existing positive s/m/h and 24-hour limits',()=>{
  assert.equal(shutdownDelay('30','s'),30000);assert.equal(shutdownDelay('24','h'),86400000);
  for(const [amount,unit] of [['0','m'],['-1','s'],['1.2','h'],['25','h'],['abc','m'],['1','d']]) assert.equal(shutdownDelay(amount,unit),null);
});
test('shutdown waits 15 seconds after entering maintenance and never means restart',()=>{
  const plan={kickAt:60000,stopAt:75000};
  assert.equal(stopPhase(null,0),'none');assert.equal(stopPhase(plan,59999),'pending');
  assert.equal(stopPhase(plan,60000),'maintenance');assert.equal(stopPhase(plan,74999),'maintenance');
  assert.equal(stopPhase(plan,75000),'complete');assert.equal(countdown(-1),'00:00:00');
  assert.equal(countdown(3601000),'01:00:01');
});
