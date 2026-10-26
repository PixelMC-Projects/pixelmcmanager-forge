import test from 'node:test';
import assert from 'node:assert/strict';
import {sortProfiles,sortFortunes,probability,monthCells,nextMonth,applySelectiveUndo,validateFortune} from '../src/fortune-preview/model.js';

test('status reversal changes groups but keeps each group alphabetic',()=>{
  const rows=[{id:'1',name:'Zoe',online:true},{id:'2',name:'Ada',online:false},{id:'3',name:'Amy',online:true},{id:'4',name:'Nana',online:false}];
  assert.deepEqual(sortProfiles(rows).map(p=>p.name),['Amy','Zoe','Ada','Nana']);
  assert.deepEqual(sortProfiles(rows,{key:'status',direction:'desc'}).map(p=>p.name),['Ada','Nana','Amy','Zoe']);
  assert.equal(rows[0].name,'Zoe');
});
test('pinyin and numeric sorting are stable without mutating input',()=>{
  const rows=[{id:10,title:'张'},{id:2,title:'白'},{id:1,title:'陈'}];
  assert.deepEqual(sortFortunes(rows,{key:'title'}).map(f=>f.title),['白','陈','张']);
  assert.deepEqual(sortFortunes(rows).map(f=>f.id),[1,2,10]);
  assert.deepEqual(rows.map(f=>f.id),[10,2,1]);
});
test('probabilities include the whole enabled pool and ignore disabled weights',()=>{
  const rows=[{weight:1},{weight:3},{weight:100,enabled:false},{weight:0}];
  assert.equal(probability(rows[0],rows),25);
  assert.equal(probability(rows[1],rows),75);
  assert.equal(probability(rows[2],rows),0);
  assert.equal(probability(rows[3],[rows[3]]),0);
});
test('calendar has 42 Sunday-first cells across month and year boundaries',()=>{
  const cells=monthCells(2026,10);
  assert.equal(cells.length,42);assert.equal(cells[0].date,'2026-09-27');
  assert.equal(cells[4].date,'2026-10-01');assert.equal(cells[4].inMonth,true);
  assert.equal(monthCells(2028,2).filter(c=>c.inMonth).length,29);
  assert.equal(nextMonth('2026-12',1),'2027-01');
  assert.equal(nextMonth('2026-01',-1),'2025-12');
});
test('selective undo preserves unrelated fields and refuses later edits',()=>{
  const change=[{field:'title',label:'题名',before:'旧题',after:'新题'}];
  assert.deepEqual(applySelectiveUndo({title:'新题',weight:3},change),{ok:true,value:{title:'旧题',weight:3}});
  assert.deepEqual(applySelectiveUndo({title:'后续题',weight:3},change),{ok:false,conflicts:['题名']});
});
test('cannot remove the last drawable fortune or accept invalid verse',()=>{
  const f={id:1,title:'灯认归人',grade:'上上签',poem:['一','二','三','四'],interpretation:'解签',weight:1,enabled:true};
  assert.equal(validateFortune(f,[f]),'');
  assert.match(validateFortune({...f,enabled:false},[f]),/至少保留/);
  assert.match(validateFortune({...f,poem:['一']},[f]),/四句/);
  assert.match(validateFortune({...f,weight:NaN},[f]),/权重/);
});
