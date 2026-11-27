import test from 'node:test';
import assert from 'node:assert/strict';
import { communityAdminApi as api } from '../src/playerhub/communityApi.js';
import { setCsrf } from '../src/transport.js';

let received;
function response(value, status = 200) {
  globalThis.fetch = async (url, options) => { received = { url, options }; return new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } }); };
}
const player = '00000000-0000-4000-8000-000000000001';
const archive = '00000000-0000-4000-8000-000000000002';
const list = rows => ({ rows, total: rows.length, page: 0, size: 20, hasMore: false, presenceCounts: { all: rows.length, online: 0, offline: rows.length } });
const memory = () => ({ playerId: player, archiveId: archive, note: { text: '', revision: '2' }, collections: { selection: { titleEventId: null, fortuneEventId: null }, items: [], revision: '2' }, supplements: [], supplementsTotal: 0 });

test('roster sends server sort/filter/page and retains authoritative ordering', async () => {
  const rows = [{ playerId: 'z', profile: {} }, { playerId: 'a', profile: {} }]; response(list(rows));
  const value = await api.players({ search: '名字 & UUID', presence: 'offline', sort: 'collection', direction: 'desc', page: 0, size: 20 });
  const query = new URL(received.url, 'http://local.invalid').searchParams;
  assert.equal(query.get('search'), '名字 & UUID'); assert.equal(query.get('sort'), 'collection'); assert.equal(query.get('direction'), 'desc'); assert.equal(query.get('presence'), 'offline');
  assert.deepEqual(value.rows.map(row => row.playerId), ['z', 'a']); assert.equal(received.options.credentials, 'same-origin');
});
test('administrator deletion includes CSRF, explicit confirmation and exact revision', async () => {
  response(memory()); setCsrf('synthetic-test-csrf');
  await api.deleteNote({ playerId: player, archiveId: archive, expectedRevision: '2' });
  assert.equal(received.url, '/api/playerhub/community/delete-note'); assert.equal(received.options.headers['X-CSRF-Token'], 'synthetic-test-csrf');
  assert.deepEqual(JSON.parse(received.options.body), { playerId: player, archiveId: archive, expectedRevision: '2', confirm: true });
});
test('collection clearing preserves both explicit JSON null values', async () => {
  response(memory()); await api.saveCollections({ playerId: player, archiveId: archive, selection: { titleEventId: null, fortuneEventId: null }, expectedRevision: '2' });
  assert.deepEqual(JSON.parse(received.options.body).selection, { titleEventId: null, fortuneEventId: null });
});
test('selected acquisitions outside first 100 choices remain renderable', async () => {
  response({ profile: { playerId: player, revision: 'opaque', featuredFortune: { date: '2026-01-01', title: 'Selected' } }, options: { backgrounds: [], patterns: [], titles: [], fortunes: [], memories: [], fortunesHasMore: true, fortunesTotal: 125 } });
  const result = await api.profile(player); assert.equal(result.options.fortunes[0].title, 'Selected'); assert.equal(result.options.fortunesHasMore, true);
});
test('foreign response identities are rejected rather than reused', async () => {
  response({ ...memory(), playerId: 'foreign' }); await assert.rejects(api.memory(player, archive), /当前玩家或档案/);
});
test('missing older service capabilities never fall back to preview data', async () => {
  response({ error: 'not available' }, 404); await assert.rejects(api.players({ page: 0, size: 20 }), /尚未提供社区管理接口/);
});
test('missing totals and category counts cannot masquerade as empty data', async () => {
  response({ rows: [] }); await assert.rejects(api.players({ page: 0, size: 20 }), /分页响应格式无效/);
  response({ ...list([]), presenceCounts: undefined }); await assert.rejects(api.players({ page: 0, size: 20 }), /分类计数不可用/);
});
