import test from 'node:test';
import assert from 'node:assert/strict';
import { gradientColors, messageDocument, serializeMessage } from '../src/richMessage.js';
import { parseMessage } from '../src/messageFormat.js';
import { overviewWorlds } from '../src/overviewWorlds.js';

const flatten=source=>parseMessage(source).flatMap(run=>Array.from(run.text).map(text=>({text,style:run.style})));
test('rich document round trips independent colors, styles, Unicode and existing gradients',()=>{
  const source='&l&#64D8FF你好😀&r &o<gradient:#000000:#ffffff>渐变字</gradient>&r结束';
  assert.deepEqual(flatten(serializeMessage(messageDocument(source))),flatten(source));
});
test('placeholders remain tokens for server resolution, not the sample player names',()=>{
  const source='&e{player}&r，在线 &a{online}';
  const doc=messageDocument(source,'player'),nodes=doc.content[0].content;
  assert.equal(nodes.filter(node=>node.type==='messagePlaceholder').length,2);
  assert.equal(nodes.find(node=>node.type==='messagePlaceholder').attrs.token,'{player}');
  const output=serializeMessage(doc);assert.ok(output.includes('{player}'));assert.ok(output.includes('{online}'));assert.ok(!output.includes('zhan_cpu'));
  assert.ok(!messageDocument('{player}','global').content[0].content.some(node=>node.type==='messagePlaceholder'));
});
test('literal format-like text stays literal when the existing Java format syntax is generated',()=>{
  const text='&l不是加粗 <gradient:#000000:#ffffff>原样</gradient> <script>alert(1)</script>';
  const doc={type:'doc',content:[{type:'paragraph',content:[{type:'text',text,marks:[{type:'bold'}]}]}]};
  const rendered=parseMessage(serializeMessage(doc));
  assert.equal(rendered.map(run=>run.text).join(''),text);assert.ok(rendered.every(run=>run.style.bold));
});
test('paragraphs and empty lines stay separate messages, not formatting that leaks across lines',()=>{
  const source='&l第一行\n\n&c第三行';
  const serialized=serializeMessage(messageDocument(source));
  assert.equal(serialized.split('\n').length,3);assert.equal(serialized.split('\n')[1],'');
  assert.equal(parseMessage(serialized.split('\n')[2])[0].style.bold,undefined);
});
test('selected gradients include exact endpoints and do not split Unicode code points',()=>{
  assert.deepEqual(gradientColors(Array.from('你😀好').length,'#000000','#ffffff'),['#000000','#808080','#ffffff']);
  assert.deepEqual(gradientColors(1,'#123456','#ffffff'),['#123456']);assert.deepEqual(gradientColors(0,'#123456','#ffffff'),[]);
});
test('home worlds are strictly the three vanilla IDs, never whichever worlds appear first',()=>{
  const worlds=[{code:'other:big'},{code:'minecraft:the_end'},{code:'other:space'},{code:'minecraft:overworld'},{code:'minecraft:the_nether'}];
  assert.deepEqual(overviewWorlds(worlds).map(world=>world.code),['minecraft:overworld','minecraft:the_nether','minecraft:the_end']);
  assert.deepEqual(overviewWorlds([{code:'other:big'}]),[]);
});
