import test from 'node:test';
import assert from 'node:assert/strict';
import { extensionPage } from '../src/extensionPagination.js';

test('eight registered extensions occupy three bounded pages without omissions or duplication',()=>{
  const items=Array.from({length:8},(_,i)=>({id:i}));
  const pages=[0,1,2].map(page=>extensionPage(items,page));
  assert.deepEqual(pages.map(page=>page.rows.length),[3,3,2]);
  assert.deepEqual(pages.flatMap(page=>page.rows),items);
  assert.equal(pages[2].pages,3);
});
test('registry shrink clamps the visible page and an empty registry remains navigable',()=>{
  assert.deepEqual(extensionPage(['remaining'],5),{page:0,pages:1,rows:['remaining']});
  assert.deepEqual(extensionPage([],99),{page:0,pages:1,rows:[]});
  assert.deepEqual(extensionPage([1,2,3],-1).rows,[1,2,3]);
});
