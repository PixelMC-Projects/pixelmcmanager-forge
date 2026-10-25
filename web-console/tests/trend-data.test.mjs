import test from 'node:test';
import assert from 'node:assert/strict';
import { reduceNoise, metricTone, tickSeries, latencyTone } from '../src/trendData.js';

test('latency colors cover every boundary without leaving 300 or 301 ms undefined',()=>{
  for(const [value,tone] of [[0,'good'],[74,'good'],[75,'caution'],[149,'caution'],[150,'hot'],[299,'hot'],[300,'critical'],[301,'critical']]) assert.equal(latencyTone(value),tone);
  assert.equal(latencyTone(0,true),'muted');assert.equal(latencyTone(-1),'muted');assert.equal(latencyTone(NaN),'muted');
});
test('suppresses minor noise while keeping the original data unchanged', () => {
  const raw = Array.from({length:300}, (_,i) => ({at:i*1000,mspt:25+Math.sin(i)*.8,tps:19.96+Math.sin(i)*.03}));
  const snapshot = JSON.stringify(raw);
  assert.ok(reduceNoise(raw,'mspt').length < 8);
  assert.ok(reduceNoise(raw,'tps').length < 8);
  assert.equal(JSON.stringify(raw),snapshot);
});
test('preserves an isolated MSPT peak and TPS drop at their actual timestamp', () => {
  const raw=Array.from({length:100},(_,i)=>({at:i*1000,mspt:i===42?115:25,tps:i===42?8:20}));
  for (const field of ['mspt','tps']) {
    const line=reduceNoise(raw,field);
    assert.ok(line.includes(raw[42]));
    assert.equal(line[0],raw[0]); assert.equal(line.at(-1),raw.at(-1));
    assert.ok(line.every((entry,i)=>!i||entry.at>line[i-1].at));
  }
});
test('retains multiple significant excursions and accepts short histories',()=>{
  const raw=Array.from({length:30},(_,i)=>({at:i,mspt:i===5?70:i===20?120:24}));
  assert.ok(reduceNoise(raw,'mspt').includes(raw[5]));
  assert.ok(reduceNoise(raw,'mspt').includes(raw[20]));
  assert.deepEqual(reduceNoise([],'mspt'),[]);
  assert.deepEqual(reduceNoise(raw.slice(0,2),'mspt'),raw.slice(0,2));
});

test('MSPT severity changes at the requested boundaries and stale data is muted',()=>{
  for(const [value,tone] of [[0,'good'],[29.99,'good'],[30,'caution'],[39.99,'caution'],[40,'hot'],[49.99,'hot'],[50,'critical'],[120,'critical']]) assert.equal(metricTone('mspt',value),tone);
  assert.equal(metricTone('mspt',10,true),'muted');
  assert.equal(metricTone('mspt',NaN),'muted');
});
test('TPS severity uses falling performance, not MSPT thresholds',()=>{
  for(const [value,tone] of [[20,'good'],[19.5,'good'],[19.49,'caution'],[18,'caution'],[17.99,'hot'],[15,'hot'],[14.99,'critical'],[0,'critical']]) assert.equal(metricTone('tps',value),tone);
});
test('the shared axis uses explicit reference units and preserves independent extrema',()=>{
  const raw=Array.from({length:40},(_,i)=>({at:i*1000,tps:i===8?7.5:20,mspt:i===25?125:25})),snapshot=JSON.stringify(raw);
  const [times,tps,mspt]=tickSeries(raw);
  assert.equal(times.length,tps.length);assert.equal(times.length,mspt.length);
  assert.equal(tps[0],100);assert.equal(mspt[0],50);
  assert.equal(tps[times.indexOf(8)],37.5);assert.equal(mspt[times.indexOf(25)],250);
  assert.equal(JSON.stringify(raw),snapshot);
  assert.deepEqual(tickSeries([]),[[],[],[]]);
});
