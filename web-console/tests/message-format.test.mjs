import test from 'node:test';
import assert from 'node:assert/strict';
import { messagePlaceholders, parseMessage, resolveMessage } from '../src/messageFormat.js';

test('announcement shortcuts expose only the four global resolver variables',()=>{
  assert.deepEqual(messagePlaceholders('global').map(item=>item.token),['{online}','{max}','{date}','{time}']);
  assert.equal(messagePlaceholders('player').length,13);
  assert.equal(resolveMessage('{player} {online} {unknown}',{online:12}),'{player} 12 {unknown}');
});
test('legacy, HEX and reset follow Style.applyFormat and retain styles until reset',()=>{
  const runs=parseMessage('&l&a绿&#123456色&r普通');
  assert.deepEqual(runs.map(run=>run.text),['绿','色','普通']);
  assert.equal(runs[0].style.bold,true);assert.equal(runs[0].style.color,'#55ff55');
  assert.equal(runs[1].style.bold,true);assert.equal(runs[1].style.color,'#123456');
  assert.deepEqual(runs[2].style,{});
});
test('gradients preserve Unicode code points, inherited styles and the outer color',()=>{
  const runs=parseMessage('&c&l<gradient:#000000:#ffffff>你😀好</gradient>后');
  assert.deepEqual(runs.map(run=>run.text),['你','😀','好','后']);
  assert.deepEqual(runs.slice(0,3).map(run=>run.style.color),['#000000','#808080','#ffffff']);
  assert.ok(runs.every(run=>run.style.bold));assert.equal(runs[3].style.color,'#ff5555');
});
test('malformed tags and unknown codes remain literal, and gradient content is not recursively parsed',()=>{
  const source='<gradient:#zzzzzz:#ffffff>abc</gradient>&z';
  assert.equal(parseMessage(source).map(run=>run.text).join(''),source);
  assert.equal(parseMessage('<gradient:000000:ffffff>&l</gradient>').map(run=>run.text).join(''),'&l');
  assert.equal(parseMessage('<script>alert(1)</script>').map(run=>run.text).join(''),'<script>alert(1)</script>');
});
