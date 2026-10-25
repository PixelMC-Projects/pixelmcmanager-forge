import test from 'node:test';
import assert from 'node:assert/strict';
import { sortPlayers,sessionDuration } from '../src/playerSorting.js';
test('dimension sorting fixes vanilla order then uses full namespace-qualified IDs',()=>{
  const players=['zmod:a','minecraft:the_end','amod:z','minecraftpro:1','minecraft:overworld','minecraft:the_nether'].map((world,index)=>({worldCode:world,name:String(index),id:String(index)}));
  const ids=sortPlayers(players,'world').map(player=>player.worldCode);
  assert.deepEqual(ids,['minecraft:overworld','minecraft:the_nether','minecraft:the_end','amod:z','minecraftpro:1','zmod:a']);
  assert.deepEqual(sortPlayers(players,'world','desc').map(player=>player.worldCode),[...ids].reverse());
});
test('numeric sorting uses actual durations and keeps missing values last in both directions',()=>{
  const players=[{id:'a',name:'a',online:'2小时14分钟',ping:90},{id:'b',name:'b',online:'47分钟',ping:7},{id:'c',name:'c',online:'18分钟',ping:null}];
  assert.equal(sessionDuration(players[0]),8040000);assert.deepEqual(sortPlayers(players,'session').map(player=>player.id),['c','b','a']);
  assert.deepEqual(sortPlayers(players,'ping').map(player=>player.id),['b','a','c']);assert.deepEqual(sortPlayers(players,'ping','desc').map(player=>player.id),['a','b','c']);
  assert.equal(players[0].id,'a');
});
test('names compare case-insensitively with stable ties, and counters are numeric',()=>{
  const players=[{id:'z',name:'zhan_cpu',joinCount:9},{id:'a',name:'Akira',joinCount:100},{id:'b',name:'bamboo',joinCount:20}];
  assert.deepEqual(sortPlayers(players).map(player=>player.name),['Akira','bamboo','zhan_cpu']);
  assert.deepEqual(sortPlayers(players,'joinCount').map(player=>player.joinCount),[9,20,100]);
});
test('status reverses online/offline group order while names remain A-Z in each group',()=>{
  const players=[{id:'d',name:'zeta',isOnline:false},{id:'c',name:'Zed',isOnline:true},{id:'b',name:'Birch',isOnline:false},{id:'a',name:'akira',isOnline:true},{id:'e',name:'Unknown'}];
  assert.deepEqual(sortPlayers(players,'status').map(p=>p.id),['a','c','b','d','e']);
  assert.deepEqual(sortPlayers(players,'status','desc').map(p=>p.id),['b','d','a','c','e']);
  assert.equal(players[0].id,'d');
});
test('offline profiles do not supply current latency/session even with stale values',()=>{
  const players=[{id:'a',name:'a',isOnline:false,ping:1,sessionOnlineMillis:999},{id:'b',name:'b',isOnline:true,ping:50,sessionOnlineMillis:60000}];
  assert.equal(sessionDuration(players[0]),null);
  for(const direction of ['asc','desc'])assert.deepEqual(sortPlayers(players,'ping',direction).map(p=>p.id),['b','a']);
});
