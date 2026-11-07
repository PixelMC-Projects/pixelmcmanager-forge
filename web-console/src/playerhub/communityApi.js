// Authenticated administration adapter. Player-provider writes are a separate API.
import { api, query } from './api.js';

const listeners = new Set();
const notify = () => listeners.forEach(listener => listener());
const object = value => value && typeof value === 'object' && !Array.isArray(value);
function requireObject(value) { if (!object(value)) throw new Error('社区服务响应格式无效，请检查 Manager 版本。'); return value; }
function page(value, rows = 'rows', size = 100) {
  requireObject(value);
  if (!Array.isArray(value[rows]) || value[rows].length > size || !Number.isSafeInteger(value.total) || value.total < 0 || !Number.isSafeInteger(value.page) || value.page < 0 || value.size !== size || typeof value.hasMore !== 'boolean') throw new Error('社区分页响应格式无效，请检查 Manager 版本。');
  return value;
}
function profile(value, playerId) {
  requireObject(value); requireObject(value.profile);
  if (value.profile.playerId !== playerId || typeof value.profile.revision !== 'string' || !object(value.options)) throw new Error('名片响应与当前玩家不一致，请重新读取。');
  for (const key of ['backgrounds', 'patterns', 'titles', 'fortunes', 'memories']) if (!Array.isArray(value.options[key])) throw new Error('展示选项响应格式无效。');
  for (const [kind, field, identity] of [['titles','featuredTitle','id'], ['fortunes','featuredFortune','date'], ['memories','featuredMemory','id']]) {
    const selected = value.profile[field];
    if (selected && !value.options[kind].some(row => row[identity] === selected[identity])) value.options[kind] = [...value.options[kind], selected];
  }
  return value;
}
function memory(value, playerId, archiveId) {
  requireObject(value);
  if (value.playerId !== playerId || value.archiveId !== archiveId || !object(value.note) || !object(value.collections) || !Array.isArray(value.supplements) || value.supplements.length > 20 || !Number.isSafeInteger(value.supplementsTotal)) throw new Error('旅途资料响应与当前玩家或档案不一致。');
  return value;
}
async function read(path, values, signal) {
  try { return await api(query('/community' + path, values), undefined, { signal }); }
  catch (error) { if ([404, 405, 501].includes(error.status)) error.message = '当前 Manager 服务尚未提供社区管理接口，请升级对应服务。'; throw error; }
}
async function write(path, body, validate) {
  const value = validate(await api('/community' + path, body)); notify(); return value;
}
export const communityAdminApi = Object.freeze({
  subscribe(listener) { listeners.add(listener); return () => listeners.delete(listener); },
  async players(values, signal) {
    const value = page(await read(values.archiveId ? '/archive-players' : '/players', values, signal), 'rows', values.size || 20);
    if (!object(value.presenceCounts) || !['all', 'online', 'offline'].every(key => Number.isSafeInteger(value.presenceCounts[key]) && value.presenceCounts[key] >= 0)) throw new Error('玩家分类计数不可用，请升级 Manager 服务。');
    if (value.rows.some(row => !row.playerId || !object(row.profile))) throw new Error('玩家资料列表格式无效。');
    return value;
  },
  async archives(instance, index, signal) { return page(await read('/archives', { instance, page: index }, signal)); },
  async profile(playerId, signal) { return profile(await read('/profile', { playerId }, signal), playerId); },
  async profileOptions(playerId, kind, index, signal) {
    const value = page(await read('/profile-options', { playerId, kind, page: index }, signal));
    if (value.playerId !== playerId || value.kind !== kind) throw new Error('展示选项不属于当前玩家。');
    return value;
  },
  async memory(playerId, archiveId, index = 0, signal) { return memory(await read('/memory', { playerId, archiveId, page: index }, signal), playerId, archiveId); },
  async memoryOptions(playerId, archiveId, index, signal) {
    const value = page(await read('/memory-options', { playerId, archiveId, page: index }, signal));
    if (value.playerId !== playerId || value.archiveId !== archiveId) throw new Error('获得记录不属于当前玩家或档案。');
    return value;
  },
  saveProfile(body) { return write('/save-profile', body, value => profile(value, body.playerId)); },
  saveNote(body) { return write('/save-note', body, value => memory(value, body.playerId, body.archiveId)); },
  deleteNote(body) { return write('/delete-note', { ...body, confirm: true }, value => memory(value, body.playerId, body.archiveId)); },
  saveSupplement(body) { return write('/save-supplement', body, value => memory(value, body.playerId, body.archiveId)); },
  deleteSupplement(body) { return write('/delete-supplement', { ...body, confirm: true }, value => memory(value, body.playerId, body.archiveId)); },
  saveCollections(body) { return write('/save-collections', body, value => memory(value, body.playerId, body.archiveId)); },
});
