import test from 'node:test';
import assert from 'node:assert/strict';
import { avatarSources,defaultHead } from '../src/playerIdentity.js';

test('disconnect retains cached avatar identity instead of switching to a default',()=>{
  const player={id:'8d3852fb-491a-4e3f-9a81-219644608912',avatarUrl:'/avatars/cached-face.png?v=2',isOnline:true};
  assert.deepEqual(avatarSources({...player,isOnline:false}),avatarSources(player));
  assert.equal(avatarSources(player).source,player.avatarUrl);
});
test('UUID fallback survives restart and does not depend on player name',()=>{
  const id='04b8c927-c8cb-435d-9cc9-01e30f288e13';
  assert.equal(avatarSources({id,name:'old'}).source,avatarSources({id,name:'new'}).source);
  assert.equal(avatarSources({id}).source,`/api/manager/avatars/${id}.png?v=0`);
  assert.match(avatarSources({id}).fallback,/^\/default-heads\/(\d|1[0-7])\.png$/);
  assert.equal(defaultHead('invalid'),6);
});
